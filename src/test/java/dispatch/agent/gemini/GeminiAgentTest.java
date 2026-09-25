package dispatch.agent.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
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

@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake gemini CLI is a POSIX shell script")
class GeminiAgentTest {

    private static final UUID SESSION = UUID.fromString("0b9d2c1e-7a53-4b5e-9d1a-2f6a8e4c3b21");

    @TempDir
    Path dir;

    private Path workdir;
    private GeminiAgent agent;

    @BeforeEach
    void setUp() throws IOException {
        workdir = Files.createDirectories(dir.resolve("worktree"));
        agent = new GeminiAgent(FakeAgents.install(dir, "gemini").toString(), FakeClaude.environment(), Duration.ofSeconds(2));
    }

    @Test
    void aPlanRunCannotWriteAndAnswersInJsonForTheSessionDispatchNamed() throws Exception {
        Path attachments = Files.createDirectories(dir.resolve("attachments"));
        AgentResult result = agent.start(new RunRequest(RunKind.PLAN, workdir, "Plan the login timeout fix", SESSION, false,
                List.of(attachments), new BigDecimal("2"), "gemini-2.5-pro", null, dir.resolve("runs/1/1"))).await();

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
        assertEquals("u", dispatch.Json.read(result.structuredOutput()).path("understanding").asText());
        assertEquals(SESSION.toString(), result.sessionId());
        assertEquals("Plan the login timeout fix", Files.readString(workdir.resolve("fake-gemini.prompt")), "on stdin");
        List<String> args = args();
        assertEquals(List.of("--output-format", "stream-json"), args.subList(0, 2));
        assertEquals("default", after(args, "--approval-mode"), "headless default mode denies edits and shell commands");
        assertTrue(args.contains("--skip-trust"), "an untrusted folder would quietly drop yolo back to default");
        assertEquals(SESSION.toString(), after(args, "--session-id"));
        assertEquals("gemini-2.5-pro", after(args, "-m"));
        assertEquals(attachments.toString(), after(args, "--include-directories"));
        assertTrue(after(args, "-p").contains("JSON Schema") && after(args, "-p").contains("\"understanding\""),
                "the schema travels in the prompt: Gemini CLI has no schema flag");
        String env = Files.readString(workdir.resolve("fake-gemini.env"));
        assertFalse(env.contains("TELEGRAM_BOT_TOKEN") || env.contains("GH_TOKEN") || env.contains("DISPATCH_WORKER_KEY"), env);
    }

    @Test
    void anExecutionRunApprovesEverythingAndACorrectionResumesTheSameSession() throws Exception {
        AgentResult result = agent.start(new RunRequest(RunKind.EXECUTE, workdir, "Implement the approved plan", SESSION, true,
                List.of(), new BigDecimal("10"), null, null, dir.resolve("runs/1/2"))).await();

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
        assertEquals("done", result.summary());
        List<String> args = args();
        assertEquals("yolo", after(args, "--approval-mode"));
        assertEquals(SESSION.toString(), after(args, "--resume"));
        assertFalse(args.contains("--session-id"));
        assertFalse(args.contains("-m"), "Gemini CLI's own default model");
        assertFalse(after(args, "-p").contains("JSON Schema"));
    }

    @Test
    void aFailedRunIsReportedWithGeminisReason() throws Exception {
        AgentResult result = agent.start(new RunRequest(RunKind.PLAN, workdir, "SCENARIO:fail", SESSION, false, List.of(),
                null, null, null, dir.resolve("runs/1/1"))).await();

        assertEquals(AgentOutcome.FAILED, result.outcome());
        assertEquals("model overloaded", result.error());
    }

    @Test
    void splittingAndTheAssistantStayOnClaudeCode() {
        assertThrows(IllegalArgumentException.class, () -> agent.start(new RunRequest(RunKind.SPLIT, workdir, "Split", null, false,
                List.of(), null, null, null, dir.resolve("splits/1"))));
    }

    private List<String> args() throws IOException {
        return Files.readAllLines(workdir.resolve("fake-gemini.args"));
    }

    private static String after(List<String> args, String flag) {
        int at = args.indexOf(flag);
        return at < 0 || at + 1 >= args.size() ? null : args.get(at + 1);
    }
}
