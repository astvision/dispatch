package dispatch.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.config.Config;
import dispatch.domain.Draft;
import dispatch.domain.OutboxKind;
import dispatch.domain.Phase;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.Run;
import dispatch.domain.RunKind;
import dispatch.domain.RunStatus;
import dispatch.domain.Task;
import dispatch.store.Drafts;
import dispatch.store.Events;
import dispatch.store.Outbox;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * What members can do, independent of the channel they use. Every method works inside the caller's transaction so
 * a channel can commit its own bookkeeping (e.g. the Telegram offset) atomically with the effect.
 */
public final class TaskService {

    private static final int TITLE_LENGTH = 80;
    private static final int HISTORY_SIZE = 10;
    private static final int INSTRUCTION_LENGTH = 200;

    private final Groups groups;
    private final Projects projects;
    private final ActiveRuns activeRuns;
    private final Clock clock;
    private final Runnable wakeScheduler;
    private final Runnable wakeOutbox;

    public TaskService(Groups groups, Projects projects, ActiveRuns activeRuns, Clock clock, Runnable wakeScheduler,
                       Runnable wakeOutbox) {
        this.groups = groups;
        this.projects = projects;
        this.activeRuns = activeRuns;
        this.clock = clock;
        this.wakeScheduler = wakeScheduler;
        this.wakeOutbox = wakeOutbox;
    }

    /**
     * A member's private message becomes a draft; its prompt asks for the project, unless the member can use only one or
     * named one, and for the priority (ADR 0012).
     *
     * @param projectKey a project the member named, null if none; one they cannot use is ignored and asked for instead
     * @param originRef  the message in the member's private chat
     */
    public DraftResult draft(Tx tx, Requester who, String projectKey, String text, String originRef) {
        Instant now = clock.instant();
        String chatRef = who.ref();
        if (Drafts.existsWithOrigin(tx, originRef)) {
            tx.afterCommit(() -> Log.info("draft.duplicate_ignored", "origin", originRef));
            return DraftResult.DUPLICATE;
        }
        if (!groups.isMember(who.ref())) {
            notAllowed(tx, who, originRef, chatRef, now);
            return DraftResult.NOT_ALLOWED;
        }
        String description = text == null ? "" : text.strip();
        if (description.isEmpty()) {
            enqueue(tx, null, OutboxKind.TASK_USAGE, chatRef, originRef, Json.object(), now);
            return DraftResult.EMPTY;
        }
        List<Config.Project> offered = offeredProjects(who.ref());
        if (offered.isEmpty()) {
            enqueue(tx, null, OutboxKind.NO_PROJECTS, chatRef, originRef, Json.object(), now);
            return DraftResult.NO_PROJECTS;
        }
        Optional<Config.Project> named = projectKey == null ? Optional.empty() : projects.find(projectKey).filter(offered::contains);
        String project = named.map(Config.Project::name).orElse(offered.size() == 1 ? offered.getFirst().name() : null);
        long id = Drafts.insert(tx, new Drafts.NewDraft(who, chatRef, originRef, description, project), now);
        enqueue(tx, null, OutboxKind.DRAFT_PROMPT, chatRef, originRef, draftPayload(tx, id).orElseThrow(), now);
        tx.afterCommit(() -> Log.info("draft.created", "draft", id, "requester", who.ref(), "project", project));
        return DraftResult.DRAFTED;
    }

    public DraftChoice chooseProject(Tx tx, Requester who, long draftId, String project) {
        Optional<Draft> found = Drafts.find(tx, draftId);
        Optional<DraftChoice> refused = refusal(found, who);
        if (refused.isPresent()) {
            return refused.get();
        }
        if (offeredProjects(who.ref()).stream().noneMatch(candidate -> candidate.name().equals(project))) {
            return DraftChoice.PROJECT_UNAVAILABLE;
        }
        Drafts.chooseProject(tx, draftId, project, clock.instant());
        return DraftChoice.PROJECT_CHOSEN;
    }

    /** Choosing the priority gives the task, provided the project is chosen and still one the member can use. */
    public DraftChoice choosePriority(Tx tx, Requester who, long draftId, Priority priority) {
        Instant now = clock.instant();
        Optional<Draft> found = Drafts.find(tx, draftId);
        Optional<DraftChoice> refused = refusal(found, who);
        if (refused.isPresent()) {
            return refused.get();
        }
        Draft draft = found.get();
        if (draft.project() == null) {
            return DraftChoice.CHOOSE_PROJECT_FIRST;
        }
        Optional<Config.Project> project = offeredProjects(who.ref()).stream()
                .filter(candidate -> candidate.name().equals(draft.project())).findFirst();
        if (project.isEmpty()) {
            return DraftChoice.PROJECT_UNAVAILABLE;
        }
        long taskId = insertTask(tx, who, project.get(), draft.description(), priority, draft.originRef(), now);
        Drafts.created(tx, draftId, taskId, now);
        return DraftChoice.CREATED;
    }

