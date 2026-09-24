package dispatch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GroupWriterTest {
    private static final Map<String, String> ENV = Map.of("TELEGRAM_BOT_TOKEN", "123456789" + ":AAH-fake-token-for-tests-only-0123456789");
    @TempDir Path dir;
    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        file = dir.resolve("dispatch.yaml");
        Files.writeString(file, new String(GroupWriterTest.class.getResourceAsStream("/personal.yaml").readAllBytes())
                .replace("STATE_DIR", dir.resolve("state").toString().replace("'", "''"))
                .replace("CLONE", dir.resolve("work/alm").toString().replace("'", "''")));
    }

    @Test
    void theOnlyProjectOfAChatlessGroupLinksInPlace() throws IOException {
        Config.Telegram telegram = GroupWriter.file(file, ENV).link(-4883391545L, "note", "alm");

        assertEquals(1, telegram.groups().size());
        assertEquals(-4883391545L, telegram.groups().getFirst().chatId());
        assertTrue(Files.readString(file).contains("# A personal instance's config"), "comments stay");
        assertTrue(Files.readString(file).contains("chatId: -4883391545"), "chat id written as a plain integer, not quoted");
        assertNull(ConfigLoader.load(file, ENV).workers(), "still personal: no workers demanded");
    }

    @Test
    void aProjectSharingItsGroupMovesIntoANewGroupForTheChat() throws IOException {
        addSecondProject("crm");   // appends a project "crm" (path CLONE2) to projects and to group bold, by text edit

        Config.Telegram telegram = GroupWriter.file(file, ENV).link(-4883391545L, "Life notes", "alm");

        Config.Group moved = telegram.groups().stream().filter(g -> Long.valueOf(-4883391545L).equals(g.chatId())).findFirst().orElseThrow();
        assertEquals(List.of("alm"), moved.projects());
        assertEquals("life-notes", moved.name());
        assertEquals(List.of(new Config.Member(123456789, "Bold")), moved.members());
        assertEquals(List.of("crm"), telegram.groups().stream().filter(g -> g.name().equals("bold")).findFirst().orElseThrow().projects());
    }

    @Test
    void aSecondProjectForTheSameChatJoinsItsGroup() throws IOException {
        addSecondProject("crm");
        GroupWriter writer = GroupWriter.file(file, ENV);
        writer.link(-4883391545L, "note", "alm");

        Config.Telegram telegram = writer.link(-4883391545L, "note", "crm");

        assertEquals(List.of("alm", "crm"), telegram.groups().stream()
                .filter(g -> Long.valueOf(-4883391545L).equals(g.chatId())).findFirst().orElseThrow().projects());
    }

    @Test
    void unlinkRemovesOnlyTheChatId() {
        GroupWriter writer = GroupWriter.file(file, ENV);
        writer.link(-4883391545L, "note", "alm");

        Config.Telegram telegram = writer.unlink("bold");

        assertNull(telegram.groups().getFirst().chatId());
        assertEquals(List.of("alm"), telegram.groups().getFirst().projects());
    }

    @Test
    void migrateFollowsTheNewChatId() throws IOException {
        GroupWriter writer = GroupWriter.file(file, ENV);
        writer.link(-4883391545L, "note", "alm");

        assertEquals(-1004883391545L, writer.migrate(-4883391545L, -1004883391545L).groups().getFirst().chatId());
        assertTrue(Files.readString(file).contains("chatId: -1004883391545"), "chat id written as a plain integer, not quoted");
    }

    @Test
    void anUnknownProjectLeavesTheFileAsItWas() throws IOException {
        String before = Files.readString(file);
        assertThrows(ConfigException.class, () -> GroupWriter.file(file, ENV).link(-1L, "x", "nope"));
        assertEquals(before, Files.readString(file));
    }

    /** A second project in group bold, so the group holds two. */
    private void addSecondProject(String name) throws IOException {
        String text = ConfigText.addProject(Files.readString(file), "bold", name, List.of(
                "name: " + name, "path: '" + dir.resolve("work/" + name).toString().replace("'", "''") + "'",
                "baseBranch: main", "agent: claude-code"));
        Files.writeString(file, text);
    }
}
