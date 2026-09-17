package dispatch.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.config.Config;
import dispatch.domain.OutboxKind;
import dispatch.domain.Phase;
import dispatch.domain.Plan;
import dispatch.domain.Requester;
import dispatch.domain.Run;
import dispatch.domain.RunKind;
import dispatch.domain.RunStatus;
import dispatch.domain.Task;
import dispatch.store.Events;
import dispatch.store.Outbox;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * What members can do, independent of the channel they use. Every method works inside the caller's transaction so
 * a channel can commit its own bookkeeping (e.g. the Telegram offset) atomically with the effect.
 */
public final class TaskService {

    private static final int TITLE_LENGTH = 80;
    private static final int HISTORY_SIZE = 10;
    private static final int INSTRUCTION_LENGTH = 200;

    private final Members members;
    private final Projects projects;
    private final ActiveRuns activeRuns;
    private final Clock clock;
    private final Runnable wakeScheduler;
    private final Runnable wakeOutbox;

    public TaskService(Members members, Projects projects, ActiveRuns activeRuns, Clock clock, Runnable wakeScheduler,
                       Runnable wakeOutbox) {
        this.members = members;
        this.projects = projects;
        this.activeRuns = activeRuns;
        this.clock = clock;
        this.wakeScheduler = wakeScheduler;
        this.wakeOutbox = wakeOutbox;
    }

    /** Whether {@code requesterRef} is on the team's allowlist, for channels that serve members only (e.g. private chats). */
    public boolean isMember(String requesterRef) {
        return members.contains(requesterRef);
    }

    /**
     * @param originRef channel reference of the command message; the task's replies thread under it
     * @param chatRef   channel reference of the chat
     */
    public CreateResult create(Tx tx, Requester who, String projectKey, String text, String originRef, String chatRef) {
        Instant now = clock.instant();
        if (Tasks.existsWithOrigin(tx, originRef)) {
            tx.afterCommit(() -> Log.info("task.duplicate_ignored", "origin", originRef));
            return CreateResult.DUPLICATE;
        }
        if (!members.contains(who.ref())) {
            notAllowed(tx, who, originRef, chatRef, now);
            return CreateResult.NOT_ALLOWED;
        }
        if (projectKey == null || projectKey.isBlank()) {
            enqueue(tx, null, OutboxKind.TASK_USAGE, chatRef, originRef, Json.object(), now);
            return CreateResult.EMPTY;
        }
        Optional<Config.Project> found = projects.find(projectKey);
        if (found.isEmpty()) {
            ObjectNode payload = Json.object().put("given", projectKey);
            ArrayNode known = payload.putArray("projects");
            projects.all().forEach(project -> known.addObject().put("name", project.name()).put("alias", project.alias()));
            enqueue(tx, null, OutboxKind.UNKNOWN_PROJECT, chatRef, originRef, payload, now);
            return CreateResult.UNKNOWN_PROJECT;
        }
        Config.Project project = found.get();
        Optional<String> unavailable = projects.unavailableReason(project);
        if (unavailable.isPresent()) {
            enqueue(tx, null, OutboxKind.PROJECT_UNAVAILABLE, chatRef, originRef,
                    Json.object().put("project", project.name()).put("reason", unavailable.get()), now);
            return CreateResult.PROJECT_UNAVAILABLE;
        }
        if (text == null || text.isBlank()) {
            enqueue(tx, null, OutboxKind.TASK_USAGE, chatRef, originRef, Json.object(), now);
            return CreateResult.EMPTY;
        }

        String description = text.strip();
        long id = Tasks.insert(tx, new Tasks.NewTask(project.name(), title(description), description, who, originRef, chatRef,
                UUID.randomUUID(), project.baseBranch()), Phase.PLANNING, now);
        Runs.insert(tx, new Runs.NewRun(id, 1, RunKind.PLAN, description, who), now);
        Events.record(tx, id, null, who.ref(), null, Phase.PLANNING, "created", now);
        enqueue(tx, id, OutboxKind.TASK_QUEUED, chatRef, originRef,
                Json.object().put("taskId", id).put("project", project.name()).put("requester", who.name()), now);
        tx.afterCommit(wakeScheduler);
        tx.afterCommit(() -> Log.info("task.created", "task", id, "project", project.name(), "requester", who.ref()));
        return CreateResult.CREATED;
    }

