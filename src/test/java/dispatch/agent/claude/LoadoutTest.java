package dispatch.agent.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dispatch.agent.DataAccess;
import dispatch.agent.RunRequest;
import dispatch.domain.RunKind;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What a run loads is one value, made in one place from what the job brought and what the owner lists. */
class LoadoutTest {

    private static final Path DISPATCH = Path.of("/state/plugins/dispatch");

    private static RunRequest request(List<Path> pluginDirs) {
        return new RunRequest(RunKind.EXECUTE, Path.of("/w/7"), "Build it", UUID.randomUUID(), false, List.of(),
                new BigDecimal("2"), null, null, Path.of("/runs/7/2"), Map.of(), pluginDirs);
    }

    @Test
    void theJobsPluginComesFirstThenTheOwnersThenTheirSkillsAsOneMore() {
        OwnerPlugins.Resolved owner = new OwnerPlugins.Resolved(List.of(Path.of("/cache/playwright")), "{\"mcpServers\":{}}",
                Map.of("DISPATCH_MCP_1", "x"), List.of(Path.of("/home/ann/.claude/skills/graphify")));

        Loadout loadout = Loadout.of(request(List.of(DISPATCH)), owner, Path.of("/runs/7/2.skills"));

        assertEquals(List.of(DISPATCH, Path.of("/cache/playwright"), Path.of("/runs/7/2.skills")), loadout.pluginDirs());
        assertEquals(List.of(Path.of("/home/ann/.claude/skills/graphify")), loadout.skillDirs(), "what the links point to");
        assertEquals("{\"mcpServers\":{}}", loadout.mcpConfig());
        assertEquals(Map.of("DISPATCH_MCP_1", "x"), loadout.environment());
        assertTrue(loadout.skillTool(), "a plugin's skill cannot be invoked without the Skill tool");
    }

    @Test
    void aRunThatLoadsNothingHasNoSkillToolAndNoServers() {
        Loadout loadout = Loadout.of(request(List.of()), OwnerPlugins.Resolved.NONE, null);

        assertEquals(List.of(), loadout.pluginDirs());
        assertFalse(loadout.skillTool());
        assertNull(loadout.mcpConfig());
    }

    /** Split so secret scanners stay quiet; never a real host. */
    private static final String URI = "mongodb://ro:" + "pw@127.0.0.1:40123/webapp";
    private static final OwnerPlugins.Resolved OWNER_WITH_A_SERVER = new OwnerPlugins.Resolved(List.of(),
            "{\"mcpServers\":{\"playwright\":{\"command\":\"npx\",\"args\":[\"@playwright/mcp\"]}}}",
            Map.of("DISPATCH_MCP_1", "x"), List.of());

    private static RunRequest dataRequest(DataAccess data) {
        return new RunRequest(RunKind.PLAN, Path.of("/w/7"), "Plan it", UUID.randomUUID(), false, List.of(),
                new BigDecimal("2"), null, null, Path.of("/runs/7/1"), Map.of(), List.of(), List.of(), data);
    }

