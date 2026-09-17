package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.config.Config;
import dispatch.domain.ClaimedRun;
import dispatch.domain.Plan;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.testing.FakeClaude;
import dispatch.testing.GitFixture;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
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
import org.junit.jupiter.api.io.TempDir;

/** Runs planning end to end: real SQLite, real git worktrees, and the fake claude script as the agent. */
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
        assertTrue(Plan.parse(task.get("plan_json")).understanding().startsWith("Staging орчинд"));
        Path worktree = repos.stateDir.resolve("worktrees/" + id);
        assertEquals(worktree.toString(), task.get("worktree"));
        assertEquals(GitFixture.sh(repos.seed, "git", "rev-parse", "HEAD"), task.get("base_sha"));
        assertTrue(Files.readString(worktree.resolve("fake-claude.prompt")).contains("Fix the login timeout on staging"));

        Map<String, String> run = row("SELECT * FROM run WHERE task_id = ?", id);
        assertEquals("SUCCEEDED", run.get("status"));
        assertNotNull(run.get("pid"));
        assertNotNull(run.get("pid_start"));
        assertEquals("0.168185", run.get("cost_usd"));
        assertTrue(Files.exists(repos.stateDir.resolve("runs/" + id + "/1.jsonl")));
        assertEquals("1", row("SELECT count(*) AS n FROM outbox WHERE kind = 'PLAN_READY'").get("n"));
        assertTrue(activeRuns.awaitIdle(Duration.ZERO));
        assertEquals(2, schedulerWakes.get(), "woken once when the task was queued and once when its run ended");
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
        assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
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

    private long queue(String description) {
        Config.Project alm = new Config.Project("alm", null, repos.origin.toString(), "main", "claude-code", null, copyFiles, null);
        Workspaces workspaces = new Workspaces(repos.stateDir, new Git("git", null, Duration.ofSeconds(30)));
        Projects projects = new Projects(List.of(alm), workspaces::unavailableReason);
        TestClock clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
        tasks = new TaskService(new Members(List.of(new Config.Member(100, "Bold"))), projects, activeRuns, clock,
                schedulerWakes::incrementAndGet, () -> { });
        RunTransitions transitions = new RunTransitions(db, clock, () -> { });
        executorUnderTest = new RunExecutor(db, projects, workspaces, Map.of("claude-code", agent()), transitions, activeRuns,
                project -> new Config.RunLimits(planTimeout, new BigDecimal("2")), schedulerWakes::incrementAndGet);
        db.transaction(tx -> tasks.create(tx, BOLD, "alm", description, CHAT + "/" + System.nanoTime(), CHAT));
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

    private void assertFailed(long id, String reason, String detailFragment) {
        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("FAILED", task.get("phase"));
        assertEquals(reason, task.get("failure_reason"));
        assertTrue(task.get("failure_detail").contains(detailFragment), task.get("failure_detail"));
        assertEquals("FAILED", row("SELECT status FROM run WHERE task_id = ?", id).get("status"));
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
