package dispatch.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Log;
import dispatch.store.DatabaseException;
import java.time.Duration;
import java.util.List;

/**
 * Long-polls Telegram and hands updates to the handler in order. Telegram outages are retried with logged backoff;
 * an update that fails to process is logged and skipped; storage failures end the loop (fatal).
 */
public final class Poller implements Runnable {

    private final BotApi api;
    private final UpdateHandler handler;
    private final int longPollSeconds;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private volatile boolean stopped;

    public Poller(BotApi api, UpdateHandler handler, int longPollSeconds, Duration initialBackoff, Duration maxBackoff) {
        this.api = api;
        this.handler = handler;
        this.longPollSeconds = longPollSeconds;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
    }

    @Override
    public void run() {
        long offset = handler.nextOffset();
        Duration backoff = initialBackoff;
        Log.info("telegram.polling", "offset", offset);
        while (!stopped) {
            List<JsonNode> updates;
            try {
                updates = api.getUpdates(offset, longPollSeconds);
                backoff = initialBackoff;
            } catch (TelegramException e) {
                if (stopped) {
                    break;
                }
                Log.warn("telegram.poll_failed", "error", e.getMessage(), "retry_in_ms", backoff.toMillis());
                if (!sleep(backoff)) {
                    break;
                }
                backoff = backoff.multipliedBy(2).compareTo(maxBackoff) > 0 ? maxBackoff : backoff.multipliedBy(2);
                continue;
            }
            for (JsonNode update : updates) {
                offset = process(update);
            }
        }
        Log.info("telegram.polling_stopped");
    }

    public void stop() {
        stopped = true;
    }

    private long process(JsonNode update) {
        long updateId = update.path("update_id").asLong();
        try {
            handler.handle(update);
        } catch (DatabaseException e) {
            throw e;
        } catch (RuntimeException e) {
            Log.error("telegram.update_failed", e, "update_id", updateId, "update", update.toString());
            handler.skip(updateId);
        }
        return updateId + 1;
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
