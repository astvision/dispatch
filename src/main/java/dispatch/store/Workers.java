package dispatch.store;

import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.worker.Readiness;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** SQL for members' computers and the one-time codes that pair them. Keys and codes are stored only as SHA-256. */
public final class Workers {

    /** A worker counts as connected while its last request is younger than this: one lease, one long poll and a margin. */
    public static final Duration SEEN_WITHIN = Duration.ofSeconds(60);

    private static final String COLUMNS = "id, member_ref, name, key_sha256, created_at, last_seen_at";

    private Workers() {
    }

    /** @param lastSeenAt null until the worker's first request */
    public record Paired(long id, String memberRef, String name, String keySha256, Instant createdAt, Instant lastSeenAt) {
    }

    public static long insert(Tx tx, String memberRef, String name, String keySha256, Instant now) {
        return tx.insert("INSERT INTO worker (member_ref, name, key_sha256, created_at) VALUES (?, ?, ?, ?)",
                memberRef, name, keySha256, now);
    }

    /** Every worker that may still be used, for the key check. */
    public static List<Paired> active(Tx tx) {
        return tx.list("SELECT " + COLUMNS + " FROM worker WHERE revoked_at IS NULL ORDER BY id", Workers::map);
    }

    public static List<Paired> ofMember(Tx tx, String memberRef) {
        return tx.list("SELECT " + COLUMNS + " FROM worker WHERE member_ref = ? AND revoked_at IS NULL ORDER BY id",
                Workers::map, memberRef);
    }

    public static Optional<Paired> find(Tx tx, long id) {
        return tx.one("SELECT " + COLUMNS + " FROM worker WHERE id = ? AND revoked_at IS NULL", Workers::map, id);
    }

    public static void touch(Tx tx, long id, Instant now) {
        tx.update("UPDATE worker SET last_seen_at = ? WHERE id = ?", now, id);
    }

    /** @return false when it does not exist or was revoked already */
    public static boolean revoke(Tx tx, long id, Instant now) {
        return tx.update("UPDATE worker SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", now, id) == 1;
    }

    /**
     * Revokes every worker of someone not in {@code memberRefs}, returning the ids just revoked so the caller can clear
     * their pin on whatever tasks they held (in the same transaction). An empty set means nobody is a member any more,
     * so every active worker is revoked.
     */
    public static List<Long> revokeMembersExcept(Tx tx, Set<String> memberRefs, Instant now) {
        String where = memberRefs.isEmpty() ? "" : " AND member_ref NOT IN (" + Tx.placeholders(memberRefs.size()) + ")";
        Object[] whereParams = memberRefs.toArray();
        List<Long> ids = tx.list("SELECT id FROM worker WHERE revoked_at IS NULL" + where, row -> row.longValue("id"), whereParams);
        Object[] updateParams = new Object[whereParams.length + 1];
        updateParams[0] = now;
        System.arraycopy(whereParams, 0, updateParams, 1, whereParams.length);
        tx.update("UPDATE worker SET revoked_at = ? WHERE revoked_at IS NULL" + where, updateParams);
        return ids;
    }

    /** Whether {@code memberRef} has a worker that reported since {@code since}. */
    public static boolean hasConnected(Tx tx, String memberRef, Instant since) {
        return tx.one("SELECT 1 AS found FROM worker WHERE member_ref = ? AND revoked_at IS NULL AND last_seen_at > ? LIMIT 1",
                row -> true, memberRef, since).isPresent();
    }

    /** Whether worker {@code workerId} itself is live: not revoked and reported since {@code since}. */
    public static boolean isLive(Tx tx, long workerId, Instant since) {
        return tx.one("SELECT 1 AS found FROM worker WHERE id = ? AND revoked_at IS NULL AND last_seen_at > ? LIMIT 1",
                row -> true, workerId, since).isPresent();
    }