    /**
     * Approves the plan with run number {@code planSeq} and queues its execution (ADR 0006). Only the requester decides on
     * their plan (ADR 0011). A plan with open questions cannot be approved: the answers come as replies, i.e. corrections.
     */
    public ApproveResult approve(Tx tx, Requester who, long taskId, int planSeq) {
        Instant now = clock.instant();
        if (!members.contains(who.ref())) {
            tx.afterCommit(() -> Log.warn("task.approve_not_allowed", "task", taskId, "requester", who.ref()));
            return ApproveResult.NOT_ALLOWED;
        }
        Optional<Task> found = Tasks.find(tx, taskId);
        if (found.isEmpty()) {
            return ApproveResult.NOT_FOUND;
        }
        Task task = found.get();
        if (!task.requester().ref().equals(who.ref())) {
            tx.afterCommit(() -> Log.info("task.approve_not_requester", "task", taskId, "requester", who.ref()));
            return ApproveResult.NOT_REQUESTER;
        }
        if (task.phase() != Phase.AWAITING_APPROVAL) {
            return ApproveResult.WRONG_STATE;
        }
        OptionalInt latestPlan = Runs.latestSucceededPlanSeq(tx, taskId);
        if (latestPlan.isEmpty() || latestPlan.getAsInt() != planSeq) {
            return ApproveResult.STALE_PLAN;
        }
        if (!Plan.parse(task.planJson()).questions().isEmpty()) {
            return ApproveResult.OPEN_QUESTIONS;
        }
        if (!Tasks.changePhase(tx, taskId, Phase.AWAITING_APPROVAL, Phase.EXECUTING, now)) {
            return ApproveResult.WRONG_STATE;
        }
        // The run carries the plan it implements, so what was approved stays on record.
        Runs.insert(tx, new Runs.NewRun(taskId, Runs.nextSeq(tx, taskId), RunKind.EXECUTE, task.planJson(), who), now);
        Events.record(tx, taskId, null, who.ref(), Phase.AWAITING_APPROVAL, Phase.EXECUTING, "approved plan " + planSeq, now);
        Outbox.enqueueForRequester(tx, task, OutboxKind.EXECUTION_QUEUED,
                Json.object().put("taskId", taskId).put("by", who.name()), now);
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(wakeScheduler);
        logTransition(tx, taskId, Phase.AWAITING_APPROVAL, Phase.EXECUTING, who.ref());
        return ApproveResult.APPROVED;
    }

    /**
     * The requester's reply to the plan with run number {@code planSeq}: the task is planned again in the same session,
     * with the reply as the run's instruction. Refusals, including replies from other members, are answered under the reply.
     *
     * @param originRef channel reference of the reply
     */
    public CorrectResult correct(Tx tx, Requester who, long taskId, int planSeq, String text, String originRef, String chatRef) {
        Instant now = clock.instant();
        if (!members.contains(who.ref())) {
            notAllowed(tx, who, originRef, chatRef, now);
            return CorrectResult.NOT_ALLOWED;
        }
        if (text == null || text.isBlank()) {
            return CorrectResult.EMPTY;
        }
        Task task = Tasks.find(tx, taskId).orElseThrow(() -> new IllegalStateException("task " + taskId + " does not exist"));
        if (!task.requester().ref().equals(who.ref())) {
            enqueue(tx, taskId, OutboxKind.CORRECTION_REFUSED, chatRef, originRef, Json.object().put("taskId", taskId)
                    .put("reason", "requester").put("requester", task.requester().name()), now);
            return CorrectResult.REFUSED;
        }
        if (task.phase() != Phase.AWAITING_APPROVAL) {
            enqueue(tx, taskId, OutboxKind.CORRECTION_REFUSED, chatRef, originRef,
                    Json.object().put("taskId", taskId).put("reason", "phase").put("phase", task.phase().name()), now);
            return CorrectResult.REFUSED;
        }
        OptionalInt latestPlan = Runs.latestSucceededPlanSeq(tx, taskId);
        if (latestPlan.isEmpty() || latestPlan.getAsInt() != planSeq
                || !Tasks.changePhase(tx, taskId, Phase.AWAITING_APPROVAL, Phase.PLANNING, now)) {
            enqueue(tx, taskId, OutboxKind.CORRECTION_REFUSED, chatRef, originRef,
                    Json.object().put("taskId", taskId).put("reason", "stale"), now);
            return CorrectResult.REFUSED;
        }
        int seq = Runs.nextSeq(tx, taskId);
        Runs.insert(tx, new Runs.NewRun(taskId, seq, RunKind.PLAN, text.strip(), who), now);
        Events.record(tx, taskId, null, who.ref(), Phase.AWAITING_APPROVAL, Phase.PLANNING, "correction", now);
        enqueue(tx, taskId, OutboxKind.CORRECTION_QUEUED, chatRef, originRef,
                Json.object().put("taskId", taskId).put("by", who.name()), now);
        tx.afterCommit(wakeScheduler);
        logTransition(tx, taskId, Phase.AWAITING_APPROVAL, Phase.PLANNING, who.ref());
        return CorrectResult.CORRECTED;
    }

