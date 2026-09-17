package dispatch.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.agent.AgentResult;
import dispatch.domain.FailureReason;
import dispatch.domain.OutboxKind;
import dispatch.domain.Phase;
import dispatch.domain.Plan;
import dispatch.domain.Run;
import dispatch.domain.RunStatus;
import dispatch.domain.Task;
import dispatch.store.Database;
import dispatch.store.Events;
import dispatch.store.Outbox;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * State changes caused by runs rather than members. Each call is its own transaction; a result that arrives after the
 * task moved on (e.g. was cancelled) still records the run but leaves the task alone.
 */
public final class RunTransitions {

    private static final String ACTOR = "dispatch";
    private static final int MAX_DETAIL_LENGTH = 2000;

    private final Database db;
    private final Clock clock;
    private final Runnable wakeOutbox;

    public RunTransitions(Database db, Clock clock, Runnable wakeOutbox) {
        this.db = db;
        this.clock = clock;
        this.wakeOutbox = wakeOutbox;
    }

    public void recordWorktree(long taskId, Path worktree, String baseSha) {
        db.transaction(tx -> Tasks.recordWorktree(tx, taskId, worktree, baseSha, clock.instant()));
    }

    public void recordProcess(long taskId, int seq, ProcessHandle process) {
        Instant start = process.info().startInstant().orElse(null);
        db.transaction(tx -> Runs.recordProcess(tx, taskId, seq, process.pid(), start));
    }

    public void planSucceeded(long taskId, int seq, Plan plan, AgentResult result) {
        db.transaction(tx -> {
            Instant now = clock.instant();
            Task task = task(tx, taskId);
            Run run = run(tx, taskId, seq);
            if (!finishRun(tx, run, RunStatus.SUCCEEDED, null, null, result, plan.toJson(), now)) {
                return;
            }
            if (!Tasks.planned(tx, taskId, plan.toJson(), now)) {
                ignoredResult(tx, task, seq);
                return;
            }
            Events.record(tx, taskId, seq, ACTOR, Phase.PLANNING, Phase.AWAITING_APPROVAL, "plan ready", now);
            ObjectNode payload = Json.object().put("taskId", taskId).put("planSeq", seq).put("project", task.project());
            payload.set("plan", Json.read(plan.toJson()));
            putCostAndDuration(payload, run, result, now);
            enqueueForRequester(tx, task, OutboxKind.PLAN_READY, payload, now);
            logTransition(tx, taskId, seq, Phase.PLANNING, Phase.AWAITING_APPROVAL);
        });
    }

    /**
     * An execution run whose changes were delivered.
     *
     * @param files paths the delivery commit changed; empty when the run changed nothing
     * @param prUrl the task's pull request, null when nothing has been delivered
     */
    public void completed(long taskId, int seq, AgentResult result, List<String> files, String prUrl) {
        db.transaction(tx -> {
            Instant now = clock.instant();
            Task task = task(tx, taskId);
            Run run = run(tx, taskId, seq);
            if (!finishRun(tx, run, RunStatus.SUCCEEDED, null, null, result, result.summary(), now)) {
                return;
            }
            if (!Tasks.completed(tx, taskId, prUrl, now)) {
                ignoredResult(tx, task, seq);
                return;
            }
            Events.record(tx, taskId, seq, ACTOR, Phase.EXECUTING, Phase.COMPLETED,
                    files.isEmpty() ? "no changes" : "delivered " + files.size() + " changed files", now);
            ObjectNode payload = Json.object().put("taskId", taskId).put("project", task.project()).put("prUrl", prUrl)
                    .put("filesChanged", files.size()).put("summary", result.summary());
            result.denials().forEach(payload.putArray("denials")::add);
            putCostAndDuration(payload, run, result, now);
            enqueueForRequester(tx, task, OutboxKind.TASK_COMPLETED, payload, now);
            enqueue(tx, task, OutboxKind.TASK_COMPLETED_SHORT, Json.object().put("taskId", taskId).put("project", task.project())
                    .put("prUrl", prUrl).put("filesChanged", files.size()), now);
            logTransition(tx, taskId, seq, Phase.EXECUTING, Phase.COMPLETED);
        });
    }

    /** @param result null when no agent result exists (setup failure, interrupted before the agent reported) */
    public void failed(long taskId, int seq, FailureReason reason, String detail, AgentResult result) {
        String shortDetail = truncate(detail);
        db.transaction(tx -> {
            Instant now = clock.instant();
            Task task = task(tx, taskId);
            Run run = run(tx, taskId, seq);
            if (!finishRun(tx, run, RunStatus.FAILED, reason, shortDetail, result, null, now)) {
                return;
            }
            if (!Tasks.failed(tx, taskId, reason, shortDetail, now)) {
                ignoredResult(tx, task, seq);
                return;
            }
            Events.record(tx, taskId, seq, ACTOR, task.phase(), Phase.FAILED, reason + ": " + shortDetail, now);
            enqueueForRequester(tx, task, OutboxKind.TASK_FAILED,
                    Json.object().put("taskId", taskId).put("reason", reason.name()).put("detail", shortDetail), now);
            enqueue(tx, task, OutboxKind.TASK_FAILED_SHORT, Json.object().put("taskId", taskId).put("reason", reason.name()), now);
            logTransition(tx, taskId, seq, task.phase(), Phase.FAILED);
        });
    }

    /** The member already cancelled the task; only the run's end is recorded. */
    public void cancelled(long taskId, int seq, AgentResult result) {
        db.transaction(tx -> finishRun(tx, run(tx, taskId, seq), RunStatus.CANCELLED, null, null, result, null, clock.instant()));
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
                result == null ? null : Json.write(result.denials()));
        if (!Runs.finish(tx, run.taskId(), run.seq(), finish, now)) {
            tx.afterCommit(() -> Log.warn("run.already_finished", "task", run.taskId(), "run", run.seq(),
                    "status", run.status(), "ignored", status));
            return false;
        }
        tx.afterCommit(() -> Log.info("run.finished", "task", run.taskId(), "run", run.seq(), "status", status,
                "reason", reason, "cost_usd", finish.costUsd(), "turns", finish.turns()));
        return true;
    }

    private static void putCostAndDuration(ObjectNode payload, Run run, AgentResult result, Instant now) {
        payload.put("costUsd", result.costUsd() == null ? null : result.costUsd().toPlainString());
        payload.put("durationSeconds", run.startedAt() == null ? 0 : Duration.between(run.startedAt(), now).toSeconds());
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
