package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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

    /** Every skill an agent reads is adapted: none sends it to a person, nor names the upstream plugin's skills. */
    @Test
    void noBundledSkillAsksForAPersonOrNamesTheUpstreamPlugin() {
        List<String> skills = SkillsPlugin.files().stream().filter(file -> file.startsWith("skills/")).toList();
        assertTrue(skills.size() >= 5, skills.toString());
        for (String file : skills) {
            String text = BundledFiles.read(SkillsPlugin.RESOURCES + "/" + file).toLowerCase(Locale.ROOT);
            for (String phrase : List.of("human partner", "your partner", "ask the user", "superpowers:")) {
                assertFalse(text.contains(phrase), file + " still says '" + phrase + "'");
            }
        }
    }

    @Test
    void eachSkillIsNamedAfterItsDirectory() {
        List<String> skills = SkillsPlugin.files().stream().filter(file -> file.endsWith("/SKILL.md")).toList();
        assertEquals(List.of("skills/test-driven-development/SKILL.md", "skills/systematic-debugging/SKILL.md",
                "skills/verification-before-completion/SKILL.md", "skills/receiving-code-review/SKILL.md",
                "skills/code-reviewer/SKILL.md"), skills);
        for (String file : skills) {
            String name = file.substring("skills/".length(), file.length() - "/SKILL.md".length());
            String text = BundledFiles.read(SkillsPlugin.RESOURCES + "/" + file);
            assertTrue(text.startsWith("---\nname: " + name + "\ndescription: "), file);
        }
    }
}
