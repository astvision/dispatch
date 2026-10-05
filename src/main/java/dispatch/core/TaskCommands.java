package dispatch.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Language;
import dispatch.Log;
import dispatch.Text;
import dispatch.config.Config;
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
import dispatch.domain.Task;
import dispatch.store.Events;
import dispatch.store.Outbox;
import dispatch.store.PlanAnswers;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import dispatch.store.Workers;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The task commands (ADR 0031). Each asks {@link TaskAccess} what the member may do (ADR 0027), changes the task, writes
 * the task's news in the caller's transaction, and says what happened. None writes a reply: the channel that ran it
 * answers whoever acted. A refused command writes nothing at all.
 */
public final class TaskCommands {

    /** Who acts when the watcher does, in a task's events and the log (spec: CI watch). */
    public static final String CI_ACTOR = "ci";

    private final Groups groups;
    private final Projects projects;
    private final ActiveRuns activeRuns;
    private final TaskAccess access;
    private final Clock clock;
    private final Runnable wakeScheduler;
    private final Runnable wakeOutbox;
    private final boolean taskTopics;
    private final boolean requiresWorker;

    /**
     * @param taskTopics     the channel can give each task its own topic in the requester's private chat
     * @param requiresWorker team mode: a task runs on its requester's own computer, so it waits when none of theirs is
     *                       connected (W-3)
     */
    TaskCommands(Groups groups, Projects projects, ActiveRuns activeRuns, Clock clock, Runnable wakeScheduler, Runnable wakeOutbox,
                 boolean taskTopics, boolean requiresWorker) {
        this.groups = groups;
        this.projects = projects;
        this.activeRuns = activeRuns;
        this.access = new TaskAccess(groups);
        this.clock = clock;
        this.wakeScheduler = wakeScheduler;
        this.wakeOutbox = wakeOutbox;
        this.taskTopics = taskTopics;
        this.requiresWorker = requiresWorker;
    }

    /** Does {@code command} for {@code who} in the caller's transaction: a refusal writes nothing. */
    public CommandResult run(Tx tx, Requester who, TaskCommand command) {
        Optional<CommandResult.Refused> refused = check(tx, who, command);
        CommandResult result = refused.isPresent() ? refused.get() : carryOut(tx, who, command);
        // One line per command, whichever channel ran it; never its text, which may hold anything.
        tx.afterCommit(() -> Log.info("task.command", "command", command.getClass().getSimpleName(), "task", taskId(command),
                "actor", who.ref(), "result", name(result)));
        return result;
    }

    /**
     * What {@link #run} would refuse now, writing nothing; empty when it would act. For offering an action (a proposal, a
     * prompt), never for guarding one: {@link #run} checks again.
     */
    public Optional<CommandResult.Refused> check(Tx tx, Requester who, TaskCommand command) {
        return switch (command) {
            case TaskCommand.Cancel cancel -> refusal(tx, who, command, cancel.taskId(), TaskAccess.Action.CANCEL);
            case TaskCommand.Retry retry -> refusal(tx, who, command, retry.taskId(), TaskAccess.Action.RETRY);
            case TaskCommand.Approve approve -> planRefusal(tx, who, command, approve.taskId(), approve.planSeq(), TaskAccess.Action.APPROVE);
            case TaskCommand.Reject reject -> planRefusal(tx, who, command, reject.taskId(), reject.planSeq(), TaskAccess.Action.REJECT);
            case TaskCommand.Reprioritize reprioritize -> refusal(tx, who, command, reprioritize.taskId(), TaskAccess.Action.PRIORITY);
            case TaskCommand.Correct correct -> {
                TaskAccess.Verdict verdict = access.of(tx, who.ref(), correct.taskId());
                int planSeq = correct.planSeq().orElse(verdict.planSeq());
                Optional<Refusal> refused = verdict.refusal(TaskAccess.Action.CORRECT, planSeq);
                if (refused.isEmpty() && isBlank(correct.text())) {
                    refused = Optional.of(Refusal.EMPTY);
                }
                yield refused.map(refusal -> refused(command, refusal, verdict.task()));
            }
            case TaskCommand.FollowUp followUp -> {
                TaskAccess.Verdict verdict = access.of(tx, who.ref(), followUp.taskId());
                Optional<Refusal> refused = verdict.refusal(TaskAccess.Action.FOLLOW_UP);
                if (refused.isEmpty() && isBlank(followUp.text())) {
                    refused = Optional.of(Refusal.EMPTY);
                }
                if (refused.isPresent()) {
                    yield refused.map(refusal -> refused(command, refusal, verdict.task()));
                }
                // A merged task's branch is gone: more work is a new task, refused exactly as giving one is.
                yield verdict.task().mergedAt() == null ? Optional.empty() : check(tx, who, newTaskFrom(verdict.task(), followUp));
            }
            case TaskCommand.Answer answer -> {
                TaskAccess.Verdict verdict = access.of(tx, who.ref(), answer.taskId());
                Optional<Refusal> refused = verdict.answerRefusal(answer.planSeq(), answer.question());
                if (refused.isEmpty() && answerText(verdict.task(), answer).isEmpty()) {
                    refused = Optional.of(Refusal.EMPTY);
                }
                yield refused.map(refusal -> refused(command, refusal, verdict.task()));
            }
            case TaskCommand.Give give -> giveRefusal(tx, who, give);
        };
    }

