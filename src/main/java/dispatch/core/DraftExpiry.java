package dispatch.core;

import dispatch.Log;
import dispatch.store.Database;
import java.time.Clock;
import java.time.Duration;

/** Expires drafts nobody answered within {@code maxAge}, checking every {@code interval} (ADR 0012). */
public final class DraftExpiry implements Runnable {

    private final Database db;
    private final TaskService tasks;
    private final Clock clock;
    private final Duration maxAge;
    private final Duration interval;
    private final Signal signal = new Signal();
    private volatile boolean stopped;

    public DraftExpiry(Database db, TaskService tasks, Clock clock, Duration maxAge, Duration interval) {
        this.db = db;
        this.tasks = tasks;
        this.clock = clock;
        this.maxAge = maxAge;
        this.interval = interval;
    }

    @Override
    public void run() {
        Log.info("draft_expiry.started", "max_age_hours", maxAge.toHours());
        while (!stopped) {
            db.transaction(tx -> tasks.expireDrafts(tx, clock.instant().minus(maxAge)));
            try {
                signal.await(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.info("draft_expiry.stopped");
    }

    public void stop() {
        stopped = true;
        signal.wake();
    }
}
