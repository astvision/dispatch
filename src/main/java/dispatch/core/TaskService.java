package dispatch.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.config.Config;
import dispatch.domain.OutboxKind;
import dispatch.domain.Phase;
import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.domain.Task;
import dispatch.store.Events;
import dispatch.store.Outbox;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * What members can do, independent of the channel they use. Every method works inside the caller's transaction so
 * a channel can commit its own bookkeeping (e.g. the Telegram offset) atomically with the effect.
 */
public final class TaskService {

    private static final int TITLE_LENGTH = 80;

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
        Runs.insert(tx, new Runs.NewRun(id, 1, RunKind.PLAN, description, who.ref()), now);
        Events.record(tx, id, null, who.ref(), null, Phase.PLANNING, "created", now);
        enqueue(tx, id, OutboxKind.TASK_QUEUED, chatRef, originRef,
                Json.object().put("taskId", id).put("project", project.name()), now);
        tx.afterCommit(wakeScheduler);
        tx.afterCommit(() -> Log.info("task.created", "task", id, "project", project.name(), "requester", who.ref()));
        return CreateResult.CREATED;
    }

    /** Rejects the plan with run number {@code planSeq}; a button on an older plan is refused as stale. */
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
        enqueue(tx, taskId, OutboxKind.TASK_CANCELLED, task.chatRef(), task.originRef(),
                Json.object().put("taskId", taskId).put("by", who.name()), now);
        tx.afterCommit(() -> activeRuns.stop(taskId, ActiveRuns.StopReason.CANCELLED));
        logTransition(tx, taskId, task.phase(), Phase.CANCELLED, who.ref());
        return CancelResult.CANCELLED;
    }

    /** Posts the active tasks, oldest first. */
    public void list(Tx tx, String originRef, String chatRef) {
        ObjectNode payload = Json.object();
        ArrayNode listed = payload.putArray("tasks");
        for (Task task : Tasks.active(tx)) {
            listed.addObject()
                    .put("id", task.id())
                    .put("title", task.title())
                    .put("project", task.project())
                    .put("phase", task.phase().name())
                    .put("createdAt", task.createdAt().toString());
        }
        enqueue(tx, null, OutboxKind.TASK_LIST, chatRef, originRef, payload, clock.instant());
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
        return firstLine.length() <= TITLE_LENGTH ? firstLine : firstLine.substring(0, TITLE_LENGTH - 1) + "…";
    }
}
