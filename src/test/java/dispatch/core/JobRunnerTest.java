package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.Redactor;
import dispatch.agent.Agent;
import dispatch.agent.AgentActivity;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.RunHandle;
import dispatch.agent.RunRequest;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.domain.FailureReason;
import dispatch.domain.RunKind;
import dispatch.testing.FakeClaude;
import dispatch.testing.FakeGh;
import dispatch.testing.GitFixture;
import dispatch.workspace.Delivery;
import dispatch.workspace.Gh;
import dispatch.workspace.Git;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The machine half of a run, with real git worktrees and the fake claude script. The runner is built without a
 * Database on purpose: everything it needs arrives in the Job, which is what W-3 puts HTTP in front of.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake claude and gh CLIs are POSIX shell scripts")
class JobRunnerTest {

    private static final long TASK = 7;
    private static final UUID SESSION = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path dir;

    private GitFixture repos;
    private Workspaces workspaces;
    private Delivery delivery;
    private JobRunner runner;
    /** What the verify loop's tests start: each kind answered here is a canned result, the rest go to fake claude. */
    private final Map<RunKind, AgentResult> answers = new EnumMap<>(RunKind.class);
    private final List<RunKind> agentKindsStarted = new CopyOnWriteArrayList<>();
    /** Every request the scripted agent was started with, in order. */
    private final List<RunRequest> loopRequests = new CopyOnWriteArrayList<>();
    /** Whether {@link #executeWithLoop} sends a job whose skills are on, as a team machine does for a Claude Code project. */
    private boolean skills;
    /** When set, a fix (a resumed EXECUTE) runs until it is cancelled or its thread interrupted. */
    private volatile boolean fixesHang;
    private final java.util.concurrent.CountDownLatch fixStarted = new java.util.concurrent.CountDownLatch(1);
    /** The prompts the verify loop's reviewers were started with. */
    private final List<String> reviewPrompts = new CopyOnWriteArrayList<>();
    /** Runs in the task's worktree between the planning run and the execution, as an earlier run's delivery would. */
    private java.util.function.Consumer<Path> beforeExecute = worktree -> { };
    /** Runs in the task's worktree while the scripted EXECUTE agent works, as another task's agent could at that moment. */
    private java.util.function.Consumer<Path> duringExecute = worktree -> { };
    /** Whether {@link #executeWithLoop} sends the planned commit as the branch's expected head, as a guarded team machine does. */
    private boolean guarded;
    private Recorder events;
    /** What the execution run with the verify loop recorded. */
    private final Recorder loopEvents = new Recorder();
    private ActiveRuns.ActiveRun control;
    private Agent claude;

    @BeforeEach
    void setUp() throws IOException {
        repos = GitFixture.create(dir, "alm");
        Git git = new Git("git", null, Duration.ofSeconds(30));
        workspaces = new Workspaces(repos.stateDir, git);
        delivery = new Delivery(git, new Gh(FakeGh.install(dir.resolve("gh")).toString(), null, Duration.ofSeconds(30)),
                "Dispatch (backend)", "dispatch-backend@example.com");
        ClaudeCodeAgent agent = new ClaudeCodeAgent(FakeClaude.install(Files.createDirectories(dir.resolve("bin"))).toString(),
                FakeClaude.environment(), Duration.ofSeconds(1));
        runner = new JobRunner(workspaces, delivery, Map.of("claude-code", agent), Redactor.patternsOnly(),
                (fileRef, target) -> {
                    throw new IllegalStateException("file " + fileRef + " is gone");
                });
        claude = agent;
        events = new Recorder();
        control = new ActiveRuns().register(TASK, 1);
    }