    private CommandResult carryOut(Tx tx, Requester who, TaskCommand command) {
        return switch (command) {
            case TaskCommand.Cancel cancel -> cancel(tx, who, task(tx, cancel.taskId()));
            case TaskCommand.Retry retry -> retry(tx, who, task(tx, retry.taskId()));
            case TaskCommand.Approve approve -> approve(tx, who, task(tx, approve.taskId()), approve.planSeq());
            case TaskCommand.Reject reject -> reject(tx, who, task(tx, reject.taskId()), reject.planSeq());
            case TaskCommand.Reprioritize reprioritize -> reprioritize(tx, who, task(tx, reprioritize.taskId()), reprioritize.priority());
            case TaskCommand.Correct correct -> {
                Task task = task(tx, correct.taskId());
                yield correct(tx, who, task, correct.planSeq().orElseGet(() -> access.of(tx, who.ref(), task).planSeq()),
                        correct.text().strip());
            }
            case TaskCommand.FollowUp followUp -> followUp(tx, who, task(tx, followUp.taskId()), followUp);
            case TaskCommand.Answer answer -> {
                Task task = task(tx, answer.taskId());
                String text = answerText(task, answer).orElseThrow(() -> new IllegalStateException("task #" + task.id()
                        + " was allowed an answer to question " + answer.question() + ", yet its "
                        + answer.choice().getClass().getSimpleName() + " choice says nothing"));
                yield answer(tx, who, task, answer, text);
            }
            case TaskCommand.Give give -> give(tx, who, give, true);
        };
    }

    private Optional<CommandResult.Refused> refusal(Tx tx, Requester who, TaskCommand command, long taskId, TaskAccess.Action action) {
        TaskAccess.Verdict verdict = access.of(tx, who.ref(), taskId);
        return verdict.refusal(action).map(refusal -> refused(command, refusal, verdict.task()));
    }

    /** For a decision that names the plan it was shown: a plan a newer one replaced is stale (ADR 0027). */
    private Optional<CommandResult.Refused> planRefusal(Tx tx, Requester who, TaskCommand command, long taskId, int planSeq,
                                                        TaskAccess.Action action) {
        TaskAccess.Verdict verdict = access.of(tx, who.ref(), taskId);
        return verdict.refusal(action, planSeq).map(refusal -> refused(command, refusal, verdict.task()));
    }