    /** The requester rejects the plan with run number {@code planSeq}; a button on an older plan is refused as stale. */
    public RejectResult reject(Tx tx, Requester who, long taskId, int planSeq) {
        Instant now = clock.instant();
        if (!members.contains(who.ref())) {
            tx.afterCommit(() -> Log.warn("task.reject_not_allowed", "task", taskId, "requester", who.ref()));
            return RejectResult.NOT_ALLOWED;
        }
        Optional<Task> found = Tasks.find(tx, taskId);
        if (found.isEmpty()) {
            return RejectResult.NOT_FOUND;
        }
        Task task = found.get();
        if (!task.requester().ref().equals(who.ref())) {
            tx.afterCommit(() -> Log.info("task.reject_not_requester", "task", taskId, "requester", who.ref()));
            return RejectResult.NOT_REQUESTER;
        }
        if (task.phase() != Phase.AWAITING_APPROVAL) {
            return RejectResult.WRONG_STATE;
        }
        OptionalInt latestPlan = Runs.latestSucceededPlanSeq(tx, taskId);
        if (latestPlan.isEmpty() || latestPlan.getAsInt() != planSeq) {
            return RejectResult.STALE_PLAN;
        }
        if (!Tasks.changePhase(tx, taskId, Phase.AWAITING_APPROVAL, Phase.REJECTED, now)) {
            return RejectResult.WRONG_STATE;
        }
        Events.record(tx, taskId, null, who.ref(), Phase.AWAITING_APPROVAL, Phase.REJECTED, "rejected", now);
        enqueue(tx, taskId, OutboxKind.TASK_REJECTED, task.chatRef(), task.originRef(),
                Json.object().put("taskId", taskId).put("by", who.name()), now);
        logTransition(tx, taskId, Phase.AWAITING_APPROVAL, Phase.REJECTED, who.ref());
        return RejectResult.REJECTED;
    }

    /** Cancels an active task; a running agent is stopped after commit and its run ends as CANCELLED. */
    public CancelResult cancel(Tx tx, Requester who, long taskId, String originRef, String chatRef) {
        Instant now = clock.instant();
        if (!members.contains(who.ref())) {
            notAllowed(tx, who, originRef, chatRef, now);
            return CancelResult.NOT_ALLOWED;
        }
        Optional<Task> found = Tasks.find(tx, taskId);
        if (found.isEmpty()) {
            enqueue(tx, null, OutboxKind.TASK_NOT_FOUND, chatRef, originRef, Json.object().put("taskId", taskId), now);
            return CancelResult.NOT_FOUND;
        }
        Task task = found.get();
        if (!task.phase().isActive() || !Tasks.changePhase(tx, taskId, task.phase(), Phase.CANCELLED, now)) {
            enqueue(tx, taskId, OutboxKind.CANCEL_REFUSED, chatRef, originRef,
                    Json.object().put("taskId", taskId).put("phase", task.phase().name()), now);
            return CancelResult.REFUSED;
        }
        Runs.cancelQueued(tx, taskId, now);
        Events.record(tx, taskId, null, who.ref(), task.phase(), Phase.CANCELLED, "cancelled", now);
        ObjectNode cancelled = Json.object().put("taskId", taskId).put("by", who.name());
        enqueue(tx, taskId, OutboxKind.TASK_CANCELLED, task.chatRef(), task.originRef(), cancelled, now);
        if (!chatRef.equals(task.chatRef())) {
            // Sent from a private chat: answer there too, not only in the group.
            enqueue(tx, taskId, OutboxKind.TASK_CANCELLED, chatRef, originRef, cancelled, now);
        }
        tx.afterCommit(() -> activeRuns.stop(taskId, ActiveRuns.StopReason.CANCELLED));
        logTransition(tx, taskId, task.phase(), Phase.CANCELLED, who.ref());
        return CancelResult.CANCELLED;
    }

    /** Posts what Dispatch is doing now: running runs with their agent's latest action, then queued runs, then plans awaiting approval. */
    public void status(Tx tx, String originRef, String chatRef) {
        ObjectNode payload = Json.object();
        ArrayNode running = payload.putArray("running");
        ArrayNode queued = payload.putArray("queued");
        for (Runs.InProgress run : Runs.inProgress(tx)) {
            boolean isRunning = run.status() == RunStatus.RUNNING;
            ObjectNode item = (isRunning ? running : queued).addObject().put("taskId", run.taskId()).put("project", run.project())
                    .put("title", run.title()).put("kind", run.kind().name());
            if (isRunning) {
                item.put("startedAt", text(run.startedAt()));
                activeRuns.activity(run.taskId()).ifPresent(activity ->
                        item.put("steps", activity.steps()).put("lastAction", activity.lastAction()));
            } else {
                item.put("queuedAt", text(run.queuedAt()));
            }
        }
        ArrayNode awaiting = payload.putArray("awaitingApproval");
        for (Task task : Tasks.withPhase(tx, Phase.AWAITING_APPROVAL)) {
            awaiting.addObject().put("taskId", task.id()).put("project", task.project()).put("title", task.title())
                    .put("requester", task.requester().name()).put("since", text(task.updatedAt()));
        }
        enqueue(tx, null, OutboxKind.STATUS, chatRef, originRef, payload, clock.instant());
    }

