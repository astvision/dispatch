package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.Coordinator;
import dispatch.core.Groups;
import dispatch.core.Job;
import dispatch.core.JobEvents;
import dispatch.core.JobResult;
import dispatch.core.Projects;
import dispatch.core.RunTransitions;
import dispatch.core.TaskService;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.store.Workers;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import dispatch.ui.ApiException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The team machine's half of the protocol, with a fake clock and no HTTP: a job waits for one of its requester's
 * computers, that computer holds a lease it renews with progress, and whatever ends the lease ends the run exactly once.
 */
class RemoteWorkersTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");
    private static final Config.Project ALM = new Config.Project("alm", null, "git@github.com:acme/alm.git", null, "main",
            "claude-code", null, null, List.of(), null, null, null);
    private static final String PLAN_JSON = new Plan("The login times out", List.of("AUTH_TIMEOUT_SECONDS is 5"),
            List.of("Raise the timeout"), List.of(), List.of()).toJson();

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private ActiveRuns activeRuns;
    private TaskService tasks;
    private WorkerKeys keys;
    private RemoteWorkers remote;
    private final TestClock clock = new TestClock(Instant.parse("2026-09-23T10:00:00Z"));

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        activeRuns = new ActiveRuns();
        Groups groups = new Groups(List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm"))));
        tasks = new TaskService(groups, new Projects(List.of(ALM), project -> Optional.empty()), activeRuns, clock,
                () -> { }, () -> { });
        keys = new WorkerKeys(db, clock);
        // A short long-poll so "no computer will ever match" assertions resolve in milliseconds, not the protocol's
        // 25 s; a short tick so a test racing the give-up/lease-watchdog poll doesn't need up to 200 ms per attempt.
        remote = new RemoteWorkers(db, clock, () -> { }, Duration.ofMillis(50), Duration.ofMillis(2));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aJobGoesOnlyToItsRequestersOwnComputer() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        Workers.Paired ann = pair(BOLD, "ann-laptop");
        Workers.Paired bobs = pair(ALI, "bob-laptop");
        Thread run = coordinate();

        assertTrue(remote.next(bobs).isEmpty(), "Ali's computer never sees Bold's work");
        Job job = remote.next(ann).orElseThrow();

        assertEquals(id, job.taskId());
        assertTrue(job.prompt().contains("Fix the login timeout"), "the prompt arrives finished: " + job.prompt());
        remote.result(ann, id, 1, JobResult.succeeded(agentResult()));
        assertTrue(run.join(Duration.ofSeconds(10)), "the coordinator thread should have finished");
        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals(ann.id(), Long.parseLong(row("SELECT worker_id FROM task WHERE id = ?", id).get("worker_id")),
                "the task now belongs to that computer");
    }

    @Test
    void aTaskGoesBackToTheComputerThatHoldsItsWorktree() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        Workers.Paired first = pair(BOLD, "ann-laptop");
        Workers.Paired second = pair(BOLD, "ann-desktop");
        Thread run = coordinate();
        remote.next(first).orElseThrow();
        remote.result(first, id, 1, JobResult.succeeded(agentResult()));
        assertTrue(run.join(Duration.ofSeconds(10)), "the coordinator thread should have finished");

        approveAndQueueExecution(id);
        Thread execution = coordinate();

        assertTrue(remote.next(second).isEmpty(), "the other computer has neither the worktree nor the session");
        assertEquals(id, remote.next(first).orElseThrow().taskId());
        remote.result(first, id, 2, JobResult.cancelled(null));
        assertTrue(execution.join(Duration.ofSeconds(10)), "the coordinator thread should have finished");
    }

    @Test
    void sixtySecondsWithoutProgressEndsTheRunAsInterrupted() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        Workers.Paired ann = pair(BOLD, "ann-laptop");
        Thread run = coordinate();
        remote.next(ann).orElseThrow();

        clock.advance(RemoteWorkers.LEASE.plusSeconds(1));
        assertTrue(run.join(Duration.ofSeconds(10)), "the coordinator thread should have finished");

        Map<String, String> task = row("SELECT phase, failure_reason FROM task WHERE id = ?", id);
        assertEquals("FAILED", task.get("phase"));
        assertEquals("INTERRUPTED", task.get("failure_reason"));
        assertEquals("FAILED", row("SELECT status FROM run WHERE task_id = ?", id).get("status"));
    }

    @Test
    void aResultAfterTheLeaseExpiredIsRefusedWithConflict() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        Workers.Paired ann = pair(BOLD, "ann-laptop");
        Thread run = coordinate();
        remote.next(ann).orElseThrow();
        clock.advance(RemoteWorkers.LEASE.plusSeconds(1));
        assertTrue(run.join(Duration.ofSeconds(10)), "the coordinator thread should have finished");

        ApiException refused = assertThrows(ApiException.class,
                () -> remote.result(ann, id, 1, JobResult.succeeded(agentResult())));

        assertEquals(409, refused.status());
        assertEquals("lease_expired", refused.code());
        assertEquals("INTERRUPTED", row("SELECT failure_reason FROM task WHERE id = ?", id).get("failure_reason"),
                "the one transition the run got stands");
    }

    @Test
    void progressRenewsTheLeaseRecordsTheWorktreeAndFeedsStatus() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        Workers.Paired ann = pair(BOLD, "ann-laptop");
        Thread run = coordinate();
        remote.next(ann).orElseThrow();

        clock.advance(Duration.ofSeconds(50));
        assertFalse(remote.progress(ann, new RemoteWorkers.Progress(id, 1, "/home/ann/work/alm-7", "6f3030a", true, 12,
                "Bash: git status")));
        clock.advance(Duration.ofSeconds(50));
        assertFalse(remote.progress(ann, new RemoteWorkers.Progress(id, 1, null, null, false, 14, "Edit: README.md")));

        assertEquals("/home/ann/work/alm-7", row("SELECT worktree FROM task WHERE id = ?", id).get("worktree"));
        assertEquals("6f3030a", row("SELECT base_sha FROM task WHERE id = ?", id).get("base_sha"));
        Map<String, String> runRow = row("SELECT pid, agent_started_at FROM run WHERE task_id = ?", id);
        assertEquals(null, runRow.get("pid"), "the process is on Ann's computer");
        assertTrue(runRow.get("agent_started_at") != null);
        assertEquals("Edit: README.md", activeRuns.activity(id).orElseThrow().lastAction());
        assertEquals(14, activeRuns.activity(id).orElseThrow().steps());
        remote.result(ann, id, 1, JobResult.succeeded(agentResult()));
        assertTrue(run.join(Duration.ofSeconds(10)), "the coordinator thread should have finished");
    }

    @Test
    void aCancelReachesTheWorkerThroughItsNextProgress() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        Workers.Paired ann = pair(BOLD, "ann-laptop");
        Thread run = coordinate();
        remote.next(ann).orElseThrow();

        db.transaction(tx -> tasks.cancel(tx, BOLD, id, "telegram:100/9", "telegram:100"));

        assertTrue(remote.progress(ann, new RemoteWorkers.Progress(id, 1, null, null, false, 3, "Bash: ls")));
        remote.result(ann, id, 1, JobResult.cancelled(null));
        assertTrue(run.join(Duration.ofSeconds(10)), "the coordinator thread should have finished");
        assertEquals("CANCELLED", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals("CANCELLED", row("SELECT status FROM run WHERE task_id = ?", id).get("status"));
    }

    @Test
    void anotherMembersComputerCannotReportOnThisRun() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        Workers.Paired ann = pair(BOLD, "ann-laptop");
        Workers.Paired bobs = pair(ALI, "bob-laptop");
        Thread run = coordinate();
        remote.next(ann).orElseThrow();

        ApiException refused = assertThrows(ApiException.class,
                () -> remote.result(bobs, id, 1, JobResult.succeeded(agentResult())));
        ApiException refusedProgress = assertThrows(ApiException.class,
                () -> remote.progress(bobs, new RemoteWorkers.Progress(id, 1, null, null, false, 1, "Bash: ls")));

        assertEquals(403, refused.status());
        assertEquals("not_your_run", refused.code());
        assertEquals(403, refusedProgress.status());
        remote.result(ann, id, 1, JobResult.cancelled(null));
        assertTrue(run.join(Duration.ofSeconds(10)), "the coordinator thread should have finished");
    }

    @Test
    void anOfferNoComputerTakesEndsAsInterrupted() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        pair(BOLD, "ann-laptop");
        Thread run = coordinate();

        clock.advance(RemoteWorkers.LEASE.plusSeconds(1));
        assertTrue(run.join(Duration.ofSeconds(10)), "the coordinator thread should have finished");

        assertEquals("INTERRUPTED", row("SELECT failure_reason FROM task WHERE id = ?", id).get("failure_reason"));
    }

    /**
     * held()'s validation and the offer's mutation used to be two separate lock acquisitions, so a losing racer could
     * act on state a winning racer had already superseded. Repeated because winning that narrow window is a real race,
     * not a controlled one: each iteration gives both threads a fair, simultaneous start and the invariant must hold
     * regardless of who the scheduler favors, so enough repeats make the old, unsynchronized behavior fail reliably.
     */
    private static final int RACE_ITERATIONS = 30;

    @Test
    void aSecondComputerCannotTakeAJobAlreadyReported() throws Exception {
        for (int i = 0; i < RACE_ITERATIONS; i++) {
            long id = queue(BOLD, "Fix the login timeout " + i);
            Workers.Paired first = pair(BOLD, "ann-laptop-" + i);
            Workers.Paired second = pair(BOLD, "ann-desktop-" + i);
            Thread run = coordinate();
            remote.next(first).orElseThrow();

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicReference<Optional<Job>> secondAttempt = new AtomicReference<>();
            Thread resultThread = Thread.ofVirtual().start(() -> {
                ready.countDown();
                awaitLatch(go);
                remote.result(first, id, 1, JobResult.succeeded(agentResult()));
            });
            Thread nextThread = Thread.ofVirtual().start(() -> {
                ready.countDown();
                awaitLatch(go);
                try {
                    secondAttempt.set(remote.next(second));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS), "both racers should have reached the starting line");
            go.countDown();
            assertTrue(resultThread.join(Duration.ofSeconds(10)), "the result post should have finished");
            assertTrue(nextThread.join(Duration.ofSeconds(10)), "the second computer's poll should have finished");

            assertTrue(secondAttempt.get().isEmpty(), "iteration " + i
                    + ": the job was already reported; no other computer may take it");
            assertEquals(first.id(), Long.parseLong(row("SELECT worker_id FROM task WHERE id = ?", id).get("worker_id")),
                    "iteration " + i + ": the task's affinity must not be repointed at a computer holding neither"
                            + " the worktree nor the session");
            assertTrue(run.join(Duration.ofSeconds(10)), "the coordinator thread should have finished");
        }
    }

    @Test
    void twoConcurrentResultSubmissionsFromTheSameWorkerLeaveExactlyOneOutcome() throws Exception {
        for (int i = 0; i < RACE_ITERATIONS; i++) {
            long id = queue(BOLD, "Fix the login timeout " + i);
            Workers.Paired ann = pair(BOLD, "ann-laptop-" + i);
            Thread run = coordinate();
            remote.next(ann).orElseThrow();

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger refusals = new AtomicInteger();
            Thread succeed = Thread.ofVirtual().start(() -> {
                ready.countDown();
                awaitLatch(go);
                submit(() -> remote.result(ann, id, 1, JobResult.succeeded(agentResult())), successes, refusals);
            });
            Thread cancel = Thread.ofVirtual().start(() -> {
                ready.countDown();
                awaitLatch(go);
                submit(() -> remote.result(ann, id, 1, JobResult.cancelled(null)), successes, refusals);
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS), "both racers should have reached the starting line");
            go.countDown();
            assertTrue(succeed.join(Duration.ofSeconds(10)), "the succeeded post should have finished");
            assertTrue(cancel.join(Duration.ofSeconds(10)), "the cancelled post should have finished");

            assertEquals(1, successes.get(), "iteration " + i + ": exactly one of two concurrent result posts is accepted");
            assertEquals(1, refusals.get(), "iteration " + i
                    + ": the loser is told 409, not silently accepted as if it were 200");
            assertTrue(run.join(Duration.ofSeconds(10)), "the coordinator thread should have finished");
            String status = row("SELECT status FROM run WHERE task_id = ?", id).get("status");
            assertTrue(status.equals("SUCCEEDED") || status.equals("CANCELLED"),
                    "iteration " + i + ": the run ends as exactly one outcome, not a mix of both: " + status);
        }
    }

    /**
     * A progress post arriving once the run's lease has fully expired is refused and must not touch the store — the
     * same contract {@code aResultAfterTheLeaseExpiredIsRefusedWithConflict} already covers for a result.
     */
    @Test
    void aProgressAfterTheLeaseExpiredIsRefusedAndRecordsNothing() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        Workers.Paired ann = pair(BOLD, "ann-laptop");
        Thread run = coordinate();
        remote.next(ann).orElseThrow();
        clock.advance(RemoteWorkers.LEASE.plusSeconds(1));
        assertTrue(run.join(Duration.ofSeconds(10)), "the coordinator thread should have finished");

        ApiException refused = assertThrows(ApiException.class, () -> remote.progress(ann, new RemoteWorkers.Progress(id,
                1, "/home/ann/work/alm-7", "6f3030a", true, 3, "Bash: ls")));

        assertEquals(409, refused.status());
        assertEquals("lease_expired", refused.code());
        Map<String, String> task = row("SELECT worktree, base_sha FROM task WHERE id = ?", id);
        assertEquals(null, task.get("worktree"), "a refused progress post must not have recorded anything");
        assertEquals(null, task.get("base_sha"));
        assertEquals(null, row("SELECT agent_started_at FROM run WHERE task_id = ?", id).get("agent_started_at"));
    }

    /** Runs {@code action}, tallying it as accepted or, when it throws {@link ApiException}, as refused. */
    private static void submit(Runnable action, AtomicInteger accepted, AtomicInteger refused) {
        try {
            action.run();
            accepted.incrementAndGet();
        } catch (ApiException e) {
            refused.incrementAndGet();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aFailingRecordWorkerLeavesTheOfferTakeableByTheNextPoll() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        // Never actually paired: worker.id() 999999 does not exist, so Tasks.recordWorker's foreign key rejects it.
        Workers.Paired ghost = new Workers.Paired(999_999L, BOLD.ref(), "ghost", "deadbeef", clock.instant(), null);
        Workers.Paired ann = pair(BOLD, "ann-laptop");
        Thread run = coordinate();

        assertThrows(RuntimeException.class, () -> remote.next(ghost),
                "recordWorker must fail for a worker id that was never actually paired");

        Job job = remote.next(ann).orElseThrow();
        assertEquals(id, job.taskId(), "the failed reservation must not leave the offer stuck unmatchable");
        remote.result(ann, id, 1, JobResult.succeeded(agentResult()));
        assertTrue(run.join(Duration.ofSeconds(10)), "the coordinator thread should have finished");
        assertEquals(ann.id(), Long.parseLong(row("SELECT worker_id FROM task WHERE id = ?", id).get("worker_id")));
    }

    @Test
    void twoConcurrentFirstProgressPostsRecordTheWorktreeAndAgentStartOnce() throws Exception {
        for (int i = 0; i < RACE_ITERATIONS; i++) {
            long id = queue(BOLD, "Fix the login timeout " + i);
            Workers.Paired ann = pair(BOLD, "ann-laptop-" + i);
            AtomicInteger worktreeCalls = new AtomicInteger();
            AtomicInteger agentCalls = new AtomicInteger();
            ActiveRuns.ActiveRun control = activeRuns.register(id, 1);
            JobEvents countingEvents = new JobEvents() {
                @Override
                public void worktreeCreated(String worktree, String baseSha) {
                    worktreeCalls.incrementAndGet();
                }

                @Override
                public void agentStarted(Long pid, Instant processStart) {
                    agentCalls.incrementAndGet();
                }
            };
            Thread runThread = Thread.ofVirtual().start(() -> remote.run(minimalJob(id), countingEvents, control));
            awaitOffer();
            remote.next(ann).orElseThrow();

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Runnable post = () -> {
                ready.countDown();
                awaitLatch(go);
                remote.progress(ann, new RemoteWorkers.Progress(id, 1, "/home/ann/work/alm-7", "6f3030a", true, 1,
                        "Bash: git status"));
            };
            Thread first = Thread.ofVirtual().start(post);
            Thread second = Thread.ofVirtual().start(post);
            assertTrue(ready.await(10, TimeUnit.SECONDS), "both posts should have reached the starting line");
            go.countDown();
            assertTrue(first.join(Duration.ofSeconds(10)), "the first progress post should have finished");
            assertTrue(second.join(Duration.ofSeconds(10)), "the second progress post should have finished");

            assertEquals(1, worktreeCalls.get(), "iteration " + i + ": the worktree is recorded once even when two"
                    + " progress posts race");
            assertEquals(1, agentCalls.get(), "iteration " + i + ": the agent start is recorded once even when two"
                    + " progress posts race");

            remote.result(ann, id, 1, JobResult.cancelled(null));
            assertTrue(runThread.join(Duration.ofSeconds(10)), "the run thread should have finished");
        }
    }

    /** A minimal Job for tests that drive {@link RemoteWorkers#run} directly, without the full Coordinator. */
    private Job minimalJob(long taskId) {
        Job.Project project = new Job.Project(ALM.name(), ALM.repo(), null, ALM.baseBranch(), ALM.agent(), List.of());
        return new Job(taskId, 1, RunKind.PLAN, project, "main", null, null, null, null, false, "prompt", null, null,
                0L, null, List.of(), "subject", List.of(), null);
    }

    private Thread coordinate() {
        Coordinator coordinator = new Coordinator(db, new Projects(List.of(ALM), project -> Optional.empty()),
                new RunTransitions(db, clock, () -> { }), activeRuns,
                project -> new Config.RunLimits(Duration.ofMinutes(30), new BigDecimal("2")),
                project -> new Config.RunLimits(Duration.ofMinutes(60), new BigDecimal("10")), remote, () -> { });
        ClaimedRun claimed = db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        Thread thread = Thread.ofVirtual().start(() -> coordinator.execute(claimed));
        awaitOffer();
        return thread;
    }

    /** The Coordinator reads the store before it offers the job; the test must not poll before that happened. */
    private void awaitOffer() {
        Instant deadline = Instant.now().plusSeconds(10);
        while (!remote.hasOffers()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("the coordinator never offered the job");
            }
            Thread.onSpinWait();
        }
    }

    private Workers.Paired pair(Requester member, String name) {
        long id = keys.pair(keys.newCode(member), name).orElseThrow().workerId();
        return db.transactionReturning(tx -> Workers.find(tx, id)).orElseThrow();
    }

    private long queue(Requester who, String description) {
        db.transaction(tx -> tasks.create(tx, who, "alm", description, Priority.NORMAL, who.ref() + "/" + System.nanoTime()));
        return Long.parseLong(row("SELECT max(id) AS id FROM task").get("id"));
    }

    private void approveAndQueueExecution(long taskId) {
        db.transaction(tx -> tasks.approve(tx, BOLD, taskId, 1));
    }

    private static AgentResult agentResult() {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "fake-session", PLAN_JSON, "done", new BigDecimal("0.01"), 2,
                List.of(), null, "claude-sonnet-5", null);
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
