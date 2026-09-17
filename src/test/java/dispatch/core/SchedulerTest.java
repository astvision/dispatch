package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import dispatch.domain.ClaimedRun;
import dispatch.domain.Phase;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.testing.TestClock;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchedulerTest {

    @TempDir
    Path dir;

    private Database db;
    private final TestClock clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));

    @BeforeEach
    void open() {
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void startsQueuedRunsUpToTheCapAndContinuesWhenWoken() throws InterruptedException {
        long first = queue();
        long second = queue();
        BlockingQueue<ClaimedRun> started = new LinkedBlockingQueue<>();
        Signal signal = new Signal();
        Scheduler scheduler = new Scheduler(db, 1, signal, clock, started::add, Duration.ofMinutes(10));
        Thread thread = Thread.ofVirtual().start(scheduler);

        assertEquals(first, started.poll(5, TimeUnit.SECONDS).taskId());
        assertNull(started.poll(300, TimeUnit.MILLISECONDS), "cap of 1 must hold the second run back");

        db.transaction(tx -> tx.update("UPDATE run SET status = 'SUCCEEDED' WHERE task_id = ?", first));
        signal.wake();
        assertEquals(second, started.poll(5, TimeUnit.SECONDS).taskId());

        scheduler.stop();
        thread.join(Duration.ofSeconds(5));
        assertFalse(thread.isAlive());
    }

    @Test
    void idlePollPicksUpWorkWithoutAWakeUp() throws InterruptedException {
        BlockingQueue<ClaimedRun> started = new LinkedBlockingQueue<>();
        Scheduler scheduler = new Scheduler(db, 1, new Signal(), clock, started::add, Duration.ofMillis(100));
        Thread thread = Thread.ofVirtual().start(scheduler);
        assertNull(started.poll(200, TimeUnit.MILLISECONDS));

        long id = queue();

        assertEquals(id, started.poll(5, TimeUnit.SECONDS).taskId());
        scheduler.stop();
        thread.join(Duration.ofSeconds(5));
    }

    private long queue() {
        return db.transactionReturning(tx -> {
            long id = Tasks.insert(tx, new Tasks.NewTask("alm", "t", "t", new Requester("telegram:1", "Bold"),
                    "telegram:-1/" + UUID.randomUUID(), "telegram:-1", UUID.randomUUID(), "main", Priority.NORMAL), Phase.PLANNING, clock.instant());
            Runs.insert(tx, new Runs.NewRun(id, 1, RunKind.PLAN, "t", new Requester("telegram:1", "Bold")), clock.instant());
            clock.advance(Duration.ofSeconds(1));
            return id;
        });
    }
}
