package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.Redactor;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.config.Config;
import dispatch.domain.ClaimedRun;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.testing.FakeClaude;
import dispatch.testing.FakeGh;
import dispatch.testing.GitFixture;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.io.TempDir;

/** Runs end to end: real SQLite, real git worktrees and origin, the fake claude script as the agent and fake gh. */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake claude and gh CLIs are POSIX shell scripts")
class RunExecutorTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final String CHAT = "telegram:-100";

    @TempDir
    Path dir;

    private GitFixture repos;
    private Path dbFile;
    private Database db;
    private ActiveRuns activeRuns;
    private TaskService tasks;
    private final AtomicInteger schedulerWakes = new AtomicInteger();
    private Duration planTimeout = Duration.ofSeconds(30);
    private List<String> copyFiles = List.of();

    @BeforeEach
    void setUp() throws IOException {
        repos = GitFixture.create(dir, "alm");
        dbFile = repos.stateDir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        activeRuns = new ActiveRuns();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void successfulPlanRunPostsThePlanAndRecordsWorktreeAndProcess() throws Exception {
        long id = queue("Fix the login timeout on staging");

        runNext();

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("AWAITING_APPROVAL", task.get("phase"));
        assertTrue(Plan.parse(task.get("plan_json")).understanding().startsWith("The user reports that login"));
        Path worktree = repos.stateDir.resolve("worktrees/" + id);
        assertEquals(worktree.toString(), task.get("worktree"));
        assertEquals(GitFixture.sh(repos.seed, "git", "rev-parse", "HEAD"), task.get("base_sha"));
        String prompt = Files.readString(worktree.resolve("fake-claude.prompt"));
        assertTrue(prompt.contains("Fix the login timeout on staging"), prompt);
        // Plan mode has the agent save a plan file too: recorded runs wrote each plan twice, as Markdown and as the JSON answer.
        assertTrue(prompt.contains("do not write it to a plan file"), prompt);
        List<String> args = Files.readAllLines(worktree.resolve("fake-claude.args"));
        assertEquals("high", valueAfter(args, "--effort"), "the project's effort, as planning sets none of its own");
        assertEquals("opus", valueAfter(args, "--model"), "planning's own model");

        Map<String, String> run = row("SELECT * FROM run WHERE task_id = ?", id);
        assertEquals("SUCCEEDED", run.get("status"));
        assertNotNull(run.get("pid"));
        assertNotNull(run.get("pid_start"));
        assertEquals("0.073173", run.get("cost_usd"));
        assertEquals("claude-sonnet-5", run.get("model"));
        JsonNode planReady = Json.read(row("SELECT payload FROM outbox WHERE kind = 'PLAN_READY'").get("payload"));
        assertEquals("claude-sonnet-5", planReady.get("model").asText());
        assertEquals("opus", planReady.get("requestedModel").asText(), "planning asked for Opus; the recorded run answered with Sonnet 5");
        assertTrue(Files.exists(repos.stateDir.resolve("runs/" + id + "/1.jsonl")));
        assertEquals("1", row("SELECT count(*) AS n FROM outbox WHERE kind = 'PLAN_READY'").get("n"));
        assertTrue(activeRuns.awaitIdle(Duration.ZERO));
        assertEquals(2, schedulerWakes.get(), "woken once when the task was queued and once when its run ended");
    }

    @Test
    void approvedPlanIsImplementedInAFreshBuildingSessionAndDeliveredAsADraftPullRequest() throws Exception {
        long id = queue("Fix the login timeout on staging");
        runNext();
        approve(id);

        runNext();

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("COMPLETED", task.get("phase"));
        assertEquals(FakeGh.PR_URL, task.get("pr_url"));
        Map<String, String> run = row("SELECT * FROM run WHERE task_id = ? AND seq = 2", id);
        assertEquals("SUCCEEDED", run.get("status"));
        assertTrue(run.get("output").contains("AUTH_TIMEOUT_SECONDS"), run.get("output"));
        assertTrue(Files.exists(repos.stateDir.resolve("runs/" + id + "/2.jsonl")));
        assertEquals("claude-sonnet-5", run.get("model"));
        JsonNode completed = Json.read(row("SELECT payload FROM outbox WHERE kind = 'TASK_COMPLETED'").get("payload"));
        assertEquals("claude-sonnet-5", completed.get("model").asText());
        assertFalse(completed.has("requestedModel"), "execution asked for no model");

        Path worktree = repos.stateDir.resolve("worktrees/" + id);
        String prompt = Files.readString(worktree.resolve("fake-claude.prompt"));
        assertTrue(prompt.contains("Fix the login timeout on staging"), prompt);
        assertTrue(prompt.contains("The user reports that login"), "the approved plan is part of the prompt: " + prompt);
        assertTrue(prompt.contains("Do not commit"), prompt);
        // A recorded Sonnet run answered a Mongolian task in English until the summary rule named the language explicitly.
        assertTrue(prompt.contains("a task written in Mongolian gets a Mongolian summary"), prompt);
        assertFalse(prompt.contains("your plan"), "the building session never saw the plan being made: " + prompt);
        List<String> args = Files.readAllLines(worktree.resolve("fake-claude.args"));
        assertNotNull(task.get("build_session_id"));
        assertNotEquals(task.get("session_id"), task.get("build_session_id"), "execution does not carry the investigation (ADR 0017)");
        assertEquals(task.get("build_session_id"), valueAfter(args, "--session-id"));
        assertFalse(args.contains("--resume"), args.toString());
        assertEquals("auto", valueAfter(args, "--permission-mode"));
        assertEquals("low", valueAfter(args, "--effort"), "execution's own effort");
        assertFalse(args.contains("--model"), "neither execution nor the project sets a model: Claude Code's default");

        String branch = "refs/heads/dispatch/" + id;
        assertEquals("dispatch #" + id + ": Fix the login timeout on staging", origin("log", "-1", "--format=%s", branch));
        String body = origin("log", "-1", "--format=%b", branch);
        assertTrue(body.contains("AUTH_TIMEOUT_SECONDS"), body);
        assertTrue(body.endsWith("Requested-by: Bold\nApproved-by: Bold"), body);
        assertEquals("README.md", origin("diff", "--name-only", task.get("base_sha"), branch));
        assertEquals("1", row("SELECT count(*) AS n FROM outbox WHERE kind = 'TASK_COMPLETED'").get("n"));
    }

    @Test
    void executionThatChangesNothingCompletesWithoutAPullRequest() throws Exception {
        long id = queue("SCENARIO:nochange Check the login timeout");
        runNext();
        approve(id);

        runNext();

        assertEquals("COMPLETED", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertNull(row("SELECT pr_url FROM task WHERE id = ?", id).get("pr_url"));
        assertEquals("", origin("branch", "--list", "dispatch/" + id));
    }

    @Test
    void localSecretFilesAreCopiedForExecutionButNeverDelivered() throws Exception {
        Files.writeString(repos.repo("alm").resolve(".env"), "DB_PASSWORD=local-only\n");
        copyFiles = List.of(".env");
        long id = queue("Fix the login timeout");
        runNext();
        approve(id);

        runNext();

        assertEquals("COMPLETED", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals("DB_PASSWORD=local-only\n", Files.readString(repos.stateDir.resolve("worktrees/" + id + "/.env")));
        String baseSha = row("SELECT base_sha FROM task WHERE id = ?", id).get("base_sha");
        assertEquals("README.md", origin("diff", "--name-only", baseSha, "refs/heads/dispatch/" + id));
    }

    @Test
    void failedExecutionFailsTheTaskAndDeliversNothing() throws Exception {
        long id = queue("SCENARIO:exec-fail Fix the login timeout");
        runNext();
        approve(id);

        runNext();

        assertFailed(id, "AGENT", "fatal: model overloaded");
        assertEquals("", origin("branch", "--list", "dispatch/" + id));
    }

    @Test
    void deliveryFailureFailsTheTaskWithGitsError() throws Exception {
        long id = queue("Fix the login timeout");
        runNext();
        approve(id);
        GitFixture.sh(repos.repo("alm"), "git", "remote", "set-url", "origin", dir.resolve("missing.git").toString());

        runNext();

        assertFailed(id, "DELIVERY", "git push");
    }

    @Test
    void secretsInTheAgentsSummaryAreMaskedBeforeTheyReachGitHub() throws Exception {
        long id = queue("SCENARIO:leaky-summary Fix the login timeout");
        runNext();
        approve(id);

        runNext();

        String body = origin("log", "-1", "--format=%b", "refs/heads/dispatch/" + id);
        assertTrue(body.contains("[redacted]") && !body.contains("ghp_"), body);
        String ghArgs = Files.readString(repos.stateDir.resolve("worktrees/" + id + "/fake-gh.args"));
        assertFalse(ghArgs.contains("ghp_"), ghArgs);
    }

    @Test
    void correctionRevisesThePlanInTheSameWorktreeAndSession() throws Exception {
        long id = queue("Fix the login timeout");
        runNext();
        String worktree = row("SELECT worktree FROM task WHERE id = ?", id).get("worktree");
        db.transaction(tx -> tasks.correct(tx, BOLD, id, 1, "Also cover the mobile login", CHAT + "/200", CHAT));

        runNext();

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("AWAITING_APPROVAL", task.get("phase"));
        assertEquals(worktree, task.get("worktree"));
        String prompt = Files.readString(Path.of(worktree, "fake-claude.prompt"));
        assertTrue(prompt.contains("Also cover the mobile login") && prompt.contains("Fix the login timeout"), prompt);
        assertTrue(prompt.contains("do not write it to a plan file"), prompt);
        List<String> args = Files.readAllLines(Path.of(worktree, "fake-claude.args"));
        assertEquals(task.get("session_id"), valueAfter(args, "--resume"));
        assertEquals("plan", valueAfter(args, "--permission-mode"));
        JsonNode latest = Json.read(row("SELECT payload FROM outbox WHERE kind = 'PLAN_READY' ORDER BY id DESC LIMIT 1").get("payload"));
        assertEquals(2, latest.get("planSeq").asInt());
    }

    @Test
    void agentErrorFailsTheTask() throws Exception {
        long id = queue("SCENARIO:fail");

        runNext();

        assertFailed(id, "AGENT", "fatal: model overloaded");
    }

    @Test
    void exhaustedBudgetFailsWithBudgetReason() throws Exception {
        long id = queue("SCENARIO:budget");

        runNext();

        assertFailed(id, "BUDGET", "Reached maximum budget ($0.005)");
    }

    @Test
    void planBreakingTheSchemaFailsInsteadOfBeingPosted() throws Exception {
        long id = queue("SCENARIO:badplan");

        runNext();

        assertFailed(id, "AGENT", "plan");
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'PLAN_READY'").get("n"));
    }

    @Test
    void timeoutStopsTheAgentAndItsChildren() throws Exception {
        planTimeout = Duration.ofMillis(700);
        long id = queue("SCENARIO:sleep");

        runNext();

        assertFailed(id, "TIMEOUT", "stopped after");
        long child = Long.parseLong(Files.readString(repos.stateDir.resolve("worktrees/" + id + "/fake-claude.child")).strip());
        assertTrue(FakeClaude.childEnds(child), "the timed-out run's children must be gone");
    }

    @Test
    void setupFailureFailsBeforeAnyAgentStarts() throws Exception {
        GitFixture.sh(repos.repo("alm"), "git", "remote", "set-url", "origin", dir.resolve("missing.git").toString());
        long id = queue("Fix the login timeout");

        runNext();

        assertFailed(id, "SETUP", "git fetch");
        assertFalse(Files.exists(repos.stateDir.resolve("worktrees/" + id + "/fake-claude.args")));
    }

    @Test
    void planningRunNeverSeesLocalSecretFiles() throws Exception {
        Files.writeString(repos.repo("alm").resolve(".env"), "DB_PASSWORD=local-only\n");
        copyFiles = List.of(".env");
        long id = queue("Fix the login timeout");

        runNext();

        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertFalse(Files.exists(repos.stateDir.resolve("worktrees/" + id + "/.env")), ".env is copied only for execution runs");
    }

    @Test
    void memberCancelDuringTheRunEndsItAsCancelled() throws Exception {
        long id = queue("SCENARIO:sleep");
        Thread executor = runNextInBackground();
        awaitFile(repos.stateDir.resolve("worktrees/" + id + "/fake-claude.child"));

        db.transaction(tx -> tasks.cancel(tx, BOLD, id, CHAT + "/99", CHAT));
        executor.join(Duration.ofSeconds(15));

        assertFalse(executor.isAlive());
        assertEquals("CANCELLED", row("SELECT status FROM run WHERE task_id = ?", id).get("status"));
        assertEquals("CANCELLED", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'TASK_FAILED'").get("n"));
    }

    @Test
    void shutdownInterruptsTheActiveRun() throws Exception {
        long id = queue("SCENARIO:sleep");
        Thread executor = runNextInBackground();
        awaitFile(repos.stateDir.resolve("worktrees/" + id + "/fake-claude.child"));

        activeRuns.stopAll(ActiveRuns.StopReason.INTERRUPTED);
        executor.join(Duration.ofSeconds(15));

        assertFailed(id, "INTERRUPTED", "Dispatch stopped");
        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'TASK_FAILED'").get("payload"));
        assertEquals("INTERRUPTED", payload.get("reason").asText());
    }

    @Test
    void runClaimedWhileShuttingDownNeverTouchesGitOrStartsAnAgent() throws Exception {
        long id = queue("Fix the login timeout");
        activeRuns.stopAll(ActiveRuns.StopReason.INTERRUPTED);

        runNext();

        assertFailed(id, "INTERRUPTED", "Dispatch stopped");
        assertFalse(Files.exists(repos.stateDir.resolve("worktrees/" + id)));
    }

    private long queue(String description) throws IOException {
        Config.Project alm = new Config.Project("alm", null, repos.origin.toString(), null, "main", "claude-code", null, "high", copyFiles, null,
                new Config.PhaseSettings("opus", null), new Config.PhaseSettings(null, "low"));
        Git git = new Git("git", null, Duration.ofSeconds(30));
        Workspaces workspaces = new Workspaces(repos.stateDir, git);
        Delivery delivery = new Delivery(git, new Gh(FakeGh.install(dir.resolve("gh-" + System.nanoTime())).toString(), null,
                Duration.ofSeconds(30)), "Dispatch (backend)", "dispatch-backend@example.com");
        Projects projects = new Projects(List.of(alm), workspaces::unavailableReason);
        TestClock clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
        Groups groups = new Groups(List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm"))));
        tasks = new TaskService(groups, projects, activeRuns, clock, schedulerWakes::incrementAndGet, () -> { });
        RunTransitions transitions = new RunTransitions(db, clock, () -> { });
        executorUnderTest = new RunExecutor(db, projects, workspaces, delivery, Map.of("claude-code", agent()), transitions,
                activeRuns, project -> new Config.RunLimits(planTimeout, new BigDecimal("2")),
                project -> new Config.RunLimits(Duration.ofSeconds(30), new BigDecimal("10")), Redactor.patternsOnly(),
                schedulerWakes::incrementAndGet);
        db.transaction(tx -> tasks.create(tx, BOLD, "alm", description, Priority.NORMAL, BOLD.ref() + "/" + System.nanoTime()));
        return Long.parseLong(row("SELECT max(id) AS id FROM task").get("id"));
    }

    private RunExecutor executorUnderTest;

    private ClaudeCodeAgent agent() {
        try {
            return new ClaudeCodeAgent(FakeClaude.install(Files.createDirectories(dir.resolve("bin-" + System.nanoTime()))).toString(),
                    FakeClaude.environment(), Duration.ofSeconds(1));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void runNext() {
        executorUnderTest.execute(claim());
    }

    private Thread runNextInBackground() {
        ClaimedRun claimed = claim();
        return Thread.ofVirtual().start(() -> executorUnderTest.execute(claimed));
    }

    private ClaimedRun claim() {
        return db.transactionReturning(tx -> Runs.claimNext(tx, 5, Instant.parse("2026-09-17T10:00:00Z"))).orElseThrow();
    }

    private void approve(long id) {
        assertEquals(ApproveResult.APPROVED, db.transactionReturning(tx -> tasks.approve(tx, BOLD, id, 1)));
    }

    private void assertFailed(long id, String reason, String detailFragment) {
        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("FAILED", task.get("phase"));
        assertEquals(reason, task.get("failure_reason"));
        assertTrue(task.get("failure_detail").contains(detailFragment), task.get("failure_detail"));
        assertEquals("FAILED", row("SELECT status FROM run WHERE task_id = ? ORDER BY seq DESC LIMIT 1", id).get("status"));
    }

    private String origin(String... args) {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "--git-dir";
        command[2] = repos.origin.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        return GitFixture.sh(dir, command);
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

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
