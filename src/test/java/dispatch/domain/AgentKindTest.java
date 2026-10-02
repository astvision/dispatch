package dispatch.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentKindTest {

    @Test
    void theKindsAreTheNamesConfigAndJobsCarry() {
        assertEquals(List.of("claude-code", "codex", "gemini"), AgentKind.ids());
        assertEquals(AgentKind.CODEX, AgentKind.of("codex"));
        assertEquals("gemini", AgentKind.GEMINI.id());
    }

    @Test
    void aProjectThatNamesNoAgentRunsOnClaudeCode() {
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.of(null));
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.find(null).orElseThrow());
    }

    @Test
    void anUnknownNameIsRefusedByName() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> AgentKind.of("cursor"));

        assertEquals("unsupported agent type: cursor", refused.getMessage());
        assertTrue(AgentKind.find("cursor").isEmpty());
    }

    @Test
    void onlyClaudeCodeLoadsPluginsTeleportsAndTakesAComputersOwnModel() {
        assertTrue(AgentKind.CLAUDE_CODE.loadsPlugins() && AgentKind.CLAUDE_CODE.teleports()
                && AgentKind.CLAUDE_CODE.takesAComputersOwnModel());
        for (AgentKind other : List.of(AgentKind.CODEX, AgentKind.GEMINI)) {
            assertFalse(other.loadsPlugins() || other.teleports() || other.takesAComputersOwnModel(), other.id());
        }
    }

    @Test
    void eachKindsCommandWhenNoneIsConfigured() {
        assertEquals(List.of("claude", "codex", "gemini"),
                List.of(AgentKind.CLAUDE_CODE.defaultCommand(), AgentKind.CODEX.defaultCommand(), AgentKind.GEMINI.defaultCommand()));
    }
}
