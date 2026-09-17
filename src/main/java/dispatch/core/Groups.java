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

    /**
     * The chat of the group that owns {@code project}, which config validation guarantees exists; empty when that group has
     * no chat (ADR 0014).
     */
    public Optional<String> chatOfProject(String project) {
        Config.Group owner = groups.stream().filter(group -> group.projects().contains(project)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("project " + project + " belongs to no group"));
        return Optional.ofNullable(owner.chatId()).map(Groups::chatRef);
    }

    public boolean isMemberOfProjectGroup(String requesterRef, String project) {
        return groups.stream().anyMatch(group -> group.projects().contains(project) && contains(group, requesterRef));
    }

    /** Names of the groups {@code requesterRef} is in, in config order. */
    public List<String> groupsOfMember(String requesterRef) {
        return groups.stream().filter(group -> contains(group, requesterRef)).map(Config.Group::name).toList();
    }

    public Optional<String> groupOfChat(String chatRef) {
        return byChat(chatRef).map(Config.Group::name);
    }

    public Set<String> projectsOfGroup(String name) {
        return groups.stream().filter(group -> group.name().equals(name)).findFirst()
                .map(group -> Set.copyOf(group.projects())).orElse(Set.of());
    }

    public List<Config.Group> all() {
        return groups;
    }

    private Optional<Config.Group> byChat(String chatRef) {
        return groups.stream().filter(group -> group.chatId() != null && chatRef(group.chatId()).equals(chatRef)).findFirst();
    }

    private static boolean contains(Config.Group group, String requesterRef) {
        return group.members().stream().anyMatch(member -> ("telegram:" + member.id()).equals(requesterRef));
    }

    private static String chatRef(long chatId) {
        return "telegram:" + chatId;
    }
}
