package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Real Claude Code in the real sandbox with the owner's Playwright plugin listed (spec: owner plugins): its browser runs
 * headless there, and the agent tells a page's colour from a screenshot, since the page holds nothing else. Off unless
 * DISPATCH_LIVE_CLAUDE=1, since it spends the account's quota.
 */
class LiveOwnerPluginsTest {

    private static final String[][] COLOURS = {{"red", "#ff0000"}, {"green", "#00b000"}, {"blue", "#0000ff"},
            {"yellow", "#ffff00"}, {"purple", "#800080"}, {"orange", "#ff8000"}};

    @Test
    @EnabledIfEnvironmentVariable(named = "DISPATCH_LIVE_CLAUDE", matches = "1")
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void theAgentSeesAPageThroughAListedPluginsBrowserInTheRealSandbox() throws Exception {
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
        String[] colour = COLOURS[ThreadLocalRandom.current().nextInt(COLOURS.length)];
        HttpServer page = colourPage(colour[1]);
        try {
            ClaudeCodeAgent claude = new ClaudeCodeAgent("claude", System.getenv(), Duration.ofSeconds(10),
                    new Confinement(sandbox, new SandboxPolicies(home, stateDir, List.of(stateDir))), owner);
            Path logBase = stateDir.resolve("runs/1/1");

            AgentResult result = claude.start(new RunRequest(RunKind.EXECUTE, workdir,
                    "Open http://127.0.0.1:" + page.getAddress().getPort() + "/ with the Playwright MCP server's "
                            + "browser_navigate tool, then take a screenshot with its browser_take_screenshot tool. Do not "
                            + "fetch the page any other way. Reply with just the name of the page's background colour.",
                    UUID.randomUUID(), false, List.of(), null, "sonnet", null, logBase)).await();

            List<JsonNode> events = Files.readAllLines(Path.of(logBase + ".jsonl")).stream().map(Json::read).toList();
            JsonNode servers = events.stream().filter(event -> event.path("subtype").asText().equals("init")).findFirst()
                    .orElseThrow().path("mcp_servers");
            System.out.println("LIVE owner plugins: " + result.outcome() + " (" + result.summary() + "), expected " + colour[0]
                    + ", mcp_servers=" + servers);
            assertEquals(AgentOutcome.SUCCEEDED, result.outcome(), result.error());
            assertTrue(servers.toString().contains("{\"name\":\"plugin_playwright_playwright\",\"status\":\"connected\""),
                    servers.toString());
            assertTrue(events.stream().anyMatch(LiveOwnerPluginsTest::returnsAnImage), "a screenshot reached the agent");
            assertTrue(result.summary().toLowerCase(Locale.ROOT).contains(colour[0]), result.summary());
        } finally {
            page.stop(0);
        }
    }

    /** A plan run, the strictest: the owner's skill loads, and a file beside it reads through the link (spec: owner plugins). */
    @Test
    @EnabledIfEnvironmentVariable(named = "DISPATCH_LIVE_CLAUDE", matches = "1")
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void aListedSkillLoadsAndItsOwnFilesReadInAPlanRunInTheRealSandbox() throws Exception {
        Sandbox sandbox = Sandboxes.detect(SandboxSetting.AUTO, Probe.system(System.getenv()));
        assumeTrue(sandbox instanceof Bubblewrap, "needs bwrap");
        Path home = Path.of(System.getProperty("user.home"));
        assumeTrue(Files.isRegularFile(home.resolve(".claude/skills/graphify/references/query.md")), "needs the graphify skill");
        Path root = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "live-skills");
        Path stateDir = Files.createDirectories(root.resolve("state"));
        Path workdir = Files.createDirectories(root.resolve("work"));
        Path config = Files.writeString(root.resolve("dispatch.yaml"),
                "agents:\n  claude-code:\n    command: claude\n    skills: [graphify]\n");
        ClaudeCodeAgent claude = new ClaudeCodeAgent("claude", System.getenv(), Duration.ofSeconds(10),
                new Confinement(sandbox, new SandboxPolicies(home, stateDir, List.of(stateDir))), OwnerPlugins.instance(config, home));
        Path logBase = stateDir.resolve("runs/1/1");

        AgentResult result = claude.start(new RunRequest(RunKind.PLAN, workdir,
                "Load the owner-skills:graphify skill with the Skill tool. Then use the Read tool on the file references/query.md "
                        + "in that skill's directory, and plan nothing else: your plan is that file's first heading.",
                UUID.randomUUID(), false, List.of(), null, "sonnet", null, logBase)).await();

        List<JsonNode> events = Files.readAllLines(Path.of(logBase + ".jsonl")).stream().map(Json::read).toList();
        System.out.println("LIVE owner skills: " + result.outcome() + " " + result.structuredOutput());
        assertEquals(AgentOutcome.SUCCEEDED, result.outcome(), result.error());
        assertTrue(succeeded(events, "Skill", "owner-skills:graphify"), "the skill loaded");
        assertTrue(succeeded(events, "Read", "references/query.md"), "a file beside it read through the link");
    }

    /** Whether a call of {@code tool} whose input mentions {@code mentioning} returned without an error. */
    private static boolean succeeded(List<JsonNode> events, String tool, String mentioning) {
        java.util.Set<String> calls = new java.util.HashSet<>();
        for (JsonNode event : events) {
            for (JsonNode block : event.path("message").path("content")) {
                if (block.path("type").asText().equals("tool_use") && block.path("name").asText().equals(tool)
                        && block.path("input").toString().contains(mentioning)) {
                    calls.add(block.path("id").asText());
                }
                if (block.path("type").asText().equals("tool_result") && calls.contains(block.path("tool_use_id").asText())
                        && !block.path("is_error").asBoolean()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A page that is nothing but its background colour. */
    private static HttpServer colourPage(String colour) throws Exception {
        byte[] body = ("<!doctype html><body style=\"margin:0;height:100vh;background:" + colour + "\"></body>")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static boolean returnsAnImage(JsonNode event) {
        for (JsonNode block : event.path("message").path("content")) {
            for (JsonNode part : block.path("content")) {
                if (block.path("type").asText().equals("tool_result") && part.path("type").asText().equals("image")) {
                    return true;
                }
            }
        }
        return false;
    }
}
