package dispatch.agent.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentStartException;
import dispatch.testing.FakeClaude;
import dispatch.testing.OwnerPluginsFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class OwnerPluginsTest {

    @TempDir
    Path dir;

    @Test
    void anInstanceReadsItsListsUnderClaudeCodeAgainForEachRun() throws IOException {
        Path config = Files.writeString(dir.resolve("dispatch.yaml"),
                "agents:\n  claude-code:\n    command: claude\n    plugins: [a@m]\n    mcpServers: [mongodb]\n");
        OwnerPlugins owner = OwnerPlugins.instance(config, dir);
        assertEquals(new OwnerPlugins.Lists(List.of("a@m"), List.of("mongodb")), owner.lists());

        Files.writeString(config, "agents:\n  claude-code:\n    command: claude\n    plugins: [a@m, b@m]\n");

        assertEquals(new OwnerPlugins.Lists(List.of("a@m", "b@m"), List.of()), owner.lists(), "read again, no restart");
    }

    @Test
    void aWorkerReadsItsOwnKeys() throws IOException {
        Path config = Files.writeString(dir.resolve("worker.yaml"),
                "team: https://team.example.com\nname: ann\nclaudePlugins: [a@m]\nclaudeMcpServers: [mongodb]\n");

        assertEquals(new OwnerPlugins.Lists(List.of("a@m"), List.of("mongodb")), OwnerPlugins.worker(config, dir).lists());
    }

    @Test
    void absentListsAndNoneAreEmpty() throws IOException {
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    command: claude\n");

        assertTrue(OwnerPlugins.instance(config, dir).lists().isEmpty());
        assertTrue(OwnerPlugins.NONE.lists().isEmpty());
    }

    @Test
    void aFileThatBreaksWhileItListsSomethingFailsTheRunNamingIt() throws IOException {
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    plugins: [a@m]\n");
        OwnerPlugins owner = OwnerPlugins.instance(config, dir);
        owner.lists();
        Files.writeString(config, "agents:\n  claude-code: [\n");

        AgentStartException error = assertThrows(AgentStartException.class, owner::lists);

        assertTrue(error.getMessage().startsWith("cannot read the plugin lists from " + config + ": "), error.getMessage());
    }

    @Test
    void aListThatIsNotNamesIsRefused() throws IOException {
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    plugins: frontend\n");

        AgentStartException error = assertThrows(AgentStartException.class, () -> OwnerPlugins.instance(config, dir).lists());

        assertEquals("agents.claude-code.plugins in " + config + " must be a list of names", error.getMessage());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void listedPluginsResolveToTheirDirectoriesAndEveryServerIntoOneLine() throws IOException {
        Path claude = FakeClaude.install(Files.createDirectories(dir.resolve("bin")));
        Path playwright = OwnerPluginsFixture.plugin(dir.resolve("cache/playwright"),
                "{\"playwright\": {\"command\": \"npx\", \"args\": [\"@playwright/mcp@latest\"]}}");
        Path mongo = OwnerPluginsFixture.plugin(dir.resolve("cache/mongodb"), null);
        Files.writeString(mongo.resolve(".claude-plugin/plugin.json"), "{\"name\": \"mongodb\", \"mcpServers\": \"./mcp.json\"}");
        Files.writeString(mongo.resolve("mcp.json"), "{\"mcpServers\": {\"mongodb\": {\"command\": \"${CLAUDE_PLUGIN_ROOT}/bin/server\"}}}");
        OwnerPluginsFixture.installed(claude, Map.of("playwright@claude-plugins-official", playwright,
                "mongodb@claude-plugins-official", mongo));
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.writeString(home.resolve(".claude.json"),
                "{\"mcpServers\": {\"mongodb\": {\"command\": \"mongodb-mcp-server\"}, \"unlisted\": {\"command\": \"x\"}}}");
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n"
                + "    plugins: [playwright@claude-plugins-official, mongodb@claude-plugins-official]\n    mcpServers: [mongodb]\n");

        OwnerPlugins.Resolved resolved = OwnerPlugins.instance(config, home).resolve(claude.toString(), FakeClaude.environment());

        assertEquals(List.of(playwright, mongo), resolved.pluginDirs());
        assertFalse(resolved.mcpConfig().contains("\n"), "one line, for the command line");
        JsonNode servers = Json.MAPPER.readTree(resolved.mcpConfig()).get("mcpServers");
        Set<String> names = new HashSet<>();
        servers.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("plugin_playwright_playwright", "plugin_mongodb_mongodb", "mongodb"), names, "unlisted stays out");
        assertEquals(mongo + "/bin/server", servers.get("plugin_mongodb_mongodb").get("command").asText());
        assertEquals("mongodb-mcp-server", servers.get("mongodb").get("command").asText());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aListedPluginThatIsNotInstalledSaysWhatToDo() throws IOException {
        Path claude = FakeClaude.install(Files.createDirectories(dir.resolve("bin")));
        OwnerPluginsFixture.installed(claude, Map.of());
        Path config = Files.writeString(dir.resolve("dispatch.yaml"),
                "agents:\n  claude-code:\n    plugins: [frontend-design@claude-plugins-official]\n");

        AgentStartException error = assertThrows(AgentStartException.class,
                () -> OwnerPlugins.instance(config, dir).resolve(claude.toString(), FakeClaude.environment()));

        assertEquals("claude-code plugin frontend-design@claude-plugins-official is not installed on this computer: install it "
                + "(claude plugin install frontend-design@claude-plugins-official) or remove it from agents.claude-code.plugins in "
                + config, error.getMessage());
    }

    @Test
    void aListedServerThatIsNotDefinedSaysWhatToDo() throws IOException {
        Path config = Files.writeString(dir.resolve("worker.yaml"), "claudeMcpServers: [mongodb]\n");

        AgentStartException error = assertThrows(AgentStartException.class,
                () -> OwnerPlugins.worker(config, dir).resolve("claude", Map.of()));

        assertEquals("claude-code MCP server mongodb is not defined on this computer: add it (claude mcp add --scope user "
                + "mongodb ...) or remove it from claudeMcpServers in " + config, error.getMessage());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void twoServersWithOneNameFailTheRun() throws IOException {
        Path claude = FakeClaude.install(Files.createDirectories(dir.resolve("bin")));
        Path playwright = OwnerPluginsFixture.plugin(dir.resolve("cache/playwright"), "{\"playwright\": {\"command\": \"npx\"}}");
        OwnerPluginsFixture.installed(claude, Map.of("playwright@m", playwright));
        Files.writeString(dir.resolve(".claude.json"), "{\"mcpServers\": {\"plugin_playwright_playwright\": {\"command\": \"x\"}}}");
        Path config = Files.writeString(dir.resolve("dispatch.yaml"),
                "agents:\n  claude-code:\n    plugins: [playwright@m]\n    mcpServers: [plugin_playwright_playwright]\n");

        AgentStartException error = assertThrows(AgentStartException.class,
                () -> OwnerPlugins.instance(config, dir).resolve(claude.toString(), FakeClaude.environment()));

        assertEquals("two MCP servers are named plugin_playwright_playwright; rename one of them", error.getMessage());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aClaudeCodeThatCannotListItsPluginsFailsTheRunWithItsError() throws IOException {
        Path claude = Files.writeString(dir.resolve("claude"), "#!/bin/sh\necho 'unknown command: plugin' >&2\nexit 1\n");
        claude.toFile().setExecutable(true);
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    plugins: [a@m]\n");

        AgentStartException error = assertThrows(AgentStartException.class, () -> OwnerPlugins.instance(config, dir)
                .resolve(claude.toString(), Map.of("PATH", System.getenv("PATH"))));

        assertEquals("cannot list Claude Code plugins (claude plugin list --json): unknown command: plugin", error.getMessage());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aPluginListThatHangsFailsTheRunInsteadOfWaitingForever() throws IOException {
        Path claude = Files.writeString(dir.resolve("claude"), "#!/bin/sh\nexec sleep 30\n");
        claude.toFile().setExecutable(true);
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    plugins: [a@m]\n");
        long start = System.nanoTime();

        AgentStartException error = assertThrows(AgentStartException.class, () -> OwnerPlugins.instance(config, dir)
                .withListTimeout(Duration.ofSeconds(1)).resolve(claude.toString(), Map.of("PATH", System.getenv("PATH"))));

        assertEquals("claude plugin list --json did not answer within 1 s", error.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
    }

    @Test
    void noListsResolveToNothing() throws IOException {
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    command: claude\n");

        assertEquals(OwnerPlugins.Resolved.NONE, OwnerPlugins.instance(config, dir).resolve("claude", Map.of()));
    }

    /** Off the command line (SECURITY.md): Claude Code expands each ${VAR} from its own environment (probed on 2.1.287). */
    @Test
    void aServersEnvAndHeadersValuesTravelInTheEnvironmentAndTheConfigOnlyNamesThem() throws IOException {
        Files.writeString(dir.resolve(".claude.json"), "{\"mcpServers\": {"
                + "\"mongodb\": {\"command\": \"mongodb-mcp-server\", \"env\": {\"MDB_MCP_CONNECTION_STRING\": \"fake-connection\", "
                + "\"CACHE\": \"${HOME}/.cache\"}},"
                + "\"remote\": {\"type\": \"http\", \"url\": \"https://mcp.example.com\", \"headers\": {\"X-Api-Key\": \"fake-key\"}}}}");
        Path config = Files.writeString(dir.resolve("worker.yaml"), "claudeMcpServers: [mongodb, remote]\n");

        OwnerPlugins.Resolved resolved = OwnerPlugins.worker(config, dir).resolve("claude", Map.of());

        assertFalse(resolved.mcpConfig().contains("fake-connection") || resolved.mcpConfig().contains("fake-key"),
                resolved.mcpConfig());
        JsonNode servers = Json.MAPPER.readTree(resolved.mcpConfig()).get("mcpServers");
        assertEquals("fake-connection", resolved.environment().get(variable(servers.get("mongodb").get("env").get("MDB_MCP_CONNECTION_STRING"))));
        assertEquals("fake-key", resolved.environment().get(variable(servers.get("remote").get("headers").get("X-Api-Key"))));
        assertEquals("${HOME}/.cache", servers.get("mongodb").get("env").get("CACHE").asText(), "a reference stays as written");
        assertEquals(2, resolved.environment().size(), resolved.environment().toString());
    }

    /** The NAME in a {@code ${NAME}} reference. */
    private static String variable(JsonNode reference) {
        String text = reference.asText();
        assertTrue(text.startsWith("${") && text.endsWith("}"), text);
        return text.substring(2, text.length() - 1);
    }
}
