package dispatch.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.agent.AgentResult;
import dispatch.agent.UsageLimit;
import dispatch.domain.AgentKind;
import dispatch.domain.Requester;
import dispatch.domain.RunCause;
import dispatch.store.AgentLimits;
import dispatch.domain.FailureReason;
import dispatch.domain.GroupReaction;
import dispatch.domain.OutboxKind;
import dispatch.domain.Phase;
import dispatch.domain.Plan;
import dispatch.domain.Run;
import dispatch.domain.RunCause;
import dispatch.domain.RunStatus;
import dispatch.domain.RunStep;
import dispatch.domain.Task;
import dispatch.store.Database;
import dispatch.store.Events;
import dispatch.store.Outbox;
import dispatch.store.RunSteps;
import dispatch.store.Runs;
import dispatch.store.TaskCi;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * State changes caused by runs rather than members. Each call is its own transaction; a result that arrives after the
 * task moved on (e.g. was cancelled) still records the run but leaves the task alone.
 */
public final class RunTransitions {

    private static final String ACTOR = "dispatch";
    private static final int MAX_DETAIL_LENGTH = 2000;
    /** The third limit hit in a row fails the task instead of queueing it again (spec: usage limit). */
    private static final int MAX_LIMIT_HITS_IN_A_ROW = 3;

    private final Database db;
    private final Clock clock;
    private final Runnable wakeOutbox;
    private final boolean offerMerge;
    /** By project name: whether its delivered pull requests are watched (spec: CI watch); null on a bot that watches none. */
    private final Predicate<String> watchesCi;
    private final Runnable wakeCi;

    public RunTransitions(Database db, Clock clock, Runnable wakeOutbox) {
        this(db, clock, wakeOutbox, false);
    }

    /** @param offerMerge a delivered task's result offers its requester the Merge button: a personal bot, which can merge */
    public RunTransitions(Database db, Clock clock, Runnable wakeOutbox, boolean offerMerge) {
        this(db, clock, wakeOutbox, offerMerge, null, () -> { });
    }

    /**
     * @param offerMerge as above
     * @param watchesCi  by project name: whether its delivered pull requests are watched; null to watch none
     * @param wakeCi     wakes the watcher once a delivery commits
     */
    public RunTransitions(Database db, Clock clock, Runnable wakeOutbox, boolean offerMerge, Predicate<String> watchesCi,
                          Runnable wakeCi) {
        this.db = db;
        this.clock = clock;
        this.wakeOutbox = wakeOutbox;
        this.offerMerge = offerMerge;
        this.watchesCi = watchesCi;
        this.wakeCi = wakeCi;
    }

    public void recordBuildSession(long taskId, UUID buildSessionId) {
        db.transaction(tx -> Tasks.recordBuildSession(tx, taskId, buildSessionId, clock.instant()));
    }

    public void recordWorktree(long taskId, String worktree, String baseSha) {
        db.transaction(tx -> Tasks.recordWorktree(tx, taskId, worktree, baseSha, clock.instant()));
    }

    /** @param pid null when the agent runs on a member's own computer */
    public void agentStarted(long taskId, int seq, Long pid, Instant processStart) {
        db.transaction(tx -> Runs.recordAgentStarted(tx, taskId, seq, clock.instant(), pid, processStart));
    }

    /** RM-1: a step of the run began; stamped with this machine's clock. */
    public void stepStarted(long taskId, int seq, int n, RunStep.Kind kind, int round) {
        db.transaction(tx -> RunSteps.started(tx, taskId, seq, n, kind, round, clock.instant()));
    }

    public void stepEnded(long taskId, int seq, int n, RunStep.Outcome outcome, String detail) {
        db.transaction(tx -> RunSteps.ended(tx, taskId, seq, n, outcome, detail, clock.instant()));
    }

    /** RM-2: a team worker's steps, whole, with the worker's own times. */
    public void stepsReported(long taskId, int seq, List<RunStep> steps) {
        db.transaction(tx -> RunSteps.replace(tx, taskId, seq, steps));
    }