    /** What a draft's prompt shows: while open, the projects to choose from; once created, the task. */
    public Optional<ObjectNode> draftPayload(Tx tx, long draftId) {
        return Drafts.find(tx, draftId).map(draft -> {
            ObjectNode payload = Json.object().put("draftId", draft.id()).put("title", title(draft.description()))
                    .put("status", draft.status().name()).put("project", draft.project()).put("taskId", draft.taskId());
            ArrayNode listed = payload.putArray("projects");
            offeredProjects(draft.requesterRef())
                    .forEach(project -> listed.addObject().put("name", project.name()).put("alias", project.alias()));
            payload.put("priority", draft.taskId() == null ? null
                    : Tasks.find(tx, draft.taskId()).map(task -> task.priority().name()).orElse(null));
            return payload;
        });
    }

    /** Closes drafts created before {@code createdBefore} that nobody answered, telling their writers. */
    public int expireDrafts(Tx tx, Instant createdBefore) {
        Instant now = clock.instant();
        List<Draft> stale = Drafts.openCreatedBefore(tx, createdBefore);
        for (Draft draft : stale) {
            Drafts.expire(tx, draft.id(), now);
            enqueue(tx, null, OutboxKind.DRAFT_EXPIRED, draft.chatRef(), draft.originRef(), Json.object().put("draftId", draft.id()), now);
        }
        if (!stale.isEmpty()) {
            tx.afterCommit(() -> Log.info("draft.expired", "count", stale.size()));
        }
        return stale.size();
    }