    @Test
    void aPlanJobRunsTheAgentInANewWorktreeAndReportsWhatTheStoreMustRecord() throws Exception {
        Instant before = Instant.now().minusSeconds(5); // OS process start times can be truncated to the second

        JobResult result = runner.run(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null), events, control);

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome());
        assertTrue(result.agent().structuredOutput().contains("understanding"), result.agent().structuredOutput());
        Path worktree = repos.stateDir.resolve("worktrees/" + TASK);
        assertEquals(worktree.toString(), events.worktree, "the Coordinator must record the worktree while the run goes on");
        assertEquals(GitFixture.sh(repos.seed, "git", "rev-parse", "HEAD"), events.baseSha);
        assertTrue(events.pid > 0, "the agent's process is recorded for orphan detection");
        assertFalse(events.processStart.isBefore(before), "the pid's own start time, which Recovery matches against the live process");
        assertFalse(events.processStart.isAfter(Instant.now()), "the pid's own start time, which Recovery matches against the live process");
        assertEquals("Plan this: fix the login timeout", Files.readString(worktree.resolve("fake-claude.prompt")));
        assertEquals(SESSION.toString(), valueAfter(Files.readAllLines(worktree.resolve("fake-claude.args")), "--session-id"));
        assertTrue(Files.exists(repos.stateDir.resolve("runs/" + TASK + "/1.jsonl")));
    }

    @Test
    void anExecuteJobCopiesTheProjectsLocalFilesIntoTheExistingWorktree() throws Exception {
        Files.writeString(repos.repo("alm").resolve(".env"), "DB_PASSWORD=local-only\n");
        runner.run(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null), events, control);
        Path worktree = Path.of(events.worktree);
        assertFalse(Files.exists(worktree.resolve(".env")), "planning never sees local secrets");

        runner.run(job(RunKind.EXECUTE, 2, "Implement the approved plan", events.worktree, events.baseSha, List.of(".env")),
                new Recorder(), new ActiveRuns().register(TASK, 2));

        assertEquals("DB_PASSWORD=local-only\n", Files.readString(worktree.resolve(".env")));
        assertTrue(Files.readString(worktree.resolve("fake-claude.prompt")).contains("Implement the approved plan"));
    }

    @Test
    void aJobWhoseTaskHasNoWorktreeFailsAsSetupWithoutStartingAnAgent() {
        JobResult result = runner.run(job(RunKind.EXECUTE, 2, "Implement the approved plan", null, null, null), events, control);

        assertEquals(JobResult.Outcome.FAILED, result.outcome());
        assertEquals(FailureReason.SETUP, result.failureReason());
        assertEquals("the task has no worktree", result.failureDetail());
        assertNull(result.agent());
        assertEquals(0, events.pid);
    }

    /** A member's computer without the project's agent (ADR 0026) fails the run with what to install, instead of crashing. */
    @Test
    void aProjectWhoseAgentIsNotConfiguredHereFailsAsAgentWithWhatToDo() {
        Job job = new Job(TASK, 1, RunKind.PLAN, new Job.Project("alm", repos.origin.toString(), null, "main", "codex", List.of()),
                "main", null, null, null, SESSION, false, "Plan this: fix the login timeout", null, null,
                Duration.ofSeconds(30).toMillis(), new BigDecimal("2"), List.of(), "dispatch #7: Fix the login timeout",
                List.of("Requested-by: Bold"), null);

        JobResult result = runner.run(job, events, control);

        assertEquals(JobResult.Outcome.FAILED, result.outcome());
        assertEquals(FailureReason.AGENT, result.failureReason());
        assertEquals("the project runs on codex, which is not configured on this computer; add it under agents "
                + "(or codexCommand in worker.yaml) and restart", result.failureDetail());
        assertEquals(0, events.pid, "no agent started");
    }

    @Test
    void anAttachmentThatCannotBeDownloadedFailsTheJobBeforeItsAgent() throws Exception {
        Job job = new Job(TASK, 1, RunKind.PLAN, project(List.of()), "main", null, null, null, SESSION, false,
                "Plan this: fix the login timeout", null, null, Duration.ofSeconds(30).toMillis(), new BigDecimal("2"),
                List.of(new dispatch.domain.Attachment("gone-id", "1-photo.jpg", 3L)), "dispatch #7: Fix the login timeout",
                List.of("Requested-by: Bold"), null);

        JobResult result = runner.run(job, events, control);

        assertEquals(FailureReason.SETUP, result.failureReason());
        assertEquals("cannot download 1-photo.jpg: file gone-id is gone", result.failureDetail());
        assertFalse(Files.exists(repos.stateDir.resolve("worktrees/" + TASK + "/fake-claude.args")));
    }

    @Test
    void aStoppedJobNeverTouchesGitOrStartsAnAgent() {
        control.stop(ActiveRuns.StopReason.INTERRUPTED);

        JobResult result = runner.run(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null), events, control);

        assertEquals(FailureReason.INTERRUPTED, result.failureReason());
        assertEquals("Dispatch stopped while the run was active", result.failureDetail());
        assertNull(events.worktree);
        assertFalse(Files.exists(repos.stateDir.resolve("worktrees/" + TASK)));
    }

    @Test
    void aCancelThroughTheControlHandleStopsTheAgentAndItsChildren() throws Exception {
        Job job = job(RunKind.PLAN, 1, "SCENARIO:sleep", null, null, null);
        AtomicReference<JobResult> result = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> result.set(runner.run(job, events, control)));
        Path child = repos.stateDir.resolve("worktrees/" + TASK + "/fake-claude.child");
        awaitFile(child);

        control.stop(ActiveRuns.StopReason.CANCELLED);
        worker.join(Duration.ofSeconds(15));

        assertFalse(worker.isAlive());
        assertEquals(JobResult.Outcome.CANCELLED, result.get().outcome());
        assertTrue(FakeClaude.childEnds(Long.parseLong(Files.readString(child).strip())), "the cancelled run's children must be gone");
    }

    @Test
    void aTimeoutStopsTheAgentAndSaysHowLongItRan() throws Exception {
        Job job = new Job(TASK, 1, RunKind.PLAN, project(List.of()), "main", null, null, null, SESSION, false, "SCENARIO:sleep",
                null, null, 700L, new BigDecimal("2"), List.of(), "dispatch #7: Fix the login timeout",
                List.of("Requested-by: Bold"), null);

        JobResult result = runner.run(job, events, control);

        assertEquals(FailureReason.TIMEOUT, result.failureReason());
        assertEquals("stopped after 700ms", result.failureDetail());
    }

    @Test
    void anExecuteJobDeliversTheAgentsChangesWithTheJobsSubjectAndTrailers() throws Exception {
        runner.run(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null), events, control);

        JobResult result = runner.run(job(RunKind.EXECUTE, 2, "Implement the approved plan", events.worktree, events.baseSha, null),
                new Recorder(), new ActiveRuns().register(TASK, 2));

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome());
        assertEquals(List.of("README.md"), result.files());
        assertEquals(FakeGh.PR_URL, result.prUrl());
        String branch = "refs/heads/dispatch/" + TASK;
        assertEquals("dispatch #7: Fix the login timeout", origin("log", "-1", "--format=%s", branch));
        String body = origin("log", "-1", "--format=%b", branch);
        assertTrue(body.contains("AUTH_TIMEOUT_SECONDS"), body);
        assertTrue(body.endsWith("Requested-by: Bold\nApproved-by: Bold"), body);
    }

    @Test
    void aDeliveryJobCommitsTheFailedRunsSummaryWithoutStartingAnAgent() throws Exception {
        runner.run(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null), events, control);
        Path worktree = Path.of(events.worktree);
        Files.writeString(worktree.resolve("README.md"), "v2\n");
        Files.delete(worktree.resolve("fake-claude.prompt"));

        Job job = new Job(TASK, 3, RunKind.DELIVER, project(List.of()), "main", events.baseSha, events.worktree, null, null,
                false, null, null, null, 0L, null, List.of(), "dispatch #7: Fix the login timeout",
                List.of("Requested-by: Bold", "Approved-by: Bold"), "Raised AUTH_TIMEOUT_SECONDS to 30");
        JobResult result = runner.run(job, new Recorder(), new ActiveRuns().register(TASK, 3));

        assertEquals(List.of("README.md"), result.files());
        assertEquals(FakeGh.PR_URL, result.prUrl());
        assertNull(result.agent(), "a delivery run has no agent result");
        assertFalse(Files.exists(worktree.resolve("fake-claude.prompt")), "a delivery run never starts the agent");
        assertEquals("Raised AUTH_TIMEOUT_SECONDS to 30",
                origin("log", "-1", "--format=%b", "refs/heads/dispatch/" + TASK).lines().findFirst().orElseThrow());
    }

    @Test
    void theWorktreeIsOnTheBranchTheJobNames() throws Exception {
        Job plan = withBranch(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null), "dispatch/team/" + TASK);

        runner.run(plan, events, control);

        assertEquals("dispatch/team/" + TASK, GitFixture.sh(Path.of(events.worktree), "git", "rev-parse", "--abbrev-ref", "HEAD"));
    }

    @Test
    void aPushThatFailsComesBackAsDeliveryAndKeepsTheAgentsResult() throws Exception {
        runner.run(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null), events, control);
        GitFixture.sh(repos.repo("alm"), "git", "remote", "set-url", "origin", dir.resolve("missing.git").toString());

        JobResult result = runner.run(job(RunKind.EXECUTE, 2, "Implement the approved plan", events.worktree, events.baseSha, null),
                new Recorder(), new ActiveRuns().register(TASK, 2));

        assertEquals(FailureReason.DELIVERY, result.failureReason());
        assertTrue(result.failureDetail().contains("git push"), result.failureDetail());
        assertTrue(result.agent().summary().contains("AUTH_TIMEOUT_SECONDS"), "the summary is kept, so a retry delivers it");
    }

    @Test
    void loopOnRunsTheTestsAndTheReviewerAndPutsTheVerificationOnTheCommit() throws Exception {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        answers.put(RunKind.REVIEW, answer("{\"verdict\":\"ok\",\"findings\":[]}", "0.20"));
        List<String> testCommands = new CopyOnWriteArrayList<>();

        JobResult result = executeWithLoop(true, (command, workdir, log, timeout, stop, started) -> {
            testCommands.add(command);
            return new TestRunner.TestRun(0, false, false, "BUILD SUCCESS");
        });

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome());
        assertEquals(List.of("./mvnw -q test"), testCommands);
        assertEquals(Verification.Tests.PASSED, result.verification().tests());
        assertEquals(Verification.ReviewState.OK, result.verification().review());
        assertEquals(List.of(RunKind.EXECUTE, RunKind.REVIEW), agentKindsStarted);
        assertTrue(lastCommitBody().contains("Verification\n- Tests: pass (run 1)"), lastCommitBody());
    }

    @Test
    void anExecutionReportsItsImplementationTestReviewAndDeliveryAsSteps() throws Exception {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        answers.put(RunKind.REVIEW, answer("{\"verdict\":\"ok\",\"findings\":[]}", "0.20"));

        executeWithLoop(true, (command, workdir, log, timeout, stop, started) -> new TestRunner.TestRun(0, false, false, "ok"));

        assertEquals(List.of("1 PLAN 1 → DONE"), events.steps, "a planning run is one step");
        assertEquals(List.of("1 IMPLEMENT 1 → DONE", "2 TEST 1 → PASSED", "3 REVIEW 1 → OK", "4 DELIVER 1 → DONE"), loopEvents.steps);
    }

    @Test
    void loopOffIsTodaysPath() throws Exception {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));

        JobResult result = executeWithLoop(false, (command, workdir, log, timeout, stop, started) -> {
            throw new AssertionError("loop off never runs the tests");
        });

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome());
        assertNull(result.verification());
        assertEquals(List.of(RunKind.EXECUTE), agentKindsStarted);
        assertFalse(lastCommitBody().contains("Verification"), lastCommitBody());
    }

    @Test
    void theLoopsAgentCallsAddUpInTheRunsCost() throws Exception {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        answers.put(RunKind.REVIEW, answer("{\"verdict\":\"ok\",\"findings\":[]}", "0.20"));
        Deque<Integer> exitCodes = new ArrayDeque<>(List.of(1, 0));

        JobResult result = executeWithLoop(true, (command, workdir, log, timeout, stop, started) ->
                new TestRunner.TestRun(exitCodes.pop(), false, false, "FooTest failed"));

        assertEquals(List.of(RunKind.EXECUTE, RunKind.EXECUTE, RunKind.REVIEW), agentKindsStarted);
        assertEquals(new BigDecimal("2.20"), result.agent().costUsd());
    }

    @Test
    void cancelDuringTheLoopDeliversNothing() throws Exception {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        answers.put(RunKind.REVIEW, answer("{\"verdict\":\"ok\",\"findings\":[]}", "0.20"));
        ActiveRuns.ActiveRun execution = new ActiveRuns().register(TASK, 2);
        java.util.concurrent.atomic.AtomicInteger testRuns = new java.util.concurrent.atomic.AtomicInteger();

        // The first test run fails and a fix runs; the member cancels during the second.
        JobResult result = executeWithLoop(true, execution, (command, workdir, log, timeout, stop, started) -> {
            if (testRuns.incrementAndGet() == 1) {
                return new TestRunner.TestRun(1, false, false, "FooTest failed");
            }
            execution.stop(ActiveRuns.StopReason.CANCELLED);
            return new TestRunner.TestRun(-1, false, true, "");
        });

        assertEquals(JobResult.Outcome.CANCELLED, result.outcome());
        assertNull(result.prUrl());
        assertEquals(List.of(RunKind.EXECUTE, RunKind.EXECUTE), agentKindsStarted, "no reviewer after a cancel");
        assertEquals(new BigDecimal("2.00"), result.agent().costUsd(), "the fix's cost counts though nothing was delivered");
        assertEquals("", origin("branch", "--list", "dispatch/" + TASK), "nothing was pushed");
    }

    @Test
    void aFixThatOutlastsItsTimeEndsOnlyThatCallAndTheRunStillDelivers() throws Exception {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        fixesHang = true;
        // A stopped clock leaves the whole 13 min every time the loop asks; all but 1 s is held back, so the fix gets 1 s.
        Duration timeout = Duration.ofMinutes(13);
        Clock stopped = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), java.time.ZoneOffset.UTC);

        JobResult result = executeWithLoop(true, new ActiveRuns().register(TASK, 2),
                (command, workdir, log, limit, stop, started) -> new TestRunner.TestRun(1, false, false, "FooTest failed"),
                stopped, timeout, timeout.minusSeconds(1));

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome());
        assertEquals(FakeGh.PR_URL, result.prUrl());
        assertEquals("fix failed: timed out after 1s", result.verification().stoppedBy());
        assertTrue(lastCommitBody().contains("Stopped early: fix failed: timed out after 1s"), lastCommitBody());
    }

    @Test
    void skippingAHangingFixCancelsOnlyThatCallAndTheRunReviewsAndDelivers() throws Exception {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        answers.put(RunKind.REVIEW, answer("{\"verdict\":\"ok\",\"findings\":[]}", "0.20"));
        fixesHang = true;
        ActiveRuns.ActiveRun execution = new ActiveRuns().register(TASK, 2);
        AtomicReference<JobResult> result = new AtomicReference<>();
        Thread run = Thread.ofVirtual().start(() -> result.set(executeWithLoop(true, execution,
                (command, workdir, log, limit, stop, started) -> new TestRunner.TestRun(1, false, false, "FooTest failed"))));
        assertTrue(fixStarted.await(15, java.util.concurrent.TimeUnit.SECONDS), "the fix never started");

        execution.skip(3);   // 1 implement, 2 test, 3 fix

        assertTrue(run.join(Duration.ofSeconds(15)), "the skipped fix still hangs");
        assertEquals(JobResult.Outcome.SUCCEEDED, result.get().outcome());
        assertEquals(List.of("1 IMPLEMENT 1 → DONE", "2 TEST 1 → FAILED", "3 FIX 1 → SKIPPED", "4 REVIEW 1 → OK", "5 DELIVER 1 → DONE"),
                loopEvents.steps);
        assertEquals(null, result.get().verification().stoppedBy());
    }

    @Test
    void deliverNowDuringAHangingFixDeliversAtOnceWithoutAReview() throws Exception {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        fixesHang = true;
        ActiveRuns.ActiveRun execution = new ActiveRuns().register(TASK, 2);
        AtomicReference<JobResult> result = new AtomicReference<>();
        Thread run = Thread.ofVirtual().start(() -> result.set(executeWithLoop(true, execution,
                (command, workdir, log, limit, stop, started) -> new TestRunner.TestRun(1, false, false, "FooTest failed"))));
        assertTrue(fixStarted.await(15, java.util.concurrent.TimeUnit.SECONDS), "the fix never started");

        execution.deliverNow();

        assertTrue(run.join(Duration.ofSeconds(15)), "deliver now left the fix hanging");
        assertEquals(FakeGh.PR_URL, result.get().prUrl());
        assertEquals(List.of(RunKind.EXECUTE, RunKind.EXECUTE), agentKindsStarted, "no reviewer after deliver now");
        assertTrue(lastCommitBody().contains("Stopped early: delivered early by the requester"), lastCommitBody());
    }

    @Test
    void anInterruptDuringALoopCallFailsTheRunWithoutDelivering() throws Exception {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        fixesHang = true;
        AtomicReference<JobResult> result = new AtomicReference<>();
        AtomicReference<Boolean> stillInterrupted = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            result.set(executeWithLoop(true,
                    (command, workdir, log, limit, stop, started) -> new TestRunner.TestRun(1, false, false, "FooTest failed")));
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        assertTrue(fixStarted.await(15, java.util.concurrent.TimeUnit.SECONDS), "the fix never started");

        worker.interrupt();
        worker.join(Duration.ofSeconds(15));

        assertFalse(worker.isAlive());
        assertEquals(JobResult.Outcome.FAILED, result.get().outcome());
        assertEquals(FailureReason.INTERRUPTED, result.get().failureReason());
        assertEquals("run thread was interrupted", result.get().failureDetail());
        assertNull(result.get().prUrl());
        assertTrue(stillInterrupted.get(), "the interrupt is kept for whoever sent it");
        assertEquals("", origin("branch", "--list", "dispatch/" + TASK), "nothing was pushed");
    }

    @Test
    void aFollowUpsReviewerSeesTheWholeTasksChange() throws Exception {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        answers.put(RunKind.REVIEW, answer("{\"verdict\":\"ok\",\"findings\":[]}", "0.20"));
        // The task's first execution was delivered: its change is a commit on the branch this follow-up continues.
        beforeExecute = worktree -> {
            try {
                Files.writeString(worktree.resolve("EARLIER.md"), "the first execution's change\n");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            GitFixture.sh(worktree, "git", "add", "EARLIER.md");
            GitFixture.sh(worktree, "git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "--quiet", "-m", "first");
        };

        executeWithLoop(true, (command, workdir, log, timeout, stop, started) -> new TestRunner.TestRun(0, false, false, "BUILD SUCCESS"));

        assertEquals(1, reviewPrompts.size());
        assertTrue(reviewPrompts.get(0).contains("the first execution's change"), reviewPrompts.get(0));
        assertTrue(reviewPrompts.get(0).contains("fixed by the scripted agent"), reviewPrompts.get(0));
    }

    @Test
    void aDeliveryThatFailsAfterTheLoopKeepsTheVerificationForTheRetry() {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        answers.put(RunKind.REVIEW, answer("{\"verdict\":\"ok\",\"findings\":[]}", "0.20"));
        beforeExecute = worktree -> GitFixture.sh(repos.repo("alm"), "git", "remote", "set-url", "origin",
                dir.resolve("missing.git").toString());

        JobResult result = executeWithLoop(true, (command, workdir, log, timeout, stop, started) -> new TestRunner.TestRun(0, false, false, "ok"));

        assertEquals(FailureReason.DELIVERY, result.failureReason());
        // The run's stored summary is what a DELIVER retry commits, so it carries the block.
        assertTrue(result.agent().summary().startsWith("Raised AUTH_TIMEOUT_SECONDS to 30\n\nVerification\n- Tests: pass (run 1)"),
                result.agent().summary());
    }

    @Test
    void theTestProcessIsRecordedForOrphanKillWhileItRuns() throws Exception {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        answers.put(RunKind.REVIEW, answer("{\"verdict\":\"ok\",\"findings\":[]}", "0.20"));
        List<Long> recordedDuringTheTest = new CopyOnWriteArrayList<>();
        List<Long> testPids = new CopyOnWriteArrayList<>();
        List<Boolean> startsDuringTheTest = new CopyOnWriteArrayList<>();

        executeWithLoop(true, (command, workdir, log, timeout, stop, started) -> {
            Process test;
            try {
                test = new ProcessBuilder("sleep", "30").start();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            try {
                started.accept(test.toHandle());
                testPids.add(test.pid());
                recordedDuringTheTest.add(loopEvents.pid);
                startsDuringTheTest.add(loopEvents.processStart.equals(test.toHandle().info().startInstant().orElseThrow()));
            } finally {
                test.destroyForcibly();
            }
            return new TestRunner.TestRun(0, false, false, "ok");
        });

        assertEquals(testPids, recordedDuringTheTest, "the test's own process, not the agent's before it");
        assertEquals(List.of(true), startsDuringTheTest, "its start time, which Recovery matches against the live process");
    }

    @Test
    void anInterruptDuringATestStepStartsNoReviewer() throws Exception {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        answers.put(RunKind.REVIEW, answer("{\"verdict\":\"ok\",\"findings\":[]}", "0.20"));
        AtomicReference<JobResult> result = new AtomicReference<>();
        // As TestCommand does when its thread is interrupted: the tree is ended, the run reported stopped, the flag kept.
        Thread worker = Thread.ofVirtual().start(() -> result.set(executeWithLoop(true, (command, workdir, log, timeout, stop, started) -> {
            Thread.currentThread().interrupt();
            return new TestRunner.TestRun(-1, false, true, "");
        })));
        worker.join(Duration.ofSeconds(15));

        assertEquals(FailureReason.INTERRUPTED, result.get().failureReason());
        assertEquals(List.of(RunKind.EXECUTE), agentKindsStarted, "no reviewer after an interrupt");
    }

    @Test
    void aBranchMovedBeforeItsExecutionFailsAsSetupWithoutStartingTheAgentOrPushing() {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        guarded = true;
        AtomicReference<String> foreign = new AtomicReference<>();
        // Another task's agent, which can write the clone's refs, points this task's branch at a commit it built.
        beforeExecute = worktree -> foreign.set(moveBranchToForeignCommit(worktree));

        JobResult result = executeWithLoop(false, (command, workdir, log, timeout, stop, started) -> {
            throw new AssertionError("loop off");
        });

        assertEquals(JobResult.Outcome.FAILED, result.outcome());
        assertEquals(FailureReason.SETUP, result.failureReason());
        assertTrue(result.failureDetail().contains("dispatch/" + TASK), result.failureDetail());
        assertTrue(result.failureDetail().contains(events.baseSha), result.failureDetail());
        assertTrue(result.failureDetail().contains(foreign.get()), result.failureDetail());
        assertEquals(List.of(), agentKindsStarted, "no agent builds on a moved branch");
        assertEquals("", origin("branch", "--list", "dispatch/" + TASK), "nothing was pushed");
    }

    @Test
    void aBranchMovedDuringTheRunIsFoldedBackAndOnlyThisRunsChangeIsDelivered() {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        guarded = true;
        AtomicReference<String> foreign = new AtomicReference<>();
        duringExecute = worktree -> foreign.set(moveBranchToForeignCommit(worktree));

        String logged = capturingLog(() -> assertEquals(JobResult.Outcome.SUCCEEDED, executeWithLoop(false,
                (command, workdir, log, timeout, stop, started) -> {
                    throw new AssertionError("loop off");
                }).outcome()));

        String branch = "refs/heads/dispatch/" + TASK;
        assertEquals(events.baseSha, origin("rev-parse", branch + "^"),
                "the delivery commit sits on the job's expected head, the run's start");
        assertEquals("README.md", origin("diff-tree", "--no-commit-id", "--name-only", "-r", branch),
                "none of the foreign commit's files");
        assertTrue(logged.contains("event=task.branch_moved"), logged);
        assertTrue(logged.contains("found=" + foreign.get()), logged);
    }

    @Test
    void anAgentsOwnCommitIsFoldedIntoTheOneDeliveryCommit() {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        guarded = true;
        duringExecute = worktree -> {
            try {
                Files.writeString(worktree.resolve("AGENT.md"), "committed by the agent\n");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            GitFixture.sh(worktree, "git", "add", "AGENT.md");
            GitFixture.sh(worktree, "git", "-c", "user.name=Agent", "-c", "user.email=agent@example.com", "commit", "--quiet",
                    "-m", "the agent's own commit");
        };

        JobResult result = executeWithLoop(false, (command, workdir, log, timeout, stop, started) -> {
            throw new AssertionError("loop off");
        });

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome(), String.valueOf(result.failureDetail()));
        String branch = "refs/heads/dispatch/" + TASK;
        assertEquals(events.baseSha, origin("rev-parse", branch + "^"), "one commit on the run's start");
        assertEquals("AGENT.md\nREADME.md", origin("diff-tree", "--no-commit-id", "--name-only", "-r", branch));
        assertEquals(origin("rev-parse", branch), result.head());
    }

    @Test
    void aWorktreeDetachedFromItsBranchDuringTheRunFailsAsDeliveryAndPushesNothing() {
        assertDeliveryRefusedWhenDuringTheRun(worktree -> GitFixture.sh(worktree, "git", "checkout", "--quiet", "--detach"));
    }

    @Test
    void aWorktreeSwitchedToAnotherBranchDuringTheRunFailsAsDeliveryAndPushesNothing() {
        assertDeliveryRefusedWhenDuringTheRun(worktree -> {
            GitFixture.sh(worktree, "git", "branch", "dispatch/99");
            GitFixture.sh(worktree, "git", "symbolic-ref", "HEAD", "refs/heads/dispatch/99");
        });
    }

    private void assertDeliveryRefusedWhenDuringTheRun(java.util.function.Consumer<Path> move) {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        guarded = true;
        duringExecute = move;

        JobResult result = executeWithLoop(false, (command, workdir, log, timeout, stop, started) -> {
            throw new AssertionError("loop off");
        });

        assertEquals(FailureReason.DELIVERY, result.failureReason());
        assertTrue(result.failureDetail().contains("dispatch/" + TASK), result.failureDetail());
        assertEquals("", origin("branch", "--list", "dispatch/*"), "nothing was pushed");
        assertEquals(events.baseSha, result.head(), "the branch's expected commit stays the one Dispatch left it at");
    }

    @Test
    void anUnmovedBranchDeliversAndReportsTheCommitItIsNowAt() {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        guarded = true;

        JobResult result = executeWithLoop(false, (command, workdir, log, timeout, stop, started) -> {
            throw new AssertionError("loop off");
        });

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome());
        assertEquals(origin("rev-parse", "refs/heads/dispatch/" + TASK), result.head());
    }

    @Test
    void aJobWithoutAnExpectedHeadIsNotCheckedAsBeforeTheGuard() {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        beforeExecute = this::moveBranchToForeignCommit;

        JobResult result = executeWithLoop(false, (command, workdir, log, timeout, stop, started) -> {
            throw new AssertionError("loop off");
        });

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome(), "an older team machine's job runs as it did");
        assertNull(result.head(), "nothing an older team machine would reject");
    }

    @Test
    void aPushThatFailsAfterTheCommitReportsTheCommitSoTheRetryDeliversIt() {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        guarded = true;
        String url = GitFixture.sh(repos.repo("alm"), "git", "remote", "get-url", "origin");
        beforeExecute = worktree -> GitFixture.sh(repos.repo("alm"), "git", "remote", "set-url", "origin",
                dir.resolve("missing.git").toString());
        JobResult failed = executeWithLoop(false, (command, workdir, log, timeout, stop, started) -> {
            throw new AssertionError("loop off");
        });
        assertEquals(FailureReason.DELIVERY, failed.failureReason());
        String committed = GitFixture.sh(Path.of(events.worktree), "git", "rev-parse", "refs/heads/dispatch/" + TASK);
        assertEquals(committed, failed.head(), "Dispatch's own unpushed commit is where the branch now is");
        GitFixture.sh(repos.repo("alm"), "git", "remote", "set-url", "origin", url);

        JobResult retried = runner.run(deliverJob(failed.head()), new Recorder(), new ActiveRuns().register(TASK, 3));

        assertEquals(JobResult.Outcome.SUCCEEDED, retried.outcome(), String.valueOf(retried.failureDetail()));
        assertEquals(origin("rev-parse", "refs/heads/dispatch/" + TASK), retried.head());
    }

    @Test
    void aDeliveryRetryOnAMovedBranchDeliversFromTheTrustedBase() throws Exception {
        runner.run(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null), events, control);
        Path worktree = Path.of(events.worktree);
        Files.writeString(worktree.resolve("README.md"), "v2\n");
        Files.delete(worktree.resolve("fake-claude.prompt"));
        moveBranchToForeignCommit(worktree);

        JobResult result = runner.run(deliverJob(events.baseSha), new Recorder(), new ActiveRuns().register(TASK, 3));

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome(), String.valueOf(result.failureDetail()));
        String branch = "refs/heads/dispatch/" + TASK;
        assertEquals(events.baseSha, origin("rev-parse", branch + "^"));
        assertEquals("README.md", origin("diff-tree", "--no-commit-id", "--name-only", "-r", branch),
                "none of the foreign commit's files");
        assertEquals(origin("rev-parse", branch), result.head());
    }

    /** A DELIVER job for the planned task, expecting its branch at {@code expectedHead}. */
    private Job deliverJob(String expectedHead) {
        return new Job(TASK, 3, RunKind.DELIVER, project(List.of()), "main", events.baseSha, events.worktree, null, null,
                false, null, null, null, 0L, null, List.of(), "dispatch #7: Fix the login timeout",
                List.of("Requested-by: Bold", "Approved-by: Bold"), "Raised AUTH_TIMEOUT_SECONDS to 30", null, null,
                expectedHead);
    }

    /**
     * Points the task's branch at a commit on top of it that Dispatch never made, adding FOREIGN.md, as another task's agent
     * could: through the clone's refs, never touching this worktree's index or files. Returns the commit.
     */
    private String moveBranchToForeignCommit(Path worktree) {
        String tree = GitFixture.sh(worktree, "sh", "-c", """
                export GIT_INDEX_FILE="$(mktemp -u)"
                git read-tree HEAD
                git update-index --add --cacheinfo "100644,$(echo foreign | git hash-object -w --stdin),FOREIGN.md"
                git write-tree
                rm -f "$GIT_INDEX_FILE"
                """);
        String foreign = GitFixture.sh(worktree, "git", "-c", "user.name=B", "-c", "user.email=b@example.com", "commit-tree",
                tree, "-p", "HEAD", "-m", "task B's work");
        GitFixture.sh(worktree, "git", "update-ref", "refs/heads/dispatch/" + TASK, foreign);
        return foreign;
    }

    /** What {@code action} logged; Log writes to stdout. */
    private static String capturingLog(Runnable action) {
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream logged = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(logged, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return logged.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void withSkillsEveryCallOfAnExecutionGetsThePluginAndItsNote() throws Exception {
        skills = true;
        SkillsPlugin.install(workspaces.skillsPluginDir());
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        answers.put(RunKind.REVIEW, answer("{\"verdict\":\"ok\",\"findings\":[]}", "0.20"));
        Deque<Integer> exitCodes = new ArrayDeque<>(List.of(1, 0));

        JobResult result = executeWithLoop(true, (command, workdir, log, timeout, stop, started) ->
                new TestRunner.TestRun(exitCodes.pop(), false, false, "FooTest failed"));

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome(), result.failureDetail());
        assertEquals(List.of(RunKind.EXECUTE, RunKind.EXECUTE, RunKind.REVIEW), agentKindsStarted);
        for (RunRequest request : loopRequests) {
            assertEquals(List.of(workspaces.skillsPluginDir()), request.pluginDirs(), request.kind() + " resume=" + request.resume());
        }
        assertTrue(loopRequests.get(0).prompt().endsWith(Prompts.SkillNote.EXECUTE.after()), loopRequests.get(0).prompt());
        assertTrue(loopRequests.get(1).prompt().endsWith(Prompts.SkillNote.FIX_TEST.after()), "the fix is resumed with its own note");
        assertTrue(loopRequests.get(2).prompt().startsWith(Prompts.SkillNote.REVIEW.before()), loopRequests.get(2).prompt());
    }

    @Test
    void withoutSkillsNoCallGetsThePluginOrANote() throws Exception {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        answers.put(RunKind.REVIEW, answer("{\"verdict\":\"ok\",\"findings\":[]}", "0.20"));

        executeWithLoop(true, (command, workdir, log, timeout, stop, started) -> new TestRunner.TestRun(0, false, false, "ok"));

        assertEquals(2, loopRequests.size());
        for (RunRequest request : loopRequests) {
            assertEquals(List.of(), request.pluginDirs());
            assertFalse(request.prompt().contains("dispatch:"), request.prompt());
        }
    }

    @Test
    void aSkillsJobOnAMachineWithoutThePluginFailsAsSetupWithoutAnAgent() {
        skills = true;

        JobResult result = executeWithLoop(true, (command, workdir, log, timeout, stop, started) -> {
            throw new AssertionError("no test runs without the plugin");
        });

        assertEquals(FailureReason.SETUP, result.failureReason());
        assertTrue(result.failureDetail().contains("skills plugin missing at " + workspaces.skillsPluginDir()),
                result.failureDetail());
        assertEquals(List.of(), agentKindsStarted);
    }

    @Test
    void aPlanWithSkillsStartsClaudeWithThePluginAndThePlanNote() throws Exception {
        SkillsPlugin.install(workspaces.skillsPluginDir());

        JobResult result = runner.run(withSkills(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null)),
                events, control);

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome(), result.failureDetail());
        Path worktree = Path.of(events.worktree);
        List<String> args = Files.readAllLines(worktree.resolve("fake-claude.args"));
        assertEquals(workspaces.skillsPluginDir().toString(), args.get(args.indexOf("--plugin-dir") + 1));
        assertEquals("Read,Bash,Skill", args.get(args.indexOf("--tools") + 1));
        // fake-claude.sh reads the prompt with $(cat), which drops its trailing newline.
        String prompt = Files.readString(worktree.resolve("fake-claude.prompt"));
        assertTrue(prompt.endsWith(Prompts.SkillNote.PLAN.text()), prompt);
    }

    /** A copy of {@code job} whose project has skills on, as the team machine sends a Claude Code project's job. */
    private static Job withSkills(Job job) {
        Job.Project p = job.project();
        return new Job(job.taskId(), job.seq(), job.kind(), new Job.Project(p.name(), p.repo(), p.path(), p.baseBranch(),
                p.agent(), p.copyFiles(), p.test(), p.loop(), true), job.baseBranch(), job.baseSha(), job.worktree(),
                job.prUrl(), job.sessionId(), job.resume(), job.prompt(), job.model(), job.effort(), job.timeoutMillis(),
                job.budgetUsd(), job.attachments(), job.commitSubject(), job.commitTrailers(), job.deliverySummary(),
                job.branch(), job.reviewPrompt(), job.expectedHead());
    }

    /** Plans with fake claude for a worktree, then runs an EXECUTE job with the loop on or off, a test command and a review prompt. */
    private JobResult executeWithLoop(boolean loop, TestRunner tests) {
        return executeWithLoop(loop, new ActiveRuns().register(TASK, 2), tests);
    }

    private JobResult executeWithLoop(boolean loop, ActiveRuns.ActiveRun execution, TestRunner tests) {
        // An hour and a budget of 10: room for every step the loop may take.
        return executeWithLoop(loop, execution, tests, Clock.systemUTC(), Duration.ofHours(1), JobRunner.DELIVERY_RESERVE);
    }

    private JobResult executeWithLoop(boolean loop, ActiveRuns.ActiveRun execution, TestRunner tests, Clock clock,
                                      Duration timeout, Duration deliveryReserve) {
        runner.run(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null), events, control);
        beforeExecute.accept(Path.of(events.worktree));
        agentKindsStarted.clear();
        JobRunner looping = new JobRunner(workspaces, delivery, Map.of("claude-code", new ScriptedAgent()),
                Redactor.patternsOnly(), (fileRef, target) -> {
                    throw new IllegalStateException("file " + fileRef + " is gone");
                }, tests, clock, deliveryReserve);
        Job.Project project = new Job.Project("alm", repos.origin.toString(), null, "main", "claude-code", List.of(),
                "./mvnw -q test", loop, skills ? Boolean.TRUE : null);
        Job job = new Job(TASK, 2, RunKind.EXECUTE, project, "main", events.baseSha, events.worktree, null, SESSION, false,
                "Implement the approved plan", null, null, timeout.toMillis(), new BigDecimal("10"), List.of(),
                "dispatch #7: Fix the login timeout", List.of("Requested-by: Bold", "Approved-by: Bold"), null, null,
                loop ? "Review this:" : null, guarded ? events.baseSha : null);
        return looping.run(job, loopEvents, execution);
    }

    private String lastCommitBody() {
        return GitFixture.sh(Path.of(events.worktree), "git", "log", "-1", "--format=%B");
    }

    private static AgentResult answer(String structuredOutput, String costUsd) {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, SESSION.toString(), structuredOutput, "Raised AUTH_TIMEOUT_SECONDS to 30",
                new BigDecimal(costUsd), 1, List.of(), null, null, null);
    }

    /** Records each kind it starts; answers the kinds in {@link #answers} itself (an EXECUTE edits README.md), the rest via fake claude. */
    private final class ScriptedAgent implements Agent {

        @Override
        public RunHandle start(RunRequest request) {
            loopRequests.add(request);
            agentKindsStarted.add(request.kind());
            if (request.kind() == RunKind.REVIEW) {
                reviewPrompts.add(request.prompt());
            }
            if (fixesHang && request.kind() == RunKind.EXECUTE && request.resume()) {
                return hanging();
            }
            AgentResult answer = answers.get(request.kind());
            if (answer == null) {
                return claude.start(request);
            }
            if (request.kind() == RunKind.EXECUTE) {
                duringExecute.accept(request.workdir());
                try {
                    Files.writeString(request.workdir().resolve("README.md"), "fixed by the scripted agent\n",
                            java.nio.file.StandardOpenOption.APPEND);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            return new RunHandle() {
                @Override
                public ProcessHandle process() {
                    return ProcessHandle.current();
                }

                @Override
                public AgentResult await() {
                    return answer;
                }

                @Override
                public void cancel() {
                    // Already finished: there is nothing to stop.
                }

                @Override
                public AgentActivity activity() {
                    return new AgentActivity(0, null);
                }
            };
        }

        /** An agent that works until it is cancelled, when it reports like a killed process. */
        private RunHandle hanging() {
            java.util.concurrent.CountDownLatch cancelled = new java.util.concurrent.CountDownLatch(1);
            fixStarted.countDown();
            return new RunHandle() {
                @Override
                public ProcessHandle process() {
                    return ProcessHandle.current();
                }

                @Override
                public AgentResult await() throws InterruptedException {
                    cancelled.await();
                    return new AgentResult(AgentOutcome.FAILED, 143, null, null, null, null, null, List.of(), "killed", null, null);
                }

                @Override
                public void cancel() {
                    cancelled.countDown();
                }

                @Override
                public AgentActivity activity() {
                    return new AgentActivity(0, null);
                }
            };
        }
    }

    private String origin(String... args) {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "--git-dir";
        command[2] = repos.origin.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        return GitFixture.sh(dir, command);
    }

    private Job job(RunKind kind, int seq, String prompt, String worktree, String baseSha, List<String> copyFiles) {
        return new Job(TASK, seq, kind, project(copyFiles == null ? List.of() : copyFiles), "main", baseSha, worktree, null,
                SESSION, false, prompt, null, null, Duration.ofSeconds(30).toMillis(), new BigDecimal("2"), List.of(),
                "dispatch #7: Fix the login timeout", List.of("Requested-by: Bold", "Approved-by: Bold"), null);
    }

    /** A copy of {@code job} with its team-sent branch set, through the 20-arg constructor. */
    private static Job withBranch(Job job, String branch) {
        return new Job(job.taskId(), job.seq(), job.kind(), job.project(), job.baseBranch(), job.baseSha(), job.worktree(),
                job.prUrl(), job.sessionId(), job.resume(), job.prompt(), job.model(), job.effort(), job.timeoutMillis(),
                job.budgetUsd(), job.attachments(), job.commitSubject(), job.commitTrailers(), job.deliverySummary(), branch);
    }

    private Job.Project project(List<String> copyFiles) {
        // path null: the clone GitFixture made under the state directory's repos/, as Dispatch's own projects use.
        return new Job.Project("alm", repos.origin.toString(), null, "main", "claude-code", copyFiles);
    }

    private static String valueAfter(List<String> args, String flag) {
        int index = args.indexOf(flag);
        if (index < 0 || index + 1 >= args.size()) {
            throw new AssertionError(flag + " missing in " + args);
        }
        return args.get(index + 1);
    }

    private static void awaitFile(Path file) throws Exception {
        Instant deadline = Instant.now().plusSeconds(15);
        while (!Files.exists(file) || Files.readString(file).isBlank()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("timed out waiting for " + file);
            }
            Thread.sleep(20);
        }
    }

    /** What the Coordinator would write to the store while the job runs. */
    private static final class Recorder implements JobEvents {

        private String worktree;
        private String baseSha;
        private long pid;
        private Instant processStart;

        @Override
        public void worktreeCreated(String worktree, String baseSha) {
            this.worktree = worktree;
            this.baseSha = baseSha;
        }

        @Override
        public void agentStarted(Long pid, Instant processStart) {
            this.pid = pid == null ? 0 : pid;
            this.processStart = processStart;
        }

        /** "n KIND round → OUTCOME", as each step started and ended. */
        private final List<String> steps = new CopyOnWriteArrayList<>();

        @Override
        public void stepStarted(int n, dispatch.domain.RunStep.Kind kind, int round) {
            steps.add(n + " " + kind + " " + round);
        }

        @Override
        public void stepEnded(int n, dispatch.domain.RunStep.Outcome outcome, String detail) {
            steps.set(n - 1, steps.get(n - 1) + " → " + outcome);
        }
    }
}