    /** Posts the most recently finished tasks, newest first, with their total cost. */
    public void history(Tx tx, String originRef, String chatRef) {
        List<Task> finished = Tasks.finished(tx, HISTORY_SIZE);
        Map<Long, BigDecimal> costs = Runs.costs(tx, finished.stream().map(Task::id).toList());
        ObjectNode payload = Json.object();
        ArrayNode listed = payload.putArray("tasks");
        for (Task task : finished) {
            BigDecimal cost = costs.get(task.id());
            listed.addObject().put("taskId", task.id()).put("project", task.project()).put("title", task.title())
                    .put("phase", task.phase().name()).put("prUrl", task.prUrl()).put("failureReason", name(task.failureReason()))
                    .put("costUsd", cost == null ? null : cost.toPlainString()).put("completedAt", text(task.completedAt()));
        }
        enqueue(tx, null, OutboxKind.HISTORY, chatRef, originRef, payload, clock.instant());
    }

    /** Posts one task's timeline: its runs in order, how it ended, and what it cost. */
    public void timeline(Tx tx, long taskId, String originRef, String chatRef) {
        Optional<Task> found = Tasks.find(tx, taskId);
        if (found.isEmpty()) {
            enqueue(tx, null, OutboxKind.TASK_NOT_FOUND, chatRef, originRef, Json.object().put("taskId", taskId), clock.instant());
            return;
        }
        Task task = found.get();
        ObjectNode payload = Json.object().put("taskId", task.id()).put("project", task.project()).put("title", task.title())
                .put("requester", task.requester().name()).put("phase", task.phase().name()).put("prUrl", task.prUrl())
                .put("failureReason", name(task.failureReason())).put("createdAt", text(task.createdAt()))
                .put("completedAt", text(task.completedAt()));
        ArrayNode runs = payload.putArray("runs");
        BigDecimal total = null;
        for (Run run : Runs.forTask(tx, taskId)) {
            // Only a correction's instruction is worth showing: the first plan's is the task, an execution's is the plan.
            String instruction = run.kind() == RunKind.PLAN && run.seq() > 1 ? truncate(run.instruction(), INSTRUCTION_LENGTH) : null;
            runs.addObject().put("seq", run.seq()).put("kind", run.kind().name()).put("status", run.status().name())
                    .put("requestedBy", run.requestedByName()).put("instruction", instruction).put("queuedAt", text(run.queuedAt()))
                    .put("startedAt", text(run.startedAt())).put("finishedAt", text(run.finishedAt()))
                    .put("costUsd", run.costUsd() == null ? null : run.costUsd().toPlainString())
                    .put("failureReason", name(run.failureReason()));
            if (run.costUsd() != null) {
                total = total == null ? run.costUsd() : total.add(run.costUsd());
            }
        }
        payload.put("costUsd", total == null ? null : total.toPlainString());
        enqueue(tx, null, OutboxKind.TASK_TIMELINE, chatRef, originRef, payload, clock.instant());
    }

    private void notAllowed(Tx tx, Requester who, String originRef, String chatRef, Instant now) {
        enqueue(tx, null, OutboxKind.NOT_ALLOWED, chatRef, originRef, Json.object().put("name", who.name()), now);
        tx.afterCommit(() -> Log.warn("member.not_allowed", "requester", who.ref(), "name", who.name()));
    }

    private void enqueue(Tx tx, Long taskId, OutboxKind kind, String chatRef, String replyToRef, ObjectNode payload, Instant now) {
        Outbox.enqueue(tx, taskId, kind, chatRef, replyToRef, payload, now);
        tx.afterCommit(wakeOutbox);
    }

    private static void logTransition(Tx tx, long taskId, Phase from, Phase to, String actor) {
        tx.afterCommit(() -> Log.info("task.transition", "task", taskId, "from", from, "to", to, "actor", actor));
    }

    /** First non-blank line; long lines are cut rather than summarized. */
    static String title(String description) {
        String firstLine = description.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst().orElse("");
        return truncate(firstLine, TITLE_LENGTH);
    }

    private static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String name(Enum<?> constant) {
        return constant == null ? null : constant.name();
    }
}