    /**
     * Gives a task whose project and priority are already known, as a draft's buttons do in the end (ADR 0012). Replies go
     * to the requester's private chat under {@code originRef}; the project's group gets a one-line announcement.
     *
     * @param originRef the message in the requester's private chat that gave the task
     */
    public CreateResult create(Tx tx, Requester who, String projectKey, String text, Priority priority, String originRef) {
        Instant now = clock.instant();
        String chatRef = who.ref();
        if (Tasks.existsWithOrigin(tx, originRef)) {
            tx.afterCommit(() -> Log.info("task.duplicate_ignored", "origin", originRef));
            return CreateResult.DUPLICATE;
        }
        if (!groups.isMember(who.ref())) {
            notAllowed(tx, who, originRef, chatRef, now);
            return CreateResult.NOT_ALLOWED;
        }
        if (projectKey == null || projectKey.isBlank()) {
            enqueue(tx, null, OutboxKind.TASK_USAGE, chatRef, originRef, Json.object(), now);
            return CreateResult.EMPTY;
        }
        Set<String> mine = groups.projectsOfMember(who.ref());
        Optional<Config.Project> found = projects.find(projectKey).filter(project -> mine.contains(project.name()));
        if (found.isEmpty()) {
            ObjectNode payload = Json.object().put("given", projectKey);
            ArrayNode known = payload.putArray("projects");
            projects.all().stream().filter(project -> mine.contains(project.name()))
                    .forEach(project -> known.addObject().put("name", project.name()).put("alias", project.alias()));
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
        insertTask(tx, who, project, text.strip(), priority, originRef, now);
        return CreateResult.CREATED;
    }

    private long insertTask(Tx tx, Requester who, Config.Project project, String description, Priority priority, String originRef,
                            Instant now) {
        String groupChat = groups.chatOfProject(project.name());
        long id = Tasks.insert(tx, new Tasks.NewTask(project.name(), title(description), description, who, originRef, groupChat,
                UUID.randomUUID(), project.baseBranch(), priority), Phase.PLANNING, now);
        Runs.insert(tx, new Runs.NewRun(id, 1, RunKind.PLAN, description, who), now);
        Events.record(tx, id, null, who.ref(), null, Phase.PLANNING, "created", now);
        enqueue(tx, id, OutboxKind.TASK_QUEUED, groupChat, null, Json.object().put("taskId", id).put("project", project.name())
                .put("requester", who.name()).put("priority", priority.name()).put("title", title(description)), now);
        tx.afterCommit(wakeScheduler);
        tx.afterCommit(() -> Log.info("task.created", "task", id, "project", project.name(), "priority", priority,
                "requester", who.ref()));
        return id;
    }

    /** A draft can be answered only by its writer, and only while it is open. */
    private static Optional<DraftChoice> refusal(Optional<Draft> found, Requester who) {
        if (found.isEmpty()) {
            return Optional.of(DraftChoice.NOT_FOUND);
        }
        Draft draft = found.get();
        if (!draft.requesterRef().equals(who.ref())) {
            return Optional.of(DraftChoice.NOT_REQUESTER);
        }
        return switch (draft.status()) {
            case OPEN -> Optional.empty();
            case CREATED -> Optional.of(DraftChoice.ALREADY_CREATED);
            case EXPIRED -> Optional.of(DraftChoice.EXPIRED);
        };
    }

    /** The member's projects that can take tasks now, in config order. */
    private List<Config.Project> offeredProjects(String requesterRef) {
        Set<String> mine = groups.projectsOfMember(requesterRef);
        return projects.all().stream()
                .filter(project -> mine.contains(project.name()) && projects.unavailableReason(project).isEmpty())
                .toList();
    }

    /**
     * Approves the plan with run number {@code planSeq} and queues its execution (ADR 0006). Only the requester decides on
     * their plan (ADR 0011). A plan with open questions cannot be approved: the answers come as replies, i.e. corrections.
     */
    public ApproveResult approve(Tx tx, Requester who, long taskId, int planSeq) {
        Instant now = clock.instant();
        if (!groups.isMember(who.ref())) {
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
        if (!groups.isMember(who.ref())) {
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
        if (!groups.isMember(who.ref())) {
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
        enqueue(tx, taskId, OutboxKind.TASK_REJECTED, task.chatRef(), task.groupOriginRef(),
                Json.object().put("taskId", taskId).put("by", who.name()), now);
        logTransition(tx, taskId, Phase.AWAITING_APPROVAL, Phase.REJECTED, who.ref());
        return RejectResult.REJECTED;
    }

    /** The requester moves their unfinished task up or down the queue; a running agent is not affected (ADR 0012). */
    public PriorityResult changePriority(Tx tx, Requester who, long taskId, Priority priority) {
        Instant now = clock.instant();
        if (!groups.isMember(who.ref())) {
            return PriorityResult.NOT_ALLOWED;
        }
        Optional<Task> found = Tasks.find(tx, taskId);
        if (found.isEmpty()) {
            return PriorityResult.NOT_FOUND;
        }
        Task task = found.get();
        if (!task.requester().ref().equals(who.ref())) {
            return PriorityResult.NOT_REQUESTER;
        }
        if (!task.phase().isActive()) {
            return PriorityResult.FINISHED;
        }
        if (task.priority() == priority) {
            return PriorityResult.UNCHANGED;
        }
        if (!Tasks.changePriority(tx, taskId, priority, now)) {
            return PriorityResult.FINISHED;
        }
        String change = "priority " + task.priority() + " -> " + priority;
        Events.record(tx, taskId, null, who.ref(), task.phase(), task.phase(), change, now);
        tx.afterCommit(() -> Log.info("task.priority_changed", "task", taskId, "from", task.priority(), "to", priority,
                "actor", who.ref()));
        return PriorityResult.CHANGED;
    }

    /**
     * Cancels an active task of the member's groups, or one they requested; a running agent is stopped after commit and its
     * run ends as CANCELLED. Another group's task is answered as not found, so its existence does not leak.
     */
    public CancelResult cancel(Tx tx, Requester who, long taskId, String originRef, String chatRef) {
        Instant now = clock.instant();
        if (!groups.isMember(who.ref())) {
            notAllowed(tx, who, originRef, chatRef, now);
            return CancelResult.NOT_ALLOWED;
        }
        Optional<Task> found = Tasks.find(tx, taskId).filter(task ->
                task.requester().ref().equals(who.ref()) || groups.isMemberOfProjectGroup(who.ref(), task.project()));
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
        enqueue(tx, taskId, OutboxKind.TASK_CANCELLED, task.chatRef(), task.groupOriginRef(), cancelled, now);
        if (!chatRef.equals(task.chatRef())) {
            // Sent from a private chat: answer there too, not only in the group.
            enqueue(tx, taskId, OutboxKind.TASK_CANCELLED, chatRef, originRef, cancelled, now);
        }
        tx.afterCommit(() -> activeRuns.stop(taskId, ActiveRuns.StopReason.CANCELLED));
        logTransition(tx, taskId, task.phase(), Phase.CANCELLED, who.ref());
        return CancelResult.CANCELLED;
    }

    /**
     * Posts what Dispatch is doing on {@code visibleProjects}: running runs with their agent's latest action, then queued
     * runs, then plans awaiting approval.
     *
     * @param viewerRef the member asking in their private chat, whose active tasks get priority buttons; null in a group
     */
    public void status(Tx tx, Set<String> visibleProjects, String viewerRef, String originRef, String chatRef) {
        enqueue(tx, null, OutboxKind.STATUS, chatRef, originRef, statusPayload(tx, visibleProjects, viewerRef), clock.instant());
    }

    /** The content of a status message, also used to update one in place after a priority change. */
    public ObjectNode statusPayload(Tx tx, Set<String> visibleProjects, String viewerRef) {
        ObjectNode payload = Json.object();
        Map<Long, Task> active = new LinkedHashMap<>();
        for (Task task : Tasks.active(tx)) {
            if (visibleProjects.contains(task.project())) {
                active.put(task.id(), task);
            }
        }
        ArrayNode running = payload.putArray("running");
        ArrayNode queued = payload.putArray("queued");
        for (Runs.InProgress run : Runs.inProgress(tx, visibleProjects)) {
            boolean isRunning = run.status() == RunStatus.RUNNING;
            ObjectNode item = (isRunning ? running : queued).addObject().put("taskId", run.taskId()).put("project", run.project())
                    .put("title", run.title()).put("kind", run.kind().name()).put("priority", priority(active.get(run.taskId())));
            if (isRunning) {
                item.put("startedAt", text(run.startedAt()));
                activeRuns.activity(run.taskId()).ifPresent(activity ->
                        item.put("steps", activity.steps()).put("lastAction", activity.lastAction()));
            } else {
                item.put("queuedAt", text(run.queuedAt()));
            }
        }
        ArrayNode awaiting = payload.putArray("awaitingApproval");
        for (Task task : Tasks.withPhase(tx, Phase.AWAITING_APPROVAL, visibleProjects)) {
            awaiting.addObject().put("taskId", task.id()).put("project", task.project()).put("title", task.title())
                    .put("priority", task.priority().name()).put("requester", task.requester().name())
                    .put("since", text(task.updatedAt()));
        }
        ArrayNode mine = payload.putArray("mine");
        active.values().stream().filter(task -> task.requester().ref().equals(viewerRef))
                .forEach(task -> mine.addObject().put("taskId", task.id()).put("priority", task.priority().name()));
        return payload;
    }

    /** Posts the most recently finished tasks of {@code visibleProjects}, newest first, with their total cost. */
    public void history(Tx tx, Set<String> visibleProjects, String originRef, String chatRef) {
        List<Task> finished = Tasks.finished(tx, visibleProjects, HISTORY_SIZE);
        Map<Long, BigDecimal> costs = Runs.costs(tx, finished.stream().map(Task::id).toList());
        ObjectNode payload = Json.object();
        ArrayNode listed = payload.putArray("tasks");
        for (Task task : finished) {
            BigDecimal cost = costs.get(task.id());
            listed.addObject().put("taskId", task.id()).put("project", task.project()).put("title", task.title())
                    .put("phase", task.phase().name()).put("priority", task.priority().name()).put("prUrl", task.prUrl()).put("failureReason", name(task.failureReason()))
                    .put("costUsd", cost == null ? null : cost.toPlainString()).put("completedAt", text(task.completedAt()));
        }
        enqueue(tx, null, OutboxKind.HISTORY, chatRef, originRef, payload, clock.instant());
    }

    /** Posts one task's timeline: its runs in order, how it ended, and what it cost. Tasks outside {@code visibleProjects} are not found. */
    public void timeline(Tx tx, Set<String> visibleProjects, long taskId, String originRef, String chatRef) {
        Optional<Task> found = Tasks.find(tx, taskId).filter(task -> visibleProjects.contains(task.project()));
        if (found.isEmpty()) {
            enqueue(tx, null, OutboxKind.TASK_NOT_FOUND, chatRef, originRef, Json.object().put("taskId", taskId), clock.instant());
            return;
        }
        Task task = found.get();
        ObjectNode payload = Json.object().put("taskId", task.id()).put("project", task.project()).put("title", task.title())
                .put("requester", task.requester().name()).put("phase", task.phase().name()).put("priority", task.priority().name())
                .put("prUrl", task.prUrl())
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

    /** A run's task is active while the run is queued or running; null only if it finished between the two reads. */
    private static String priority(Task task) {
        return task == null ? null : task.priority().name();
    }

    private static String name(Enum<?> constant) {
        return constant == null ? null : constant.name();
    }
}
