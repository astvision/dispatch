package dispatch.store;

import java.time.Instant;
import java.util.Optional;

/** SQL for additions: replies in a linked group offered to a task's requester, each applied at most once. */
public final class Additions {

    /**
     * @param originRef the message the task (or its draft) came from, as the task keeps it
     * @param memberRef the requester it is offered to, the only one who may apply it
     * @param author    who wrote it, by the first name the group knows them by
     */
    public record Addition(long id, String originRef, String memberRef, String author, String text) {
    }

    private Additions() {
    }

    public static long insert(Tx tx, String originRef, String memberRef, String author, String text, Instant now) {
        return tx.insert("INSERT INTO addition (origin_ref, member_ref, author, text, created_at) VALUES (?, ?, ?, ?, ?)",
                originRef, memberRef, author, text, now);
    }

    public static void markUsed(Tx tx, long id, Instant now) {
        tx.update("UPDATE addition SET used_at = ? WHERE id = ?", now, id);
    }

    /** {@code memberRef}'s addition {@code id}, unless it was already used. */
    public static Optional<Addition> findUnused(Tx tx, long id, String memberRef) {
        return tx.one("SELECT id, origin_ref, member_ref, author, text FROM addition WHERE id = ? AND member_ref = ? AND used_at IS NULL",
                row -> new Addition(row.longValue("id"), row.string("origin_ref"), row.string("member_ref"), row.string("author"),
                        row.string("text")), id, memberRef);
    }
}
