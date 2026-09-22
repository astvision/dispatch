package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.domain.ClaimedRun;
import dispatch.domain.Phase;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.store.Workers;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import dispatch.worker.WorkerKeys;
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

    private Path dbFile;
    private Database db;
    private final TestClock clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));

    @BeforeEach
    void open() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
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

    @Test
    void inTeamModeARunWaitsUntilItsRequestersComputerIsConnected() {
        long id = queuedTaskOf("telegram:100");

        assertTrue(db.transactionReturning(tx -> Runs.claimNext(tx, 2, clock.instant(),
                clock.instant().minus(Workers.SEEN_WITHIN))).isEmpty(), "nobody is there to run it");

        long worker = pair("telegram:100", "ann-laptop");
        db.transaction(tx -> Workers.touch(tx, worker, clock.instant()));

        assertEquals(id, db.transactionReturning(tx -> Runs.claimNext(tx, 2, clock.instant(),
                clock.instant().minus(Workers.SEEN_WITHIN))).orElseThrow().taskId());
    }

    @Test
    void aTaskWithOnlyASilentComputerIsNotClaimed() {
        long id = queuedTaskOf("telegram:100");
        long worker = pair("telegram:100", "ann-laptop");
        db.transaction(tx -> Workers.touch(tx, worker, clock.instant().minus(Workers.SEEN_WITHIN).minusSeconds(1)));

        assertTrue(db.transactionReturning(tx -> Runs.claimNext(tx, 2, clock.instant(),
                clock.instant().minus(Workers.SEEN_WITHIN))).isEmpty(), "silent for over a minute is gone");
        assertEquals("QUEUED", SqlRows.single(dbFile, "SELECT status FROM run WHERE task_id = ?", id).get("status"));
    }

    @Test
    void aRevokedWorkersUnfinishedPinnedTaskBecomesClaimableByTheMembersOtherLiveWorker() {
        long id = queuedTaskOf("telegram:100");
        long revoked = pair("telegram:100", "ann-laptop");
        long other = pair("telegram:100", "ann-desktop");
        db.transaction(tx -> Tasks.recordWorker(tx, id, revoked, clock.instant()));
        db.transaction(tx -> Workers.touch(tx, other, clock.instant()));

        assertTrue(db.transactionReturning(tx -> Runs.claimNext(tx, 2, clock.instant(),
                clock.instant().minus(Workers.SEEN_WITHIN))).isEmpty(), "still pinned to the laptop, which is silent");

        new WorkerKeys(db, clock).revoke(revoked, "telegram:100", false);

        assertEquals(id, db.transactionReturning(tx -> Runs.claimNext(tx, 2, clock.instant(),
                clock.instant().minus(Workers.SEEN_WITHIN))).orElseThrow().taskId(), "revoking the laptop frees the pin");
    }

    @Test
    void aTaskWithAWorktreeGoesBackToItsOwnComputerOrWaits() {
        long id = queuedTaskOf("telegram:100");
        long first = pair("telegram:100", "ann-laptop");
        long second = pair("telegram:100", "ann-desktop");
        db.transaction(tx -> Tasks.recordWorker(tx, id, first, clock.instant()));
        db.transaction(tx -> Workers.touch(tx, second, clock.instant()));

        assertTrue(db.transactionReturning(tx -> Runs.claimNext(tx, 2, clock.instant(),
                clock.instant().minus(Workers.SEEN_WITHIN))).isEmpty(), "the other computer has no worktree for it");

        db.transaction(tx -> Workers.touch(tx, first, clock.instant()));
        assertEquals(id, db.transactionReturning(tx -> Runs.claimNext(tx, 2, clock.instant(),
                clock.instant().minus(Workers.SEEN_WITHIN))).orElseThrow().taskId());
    }

    @Test
    void personalModeClaimsWithoutAskingAboutComputers() {
        long id = queuedTaskOf("telegram:100");

        assertEquals(id, db.transactionReturning(tx -> Runs.claimNext(tx, 2, clock.instant(), null)).orElseThrow().taskId());
    }

    private long queuedTaskOf(String requesterRef) {
        return db.transactionReturning(tx -> {
            Requester requester = new Requester(requesterRef, "Ann");
            long id = Tasks.insert(tx, new Tasks.NewTask("alm", "t", "t", requester, "telegram:-1/" + UUID.randomUUID(),
                    "telegram:-1", UUID.randomUUID(), "main", Priority.NORMAL), Phase.PLANNING, clock.instant());
            Runs.insert(tx, new Runs.NewRun(id, 1, RunKind.PLAN, dispatch.domain.RunCause.TASK, "t", requester), clock.instant());
            return id;
        });
    }

    private long pair(String memberRef, String name) {
        WorkerKeys keys = new WorkerKeys(db, clock);
        String code = keys.newCode(new Requester(memberRef, "Ann"));
        return keys.pair(code, name).orElseThrow().workerId();
    }

    private long queue() {
        return db.transactionReturning(tx -> {
            long id = Tasks.insert(tx, new Tasks.NewTask("alm", "t", "t", new Requester("telegram:1", "Bold"),
                    "telegram:-1/" + UUID.randomUUID(), "telegram:-1", UUID.randomUUID(), "main", Priority.NORMAL), Phase.PLANNING, clock.instant());
            Runs.insert(tx, new Runs.NewRun(id, 1, RunKind.PLAN, dispatch.domain.RunCause.TASK, "t", new Requester("telegram:1", "Bold")), clock.instant());
            clock.advance(Duration.ofSeconds(1));
            return id;
        });
    }
}
