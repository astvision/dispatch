package dispatch.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.Text;
import dispatch.domain.FailureReason;
import dispatch.domain.GroupReaction;
import dispatch.domain.OutboxKind;
import dispatch.domain.Phase;
import dispatch.domain.Requester;
import dispatch.domain.Run;
import dispatch.domain.RunCause;
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

/**
 * The task commands (ADR 0031). Each asks {@link TaskAccess} what the member may do (ADR 0027), changes the task, writes
 * the task's news in the caller's transaction, and says what happened. None writes a reply: the channel that ran it
 * answers whoever acted. A refused command writes nothing at all.
 */
public final class TaskCommands {

    private final ActiveRuns activeRuns;
    private final TaskAccess access;
    private final Clock clock;
    private final Runnable wakeScheduler;
    private final Runnable wakeOutbox;

    TaskCommands(Groups groups, ActiveRuns activeRuns, Clock clock, Runnable wakeScheduler, Runnable wakeOutbox) {
        this.activeRuns = activeRuns;
        this.access = new TaskAccess(groups);
        this.clock = clock;
        this.wakeScheduler = wakeScheduler;
        this.wakeOutbox = wakeOutbox;
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
        };
    }

    private CommandResult carryOut(Tx tx, Requester who, TaskCommand command) {
        return switch (command) {
            case TaskCommand.Cancel cancel -> cancel(tx, who, task(tx, cancel.taskId()));
            case TaskCommand.Retry retry -> retry(tx, who, task(tx, retry.taskId()));
        };
    }

    private Optional<CommandResult.Refused> refusal(Tx tx, Requester who, TaskCommand command, long taskId, TaskAccess.Action action) {
        TaskAccess.Verdict verdict = access.of(tx, who.ref(), taskId);
        return verdict.refusal(action).map(refusal -> refused(command, refusal, verdict.task()));
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
        Run step = Runs.latest(tx, task.id()).orElseThrow();
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
        changePhase(tx, task, Phase.FAILED, to, now);
        int seq = Runs.nextSeq(tx, task.id());
        Runs.insert(tx, new Runs.NewRun(task.id(), seq, kind, RunCause.RETRY, instruction, who), now);
        Events.record(tx, task.id(), seq, who.ref(), Phase.FAILED, to, "retry of run " + step.seq(), now);
        Outbox.enqueueForRequester(tx, task, OutboxKind.RETRY_QUEUED,
                Json.object().put("taskId", task.id()).put("by", who.name()).put("kind", kind.name()), now);
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(wakeScheduler);
        logTransition(tx, task.id(), Phase.FAILED, to, who.ref());
        return new CommandResult.Done(task.id(), isRequester(who, task));
    }

    /** A refusal and its one wording (ADR 0031), naming the task as far as its headline allows (ADR 0020). */
    static CommandResult.Refused refused(TaskCommand command, Refusal refusal, Task task) {
        return new CommandResult.Refused(refusal, words(command, refusal, task));
    }

    private static Text words(TaskCommand command, Refusal refusal, Task task) {
        long taskId = taskId(command);
        return switch (refusal) {
            case NOT_MEMBER -> Text.of("refused.notMember");
            case NOT_FOUND -> Text.of("refused.notFound", taskId);
            case NOT_REQUESTER -> command instanceof TaskCommand.Cancel
                    ? Text.of("refused.cancelNotRequester", taskId, task.requester().name())
                    : Text.of("refused.notRequester", taskId, task.requester().name());
            case WRONG_PHASE -> Text.of("refused.wrongPhase", taskId, phase(task.phase()));
            case NOT_FAILED -> Text.of("refused.notFailed", taskId, phase(task.phase()));
            // Refusals no command of this module gives yet; each gets its words with the command that first gives it.
            case STALE_PLAN, OPEN_QUESTIONS, OUT_OF_ORDER, ALREADY_ANSWERED, NOT_EXECUTED, MERGED, EMPTY, UNKNOWN_PROJECT,
                 PROJECT_UNAVAILABLE -> throw new IllegalStateException(command + " is never refused as " + refusal);
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

    private static long taskId(TaskCommand command) {
        return switch (command) {
            case TaskCommand.Cancel cancel -> cancel.taskId();
            case TaskCommand.Retry retry -> retry.taskId();
        };
    }

    private static String name(CommandResult result) {
        return switch (result) {
            case CommandResult.Done done -> "DONE";
            case CommandResult.Created created -> "CREATED #" + created.taskId();
            case CommandResult.Unchanged unchanged -> "UNCHANGED";
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

    private static void logTransition(Tx tx, long taskId, Phase from, Phase to, String actor) {
        tx.afterCommit(() -> Log.info("task.transition", "task", taskId, "from", from, "to", to, "actor", actor));
    }
}
