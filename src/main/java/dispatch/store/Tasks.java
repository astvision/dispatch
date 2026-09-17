package dispatch.store;

import dispatch.domain.FailureReason;
import dispatch.domain.Phase;
import dispatch.domain.Requester;
import dispatch.domain.Task;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for the task table. Phase changes are conditional: they return false when the task already moved on. */
public final class Tasks {

    private static final String COLUMNS = """
            id, project, title, description, phase, requester_ref, requester_name, origin_ref, chat_ref, session_id,
            base_branch, base_sha, worktree, plan_json, pr_url, failure_reason, failure_detail,
            created_at, started_at, completed_at, updated_at""";

    private Tasks() {
    }

    public record NewTask(
            String project,
            String title,
            String description,
            Requester requester,
            String originRef,
            String chatRef,
            UUID sessionId,
            String baseBranch) {
    }

    public static long insert(Tx tx, NewTask task, Phase phase, Instant now) {
        return tx.insert("""
                        INSERT INTO task (project, title, description, phase, requester_ref, requester_name, origin_ref, chat_ref,
                                          session_id, base_branch, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                task.project(), task.title(), task.description(), phase, task.requester().ref(), task.requester().name(),
                task.originRef(), task.chatRef(), task.sessionId(), task.baseBranch(), now, now);
    }

    public static Optional<Task> find(Tx tx, long id) {
        return tx.one("SELECT " + COLUMNS + " FROM task WHERE id = ?", Tasks::map, id);
    }

    public static boolean existsWithOrigin(Tx tx, String originRef) {
        return tx.one("SELECT 1 AS found FROM task WHERE origin_ref = ?", row -> true, originRef).isPresent();
    }

    public static List<Task> active(Tx tx) {
        return tx.list("SELECT " + COLUMNS + " FROM task WHERE phase IN (?, ?, ?) ORDER BY id",
                Tasks::map, Phase.PLANNING, Phase.AWAITING_APPROVAL, Phase.EXECUTING);
    }

    /** Moves {@code from} to {@code to}; entering a finished phase stamps completed_at. */
    public static boolean changePhase(Tx tx, long id, Phase from, Phase to, Instant now) {
        Instant completedAt = to.isActive() ? null : now;
        return tx.update("""
                        UPDATE task SET phase = ?, completed_at = COALESCE(?, completed_at), updated_at = ?
                        WHERE id = ? AND phase = ?""",
                to, completedAt, now, id, from) == 1;
    }

    public static boolean planned(Tx tx, long id, String planJson, Instant now) {
        return tx.update("""
                        UPDATE task SET phase = ?, plan_json = ?, updated_at = ?
                        WHERE id = ? AND phase = ?""",
                Phase.AWAITING_APPROVAL, planJson, now, id, Phase.PLANNING) == 1;
    }

    public static boolean failed(Tx tx, long id, FailureReason reason, String detail, Instant now) {
        return tx.update("""
                        UPDATE task SET phase = ?, failure_reason = ?, failure_detail = ?, completed_at = ?, updated_at = ?
                        WHERE id = ? AND phase IN (?, ?)""",
                Phase.FAILED, reason, detail, now, now, id, Phase.PLANNING, Phase.EXECUTING) == 1;
    }

    public static void recordWorktree(Tx tx, long id, Path worktree, String baseSha, Instant now) {
        tx.update("UPDATE task SET worktree = ?, base_sha = ?, updated_at = ? WHERE id = ?", worktree, baseSha, now, id);
    }

    private static Task map(Row row) throws java.sql.SQLException {
        return new Task(
                row.longValue("id"),
                row.string("project"),
                row.string("title"),
                row.string("description"),
                row.enumValue("phase", Phase.class),
                new Requester(row.string("requester_ref"), row.string("requester_name")),
                row.string("origin_ref"),
                row.string("chat_ref"),
                row.uuid("session_id"),
                row.string("base_branch"),
                row.string("base_sha"),
                row.path("worktree"),
                row.string("plan_json"),
                row.string("pr_url"),
                row.enumValue("failure_reason", FailureReason.class),
                row.string("failure_detail"),
                row.instant("created_at"),
                row.instant("started_at"),
                row.instant("completed_at"),
                row.instant("updated_at"));
    }
}
