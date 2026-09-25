package dispatch.agent.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.AgentStartException;
import dispatch.agent.RunRequest;
import dispatch.domain.RunKind;
import dispatch.testing.FakeAgents;
import dispatch.testing.FakeClaude;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake codex CLI is a POSIX shell script")
class CodexAgentTest {

    private static final UUID SESSION = UUID.fromString("0b9d2c1e-7a53-4b5e-9d1a-2f6a8e4c3b21");
    private static final String THREAD = "01a0d798-5aab-74d2-b4c8-a8c6ea63d8c1";

    @TempDir
    Path dir;

    private Path workdir;
    private CodexAgent agent;

    @BeforeEach
    void setUp() throws IOException {
        workdir = Files.createDirectories(dir.resolve("worktree"));
        agent = new CodexAgent(FakeAgents.install(dir, "codex").toString(), FakeClaude.environment(), Duration.ofSeconds(2),
                dir.resolve("state/agent-sessions/codex"));
    }

    @Test
    void aPlanRunIsReadOnlyAnswersInTheSchemaAndRemembersItsThread() throws Exception {
        AgentResult result = agent.start(request(RunKind.PLAN, "Plan the login timeout fix", false, "gpt-5-codex", "high")).await();

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
        assertEquals("u", dispatch.Json.read(result.structuredOutput()).path("understanding").asText());
        assertEquals("Plan the login timeout fix", Files.readString(workdir.resolve("fake-codex.prompt")), "on stdin");
        List<String> args = args();
        assertEquals(List.of("exec", "--json"), args.subList(0, 2));
        assertEquals("sandbox_mode=\"read-only\"", valueAfter(args, "sandbox_mode"));
        assertEquals("approval_policy=\"never\"", valueAfter(args, "approval_policy"), "nobody is there to approve");
        assertTrue(args.contains("--ignore-user-config"), "the same run on every computer, as Claude's --setting-sources");
        assertEquals("gpt-5-codex", args.get(args.indexOf("-m") + 1));
        assertEquals("model_reasoning_effort=\"high\"", valueAfter(args, "model_reasoning_effort"));
        String schema = Files.readString(Path.of(args.get(args.indexOf("--output-schema") + 1)));
        assertTrue(schema.contains("\"understanding\""), schema);
        assertFalse(schema.contains("maxLength"), "strict schemas refuse length limits; Plan.parse enforces them");
        assertEquals("-", args.getLast(), "the prompt comes from stdin");
        assertEquals(THREAD, Files.readString(dir.resolve("state/agent-sessions/codex/" + SESSION)).strip());
        String env = Files.readString(workdir.resolve("fake-codex.env"));
        assertFalse(env.contains("TELEGRAM_BOT_TOKEN") || env.contains("GH_TOKEN") || env.contains("DISPATCH_WORKER_KEY"), env);
    }

    @Test
    void aCorrectionResumesTheThreadItsSessionStarted() throws Exception {
        agent.start(request(RunKind.PLAN, "Plan the login timeout fix", false, null, null)).await();

        AgentResult result = agent.start(request(RunKind.PLAN, "Also cover the mobile login", true, null, null)).await();

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
        List<String> args = args();
        assertEquals(List.of("exec", "resume", "--json"), args.subList(0, 3));
        assertEquals(List.of(THREAD, "-"), args.subList(args.size() - 2, args.size()));
        assertEquals("sandbox_mode=\"read-only\"", valueAfter(args, "sandbox_mode"), "resume takes no --sandbox, so -c sets it");
    }

    @Test
    void resumingASessionThatNeverRanHereFailsToStartWithTheReason() {
        AgentStartException error = assertThrows(AgentStartException.class,
                () -> agent.start(request(RunKind.EXECUTE, "Continue", true, null, null)));

        assertEquals("no Codex thread is recorded here for session " + SESSION
                + "; it started on another computer or its record was removed", error.getMessage());
    }

    @Test
    void anExecutionRunHasTheSameAccessAsTheUserAndNoSchema() throws Exception {
        AgentResult result = agent.start(request(RunKind.EXECUTE, "Implement the approved plan", false, null, null)).await();

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
        assertEquals("done", result.summary());
        List<String> args = args();
        assertEquals("sandbox_mode=\"danger-full-access\"", valueAfter(args, "sandbox_mode"));
        assertFalse(args.contains("--output-schema"));
        assertFalse(args.contains("-m"), "Codex's own default model");
    }

    @Test
    void aFailedTurnIsReportedWithCodexsReason() throws Exception {
        AgentResult result = agent.start(request(RunKind.PLAN, "SCENARIO:fail", false, null, null)).await();

        assertEquals(AgentOutcome.FAILED, result.outcome());
        assertEquals("model overloaded", result.error());
    }

    @Test
    void splittingAndTheAssistantStayOnClaudeCode() {
        assertThrows(IllegalArgumentException.class, () -> agent.start(request(RunKind.SPLIT, "Split this", false, null, null)));
        assertThrows(IllegalArgumentException.class, () -> agent.start(request(RunKind.ASSISTANT, "Hi", false, null, null)));
    }

    private RunRequest request(RunKind kind, String prompt, boolean resume, String model, String effort) {
        return new RunRequest(kind, workdir, prompt, SESSION, resume, List.of(), new BigDecimal("2"), model, effort,
                dir.resolve("runs/1/" + (resume ? 2 : 1)));
    }

    private List<String> args() throws IOException {
        return Files.readAllLines(workdir.resolve("fake-codex.args"));
    }

    /** The value of the "-c key=value" override whose value starts with {@code key}. */
    private static String valueAfter(List<String> args, String key) {
        for (int i = 0; i + 1 < args.size(); i++) {
            if (args.get(i).equals("-c") && args.get(i + 1).startsWith(key + "=")) {
                return args.get(i + 1);
            }
        }
        return null;
    }
}
