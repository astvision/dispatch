package dispatch.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.config.Config;
import dispatch.domain.AgentKind;
import dispatch.domain.Attachment;
import dispatch.domain.Draft;
import dispatch.domain.GroupAck;
import dispatch.domain.DraftStatus;
import dispatch.domain.OutboxKind;
import dispatch.domain.Phase;
import dispatch.domain.Plan;
import dispatch.domain.PlanQuestion;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.Run;
import dispatch.domain.RunCause;
import dispatch.domain.RunStatus;
import dispatch.domain.RunStep;
import dispatch.domain.SplitState;
import dispatch.domain.Task;
import dispatch.store.Attachments;
import dispatch.store.Conversations;
import dispatch.store.Drafts;
import dispatch.store.MemberPrefs;
import dispatch.store.Outbox;
import dispatch.store.PlanAnswers;
import dispatch.store.RunSteps;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * What members can do, independent of the channel they use. Every method works inside the caller's transaction so
 * a channel can commit its own bookkeeping (e.g. the Telegram offset) atomically with the effect.
 */
public final class TaskService {

    private static final int TITLE_LENGTH = 80;
    private static final int HISTORY_SIZE = 10;
    private static final int INSTRUCTION_LENGTH = 200;
    /** Heads what replies to a draft's prompt added to its description; its agent reads the description as the task. */
    static final String CONTEXT_HEADING = "Нэмэлт мэдээлэл:";

    private final Groups groups;
    private final Projects projects;
    private final ActiveRuns activeRuns;
    private final TaskAccess access;
    private final TaskCommands commands;
    private final Clock clock;
    private final Runnable wakeOutbox;
    private final boolean taskTopics;
    private final LongConsumer startSplit;
    /** Team mode: a task runs on its requester's own computer, so it waits when none of theirs is connected. */
    private final boolean requiresWorker;
    /** The instance's own prefix for task branches (M); null for "dispatch". */
    private final String branchPrefix;

    /** Without topics or splitting: for tests that need neither. */
    public TaskService(Groups groups, Projects projects, ActiveRuns activeRuns, Clock clock, Runnable wakeScheduler,
                       Runnable wakeOutbox) {
        this(groups, projects, activeRuns, clock, wakeScheduler, wakeOutbox, false,
                draftId -> Log.warn("split.not_wired", "draft", draftId), false);
    }

    /**
     * @param taskTopics     the channel can give each task its own topic in the requester's private chat
     * @param startSplit     starts splitting a draft's message in the background once ✂️ was pressed (ADR 0013); null
     *                       when there is nothing to split with (no Claude Code configured, ADR 0026), and ✂️ is not offered
     * @param requiresWorker team mode: a task runs on its requester's own computer (W-3)
     */
    public TaskService(Groups groups, Projects projects, ActiveRuns activeRuns, Clock clock, Runnable wakeScheduler,
                       Runnable wakeOutbox, boolean taskTopics, LongConsumer startSplit, boolean requiresWorker) {
        this(groups, projects, activeRuns, clock, wakeScheduler, wakeOutbox, taskTopics, startSplit, requiresWorker, null);
    }

    /** @param branchPrefix the instance's own prefix for task branches (M: several instances on one computer); null for "dispatch" */
    public TaskService(Groups groups, Projects projects, ActiveRuns activeRuns, Clock clock, Runnable wakeScheduler,
                       Runnable wakeOutbox, boolean taskTopics, LongConsumer startSplit, boolean requiresWorker, String branchPrefix) {
        this.branchPrefix = branchPrefix;
        this.taskTopics = taskTopics;
        this.startSplit = startSplit;
        this.requiresWorker = requiresWorker;
        this.groups = groups;
        this.projects = projects;
        this.activeRuns = activeRuns;
        this.access = new TaskAccess(groups);
        this.commands = new TaskCommands(groups, projects, activeRuns, clock, wakeScheduler, wakeOutbox, taskTopics, requiresWorker);
        this.clock = clock;
        this.wakeOutbox = wakeOutbox;
    }

