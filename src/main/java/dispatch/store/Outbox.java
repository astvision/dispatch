package dispatch.store;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.domain.OutboxKind;
import dispatch.domain.Task;
import java.time.Instant;
import java.util.Optional;

/** SQL for outbox messages (ADR 0010). */
public final class Outbox {

    private Outbox() {
    }

    /**
     * A pending message as the sender sees it.
     *
     * @param editRef         the message this one redraws in place; null for a new message
     * @param fallbackChatRef where the message goes if its chat refuses it; null when it has no fallback
     * @param fellBack        the message already went to its fallback
     */
    public record Message(
            long id,
            Long taskId,
            OutboxKind kind,
            String chatRef,
            String replyToRef,
            String editRef,
            String fallbackChatRef,
            String fallbackReplyToRef,
            boolean fellBack,
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

    /**
     * A message for someone's private chat that goes to {@code fallbackChatRef}, as a reply to {@code fallbackReplyToRef},
     * if the channel refuses it, e.g. because they blocked the bot (ADR 0011).
     */
    public static long enqueueWithFallback(Tx tx, Long taskId, OutboxKind kind, String chatRef, String replyToRef,
                                           String fallbackChatRef, String fallbackReplyToRef, JsonNode payload, Instant now) {
        return tx.insert("""
                        INSERT INTO outbox (task_id, kind, chat_ref, reply_to_ref, fallback_chat_ref, fallback_reply_to_ref, payload,
                                            status, next_attempt_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)""",
                taskId, kind, chatRef, replyToRef, fallbackChatRef, fallbackReplyToRef, Json.write(payload), now, now);
    }

    /**
     * A task message for its requester's private chat, under the message that gave the task, falling back to the task's
     * group (ADR 0011, 0012). The requester reference addresses the private chat: for Telegram, a private chat's id is the
     * user's id. A reply target is set only in the chat that holds the message, so a reply never lands on an unrelated one.
     */
    public static long enqueueForRequester(Tx tx, Task task, OutboxKind kind, JsonNode payload, Instant now) {
        if (!task.hasGroupChat()) {
            // A personal bot's task (ADR 0014): there is no group to fall back to.
            return enqueue(tx, task.id(), kind, task.requester().ref(), task.privateOriginRef(), payload, now);
        }
        return enqueueWithFallback(tx, task.id(), kind, task.requester().ref(), task.privateOriginRef(), task.chatRef(),
                task.groupOriginRef(), payload, now);
    }

    /**
     * Redraws the message {@code editRef} in {@code chatRef} with a fresh rendering of {@code payload}, e.g. a prompt whose
     * buttons changed after a background step. The edited message keeps its own row, so replies to it are still found.
     */
    public static long enqueueEdit(Tx tx, Long taskId, OutboxKind kind, String chatRef, String editRef, JsonNode payload,
                                   Instant now) {
        return tx.insert("""
                        INSERT INTO outbox (task_id, kind, chat_ref, edit_ref, payload, status, next_attempt_at, created_at)
                        VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)""",
                taskId, kind, chatRef, editRef, Json.write(payload), now, now);
    }

    /** The pending message that has waited longest past its next attempt time. */
    public static Optional<Message> nextDue(Tx tx, Instant now) {
        return tx.one("""
                        SELECT id, task_id, kind, chat_ref, reply_to_ref, edit_ref, fallback_chat_ref, fallback_reply_to_ref, fell_back,
                               payload, attempts, created_at
                        FROM outbox
                        WHERE status = 'PENDING' AND next_attempt_at <= ?
                        ORDER BY next_attempt_at, id
                        LIMIT 1""",
                row -> new Message(row.longValue("id"), row.longOrNull("task_id"), row.enumValue("kind", OutboxKind.class),
                        row.string("chat_ref"), row.string("reply_to_ref"), row.string("edit_ref"), row.string("fallback_chat_ref"),
                        row.string("fallback_reply_to_ref"), row.intValue("fell_back") == 1, row.string("payload"),
                        row.intValue("attempts"), row.instant("created_at")),
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

    /** Re-addresses a refused message to its fallback, due at once. */
    public static void fallBack(Tx tx, long id, int attempts, String error, Instant now) {
        tx.update("""
                        UPDATE outbox SET chat_ref = fallback_chat_ref, reply_to_ref = fallback_reply_to_ref, fallback_chat_ref = NULL,
                                          fallback_reply_to_ref = NULL, fell_back = 1, attempts = ?, next_attempt_at = ?, last_error = ?
                        WHERE id = ? AND fallback_chat_ref IS NOT NULL""",
                attempts, now, error, id);
    }

    public static void markFailed(Tx tx, long id, int attempts, String error) {
        tx.update("UPDATE outbox SET status = 'FAILED', attempts = ?, last_error = ? WHERE id = ?", attempts, error, id);
    }
}