    /**
     * Cancels the task; an admin may cancel any task, even outside their own groups. A running agent is stopped after
     * commit and its run ends as CANCELLED. The group hears it, and so does the requester in the task's own topic, whoever
     * cancelled it (ADR 0031).
     */
    private CommandResult cancel(Tx tx, Requester who, Task task) {
        Instant now = clock.instant();
        changePhase(tx, task, task.phase(), Phase.CANCELLED, now);
        Runs.cancelQueued(tx, task.id(), now);
        Events.record(tx, task.id(), null, who.ref(), task.phase(), Phase.CANCELLED, "cancelled", now);
        ObjectNode cancelled = Json.object().put("taskId", task.id()).put("by", who.name());
        Outbox.enqueue(tx, task.id(), OutboxKind.TASK_CANCELLED, task.chatRef(), task.groupOriginRef(), cancelled, now);
        if (task.hasGroupChat()) {
            // No fallback to the group: the group has its own line already. A personal bot's task chat is the requester's.
            Outbox.enqueue(tx, task.id(), OutboxKind.TASK_CANCELLED, task.requester().ref(), task.privateOriginRef(), cancelled, now);
        }
        GroupAcks.react(tx, task, GroupReaction.ENDED, now);
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(() -> activeRuns.stop(task.id(), ActiveRuns.StopReason.CANCELLED));
        logTransition(tx, task.id(), task.phase(), Phase.CANCELLED, who.ref());
        return new CommandResult.Done(task.id(), isRequester(who, task));
    }

    /** Repeats the failed step: a plan, an execution, or only the delivery of work already done (ADR 0008). */
    private CommandResult retry(Tx tx, Requester who, Task task) {
        Instant now = clock.instant();
        // Task access saw the latest run fail: that run is the step to repeat.
        Run step = Runs.latest(tx, task.id()).orElseThrow(() -> new IllegalStateException("task #" + task.id()
                + " was allowed a retry, yet has no run to repeat"));
        RunKind kind;
        String instruction;
        if (step.kind() == RunKind.PLAN) {
            kind = RunKind.PLAN;
            instruction = step.instruction();
        } else if (step.kind() == RunKind.DELIVER || step.failureReason() == FailureReason.DELIVERY) {
            // The agent's work is done and waits in the worktree; only its summary is needed, as the commit message.
            kind = RunKind.DELIVER;
            instruction = step.kind() == RunKind.DELIVER ? step.instruction() : Runs.output(tx, task.id(), step.seq()).orElse("");
        } else {
            kind = RunKind.EXECUTE;
            instruction = step.instruction();
        }
        Phase to = kind == RunKind.PLAN ? Phase.PLANNING : Phase.EXECUTING;
        // A retried CI fix is still one: its instruction is a failed log, which only the fix prompt quotes as output to read.
        RunCause cause = kind == RunKind.EXECUTE && step.cause() == RunCause.CI_FIX ? RunCause.CI_FIX : RunCause.RETRY;
        return queueRun(tx, who, task, to, kind, cause, instruction, "retry of run " + step.seq(), true,
                OutboxKind.RETRY_QUEUED, now);
    }

    /**
     * Queues a run of {@code task} and moves it to the run's phase: every command that starts work does these steps, in
     * this order, and nothing else. The requester hears {@code news}; the scheduler and the outbox wake once it commits.
     *
     * @param reason        the task's event, in words
     * @param eventNamesRun whether that event belongs to the new run (a retry, a follow-up) or to the plan it acts on
     */
    private CommandResult queueRun(Tx tx, Requester who, Task task, Phase to, RunKind kind, RunCause cause, String instruction,
                                   String reason, boolean eventNamesRun, OutboxKind news, Instant now) {
        return queueRun(tx, who, who.ref(), task, to, kind, cause, instruction, reason, eventNamesRun, news, Json.object(), now);
    }

