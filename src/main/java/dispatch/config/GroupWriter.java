package dispatch.config;

import dispatch.config.ConfigEdit.At;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Links, unlinks and migrates a group's Telegram chat in the config file the instance runs with (ADR 0012, 0014). */
public interface GroupWriter {

    /** What {@code dispatch init} writes after a personal group's name ({@code Setup}); false once the group has a chat. */
    String NO_CHAT_COMMENT = "# a personal bot: no group chat, everything stays private";

    /** No group owns the project any more, e.g. it was removed after a link prompt listed it. */
    final class UnknownProject extends ConfigException {
        public UnknownProject(String project) {
            super("no project " + project + " in any group");
        }
    }

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
     * The pure text edit behind {@link #link}.
     *
     * @throws UnknownProject when no group owns {@code project}; the text is returned unchanged otherwise (e.g. the
     *                          project's group already holds this chat)
     */
    static String linkText(String text, Config config, long chatId, String title, String project) {
        List<Config.Group> owners = config.telegram().groups().stream().filter(group -> group.projects().contains(project)).toList();
        if (owners.isEmpty()) {
            throw new UnknownProject(project);
        }
        // A chatless group of the project is filled first; otherwise the chat is one more for it (ADR 0025).
        Config.Group from = owners.stream().filter(group -> group.chatId() == null).findFirst().orElse(owners.getFirst());
        Config.Group already = config.telegram().groups().stream()
                .filter(group -> Long.valueOf(chatId).equals(group.chatId()))
                .findFirst()
                .orElse(null);
        if (already != null) {
            if (already.projects().contains(project)) {
                return text;
            }
            // Only a chatless group gives the project up: a linked one keeps announcing it in its own chat. A chatless
            // group left with nothing to hold has no reason to survive.
            String edited = from.chatId() != null ? text
                    : from.projects().size() == 1
                    ? ConfigEdit.remove(text, At.of("telegram", "groups").item("name", from.name()))
                    : ConfigEdit.remove(text, At.of("telegram", "groups").item("name", from.name()).key("projects").value(project));
            return ConfigEdit.append(edited, At.of("telegram", "groups").item("name", already.name()).key("projects"), project);
        }
        if (from.chatId() != null) {
            return ConfigText.addGroup(text, uniqueName(slug(title), config), chatId, from.members(), project);
        }
        // A chatless group holding only this project takes the chat: moving the project out would leave it empty.
        if (from.projects().size() == 1) {
            String uncommented = text.replaceFirst("(?m)^(\\s*-\\s+name:\\s*'?" + Pattern.quote(from.name()) + "'?)"
                    + "\\s+" + Pattern.quote(NO_CHAT_COMMENT) + "$", "$1");
            return ConfigEdit.set(uncommented, At.of("telegram", "groups").item("name", from.name()).key("chatId"), chatId);
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

    /**
     * {@code slug}, then suffixed with -2, -3, … until no group in {@code config} has it in any case (the loader refuses
     * names differing only in case), cut so that the name with its suffix fits {@link ConfigLoader#MAX_GROUP_NAME}.
     */
    private static String uniqueName(String slug, Config config) {
        List<String> taken = config.telegram().groups().stream().map(Config.Group::name).toList();
        for (int i = 1; ; i++) {
            String suffix = i == 1 ? "" : "-" + i;
            String candidate = cut(slug, ConfigLoader.MAX_GROUP_NAME - suffix.length()) + suffix;
            if (taken.stream().noneMatch(candidate::equalsIgnoreCase)) {
                return candidate;
            }
        }
    }

    /** {@code slug} at most {@code max} characters long, without the trailing '-' a cut can leave. */
    private static String cut(String slug, int max) {
        return slug.length() <= max ? slug : slug.substring(0, max).replaceAll("-+$", "");
    }

    /**
     * A group name from a chat's title, e.g. "acme-backend", "Тэмдэглэл баг" → "temdeglel-bag"; "group" when nothing usable
     * is left. {@link dispatch.cli.Setup#teamName}'s rule after transliterating Cyrillic, copied rather than importing
     * {@code dispatch.cli} into {@code dispatch.config}.
     */
    private static String slug(String title) {
        String slug = latin(title.toLowerCase(Locale.ROOT)).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "group" : slug;
    }

    /** Mongolian and Russian Cyrillic letters of a lower-case {@code text} spelled in Latin; everything else as it was. */
    private static String latin(String text) {
        String cyrillic = "абвгдеёжзийклмноөпрстуүфхцчшщъыьэюя";
        String[] latin = {"a", "b", "v", "g", "d", "e", "yo", "j", "z", "i", "i", "k", "l", "m", "n", "o", "u", "p", "r", "s", "t",
                "u", "u", "f", "kh", "ts", "ch", "sh", "sh", "", "y", "", "e", "yu", "ya"};
        StringBuilder spelled = new StringBuilder(text.length());
        for (char letter : text.toCharArray()) {
            int index = cyrillic.indexOf(letter);
            spelled.append(index < 0 ? String.valueOf(letter) : latin[index]);
        }
        return spelled.toString();
    }
}
