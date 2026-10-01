package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.Agent;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.RunRequest;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.domain.Phase;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.domain.Task;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs real Claude Code with the dispatch plugin, Dispatch's execution prompt and its skill note, in a scratch repository
 * with a bug to fix (spec: agent skills). Off unless DISPATCH_LIVE_CLAUDE=1, since it spends the account's quota;
 * DISPATCH_LIVE_DIR keeps the run log there.
 */
class LiveSkillsTest {

    @TempDir
    Path dir;

    @Test
    @EnabledIfEnvironmentVariable(named = "DISPATCH_LIVE_CLAUDE", matches = "1")
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void anExecutionWithSkillsInvokesTestDrivenDevelopmentAndFixesTheBug() throws Exception {
        Path plugin = dir.resolve("state/plugins/dispatch");
        SkillsPlugin.install(plugin);
        Path repo = scratchRepository();
        Path logs = System.getenv("DISPATCH_LIVE_DIR") != null ? Path.of(System.getenv("DISPATCH_LIVE_DIR"), "skills")
                : dir.resolve("runs");
        Task task = task("calc.py: add(2, 3) returns -1 instead of 5. Fix add().");
        String plan = "{\"understanding\":\"add() subtracts\",\"findings\":[\"calc.py: add returns a - b\"],"
                + "\"steps\":[\"Write a failing unittest for add(2, 3) in test_calc.py\",\"Make add return a + b\"],"
                + "\"risks\":[],\"questions\":[],\"decisions\":[]}";
        Agent claude = new ClaudeCodeAgent("claude", System.getenv(), Duration.ofSeconds(10));

        AgentResult done = claude.start(new RunRequest(RunKind.EXECUTE, repo,
                Prompts.execute(task, plan) + Prompts.SkillNote.EXECUTE.after(), UUID.randomUUID(), false, List.of(), null,
                "sonnet", null, logs.resolve("1"), Map.of(), List.of(plugin))).await();

        System.out.println("LIVE skills: outcome=" + done.outcome() + " model=" + done.model() + " cost=" + done.costUsd()
                + " error=" + done.error() + "\n  summary: " + done.summary());
        assertEquals(AgentOutcome.SUCCEEDED, done.outcome(), done.error());
        String stream = Files.readString(Path.of(logs.resolve("1") + ".jsonl"));
        assertTrue(stream.contains("\"skill\":\"dispatch:test-driven-development\""), "the execution invoked the TDD skill");
        assertEquals("5", python(repo, "from calc import add; print(add(2, 3))").strip());
    }

    private Path scratchRepository() throws Exception {
        Path repo = Files.createDirectories(dir.resolve("worktree"));
        Files.writeString(repo.resolve("calc.py"), "def add(a, b):\n    return a - b\n");
        Files.writeString(repo.resolve("README.md"), "# Calc\n\nA tiny calculator module. Tests: python3 -m unittest\n");
        git(repo, "init", "-q");
        git(repo, "add", "-A");
        // An isolated scratch repository: a throwaway identity for this one commit, not the user's own.
        git(repo, "-c", "user.name=Dispatch test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "init");
        return repo;
    }

    private static Task task(String description) {
        Instant now = Instant.now();
        return new Task(1, "calc", "Fix add()", description, Phase.EXECUTING, Priority.NORMAL,
                new Requester("telegram:100", "Bold"), "telegram:100/1", "telegram:100", UUID.randomUUID(), null, "main",
                null, null, null, null, null, null, null, now, null, null, now, null);
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