    /**
     * The same, for a run nobody's command queued.
     *
     * @param actor whom the task's event names: {@code who}'s reference, unless no member acted
     * @param more  what the news says besides the task, in whose name it runs and the run's kind
     */
    private CommandResult queueRun(Tx tx, Requester who, String actor, Task task, Phase to, RunKind kind, RunCause cause,
                                   String instruction, String reason, boolean eventNamesRun, OutboxKind news, ObjectNode more,
                                   Instant now) {
        Phase from = task.phase();
        changePhase(tx, task, from, to, now);
        int seq = Runs.nextSeq(tx, task.id());
        Runs.insert(tx, new Runs.NewRun(task.id(), seq, kind, cause, instruction, who), now);
        Events.record(tx, task.id(), eventNamesRun ? seq : null, actor, from, to, reason, now);
        Outbox.enqueueForRequester(tx, task, news,
                more.put("taskId", task.id()).put("by", who.name()).put("kind", kind.name()), now);
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(wakeScheduler);
        logTransition(tx, task.id(), from, to, actor);
        return new CommandResult.Done(task.id(), isRequester(who, task));
    }

    /**
     * The checks failed on the commit Dispatch delivered (spec: CI watch): one more execution in the task's building
     * session, queued in its requester's name. No member's command asks for it, so no access is asked; what is checked is
     * that the task is still the delivered, unmerged one whose branch is at {@code head}. False, writing nothing, when it
     * is not.
     *
     * @param news what CI_FIX_QUEUED says besides the task: the first failed check and the round
     */
    public boolean ciFix(Tx tx, long taskId, String head, String instruction, ObjectNode news) {
        Optional<Task> found = Tasks.find(tx, taskId).filter(task -> task.phase() == Phase.COMPLETED && task.mergedAt() == null
                && head.equals(Tasks.expectedHead(tx, taskId)));
        if (found.isEmpty()) {
            return false;
        }
        queueRun(tx, found.get().requester(), CI_ACTOR, found.get(), Phase.EXECUTING, RunKind.EXECUTE, RunCause.CI_FIX, instruction,
                "ci-fix", true, OutboxKind.CI_FIX_QUEUED, news, clock.instant());
        tx.afterCommit(() -> Log.info("task.command", "command", "CiFix", "task", taskId, "actor", CI_ACTOR, "result", "Done"));
        return true;
    }

    /** Queues the approved plan's execution; the requester hears it is queued. */
    private CommandResult approve(Tx tx, Requester who, Task task, int planSeq) {
        // The run carries the plan it implements, so what was approved stays on record.
        return queueRun(tx, who, task, Phase.EXECUTING, RunKind.EXECUTE, RunCause.APPROVAL, task.planJson(),
                "approved plan " + planSeq, false, OutboxKind.EXECUTION_QUEUED, clock.instant());
    }

    /** Ends the task: its chat hears who rejected it, and a group message that gave it shows it ended (G-1e). */
    private CommandResult reject(Tx tx, Requester who, Task task, int planSeq) {
        Instant now = clock.instant();
        changePhase(tx, task, Phase.AWAITING_APPROVAL, Phase.REJECTED, now);
        Events.record(tx, task.id(), null, who.ref(), Phase.AWAITING_APPROVAL, Phase.REJECTED, "rejected plan " + planSeq, now);
        Outbox.enqueue(tx, task.id(), OutboxKind.TASK_REJECTED, task.chatRef(), task.groupOriginRef(),
                Json.object().put("taskId", task.id()).put("by", who.name()), now);
        GroupAcks.react(tx, task, GroupReaction.ENDED, now);
        tx.afterCommit(wakeOutbox);
        logTransition(tx, task.id(), Phase.AWAITING_APPROVAL, Phase.REJECTED, who.ref());
        return new CommandResult.Done(task.id(), isRequester(who, task));
    }

    /** The priority it already has is no change and writes nothing (ADR 0012). */
    private CommandResult reprioritize(Tx tx, Requester who, Task task, Priority priority) {
        if (task.priority() == priority) {
            return new CommandResult.Unchanged(task.id());
        }
        Instant now = clock.instant();
        if (!Tasks.changePriority(tx, task.id(), priority, now)) {
            throw new IllegalStateException("task #" + task.id() + " was allowed a new priority, yet it finished");
        }
        Events.record(tx, task.id(), null, who.ref(), task.phase(), task.phase(), "priority " + task.priority() + " -> " + priority, now);
        tx.afterCommit(() -> Log.info("task.priority_changed", "task", task.id(), "from", task.priority(), "to", priority,
                "actor", who.ref()));
        return new CommandResult.Done(task.id(), isRequester(who, task));
    }

