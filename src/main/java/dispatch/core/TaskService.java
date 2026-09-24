package dispatch.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.config.Config;
import dispatch.domain.Attachment;
import dispatch.domain.Draft;
import dispatch.domain.DraftStatus;
import dispatch.domain.FailureReason;
import dispatch.domain.GroupReaction;
import dispatch.domain.OutboxKind;
import dispatch.domain.Phase;
import dispatch.domain.Plan;
import dispatch.domain.PlanQuestion;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.Run;
import dispatch.domain.RunCause;
import dispatch.domain.RunKind;
import dispatch.domain.RunStatus;
import dispatch.domain.SplitState;
import dispatch.domain.Task;
import dispatch.store.Attachments;
import dispatch.store.Conversations;
import dispatch.store.Drafts;
import dispatch.store.Events;
import dispatch.store.Outbox;
import dispatch.store.PlanAnswers;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import dispatch.store.Workers;
import dispatch.worker.Readiness;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongConsumer;

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
    private final boolean taskTopics;
    private final LongConsumer startSplit;
    /** Team mode: a task runs on its requester's own computer, so it waits when none of theirs is connected. */
    private final boolean requiresWorker;

    /** Without topics or splitting: for tests that need neither. */
    public TaskService(Groups groups, Projects projects, ActiveRuns activeRuns, Clock clock, Runnable wakeScheduler,
                       Runnable wakeOutbox) {
        this(groups, projects, activeRuns, clock, wakeScheduler, wakeOutbox, false,
                draftId -> Log.warn("split.not_wired", "draft", draftId), false);
    }

    /**
     * @param taskTopics     the channel can give each task its own topic in the requester's private chat
     * @param startSplit     starts splitting a draft's message in the background once ✂️ was pressed (ADR 0013)
     * @param requiresWorker team mode: a task runs on its requester's own computer (W-3)
     */
    public TaskService(Groups groups, Projects projects, ActiveRuns activeRuns, Clock clock, Runnable wakeScheduler,
                       Runnable wakeOutbox, boolean taskTopics, LongConsumer startSplit, boolean requiresWorker) {
        this.taskTopics = taskTopics;
        this.startSplit = startSplit;
        this.requiresWorker = requiresWorker;
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
        return draft(tx, who, projectKey, text, originRef, List.of());
    }

    /** @param attachments files sent with the message, which the task's agent gets to read */
    public DraftResult draft(Tx tx, Requester who, String projectKey, String text, String originRef, List<Attachment> attachments) {
        return draft(tx, who, projectKey, text, originRef, attachments, null);
    }

    /**
     * The group a task was given in by mentioning the bot, and the first name it calls the giver by (G-1b).
     *
     * @param chatRef the group chat, where the prompt's delivery is confirmed or, if refused, the giver is asked to press Start
     */
    public record GroupOrigin(String chatRef, String firstName) {
    }

    /**
     * @param originRef the message that gave the task; one in a group (G-1b) is not replied to, being in another chat
     * @param group     where the task was given if not privately, else null; the draft and its prompt are private either way
     */
    public DraftResult draft(Tx tx, Requester who, String projectKey, String text, String originRef, List<Attachment> attachments,
                             GroupOrigin group) {
        Instant now = clock.instant();
        String chatRef = who.ref();
        String replyTo = inChat(chatRef, originRef);
        if (Drafts.existsWithOrigin(tx, originRef)) {
            tx.afterCommit(() -> Log.info("draft.duplicate_ignored", "origin", originRef));
            return DraftResult.DUPLICATE;
        }
        if (!groups.isMember(who.ref())) {
            notAllowed(tx, who, replyTo, chatRef, now);
            return DraftResult.NOT_ALLOWED;
        }
        String description = text == null ? "" : text.strip();
        if (description.isEmpty()) {
            enqueue(tx, null, OutboxKind.TASK_USAGE, chatRef, replyTo, Json.object(), now);
            return DraftResult.EMPTY;
        }
        List<Config.Project> offered = offeredProjects(who.ref());
        if (offered.isEmpty()) {
            // A task given in a group is answered there by the caller, in one line for everyone its message concerned.
            if (group == null) {
                enqueue(tx, null, OutboxKind.NO_PROJECTS, chatRef, replyTo, Json.object(), now);
            }
            return DraftResult.NO_PROJECTS;
        }
        String named = projectKey == null ? null : projects.find(projectKey).map(Config.Project::name).orElse(null);
        String project = preselected(offered, named);
        long id = Drafts.insert(tx, new Drafts.NewDraft(who, chatRef, originRef, description, project, null, null), now);
        Attachments.addToDraft(tx, id, attachments);
        ObjectNode payload = draftPayload(tx, id).orElseThrow();
        if (group == null) {
            enqueue(tx, null, OutboxKind.DRAFT_PROMPT, chatRef, replyTo, payload, now);
        } else {
            // The sender confirms a delivered prompt in the group, or falls back there with a Start hint (G-1b).
            Outbox.enqueueWithFallback(tx, null, OutboxKind.DRAFT_PROMPT, chatRef, null, group.chatRef(), originRef,
                    payload.put("requester", group.firstName()), now);
            tx.afterCommit(wakeOutbox);
        }
        tx.afterCommit(() -> Log.info("draft.created", "draft", id, "requester", who.ref(), "project", project,
                "attachments", attachments.size()));
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
        Attachments.giveToTask(tx, draftId, taskId);
        return DraftChoice.CREATED;
    }

    /**
     * What a draft's prompt shows: while open, the projects to choose from and how far a split has come; once created, the
     * task; once split, its parts.
     */
    public Optional<ObjectNode> draftPayload(Tx tx, long draftId) {
        return Drafts.find(tx, draftId).map(draft -> {
            ObjectNode payload = Json.object().put("draftId", draft.id()).put("title", title(draft.description()))
                    .put("status", draft.status().name()).put("project", draft.project()).put("taskId", draft.taskId())
                    .put("topic", taskTopics);
            ArrayNode listed = payload.putArray("projects");
            offeredProjects(draft.requesterRef())
                    .forEach(project -> listed.addObject().put("name", project.name()).put("alias", project.alias()));
            payload.put("priority", draft.taskId() == null ? null
                    : Tasks.find(tx, draft.taskId()).map(task -> task.priority().name()).orElse(null));
            payload.put("split", draft.splitState() == null ? null : draft.splitState().name());
            ArrayNode skipped = payload.putArray("skippedFiles");
            Attachments.forDraft(tx, draftId).stream().filter(Attachment::tooLarge).forEach(file -> skipped.add(file.name()));
            payload.put("splittable", draft.status() == DraftStatus.OPEN && draft.parentId() == null
                    && (draft.splitState() == null || draft.splitState() == SplitState.FAILED));
            ArrayNode topics = payload.putArray("topics");
            draft.topics().forEach(topics::add);
            if (draft.parentId() != null) {
                payload.put("part", draft.part()).put("parts",
                        Drafts.find(tx, draft.parentId()).map(parent -> parent.topics().size()).orElse(0));
            }
            return payload;
        });
    }

    /**
     * ✂️ on a draft's prompt: the agent looks for the independent tasks in its message, in the background (ADR 0013).
     *
     * @param promptRef the prompt the button was pressed on; it is redrawn with the answer
     */
    public DraftChoice split(Tx tx, Requester who, long draftId, String promptRef) {
        Optional<DraftChoice> refused = refusal(Drafts.find(tx, draftId), who);
        if (refused.isPresent()) {
            return refused.get();
        }
        if (!Drafts.startSplit(tx, draftId, promptRef, clock.instant())) {
            return DraftChoice.CANNOT_SPLIT;
        }
        tx.afterCommit(() -> Log.info("split.started", "draft", draftId, "requester", who.ref()));
        tx.afterCommit(() -> startSplit.accept(draftId));
        return DraftChoice.SPLITTING;
    }

    /** The agent's topics: several are proposed on the prompt, a single one leaves the draft as it was. */
    public void splitProposed(Tx tx, long draftId, List<String> topics) {
        boolean several = topics.size() > 1;
        finishSplit(tx, draftId, several ? SplitState.PROPOSED : SplitState.ONE_TOPIC, several ? topics : List.of(),
                "topics", topics.size());
    }

    /** The prompt shows that the split failed and offers ✂️ again; the detail goes to the log. */
    public void splitFailed(Tx tx, long draftId, String error) {
        finishSplit(tx, draftId, SplitState.FAILED, List.of(), "error", error);
    }

    /** Splits that a previous process left running end as failed (ADR 0013); must run before anything splits. */
    public int failInterruptedSplits(Tx tx) {
        List<Draft> interrupted = Drafts.openAndSplitting(tx);
        interrupted.forEach(draft -> splitFailed(tx, draft.id(), "Dispatch restarted while splitting"));
        return interrupted.size();
    }

    private void finishSplit(Tx tx, long draftId, SplitState state, List<String> topics, String detailName, Object detail) {
        Instant now = clock.instant();
        if (!Drafts.finishSplit(tx, draftId, state, topics, now)) {
            // Given as one task, or expired, while the agent worked: its prompt already shows that.
            tx.afterCommit(() -> Log.info("split.discarded", "draft", draftId, "state", state));
            return;
        }
        Draft draft = Drafts.find(tx, draftId).orElseThrow();
        Outbox.enqueueEdit(tx, null, OutboxKind.DRAFT_PROMPT, draft.chatRef(), draft.promptRef(), draftPayload(tx, draftId).orElseThrow(), now);
        tx.afterCommit(wakeOutbox);
        if (state == SplitState.FAILED) {
            tx.afterCommit(() -> Log.warn("split.failed", "draft", draftId, detailName, detail));
        } else {
            tx.afterCommit(() -> Log.info("split.finished", "draft", draftId, "state", state, detailName, detail));
        }
    }

    /**
     * Takes the proposal: each part becomes a draft with its own prompt, under the message it came from. A project chosen
     * for the whole message carries over.
     */
    public DraftChoice acceptSplit(Tx tx, Requester who, long draftId) {
        Instant now = clock.instant();
        Optional<Draft> found = Drafts.find(tx, draftId);
        Optional<DraftChoice> refused = refusal(found, who);
        if (refused.isPresent()) {
            return refused.get();
        }
        if (!Drafts.split(tx, draftId, now)) {
            return DraftChoice.CANNOT_SPLIT;
        }
        Draft whole = found.get();
        String project = preselected(offeredProjects(who.ref()), whole.project());
        for (int part = 1; part <= whole.topics().size(); part++) {
            // Unique per part, while still naming the message its task replies under.
            String originRef = whole.originRef() + "#" + part;
            long id = Drafts.insert(tx, new Drafts.NewDraft(who, whole.chatRef(), originRef, whole.topics().get(part - 1), project,
                    draftId, part), now);
            Attachments.copyToDraft(tx, draftId, id);
            enqueue(tx, null, OutboxKind.DRAFT_PROMPT, whole.chatRef(), inChat(whole.chatRef(), originRef),
                    draftPayload(tx, id).orElseThrow(), now);
        }
        tx.afterCommit(() -> Log.info("split.accepted", "draft", draftId, "parts", whole.topics().size()));
        return DraftChoice.SPLIT;
    }

    /** Declines the proposal: the prompt asks for the whole message's project and priority again, without ✂️. */
    public DraftChoice keepWhole(Tx tx, Requester who, long draftId) {
        Optional<DraftChoice> refused = refusal(Drafts.find(tx, draftId), who);
        if (refused.isPresent()) {
            return refused.get();
        }
        return Drafts.keepWhole(tx, draftId, clock.instant()) ? DraftChoice.KEPT_WHOLE : DraftChoice.CANNOT_SPLIT;
    }

    /** Closes drafts created before {@code createdBefore} that nobody answered, telling their writers. */
    public int expireDrafts(Tx tx, Instant createdBefore) {
        Instant now = clock.instant();
        List<Draft> stale = Drafts.openCreatedBefore(tx, createdBefore);
        for (Draft draft : stale) {
            Drafts.expire(tx, draft.id(), now);
            enqueue(tx, null, OutboxKind.DRAFT_EXPIRED, draft.chatRef(), inChat(draft.chatRef(), draft.originRef()),
                    Json.object().put("draftId", draft.id()).put("title", title(draft.description())), now);
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
        Optional<String> groupChat = groups.chatOfProject(project.name());
        // Without a group chat the task belongs to the requester's private chat, where nothing needs announcing (ADR 0014).
        long id = Tasks.insert(tx, new Tasks.NewTask(project.name(), title(description), description, who, originRef, groupChat.orElse(who.ref()),
                UUID.randomUUID(), project.baseBranch(), priority), Phase.PLANNING, now);
        Runs.insert(tx, new Runs.NewRun(id, 1, RunKind.PLAN, RunCause.TASK, description, who), now);
        Events.record(tx, id, null, who.ref(), null, Phase.PLANNING, "created", now);
        if (taskTopics) {
            enqueue(tx, id, OutboxKind.TOPIC_CREATE, who.ref(), null, Json.object().put("taskId", id), now);
        }
        if (groupChat.isPresent()) {
            enqueue(tx, id, OutboxKind.TASK_QUEUED, groupChat.get(), null, Json.object().put("taskId", id).put("project", project.name())
                    .put("requester", who.name()).put("priority", priority.name()).put("title", title(description)), now);
        }
        if (requiresWorker && !Workers.hasConnected(tx, who.ref(), now.minus(Workers.SEEN_WITHIN))) {
            // Said once, when the task is given; /status keeps showing it until a computer connects.
            enqueue(tx, id, OutboxKind.WORKER_WAITING, who.ref(), null, Json.object().put("taskId", id), now);
        }
        GroupAcks.react(tx, Tasks.find(tx, id).orElseThrow(), GroupReaction.TASK_CREATED, now);
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
            case SPLIT -> Optional.of(DraftChoice.ALREADY_SPLIT);
        };
    }

    /** The project named or chosen, if the member can still use it; otherwise their only project, if they have just one. */
    private static String preselected(List<Config.Project> offered, String named) {
        if (named != null && offered.stream().anyMatch(project -> project.name().equals(named))) {
            return named;
        }
        return offered.size() == 1 ? offered.getFirst().name() : null;
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
     * their plan (ADR 0011). A plan with open questions cannot be approved: the answers come as a correction, typed as a
     * reply or given under the question messages (G-1d).
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
        if (!isRequester(task, who)) {
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
        Runs.insert(tx, new Runs.NewRun(taskId, Runs.nextSeq(tx, taskId), RunKind.EXECUTE, RunCause.APPROVAL, task.planJson(), who), now);
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
        Task task = Tasks.find(tx, taskId).orElseThrow(() -> new IllegalStateException("task " + taskId + " does not exist"));
        if (!isRequester(task, who)) {
            enqueue(tx, taskId, OutboxKind.CORRECTION_REFUSED, chatRef, originRef, notRequester(task), now);
            return CorrectResult.REFUSED;
        }
        if (text == null || text.isBlank()) {
            return CorrectResult.EMPTY;
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
        Runs.insert(tx, new Runs.NewRun(taskId, seq, RunKind.PLAN, RunCause.CORRECTION, text.strip(), who), now);
        Events.record(tx, taskId, null, who.ref(), Phase.AWAITING_APPROVAL, Phase.PLANNING, "correction", now);
        enqueue(tx, taskId, OutboxKind.CORRECTION_QUEUED, chatRef, originRef,
                Json.object().put("taskId", taskId).put("by", who.name()), now);
        tx.afterCommit(wakeScheduler);
        logTransition(tx, taskId, Phase.AWAITING_APPROVAL, Phase.PLANNING, who.ref());
        return CorrectResult.CORRECTED;
    }

    /** A message in a task's own topic corrects its latest plan; refused with the reason when the task is not waiting for one. */
    public CorrectResult correctLatest(Tx tx, Requester who, long taskId, String text, String originRef, String chatRef) {
        int planSeq = Runs.latestSucceededPlanSeq(tx, taskId).orElse(0);
        return correct(tx, who, taskId, planSeq, text, originRef, chatRef);
    }

    /**
     * The requester's answer to question {@code index} (1-based) of the plan with run number {@code planSeq} (G-1d). The
     * question message is redrawn with it and the next unanswered question is sent; the last answer sends them all to
     * the agent as one correction, exactly as a typed one. A question already answered, one of an older plan, or one of a
     * task no longer awaiting approval changes nothing.
     *
     * @param questionRef the question message, redrawn with the answer
     * @param originRef   the message the correction is acknowledged under
     */
    public AnswerResult answer(Tx tx, Requester who, long taskId, int planSeq, int index, String answer, String questionRef,
                               String originRef, String chatRef) {
        Optional<AnswerResult> refused = questionRefusal(tx, who, taskId, planSeq, index);
        if (refused.isPresent()) {
            tx.afterCommit(() -> Log.info("task.answer_refused", "task", taskId, "plan", planSeq, "question", index,
                    "requester", who.ref(), "result", refused.get()));
            return refused.get();
        }
        if (answer == null || answer.isBlank()) {
            return AnswerResult.EMPTY;
        }
        Instant now = clock.instant();
        Task task = Tasks.find(tx, taskId).orElseThrow();
        List<PlanQuestion> questions = Plan.parse(task.planJson()).questionItems();
        String text = answer.strip();
        if (!PlanAnswers.record(tx, taskId, planSeq, index, text, who.ref(), questionRef, now)) {
            return AnswerResult.ALREADY_ANSWERED;
        }
        Outbox.enqueueEdit(tx, taskId, OutboxKind.PLAN_QUESTION, task.requester().ref(), questionRef,
                questionPayload(taskId, planSeq, questions, index).put("answer", text), now);
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(() -> Log.info("task.question_answered", "task", taskId, "plan", planSeq, "question", index,
                "requester", who.ref()));
        Map<Integer, String> answers = PlanAnswers.of(tx, taskId, planSeq);
        for (int next = 1; next <= questions.size(); next++) {
            if (!answers.containsKey(next)) {
                enqueueQuestion(tx, task, planSeq, questions, next, now);
                return AnswerResult.ANSWERED;
            }
        }
        correct(tx, who, taskId, planSeq, answersText(questions, answers), originRef, chatRef);
        return AnswerResult.ANSWERED;
    }

    /**
     * The requester chose answer option {@code option} (0-based) of a question: {@link #answer} with that option's text.
     * An option the question does not have is {@link AnswerResult#EMPTY}.
     */
    public AnswerResult chooseOption(Tx tx, Requester who, long taskId, int planSeq, int index, int option, String questionRef,
                                     String originRef, String chatRef) {
        String text = Tasks.find(tx, taskId)
                .filter(task -> task.planJson() != null)
                .map(task -> Plan.parse(task.planJson()).questionItems())
                .filter(questions -> index >= 1 && index <= questions.size())
                .map(questions -> questions.get(index - 1).options())
                .filter(options -> option >= 0 && option < options.size())
                .map(options -> options.get(option))
                .orElse("");
        return answer(tx, who, taskId, planSeq, index, text, questionRef, originRef, chatRef);
    }

    /** The requester wants to write their own answer to a question: they are asked for it as a forced reply under it (G-1d). */
    public AnswerResult askForAnswer(Tx tx, Requester who, long taskId, int planSeq, int index, String questionRef) {
        Optional<AnswerResult> refused = questionRefusal(tx, who, taskId, planSeq, index);
        if (refused.isPresent()) {
            return refused.get();
        }
        if (Outbox.hasAnswerPrompt(tx, taskId, planSeq, index)) {
            // The question is unanswered, so its prompt is still open: a second one would only clutter the chat.
            return AnswerResult.PROMPT_OPEN;
        }
        Task task = Tasks.find(tx, taskId).orElseThrow();
        Outbox.enqueue(tx, taskId, OutboxKind.PLAN_ANSWER_PROMPT, task.requester().ref(), questionRef,
                Json.object().put("taskId", taskId).put("planSeq", planSeq).put("index", index).put("questionRef", questionRef),
                clock.instant());
        tx.afterCommit(wakeOutbox);
        return AnswerResult.PROMPTED;
    }

    /**
     * Sends question {@code index} of a plan to its requester's private chat only: its buttons are theirs alone, so it has
     * no fallback to the group (G-1d).
     */
    static void enqueueQuestion(Tx tx, Task task, int planSeq, List<PlanQuestion> questions, int index, Instant now) {
        Outbox.enqueue(tx, task.id(), OutboxKind.PLAN_QUESTION, task.requester().ref(), null,
                questionPayload(task.id(), planSeq, questions, index), now);
    }

    private static ObjectNode questionPayload(long taskId, int planSeq, List<PlanQuestion> questions, int index) {
        PlanQuestion question = questions.get(index - 1);
        ObjectNode payload = Json.object().put("taskId", taskId).put("planSeq", planSeq).put("index", index)
                .put("total", questions.size()).put("text", question.text());
        question.options().forEach(payload.putArray("options")::add);
        return payload;
    }

    /** Why question {@code index} of plan {@code planSeq} cannot be answered now; empty when it can. */
    private Optional<AnswerResult> questionRefusal(Tx tx, Requester who, long taskId, int planSeq, int index) {
        if (!groups.isMember(who.ref())) {
            return Optional.of(AnswerResult.NOT_ALLOWED);
        }
        Optional<Task> found = Tasks.find(tx, taskId);
        if (found.isEmpty()) {
            return Optional.of(AnswerResult.NOT_FOUND);
        }
        Task task = found.get();
        if (!isRequester(task, who)) {
            return Optional.of(AnswerResult.NOT_REQUESTER);
        }
        OptionalInt latestPlan = Runs.latestSucceededPlanSeq(tx, taskId);
        if (task.phase() != Phase.AWAITING_APPROVAL || latestPlan.isEmpty() || latestPlan.getAsInt() != planSeq) {
            return Optional.of(AnswerResult.STALE);
        }
        int total = Plan.parse(task.planJson()).questionItems().size();
        if (index < 1 || index > total) {
            return Optional.of(AnswerResult.STALE);
        }
        if (PlanAnswers.of(tx, taskId, planSeq).containsKey(index)) {
            return Optional.of(AnswerResult.ALREADY_ANSWERED);
        }
        return Optional.empty();
    }

    /** The correction that carries every answer, in the requester's language like the buttons they pressed. */
    private static String answersText(List<PlanQuestion> questions, Map<Integer, String> answers) {
        StringBuilder text = new StringBuilder("Асуултын хариулт:");
        for (int index = 1; index <= questions.size(); index++) {
            text.append('\n').append(index).append(". ").append(questions.get(index - 1).text()).append(" → ").append(answers.get(index));
        }
        return text.toString();
    }

    public Optional<Task> taskOfTopic(Tx tx, String requesterRef, String topicRef) {
        return Tasks.findByTopic(tx, requesterRef, topicRef);
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
        if (!isRequester(task, who)) {
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
        GroupAcks.react(tx, task, GroupReaction.ENDED, now);
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
        if (!isRequester(task, who)) {
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
     * Cancels the member's own task; an admin may cancel any task, even outside their own groups. A running agent is
     * stopped after commit and its run ends as CANCELLED. Another group's task is answered as not found, so its existence
     * does not leak, unless the caller is an admin.
     */
    public CancelResult cancel(Tx tx, Requester who, long taskId, String originRef, String chatRef) {
        Instant now = clock.instant();
        if (!groups.isMember(who.ref()) && !groups.isAdmin(who.ref())) {
            notAllowed(tx, who, originRef, chatRef, now);
            return CancelResult.NOT_ALLOWED;
        }
        Optional<Task> found = groups.isAdmin(who.ref()) ? Tasks.find(tx, taskId) : visibleTask(tx, who, taskId);
        if (found.isEmpty()) {
            enqueue(tx, null, OutboxKind.TASK_NOT_FOUND, chatRef, originRef, Json.object().put("taskId", taskId), now);
            return CancelResult.NOT_FOUND;
        }
        Task task = found.get();
        if (!isRequester(task, who) && !groups.isAdmin(who.ref())) {
            enqueue(tx, taskId, OutboxKind.CANCEL_REFUSED, chatRef, originRef, notRequester(task), now);
            return CancelResult.REFUSED;
        }
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
        GroupAcks.react(tx, task, GroupReaction.ENDED, now);
        tx.afterCommit(() -> activeRuns.stop(taskId, ActiveRuns.StopReason.CANCELLED));
        logTransition(tx, taskId, task.phase(), Phase.CANCELLED, who.ref());
        return CancelResult.CANCELLED;
    }

    /**
     * Repeats just the failed step of a failed task (ADR 0008): a failed plan is planned again, a failed execution continues
     * its building session, and a failed delivery is delivered again without the agent. Only the requester may retry it;
     * another group's task is answered as not found.
     */
    public RetryResult retry(Tx tx, Requester who, long taskId, String originRef, String chatRef) {
        Instant now = clock.instant();
        if (!groups.isMember(who.ref())) {
            notAllowed(tx, who, originRef, chatRef, now);
            return RetryResult.NOT_ALLOWED;
        }
        Optional<Task> found = visibleTask(tx, who, taskId);
        if (found.isEmpty()) {
            enqueue(tx, null, OutboxKind.TASK_NOT_FOUND, chatRef, originRef, Json.object().put("taskId", taskId), now);
            return RetryResult.NOT_FOUND;
        }
        Task task = found.get();
        if (!isRequester(task, who)) {
            enqueue(tx, taskId, OutboxKind.RETRY_REFUSED, chatRef, originRef, notRequester(task), now);
            return RetryResult.REFUSED;
        }
        Optional<Run> failed = Runs.latest(tx, taskId).filter(run -> run.status() == RunStatus.FAILED);
        if (task.phase() != Phase.FAILED || failed.isEmpty()) {
            enqueue(tx, taskId, OutboxKind.RETRY_REFUSED, chatRef, originRef,
                    Json.object().put("taskId", taskId).put("phase", task.phase().name()), now);
            return RetryResult.REFUSED;
        }
        Run step = failed.get();
        RunKind kind;
        String instruction;
        if (step.kind() == RunKind.PLAN) {
            kind = RunKind.PLAN;
            instruction = step.instruction();
        } else if (step.kind() == RunKind.DELIVER || step.failureReason() == FailureReason.DELIVERY) {
            // The agent's work is done and waits in the worktree; only its summary is needed, as the commit message.
            kind = RunKind.DELIVER;
            instruction = step.kind() == RunKind.DELIVER ? step.instruction() : Runs.output(tx, taskId, step.seq()).orElse("");
        } else {
            kind = RunKind.EXECUTE;
            instruction = step.instruction();
        }
        Phase to = kind == RunKind.PLAN ? Phase.PLANNING : Phase.EXECUTING;
        if (!Tasks.changePhase(tx, taskId, Phase.FAILED, to, now)) {
            enqueue(tx, taskId, OutboxKind.RETRY_REFUSED, chatRef, originRef,
                    Json.object().put("taskId", taskId).put("phase", task.phase().name()), now);
            return RetryResult.REFUSED;
        }
        int seq = Runs.nextSeq(tx, taskId);
        Runs.insert(tx, new Runs.NewRun(taskId, seq, kind, RunCause.RETRY, instruction, who), now);
        Events.record(tx, taskId, seq, who.ref(), Phase.FAILED, to, "retry of run " + step.seq(), now);
        enqueue(tx, taskId, OutboxKind.RETRY_QUEUED, chatRef, originRef,
                Json.object().put("taskId", taskId).put("by", who.name()).put("kind", kind.name()), now);
        tx.afterCommit(wakeScheduler);
        logTransition(tx, taskId, Phase.FAILED, to, who.ref());
        return RetryResult.RETRIED;
    }

    /**
     * The requester's reply to a finished task's result: it runs at once in the task's building session and branch,
     * without a new plan, because the reply is itself the instruction (ADR 0006). Only a task that got as far as
     * execution can be followed up; one that is still active is refused, as its current run would not see the reply.
     *
     * @param originRef channel reference of the reply, which the answer goes under
     */
    public FollowUpResult followUp(Tx tx, Requester who, long taskId, String text, String originRef, String chatRef) {
        Instant now = clock.instant();
        if (!groups.isMember(who.ref())) {
            notAllowed(tx, who, originRef, chatRef, now);
            return FollowUpResult.NOT_ALLOWED;
        }
        Optional<Task> found = visibleTask(tx, who, taskId);
        if (found.isEmpty()) {
            enqueue(tx, null, OutboxKind.TASK_NOT_FOUND, chatRef, originRef, Json.object().put("taskId", taskId), now);
            return FollowUpResult.NOT_FOUND;
        }
        Task task = found.get();
        if (!isRequester(task, who)) {
            enqueue(tx, taskId, OutboxKind.FOLLOW_UP_REFUSED, chatRef, originRef, notRequester(task), now);
            return FollowUpResult.REFUSED;
        }
        if (text == null || text.isBlank()) {
            return FollowUpResult.EMPTY;
        }
        boolean finished = task.phase() == Phase.COMPLETED || task.phase() == Phase.FAILED;
        boolean executed = Runs.agentStartedBefore(tx, taskId, RunKind.EXECUTE, Integer.MAX_VALUE);
        if (!finished || !executed || !Tasks.changePhase(tx, taskId, task.phase(), Phase.EXECUTING, now)) {
            enqueue(tx, taskId, OutboxKind.FOLLOW_UP_REFUSED, chatRef, originRef, Json.object().put("taskId", taskId)
                    .put("reason", finished && !executed ? "notExecuted" : "phase").put("phase", task.phase().name()), now);
            return FollowUpResult.REFUSED;
        }
        int seq = Runs.nextSeq(tx, taskId);
        Runs.insert(tx, new Runs.NewRun(taskId, seq, RunKind.EXECUTE, RunCause.FOLLOW_UP, text.strip(), who), now);
        Events.record(tx, taskId, seq, who.ref(), task.phase(), Phase.EXECUTING, "follow-up", now);
        enqueue(tx, taskId, OutboxKind.FOLLOW_UP_QUEUED, chatRef, originRef, Json.object().put("taskId", taskId).put("by", who.name()), now);
        tx.afterCommit(wakeScheduler);
        logTransition(tx, taskId, task.phase(), Phase.EXECUTING, who.ref());
        return FollowUpResult.QUEUED;
    }

    /** The member's own task, or one of their groups' tasks; empty for anything else, so its existence does not leak. */
    private Optional<Task> visibleTask(Tx tx, Requester who, long taskId) {
        return Tasks.find(tx, taskId).filter(task ->
                task.requester().ref().equals(who.ref()) || groups.isMemberOfProjectGroup(who.ref(), task.project()));
    }

    private static boolean isRequester(Task task, Requester who) {
        return task.requester().ref().equals(who.ref());
    }

    /** Null when the task is not among the active ones this viewer may see, e.g. a run of another group's project. */
    private static String requesterName(Task task) {
        return task == null ? null : task.requester().name();
    }

    /** Whether {@code viewerRef} gave the task; a group chat (null viewer) owns nothing, so it sees headlines only (ADR 0020). */
    private static boolean ownedBy(Task task, String viewerRef) {
        return task != null && viewerRef != null && task.requester().ref().equals(viewerRef);
    }

    /** Why a member may see a task but not act on it: it is someone else's (ADR 0020). */
    private static ObjectNode notRequester(Task task) {
        return Json.object().put("taskId", task.id()).put("reason", "requester").put("requester", task.requester().name());
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
        Instant workerSeenSince = requiresWorker ? clock.instant().minus(Workers.SEEN_WITHIN) : null;
        for (Runs.InProgress run : Runs.inProgress(tx, visibleProjects)) {
            boolean isRunning = run.status() == RunStatus.RUNNING;
            ObjectNode item = (isRunning ? running : queued).addObject().put("taskId", run.taskId()).put("project", run.project())
                    .put("title", run.title()).put("kind", run.kind().name()).put("priority", priority(active.get(run.taskId())))
                    // Who gave it: the name for an admin's Tasks page to show (ADR 0020 allows it), and a flag for the
                    // Mini App's My tasks page, which keeps only the viewer's own and cannot go by name — two members
                    // may share a first name.
                    .put("requester", requesterName(active.get(run.taskId())))
                    .put("mine", ownedBy(active.get(run.taskId()), viewerRef));
            if (isRunning) {
                item.put("startedAt", text(run.startedAt()));
                if (ownedBy(active.get(run.taskId()), viewerRef)) {
                    activeRuns.activity(run.taskId()).ifPresent(activity ->
                            item.put("steps", activity.steps()).put("lastAction", activity.lastAction()));
                }
            } else {
                item.put("queuedAt", text(run.queuedAt()));
                Task queuedTask = active.get(run.taskId());
                boolean waitingForWorker = requiresWorker && queuedTask != null && waitsForWorker(tx, queuedTask, workerSeenSince);
                if (waitingForWorker) {
                    item.put("waitingForWorker", true);
                }
                // An offline computer's last blocker is stale until the scheduler's next pass clears it; offline says more.
                String blocked = requiresWorker && !waitingForWorker ? Tasks.blockedReason(tx, run.taskId()) : null;
                if (blocked != null) {
                    item.put("blocked", blocked);
                }
            }
        }
        ArrayNode awaiting = payload.putArray("awaitingApproval");
        for (Task task : Tasks.withPhase(tx, Phase.AWAITING_APPROVAL, visibleProjects)) {
            ObjectNode item = awaiting.addObject().put("taskId", task.id()).put("project", task.project()).put("title", task.title())
                    .put("priority", task.priority().name()).put("requester", task.requester().name())
                    .put("mine", ownedBy(task, viewerRef)).put("since", text(task.updatedAt()));
            if (ownedBy(task, viewerRef)) {
                // What the Mini App's home shows on the requester's own waiting task: the question it waits on, if any.
                currentPlan(tx, task.id()).ifPresent(plan -> {
                    List<JsonNode> open = new ArrayList<>();
                    plan.withArray("questions").forEach(question -> {
                        if (question.path("answer").isNull()) {
                            open.add(question);
                        }
                    });
                    item.put("openQuestions", open.size());
                    if (!open.isEmpty()) {
                        item.put("question", open.getFirst().path("text").asText());
                    }
                });
            }
        }
        ArrayNode mine = payload.putArray("mine");
        active.values().stream().filter(task -> task.requester().ref().equals(viewerRef))
                .forEach(task -> mine.addObject().put("taskId", task.id()).put("priority", task.priority().name()));
        return payload;
    }

    /**
     * Tells each requester what on their computer holds their queued run — once per reason, not once per call — and
     * forgets the reason once nothing holds it, so /status stops saying so. The scheduler calls this whenever it found
     * nothing to claim: when the queue is held or full, never between two claims.
     *
     * @param workerSeenSince how recently a computer must have reported to count, as the scheduler's claim uses
     */
    public void reportBlocked(Tx tx, Instant workerSeenSince) {
        for (Runs.InProgress run : Runs.queued(tx)) {
            Optional<Task> task = Tasks.find(tx, run.taskId());
            if (task.isEmpty()) {
                continue;
            }
            String requesterRef = task.get().requester().ref();
            Optional<Readiness.Blocker> blocker = Workers.blockerOf(tx, requesterRef, Tasks.workerOf(tx, run.taskId()).orElse(null),
                    workerSeenSince, run.project(), run.kind());
            if (blocker.isEmpty()) {
                Tasks.setBlockedReason(tx, run.taskId(), null);
                continue;
            }
            String code = blocker.get().code();
            if (code.equals(Tasks.blockedReason(tx, run.taskId()))) {
                continue;
            }
            Tasks.setBlockedReason(tx, run.taskId(), code);
            enqueue(tx, run.taskId(), OutboxKind.WORKER_BLOCKED, requesterRef, null,
                    Json.object().put("taskId", run.taskId()).put("code", code).put("detail", blocker.get().detail()),
                    clock.instant());
            tx.afterCommit(() -> Log.info("task.blocked", "task", run.taskId(), "run", run.seq(), "reason", code));
        }
    }

    /**
     * Posts the most recently finished tasks of {@code visibleProjects}, newest first; the total cost is shown only on
     * the viewer's own tasks, everyone else's carry just the headline (ADR 0020).
     */
    public void history(Tx tx, Set<String> visibleProjects, String viewerRef, String originRef, String chatRef) {
        enqueue(tx, null, OutboxKind.HISTORY, chatRef, originRef, historyPayload(tx, visibleProjects, viewerRef), clock.instant());
    }

    /** The content of a history message; also what the Mini App's task list reads (spec: Task pages). */
    public ObjectNode historyPayload(Tx tx, Set<String> visibleProjects, String viewerRef) {
        List<Task> finished = Tasks.finished(tx, visibleProjects, HISTORY_SIZE);
        Map<Long, BigDecimal> costs = Runs.costs(tx, finished.stream().map(Task::id).toList());
        ObjectNode payload = Json.object();
        ArrayNode listed = payload.putArray("tasks");
        for (Task task : finished) {
            boolean mine = ownedBy(task, viewerRef);
            BigDecimal cost = mine ? costs.get(task.id()) : null;
            listed.addObject().put("taskId", task.id()).put("project", task.project()).put("title", task.title())
                    .put("mine", mine)
                    .put("phase", task.phase().name()).put("priority", task.priority().name())
                    .put("requester", task.requester().name()).put("createdAt", text(task.createdAt())).put("prUrl", task.prUrl()).put("failureReason", name(task.failureReason()))
                    .put("costUsd", cost == null ? null : cost.toPlainString()).put("completedAt", text(task.completedAt()));
        }
        return payload;
    }

    /**
     * Posts one task's timeline: its runs in order, how it ended, and what it cost. Tasks outside {@code visibleProjects}
     * are not found. Someone else's task stops at the headline: no runs, no cost (ADR 0020).
     */
    public void timeline(Tx tx, Set<String> visibleProjects, String viewerRef, long taskId, String originRef, String chatRef) {
        Optional<ObjectNode> payload = timelinePayload(tx, visibleProjects, viewerRef, taskId);
        enqueue(tx, null, payload.isPresent() ? OutboxKind.TASK_TIMELINE : OutboxKind.TASK_NOT_FOUND, chatRef, originRef,
                payload.orElseGet(() -> Json.object().put("taskId", taskId)), clock.instant());
    }

    /**
     * One task's timeline, or empty when it is not in {@code visibleProjects} — so a task's existence does not leak.
     * Someone else's task stops at the headline here too (ADR 0020), which is what keeps that rule in one place.
     */
    public Optional<ObjectNode> timelinePayload(Tx tx, Set<String> visibleProjects, String viewerRef, long taskId) {
        Optional<Task> found = Tasks.find(tx, taskId).filter(task -> visibleProjects.contains(task.project()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Task task = found.get();
        ObjectNode payload = Json.object().put("taskId", task.id()).put("project", task.project()).put("title", task.title())
                .put("requester", task.requester().name()).put("phase", task.phase().name()).put("priority", task.priority().name())
                .put("prUrl", task.prUrl())
                .put("failureReason", name(task.failureReason())).put("createdAt", text(task.createdAt()))
                .put("completedAt", text(task.completedAt()));
        if (!ownedBy(task, viewerRef)) {
            return Optional.of(payload.put("headline", true).putNull("costUsd"));
        }
        ArrayNode runs = payload.putArray("runs");
        BigDecimal total = null;
        for (Run run : Runs.forTask(tx, taskId)) {
            // A reply's text is worth showing; the first plan's instruction is the task, an execution's the plan.
            boolean reply = run.cause() == RunCause.CORRECTION || run.cause() == RunCause.FOLLOW_UP;
            String instruction = reply ? truncate(run.instruction(), INSTRUCTION_LENGTH) : null;
            runs.addObject().put("seq", run.seq()).put("kind", run.kind().name()).put("cause", name(run.cause()))
                    .put("status", run.status().name())
                    .put("requestedBy", run.requestedByName()).put("instruction", instruction).put("queuedAt", text(run.queuedAt()))
                    .put("startedAt", text(run.startedAt())).put("finishedAt", text(run.finishedAt()))
                    .put("costUsd", run.costUsd() == null ? null : run.costUsd().toPlainString())
                    .put("failureReason", name(run.failureReason()));
            if (run.costUsd() != null) {
                total = total == null ? run.costUsd() : total.add(run.costUsd());
            }
        }
        payload.put("costUsd", total == null ? null : total.toPlainString());
        return Optional.of(payload);
    }

    /**
     * The task's latest plan with its questions and the answers given so far, as the Mini App's task sheet shows it; empty
     * when the task has no plan yet. It does not check who asks: the caller shows it to the requester alone.
     */
    public Optional<ObjectNode> currentPlan(Tx tx, long taskId) {
        Optional<Task> found = Tasks.find(tx, taskId).filter(task -> task.planJson() != null);
        OptionalInt planSeq = Runs.latestSucceededPlanSeq(tx, taskId);
        if (found.isEmpty() || planSeq.isEmpty()) {
            return Optional.empty();
        }
        Plan plan = Plan.parse(found.get().planJson());
        Map<Integer, String> answers = PlanAnswers.of(tx, taskId, planSeq.getAsInt());
        ObjectNode payload = Json.object().put("planSeq", planSeq.getAsInt()).put("understanding", plan.understanding());
        plan.steps().forEach(payload.putArray("steps")::add);
        plan.risks().forEach(payload.putArray("risks")::add);
        plan.findings().forEach(payload.putArray("findings")::add);
        ArrayNode questions = payload.putArray("questions");
        for (int index = 1; index <= plan.questionItems().size(); index++) {
            PlanQuestion question = plan.questionItems().get(index - 1);
            ObjectNode item = questions.addObject().put("index", index).put("text", question.text()).put("answer", answers.get(index));
            question.options().forEach(item.putArray("options")::add);
        }
        return Optional.of(payload);
    }

    /** Posts statistics for this month: the viewer's own in a private chat, the group's in a group chat (ADR 0012). */
    public void stats(Tx tx, String viewerRef, List<String> groupNames, String originRef, String chatRef) {
        String view = viewerRef != null ? "me" : "group:" + groupNames.getFirst();
        ObjectNode payload = statsPayload(tx, viewerRef, groupNames, view, "month")
                .orElseThrow(() -> new IllegalStateException("default statistics view " + view + " refused"));
        enqueue(tx, null, OutboxKind.STATS, chatRef, originRef, payload, clock.instant());
    }

    /**
     * Statistics of tasks given in {@code period} ("week": the last 7 days, "month": since the 1st, "all"). Outside the
     * viewer's own "me" view, cost is dropped from the summary and shown per person only on the viewer's own row (ADR 0020).
     *
     * @param viewerRef  the member asking in their private chat; null in a group chat, which has no "me" view
     * @param groupNames the groups whose tasks the viewer may count
     * @param view       "me", "group:&lt;name&gt;" (one of {@code groupNames}) or "people" (per requester)
     * @return empty when the viewer may not ask for this view or the period is unknown
     */
    public Optional<ObjectNode> statsPayload(Tx tx, String viewerRef, List<String> groupNames, String view, String period) {
        Instant now = clock.instant();
        Instant since;
        switch (period) {
            case "week" -> since = now.minus(Duration.ofDays(7));
            case "month" -> since = now.atZone(clock.getZone()).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
            case "all" -> since = null;
            default -> {
                return Optional.empty();
            }
        }
        Set<String> projectsOfGroups = new HashSet<>();
        groupNames.forEach(name -> projectsOfGroups.addAll(groups.projectsOfGroup(name)));
        List<Task> given;
        if (view.equals("me") && viewerRef != null) {
            given = Tasks.createdSince(tx, projectsOfGroups, since).stream().filter(task -> task.requester().ref().equals(viewerRef)).toList();
        } else if (view.equals("people")) {
            given = Tasks.createdSince(tx, projectsOfGroups, since);
        } else if (view.startsWith("group:") && groupNames.contains(view.substring("group:".length()))) {
            given = Tasks.createdSince(tx, groups.projectsOfGroup(view.substring("group:".length())), since);
        } else {
            return Optional.empty();
        }
        List<Runs.Cost> runs = Runs.costsOf(tx, given.stream().map(Task::id).toList());
        ObjectNode payload = Json.object().put("view", view).put("period", period).put("canViewMe", viewerRef != null);
        groupNames.forEach(payload.putArray("groups")::add);
        payload.set("summary", Statistics.summary(given, runs));
        if (!view.equals("me")) {
            // A group's total holds other members' costs (ADR 0020).
            ((ObjectNode) payload.get("summary")).putNull("costUsd").putNull("averageCostUsd");
        }
        payload.set("people", view.equals("people") ? Statistics.people(given, runs, viewerRef) : Json.MAPPER.createArrayNode());
        if (view.equals("me")) {
            // What talking to the assistant cost (A-1): the viewer's own, so only in their own view (ADR 0020).
            BigDecimal chat = Conversations.spentSince(tx, viewerRef, since);
            payload.put("chatCostUsd", chat.signum() == 0 ? null : chat.toPlainString());
        }
        return Optional.of(payload);
    }

    /** Tells {@code who} in {@code chatRef} that they may not give Dispatch tasks, and logs it; also for a group's task (G-1b). */
    public void notAllowed(Tx tx, Requester who, String originRef, String chatRef, Instant now) {
        enqueue(tx, null, OutboxKind.NOT_ALLOWED, chatRef, originRef, Json.object().put("name", who.name()), now);
        tx.afterCommit(() -> Log.warn("member.not_allowed", "requester", who.ref(), "name", who.name(), "chat", chatRef));
    }

    /**
     * {@code originRef} as a reply target in {@code chatRef}, or null when it is in another chat: a draft given in a group
     * (G-1b) is prompted privately, and a reply target from elsewhere could land on an unrelated message.
     */
    private static String inChat(String chatRef, String originRef) {
        return originRef.startsWith(chatRef + "/") ? originRef : null;
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

    /**
     * Whether {@code task} has nobody to run it right now: any of the requester's computers, or — once the task has a
     * worktree — only the one it is pinned to (a revoked pin no longer counts; {@link Tasks#clearWorkerPin} clears it).
     */
    private static boolean waitsForWorker(Tx tx, Task task, Instant since) {
        Optional<Long> pinned = Tasks.workerOf(tx, task.id());
        return pinned.isPresent() ? !Workers.isLive(tx, pinned.get(), since) : !Workers.hasConnected(tx, task.requester().ref(), since);
    }

    private static String name(Enum<?> constant) {
        return constant == null ? null : constant.name();
    }
}
