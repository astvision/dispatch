package dispatch.store;

import dispatch.domain.FailureReason;
import dispatch.domain.Phase;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.Task;
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
            created_at, started_at, completed_at, updated_at, merged_at""";

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

    /** The projects {@code requesterRef} gave tasks for, the one of their latest task first. */
    public static List<String> recentProjects(Tx tx, String requesterRef) {
        return tx.list("SELECT project FROM task WHERE requester_ref = ? GROUP BY project ORDER BY max(id) DESC",
                row -> row.string("project"), requesterRef);
    }

    public static boolean existsWithOrigin(Tx tx, String originRef) {
        return tx.one("SELECT 1 AS found FROM task WHERE origin_ref = ?", row -> true, originRef).isPresent();
    }

    public static Optional<Task> findByOrigin(Tx tx, String originRef) {
        return tx.one("SELECT " + COLUMNS + " FROM task WHERE origin_ref = ?", Tasks::map, originRef);
    }

    /**
     * The tasks message {@code messageRef} gave: its own origin, in a topic ("@thread"), and each developer's or part's
     * ("#…") of the same message. GLOB rather than LIKE, so the origin_ref index serves the prefixes; a reference holds no
     * GLOB wildcard (*, ?, [).
     */
    public static List<Task> fromMessage(Tx tx, String messageRef) {
        return tx.list("SELECT " + COLUMNS + " FROM task WHERE origin_ref = ? OR origin_ref GLOB ? OR origin_ref GLOB ? ORDER BY id",
                Tasks::map, messageRef, messageRef + "@*", messageRef + "#*");
    }

    public static List<Task> active(Tx tx) {
        return tx.list("SELECT " + COLUMNS + " FROM task WHERE phase IN (?, ?, ?) ORDER BY id",
                Tasks::map, Phase.PLANNING, Phase.AWAITING_APPROVAL, Phase.EXECUTING);
    }

    /** Tasks in {@code phase} of {@code projects}, or given by {@code requesterRef}, the longest unchanged first. */
    public static List<Task> withPhase(Tx tx, Phase phase, Set<String> projects, String requesterRef) {
        List<Object> params = new ArrayList<>(List.of(phase));
        String visible = visibleClause("", projects, requesterRef, params);
        if (visible == null) {
            return List.of();
        }
        return tx.list("SELECT " + COLUMNS + " FROM task WHERE phase = ? AND " + visible + " ORDER BY updated_at, id",
                Tasks::map, params.toArray());
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

    /** The {@code limit} most recently finished tasks of {@code projects}, or given by {@code requesterRef}, newest first. */
    public static List<Task> finished(Tx tx, Set<String> projects, String requesterRef, int limit) {
        List<Object> params = new ArrayList<>(List.of(Phase.COMPLETED, Phase.FAILED, Phase.REJECTED, Phase.CANCELLED));
        String visible = visibleClause("", projects, requesterRef, params);
        if (visible == null) {
            return List.of();
        }
        params.add(limit);
        return tx.list("SELECT " + COLUMNS + " FROM task WHERE phase IN (?, ?, ?, ?) AND " + visible
                + " ORDER BY completed_at DESC, id DESC LIMIT ?", Tasks::map, params.toArray());
    }

    /**
     * A viewer's list: tasks of their projects, and their own wherever they are (ADR 0027). Adds its parameters to
     * {@code params}; null when nothing can match, with no projects and no member.
     *
     * @param alias the task table's alias with its dot, e.g. {@code "t."}; empty when it has none
     */
    static String visibleClause(String alias, Set<String> projects, String requesterRef, List<Object> params) {
        List<String> terms = new ArrayList<>();
        if (!projects.isEmpty()) {
            terms.add(alias + "project IN (" + Tx.placeholders(projects.size()) + ")");
            params.addAll(projects);
        }
        if (requesterRef != null) {
            terms.add(alias + "requester_ref = ?");
            params.add(requesterRef);
        }
        return terms.isEmpty() ? null : "(" + String.join(" OR ", terms) + ")";
    }

    /** Finished tasks with a worktree, unchanged since before {@code idleSince}, oldest first. */
    public static List<Task> finishedIdleWithWorktree(Tx tx, Instant idleSince) {
        return tx.list("SELECT " + COLUMNS + " FROM task WHERE phase IN (?, ?, ?, ?) AND worktree IS NOT NULL AND updated_at < ?"
                        + " ORDER BY updated_at, id", Tasks::map,
                Phase.COMPLETED, Phase.FAILED, Phase.REJECTED, Phase.CANCELLED, idleSince);
    }

    /**
     * Moves {@code from} to {@code to}; entering a finished phase stamps completed_at. A finished task running again (a retry
     * or follow-up) drops a pin to a computer revoked since: {@link #clearWorkerPin} kept it only as a record, and the run
     * would otherwise wait for that computer forever.
     */
    public static boolean changePhase(Tx tx, long id, Phase from, Phase to, Instant now) {
        Instant completedAt = to.isActive() ? null : now;
        boolean changed = tx.update("""
                        UPDATE task SET phase = ?, completed_at = COALESCE(?, completed_at), updated_at = ?
                        WHERE id = ? AND phase = ?""",
                to, completedAt, now, id, from) == 1;
        if (changed && !from.isActive() && to.isActive()) {
            tx.update("UPDATE task SET worker_id = NULL WHERE id = ? AND worker_id IN (SELECT id FROM worker WHERE revoked_at IS NOT NULL)",
                    id);
        }
        return changed;
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

    /** Its pull request was merged; false when that was recorded already. */
    public static boolean merged(Tx tx, long id, Instant now) {
        return tx.update("UPDATE task SET merged_at = ?, updated_at = ? WHERE id = ? AND merged_at IS NULL", now, now, id) == 1;
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

    /** The new worktree's branch starts at {@code baseSha}, which is where the next execution must find it. */
    public static void recordWorktree(Tx tx, long id, String worktree, String baseSha, Instant now) {
        tx.update("UPDATE task SET worktree = ?, base_sha = ?, head_sha = ?, updated_at = ? WHERE id = ?", worktree, baseSha,
                baseSha, now, id);
    }

    /** The commit Dispatch last left the task's branch at; null when unknown, and then nothing is checked. */
    public static String expectedHead(Tx tx, long id) {
        return tx.one("SELECT head_sha FROM task WHERE id = ?", row -> row.string("head_sha"), id).orElse(null);
    }

    /** @param head where a delivery left the task's branch; null when the worker did not say, which ends the check */
    public static void recordHead(Tx tx, long id, String head, Instant now) {
        tx.update("UPDATE task SET head_sha = ?, updated_at = ? WHERE id = ?", head, now, id);
    }

    /** The computer that made this task's worktree: every later run of the task goes back to it. */
    public static void recordWorker(Tx tx, long id, long workerId, Instant now) {
        tx.update("UPDATE task SET worker_id = ?, updated_at = ? WHERE id = ?", workerId, now, id);
    }

    /** The readiness blocker code the requester was last told holds this task, or null when nothing does. */
    public static String blockedReason(Tx tx, long id) {
        return tx.one("SELECT blocked_reason FROM task WHERE id = ?", row -> row.string("blocked_reason"), id).orElse(null);
    }

    /** Only writes when it changes: called on every idle poll of the scheduler. */
    public static void setBlockedReason(Tx tx, long id, String code) {
        tx.update("UPDATE task SET blocked_reason = ? WHERE id = ? AND blocked_reason IS NOT ?", code, id, code);
    }

    public static Optional<Long> workerOf(Tx tx, long id) {
        return tx.one("SELECT worker_id FROM task WHERE id = ?", row -> row.longOrNull("worker_id"), id);
    }

    /**
     * Clears the pin to {@code workerId} on its still-active tasks, once that worker is revoked: a follow-up or retry
     * then goes to whichever of the member's other computers is live, rather than waiting on this one forever. The old
     * worktree stays on the revoked machine, so such a run starts a fresh one from the base branch. A finished task's
     * pin is left as a record of which computer actually did the work, until {@link #changePhase} runs the task again.
     */
    public static int clearWorkerPin(Tx tx, long workerId, Instant now) {
        return tx.update("UPDATE task SET worker_id = NULL, updated_at = ? WHERE worker_id = ? AND phase IN (?, ?, ?)",
                now, workerId, Phase.PLANNING, Phase.AWAITING_APPROVAL, Phase.EXECUTING);
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
                row.string("worktree"),
                row.string("plan_json"),
                row.string("pr_url"),
                row.string("topic_ref"),
                row.enumValue("failure_reason", FailureReason.class),
                row.string("failure_detail"),
                row.instant("created_at"),
                row.instant("started_at"),
                row.instant("completed_at"),
                row.instant("updated_at"),
                row.instant("merged_at"));
    }
}
