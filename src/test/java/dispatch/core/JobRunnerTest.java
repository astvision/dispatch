package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.Redactor;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private JobRunner runner;
    private Recorder events;
    private ActiveRuns.ActiveRun control;

    @BeforeEach
    void setUp() throws IOException {
        repos = GitFixture.create(dir, "alm");
        Git git = new Git("git", null, Duration.ofSeconds(30));
        Workspaces workspaces = new Workspaces(repos.stateDir, git);
        Delivery delivery = new Delivery(git, new Gh(FakeGh.install(dir.resolve("gh")).toString(), null, Duration.ofSeconds(30)),
                "Dispatch (backend)", "dispatch-backend@example.com");
        ClaudeCodeAgent agent = new ClaudeCodeAgent(FakeClaude.install(Files.createDirectories(dir.resolve("bin"))).toString(),
                FakeClaude.environment(), Duration.ofSeconds(1));
        runner = new JobRunner(workspaces, delivery, Map.of("claude-code", agent), Redactor.patternsOnly(),
                (fileRef, target) -> {
                    throw new IllegalStateException("file " + fileRef + " is gone");
                });
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
    void aPushThatFailsComesBackAsDeliveryAndKeepsTheAgentsResult() throws Exception {
        runner.run(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null), events, control);
        GitFixture.sh(repos.repo("alm"), "git", "remote", "set-url", "origin", dir.resolve("missing.git").toString());

        JobResult result = runner.run(job(RunKind.EXECUTE, 2, "Implement the approved plan", events.worktree, events.baseSha, null),
                new Recorder(), new ActiveRuns().register(TASK, 2));

        assertEquals(FailureReason.DELIVERY, result.failureReason());
        assertTrue(result.failureDetail().contains("git push"), result.failureDetail());
        assertTrue(result.agent().summary().contains("AUTH_TIMEOUT_SECONDS"), "the summary is kept, so a retry delivers it");
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
        public void agentStarted(long pid, Instant processStart) {
            this.pid = pid;
            this.processStart = processStart;
        }
    }
}
