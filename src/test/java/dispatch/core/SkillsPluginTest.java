package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
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
            for (String phrase : List.of("human partner", "your partner", "ask the user", "ask for help", "superpowers:",
                    "/ponytail", "stop ponytail", "normal mode")) {
                assertFalse(text.contains(phrase), file + " still says '" + phrase + "'");
            }
        }
    }

    @Test
    void eachSkillIsNamedAfterItsDirectory() {
        List<String> skills = SkillsPlugin.files().stream().filter(file -> file.endsWith("/SKILL.md")).toList();
        assertEquals(List.of("skills/test-driven-development/SKILL.md", "skills/systematic-debugging/SKILL.md",
                "skills/verification-before-completion/SKILL.md", "skills/receiving-code-review/SKILL.md",
                "skills/code-reviewer/SKILL.md", "skills/ponytail/SKILL.md"), skills);
        for (String file : skills) {
            String name = file.substring("skills/".length(), file.length() - "/SKILL.md".length());
            String text = BundledFiles.read(SkillsPlugin.RESOURCES + "/" + file);
            assertTrue(text.startsWith("---\nname: " + name + "\ndescription: "), file);
        }
    }

    /** Speed matters (spec: agent skills): a skill must not send the agent to the whole suite, which Dispatch runs itself. */
    @Test
    void theSkillsTestWhatTheChangeCoversNotTheWholeSuite() {
        assertFalse(skill("test-driven-development/SKILL.md").contains("A scope statement in your task bounds the"));
        assertFalse(skill("verification-before-completion/SKILL.md").contains("Partial proves nothing"));
    }

    /** A retry continues an earlier run's work in the same worktree; TDD must not throw that work away. */
    @Test
    void tddKeepsTheWorkAnEarlierRunLeft() {
        assertTrue(skill("test-driven-development/SKILL.md").contains("an earlier run"));
    }

    /** The planner is never asked to allow exceptions, so the run names its own (configuration, documentation, ...). */
    @Test
    void tddNamesItsOwnExceptions() {
        assertFalse(skill("test-driven-development/SKILL.md").contains("approved plan allows"));
    }

    /** The reviewer runs read-only after Dispatch already ran the test command. */
    @Test
    void theReviewerLeavesTheTestsToDispatch() {
        assertTrue(skill("code-reviewer/SKILL.md").contains("Do not run the tests"));
    }

    /** Nobody is there to say "build the full version": the run says in its summary what it left out instead. */
    @Test
    void ponytailLeavesTestsToTddAndWhatItSkippedToTheSummary() {
        String ponytail = skill("ponytail/SKILL.md");

        assertTrue(ponytail.contains("dispatch:test-driven-development"), "tests follow TDD, not ponytail's one check");
        assertTrue(ponytail.contains("in your summary"), "what was left out is reported, not asked about");
    }

    /** A matter of taste must not hold up delivery: over-engineering is reported, never sent back for a fix round. */
    @Test
    void theReviewerListsOverEngineeringAsMinorOnly() {
        assertTrue(skill("code-reviewer/SKILL.md").contains("Over-engineering is always `minor`"));
    }

    @Test
    void eachUpstreamIsCreditedWithItsLicence() {
        assertTrue(SkillsPlugin.files().containsAll(List.of("LICENSE-superpowers", "LICENSE-ponytail")), SkillsPlugin.files().toString());
        String notice = BundledFiles.read(SkillsPlugin.RESOURCES + "/NOTICE.md");
        assertTrue(notice.contains("ponytail 4.9.0") && notice.contains("superpowers 6.4.1"), notice);
    }

    @Test
    void aPluginThatCannotBeWrittenSaysWhy() throws IOException {
        Files.writeString(dir.resolve("plugins"), "a file where the plugins directory should be");

        UncheckedIOException error = assertThrows(UncheckedIOException.class,
                () -> SkillsPlugin.install(dir.resolve("plugins/dispatch")));

        // The path alone, as before, says nothing; the exception's type and reason vary by OS (Linux: Not a directory).
        assertTrue(error.getMessage().contains("Exception: "), error.getMessage());
    }

    private static String skill(String path) {
        return BundledFiles.read(SkillsPlugin.RESOURCES + "/skills/" + path);
    }
}
