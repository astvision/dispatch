package dispatch.telegram;

import dispatch.Json;
import dispatch.Log;
import dispatch.Redactor;
import dispatch.core.Signal;
import dispatch.store.Database;
import dispatch.store.Outbox;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Delivers outbox messages one at a time (ADR 0010). Transient failures are retried with logged, bounded backoff. A
 * private message Telegram refuses goes to its fallback in the group (ADR 0011); other permanent failures and anything
 * older than a day are marked FAILED and logged, never dropped silently.
 */
public final class OutboxSender implements Runnable {

    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final Database db;
    private final BotApi api;
    private final Renderer renderer;
    private final Redactor redactor;
    private final Signal signal;
    private final Clock clock;
    private final Duration idlePoll;
    private volatile boolean stopped;

    public OutboxSender(Database db, BotApi api, Renderer renderer, Redactor redactor, Signal signal, Clock clock,
                        Duration idlePoll) {
        this.db = db;
        this.api = api;
        this.renderer = renderer;
        this.redactor = redactor;
        this.signal = signal;
        this.clock = clock;
        this.idlePoll = idlePoll;
    }

    @Override
    public void run() {
        Log.info("outbox.started");
        while (!stopped) {
            if (deliverDue()) {
                continue;
            }
            try {
                signal.await(idlePoll);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.info("outbox.stopped");
    }

    public void stop() {
        stopped = true;
        signal.wake();
    }

    /** Sends the next due message, if any. Returns false when nothing was due. */
    public boolean deliverDue() {
        Optional<Outbox.Message> next = db.transactionReturning(tx -> Outbox.nextDue(tx, clock.instant()));
        if (next.isEmpty()) {
            return false;
        }
        deliver(next.get());
        return true;
    }

    private void deliver(Outbox.Message message) {
        int attempts = message.attempts() + 1;
        Renderer.Rendered rendered;
        try {
            // Masked before rendering, so length limits apply to the text that is actually sent.
            rendered = renderer.render(message.kind(), Json.read(redactor.redact(message.payload())), message.fellBack());
        } catch (RuntimeException e) {
            Log.error("outbox.render_failed", e, "id", message.id(), "kind", message.kind());
            db.transaction(tx -> Outbox.markFailed(tx, message.id(), attempts, "render failed: " + e.getMessage()));
            return;
        }
        long chatId = Refs.chatId(message.chatRef());
        Long replyTo = message.replyToRef() == null ? null : Refs.messageId(message.replyToRef());
        try {
            long sentId = rendered.document() == null
                    ? api.sendMessage(chatId, rendered.html(), replyTo, rendered.keyboard())
                    : api.sendDocument(chatId, rendered.document().fileName(),
                            rendered.document().markdown().getBytes(StandardCharsets.UTF_8), rendered.html(), replyTo, rendered.keyboard());
            db.transaction(tx -> Outbox.markSent(tx, message.id(), attempts, Refs.message(chatId, sentId), clock.instant()));
            Log.info("outbox.sent", "id", message.id(), "kind", message.kind(), "task", message.taskId(), "attempt", attempts);
        } catch (TelegramException e) {
            handleFailure(message, attempts, e);
        }
    }

    private void handleFailure(Outbox.Message message, int attempts, TelegramException error) {
        Instant now = clock.instant();
        if (error.isPermanent() && message.fallbackChatRef() != null) {
            // Usually the requester never pressed Start or has blocked the bot.
            Log.warn("outbox.fell_back", "id", message.id(), "kind", message.kind(), "task", message.taskId(),
                    "attempt", attempts, "error", error.getMessage());
            db.transaction(tx -> Outbox.fallBack(tx, message.id(), attempts, error.getMessage(), now));
            return;
        }
        if (error.isPermanent()) {
            Log.error("outbox.failed", null, "id", message.id(), "kind", message.kind(), "task", message.taskId(),
                    "attempt", attempts, "error", error.getMessage());
            db.transaction(tx -> Outbox.markFailed(tx, message.id(), attempts, error.getMessage()));
            return;
        }
        if (Duration.between(message.createdAt(), now).compareTo(MAX_AGE) >= 0) {
            String reason = "gave up after 24h: " + error.getMessage();
            Log.error("outbox.failed", null, "id", message.id(), "kind", message.kind(), "task", message.taskId(),
                    "attempt", attempts, "error", reason);
            db.transaction(tx -> Outbox.markFailed(tx, message.id(), attempts, reason));
            return;
        }
        Duration delay = error.retryAfterSeconds() != null ? Duration.ofSeconds(error.retryAfterSeconds()) : backoff(attempts);
        Log.warn("outbox.retry", "id", message.id(), "kind", message.kind(), "attempt", attempts,
                "retry_in_seconds", delay.toSeconds(), "error", error.getMessage());
        db.transaction(tx -> Outbox.retryLater(tx, message.id(), attempts, now.plus(delay), error.getMessage()));
    }

    /** 5s, 10s, 20s ... capped at 5 minutes. */
    static Duration backoff(int attempts) {
        Duration delay = FIRST_BACKOFF.multipliedBy(1L << Math.min(attempts - 1, 10));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }
}
