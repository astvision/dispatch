package dispatch.agent.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.AgentStartException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
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
    void aFileThatCannotBeParsedFailsTheRunNamingIt() throws IOException {
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code: [\n");

        AgentStartException error = assertThrows(AgentStartException.class, () -> OwnerPlugins.instance(config, dir).lists());

        assertTrue(error.getMessage().startsWith("cannot read the plugin lists from " + config + ": "), error.getMessage());
    }

    @Test
    void aListThatIsNotNamesIsRefused() throws IOException {
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    plugins: frontend\n");

        AgentStartException error = assertThrows(AgentStartException.class, () -> OwnerPlugins.instance(config, dir).lists());

        assertEquals("agents.claude-code.plugins in " + config + " must be a list of names", error.getMessage());
    }
}