    /** The task commands (ADR 0031): what a channel runs to act on a task. */
    public TaskCommands commands() {
        return commands;
    }

    /**
     * A member's private message becomes a draft (ADR 0012). Its project is the one the member named, else their only one,
     * else the one of their latest task still offered to them (for a draft given in a group, only among that group's
     * projects); with none of these its prompt opens on the detail view to ask.
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
     * The group a task was given in, by mentioning the bot (G-1b) or its developer (G-1c), and the first name it calls the
     * one it is for by.
     *
     * @param chatRef   the group chat, where the prompt's delivery is confirmed or, if refused, the giver is asked to press Start
     * @param sourceRef someone's message there the task was given in reply to, whose replies are additions to it; null if none
     */
    public record GroupOrigin(String chatRef, String firstName, String sourceRef) {
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
        String preselected = preselected(offered, named);
        // A group's draft takes a latest project only from that group's own, so ✅ never sends it to another group's project.
        Set<String> groupsOwn = group == null ? null : groups.projectsOfChat(group.chatRef());
        List<Config.Project> usable = group == null ? offered
                : offered.stream().filter(candidate -> groupsOwn.contains(candidate.name())).toList();
        String project = preselected != null ? preselected : lastUsed(tx, who.ref(), usable);
        long id = Drafts.insert(tx, new Drafts.NewDraft(who, chatRef, originRef, description, project, null, null,
                group == null ? null : group.sourceRef()), now);
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
        Optional<String> offered = offeredNamed(who.ref(), project);
        if (offered.isEmpty()) {
            return DraftChoice.PROJECT_UNAVAILABLE;
        }
        Drafts.chooseProject(tx, draftId, offered.get(), clock.instant());
        return DraftChoice.PROJECT_CHOSEN;
    }

    /**
     * The offered project {@code named} names: by its whole name, or by its start alone when a button's 64 bytes had no
     * room for more. A start that more than one offered project shares names none.
     */
    private Optional<String> offeredNamed(String requesterRef, String named) {
        List<String> offered = offeredProjects(requesterRef).stream().map(Config.Project::name).toList();
        if (offered.contains(named)) {
            return Optional.of(named);
        }
        // ponytail: two projects sharing their first ~36 characters cannot both be chosen by button; give them a short id if that happens.
        List<String> started = named.isEmpty() ? List.of() : offered.stream().filter(name -> name.startsWith(named)).toList();
        return started.size() == 1 ? Optional.of(started.getFirst()) : Optional.empty();
    }

    /** ✅ on a draft's prompt gives the task at the priority chosen there, LOW unless another was. */
    public DraftChoice send(Tx tx, Requester who, long draftId) {
        Optional<Draft> found = Drafts.find(tx, draftId);
        Optional<DraftChoice> refused = refusal(found, who);
        if (refused.isPresent()) {
            return refused.get();
        }
        return give(tx, who, found.get(), found.get().priority());
    }

    /**
     * A priority button of a prompt from before the lighter prompt: choosing it gives the task, as it always has, provided
     * the project is chosen and still one the member can use.
     */
    public DraftChoice choosePriority(Tx tx, Requester who, long draftId, Priority priority) {
        Optional<Draft> found = Drafts.find(tx, draftId);
        Optional<DraftChoice> refused = refusal(found, who);
        if (refused.isPresent()) {
            return refused.get();
        }
        return give(tx, who, found.get(), priority);
    }

    /** Whether a reply to {@code draftId}'s prompt would add to it: the draft is {@code who}'s and still open. */
    public boolean takesContext(Tx tx, Requester who, long draftId) {
        return refusal(Drafts.find(tx, draftId), who).isEmpty();
    }

