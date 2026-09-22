package dispatch.core;

import dispatch.Log;
import dispatch.domain.ClaimedRun;
import dispatch.store.Database;
import dispatch.store.Runs;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Claims queued runs and hands them to {@code starter}. Woken when work is queued or a run ends; the idle poll is only a
 * safety net. Storage errors propagate and end the loop (the caller treats that as fatal).
 */
public final class Scheduler implements Runnable {

    private final Database db;
    private final int maxConcurrentRuns;
    private final Signal signal;
    private final Clock clock;
    private final Consumer<ClaimedRun> starter;
    private final Duration idlePoll;
    private final Duration workerSeenWithin;
    private volatile boolean stopped;

    public Scheduler(Database db, int maxConcurrentRuns, Signal signal, Clock clock, Consumer<ClaimedRun> starter,
                     Duration idlePoll) {
        this(db, maxConcurrentRuns, signal, clock, starter, idlePoll, null);
    }

    /** @param workerSeenWithin team mode: how recently a requester's computer must have reported; null in personal mode */
    public Scheduler(Database db, int maxConcurrentRuns, Signal signal, Clock clock, Consumer<ClaimedRun> starter,
                     Duration idlePoll, Duration workerSeenWithin) {
        this.db = db;
        this.maxConcurrentRuns = maxConcurrentRuns;
        this.signal = signal;
        this.clock = clock;
        this.starter = starter;
        this.idlePoll = idlePoll;
        this.workerSeenWithin = workerSeenWithin;
    }

    @Override
    public void run() {
        Log.info("scheduler.started", "max_concurrent_runs", maxConcurrentRuns);
        while (!stopped) {
            Instant now = clock.instant();
            Instant seenSince = workerSeenWithin == null ? null : now.minus(workerSeenWithin);
            Optional<ClaimedRun> next = db.transactionReturning(tx -> Runs.claimNext(tx, maxConcurrentRuns, now, seenSince));
            if (next.isPresent()) {
                ClaimedRun run = next.get();
                Log.info("run.claimed", "task", run.taskId(), "run", run.seq(), "kind", run.kind());
                starter.accept(run);
                continue;
            }
            try {
                signal.await(idlePoll);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.info("scheduler.stopped");
    }

    /** Stops claiming; runs already started keep going. */
    public void stop() {
        stopped = true;
        signal.wake();
    }
}
