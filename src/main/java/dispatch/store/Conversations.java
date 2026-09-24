package dispatch.store;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** SQL for the assistant's conversations: each member's session, the cost of each turn, and the actions it proposed (A-1). */
public final class Conversations {

    private Conversations() {
    }

    /** The member's session, if one was used at or after {@code usedSince}; an older one is left to be replaced. */
    public static Optional<UUID> session(Tx tx, String memberRef, Instant usedSince) {
        return tx.one("SELECT session_id FROM assistant_session WHERE member_ref = ? AND last_used_at >= ?",
                row -> row.uuid("session_id"), memberRef, usedSince);
    }

    public static void saveSession(Tx tx, String memberRef, UUID sessionId, Instant now) {
        tx.update("""
                INSERT INTO assistant_session (member_ref, session_id, last_used_at) VALUES (?, ?, ?)
                ON CONFLICT (member_ref) DO UPDATE SET session_id = excluded.session_id, last_used_at = excluded.last_used_at""",
                memberRef, sessionId, now);
    }

    /** The next message starts a new session: after /new, or after a turn failed on this one. */
    public static void forgetSession(Tx tx, String memberRef) {
        tx.update("DELETE FROM assistant_session WHERE member_ref = ?", memberRef);
    }

    /** @param costUsd null when the agent did not report one */
    public static void recordTurn(Tx tx, String memberRef, String model, BigDecimal costUsd, Instant now) {
        tx.update("INSERT INTO assistant_turn (member_ref, model, cost_usd, at) VALUES (?, ?, ?, ?)", memberRef, model, costUsd, now);
    }

    /** What the member's turns cost since {@code since} (null: ever); zero when none reported a cost. */
    public static BigDecimal spentSince(Tx tx, String memberRef, Instant since) {
        return tx.list("SELECT cost_usd FROM assistant_turn WHERE member_ref = ? AND at >= ? AND cost_usd IS NOT NULL",
                        row -> row.decimal("cost_usd"), memberRef, since == null ? Instant.EPOCH : since)
                .stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static long proposeAction(Tx tx, String memberRef, JsonNode action, Instant now) {
        return tx.insert("INSERT INTO assistant_action (member_ref, payload, created_at) VALUES (?, ?, ?)",
                memberRef, action.toString(), now);
    }

    /**
     * Takes the member's unused action for running, so a second tap finds nothing.
     *
     * @return the action, empty when it is not theirs, not there, or already taken
     */
    public static Optional<JsonNode> takeAction(Tx tx, long id, String memberRef, Instant now) {
        Optional<JsonNode> action = tx.one("SELECT payload FROM assistant_action WHERE id = ? AND member_ref = ? AND used_at IS NULL",
                row -> Json.read(row.string("payload")), id, memberRef);
        action.ifPresent(taken -> tx.update("UPDATE assistant_action SET used_at = ? WHERE id = ?", now, id));
        return action;
    }
}
