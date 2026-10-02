package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.RunRequest;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.agent.claude.OwnerPlugins;
import dispatch.agent.sandbox.Bubblewrap;
import dispatch.agent.sandbox.Confinement;
import dispatch.agent.sandbox.Probe;
import dispatch.agent.sandbox.Sandbox;
import dispatch.agent.sandbox.SandboxPolicies;
import dispatch.agent.sandbox.SandboxSetting;
import dispatch.agent.sandbox.Sandboxes;
import dispatch.domain.Phase;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.domain.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Real Claude Code in the real sandbox: a question about Dispatch's own sources comes back as an answer (spec: answers). */
class LiveAnswersTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "DISPATCH_LIVE_CLAUDE", matches = "1")
    void aQuestionAboutTheseSourcesIsAnsweredByItsPlanRun() throws Exception {
        Sandbox sandbox = Sandboxes.detect(SandboxSetting.AUTO, Probe.system(System.getenv()));
        assumeTrue(sandbox instanceof Bubblewrap, "needs bwrap");
        Path home = Path.of(System.getProperty("user.home"));
        Path root = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "live-answers");
        Path stateDir = Files.createDirectories(root.resolve("state"));
        // A plain copy of the domain sources: the sandbox refuses a worktree whose .git names a clone it was not given.
        Path repo = Files.createDirectories(root.resolve("work"));
        try (var sources = Files.list(Path.of("src/main/java/dispatch/domain"))) {
            for (Path source : sources.toList()) {
                Files.copy(source, repo.resolve(source.getFileName()));
            }
        }
        ClaudeCodeAgent claude = new ClaudeCodeAgent("claude", System.getenv(), Duration.ofSeconds(10),
                new Confinement(sandbox, new SandboxPolicies(home, stateDir, List.of(stateDir))), OwnerPlugins.NONE);
        Instant now = Instant.now();
        Task task = new Task(1, "dispatch", "Which class parses a plan?",
                "Which class parses a plan's JSON in these sources? Just tell me; change nothing.", Phase.PLANNING,
                Priority.NORMAL, new Requester("telegram:100", "Bold"), "telegram:100/1", "telegram:100", UUID.randomUUID(),
                null, "main", null, null, null, null, null, null, null, now, null, null, now, null);

        AgentResult result = claude.start(new RunRequest(RunKind.PLAN, repo, Prompts.plan(task), UUID.randomUUID(), false,
                List.of(), null, "sonnet", null, stateDir.resolve("runs/1/1"))).await();

        System.out.println("LIVE answers: " + result.outcome() + " " + result.structuredOutput());
        assertEquals(AgentOutcome.SUCCEEDED, result.outcome(), result.error());
        Plan plan = Plan.parse(result.structuredOutput());
        assertEquals(Plan.Result.ANSWER, plan.result());
        assertTrue(plan.answer().contains("Plan.java") || plan.answer().contains("Plan.parse"), plan.answer());
    }
}