    /** The requester's correction: the task is planned again in its planning session, with it as the run's instruction. */
    private CommandResult correct(Tx tx, Requester who, Task task, int planSeq, String text) {
        return queueRun(tx, who, task, Phase.PLANNING, RunKind.PLAN, RunCause.CORRECTION, text,
                "correction of plan " + planSeq, false, OutboxKind.CORRECTION_QUEUED, clock.instant());
    }

    /**
     * More work on a finished task, in its building session and branch, without a new plan (ADR 0006). A merged task's
     * branch is gone, so its follow-up is a new task, planned from the base the merge moved and given from the follow-up's
     * origin; the old task's topic hears where it went.
     */
    private CommandResult followUp(Tx tx, Requester who, Task task, TaskCommand.FollowUp followUp) {
        Instant now = clock.instant();
        if (task.mergedAt() != null) {
            Optional<Task> replayed = Tasks.findByOrigin(tx, followUp.origin().ref());
            if (replayed.isPresent()) {
                return new CommandResult.Created(replayed.get().id());
            }
            CommandResult given = give(tx, who, newTaskFrom(task, followUp), false);
            if (given instanceof CommandResult.Created created) {
                Outbox.enqueueForRequester(tx, task, OutboxKind.FOLLOW_UP_NEW_TASK,
                        Json.object().put("taskId", task.id()).put("newTaskId", created.taskId()), now);
                tx.afterCommit(wakeOutbox);
            }
            return given;
        }
        // The reply to an answer is planned in the answer's own session: another answer, or a plan to approve. Any other
        // finished task continues in its building session, without a new plan (ADR 0006).
        boolean answered = Plan.answers(task.planJson());
        return queueRun(tx, who, task, answered ? Phase.PLANNING : Phase.EXECUTING, answered ? RunKind.PLAN : RunKind.EXECUTE,
                RunCause.FOLLOW_UP, followUp.text().strip(), "follow-up", true, OutboxKind.FOLLOW_UP_QUEUED, now);
    }

    /**
     * A merged task's follow-up as the task it becomes; the last line points back without words, so it never sways the
     * plan's language.
     */
    private static TaskCommand.Give newTaskFrom(Task merged, TaskCommand.FollowUp followUp) {
        return new TaskCommand.Give(merged.project(), followUp.text().strip() + "\n\n↩️ #" + merged.id() + " " + merged.prUrl(),
                merged.priority(), followUp.origin());
    }

    /**
     * An answer: the question message is redrawn with it and the next open question is sent; the last answer sends them all
     * to the agent as one correction, exactly as a typed one (G-1d).
     */
    private CommandResult answer(Tx tx, Requester who, Task task, TaskCommand.Answer answer, String text) {
        Instant now = clock.instant();
        int planSeq = answer.planSeq();
        int index = answer.question();
        List<PlanQuestion> questions = Plan.parse(task.planJson()).questionItems();
        String questionRef = Outbox.sentQuestion(tx, task.id(), planSeq, index).orElse(null);
        if (!PlanAnswers.record(tx, task.id(), planSeq, index, text, who.ref(), questionRef, now)) {
            throw new IllegalStateException("task #" + task.id() + " was allowed an answer to question " + index + ", yet it had one");
        }
        // The question's own row, not its Telegram message: an answer given before the question reached the chat redraws it
        // once it is sent, instead of the question going out a second time (ADR 0031).
        Outbox.questionRow(tx, task.id(), planSeq, index).ifPresent(row -> Outbox.enqueueEditOf(tx, task.id(), OutboxKind.PLAN_QUESTION,
                task.requester().ref(), row, questionPayload(task.id(), planSeq, questions, index).put("answer", text), now));
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(() -> Log.info("task.question_answered", "task", task.id(), "plan", planSeq, "question", index,
                "requester", who.ref()));
        Map<Integer, String> answers = PlanAnswers.of(tx, task.id(), planSeq);
        for (int next = 1; next <= questions.size(); next++) {
            if (!answers.containsKey(next)) {
                enqueueQuestion(tx, task, planSeq, questions, next, now);
                return new CommandResult.Done(task.id(), isRequester(who, task));
            }
        }
        return correct(tx, who, task, planSeq, answersText(questions, answers));
    }

