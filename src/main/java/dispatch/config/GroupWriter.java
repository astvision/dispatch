package dispatch.config;

import dispatch.config.ConfigEdit.At;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Links, unlinks and migrates a group's Telegram chat in the config file the instance runs with (ADR 0012, 0014). */
public interface GroupWriter {

    /** @return telegram as the file holds it afterwards */
    Config.Telegram link(long chatId, String title, String project);

    /** @throws ConfigException when the group has no chat to unlink, or is unknown; the config is unchanged */
    Config.Telegram unlink(String groupName);

    /** @return telegram unchanged when no group holds {@code oldChatId} */
    Config.Telegram migrate(long oldChatId, long newChatId);

    /**
     * Edits the config file in place and validates it before replacing it, same locking as {@link ConfigFile#edit}, so a
     * group linked here can never be lost to a concurrent {@code dispatch project add} or a management-page save.
     */
    static GroupWriter file(Path configFile, Map<String, String> environment) {
        return new GroupWriter() {
            @Override
            public Config.Telegram link(long chatId, String title, String project) {
                return ConfigFile.edit(configFile, environment, current ->
                        linkText(current, ConfigFile.parse(configFile, current, environment), chatId, title, project)).telegram();
            }

            @Override
            public Config.Telegram unlink(String groupName) {
                return ConfigFile.edit(configFile, environment, current ->
                        unlinkText(current, ConfigFile.parse(configFile, current, environment), groupName)).telegram();
            }

            @Override
            public Config.Telegram migrate(long oldChatId, long newChatId) {
                return ConfigFile.edit(configFile, environment, current ->
                        migrateText(current, ConfigFile.parse(configFile, current, environment), oldChatId, newChatId)).telegram();
            }
        };
    }

    /**
     * The pure text edit behind {@link #link}, exposed so the Mini App's management API can run it inside its own
     * version-checked save.
     *
     * @throws ConfigException when no group owns {@code project}; the text is returned unchanged otherwise (e.g. the
     *                          project's group already holds this chat)
     */
    static String linkText(String text, Config config, long chatId, String title, String project) {
        Config.Group from = config.telegram().groups().stream()
                .filter(group -> group.projects().contains(project))
                .findFirst()
                .orElseThrow(() -> new ConfigException("no project " + project + " in any group"));
        Config.Group already = config.telegram().groups().stream()
                .filter(group -> Long.valueOf(chatId).equals(group.chatId()))
                .findFirst()
                .orElse(null);
        if (already != null) {
            if (already.name().equals(from.name())) {
                return text;
            }
            // A chatless group left with nothing to hold has no reason to survive; one still linked to its own chat is
            // never emptied here, same as the addGroup branch below: that is refused by validation instead.
            String edited = from.chatId() == null && from.projects().size() == 1
                    ? ConfigEdit.remove(text, At.of("telegram", "groups").item("name", from.name()))
                    : ConfigEdit.remove(text, At.of("telegram", "groups").item("name", from.name()).key("projects").value(project));
            return ConfigEdit.append(edited, At.of("telegram", "groups").item("name", already.name()).key("projects"), project);
        }
        if (from.chatId() == null && from.projects().size() == 1) {
            return ConfigEdit.set(text, At.of("telegram", "groups").item("name", from.name()).key("chatId"), chatId);
        }
        String edited = ConfigEdit.remove(text, At.of("telegram", "groups").item("name", from.name()).key("projects").value(project));
        return ConfigText.addGroup(edited, uniqueName(slug(title), config), chatId, from.members(), project);
    }

    /**
     * The pure text edit behind {@link #unlink}.
     *
     * @throws ConfigException when {@code groupName} is unknown, or has no chat to unlink
     */
    static String unlinkText(String text, Config config, String groupName) {
        Config.Group group = config.telegram().groups().stream()
                .filter(candidate -> candidate.name().equals(groupName))
                .findFirst()
                .orElseThrow(() -> new ConfigException("no group named '" + groupName + "' in the config"));
        if (group.chatId() == null) {
            throw new ConfigException(groupName + " has no group chat to unlink");
        }
        return ConfigEdit.remove(text, At.of("telegram", "groups").item("name", groupName).key("chatId"));
    }

    /**
     * The pure text edit behind {@link #migrate}.
     *
     * @return the text unchanged when no group holds {@code oldChatId}, e.g. a migration this config was never told about
     */
    static String migrateText(String text, Config config, long oldChatId, long newChatId) {
        Config.Group group = config.telegram().groups().stream()
                .filter(candidate -> Long.valueOf(oldChatId).equals(candidate.chatId()))
                .findFirst()
                .orElse(null);
        if (group == null) {
            return text;
        }
        return ConfigEdit.set(text, At.of("telegram", "groups").item("name", group.name()).key("chatId"), newChatId);
    }

    /** {@code name} suffixed with -2, -3, … until no group in {@code config} already has it. */
    private static String uniqueName(String name, Config config) {
        List<String> taken = config.telegram().groups().stream().map(Config.Group::name).toList();
        if (!taken.contains(name)) {
            return name;
        }
        for (int i = 2; ; i++) {
            String candidate = name + "-" + i;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * A group name from a chat's title, e.g. "acme-backend"; "group" when nothing usable is left. Copied from
     * {@link dispatch.cli.Setup#teamName}'s rule rather than importing {@code dispatch.cli} into {@code dispatch.config}.
     */
    private static String slug(String title) {
        String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "group" : slug;
    }
}
