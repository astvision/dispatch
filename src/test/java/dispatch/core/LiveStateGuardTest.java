package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.RunRequest;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.agent.sandbox.Bubblewrap;
import dispatch.agent.sandbox.Confinement;
import dispatch.agent.sandbox.Probe;
import dispatch.agent.sandbox.Sandbox;
import dispatch.agent.sandbox.SandboxPolicies;
import dispatch.agent.sandbox.SandboxSetting;
import dispatch.agent.sandbox.Sandboxes;
import dispatch.domain.RunKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Real Claude Code in the real sandbox with the owner's own ~/.claude (spec: agent state guard): a file the agent writes
 * under ~/.claude/agents is gone after the run, and a second run still resumes the first one's session, with overlays and
 * without. Off unless
 * DISPATCH_LIVE_CLAUDE=1, since it spends the account's quota.
 */
class LiveStateGuardTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "DISPATCH_LIVE_CLAUDE", matches = "1")
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void aPlantedAgentFileIsGoneAndTheSessionStillResumes() throws Exception {
        Sandbox sandbox = Sandboxes.detect(SandboxSetting.AUTO, Probe.system(System.getenv()));
        assumeTrue(sandbox.copyOnWrite(), "needs bwrap with overlays");

        plantAndResume(sandbox, "the copy-on-write layer took the write");
    }

    /** Bubblewrap older than 0.10: ~/.claude writable but for its loaders and every other project, read-only. */
    @Test
    @EnabledIfEnvironmentVariable(named = "DISPATCH_LIVE_CLAUDE", matches = "1")
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void withoutOverlaysAPlantedAgentFileIsGoneAndTheSessionStillResumes() throws Exception {
        assumeTrue(Sandboxes.detect(SandboxSetting.AUTO, Probe.system(System.getenv())) instanceof Bubblewrap, "needs bwrap");

        plantAndResume(new Bubblewrap("bwrap", false), "read-only when present, quarantined when planted");
    }

    private static void plantAndResume(Sandbox sandbox, String howItIsGone) throws Exception {
        Path root = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "live-guard");
        Path stateDir = Files.createDirectories(root.resolve("state"));
        Path workdir = Files.createDirectories(root.resolve("work"));
        Path home = Path.of(System.getProperty("user.home"));
        Path planted = home.resolve(".claude/agents/dispatch-live-guard-probe.md");
        Files.deleteIfExists(planted);
        Confinement confinement = new Confinement(sandbox, new SandboxPolicies(home, stateDir, List.of(stateDir)));
        ClaudeCodeAgent claude = new ClaudeCodeAgent("claude", System.getenv(), Duration.ofSeconds(10), confinement);
        UUID session = UUID.randomUUID();

        AgentResult first = claude.start(new RunRequest(RunKind.EXECUTE, workdir,
                "Create the file " + planted + " containing the word probe, then reply with just: done", session, false,
                List.of(), null, "sonnet", null, stateDir.resolve("runs/1/1"))).await();
        AgentResult second = claude.start(new RunRequest(RunKind.EXECUTE, workdir,
                "Which file did you create in this session? Reply with its full path only.", session, true,
                List.of(), null, "sonnet", null, stateDir.resolve("runs/1/2"))).await();

        // Claude Code may itself refuse to write under ~/.claude; either way the file must not exist on the host. That a
        // planted file vanishes is proven in BubblewrapSandboxTest; this run proves real Claude works and resumes in the sandbox.
        System.out.println("LIVE guard (" + (sandbox.copyOnWrite() ? "overlay" : "fallback") + "): first=" + first.outcome()
                + " (" + first.summary() + ") second=" + second.outcome() + " says: " + second.summary());
        assertEquals(AgentOutcome.SUCCEEDED, first.outcome(), first.error());
        assertFalse(Files.exists(planted), howItIsGone);
        assertEquals(AgentOutcome.SUCCEEDED, second.outcome(), "resume needs the first run's transcript: " + second.error());
    }
}
