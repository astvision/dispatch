package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillsPluginTest {

    @TempDir
    Path dir;

    @Test
    void installReplacesAnOlderCopyWholly() throws IOException {
        Path plugin = dir.resolve("plugins/dispatch");
        Files.createDirectories(plugin.resolve("skills/dropped-skill"));
        Files.writeString(plugin.resolve("skills/dropped-skill/SKILL.md"), "an older Dispatch's skill");
        Files.createDirectories(plugin.resolve(".claude-plugin"));
        Files.writeString(plugin.resolve(".claude-plugin/plugin.json"), "{}");

        SkillsPlugin.install(plugin);

        for (String file : SkillsPlugin.files()) {
            assertTrue(Files.isRegularFile(plugin.resolve(file)), file);
        }
        assertFalse(Files.exists(plugin.resolve("skills/dropped-skill")), "a skill no longer shipped is gone");
        assertTrue(Files.readString(plugin.resolve(".claude-plugin/plugin.json")).contains("\"name\": \"dispatch\""));
    }
}
