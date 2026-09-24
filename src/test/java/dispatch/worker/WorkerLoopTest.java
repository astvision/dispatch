package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.core.Job;
import dispatch.core.JobResult;
import dispatch.domain.FailureReason;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** The member's side: it takes the job, runs the real JobRunner and reports back. */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake claude and gh CLIs are POSIX shell scripts")
class WorkerLoopTest extends WorkerApiFixture {

    @Test
    void theLoopRunsAJobInItsOwnCloneAndReportsTheResult() throws Exception {
        startLoop("ann-laptop", repos.repo("alm"));

        offer(planJob());

        JobResult result = awaitResult();
        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome());
        assertTrue(result.agent().structuredOutput().contains("understanding"), result.agent().structuredOutput());
        assertTrue(worktreeOf("ann-laptop", 7).resolve("fake-claude.args").toFile().exists(),
                "the worktree is on this computer, not on the team machine");
    }

    @Test
    void aProjectThisComputerDoesNotHaveFailsWithWhatToDo() throws Exception {
        startLoopWithoutProjects("ann-laptop");

        offer(planJob());

        JobResult result = awaitResult();
        assertEquals(FailureReason.SETUP, result.failureReason());
        assertEquals("project alm is not set up on your computer: run dispatch worker init", result.failureDetail());
    }

    @Test
    void progressReachesTheTeamMachineWhileTheAgentRuns() throws Exception {
        startLoop("ann-laptop", repos.repo("alm"));

        offer(planJob());
        awaitResult();

        assertEquals("/", worktreeOf("ann-laptop", 7).toString().substring(0, 1));
        assertTrue(recordedWorktree.get().startsWith(workerStateDir("ann-laptop").toString()),
                "the team machine learned this computer's path: " + recordedWorktree.get());
        assertTrue(agentStartedWithoutAPid.get(), "the pid stays on this computer");
    }

    @Test
    void aCancelFromTheTeamMachineStopsTheAgent() throws Exception {
        startLoop("ann-laptop", repos.repo("alm"));
        offer(planJob("SCENARIO:sleep"));
        awaitAgentStarted();

        control.stop(dispatch.core.ActiveRuns.StopReason.CANCELLED);

        assertEquals(JobResult.Outcome.CANCELLED, awaitResult().outcome());
    }

    @Test
    void aResultPostThatFailsOnceIsRetriedAndDelivered() throws Exception {
        AtomicInteger resultCalls = new AtomicInteger();
        startLoop("ann-laptop", repos.repo("alm"), (http, team, key) -> new WorkerClient(http, team, key) {
            @Override
            public void result(long taskId, int seq, JobResult result) {
                if (resultCalls.getAndIncrement() == 0) {
                    throw new IllegalStateException("simulated: the team machine did not answer this time");
                }
                super.result(taskId, seq, result);
            }
        });

        offer(planJob());

        JobResult result = awaitResult();
        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome(), "the retried post must still land");
        assertEquals(2, resultCalls.get(), "one failed attempt, then the one that delivered");
    }

    @Test
    void aRevokedKeyStopsTheRunInsteadOfLettingItFinishAndStopsTheLoop() throws Exception {
        AtomicInteger nextCalls = new AtomicInteger();
        AtomicInteger progressCalls = new AtomicInteger();
        AtomicBoolean revoked = new AtomicBoolean();
        startLoop("ann-laptop", repos.repo("alm"), (http, team, key) -> new WorkerClient(http, team, key) {
            @Override
            public Optional<Job> next(Readiness readiness) {
                nextCalls.incrementAndGet();
                return super.next(readiness);
            }

            @Override
            public boolean progress(RemoteWorkers.Progress progress) {
                // The first two posts are JobRunner's own worktreeCreated/agentStarted events, not the ticker (see
                // JobRunner.run): revoking on those would only hit their own best-effort catch, never WorkerLoop.tick.
                if (progressCalls.incrementAndGet() > 2 && revoked.compareAndSet(false, true)) {
                    throw new WorkerClient.RevokedException("simulated: this key was revoked mid-run");
                }
                return super.progress(progress);
            }
        });

        offer(planJob("SCENARIO:sleep"));
        awaitAgentStarted();

        JobResult result = awaitResult();

        assertEquals(JobResult.Outcome.FAILED, result.outcome(), "a revoked key must stop the run, not let it finish");
        assertEquals(FailureReason.INTERRUPTED, result.failureReason());
        int callsOnceRevoked = nextCalls.get();
        // No sleep-and-hope: the loop itself sets `stopped` before this run's carry() even unregisters (see
        // WorkerLoop.stop()), so by the time awaitResult() above returned, a further next() would already be
        // impossible. This waits past one more tick interval purely to make a regression's extra poll visible.
        Thread.sleep(TEST_PROGRESS.multipliedBy(3).toMillis());
        assertEquals(callsOnceRevoked, nextCalls.get(), "the poll loop must also stop once the key is revoked");
    }

    @Test
    void aLeaseExpiredResultIsNeverRetried() throws Exception {
        AtomicInteger resultCalls = new AtomicInteger();
        startLoop("ann-laptop", repos.repo("alm"), (http, team, key) -> new WorkerClient(http, team, key) {
            @Override
            public void result(long taskId, int seq, JobResult result) {
                resultCalls.incrementAndGet();
                throw new WorkerClient.LeaseExpiredException("simulated: this run's lease already expired");
            }
        });

        offer(planJob());
        // No retry means this settles almost at once; a wrongly-retried post would still be asleep in its first 2 s
        // backoff when this checks, so waiting past it is enough to tell the two apart without polling for an absence.
        Thread.sleep(Duration.ofSeconds(3).toMillis());

        assertEquals(1, resultCalls.get(), "a 409 must not be retried: the lease is already gone, not transient");
    }
}
