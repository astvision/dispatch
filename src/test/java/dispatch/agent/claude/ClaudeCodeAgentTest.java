package dispatch.agent.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.AgentStartException;
import dispatch.agent.RunHandle;
import dispatch.agent.RunRequest;
import dispatch.domain.RunKind;
import dispatch.testing.FakeClaude;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake claude and gh CLIs are POSIX shell scripts")
class ClaudeCodeAgentTest {

    private static final UUID SESSION = UUID.fromString("0b9d2c1e-7a53-4b5e-9d1a-2f6a8e4c3b21");

    @TempDir
    Path dir;

    private Path workdir;
    private ClaudeCodeAgent agent;

    @BeforeEach
    void setUp() throws IOException {
        workdir = Files.createDirectories(dir.resolve("worktree"));
        agent = new ClaudeCodeAgent(FakeClaude.install(dir).toString(), FakeClaude.environment(), Duration.ofSeconds(2));
    }

    @Test
    void planRunGetsThePromptOnStdinAndHeadlessReadOnlyFlags() throws Exception {
        Path attachments = Files.createDirectories(dir.resolve("attachments"));
        RunRequest request = new RunRequest(RunKind.PLAN, workdir, "Plan the login timeout fix", SESSION, false,
                List.of(attachments), new BigDecimal("2"), "opus", "high", dir.resolve("runs/1/1"));

        AgentResult result = agent.start(request).await();

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
        assertEquals("Plan the login timeout fix", Files.readString(workdir.resolve("fake-claude.prompt")));
        List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));
        assertTrue(args.contains("-p"), args.toString());
        assertEquals("stream-json", valueAfter(args, "--output-format"));
        assertTrue(args.contains("--verbose"), args.toString());
        assertEquals("none", valueAfter(args, "--permission-prompts"));
        assertEquals("project,local", valueAfter(args, "--setting-sources"));
        assertTrue(args.contains("--strict-mcp-config"), args.toString());
        assertEquals("plan", valueAfter(args, "--permission-mode"));
        assertEquals("Read,Bash", valueAfter(args, "--tools"));
        assertEquals("2", valueAfter(args, "--max-budget-usd"));
        assertEquals(SESSION.toString(), valueAfter(args, "--session-id"));
        assertFalse(args.contains("--resume"), args.toString());
        assertEquals("opus", valueAfter(args, "--model"));
        assertEquals("high", valueAfter(args, "--effort"));
        assertEquals(attachments.toString(), valueAfter(args, "--add-dir"));
        assertTrue(valueAfter(args, "--json-schema").contains("\"understanding\""), args.toString());
        Path fixture = Path.of(getClass().getResource("/fixtures/claude/plan-success.jsonl").toURI());
        assertEquals(Files.readAllLines(fixture), Files.readAllLines(dir.resolve("runs/1/1.jsonl")));
    }

    @Test
    void executeRunResumesInAutoModeWithoutCommitPushOrGhAndReturnsTheSummary() throws Exception {
        RunRequest request = new RunRequest(RunKind.EXECUTE, workdir, "Implement the approved plan", SESSION, true, List.of(),
                new BigDecimal("10"), "sonnet", null, dir.resolve("runs/1/2"));

        RunHandle handle = agent.start(request);
        AgentResult result = handle.await();

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome(), result.error());
        assertTrue(result.summary().contains("AUTH_TIMEOUT_SECONDS"), result.summary());
        assertEquals(6, handle.activity().steps(), "tool calls read from the stream");
        List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));
        assertEquals("auto", valueAfter(args, "--permission-mode"));
        assertEquals("Read,Edit,Write,Bash", valueAfter(args, "--tools"));
        int denied = args.indexOf("--disallowedTools");
        assertEquals(List.of("Bash(git commit *)", "Bash(git push *)", "Bash(gh *)"), args.subList(denied + 1, denied + 4));
        assertEquals(SESSION.toString(), valueAfter(args, "--resume"));
        assertEquals("10", valueAfter(args, "--max-budget-usd"));
        assertFalse(args.contains("--json-schema"), args.toString());
        assertFalse(args.contains("--effort"), "Claude Code's default effort when the project sets none");
    }

    @Test
    void assistantTurnReadsOnlyAndMayRunNothingButDispatchAsk() throws Exception {
        Path clone = Files.createDirectories(dir.resolve("clones/life"));
        RunRequest request = new RunRequest(RunKind.ASSISTANT, workdir, "юу хийгдэж байна?", SESSION, true, List.of(clone),
                null, "haiku", null, dir.resolve("assistant/100-1"), java.util.Map.of("DISPATCH_ASK_MEMBER", "telegram:100"));

        AgentResult result = agent.start(request).await();

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome(), result.error());
        assertTrue(result.structuredOutput().contains("telegram:100"), "the run's own variables reach the agent: " + result.structuredOutput());
        List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));
        assertEquals("dontAsk", valueAfter(args, "--permission-mode"), "anything not allowed is refused without asking");
        assertEquals("project", valueAfter(args, "--setting-sources"));
        assertTrue(args.contains("--strict-mcp-config"), args.toString());
        assertEquals("Read,Grep,Glob,Bash", valueAfter(args, "--tools"));
        assertEquals(List.of("Bash(dispatch ask *)"), args.subList(args.indexOf("--allowedTools") + 1, args.size()),
                "the one allowed command, last because the flag takes every argument after it");
        assertTrue(valueAfter(args, "--json-schema").contains("\"actions\""), args.toString());
        assertEquals(SESSION.toString(), valueAfter(args, "--resume"));
        assertEquals(clone.toString(), valueAfter(args, "--add-dir"));
        assertFalse(args.contains("--max-budget-usd"), "no cap, as the owner chose");
        String env = Files.readString(workdir.resolve("fake-claude.env"));
        assertFalse(env.contains("telegram-secret"), "the bot's token still never reaches the agent");
    }

    @Test
    void splitRunAnswersWithTopicsWithoutToolsSkillsOrASavedSession() throws Exception {
        RunRequest request = new RunRequest(RunKind.SPLIT, workdir, "Split this message", null, false, List.of(),
                new BigDecimal("0.25"), "haiku", null, dir.resolve("splits/7-1"));

        AgentResult result = agent.start(request).await();

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome(), result.error());
        assertTrue(result.structuredOutput().contains("make help target"), result.structuredOutput());
        List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));
        assertEquals("plan", valueAfter(args, "--permission-mode"));
        assertEquals("", valueAfter(args, "--tools"), "no tools at all");
        assertEquals("haiku", valueAfter(args, "--model"));
        assertEquals("0.25", valueAfter(args, "--max-budget-usd"));
        assertTrue(valueAfter(args, "--json-schema").contains("\"topics\""), args.toString());
        assertTrue(valueAfter(args, "--system-prompt").contains("split"), "Claude Code's own coding prompt is not needed");
        assertTrue(args.contains("--no-session-persistence"), args.toString());
        assertTrue(args.contains("--disable-slash-commands"), args.toString());
        assertFalse(args.contains("--session-id") || args.contains("--resume"), args.toString());
    }

    @Test
    void agentStartedInTheWrongPermissionModeIsStoppedInsteadOfRunningOn() throws Exception {
        RunHandle handle = agent.start(new RunRequest(RunKind.EXECUTE, workdir, "SCENARIO:wrong-mode", SESSION, true, List.of(),
                new BigDecimal("10"), "haiku", null, dir.resolve("runs/1/2")));
        long child = awaitChildPid();

        AgentResult result = CompletableFuture.supplyAsync(() -> awaitQuietly(handle)).get(10, TimeUnit.SECONDS);

        assertEquals(AgentOutcome.FAILED, result.outcome());
        assertTrue(result.error().contains("permission mode 'default' instead of 'auto'"), result.error());
        assertTrue(FakeClaude.childEnds(child), "the stopped run's children must be gone");
    }

    @Test
    void startTimeIsKnownAfterTheAgentHasExited() throws Exception {
        RunHandle handle = agent.start(plan("Plan it"));
        handle.await();

        assertFalse(handle.process().isAlive());
        assertNotNull(handle.processStart(), "with the pid, it tells a crashed run's orphan from a process that reused the pid");
    }

    @Test
    void laterRunsResumeTheSession() throws Exception {
        RunRequest request = new RunRequest(RunKind.PLAN, workdir, "Revise the plan", SESSION, true, List.of(),
                new BigDecimal("2"), null, null, dir.resolve("runs/1/2"));

        agent.start(request).await();

        List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));
        assertEquals(SESSION.toString(), valueAfter(args, "--resume"));
        assertFalse(args.contains("--session-id"), args.toString());
        assertFalse(args.contains("--model"), args.toString());
    }

    @Test
    void secretsNeverReachTheAgentEnvironment() throws Exception {
        agent.start(plan("Plan it")).await();

        String env = Files.readString(workdir.resolve("fake-claude.env"));
        assertFalse(env.contains("TELEGRAM_BOT_TOKEN"), env);
        assertFalse(env.contains("GH_TOKEN"), env);
        assertFalse(env.contains("DISPATCH_WORKER_KEY"), env);
        assertTrue(env.contains("ANTHROPIC_API_KEY=sk-ant-test"), env);
    }

    @Test
    void failingAgentReportsExitCodeAndStderr() throws Exception {
        AgentResult result = agent.start(plan("SCENARIO:fail")).await();

        assertEquals(AgentOutcome.FAILED, result.outcome());
        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("fatal: model overloaded"), result.error());
        assertTrue(Files.readString(dir.resolve("runs/1/1.stderr")).contains("fatal: model overloaded"));
    }

    @Test
    void cancelTerminatesTheWholeProcessTree() throws Exception {
        RunHandle handle = agent.start(plan("SCENARIO:sleep"));
        long child = awaitChildPid();

        handle.cancel();
        AgentResult result = CompletableFuture.supplyAsync(() -> awaitQuietly(handle)).get(10, TimeUnit.SECONDS);

        assertEquals(AgentOutcome.FAILED, result.outcome());
        assertTrue(FakeClaude.childEnds(child), "child sleep must be gone");
    }

    @Test
    void cancelKillsAgentThatIgnoresTerm() throws Exception {
        ClaudeCodeAgent impatient = new ClaudeCodeAgent(FakeClaude.install(Files.createDirectories(dir.resolve("bin"))).toString(),
                FakeClaude.environment(), Duration.ofMillis(300));
        RunHandle handle = impatient.start(plan("SCENARIO:ignore-term"));
        awaitChildPid();

        handle.cancel();

        AgentResult result = CompletableFuture.supplyAsync(() -> awaitQuietly(handle)).get(10, TimeUnit.SECONDS);
        assertEquals(AgentOutcome.FAILED, result.outcome());
        assertFalse(handle.process().isAlive());
    }

    @Test
    void missingCommandFailsToStart() {
        ClaudeCodeAgent broken = new ClaudeCodeAgent(dir.resolve("no-such-claude").toString(), FakeClaude.environment(),
                Duration.ofSeconds(1));

        AgentStartException error = assertThrows(AgentStartException.class, () -> broken.start(plan("Plan it")));

        assertTrue(error.getMessage().contains("no-such-claude"), error.getMessage());
    }

    private RunRequest plan(String prompt) {
        return new RunRequest(RunKind.PLAN, workdir, prompt, SESSION, false, List.of(), new BigDecimal("2"), null,
                null,
                dir.resolve("runs/1/1"));
    }

    private long awaitChildPid() throws Exception {
        Path pidFile = workdir.resolve("fake-claude.child");
        Instant deadline = Instant.now().plusSeconds(10);
        while (!Files.exists(pidFile) || Files.readString(pidFile).isBlank()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("fake claude never started its child");
            }
            Thread.sleep(20);
        }
        return Long.parseLong(Files.readString(pidFile).strip());
    }

    private static AgentResult awaitQuietly(RunHandle handle) {
        try {
            return handle.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String valueAfter(List<String> args, String flag) {
        int index = args.indexOf(flag);
        if (index < 0 || index + 1 >= args.size()) {
            throw new AssertionError(flag + " missing in " + args);
        }
        return args.get(index + 1);
    }
}