    public static void insertCode(Tx tx, String codeSha256, Requester member, Instant now, Instant expiresAt) {
        tx.update("INSERT INTO pairing_code (code_sha256, member_ref, member_name, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                codeSha256, member.ref(), member.name(), now, expiresAt);
    }

    /**
     * Claims a code: the member it belongs to, or empty when it is unknown, used or expired. The claim is the conditional
     * update itself, so two workers racing on one code cannot both get a key.
     */
    public static Optional<Requester> useCode(Tx tx, String codeSha256, Instant now) {
        if (tx.update("UPDATE pairing_code SET used_at = ? WHERE code_sha256 = ? AND used_at IS NULL AND expires_at > ?",
                now, codeSha256, now) != 1) {
            return Optional.empty();
        }
        return tx.one("SELECT member_ref, member_name FROM pairing_code WHERE code_sha256 = ?",
                row -> new Requester(row.string("member_ref"), row.string("member_name")), codeSha256);
    }

    public static int deleteExpiredCodes(Tx tx, Instant now) {
        return tx.update("DELETE FROM pairing_code WHERE expires_at <= ?", now);
    }

    /** Replaces this worker's whole report: a computer that was fixed must not stay broken here. */
    public static void saveReadiness(Tx tx, long workerId, Readiness readiness, Instant now) {
        tx.update("UPDATE worker SET claude_ok = ?, claude_detail = ?, gh_ok = ?, gh_detail = ?, readiness_at = ? WHERE id = ?",
                readiness.claude().ok() ? 1 : 0, readiness.claude().detail(),
                readiness.gh().ok() ? 1 : 0, readiness.gh().detail(), now, workerId);
        tx.update("DELETE FROM worker_project WHERE worker_id = ?", workerId);
        readiness.projects().forEach((project, check) ->
                tx.update("INSERT INTO worker_project (worker_id, project, ok, detail) VALUES (?, ?, ?, ?)",
                        workerId, project, check.ok() ? 1 : 0, check.detail()));
    }

    /** Never null: a worker that reported nothing counts as ready (see {@link Readiness#READY}). */
    public static Readiness readiness(Tx tx, long workerId) {
        Optional<Readiness> base = tx.one(
                "SELECT claude_ok, claude_detail, gh_ok, gh_detail FROM worker WHERE id = ? AND claude_ok IS NOT NULL",
                row -> new Readiness(new Readiness.Check(row.intValue("claude_ok") == 1, row.string("claude_detail")),
                        new Readiness.Check(row.intValue("gh_ok") == 1, row.string("gh_detail")), Map.of()),
                workerId);
        if (base.isEmpty()) {
            return Readiness.READY;
        }
        Map<String, Readiness.Check> projects = new LinkedHashMap<>();
        for (Map.Entry<String, Readiness.Check> project : tx.list(
                "SELECT project, ok, detail FROM worker_project WHERE worker_id = ?",
                row -> Map.entry(row.string("project"), new Readiness.Check(row.intValue("ok") == 1, row.string("detail"))),
                workerId)) {
            projects.put(project.getKey(), project.getValue());
        }
        return new Readiness(base.get().claude(), base.get().gh(), projects);
    }

    /**
     * What holds a run of {@code kind} on {@code project} for {@code memberRef}: empty when any computer that may take
     * it — the pinned one, else any of the member's — can, otherwise the first of their blockers. The same rule as
     * {@link Runs#claimNext}'s worker gate, so a run that is only waiting for its turn is never reported as held.
     *
     * <p>Only live computers count (per {@link #isLive}, typically {@code now.minus(SEEN_WITHIN)}): with none, this is
     * empty rather than a blocker, since a stale report must not be read as "Claude Code is broken" when the actual
     * state is "not connected", which the offline path already says.
     *
     * @param pinnedWorkerId the computer holding the task's worktree, or null when it has none yet
     */
    public static Optional<Readiness.Blocker> blockerOf(Tx tx, String memberRef, Long pinnedWorkerId, Instant seenSince,
                                                        String project, RunKind kind) {
        Optional<Readiness.Blocker> first = Optional.empty();
        for (Paired worker : ofMember(tx, memberRef)) {
            if ((pinnedWorkerId != null && worker.id() != pinnedWorkerId) || !isLive(tx, worker.id(), seenSince)) {
                continue;
            }
            Optional<Readiness.Blocker> blocker = readiness(tx, worker.id()).blocker(project, kind);
            if (blocker.isEmpty()) {
                return Optional.empty();
            }
            if (first.isEmpty()) {
                first = blocker;
            }
        }
        return first;
    }

    private static Paired map(Row row) throws SQLException {
        return new Paired(row.longValue("id"), row.string("member_ref"), row.string("name"), row.string("key_sha256"),
                row.instant("created_at"), row.instant("last_seen_at"));
    }
}
