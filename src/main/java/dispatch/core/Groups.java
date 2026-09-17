package dispatch.core;

import dispatch.config.Config;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The configured groups: who belongs to which, and which projects each owns (ADR 0012). A member sees and acts on the
 * projects of every group they are in; a group chat sees only its own. Members and chats are configured by Telegram id,
 * the only channel so far.
 */
public final class Groups {

    private final List<Config.Group> groups;

    public Groups(List<Config.Group> groups) {
        this.groups = List.copyOf(groups);
    }

    public boolean isMember(String requesterRef) {
        return groups.stream().anyMatch(group -> contains(group, requesterRef));
    }

    public Set<String> projectsOfMember(String requesterRef) {
        Set<String> projects = new HashSet<>();
        groups.stream().filter(group -> contains(group, requesterRef)).forEach(group -> projects.addAll(group.projects()));
        return Set.copyOf(projects);
    }

    public boolean isGroupChat(String chatRef) {
        return byChat(chatRef).isPresent();
    }

    public Set<String> projectsOfChat(String chatRef) {
        return byChat(chatRef).map(group -> Set.copyOf(group.projects())).orElse(Set.of());
    }

    /** The chat of the group that owns {@code project}; config validation guarantees there is exactly one. */
    public String chatOfProject(String project) {
        return groups.stream().filter(group -> group.projects().contains(project)).findFirst()
                .map(group -> chatRef(group.chatId()))
                .orElseThrow(() -> new IllegalArgumentException("project " + project + " belongs to no group"));
    }

    public boolean isMemberOfProjectGroup(String requesterRef, String project) {
        return groups.stream().anyMatch(group -> group.projects().contains(project) && contains(group, requesterRef));
    }

    public List<Config.Group> all() {
        return groups;
    }

    private Optional<Config.Group> byChat(String chatRef) {
        return groups.stream().filter(group -> chatRef(group.chatId()).equals(chatRef)).findFirst();
    }

    private static boolean contains(Config.Group group, String requesterRef) {
        return group.members().stream().anyMatch(member -> ("telegram:" + member.id()).equals(requesterRef));
    }

    private static String chatRef(long chatId) {
        return "telegram:" + chatId;
    }
}
