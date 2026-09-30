package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import dispatch.core.Job;
import dispatch.core.JobResult;
import dispatch.domain.FailureReason;
import dispatch.testing.SqlRows;
import java.nio.file.Files;
import java.time.Duration;
import dispatch.domain.RunKind;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
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
    void workerKeepsTheLoopSettingsOfTheJob() throws Exception {
        WorkerLoop loop = idleLoop("ann-laptop", repos.repo("alm"));

        Job ran = loop.withLocalClone(executeJob(new Job.Project("alm", "git@github.com:acme/alm.git", null, "main",
                "claude-code", List.of(), "./mvnw -q test", true), "Review it")).orElseThrow();

        assertEquals("./mvnw -q test", ran.project().test());
        assertTrue(ran.project().loopOn());
        assertEquals("Review it", ran.reviewPrompt());
        assertEquals(repos.repo("alm").toString(), ran.project().path(), "the member's own clone");

        Job older = loop.withLocalClone(executeJob(new Job.Project("alm", "git@github.com:acme/alm.git", null, "main",
                "claude-code", List.of()), null)).orElseThrow();
        assertFalse(older.project().loopOn(), "a job without the loop stays off");
        assertNull(older.reviewPrompt());
    }

    @Test
    void workerKeepsTheBranchsExpectedHead() throws Exception {
        WorkerLoop loop = idleLoop("ann-laptop", repos.repo("alm"));
        Job.Project project = new Job.Project("alm", "git@github.com:acme/alm.git", null, "main", "claude-code", List.of());
        Job sent = executeJob(project, null);
        Job guarded = new Job(sent.taskId(), sent.seq(), sent.kind(), project, sent.baseBranch(), sent.baseSha(), sent.worktree(),
                sent.prUrl(), sent.sessionId(), sent.resume(), sent.prompt(), sent.model(), sent.effort(), sent.timeoutMillis(),
                sent.budgetUsd(), sent.attachments(), sent.commitSubject(), sent.commitTrailers(), sent.deliverySummary(),
                sent.branch(), sent.reviewPrompt(), "9c1e2d4");

        assertEquals("9c1e2d4", loop.withLocalClone(guarded).orElseThrow().expectedHead(), "dropped, the guard would check nothing");
    }

    private static Job executeJob(Job.Project project, String reviewPrompt) {
        return new Job(TASK_ID, 2, RunKind.EXECUTE, project, "main", "6f3030a", "/w/7", null, UUID.randomUUID(), false,
                "Implement", null, null, 1000L, new BigDecimal("2"), List.of(), "dispatch #7: x", List.of(), null, null,
                reviewPrompt);
    }

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
    void theLoopTellsTheTeamMachineHowManyRunsItTakesAtOnce() throws Exception {
        startLoop("ann-laptop", repos.repo("alm"), 2);

        offer(planJob());
        awaitResult();

        assertEquals("2", SqlRows.single(dir.resolve("dispatch.db"), "SELECT max_runs FROM worker WHERE name = ?",
                "ann-laptop").get("max_runs"), "worker.yaml's maxConcurrentRuns rides on every poll");
    }

    @Test
    void thisComputersOwnModelAndEffortReachClaudeCode() throws Exception {
        startLoop("ann-laptop", new WorkerConfig.Project(repos.repo("alm").toString(), "opus", "max"));

        offer(planJob());

        assertEquals(JobResult.Outcome.SUCCEEDED, awaitResult().outcome());
        List<String> args = Files.readAllLines(worktreeOf("ann-laptop", 7).resolve("fake-claude.args"));
        assertEquals("opus", args.get(args.indexOf("--model") + 1), args.toString());
        assertEquals("max", args.get(args.indexOf("--effort") + 1), args.toString());
    }

    /**
     * A computer's own model and effort are Claude Code's, the only ones worker init offers: a project the team has moved
     * to codex since must run on the team's codex settings, never on {@code -m opus}.
     */
    @Test
    void thisComputersOwnModelAndEffortNeverReachAnotherAgent() throws Exception {
        startLoop("ann-laptop", new WorkerConfig.Project(repos.repo("alm").toString(), "opus", "max"));

        offer(planJob("Plan this: fix the login timeout", "codex", "gpt-5-codex", "high"));

        assertEquals(JobResult.Outcome.SUCCEEDED, awaitResult().outcome());
        List<String> args = Files.readAllLines(worktreeOf("ann-laptop", 7).resolve("fake-codex.args"));
        assertEquals("gpt-5-codex", args.get(args.indexOf("-m") + 1), args.toString());
        assertTrue(args.contains("model_reasoning_effort=\"high\""), args.toString());
        assertFalse(args.contains("opus"), args.toString());
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
            public Optional<Job> next(Readiness readiness, int maxConcurrentRuns) {
                nextCalls.incrementAndGet();
                return super.next(readiness, maxConcurrentRuns);
            }

            @Override
            public boolean progress(RemoteWorkers.Progress progress) {
                // The first three posts are JobRunner's own worktreeCreated, plan step started and agentStarted events,
                // not the ticker (see JobRunner.run): revoking on those would only hit their own best-effort catch, never
                // WorkerLoop.tick.
                if (progressCalls.incrementAndGet() > 3 && revoked.compareAndSet(false, true)) {
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