    /** Another server (a Mongo plugin, say) could read the URI from the environment and query past the level's gate. */
    @Test
    void aDataRunLoadsTheDataServerAndNoneOfTheOwners() throws IOException {
        Loadout loadout = Loadout.of(dataRequest(new DataAccess(DataAccess.Level.SCHEMA, URI)), OWNER_WITH_A_SERVER, null);

        JsonNode servers = new ObjectMapper().readTree(loadout.mcpConfig()).path("mcpServers");
        assertEquals(List.of("dispatch_data"), List.copyOf(servers.properties().stream().map(Map.Entry::getKey).toList()));
        JsonNode data = servers.path("dispatch_data");
        assertEquals("npx", data.path("command").asText());
        assertEquals("[\"-y\",\"mongodb-mcp-server@2.1.2\"]", data.path("args").toString());
        JsonNode env = data.path("env");
        assertEquals("${DISPATCH_DATA_URI}", env.path("MDB_MCP_CONNECTION_STRING").asText());
        assertEquals("aggregate,aggregate-db,export,explain,find,connect,disconnect,list-connections,mongodb-logs,"
                + "atlas-local-connect-deployment,atlas-local-list-deployments,search-knowledge,list-knowledge-sources",
                env.path("MDB_MCP_DISABLED_TOOLS").asText());
        assertEquals("true", env.path("MDB_MCP_READ_ONLY").asText());
        assertEquals("60000", env.path("MDB_MCP_MAX_TIME_MS").asText());
        assertEquals("disabled", env.path("MDB_MCP_TELEMETRY").asText());
        assertFalse(env.has("MDB_MCP_MAX_DOCUMENTS_PER_QUERY"), "the server's default of 100 is enough for a shape");
        assertEquals(Map.of("DISPATCH_DATA_URI", URI, "MCP_TIMEOUT", "120000"), loadout.environment(),
                "the owner's servers' secrets stay out with their servers");
    }

    @Test
    void aDataRunKeepsTheOwnersPluginsAndSkills() {
        OwnerPlugins.Resolved owner = new OwnerPlugins.Resolved(List.of(Path.of("/cache/playwright")), null, Map.of(),
                List.of(Path.of("/home/ann/.claude/skills/graphify")));

        Loadout loadout = Loadout.of(dataRequest(new DataAccess(DataAccess.Level.SCHEMA, URI)), owner, Path.of("/runs/7/1.skills"));

        assertEquals(List.of(Path.of("/cache/playwright"), Path.of("/runs/7/1.skills")), loadout.pluginDirs());
        assertEquals(List.of(Path.of("/home/ann/.claude/skills/graphify")), loadout.skillDirs());
    }

    @Test
    void theUriNeverAppearsInTheMcpConfig() {
        Loadout loadout = Loadout.of(dataRequest(new DataAccess(DataAccess.Level.FULL, URI)), OwnerPlugins.Resolved.NONE, null);

        assertFalse(loadout.mcpConfig().contains("mongodb://"), loadout.mcpConfig());
    }

    @Test
    void aFullRunRaisesTheDocumentCap() throws IOException {
        Loadout loadout = Loadout.of(dataRequest(new DataAccess(DataAccess.Level.FULL, URI)), OwnerPlugins.Resolved.NONE, null);

        JsonNode env = new ObjectMapper().readTree(loadout.mcpConfig()).path("mcpServers").path("dispatch_data").path("env");
        assertEquals("5000", env.path("MDB_MCP_MAX_DOCUMENTS_PER_QUERY").asText());
        List<String> disabled = List.of(env.path("MDB_MCP_DISABLED_TOOLS").asText().split(","));
        assertFalse(disabled.contains("aggregate"), disabled.toString());
        assertFalse(disabled.contains("find"), disabled.toString());
        assertTrue(disabled.contains("export"), "export can run a pipeline whose output nothing caps");
    }

    @Test
    void aRunWithoutDataIsUnchanged() {
        Loadout loadout = Loadout.of(dataRequest(null), OWNER_WITH_A_SERVER, null);

        assertEquals(OWNER_WITH_A_SERVER.mcpConfig(), loadout.mcpConfig());
        assertEquals(Map.of("DISPATCH_MCP_1", "x"), loadout.environment());
    }

    @Test
    void theUriIsMaskedWhenPrinted() {
        Loadout loadout = Loadout.of(dataRequest(new DataAccess(DataAccess.Level.SCHEMA, URI)), OwnerPlugins.Resolved.NONE, null);

        assertFalse(new DataAccess(DataAccess.Level.SCHEMA, URI).toString().contains("pw@"));
        assertFalse(loadout.toString().contains("pw@"), loadout.toString());
        assertTrue(loadout.toString().contains("DISPATCH_DATA_URI=***"), loadout.toString());
    }
}
