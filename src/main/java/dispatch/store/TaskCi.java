package dispatch.store;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** SQL for task_ci: the watch on the checks of a task's delivered commit (spec: CI watch). */
public final class TaskCi {

    private static final String COLUMNS = "task_id, head_sha, state, reason, fix_rounds, checks_json, armed_at, checked_at";

    private TaskCi() {
    }

    public enum State {
        /** The checks are running, or have not appeared yet: the only state GitHub is asked about. */
        PENDING,
        PASSED,
        /** A fix run is queued or running for this commit. */
        FIXING,
        /** Handed back to the requester; {@code reason} says why. */
        GAVE_UP,
        /** The repository reported no checks. */
        NONE,
        /** No longer this watch's business; {@code reason} says why. */
        STOPPED
    }

    /**
     * @param headSha    the delivered commit the state is about
     * @param reason     why it gave up or stopped; null otherwise
     * @param checksJson the failed checks as last read, [{name, link}]; null until a red verdict
     * @param armedAt    when {@code headSha} was delivered
     * @param checkedAt  when GitHub was last asked; null before the first time
     */
    public record Watch(long taskId, String headSha, State state, String reason, int fixRounds, String checksJson, Instant armedAt,
                        Instant checkedAt) {
    }

    /** Watches {@code headSha} from now; {@code keepRounds} for a commit a fix run delivered, so its rounds still count. */
    public static void arm(Tx tx, long taskId, String headSha, boolean keepRounds, Instant now) {
        tx.update("""
                        INSERT INTO task_ci (task_id, head_sha, state, fix_rounds, armed_at) VALUES (?, ?, 'PENDING', 0, ?)
                        ON CONFLICT (task_id) DO UPDATE SET head_sha = excluded.head_sha, state = 'PENDING', reason = NULL,
                            checks_json = NULL, armed_at = excluded.armed_at, checked_at = NULL,
                            fix_rounds = CASE WHEN ? = 1 THEN fix_rounds ELSE 0 END""",
                taskId, headSha, now, keepRounds ? 1 : 0);
    }

    public static Optional<Watch> find(Tx tx, long taskId) {
        return tx.one("SELECT " + COLUMNS + " FROM task_ci WHERE task_id = ?", TaskCi::map, taskId);
    }

    /** Every watch that still has something to decide, oldest first. */
    public static List<Watch> open(Tx tx) {
        return tx.list("SELECT " + COLUMNS + " FROM task_ci WHERE state IN ('PENDING', 'FIXING') ORDER BY armed_at, task_id",
                TaskCi::map);
    }

    public static boolean anyOpen(Tx tx) {
        return tx.one("SELECT 1 AS found FROM task_ci WHERE state IN ('PENDING', 'FIXING') LIMIT 1", row -> true).isPresent();
    }

    /**
     * The watch's verdict. The caller has read the watch again in this transaction, so nothing is guarded here.
     *
     * @param checksJson the failed checks; null keeps what the watch already holds
     */
    public static void settle(Tx tx, long taskId, State to, String reason, String checksJson, Instant now) {
        tx.update("UPDATE task_ci SET state = ?, reason = ?, checks_json = COALESCE(?, checks_json), checked_at = ? WHERE task_id = ?",
                to, reason, checksJson, now, taskId);
    }

    /** A fix run was queued for the watched commit: one more round. */
    public static void fixing(Tx tx, long taskId, String checksJson, Instant now) {
        tx.update("""
                        UPDATE task_ci SET state = 'FIXING', reason = NULL, checks_json = ?, fix_rounds = fix_rounds + 1, checked_at = ?
                        WHERE task_id = ?""",
                checksJson, now, taskId);
    }

    /** GitHub was asked and had no verdict yet. */
    public static void checked(Tx tx, long taskId, Instant now) {
        tx.update("UPDATE task_ci SET checked_at = ? WHERE task_id = ?", now, taskId);
    }

    private static Watch map(Row row) throws SQLException {
        return new Watch(row.longValue("task_id"), row.string("head_sha"), row.enumValue("state", State.class), row.string("reason"),
                row.intValue("fix_rounds"), row.string("checks_json"), row.instant("armed_at"), row.instant("checked_at"));
    }
}
