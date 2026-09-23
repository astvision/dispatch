package dispatch.store;

import dispatch.domain.Requester;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    private static Paired map(Row row) throws SQLException {
        return new Paired(row.longValue("id"), row.string("member_ref"), row.string("name"), row.string("key_sha256"),
                row.instant("created_at"), row.instant("last_seen_at"));
    }
}
