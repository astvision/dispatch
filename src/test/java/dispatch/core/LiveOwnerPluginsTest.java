package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.AgentStartException;
import dispatch.agent.ProcessRun;
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
 * Real Claude Code in the real sandbox with the owner's Playwright plugin listed (spec: owner plugins): its server is
 * connected and the agent uses one of its tools. Off unless DISPATCH_LIVE_CLAUDE=1, since it spends the account's quota.
 */
class LiveOwnerPluginsTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "DISPATCH_LIVE_CLAUDE", matches = "1")
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void aListedPluginsServerRunsInTheRealSandbox() throws Exception {
        Sandbox sandbox = Sandboxes.detect(SandboxSetting.AUTO, Probe.system(System.getenv()));
        assumeTrue(sandbox instanceof Bubblewrap, "needs bwrap");
        Path root = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "live-owner");
        Path stateDir = Files.createDirectories(root.resolve("state"));
        Path workdir = Files.createDirectories(root.resolve("work"));
        Path home = Path.of(System.getProperty("user.home"));
        Path config = Files.writeString(root.resolve("dispatch.yaml"),
                "agents:\n  claude-code:\n    command: claude\n    plugins: [playwright@claude-plugins-official]\n");
        OwnerPlugins owner = OwnerPlugins.instance(config, home);
        try {
            owner.resolve("claude", ProcessRun.agentEnvironment(System.getenv()));
        } catch (AgentStartException e) {
            assumeTrue(false, "the playwright plugin is not installed here: " + e.getMessage());
        }
        ClaudeCodeAgent claude = new ClaudeCodeAgent("claude", System.getenv(), Duration.ofSeconds(10),
                new Confinement(sandbox, new SandboxPolicies(home, stateDir, List.of(stateDir))), owner);
        Path logBase = stateDir.resolve("runs/1/1");

        AgentResult result = claude.start(new RunRequest(RunKind.EXECUTE, workdir,
                "Use the Playwright MCP server's browser_navigate tool to open about:blank, then reply with just: done",
                UUID.randomUUID(), false, List.of(), null, "sonnet", null, logBase)).await();

        JsonNode init = Files.readAllLines(Path.of(logBase + ".jsonl")).stream().map(Json::read)
                .filter(event -> event.path("subtype").asText().equals("init")).findFirst().orElseThrow();
        System.out.println("LIVE owner plugins: " + result.outcome() + " (" + result.summary() + ") mcp_servers="
                + init.path("mcp_servers"));
        assertEquals(AgentOutcome.SUCCEEDED, result.outcome(), result.error());
        boolean connected = false;
        for (JsonNode server : init.path("mcp_servers")) {
            connected |= server.path("name").asText().equals("plugin_playwright_playwright")
                    && server.path("status").asText().equals("connected");
        }
        assertTrue(connected, init.path("mcp_servers").toString());
    }
}