    public void planSucceeded(long taskId, int seq, Plan plan, AgentResult result) {
        db.transaction(tx -> {
            Instant now = clock.instant();
            Task task = task(tx, taskId);
            Run run = run(tx, taskId, seq);
            if (!finishRun(tx, run, RunStatus.SUCCEEDED, null, null, result, plan.toJson(), now)) {
                return;
            }
            if (plan.result() == Plan.Result.ANSWER) {
                answered(tx, task, run, plan, result, now);
                return;
            }
            if (!Tasks.planned(tx, taskId, plan.toJson(), now)) {
                ignoredResult(tx, task, seq);
                return;
            }
            ObjectNode payload = Json.object().put("taskId", taskId).put("planSeq", seq).put("project", task.project());
            payload.set("plan", Json.read(plan.toJson()));
            putRunDetails(payload, run, result, now);
            end(tx, task, run, new Ending(Phase.AWAITING_APPROVAL, "plan ready", OutboxKind.PLAN_READY, payload, null, null), now);
            if (!plan.questionItems().isEmpty()) {
                // One at a time: the next is sent once this one is answered (G-1d).
                TaskCommands.enqueueQuestion(tx, task, seq, plan.questionItems(), 1, now);
            }
        });
    }

    /** The plan run answered the task (spec: answers): it completes now, and only its requester hears the answer. */
    private void answered(Tx tx, Task task, Run run, Plan plan, AgentResult result, Instant now) {
        long taskId = task.id();
        if (!Tasks.answered(tx, taskId, plan.toJson(), now)) {
            ignoredResult(tx, task, run.seq());
            return;
        }
        ObjectNode payload = Json.object().put("taskId", taskId).put("project", task.project()).put("answer", plan.answer());
        putRunDetails(payload, run, result, now);
        end(tx, task, run, new Ending(Phase.COMPLETED, "answered", OutboxKind.ANSWER_READY, payload,
                completedLine(task, null, 0, true), GroupReaction.COMPLETED), now);
    }

    /** An execution run whose changes were delivered, with the agent's own summary. */
    public void completed(long taskId, int seq, AgentResult result, List<String> files, String prUrl) {
        completed(taskId, seq, result, result.summary(), files, prUrl);
    }

    /**
     * An execution or delivery run whose changes were delivered.
     *
     * @param result  null for a delivery run, which has no agent
     * @param summary what the agent said it did
     * @param files   paths the delivery commit changed; empty when the run changed nothing
     * @param prUrl   the task's pull request, null when nothing has been delivered
     */
    public void completed(long taskId, int seq, AgentResult result, String summary, List<String> files, String prUrl) {
        completed(taskId, seq, result, summary, files, prUrl, null);
    }

    /** @param verification what the verify loop found; null for a run without the loop */
    public void completed(long taskId, int seq, AgentResult result, String summary, List<String> files, String prUrl,
            Verification verification) {
        completed(taskId, seq, result, summary, files, prUrl, verification, null);
    }

    /**
     * @param head the commit the delivery left the task's branch at, which the next run must find it at; null when the worker
     *             did not say (one from before the branch guard), which checks nothing from now on
     */
    public void completed(long taskId, int seq, AgentResult result, String summary, List<String> files, String prUrl,
            Verification verification, String head) {
        db.transaction(tx -> {
            Instant now = clock.instant();
            Task task = task(tx, taskId);
            Run run = run(tx, taskId, seq);
            if (!finishRun(tx, run, RunStatus.SUCCEEDED, null, null, result, summary, now)) {
                return;
            }
            // Where the branch is, whatever became of the task meanwhile.
            Tasks.recordHead(tx, taskId, head, now);
            if (!Tasks.completed(tx, taskId, prUrl, now)) {
                ignoredResult(tx, task, seq);
                return;
            }
            ObjectNode payload = Json.object().put("taskId", taskId).put("project", task.project()).put("prUrl", prUrl)
                    .put("filesChanged", files.size()).put("summary", summary);
            Task delivered = task(tx, taskId);
            if (offerMerge && TaskAccess.mergeRefusal(delivered).isEmpty()) {
                payload.put("merge", true);
            }
            ArrayNode denials = payload.putArray("denials");
            if (result != null) {
                result.denials().forEach(denials::add);
            }
            putRunDetails(payload, run, result, now);
            if (verification != null) {
                payload.set("verification", Json.MAPPER.valueToTree(verification));
            }
            if (armCi(tx, delivered, run, head, !files.isEmpty(), now)) {
                payload.set("ci", Json.object().put("state", TaskCi.State.PENDING.name()));
            }
            if (watchesCi != null) {
                // Also when nothing was armed: a fix run that changed nothing is the watcher's to settle.
                tx.afterCommit(wakeCi);
            }
            String event = files.isEmpty() ? "no changes" : "delivered " + files.size() + " changed files";
            end(tx, task, run, new Ending(Phase.COMPLETED, event, OutboxKind.TASK_COMPLETED, payload,
                    completedLine(task, prUrl, files.size(), false), GroupReaction.COMPLETED), now);
        });
    }

