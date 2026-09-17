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
            new Config.Group("backend", -100, List.of(new Config.Member(1, "Bold"), new Config.Member(2, "Ali")), List.of("alm", "crm")),
            new Config.Group("mobile", -200, List.of(new Config.Member(1, "Bold"), new Config.Member(3, "Sara")), List.of("life"))));

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
        assertEquals("telegram:-200", groups.chatOfProject("life"));
        assertTrue(groups.isMemberOfProjectGroup("telegram:3", "life"));
        assertFalse(groups.isMemberOfProjectGroup("telegram:3", "alm"));
    }
}