    /**
     * A writer's reply to their open draft's prompt adds context: its text goes under the description's
     * {@value #CONTEXT_HEADING} section, one paragraph per reply, and its files join the draft's. The prompt is redrawn with
     * the count. False when the reply is not this, so it keeps its old meaning: the draft is someone else's or no longer
     * open, or the reply has neither text nor files. A reply to a whole message's prompt while its split is SPLITTING or
     * PROPOSED goes to that whole draft: if it is then split, its files reach every part (copied with the rest) but its
     * text does not, since each part's description is its proposed topic.
     *
     * @param files     the reply's files, numbered on from the draft's so none collide
     * @param promptRef the prompt replied to
     */
    public boolean addContext(Tx tx, Requester who, long draftId, String text, List<Attachment> files, String promptRef) {
        Optional<Draft> found = Drafts.find(tx, draftId);
        String added = text == null ? "" : text.strip();
        if (refusal(found, who).isPresent() || (added.isEmpty() && files.isEmpty())) {
            return false;
        }
        Instant now = clock.instant();
        Draft draft = found.get();
        String description = draft.description();
        if (!added.isEmpty()) {
            boolean first = !description.contains("\n\n" + CONTEXT_HEADING + "\n");
            description = description + "\n\n" + (first ? CONTEXT_HEADING + "\n" : "") + added;
        }
        Drafts.addContext(tx, draftId, description, now);
        Attachments.addToDraft(tx, draftId, files);
        Outbox.enqueueEdit(tx, null, OutboxKind.DRAFT_PROMPT, draft.chatRef(), promptRef, draftPayload(tx, draftId).orElseThrow(), now);
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(() -> Log.info("draft.context_added", "draft", draftId, "files", files.size()));
        return true;
    }

    /** A priority in the detail view: chosen for ✅ to give the task with, never giving it itself. */
    public DraftChoice pickPriority(Tx tx, Requester who, long draftId, Priority priority) {
        Optional<DraftChoice> refused = refusal(Drafts.find(tx, draftId), who);
        if (refused.isPresent()) {
            return refused.get();
        }
        Drafts.choosePriority(tx, draftId, priority, clock.instant());
        return DraftChoice.PRIORITY_CHOSEN;
    }

    /** ⚙️ shows the prompt's detail view, ↩️ its short one again, which needs a project to show. */
    public DraftChoice showDetail(Tx tx, Requester who, long draftId, boolean detail) {
        Optional<Draft> found = Drafts.find(tx, draftId);
        Optional<DraftChoice> refused = refusal(found, who);
        if (refused.isPresent()) {
            return refused.get();
        }
        if (!detail && found.get().project() == null) {
            return DraftChoice.CHOOSE_PROJECT_FIRST;
        }
        Drafts.showDetail(tx, draftId, detail, clock.instant());
        return DraftChoice.VIEW_CHANGED;
    }

    private DraftChoice give(Tx tx, Requester who, Draft draft, Priority priority) {
        long draftId = draft.id();
        if (draft.project() == null) {
            return DraftChoice.CHOOSE_PROJECT_FIRST;
        }
        CommandResult given = commands.run(tx, who, new TaskCommand.Give(draft.project(), draft.description(), priority,
                new Origin(draft.originRef())));
        if (!(given instanceof CommandResult.Created created)) {
            // The project stopped taking tasks, or the member left its groups, since the prompt offered it.
            return DraftChoice.PROJECT_UNAVAILABLE;
        }
        Drafts.created(tx, draftId, created.taskId(), clock.instant());
        Attachments.giveToTask(tx, draftId, created.taskId());
        return DraftChoice.CREATED;
    }