    /**
     * A commit pushed to a watched project's pull request is watched from now (spec: CI watch). A run a member caused
     * starts the fix count afresh; a fix run's own delivery keeps it.
     */
    private boolean armCi(Tx tx, Task task, Run run, String head, boolean pushed, Instant now) {
        if (watchesCi == null || !pushed || head == null || task.prUrl() == null || !watchesCi.test(task.project())) {
            return false;
        }
        TaskCi.arm(tx, task.id(), head, run.cause() == RunCause.CI_FIX, now);
        return true;
    }

    /** @param result null when no agent result exists (setup failure, interrupted before the agent reported) */
    public void failed(long taskId, int seq, FailureReason reason, String detail, AgentResult result) {
        failed(taskId, seq, reason, detail, result, false, null);
    }

    /**
     * A failed delivery, which may have left the task's branch at Dispatch's own commit.
     *
     * @param head where the task's branch is now, as for {@link #completed}
     */
    public void deliveryFailed(long taskId, int seq, String detail, AgentResult result, String head) {
        failed(taskId, seq, FailureReason.DELIVERY, detail, result, true, head);
    }

    private void failed(long taskId, int seq, FailureReason reason, String detail, AgentResult result, boolean recordHead,
                        String head) {
        String shortDetail = truncate(detail);
        db.transaction(tx -> {
            Instant now = clock.instant();
            Task task = task(tx, taskId);
            Run run = run(tx, taskId, seq);
            // The summary is kept: after a delivery failure, a retry delivers the same work with it (ADR 0008).
            String output = result == null ? null : result.summary();
            if (!finishRun(tx, run, RunStatus.FAILED, reason, shortDetail, result, output, now)) {
                return;
            }
            if (recordHead) {
                Tasks.recordHead(tx, taskId, head, now);
            }
            failTask(tx, task, run, reason, shortDetail, now);
        });
    }

    /** The task's end on its run's failure, once the run is finished. */
    private void failTask(Tx tx, Task task, Run run, FailureReason reason, String shortDetail, Instant now) {
        if (!Tasks.failed(tx, task.id(), reason, shortDetail, now)) {
            ignoredResult(tx, task, run.seq());
            return;
        }
        // A failure is the requester's news alone: the group hears of it by reaction, never by a line (ZB, 2026-10-06), and
        // of a usage limit not at all, since the task waits for /retry after the reset rather than ending.
        GroupReaction reaction = reason == FailureReason.USAGE_LIMIT ? null : GroupReaction.ENDED;
        end(tx, task, run, new Ending(Phase.FAILED, reason + ": " + shortDetail, OutboxKind.TASK_FAILED,
                Json.object().put("taskId", task.id()).put("reason", reason.name()).put("detail", shortDetail), null, reaction), now);
    }

