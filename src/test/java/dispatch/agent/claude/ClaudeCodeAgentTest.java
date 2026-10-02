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
import dispatch.agent.SandboxUse;
import dispatch.agent.sandbox.Confinement;
import dispatch.agent.sandbox.Sandbox;
import dispatch.agent.sandbox.SandboxPolicies;
import dispatch.agent.sandbox.SandboxPolicy;
import dispatch.domain.RunKind;
import dispatch.testing.FakeClaude;
import dispatch.testing.OwnerPluginsFixture;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private static final String FAKE_SECRET = "fake-connection-string-not-a-secret";

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
    void agentWithoutConfinementRunsTheCommandUnwrapped() throws Exception {
        RunRequest request = new RunRequest(RunKind.PLAN, workdir, "Plan it", SESSION, false, List.of(), null, null, null,
                dir.resolve("runs/1/1"));

        AgentResult result = agent.start(request).await();

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
        assertTrue(Files.readAllLines(workdir.resolve("fake-claude.args")).contains("-p"), "the fake claude itself ran");
        assertEquals(new SandboxUse("none", "no sandbox configured"), result.sandbox());
    }

    @Test
    void aConfinedAgentStartsThroughItsSandbox() throws Exception {
        // env runs the command unchanged, so the fake claude still answers; the marker proves the wrap happened.
        Sandbox recording = prefixing("recording", "env", "SANDBOXED_BY=recording");
        Confinement confinement = new Confinement(recording, new SandboxPolicies(workdir, workdir.resolve("state"), List.of()));
        ClaudeCodeAgent confined = new ClaudeCodeAgent(FakeClaude.install(Files.createDirectories(dir.resolve("confined"))).toString(),
                FakeClaude.environment(), Duration.ofSeconds(2), confinement);
        RunRequest request = new RunRequest(RunKind.PLAN, workdir, "Plan it", SESSION, false, List.of(), null, null, null,
                dir.resolve("runs/1/1"));

        AgentResult result = confined.start(request).await();

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
        assertTrue(Files.readString(workdir.resolve("fake-claude.env")).contains("SANDBOXED_BY=recording"));
        assertEquals(new SandboxUse("recording", null), result.sandbox());
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
        assertTrue(result.structuredOutput().contains("Дасгалын тэмдэглэл"), result.structuredOutput());
        List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));
        assertEquals("dontAsk", valueAfter(args, "--permission-mode"), "anything not allowed is refused without asking");
        assertEquals("project", valueAfter(args, "--setting-sources"));
        assertTrue(args.contains("--strict-mcp-config"), args.toString());
        assertEquals("Read,Grep,Glob,Bash,Skill", valueAfter(args, "--tools"));
        assertEquals(List.of("Bash(dispatch ask *)"), args.subList(args.indexOf("--allowedTools") + 1, args.size()),
                "the one allowed command, last because the flag takes every argument after it");
        assertTrue(valueAfter(args, "--json-schema").contains("\"actions\""), args.toString());
        assertEquals(SESSION.toString(), valueAfter(args, "--resume"));
        assertEquals(clone.toString(), valueAfter(args, "--add-dir"));
        assertFalse(args.contains("--max-budget-usd"), "no cap, as the owner chose");
        String env = Files.readString(workdir.resolve("fake-claude.env"));
        assertTrue(env.contains("DISPATCH_ASK_MEMBER=telegram:100"), "the run's own variables reach the agent");
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

    @Test
    void aRunWithAPluginLoadsItAndMayInvokeItsSkills() throws Exception {
        Path plugin = Files.createDirectories(dir.resolve("state/plugins/dispatch"));
        for (RunKind kind : List.of(RunKind.PLAN, RunKind.EXECUTE, RunKind.REVIEW)) {
            agent.start(new RunRequest(kind, workdir, "Do it", UUID.randomUUID(), false, List.of(), new BigDecimal("1"),
                    null, null, dir.resolve("runs/1/" + kind), Map.of(), List.of(plugin))).await();

            List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));
            assertEquals(plugin.toString(), valueAfter(args, "--plugin-dir"), kind.name());
            assertTrue(valueAfter(args, "--tools").endsWith(",Skill"), kind + ": " + args);
            assertEquals("project,local", valueAfter(args, "--setting-sources"), "the owner's own plugins still stay out");
        }
    }

    /** Plan and review runs may not read outside their working directories, and nobody answers a prompt to allow it. */
    @Test
    void aRunMayReadItsPluginsSupportingFiles() throws Exception {
        Path plugin = Files.createDirectories(dir.resolve("state/plugins/dispatch"));

        agent.start(new RunRequest(RunKind.PLAN, workdir, "Do it", UUID.randomUUID(), false, List.of(), new BigDecimal("1"),
                null, null, dir.resolve("runs/1/1"), Map.of(), List.of(plugin))).await();

        List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));
        assertEquals(plugin.toString(), valueAfter(args, "--add-dir"));
    }

    @Test
    void aRunWithoutAPluginGetsNeitherThePluginNorTheSkillTool() throws Exception {
        for (RunKind kind : List.of(RunKind.PLAN, RunKind.EXECUTE, RunKind.REVIEW)) {
            agent.start(new RunRequest(kind, workdir, "Do it", UUID.randomUUID(), false, List.of(), new BigDecimal("1"),
                    null, null, dir.resolve("runs/1/" + kind))).await();

            List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));
            assertFalse(args.contains("--plugin-dir"), kind + ": " + args);
            assertFalse(valueAfter(args, "--tools").contains("Skill"), kind + ": " + args);
        }
    }

    /** A sandbox that runs the agent's command line after {@code prefix}. */
    private static Sandbox prefixing(String name, String... prefix) {
        return new Sandbox() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String unavailableReason() {
                return null;
            }

            @Override
            public List<String> wrap(List<String> commandLine, SandboxPolicy policy) {
                List<String> wrapped = new ArrayList<>(List.of(prefix));
                wrapped.addAll(commandLine);
                return wrapped;
            }
        };
    }

    /** A sandbox that plants a loader path, then runs the agent, as a prompt-injected agent could. */
    private Confinement planting(Path home, Path planted) {
        return new Confinement(prefixing("planting", "sh", "-c", "mkdir -p \"$0\" && exec \"$@\"", planted.toString()),
                new SandboxPolicies(home, dir.resolve("state"), List.of()));
    }

    @Test
    void aLoaderPathTheRunCreatesIsQuarantinedWhenItEnds() throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path planted = home.resolve(".claude/agents");
        ClaudeCodeAgent confined = new ClaudeCodeAgent(FakeClaude.install(Files.createDirectories(dir.resolve("planting"))).toString(),
                FakeClaude.environment(), Duration.ofSeconds(2), planting(home, planted));

        confined.start(new RunRequest(RunKind.PLAN, workdir, "Plan it", SESSION, false, List.of(), null, null, null,
                dir.resolve("state/runs/1/1"))).await();

        assertFalse(Files.exists(planted), "moved out of the owner's home");
        assertTrue(Files.isDirectory(dir.resolve("state/quarantine/1-1/.claude/agents")));
    }

    @Test
    void aCancelledRunIsSweptToo() throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path planted = home.resolve(".claude/agents");
        ClaudeCodeAgent confined = new ClaudeCodeAgent(FakeClaude.install(Files.createDirectories(dir.resolve("planting"))).toString(),
                FakeClaude.environment(), Duration.ofSeconds(2), planting(home, planted));
        RunHandle handle = confined.start(new RunRequest(RunKind.PLAN, workdir, "SCENARIO:sleep", SESSION, false, List.of(), null,
                null, null, dir.resolve("state/runs/1/2")));
        awaitChildPid();

        handle.cancel();
        CompletableFuture.supplyAsync(() -> awaitQuietly(handle)).get(10, TimeUnit.SECONDS);

        assertFalse(Files.exists(planted));
        assertTrue(Files.isDirectory(dir.resolve("state/quarantine/1-2/.claude/agents")));
    }

    /** As a sandbox whose outer bwrap dies at once on SIGTERM while a process inside plants something before it is killed. */
    private Confinement plantingLate(Path home, Path planted) {
        return new Confinement(prefixing("planting-late", "sh", "-c",
                "( trap 'sleep 0.5; mkdir -p \"$0\"' TERM; while true; do sleep 0.1; done ) & exec \"$@\"", planted.toString()),
                new SandboxPolicies(home, dir.resolve("state"), List.of()));
    }

    @Test
    void aLoaderPlantedWhileACancelledSandboxDiesIsSweptOnceItIsDead() throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path planted = home.resolve(".claude/agents");
        ClaudeCodeAgent confined = new ClaudeCodeAgent(FakeClaude.install(Files.createDirectories(dir.resolve("late"))).toString(),
                FakeClaude.environment(), Duration.ofSeconds(2), plantingLate(home, planted));
        RunHandle handle = confined.start(new RunRequest(RunKind.PLAN, workdir, "SCENARIO:sleep", SESSION, false, List.of(), null,
                null, null, dir.resolve("state/runs/1/3")));
        awaitChildPid();

        handle.cancel();
        // await() returns once the cancel has ended the whole tree and closed the guard.
        CompletableFuture.supplyAsync(() -> awaitQuietly(handle)).get(10, TimeUnit.SECONDS);

        assertFalse(Files.exists(planted), "planted after the first sweep, swept once the sandbox was dead");
        assertTrue(Files.isDirectory(dir.resolve("state/quarantine/1-3/.claude/agents")));
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

    @Test
    void reviewRunReadsOnlyInAFreshSessionAndAnswersWithTheReviewSchema() throws Exception {
        UUID reviewSession = UUID.randomUUID();
        RunRequest request = new RunRequest(RunKind.REVIEW, workdir, "Review this change", reviewSession, false, List.of(),
                new BigDecimal("1"), "sonnet", null, dir.resolve("runs/1/1.review"));

        agent.start(request).await();

        List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));
        assertTrue(args.containsAll(List.of("--permission-mode", "plan")), args.toString());
        assertEquals("Read,Bash", args.get(args.indexOf("--tools") + 1));
        assertEquals(dispatch.agent.Schemas.REVIEW, args.get(args.indexOf("--json-schema") + 1));
        assertEquals(reviewSession.toString(), args.get(args.indexOf("--session-id") + 1));
        assertFalse(args.contains("--resume"));
    }

    /** What Log printed while {@code action} ran. */
    private static String capturingLog(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setOut(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return logged.toString(StandardCharsets.UTF_8);
    }

    private ClaudeCodeAgent owning(Path config, Path home, Map<String, Path> installed) throws IOException {
        Path claude = FakeClaude.install(Files.createDirectories(dir.resolve("owner")));
        OwnerPluginsFixture.installed(claude, installed);
        return new ClaudeCodeAgent(claude.toString(), FakeClaude.environment(), Duration.ofSeconds(2),
                Confinement.none("no sandbox configured"), OwnerPlugins.instance(config, home));
    }

    @Test
    void listedPluginsAndServersReachTheCommandLineAndAnEditReachesTheNextRun() throws Exception {
        Path plugin = OwnerPluginsFixture.plugin(dir.resolve("cache/playwright"), "{\"playwright\": {\"command\": \"npx\"}}");
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.writeString(home.resolve(".claude.json"), "{\"mcpServers\": {\"mongodb\": {\"command\": \"mongodb-mcp-server\", "
                + "\"env\": {\"MDB_MCP_CONNECTION_STRING\": \"" + FAKE_SECRET + "\"}}}}");
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    command: claude\n"
                + "    plugins: [playwright@claude-plugins-official]\n    mcpServers: [mongodb]\n");
        ClaudeCodeAgent owned = owning(config, home, Map.of("playwright@claude-plugins-official", plugin));

        String logged = capturingLog(() -> awaitQuietly(owned.start(plan("Plan it"))));
        List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));

        assertEquals(plugin.toString(), args.get(args.indexOf("--plugin-dir") + 1));
        assertEquals(plugin.toString(), args.get(args.indexOf("--add-dir") + 1), "plan runs may read its skill files");
        assertTrue(args.get(args.indexOf("--tools") + 1).endsWith(",Skill"), args.toString());
        int mcp = args.indexOf("--mcp-config");
        assertTrue(args.get(mcp + 1).contains("\"plugin_playwright_playwright\"") && args.get(mcp + 1).contains("\"mongodb\""));
        assertTrue(args.get(mcp + 2).startsWith("--"), "a flag ends --mcp-config's values: " + args);
        assertTrue(args.contains("--strict-mcp-config"));
        assertFalse(logged.contains(FAKE_SECRET), "a server's credentials never reach the log");
        assertFalse(String.join(" ", args).contains(FAKE_SECRET), "nor a command line, which any local user can read");
        assertTrue(Files.readString(workdir.resolve("fake-claude.env")).contains(FAKE_SECRET), "they travel in claude's environment");

        Files.writeString(config, "agents:\n  claude-code:\n    command: claude\n");
        awaitQuietly(owned.start(plan("Plan it")));
        List<String> after = Files.readAllLines(workdir.resolve("fake-claude.args"));

        assertFalse(after.contains("--mcp-config") || after.contains("--plugin-dir"), "emptied, no restart: " + after);
        assertFalse(after.get(after.indexOf("--tools") + 1).contains("Skill"), after.toString());
    }

    @Test
    void aSplitLoadsNoneOfTheOwnersPlugins() throws Exception {
        Path plugin = OwnerPluginsFixture.plugin(dir.resolve("cache/playwright"), null);
        Path config = Files.writeString(dir.resolve("dispatch.yaml"),
                "agents:\n  claude-code:\n    plugins: [playwright@claude-plugins-official]\n");
        ClaudeCodeAgent owned = owning(config, dir, Map.of("playwright@claude-plugins-official", plugin));

        awaitQuietly(owned.start(new RunRequest(RunKind.SPLIT, workdir, "fix X, add Y", null, false, List.of(), null, null,
                null, dir.resolve("runs/split/1"))));

        assertFalse(Files.readAllLines(workdir.resolve("fake-claude.args")).contains("--plugin-dir"));
    }

    @Test
    void aListedPluginThatIsNotInstalledFailsTheRunBeforeItsAgentStarts() throws Exception {
        Path config = Files.writeString(dir.resolve("dispatch.yaml"),
                "agents:\n  claude-code:\n    plugins: [frontend-design@claude-plugins-official]\n");
        ClaudeCodeAgent owned = owning(config, dir, Map.of());

        AgentStartException error = assertThrows(AgentStartException.class, () -> owned.start(plan("Plan it")));

        assertTrue(error.getMessage().startsWith("claude-code plugin frontend-design@claude-plugins-official is not installed"));
        assertFalse(Files.exists(workdir.resolve("fake-claude.args")), "the agent never started");
    }

    /** Before the lists, the file was read only at startup: an owner who lists nothing keeps today's runs through a bad save. */
    @Test
    void aConfigThatBreaksWhileNothingIsListedLeavesTheRunAsItWas() throws Exception {
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    command: claude\n");
        ClaudeCodeAgent owned = owning(config, dir, Map.of());
        awaitQuietly(owned.start(plan("Plan it")));
        Files.writeString(config, "agents:\n  claude-code: [\n");

        String logged = capturingLog(() -> awaitQuietly(owned.start(plan("Plan it"))));

        List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));
        assertFalse(args.contains("--mcp-config") || args.contains("--plugin-dir"), args.toString());
        assertTrue(logged.contains("event=agent.owner_lists_unreadable"), logged);
    }
}
