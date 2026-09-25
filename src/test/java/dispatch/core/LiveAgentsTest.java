package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.Agent;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.RunRequest;
import dispatch.agent.codex.CodexAgent;
import dispatch.agent.gemini.GeminiAgent;
import dispatch.domain.Phase;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.Run;
import dispatch.domain.RunCause;
import dispatch.domain.RunKind;
import dispatch.domain.RunStatus;
import dispatch.domain.Task;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs a real agent CLI through its adapter with Dispatch's own prompts, in a scratch repository: a plan, a correction that
 * resumes the planning session, and the execution of the revised plan (ADR 0026). Off unless DISPATCH_LIVE_GEMINI=1 or
 * DISPATCH_LIVE_CODEX=1, since each spends the account's quota; DISPATCH_LIVE_DIR keeps the run logs there.
 */
class LiveAgentsTest {

    @TempDir
    Path dir;

    @Test
    @EnabledIfEnvironmentVariable(named = "DISPATCH_LIVE_GEMINI", matches = "1")
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void geminiPlansTakesACorrectionAndImplementsTheFix() throws Exception {
        scenario(new GeminiAgent("gemini", System.getenv(), Duration.ofSeconds(10)), "gemini");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DISPATCH_LIVE_CODEX", matches = "1")
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void codexPlansTakesACorrectionAndImplementsTheFix() throws Exception {
        scenario(new CodexAgent("codex", System.getenv(), Duration.ofSeconds(10), dir.resolve("state/agent-sessions/codex")), "codex");
    }

    private void scenario(Agent agent, String name) throws Exception {
        Path repo = scratchRepository();
        Path logs = System.getenv("DISPATCH_LIVE_DIR") != null ? Path.of(System.getenv("DISPATCH_LIVE_DIR"), name) : dir.resolve("runs");
        Task task = task("calc.py: add(2, 3) returns -1 instead of 5. Fix add().");

        AgentResult plan = agent.start(new RunRequest(RunKind.PLAN, repo, Prompts.plan(task), task.sessionId(), false, List.of(),
                null, null, null, logs.resolve("1"))).await();
        report("plan", plan);
        assertEquals(AgentOutcome.SUCCEEDED, plan.outcome(), plan.error());
        assertFalse(Plan.parse(plan.structuredOutput()).steps().isEmpty(), plan.structuredOutput());
        assertEquals("", git(repo, "status", "--porcelain"), "planning is read-only");

        Run correction = new Run(1, 2, RunKind.PLAN, RunCause.CORRECTION, RunStatus.RUNNING,
                "Also add test_calc.py with a unit test for add(2, 3) using unittest.", "telegram:100", "Bold",
                null, null, null, null, null, null, null, null);
        AgentResult revised = agent.start(new RunRequest(RunKind.PLAN, repo, Prompts.correction(task, correction), task.sessionId(),
                true, List.of(), null, null, null, logs.resolve("2"))).await();
        report("correction", revised);
        assertEquals(AgentOutcome.SUCCEEDED, revised.outcome(), revised.error());
        assertFalse(Plan.parse(revised.structuredOutput()).steps().isEmpty(), revised.structuredOutput());
        assertEquals("", git(repo, "status", "--porcelain"), "a correction is read-only too");

        AgentResult done = agent.start(new RunRequest(RunKind.EXECUTE, repo, Prompts.execute(task, revised.structuredOutput()),
                UUID.randomUUID(), false, List.of(), null, null, null, logs.resolve("3"))).await();
        report("execution", done);
        assertEquals(AgentOutcome.SUCCEEDED, done.outcome(), done.error());
        String calc = Files.readString(repo.resolve("calc.py"));
        assertTrue(calc.contains("a + b"), calc);
        assertEquals("1", git(repo, "rev-list", "--count", "HEAD").strip(), "Dispatch commits, not the agent");
        assertEquals("5", python(repo, "from calc import add; print(add(2, 3))").strip());
    }

    private Path scratchRepository() throws Exception {
        Path repo = Files.createDirectories(dir.resolve("worktree"));
        Files.writeString(repo.resolve("calc.py"), "def add(a, b):\n    return a - b\n");
        Files.writeString(repo.resolve("README.md"), "# Calc\n\nA tiny calculator module.\n");
        git(repo, "init", "-q");
        git(repo, "add", "-A");
        // An isolated scratch repository: a throwaway identity for this one commit, not the user's own.
        git(repo, "-c", "user.name=Dispatch test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "init");
        return repo;
    }

    private static Task task(String description) {
        Instant now = Instant.now();
        return new Task(1, "calc", "Fix add()", description, Phase.PLANNING, Priority.NORMAL,
                new Requester("telegram:100", "Bold"), "telegram:100/1", "telegram:100", UUID.randomUUID(), null, "main",
                null, null, null, null, null, null, null, now, null, null, now);
    }

    private static void report(String step, AgentResult result) {
        System.out.println("LIVE " + step + ": outcome=" + result.outcome() + " session=" + result.sessionId()
                + " model=" + result.model() + " error=" + result.error() + "\n  summary: " + result.summary());
    }

    private static String git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        return run(command);
    }

    private static String python(Path repo, String code) throws Exception {
        return run(List.of("python3", "-c", "import sys; sys.path.insert(0, '" + repo + "'); " + code));
    }

    private static String run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
        }
        return output;
    }
}
