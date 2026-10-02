package dispatch.agent.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.RunRequest;
import dispatch.domain.RunKind;
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
}