    /** What an answer says, or empty when it says nothing: blank words, or an option the question does not have. */
    private static Optional<String> answerText(Task task, TaskCommand.Answer answer) {
        return switch (answer.choice()) {
            case TaskCommand.Choice.Written written ->
                    Optional.ofNullable(written.text()).map(String::strip).filter(text -> !text.isEmpty());
            case TaskCommand.Choice.YouDecide _ -> Optional.of(Text.of("answer.youDecide").render(Language.MN));
            case TaskCommand.Choice.Option option -> {
                List<PlanQuestion> questions = Plan.parse(task.planJson()).questionItems();
                List<String> options = questions.get(answer.question() - 1).options();
                yield option.index() >= 0 && option.index() < options.size() ? Optional.of(options.get(option.index())) : Optional.empty();
            }
        };
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

    /** The correction that carries every answer, in the requester's language like the buttons they pressed. */
    private static String answersText(List<PlanQuestion> questions, Map<Integer, String> answers) {
        StringBuilder text = new StringBuilder("Асуултын хариулт:");
        for (int index = 1; index <= questions.size(); index++) {
            text.append('\n').append(index).append(". ").append(questions.get(index - 1).text()).append(" → ").append(answers.get(index));
        }
        return text.toString();
    }

    /** Why giving would be refused: who, then the project, then the words. A repeated origin is no refusal: it gives the same task. */
    private Optional<CommandResult.Refused> giveRefusal(Tx tx, Requester who, TaskCommand.Give give) {
        if (Tasks.existsWithOrigin(tx, give.origin().ref())) {
            return Optional.empty();
        }
        if (!groups.isMember(who.ref())) {
            return Optional.of(refused(give, Refusal.NOT_MEMBER, null));
        }
        Optional<Config.Project> project = memberProject(who, give.project());
        if (project.isEmpty()) {
            return Optional.of(new CommandResult.Refused(Refusal.UNKNOWN_PROJECT,
                    Text.of("refused.unknownProject", nonNull(give.project()))));
        }
        Optional<String> unavailable = projects.unavailableReason(project.get());
        if (unavailable.isPresent()) {
            return Optional.of(new CommandResult.Refused(Refusal.PROJECT_UNAVAILABLE,
                    Text.of("refused.projectUnavailable", project.get().name(), unavailable.get())));
        }
        return isBlank(give.text()) ? Optional.of(refused(give, Refusal.EMPTY, null)) : Optional.empty();
    }

    /**
     * Gives the task, or finds the one its origin gave before. A task from a page tells its requester's chat so, since no
     * message of theirs there gave it (D-2b); a merged task's follow-up says where it went itself.
     */
    private CommandResult give(Tx tx, Requester who, TaskCommand.Give give, boolean fromPageSaysSo) {
        Optional<Task> replayed = Tasks.findByOrigin(tx, give.origin().ref());
        if (replayed.isPresent()) {
            tx.afterCommit(() -> Log.info("task.duplicate_ignored", "origin", give.origin().ref()));
            return new CommandResult.Created(replayed.get().id());
        }
        Instant now = clock.instant();
        // The groups live outside the store's lock: another thread may have replaced them since the check.
        Config.Project project = memberProject(who, give.project()).orElseThrow(() -> new IllegalStateException(
                who.ref() + " was allowed a task on " + give.project() + ", yet it is none of their groups' projects now"));
        long id = insertTask(tx, who, project, give.text().strip(), give.priority(), give.origin().ref(), now);
        if (fromPageSaysSo && give.origin().ref().startsWith(Origin.PAGE)) {
            Task task = task(tx, id);
            Outbox.enqueueForRequester(tx, task, OutboxKind.TASK_GIVEN_ON_DESK, Json.object().put("taskId", id)
                    .put("project", task.project()).put("priority", give.priority().name()).put("title", task.title()), now);
            tx.afterCommit(wakeOutbox);
        }
        return new CommandResult.Created(id);
    }

    /** The task with its first plan run queued. */
    private long insertTask(Tx tx, Requester who, Config.Project project, String description, Priority priority, String originRef,
                            Instant now) {
        Optional<String> groupChat = groups.chatOfTask(project.name(), originRef);
        // Without a group chat the task belongs to the requester's private chat, where nothing needs announcing (ADR 0014).
        long id = Tasks.insert(tx, new Tasks.NewTask(project.name(), TaskService.title(description), description, who, originRef,
                groupChat.orElse(who.ref()), UUID.randomUUID(), project.baseBranch(), priority), Phase.PLANNING, now);
        Runs.insert(tx, new Runs.NewRun(id, 1, RunKind.PLAN, RunCause.TASK, description, who), now);
        Events.record(tx, id, null, who.ref(), null, Phase.PLANNING, "created", now);
        if (taskTopics) {
            Outbox.enqueue(tx, id, OutboxKind.TOPIC_CREATE, who.ref(), null, Json.object().put("taskId", id), now);
            tx.afterCommit(wakeOutbox);
        }
        if (requiresWorker && !Workers.hasConnected(tx, who.ref(), now.minus(Workers.SEEN_WITHIN))) {
            // Said once, when the task is given; /status keeps showing it until a computer connects.
            Outbox.enqueue(tx, id, OutboxKind.WORKER_WAITING, who.ref(), null, Json.object().put("taskId", id), now);
            tx.afterCommit(wakeOutbox);
        }
        // The group gets no line of its own for a new task, only the reaction on a group message that gave it (G-1e).
        GroupAcks.react(tx, task(tx, id), GroupReaction.TASK_CREATED, now);
        if (groupChat.isPresent()) {
            tx.afterCommit(wakeOutbox);
        }
        tx.afterCommit(wakeScheduler);
        tx.afterCommit(() -> Log.info("task.created", "task", id, "project", project.name(), "priority", priority,
                "requester", who.ref()));
        return id;
    }

    /** The project {@code key} names (its name or alias), if it is one of the requester's groups' projects. */
    private Optional<Config.Project> memberProject(Requester who, String key) {
        Set<String> mine = groups.projectsOfMember(who.ref());
        return key == null ? Optional.empty() : projects.find(key).filter(project -> mine.contains(project.name()));
    }

    /** A refusal and its one wording (ADR 0031), naming the task as far as its headline allows (ADR 0020). */
    private static CommandResult.Refused refused(TaskCommand command, Refusal refusal, Task task) {
        return new CommandResult.Refused(refusal, words(command, refusal, task));
    }

    private static Text words(TaskCommand command, Refusal refusal, Task task) {
        Long taskId = taskId(command);
        return switch (refusal) {
            case NOT_MEMBER -> Text.of("refused.notMember");
            case NOT_FOUND -> Text.of("refused.notFound", taskId);
            case NOT_REQUESTER -> command instanceof TaskCommand.Cancel
                    ? Text.of("refused.cancelNotRequester", taskId, task.requester().name())
                    : Text.of("refused.notRequester", taskId, task.requester().name());
            case WRONG_PHASE -> Text.of("refused.wrongPhase", taskId, phase(task.phase()));
            case NOT_FAILED -> Text.of("refused.notFailed", taskId, phase(task.phase()));
            case STALE_PLAN -> Text.of("refused.stalePlan", taskId);
            case OPEN_QUESTIONS -> Text.of("refused.openQuestions", taskId);
            case OUT_OF_ORDER -> Text.of("refused.outOfOrder", taskId);
            case ALREADY_ANSWERED -> Text.of("refused.answered", taskId);
            case NOT_EXECUTED -> Text.of("refused.notExecuted", taskId);
            case EMPTY -> switch (command) {
                case TaskCommand.Correct _ -> Text.of("refused.emptyCorrection");
                case TaskCommand.FollowUp _ -> Text.of("refused.emptyFollowUp");
                case TaskCommand.Answer _ -> Text.of("refused.emptyAnswer");
                case TaskCommand.Give _ -> Text.of("refused.emptyTask");
                // No words of their own to be blank.
                case TaskCommand.Cancel _, TaskCommand.Retry _, TaskCommand.Approve _, TaskCommand.Reject _, TaskCommand.Reprioritize _ ->
                        throw new IllegalStateException(command.getClass().getSimpleName() + " of task " + taskId
                                + " is never refused as " + refusal);
            };
            // Merges' own refusal, and giving's, which words its project where it is refused.
            case MERGED, UNKNOWN_PROJECT, PROJECT_UNAVAILABLE -> throw new IllegalStateException(command.getClass().getSimpleName()
                    + " of task " + taskId + " is never refused as " + refusal);
        };
    }

    private static Text phase(Phase phase) {
        return switch (phase) {
            case PLANNING -> Text.of("phase.planning");
            case AWAITING_APPROVAL -> Text.of("phase.awaitingApproval");
            case EXECUTING -> Text.of("phase.executing");
            case COMPLETED -> Text.of("phase.completed");
            case FAILED -> Text.of("phase.failed");
            case REJECTED -> Text.of("phase.rejected");
            case CANCELLED -> Text.of("phase.cancelled");
        };
    }

    /** The task the command acts on; null for giving one, which has none until it is given. */
    private static Long taskId(TaskCommand command) {
        return switch (command) {
            case TaskCommand.Cancel cancel -> cancel.taskId();
            case TaskCommand.Retry retry -> retry.taskId();
            case TaskCommand.Approve approve -> approve.taskId();
            case TaskCommand.Reject reject -> reject.taskId();
            case TaskCommand.Reprioritize reprioritize -> reprioritize.taskId();
            case TaskCommand.Correct correct -> correct.taskId();
            case TaskCommand.FollowUp followUp -> followUp.taskId();
            case TaskCommand.Answer answer -> answer.taskId();
            case TaskCommand.Give _ -> null;
        };
    }

    private static String name(CommandResult result) {
        return switch (result) {
            case CommandResult.Done _ -> "DONE";
            case CommandResult.Created created -> "CREATED #" + created.taskId();
            case CommandResult.Unchanged _ -> "UNCHANGED";
            case CommandResult.Refused refused -> "REFUSED " + refused.reason();
        };
    }

    private static Task task(Tx tx, long taskId) {
        return Tasks.find(tx, taskId).orElseThrow(() -> new IllegalStateException("task #" + taskId + " was allowed, yet is gone"));
    }

    /**
     * The verdict allowed this in the same transaction, over the one connection and its lock, so the phase cannot have
     * moved since: if the update finds it moved, the verdict and the store disagree, and nothing of it may be kept.
     */
    private static void changePhase(Tx tx, Task task, Phase from, Phase to, Instant now) {
        if (!Tasks.changePhase(tx, task.id(), from, to, now)) {
            throw new IllegalStateException("task #" + task.id() + " was allowed " + from + " -> " + to + ", yet its phase moved");
        }
    }

    private static boolean isRequester(Requester who, Task task) {
        return task.requester().ref().equals(who.ref());
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }

    private static void logTransition(Tx tx, long taskId, Phase from, Phase to, String actor) {
        tx.afterCommit(() -> Log.info("task.transition", "task", taskId, "from", from, "to", to, "actor", actor));
    }
}
