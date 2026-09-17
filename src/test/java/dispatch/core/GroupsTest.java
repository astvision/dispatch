package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.config.Config;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GroupsTest {

    private final Groups groups = new Groups(List.of(
            new Config.Group("backend", -100L, List.of(new Config.Member(1, "Bold"), new Config.Member(2, "Ali")), List.of("alm", "crm")),
            new Config.Group("mobile", -200L, List.of(new Config.Member(1, "Bold"), new Config.Member(3, "Sara")), List.of("life"))));

    @Test
    void groupWithoutAChatHasNoChatToAnnounceIn() {
        Groups personal = new Groups(List.of(new Config.Group("bold", null, List.of(new Config.Member(1, "Bold")), List.of("alm"))));

        assertEquals(java.util.Optional.empty(), personal.chatOfProject("alm"));
        assertEquals(Set.of("alm"), personal.projectsOfMember("telegram:1"));
        assertFalse(personal.isGroupChat("telegram:null"));
        assertEquals(java.util.Optional.of("telegram:-100"), groups.chatOfProject("crm"));
    }

    @Test
    void adminsAreKnownAndMembersCanChangeWhileRunning() {
        Config.Group backend = new Config.Group("backend", -100L, List.of(new Config.Member(1, "Bold")), List.of("alm"));
        Groups live = new Groups(new Config.Telegram(List.of(9L), List.of(backend)));

        assertTrue(live.isAdmin("telegram:9"));
        assertFalse(live.isAdmin("telegram:1"));
        assertFalse(live.isMember("telegram:2"));

        live.replace(new Config.Telegram(List.of(9L), List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(1, "Bold"), new Config.Member(2, "Ali")), List.of("alm")))));

        assertTrue(live.isMember("telegram:2"));
        assertEquals(Set.of("alm"), live.projectsOfMember("telegram:2"));
        assertEquals(List.of("telegram:9"), live.admins());
    }

    @Test
    void memberOfAnyGroupIsAMember() {
        assertTrue(groups.isMember("telegram:1"));
        assertTrue(groups.isMember("telegram:3"));
        assertFalse(groups.isMember("telegram:9"));
    }

    @Test
    void memberSeesTheProjectsOfEveryGroupTheyAreIn() {
        assertEquals(Set.of("alm", "crm", "life"), groups.projectsOfMember("telegram:1"));
        assertEquals(Set.of("life"), groups.projectsOfMember("telegram:3"));
        assertEquals(Set.of(), groups.projectsOfMember("telegram:9"));
    }

    @Test
    void groupChatSeesOnlyItsOwnProjects() {
        assertEquals(Set.of("alm", "crm"), groups.projectsOfChat("telegram:-100"));
        assertTrue(groups.isGroupChat("telegram:-200"));
        assertFalse(groups.isGroupChat("telegram:-999"));
        assertEquals(Set.of(), groups.projectsOfChat("telegram:-999"));
    }

    @Test
    void eachProjectHasTheChatOfItsGroup() {
        assertEquals(java.util.Optional.of("telegram:-200"), groups.chatOfProject("life"));
        assertTrue(groups.isMemberOfProjectGroup("telegram:3", "life"));
        assertFalse(groups.isMemberOfProjectGroup("telegram:3", "alm"));
    }
}
