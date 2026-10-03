package dispatch.config;

import dispatch.Text;
import dispatch.config.ConfigEdit.At;
import java.util.List;

/**
 * The edits of dispatch.yaml that must keep something true while they change it: each one carries its cascade and its
 * refusal, so the Mini App, the CLI and group linking cannot disagree about them. Each takes the file's text and the
 * configuration parsed from it, and answers the edited text or refuses with a {@link ConfigException} in the team's words.
 * Field edits with nothing to keep true go through {@link ConfigEdit} directly.
 */
public final class ConfigEdits {

    private ConfigEdits() {
    }

    /**
     * Removes a project and unlinks it from every group that lists it (a project may be in several, ADR 0025). Refused
     * while it is the only project, or the only project of some group, since a group needs one.
     */
    public static String removeProject(String text, Config config, String name) {
        if (config.projects().stream().noneMatch(project -> project.name().equals(name))) {
            throw new ConfigException(Text.of("manage.noProject", name));
        }
        if (config.projects().size() == 1) {
            throw new ConfigException(Text.of("manage.isThe", name));
        }
        List<Config.Group> listing = config.telegram().groups().stream().filter(group -> group.projects().contains(name)).toList();
        if (listing.isEmpty()) {
            throw new ConfigException(Text.of("manage.isNotListed", name));
        }
        for (Config.Group group : listing) {
            if (group.projects().size() == 1) {
                throw new ConfigException(Text.of("manage.isTheOnly", name, group.name()));
            }
        }
        String edited = text;
        for (Config.Group group : listing) {
            edited = ConfigEdit.remove(edited, At.of("telegram", "groups").item("name", group.name()).key("projects").value(name));
        }
        return ConfigEdit.remove(edited, At.of("projects").item("name", name));
    }

    /**
     * Removes a member from a group. Refused while they are its only member. Their admin right goes with them when no other
     * group has them, unless they are the team's only real admin: then the removal is refused.
     */
    public static String removeMember(String text, Config config, String groupName, long id) {
        Config.Group group = config.telegram().groups().stream().filter(candidate -> candidate.name().equals(groupName)).findFirst()
                .orElseThrow(() -> new ConfigException(Text.of("manage.noGroup", groupName)));
        Config.Member member = group.members().stream().filter(candidate -> candidate.id() == id).findFirst()
                .orElseThrow(() -> new ConfigException(Text.of("manage.nobodyWithId", id, groupName)));
        if (group.members().size() == 1) {
            throw new ConfigException(Text.of("manage.isTheOnlyMember", member.name(), groupName));
        }
        boolean memberElsewhere = config.telegram().groups().stream()
                .anyMatch(other -> other != group && other.members().stream().anyMatch(candidate -> candidate.id() == id));
        String edited = ConfigEdit.remove(text, At.of("telegram", "groups").item("name", groupName).key("members").item("id", String.valueOf(id)));
        if (config.telegram().admins().contains(id) && !memberElsewhere) {
            if (realAdminCount(config) == 1) {
                throw new ConfigException(Text.of("manage.isTheTeam", member.name()));
            }
            edited = ConfigEdit.remove(edited, At.of("telegram", "admins").value(String.valueOf(id)));
        }
        return edited;
    }

    /** Admins who actually belong to a group; an id in telegram.admins with no matching member is not a real admin. */
    public static long realAdminCount(Config config) {
        List<Long> memberIds = config.telegram().groups().stream().flatMap(group -> group.members().stream())
                .map(Config.Member::id).toList();
        return config.telegram().admins().stream().filter(memberIds::contains).count();
    }
}
