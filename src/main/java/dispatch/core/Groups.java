package dispatch.core;

import dispatch.config.Config;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The configured groups: who belongs to which, and which projects each owns (ADR 0012), and the admins who let people join
 * (ADR 0015). A member sees and acts on the projects of every group they are in; a group chat sees only its own. Members and
 * chats are configured by Telegram id, the only channel so far. Joining replaces the whole set while Dispatch runs.
 */
public final class Groups {

    private volatile Config.Telegram telegram;

    public Groups(List<Config.Group> groups) {
        this(new Config.Telegram(List.of(), groups));
    }

    public Groups(Config.Telegram telegram) {
        replace(telegram);
    }

    /** Takes the groups and admins as the config now has them; later calls see them at once. */
    public void replace(Config.Telegram telegram) {
        this.telegram = new Config.Telegram(List.copyOf(telegram.admins()), List.copyOf(telegram.groups()));
    }

    public boolean isAdmin(String requesterRef) {
        return admins().contains(requesterRef);
    }

    /** The admins' references, in config order. */
    public List<String> admins() {
        return telegram.admins().stream().map(Groups::chatRef).toList();
    }

    public boolean isMember(String requesterRef) {
        return telegram.groups().stream().anyMatch(group -> contains(group, requesterRef));
    }

    /** The member's name as configured (the first group listing them wins), empty for someone who is not a member. */
    public Optional<String> memberName(String requesterRef) {
        return telegram.groups().stream().flatMap(group -> group.members().stream())
                .filter(member -> ("telegram:" + member.id()).equals(requesterRef)).map(Config.Member::name).findFirst();
    }

    public Set<String> projectsOfMember(String requesterRef) {
        Set<String> projects = new HashSet<>();
        telegram.groups().stream().filter(group -> contains(group, requesterRef)).forEach(group -> projects.addAll(group.projects()));
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
        Config.Group owner = telegram.groups().stream().filter(group -> group.projects().contains(project)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("project " + project + " belongs to no group"));
        return Optional.ofNullable(owner.chatId()).map(Groups::chatRef);
    }

    public boolean isMemberOfProjectGroup(String requesterRef, String project) {
        return telegram.groups().stream().anyMatch(group -> group.projects().contains(project) && contains(group, requesterRef));
    }

    /** Names of the groups {@code requesterRef} is in, in config order. */
    public List<String> groupsOfMember(String requesterRef) {
        return telegram.groups().stream().filter(group -> contains(group, requesterRef)).map(Config.Group::name).toList();
    }

    public Optional<String> groupOfChat(String chatRef) {
        return byChat(chatRef).map(Config.Group::name);
    }

    public Set<String> projectsOfGroup(String name) {
        return telegram.groups().stream().filter(group -> group.name().equals(name)).findFirst()
                .map(group -> Set.copyOf(group.projects())).orElse(Set.of());
    }

    public List<Config.Group> all() {
        return telegram.groups();
    }

    /** One member and no admins: a personal bot (ADR 0014). */
    public boolean isPersonal() {
        return admins().isEmpty() && all().stream().flatMap(group -> group.members().stream())
                .mapToLong(Config.Member::id).distinct().count() == 1;
    }

    /** Who may change this Dispatch's setup from Telegram: an admin, or a personal bot's one member. */
    public boolean mayManage(String requesterRef) {
        return isAdmin(requesterRef) || (isPersonal() && isMember(requesterRef));
    }

    private Optional<Config.Group> byChat(String chatRef) {
        return telegram.groups().stream().filter(group -> group.chatId() != null && chatRef(group.chatId()).equals(chatRef)).findFirst();
    }

    private static boolean contains(Config.Group group, String requesterRef) {
        return group.members().stream().anyMatch(member -> ("telegram:" + member.id()).equals(requesterRef));
    }

    private static String chatRef(long chatId) {
        return "telegram:" + chatId;
    }
}
