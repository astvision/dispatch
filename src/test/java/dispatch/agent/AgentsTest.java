package dispatch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.agent.codex.CodexAgent;
import dispatch.agent.gemini.GeminiAgent;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentsTest {

    private static final Path STATE = Path.of("/var/lib/dispatch/demo");

    @Test
    void eachConfiguredTypeGetsItsOwnAdapter() {
        Map<String, String> commands = new LinkedHashMap<>();
        commands.put("claude-code", "claude");
        commands.put("codex", "codex");
        commands.put("gemini", "/opt/gemini/bin/gemini");

        Map<String, Agent> agents = Agents.create(commands, Map.of(), STATE);

        assertEquals(3, agents.size());
        assertInstanceOf(ClaudeCodeAgent.class, agents.get("claude-code"));
        assertInstanceOf(CodexAgent.class, agents.get("codex"));
        assertInstanceOf(GeminiAgent.class, agents.get("gemini"));
    }

    @Test
    void onlyTheConfiguredOnesExist() {
        assertEquals(Map.of(), Agents.create(Map.of(), Map.of(), STATE));
        assertEquals(1, Agents.create(Map.of("codex", "codex"), Map.of(), STATE).size());
    }

    @Test
    void anUnknownTypeIsAProgrammingError() {
        assertThrows(IllegalArgumentException.class, () -> Agents.create(Map.of("aider", "aider"), Map.of(), STATE));
    }
}
