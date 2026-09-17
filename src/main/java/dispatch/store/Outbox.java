package dispatch.store;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.domain.OutboxKind;
import java.time.Instant;
import java.util.Optional;

/** SQL for outbox messages (ADR 0010). */
public final class Outbox {

    private Outbox() {
    }

    /** A pending message as the sender sees it. */
    public record Message(
            long id,
            Long taskId,
            OutboxKind kind,
            String chatRef,
            String replyToRef,
            String payload,
            int attempts,
            Instant createdAt) {
    }

    /** A delivered message, as found through the reference the channel gave it. */
    public record Sent(long id, Long taskId, OutboxKind kind, String payload) {
    }

    /**
     * @param taskId     null for replies that concern no task (help, not allowed, ...)
     * @param replyToRef channel reference of the message to reply to, null for none
     */
    public static long enqueue(Tx tx, Long taskId, OutboxKind kind, String chatRef, String replyToRef, JsonNode payload,
                               Instant now) {
        return tx.insert("""
                        INSERT INTO outbox (task_id, kind, chat_ref, reply_to_ref, payload, status, next_attempt_at, created_at)
                        VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)""",
                taskId, kind, chatRef, replyToRef, Json.write(payload), now, now);
    }

    /** The pending message that has waited longest past its next attempt time. */
    public static Optional<Message> nextDue(Tx tx, Instant now) {
        return tx.one("""
                        SELECT id, task_id, kind, chat_ref, reply_to_ref, payload, attempts, created_at
                        FROM outbox
                        WHERE status = 'PENDING' AND next_attempt_at <= ?
                        ORDER BY next_attempt_at, id
                        LIMIT 1""",
                row -> new Message(row.longValue("id"), row.longOrNull("task_id"), row.enumValue("kind", OutboxKind.class),
                        row.string("chat_ref"), row.string("reply_to_ref"), row.string("payload"), row.intValue("attempts"),
                        row.instant("created_at")),
                now);
    }

    public static Optional<Sent> findSent(Tx tx, String sentRef) {
        return tx.one("SELECT id, task_id, kind, payload FROM outbox WHERE sent_ref = ?",
                row -> new Sent(row.longValue("id"), row.longOrNull("task_id"), row.enumValue("kind", OutboxKind.class),
                        row.string("payload")),
                sentRef);
    }

    public static void markSent(Tx tx, long id, int attempts, String sentRef, Instant now) {
        tx.update("UPDATE outbox SET status = 'SENT', attempts = ?, sent_ref = ?, sent_at = ?, last_error = NULL WHERE id = ?",
                attempts, sentRef, now, id);
    }

    public static void retryLater(Tx tx, long id, int attempts, Instant nextAttemptAt, String error) {
        tx.update("UPDATE outbox SET attempts = ?, next_attempt_at = ?, last_error = ? WHERE id = ?",
                attempts, nextAttemptAt, error, id);
    }

    public static void markFailed(Tx tx, long id, int attempts, String error) {
        tx.update("UPDATE outbox SET status = 'FAILED', attempts = ?, last_error = ? WHERE id = ?", attempts, error, id);
    }
}