    /**
     * Claude's usage limit cut the run short (spec: usage limit, ADR 0040). The run ends FAILED with reason USAGE_LIMIT and
     * the machine that ran it is held until the reset; the task keeps its phase and the same run is queued again, to start
     * by itself after the reset, as /retry would start it. The task fails as any other instead when Dispatch cannot say
     * when the limit resets, when the reset has already passed, or on the third such hit in a row (a runaway guard).
     *
     * @param result the agent's result, whose {@link AgentResult#limit()} names the reset
     */
    public void limited(long taskId, int seq, String detail, AgentResult result) {
        String shortDetail = truncate(detail);
        db.transaction(tx -> {
            Instant now = clock.instant();
            Task task = task(tx, taskId);
            Run run = run(tx, taskId, seq);
            if (!finishRun(tx, run, RunStatus.FAILED, FailureReason.USAGE_LIMIT, shortDetail, result, result.summary(), now)) {
                return;
            }
            UsageLimit limit = result.limit();
            Instant resetsAt = limit == null ? null : Instant.ofEpochSecond(limit.resetsAt());
            if (resetsAt == null || !resetsAt.isAfter(now)) {
                failTask(tx, task, run, FailureReason.USAGE_LIMIT, shortDetail, now);
                return;
            }
            hold(tx, task, limit);
            if (task.phase() != Phase.PLANNING && task.phase() != Phase.EXECUTING) {
                ignoredResult(tx, task, seq);
                return;
            }
            if (Runs.limitedInARow(tx, taskId) >= MAX_LIMIT_HITS_IN_A_ROW) {
                failTask(tx, task, run, FailureReason.USAGE_LIMIT, shortDetail, now);
                return;
            }
            int next = Runs.nextSeq(tx, taskId);
            Runs.insert(tx, new Runs.NewRun(taskId, next, run.kind(), RunCause.RETRY, run.instruction(),
                    new Requester(run.requestedBy(), run.requestedByName())), now);
            Events.record(tx, taskId, next, ACTOR, task.phase(), task.phase(), "usage limit: run " + next + " queued", now);
            enqueueForRequester(tx, task, OutboxKind.LIMIT_REQUEUED, Json.object().put("taskId", taskId)
                    .put("type", limit.type()).put("resetsAt", resetsAt.toString()), now);
            tx.afterCommit(() -> Log.info("run.limit_requeued", "task", taskId, "run", next, "resets_at", resetsAt,
                    "type", limit.type()));
        });
    }

    /** A verify-loop call of a run that still delivered met the limit: only the machine's hold is written. */
    public void hold(long taskId, UsageLimit limit) {
        db.transaction(tx -> hold(tx, task(tx, taskId), limit));
    }

    /** Holds the machine that ran the task's agent: the bot's own in personal mode, else the task's worker. */
    private void hold(Tx tx, Task task, UsageLimit limit) {
        long machine = Tasks.workerOf(tx, task.id()).orElse(AgentLimits.THIS_MACHINE);
        AgentLimits.hold(tx, machine, AgentKind.CLAUDE_CODE.id(), limit);
        tx.afterCommit(() -> Log.info("agent.held", "machine", machine, "agent", AgentKind.CLAUDE_CODE.id(),
                "resets_at", Instant.ofEpochSecond(limit.resetsAt()), "type", limit.type()));
    }

    /** The member already cancelled the task; only the run's end is recorded. */
    public void cancelled(long taskId, int seq, AgentResult result) {
        db.transaction(tx -> finishRun(tx, run(tx, taskId, seq), RunStatus.CANCELLED, null, null, result, null, clock.instant()));
    }

    /**
     * What differs between the endings of a task once its run is finished and its phase moved: where it went, the event's
     * words, the requester's news, the group's one line (null when the group gets none: only a completion has one) and its
     * reaction (null when the group's message keeps the one it has). Everything else about ending a task is the same and
     * lives in {@link #end}.
     */
    private record Ending(Phase to, String event, OutboxKind news, ObjectNode payload, ObjectNode groupLine,
            GroupReaction reaction) {
    }

    /**
     * The one tail of every ending. {@code task} was read before its phase moved, and each move is guarded on that phase,
     * so it is the phase the event comes from.
     */
    private void end(Tx tx, Task task, Run run, Ending ending, Instant now) {
        Events.record(tx, task.id(), run.seq(), ACTOR, task.phase(), ending.to(), ending.event(), now);
        enqueueForRequester(tx, task, ending.news(), ending.payload(), now);
        if (ending.groupLine() != null) {
            enqueue(tx, task, OutboxKind.TASK_COMPLETED_SHORT, ending.groupLine(), now);
        }
        if (ending.reaction() != null) {
            GroupAcks.react(tx, task, ending.reaction(), now);
        }
        logTransition(tx, task.id(), run.seq(), task.phase(), ending.to());
    }

    /** The group's line for a completed task, with the same keys whether it was answered or delivered. */
    private static ObjectNode completedLine(Task task, String prUrl, int filesChanged, boolean answered) {
        return Json.object().put("taskId", task.id()).put("project", task.project()).put("prUrl", prUrl)
                .put("filesChanged", filesChanged).put("answered", answered);
    }