    /**
     * What a draft's prompt shows: while open, its view, project and priority, the projects to choose from and how far a
     * split has come; once created, the task; once split, its parts.
     */
    public Optional<ObjectNode> draftPayload(Tx tx, long draftId) {
        return Drafts.find(tx, draftId).map(draft -> {
            ObjectNode payload = Json.object().put("draftId", draft.id()).put("title", title(draft.description()))
                    .put("status", draft.status().name()).put("project", draft.project()).put("taskId", draft.taskId())
                    .put("topic", taskTopics);
            ArrayNode listed = payload.putArray("projects");
            offeredProjects(draft.requesterRef())
                    .forEach(project -> listed.addObject().put("name", project.name()).put("alias", project.alias()));
            payload.put("priority", draft.taskId() == null ? draft.priority().name()
                    : Tasks.find(tx, draft.taskId()).map(task -> task.priority().name()).orElse(null));
            // With no project there is nothing for the short view to send: the detail view asks for one.
            payload.put("view", draft.detail() || draft.project() == null ? "DETAIL" : "DEFAULT");
            payload.put("additions", draft.additions());
            payload.put("split", draft.splitState() == null ? null : draft.splitState().name());
            ArrayNode skipped = payload.putArray("skippedFiles");
            Attachments.forDraft(tx, draftId).stream().filter(Attachment::tooLarge).forEach(file -> skipped.add(file.name()));
            payload.put("splittable", startSplit != null && draft.status() == DraftStatus.OPEN && draft.parentId() == null
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
        if (startSplit == null || !Drafts.startSplit(tx, draftId, promptRef, clock.instant())) {
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
                    draftId, part, whole.sourceRef()), now);
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

    /**
     * 🗑 on a draft's prompt: its writer says it is not a task, e.g. a question that mentioned them in a group. The draft
     * closes, and the 👀 its delivered prompt put on that group message comes off again (G-1e) — unless the message
     * mentioned several members, whose drafts share its one reaction.
     */
    public DraftChoice discard(Tx tx, Requester who, long draftId) {
        Instant now = clock.instant();
        Optional<Draft> found = Drafts.find(tx, draftId);
        Optional<DraftChoice> refused = refusal(found, who);
        if (refused.isPresent()) {
            return refused.get();
        }
        if (!Drafts.discard(tx, draftId, now)) {
            return DraftChoice.ALREADY_DISCARDED;
        }
        Draft draft = found.get();
        String origin = draft.originRef();
        boolean ownGroupMessage = draft.parentId() == null && !origin.contains("#") && !origin.startsWith(draft.chatRef() + "/");
        if (ownGroupMessage && MemberPrefs.groupAck(tx, GroupAcks.userId(who.ref())) != GroupAck.SILENT) {
            enqueue(tx, null, OutboxKind.GROUP_REACTION, origin.substring(0, origin.indexOf('/')), origin,
                    Json.object().put("emoji", ""), now);
        }
        tx.afterCommit(() -> Log.info("draft.discarded", "draft", draftId, "requester", who.ref()));
        return DraftChoice.DISCARDED;
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
            case DISCARDED -> Optional.of(DraftChoice.ALREADY_DISCARDED);
        };
    }

    /** The project named or chosen, if the member can still use it; otherwise their only project, if they have just one. */
    private static String preselected(List<Config.Project> offered, String named) {
        if (named != null && offered.stream().anyMatch(project -> project.name().equals(named))) {
            return named;
        }
        return offered.size() == 1 ? offered.getFirst().name() : null;
    }

    /** The project of the member's latest task that is one of {@code offered}; null for none. */
    private static String lastUsed(Tx tx, String requesterRef, List<Config.Project> offered) {
        Set<String> names = new HashSet<>();
        offered.forEach(project -> names.add(project.name()));
        return Tasks.recentProjects(tx, requesterRef).stream().filter(names::contains).findFirst().orElse(null);
    }

    /** The member's projects that can take tasks now, in config order. */
    private List<Config.Project> offeredProjects(String requesterRef) {
        Set<String> mine = groups.projectsOfMember(requesterRef);
        return projects.all().stream()
                .filter(project -> mine.contains(project.name()) && projects.unavailableReason(project).isEmpty())
                .toList();
    }

    public Optional<Task> taskOfTopic(Tx tx, String requesterRef, String topicRef) {
        return Tasks.findByTopic(tx, requesterRef, topicRef);
    }

    /** Null when the task is not among the active ones this viewer may see, e.g. a run of another group's project. */
    private static String requesterName(Task task) {
        return task == null ? null : task.requester().name();
    }

    /**
     * Whether {@code viewer} sees all of the task: its requester, or the owner on the desktop (D-2); a group chat owns
     * nothing (ADR 0020).
     */
    private static boolean isOwn(TaskAccess.Viewer viewer, Task task) {
        return task != null && viewer.sees(task) == TaskAccess.Sight.FULL;
    }

    /** Whether {@code viewer} gave the task: what "mine" means on a list, apart from how much of it they see (D-2). */
    private static boolean isMine(TaskAccess.Viewer viewer, Task task) {
        return task != null && viewer.ref() != null && task.requester().ref().equals(viewer.ref());
    }

    /** What {@code viewer} may do with each listed task now; nothing for a group chat, which acts on no task (ADR 0027). */
    private Map<Long, List<TaskAccess.Action>> actionsOf(Tx tx, TaskAccess.Viewer viewer, Collection<Task> listed) {
        Map<Long, List<TaskAccess.Action>> actions = new HashMap<>();
        if (viewer.ref() == null) {
            return actions;
        }
        // ponytail: one verdict per listed task (a few indexed reads each); batch the run reads if a list ever holds hundreds.
        for (Task task : listed) {
            List<TaskAccess.Action> allowed = access.of(tx, viewer.ref(), task).allowed();
            // A team bot cannot merge: its members' own computers hold the credentials that delivered each pull request.
            actions.put(task.id(), requiresWorker ? allowed.stream().filter(action -> action != TaskAccess.Action.MERGE).toList() : allowed);
        }
        return actions;
    }

    private static void putActions(ObjectNode item, List<TaskAccess.Action> actions) {
        ArrayNode listed = item.putArray("actions");
        if (actions != null) {
            actions.forEach(action -> listed.add(action.json()));
        }
    }

    /**
     * What Dispatch is doing on the tasks {@code viewer} sees: running runs with their agent's latest action, then queued
     * runs, then plans awaiting approval. The content of a status message, also used to update one in place after a
     * priority change. Every task carries what the viewer may do with it now ({@code actions}); {@code mine} lists the tasks
     * whose priority they may change, which a private chat offers as buttons.
     */
    public ObjectNode statusPayload(Tx tx, TaskAccess.Viewer viewer) {
        ObjectNode payload = Json.object();
        Map<Long, Task> active = new LinkedHashMap<>();
        for (Task task : Tasks.active(tx)) {
            if (viewer.sees(task) != TaskAccess.Sight.NONE) {
                active.put(task.id(), task);
            }
        }
        Map<Long, List<TaskAccess.Action>> actions = actionsOf(tx, viewer, active.values());
        ArrayNode running = payload.putArray("running");
        ArrayNode queued = payload.putArray("queued");
        Instant workerSeenSince = requiresWorker ? clock.instant().minus(Workers.SEEN_WITHIN) : null;
        for (Runs.InProgress run : Runs.inProgress(tx, viewer.projects(), viewer.ref())) {
            Task task = active.get(run.taskId());
            boolean own = isOwn(viewer, task);
            boolean isRunning = run.status() == RunStatus.RUNNING;
            ObjectNode item = (isRunning ? running : queued).addObject().put("taskId", run.taskId()).put("project", run.project())
                    .put("title", run.title()).put("kind", run.kind().name()).put("priority", priority(task))
                    // Who gave it: the name for an admin's Tasks page to show (ADR 0020 allows it), and a flag for the
                    // Mini App's My tasks page, which keeps only the viewer's own and cannot go by name — two members
                    // may share a first name.
                    .put("requester", requesterName(task))
                    .put("mine", isMine(viewer, task));
            putActions(item, actions.get(run.taskId()));
            if (isRunning) {
                item.put("startedAt", text(run.startedAt()));
                if (own) {
                    activeRuns.activity(run.taskId()).ifPresent(activity ->
                            item.put("steps", activity.steps()).put("lastAction", activity.lastAction()));
                }
            } else {
                item.put("queuedAt", text(run.queuedAt()));
                boolean waitingForWorker = requiresWorker && task != null && waitsForWorker(tx, task, workerSeenSince);
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
        for (Task task : Tasks.withPhase(tx, Phase.AWAITING_APPROVAL, viewer.projects(), viewer.ref())) {
            boolean own = isOwn(viewer, task);
            ObjectNode item = awaiting.addObject().put("taskId", task.id()).put("project", task.project()).put("title", task.title())
                    .put("priority", task.priority().name()).put("requester", task.requester().name())
                    .put("mine", isMine(viewer, task)).put("since", text(task.updatedAt()));
            putActions(item, actions.get(task.id()));
            if (own) {
                // What the Mini App's home shows on the requester's own waiting task: the question it waits on, if any.
                currentPlan(tx, task.id()).ifPresent(plan -> {
                    item.put("planSeq", plan.path("planSeq").asInt());
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
        ArrayNode prioritized = payload.putArray("mine");
        // A private chat's priority buttons: the tasks whose priority task access lets this viewer change.
        active.values().stream().filter(task -> actions.getOrDefault(task.id(), List.of()).contains(TaskAccess.Action.PRIORITY))
                .forEach(task -> prioritized.addObject().put("taskId", task.id()).put("priority", task.priority().name()));
        return payload;
    }

    /** Each project's agent (ADR 0026): what a member's computer is asked about before it gets that project's run. */
    public Map<String, String> agentsOfProjects() {
        Map<String, String> agents = new HashMap<>();
        projects.all().forEach(project -> agents.put(project.name(), project.agent()));
        return agents;
    }

    /**
     * Tells each requester what on their computer holds their queued run — once per reason, not once per call — and
     * forgets the reason once nothing holds it, so /status stops saying so. The scheduler calls this whenever it found
     * nothing to claim: when the queue is held or full, never between two claims.
     *
     * @param workerSeenSince how recently a computer must have reported to count, as the scheduler's claim uses
     */
    public void reportBlocked(Tx tx, Instant workerSeenSince) {
        Map<String, String> agentOf = agentsOfProjects();
        for (Runs.InProgress run : Runs.queued(tx)) {
            Optional<Task> task = Tasks.find(tx, run.taskId());
            if (task.isEmpty()) {
                continue;
            }
            String requesterRef = task.get().requester().ref();
            Optional<Readiness.Blocker> blocker = Workers.blockerOf(tx, requesterRef, Tasks.workerOf(tx, run.taskId()).orElse(null),
                    workerSeenSince, run.project(), agentOf.getOrDefault(run.project(), AgentKind.CLAUDE_CODE.id()), run.kind());
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
     * The most recently finished tasks {@code viewer} sees, newest first; the total cost is shown only on the viewer's own
     * tasks, everyone else's carry just the headline (ADR 0020). The content of a history message; also what the Mini App's
     * task list reads (spec: Task pages).
     */
    public ObjectNode historyPayload(Tx tx, TaskAccess.Viewer viewer) {
        return historyPayload(tx, viewer, HISTORY_SIZE);
    }

    /** @param limit how many of the most recently finished tasks; the desktop lists a month's (D-2) */
    public ObjectNode historyPayload(Tx tx, TaskAccess.Viewer viewer, int limit) {
        List<Task> finished = Tasks.finished(tx, viewer.projects(), viewer.ref(), limit);
        Map<Long, BigDecimal> costs = Runs.costs(tx, finished.stream().map(Task::id).toList());
        Map<Long, List<TaskAccess.Action>> actions = actionsOf(tx, viewer, finished);
        ObjectNode payload = Json.object();
        ArrayNode listed = payload.putArray("tasks");
        for (Task task : finished) {
            boolean own = isOwn(viewer, task);
            BigDecimal cost = own ? costs.get(task.id()) : null;
            ObjectNode item = listed.addObject().put("taskId", task.id()).put("project", task.project()).put("title", task.title())
                    .put("mine", isMine(viewer, task))
                    .put("phase", task.phase().name()).put("priority", task.priority().name())
                    .put("requester", task.requester().name()).put("createdAt", text(task.createdAt())).put("prUrl", task.prUrl()).put("failureReason", name(task.failureReason()))
                    .put("costUsd", cost == null ? null : cost.toPlainString()).put("completedAt", text(task.completedAt()));
            putActions(item, actions.get(task.id()));
        }
        return payload;
    }

    /**
     * One task's timeline: its runs in order, how it ended, and what it cost; empty when {@code viewer} does not see it, so a
     * task's existence does not leak. Someone else's task stops at the headline: no runs, no cost (ADR 0020).
     */
    public Optional<ObjectNode> timelinePayload(Tx tx, TaskAccess.Viewer viewer, long taskId) {
        Optional<Task> found = Tasks.find(tx, taskId).filter(task -> viewer.sees(task) != TaskAccess.Sight.NONE);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Task task = found.get();
        ObjectNode payload = Json.object().put("taskId", task.id()).put("project", task.project()).put("title", task.title())
                .put("requester", task.requester().name()).put("phase", task.phase().name()).put("priority", task.priority().name())
                .put("prUrl", task.prUrl()).put("baseBranch", task.baseBranch()).put("branch", Config.branch(branchPrefix, task.id()))
                .put("failureReason", name(task.failureReason())).put("createdAt", text(task.createdAt()))
                .put("completedAt", text(task.completedAt())).put("answered", Plan.answers(task.planJson()));
        putActions(payload, actionsOf(tx, viewer, List.of(task)).get(task.id()));
        if (!isOwn(viewer, task)) {
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
     * The task's latest run step by step, as the run monitor shows it (RM-3): its kind and status, each step with its times,
     * outcome and detail, and while it runs here the agent's latest action; empty when the task has no run. It does not
     * check who asks: the caller shows it to the requester alone.
     */
    public Optional<ObjectNode> runPayload(Tx tx, long taskId) {
        Optional<Run> latest = Runs.latest(tx, taskId);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        Run run = latest.get();
        ObjectNode payload = Json.object().put("taskId", taskId).put("seq", run.seq()).put("kind", run.kind().name())
                .put("status", run.status().name()).put("startedAt", text(run.startedAt())).put("finishedAt", text(run.finishedAt()))
                .put("costUsd", run.costUsd() == null ? null : run.costUsd().toPlainString())
                .put("now", clock.instant().toString());
        ArrayNode steps = payload.putArray("steps");
        for (RunStep step : RunSteps.of(tx, taskId, run.seq())) {
            ObjectNode item = steps.addObject().put("n", step.n()).put("kind", step.kind().name()).put("round", step.round())
                    .put("startedAt", step.startedAt().toString()).put("endedAt", text(step.endedAt()))
                    .put("outcome", step.outcome() == null ? null : step.outcome().name());
            if (step.detail() != null) {
                item.set("detail", Json.read(step.detail()));
            }
        }
        if (run.status() == RunStatus.RUNNING) {
            activeRuns.activity(taskId).ifPresent(activity ->
                    payload.putObject("activity").put("steps", activity.steps()).put("lastAction", activity.lastAction()));
            activeRuns.run(taskId).ifPresent(active -> putControls(payload, RunSteps.of(tx, taskId, run.seq()), active));
        }
        payload.set("teleport", teleportPayload(tx, taskId));
        return Optional.of(payload);
    }

    /**
     * What the run monitor offers now (RM-4): ⏭ on the running test, fix or review, by its number, unless it was already
     * skipped; 📦 while such a step runs, until it was asked for. Whether the caller may use them is TaskAccess's STEER.
     */
    private static void putControls(ObjectNode payload, List<RunStep> steps, ActiveRuns.ActiveRun active) {
        RunStep current = steps.isEmpty() || steps.getLast().outcome() != null ? null : steps.getLast();
        boolean loopStep = current != null && (current.kind() == RunStep.Kind.TEST || current.kind() == RunStep.Kind.FIX
                || current.kind() == RunStep.Kind.REVIEW);
        boolean paused = current != null && current.kind() == RunStep.Kind.PAUSE;
        ObjectNode controls = payload.putObject("controls");
        if (loopStep && !active.skipRequested(current.n())) {
            controls.put("skip", current.n());
        } else {
            controls.putNull("skip");
        }
        controls.put("deliverNow", (loopStep || paused) && !active.deliverNowRequested());
        controls.put("deliverNowRequested", active.deliverNowRequested());
        // ⏸ can be switched until the review is reached; while paused, 🔍 and the time it goes on by itself (RM-5).
        boolean reviewReached = steps.stream().anyMatch(step -> step.kind() == RunStep.Kind.PAUSE || step.kind() == RunStep.Kind.REVIEW);
        controls.put("pauseBeforeReview", active.pauseBeforeReviewRequested());
        controls.put("canPause", !reviewReached && !active.deliverNowRequested());
        controls.put("paused", paused && !active.resumeRequested() && !active.deliverNowRequested());
        controls.put("pauseEndsAt", paused ? current.startedAt().plus(VerifyLoop.PAUSE_LIMIT).toString() : null);
    }

    /** ⏸ on or off for the task's running execution (RM-5); false when no run of the task is active here. */
    public boolean pauseBeforeReview(long taskId, boolean on) {
        Optional<ActiveRuns.ActiveRun> run = activeRuns.run(taskId);
        run.ifPresent(active -> active.pauseBeforeReview(on));
        return run.isPresent();
    }

    /** 🔍 on a paused run: the review starts now (RM-5); false when no run of the task is active here. */
    public boolean resume(long taskId) {
        Optional<ActiveRuns.ActiveRun> run = activeRuns.run(taskId);
        run.ifPresent(ActiveRuns.ActiveRun::resume);
        return run.isPresent();
    }

    /**
     * Task #N's session as a terminal can go on with it (RM-6): the command, the line to paste, and why not when it cannot.
     * It does not check who asks: the caller shows it to the requester alone.
     */
    public ObjectNode teleportPayload(Tx tx, long taskId) {
        String agent = Tasks.find(tx, taskId).flatMap(task -> projects.byName(task.project())).map(Config.Project::agent)
                .orElse(AgentKind.CLAUDE_CODE.id());
        Teleport teleport = Teleport.of(tx, taskId, false, agent);
        ObjectNode payload = Json.object().put("taskId", taskId).put("command", "dispatch teleport " + taskId)
                .put("reason", teleport.refusal() == null ? null : teleport.refusal().name())
                .put("worker", teleport.worker()).put("agent", agent);
        payload.put("line", teleport.workdir() == null || teleport.session() == null ? null : teleport.shellLine());
        return payload;
    }

    /** ⏭ on the task's running step (RM-4); false when no run of the task is active here. */
    public boolean skip(long taskId, int step) {
        return activeRuns.skip(taskId, step);
    }

    /** 📦 deliver now on the task's running execution (RM-4); false when no run of the task is active here. */
    public boolean deliverNow(long taskId) {
        return activeRuns.deliverNow(taskId);
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
        // Every field of the plan, so a new one reaches the pages from Plan alone; each question gains its number and answer.
        ObjectNode payload = plan.view().put("planSeq", planSeq.getAsInt());
        ArrayNode questions = payload.putArray("questions");
        for (int index = 1; index <= plan.questionItems().size(); index++) {
            PlanQuestion question = plan.questionItems().get(index - 1);
            ObjectNode item = questions.addObject().put("index", index).put("text", question.text()).put("answer", answers.get(index));
            question.options().forEach(item.putArray("options")::add);
        }
        return Optional.of(payload);
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

    /**
     * Tells {@code who} in {@code chatRef} that they may not give Dispatch tasks, and logs it. In a group chat it is only
     * logged: a busy group's chatter with the bot would otherwise be answered with a refusal line each time (G-1b).
     */
    public void notAllowed(Tx tx, Requester who, String originRef, String chatRef, Instant now) {
        boolean inGroup = groups.isGroupChat(chatRef);
        if (!inGroup) {
            enqueue(tx, null, OutboxKind.NOT_ALLOWED, chatRef, originRef, Json.object().put("name", who.name()), now);
        }
        tx.afterCommit(() -> Log.warn("member.not_allowed", "requester", who.ref(), "name", who.name(), "chat", chatRef,
                "answered", !inGroup));
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
