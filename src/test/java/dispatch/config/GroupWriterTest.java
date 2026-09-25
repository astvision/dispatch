package dispatch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** Telegram titles go to 128 characters; the group name must stay within the loader's 40, suffix included. */
    @Test
    void aLongTitleYieldsAGroupNameWithinTheLimitEvenWithASuffix() throws IOException {
        addSecondProject("crm");
        addSecondProject("erp");
        String title = "Acme backend and infrastructure team chat for the whole year";   // 60 characters
        GroupWriter writer = GroupWriter.file(file, ENV);
        writer.link(-1001L, title, "alm");

        Config.Telegram telegram = writer.link(-1002L, title, "crm");

        List<String> names = telegram.groups().stream().map(Config.Group::name).toList();
        assertEquals(3, names.size(), names.toString());
        names.forEach(name -> assertTrue(name.length() <= 40 && !name.contains("--"), name));
        assertTrue(names.get(2).endsWith("-2"), names.toString());
    }

    /** The loader refuses group names that differ only in case, so the suffix must be chosen the same way. */
    @Test
    void aTitleClashingWithAGroupNameInAnotherCaseGetsADistinctName() throws IOException {
        addSecondProject("crm");
        Files.writeString(file, Files.readString(file).replace("- name: bold", "- name: Note"));

        Config.Telegram telegram = GroupWriter.file(file, ENV).link(-4883391545L, "note", "alm");

        assertEquals(List.of("Note", "note-2"), telegram.groups().stream().map(Config.Group::name).toList());
    }

    /** ADR 0025: a project may have several group chats; linking another one adds it and keeps the first. */
    @Test
    void linkingALinkedProjectToASecondChatAddsAGroupAndKeepsTheFirst() throws IOException {
        GroupWriter writer = GroupWriter.file(file, ENV);
        writer.link(-4883391545L, "note", "alm");

        Config.Telegram telegram = writer.link(-1002L, "ТӨБЗГ (Local)", "alm");

        Config.Member bold = new Config.Member(123456789, "Bold");
        assertEquals(List.of(new Config.Group("bold", -4883391545L, List.of(bold), List.of("alm")),
                new Config.Group("tubzg-local", -1002L, List.of(bold), List.of("alm"))), telegram.groups());
        assertEquals(telegram, ConfigLoader.load(file, ENV).telegram(), "the file loads as written");
    }

    /** dispatch init marks a personal group as having no chat; once it has one, that comment would lie. */
    @Test
    void linkingInPlaceDropsInitsNoGroupChatComment() throws IOException {
        Files.writeString(file, Files.readString(file)
                .replace("- name: bold\n", "- name: bold     # a personal bot: no group chat, everything stays private\n"));

        GroupWriter.file(file, ENV).link(-4883391545L, "note", "alm");

        String text = Files.readString(file);
        assertFalse(text.contains("no group chat"), text);
        assertTrue(text.contains("- name: bold\n"), text);
        assertTrue(text.contains("# A personal instance's config"), "other comments stay");
    }

    /** Mongolian and Russian titles would otherwise slug to nothing and every group would be "group", "group-2", … */
    @Test
    void aCyrillicTitleIsTransliteratedIntoAReadableName() throws IOException {
        addSecondProject("crm");

        Config.Telegram telegram = GroupWriter.file(file, ENV).link(-4883391545L, "Тэмдэглэл баг", "alm");

        assertEquals(List.of("bold", "temdeglel-bag"), telegram.groups().stream().map(Config.Group::name).toList());
    }

    @Test
    void anUnknownProjectLeavesTheFileAsItWas() throws IOException {
        String before = Files.readString(file);
        assertThrows(GroupWriter.UnknownProject.class, () -> GroupWriter.file(file, ENV).link(-1L, "x", "nope"));
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