    private boolean finishRun(Tx tx, Run run, RunStatus status, FailureReason reason, String detail, AgentResult result,
                              String output, Instant now) {
        Runs.Finish finish = new Runs.Finish(
                status,
                result == null ? null : result.exitCode(),
                reason,
                detail,
                result == null ? null : result.costUsd(),
                result == null ? null : result.turns(),
                output,
                result == null ? null : Json.write(result.denials()),
                result == null ? null : result.model(),
                result == null || result.sandbox() == null ? null : result.sandbox().name());
        if (!Runs.finish(tx, run.taskId(), run.seq(), finish, now)) {
            tx.afterCommit(() -> Log.warn("run.already_finished", "task", run.taskId(), "run", run.seq(),
                    "status", run.status(), "ignored", status));
            return false;
        }
        tx.afterCommit(() -> Log.info("run.finished", "task", run.taskId(), "run", run.seq(), "status", status,
                "reason", reason, "cost_usd", finish.costUsd(), "turns", finish.turns(), "model", finish.model()));
        if (result != null && result.requestedModel() != null) {
            tx.afterCommit(() -> Log.warn("agent.model_differs", "task", run.taskId(), "run", run.seq(),
                    "requested", result.requestedModel(), "answered", result.model()));
        }
        if (result != null && result.sandbox() != null && result.sandbox().unsandboxedReason() != null) {
            tx.afterCommit(() -> Log.warn("agent.unsandboxed", "task", run.taskId(), "run", run.seq(),
                    "reason", result.sandbox().unsandboxedReason()));
        }
        return true;
    }

    /** @param result null for a run without an agent */
    private static void putRunDetails(ObjectNode payload, Run run, AgentResult result, Instant now) {
        payload.put("durationSeconds", run.startedAt() == null ? 0 : Duration.between(run.startedAt(), now).toSeconds());
        if (result == null) {
            payload.putNull("costUsd").putNull("model");
            return;
        }
        payload.put("costUsd", result.costUsd() == null ? null : result.costUsd().toPlainString());
        payload.put("model", result.model());
        if (result.requestedModel() != null) {
            payload.put("requestedModel", result.requestedModel());
        }
        if (result.sandbox() != null && result.sandbox().unsandboxedReason() != null) {
            payload.put("unsandboxed", result.sandbox().unsandboxedReason());
        }
    }

    /** Details for the requester's private chat, falling back to the group (ADR 0011). */
    private void enqueueForRequester(Tx tx, Task task, OutboxKind kind, ObjectNode payload, Instant now) {
        Outbox.enqueueForRequester(tx, task, kind, payload, now);
        tx.afterCommit(wakeOutbox);
    }

    /**
     * A one-line outcome for the task's group, under the task's own message if it was given there. A task without a group
     * chat gets none: it would only repeat the details its requester already has (ADR 0014).
     */
    private void enqueue(Tx tx, Task task, OutboxKind kind, ObjectNode payload, Instant now) {
        if (!task.hasGroupChat()) {
            return;
        }
        Outbox.enqueue(tx, task.id(), kind, task.chatRef(), task.groupOriginRef(), payload, now);
        tx.afterCommit(wakeOutbox);
    }

    private static Task task(Tx tx, long taskId) {
        return Tasks.find(tx, taskId).orElseThrow(() -> new IllegalStateException("task " + taskId + " does not exist"));
    }

    private static Run run(Tx tx, long taskId, int seq) {
        return Runs.find(tx, taskId, seq)
                .orElseThrow(() -> new IllegalStateException("run " + taskId + "." + seq + " does not exist"));
    }

    private static void ignoredResult(Tx tx, Task task, int seq) {
        tx.afterCommit(() -> Log.info("run.result_ignored", "task", task.id(), "run", seq, "phase", task.phase()));
    }

    private static void logTransition(Tx tx, long taskId, int seq, Phase from, Phase to) {
        tx.afterCommit(() -> Log.info("task.transition", "task", taskId, "run", seq, "from", from, "to", to, "actor", ACTOR));
    }

    private static String truncate(String detail) {
        if (detail == null) {
            return "";
        }
        return detail.length() <= MAX_DETAIL_LENGTH ? detail : detail.substring(0, MAX_DETAIL_LENGTH - 1) + "…";
    }
}
