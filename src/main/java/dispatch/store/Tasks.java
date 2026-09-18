package dispatch.store;

import dispatch.domain.FailureReason;
import dispatch.domain.Phase;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.Task;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** SQL for the task table. Phase changes are conditional: they return false when the task already moved on. */
public final class Tasks {

    private static final String COLUMNS = """
            id, project, title, description, phase, priority, requester_ref, requester_name, origin_ref, chat_ref, session_id,
            build_session_id, base_branch, base_sha, worktree, plan_json, pr_url, topic_ref, failure_reason, failure_detail,
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
            String baseBranch,
            Priority priority) {
    }

    public static long insert(Tx tx, NewTask task, Phase phase, Instant now) {
        return tx.insert("""
                        INSERT INTO task (project, title, description, phase, priority, requester_ref, requester_name, origin_ref,
                                          chat_ref, session_id, base_branch, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                task.project(), task.title(), task.description(), phase, task.priority(), task.requester().ref(),
                task.requester().name(), task.originRef(), task.chatRef(), task.sessionId(), task.baseBranch(), now, now);
    }

    public static Optional<Task> find(Tx tx, long id) {
        return tx.one("SELECT " + COLUMNS + " FROM task WHERE id = ?", Tasks::map, id);
    }

    /** The requester's task that owns {@code topicRef} in their private chat. */
    public static Optional<Task> findByTopic(Tx tx, String requesterRef, String topicRef) {
        return tx.one("SELECT " + COLUMNS + " FROM task WHERE requester_ref = ? AND topic_ref = ?", Tasks::map, requesterRef, topicRef);
    }

    public static void recordTopic(Tx tx, long id, String topicRef, Instant now) {
        tx.update("UPDATE task SET topic_ref = ?, updated_at = ? WHERE id = ?", topicRef, now, id);
    }

    public static void forgetTopic(Tx tx, long id, Instant now) {
        tx.update("UPDATE task SET topic_ref = NULL, updated_at = ? WHERE id = ?", now, id);
    }

    public static boolean existsWithOrigin(Tx tx, String originRef) {
        return tx.one("SELECT 1 AS found FROM task WHERE origin_ref = ?", row -> true, originRef).isPresent();
    }

    public static List<Task> active(Tx tx) {
        return tx.list("SELECT " + COLUMNS + " FROM task WHERE phase IN (?, ?, ?) ORDER BY id",
                Tasks::map, Phase.PLANNING, Phase.AWAITING_APPROVAL, Phase.EXECUTING);
    }

    /** Tasks of {@code projects} in {@code phase}, the longest unchanged first. */
    public static List<Task> withPhase(Tx tx, Phase phase, Set<String> projects) {
        if (projects.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>(List.of(phase));
        params.addAll(projects);
        return tx.list("SELECT " + COLUMNS + " FROM task WHERE phase = ? AND project IN (" + Tx.placeholders(projects.size()) + ")"
                + " ORDER BY updated_at, id", Tasks::map, params.toArray());
    }

    /** Tasks of {@code projects} given at or after {@code since}, or ever when it is null. */
    public static List<Task> createdSince(Tx tx, Set<String> projects, Instant since) {
        if (projects.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>(projects);
        String sql = "SELECT " + COLUMNS + " FROM task WHERE project IN (" + Tx.placeholders(projects.size()) + ")";
        if (since != null) {
            sql += " AND created_at >= ?";
            params.add(since);
        }
        return tx.list(sql + " ORDER BY id", Tasks::map, params.toArray());
    }

    /** The {@code limit} most recently finished tasks of {@code projects}, newest first. */
    public static List<Task> finished(Tx tx, Set<String> projects, int limit) {
        if (projects.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>(List.of(Phase.COMPLETED, Phase.FAILED, Phase.REJECTED, Phase.CANCELLED));
        params.addAll(projects);
        params.add(limit);
        return tx.list("SELECT " + COLUMNS + " FROM task WHERE phase IN (?, ?, ?, ?) AND project IN ("
                + Tx.placeholders(projects.size()) + ") ORDER BY completed_at DESC, id DESC LIMIT ?", Tasks::map, params.toArray());
    }

    /** Finished tasks with a worktree, unchanged since before {@code idleSince}, oldest first. */
    public static List<Task> finishedIdleWithWorktree(Tx tx, Instant idleSince) {
        return tx.list("SELECT " + COLUMNS + " FROM task WHERE phase IN (?, ?, ?, ?) AND worktree IS NOT NULL AND updated_at < ?"
                        + " ORDER BY updated_at, id", Tasks::map,
                Phase.COMPLETED, Phase.FAILED, Phase.REJECTED, Phase.CANCELLED, idleSince);
    }

    /** Moves {@code from} to {@code to}; entering a finished phase stamps completed_at. */
    public static boolean changePhase(Tx tx, long id, Phase from, Phase to, Instant now) {
        Instant completedAt = to.isActive() ? null : now;
        return tx.update("""
                        UPDATE task SET phase = ?, completed_at = COALESCE(?, completed_at), updated_at = ?
                        WHERE id = ? AND phase = ?""",
                to, completedAt, now, id, from) == 1;
    }

    /** Sets the priority of a task that has not finished; false when it has. */
    public static boolean changePriority(Tx tx, long id, Priority priority, Instant now) {
        return tx.update("UPDATE task SET priority = ?, updated_at = ? WHERE id = ? AND phase IN (?, ?, ?)",
                priority, now, id, Phase.PLANNING, Phase.AWAITING_APPROVAL, Phase.EXECUTING) == 1;
    }

    public static boolean planned(Tx tx, long id, String planJson, Instant now) {
        return tx.update("""
                        UPDATE task SET phase = ?, plan_json = ?, failure_reason = NULL, failure_detail = NULL, updated_at = ?
                        WHERE id = ? AND phase = ?""",
                Phase.AWAITING_APPROVAL, planJson, now, id, Phase.PLANNING) == 1;
    }

    /**
     * A retried or followed-up task that failed before no longer carries that failure.
     *
     * @param prUrl null keeps the task's existing pull request, if any
     */
    public static boolean completed(Tx tx, long id, String prUrl, Instant now) {
        return tx.update("""
                        UPDATE task SET phase = ?, pr_url = COALESCE(?, pr_url), failure_reason = NULL, failure_detail = NULL,
                                        completed_at = ?, updated_at = ?
                        WHERE id = ? AND phase = ?""",
                Phase.COMPLETED, prUrl, now, now, id, Phase.EXECUTING) == 1;
    }

    public static boolean failed(Tx tx, long id, FailureReason reason, String detail, Instant now) {
        return tx.update("""
                        UPDATE task SET phase = ?, failure_reason = ?, failure_detail = ?, completed_at = ?, updated_at = ?
                        WHERE id = ? AND phase IN (?, ?)""",
                Phase.FAILED, reason, detail, now, now, id, Phase.PLANNING, Phase.EXECUTING) == 1;
    }

    public static void recordBuildSession(Tx tx, long id, UUID buildSessionId, Instant now) {
        tx.update("UPDATE task SET build_session_id = ?, updated_at = ? WHERE id = ?", buildSessionId, now, id);
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
                row.enumValue("priority", Priority.class),
                new Requester(row.string("requester_ref"), row.string("requester_name")),
                row.string("origin_ref"),
                row.string("chat_ref"),
                row.uuid("session_id"),
                row.uuid("build_session_id"),
                row.string("base_branch"),
                row.string("base_sha"),
                row.path("worktree"),
                row.string("plan_json"),
                row.string("pr_url"),
                row.string("topic_ref"),
                row.enumValue("failure_reason", FailureReason.class),
                row.string("failure_detail"),
                row.instant("created_at"),
                row.instant("started_at"),
                row.instant("completed_at"),
                row.instant("updated_at"));
    }
}
