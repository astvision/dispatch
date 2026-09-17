package dispatch.store;

import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.Requester;
import dispatch.domain.Run;
import dispatch.domain.RunKind;
import dispatch.domain.RunStatus;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** SQL for the run table, including the scheduler's claim rules. */
public final class Runs {

    private static final String COLUMNS = """
            task_id, seq, kind, status, instruction, requested_by, requested_by_name, pid, pid_start, queued_at, started_at,
            finished_at, cost_usd, turns, failure_reason""";

    private Runs() {
    }

    public record NewRun(long taskId, int seq, RunKind kind, String instruction, Requester requestedBy) {
    }

    /** A queued or running run, with the task details /status shows. */
    public record InProgress(long taskId, int seq, RunKind kind, RunStatus status, Instant queuedAt, Instant startedAt,
                             String project, String title) {
    }

    /** Result columns written once when a run ends; any may be null. */
    public record Finish(
            RunStatus status,
            Integer exitCode,
            FailureReason failureReason,
            String errorDetail,
            BigDecimal costUsd,
            Integer turns,
            String output,
            String denials) {
    }

    public static void insert(Tx tx, NewRun run, Instant now) {
        tx.update("""
                        INSERT INTO run (task_id, seq, kind, status, instruction, requested_by, requested_by_name, queued_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                run.taskId(), run.seq(), run.kind(), RunStatus.QUEUED, run.instruction(), run.requestedBy().ref(),
                run.requestedBy().name(), now);
    }

    /**
     * Claims the oldest queued run that may start now: fewer than {@code maxConcurrentRuns} runs are active, and an
     * execution-type run also needs its project to have no other execution running (builds and tests of the same
     * project can clash on ports and test databases). Planning runs only need a free slot.
     */
    public static Optional<ClaimedRun> claimNext(Tx tx, int maxConcurrentRuns, Instant now) {
        int running = tx.one("SELECT count(*) AS n FROM run WHERE status = ?", row -> row.intValue("n"), RunStatus.RUNNING)
                .orElse(0);
        if (running >= maxConcurrentRuns) {
            return Optional.empty();
        }
        Optional<ClaimedRun> next = tx.one("""
                        SELECT r.task_id, r.seq, r.kind
                        FROM run r JOIN task t ON t.id = r.task_id
                        WHERE r.status = ?
                          AND (r.kind = ? OR NOT EXISTS (
                                SELECT 1 FROM run busy JOIN task busy_task ON busy_task.id = busy.task_id
                                WHERE busy.status = ? AND busy.kind IN (?, ?) AND busy_task.project = t.project))
                        ORDER BY r.queued_at, r.task_id, r.seq
                        LIMIT 1""",
                row -> new ClaimedRun(row.longValue("task_id"), row.intValue("seq"), row.enumValue("kind", RunKind.class)),
                RunStatus.QUEUED, RunKind.PLAN, RunStatus.RUNNING, RunKind.EXECUTE, RunKind.DELIVER);
        next.ifPresent(run -> {
            tx.update("UPDATE run SET status = ?, started_at = ? WHERE task_id = ? AND seq = ? AND status = ?",
                    RunStatus.RUNNING, now, run.taskId(), run.seq(), RunStatus.QUEUED);
            tx.update("UPDATE task SET started_at = COALESCE(started_at, ?), updated_at = ? WHERE id = ?",
                    now, now, run.taskId());
        });
        return next;
    }

    public static Optional<Run> find(Tx tx, long taskId, int seq) {
        return tx.one("SELECT " + COLUMNS + " FROM run WHERE task_id = ? AND seq = ?", Runs::map, taskId, seq);
    }

    /** Running and queued runs of {@code projects}, oldest queued first. */
    public static List<InProgress> inProgress(Tx tx, Set<String> projects) {
        if (projects.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>(List.of(RunStatus.RUNNING, RunStatus.QUEUED));
        params.addAll(projects);
        return tx.list("""
                        SELECT r.task_id, r.seq, r.kind, r.status, r.queued_at, r.started_at, t.project, t.title
                        FROM run r JOIN task t ON t.id = r.task_id
                        WHERE r.status IN (?, ?) AND t.project IN (""" + Tx.placeholders(projects.size()) + """
                        )
                        ORDER BY r.queued_at, r.task_id, r.seq""",
                row -> new InProgress(row.longValue("task_id"), row.intValue("seq"), row.enumValue("kind", RunKind.class),
                        row.enumValue("status", RunStatus.class), row.instant("queued_at"), row.instant("started_at"),
                        row.string("project"), row.string("title")),
                params.toArray());
    }

    public static List<Run> forTask(Tx tx, long taskId) {
        return tx.list("SELECT " + COLUMNS + " FROM run WHERE task_id = ? ORDER BY seq", Runs::map, taskId);
    }

    /** Total reported cost per task; a task none of whose runs reported a cost is absent. */
    public static Map<Long, BigDecimal> costs(Tx tx, List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        record Cost(long taskId, BigDecimal usd) {
        }
        String placeholders = Tx.placeholders(taskIds.size());
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (Cost cost : tx.list("SELECT task_id, cost_usd FROM run WHERE cost_usd IS NOT NULL AND task_id IN (" + placeholders + ")",
                row -> new Cost(row.longValue("task_id"), row.decimal("cost_usd")), taskIds.toArray())) {
            totals.merge(cost.taskId(), cost.usd(), BigDecimal::add);
        }
        return totals;
    }

    public static List<Run> withStatus(Tx tx, RunStatus status) {
        return tx.list("SELECT " + COLUMNS + " FROM run WHERE status = ? ORDER BY task_id, seq", Runs::map, status);
    }

    public static int nextSeq(Tx tx, long taskId) {
        return tx.one("SELECT coalesce(max(seq), 0) + 1 AS seq FROM run WHERE task_id = ?", row -> row.intValue("seq"), taskId)
                .orElseThrow();
    }

    public static OptionalInt latestSucceededPlanSeq(Tx tx, long taskId) {
        return tx.one("SELECT max(seq) AS seq FROM run WHERE task_id = ? AND kind = ? AND status = ?",
                        row -> row.intOrNull("seq"), taskId, RunKind.PLAN, RunStatus.SUCCEEDED)
                .filter(seq -> seq != null)
                .map(OptionalInt::of)
                .orElse(OptionalInt.empty());
    }

    public static void recordProcess(Tx tx, long taskId, int seq, long pid, Instant pidStart) {
        tx.update("UPDATE run SET pid = ?, pid_start = ? WHERE task_id = ? AND seq = ?", pid, pidStart, taskId, seq);
    }

    /** Ends a running run; false if it was not running (already finished elsewhere). */
    public static boolean finish(Tx tx, long taskId, int seq, Finish finish, Instant now) {
        return tx.update("""
                        UPDATE run SET status = ?, finished_at = ?, exit_code = ?, failure_reason = ?, error_detail = ?,
                                       cost_usd = ?, turns = ?, output = ?, denials = ?
                        WHERE task_id = ? AND seq = ? AND status = ?""",
                finish.status(), now, finish.exitCode(), finish.failureReason(), finish.errorDetail(), finish.costUsd(),
                finish.turns(), finish.output(), finish.denials(), taskId, seq, RunStatus.RUNNING) == 1;
    }

    public static int cancelQueued(Tx tx, long taskId, Instant now) {
        return tx.update("UPDATE run SET status = ?, finished_at = ? WHERE task_id = ? AND status = ?",
                RunStatus.CANCELLED, now, taskId, RunStatus.QUEUED);
    }

    private static Run map(Row row) throws SQLException {
        return new Run(
                row.longValue("task_id"),
                row.intValue("seq"),
                row.enumValue("kind", RunKind.class),
                row.enumValue("status", RunStatus.class),
                row.string("instruction"),
                row.string("requested_by"),
                row.string("requested_by_name"),
                row.longOrNull("pid"),
                row.instant("pid_start"),
                row.instant("queued_at"),
                row.instant("started_at"),
                row.instant("finished_at"),
                row.decimal("cost_usd"),
                row.intOrNull("turns"),
                row.enumValue("failure_reason", FailureReason.class));
    }
}
