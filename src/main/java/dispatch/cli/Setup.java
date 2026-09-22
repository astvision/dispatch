package dispatch.cli;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.OwnerOnly;
import dispatch.config.Config;
import dispatch.config.ConfigException;
import dispatch.config.ConfigLoader;
import dispatch.config.ConfigText;
import dispatch.telegram.BotApi;
import dispatch.telegram.TelegramException;
import dispatch.workspace.Git;
import dispatch.workspace.WorkspaceException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What `dispatch init` and the web UI's setup share (ADR 0016, 0018): checking a bot token, reading the bot's updates while
 * people press Start, and rendering and writing the config. Nothing here talks to a person; the callers do.
 */
public final class Setup {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    /** How long a wait for someone stays quiet before the hints are shown. */
    public static final Duration HINT_AFTER = Duration.ofSeconds(20);

    /** What to check when nothing reaches the bot: Telegram can hold a message back without any error reaching Dispatch. */
    public static final List<String> QUIET_HINTS = List.of(
            "no Start button because the chat was used before? Send the bot any message instead",
            "one tick on the message means Telegram has not delivered it to the bot: in Telegram, Settings > Chat Automation must not use this bot",
            "Dispatch already running with this bot? Stop it first: dispatch service stop");

    static final String GREETING = "👋 Got it. Setup continues where you started it: <code>dispatch init</code> in a terminal, "
            + "or the <code>dispatch ui</code> page.";

    private Setup() {
    }

    public record Bot(BotApi api, String token, String username, boolean topicsEnabled) {

        @Override
        public String toString() {
            return "Bot[@" + username + "]";
        }
    }

    /** The team's group chat, when it has one. */
    public record Chat(long id, String title) {
    }

    /**
     * @param team   the team's name, as the config and the state directory use it
     * @param shared a team's bot (admins, a group chat) rather than a personal one
     * @param chat   null for none
     */
    public record Answers(String team, boolean shared, List<Config.Member> members, Chat chat, String claude,
                          List<ProjectAddCommand.Project> projects, String authorName, String authorEmail, Advanced advanced) {

        /** A quick setup: every advanced answer keeps its default. */
        public Answers(String team, boolean shared, List<Config.Member> members, Chat chat, String claude,
                       List<ProjectAddCommand.Project> projects, String authorName, String authorEmail) {
            this(team, shared, members, chat, claude, projects, authorName, authorEmail, Advanced.NONE);
        }
    }

    /**
     * Setup's Advanced answers for the whole instance; each null keeps today's default: plan 15m and $2, execute 60m and
     * $10, 1 run at a time for a personal bot and 2 for a team's, the default state directory, and "gh".
     *
     * @param planTimeout a duration as the config writes it, e.g. "15m"
     */
    public record Advanced(String planTimeout, BigDecimal planBudgetUsd, String executeTimeout, BigDecimal executeBudgetUsd,
                           Integer maxConcurrentRuns, Path stateDir, String ghCommand) {

        public static final Advanced NONE = new Advanced(null, null, null, null, null, null, null);
    }

    /** Dispatch or another program already reads this bot's updates; Telegram gives them to one reader at a time. */
    public static final class ConflictException extends CliException {

        ConflictException(String message) {
            super(message);
        }
    }

    /** @throws CliException saying what is wrong with the token, never containing it */
    public static Bot bot(String token, Function<String, BotApi> bots) {
        if (!BotApi.isBotToken(token)) {
            throw new CliException("that is not a bot token: @BotFather gives digits, a colon, then letters and digits");
        }
        BotApi api = bots.apply(token);
        try {
            JsonNode me = api.getMe();
            return new Bot(api, token, me.path("username").asText(), me.path("has_topics_enabled").asBoolean(false));
        } catch (TelegramException e) {
            throw new CliException("Telegram refused that token or could not be reached (" + e.getMessage() + ")");
        }
    }

    /**
     * The bot's updates during setup. Updates read while waiting for one thing stay here for the next wait, and all of them
     * are confirmed at the end, so the running bot does not answer the Start presses of setup. Not thread-safe: one wait at
     * a time.
     */
    public static final class Updates {

        private final BotApi api;
        private final Deque<JsonNode> read = new ArrayDeque<>();
        private long offset;

        public Updates(BotApi api) {
            this.api = api;
        }

        /** The next update {@code wanted} accepts, or empty when none came within {@code wait}. */
        public Optional<JsonNode> next(Predicate<JsonNode> wanted, Duration wait) {
            Instant deadline = Instant.now().plus(wait);
            while (true) {
                Iterator<JsonNode> unused = read.iterator();
                while (unused.hasNext()) {
                    JsonNode update = unused.next();
                    if (wanted.test(update)) {
                        unused.remove();
                        return Optional.of(update);
                    }
                }
                Duration remaining = Duration.between(Instant.now(), deadline);
                if (remaining.isZero() || remaining.isNegative()) {
                    return Optional.empty();
                }
                // At least 1: a remainder under a second is still time left, not "seconds <= 0" truncated away.
                long seconds = Math.max(remaining.toSeconds(), 1);
                List<JsonNode> fetched;
                try {
                    fetched = api.getUpdates(offset, (int) Math.min(seconds, 25));
                } catch (TelegramException e) {
                    String message = "cannot read the bot's messages (" + e.getMessage() + "); if Dispatch is already running "
                            + "with this bot, stop it first";
                    throw e.errorCode() == 409 ? new ConflictException(message) : new CliException(message);
                }
                for (JsonNode update : fetched) {
                    offset = Math.max(offset, update.path("update_id").asLong() + 1);
                    read.add(update);
                }
            }
        }

        /** Answers in Telegram, so pressing Start is not met with silence. */
        public void greet(long chatId) {
            api.sendMessage(chatId, null, GREETING, null, List.of());
        }

        /** Confirms every update read; if this fails, the running bot answers those messages once. */
        public void acknowledge() {
            api.getUpdates(offset, 0);
        }
    }

    public static boolean isPrivateMessage(JsonNode update) {
        JsonNode message = update.path("message");
        return message.path("chat").path("type").asText().equals("private") && message.path("from").has("id")
                && !message.path("from").path("is_bot").asBoolean(false);
    }

    /** A chat the update shows the bot in: it was added to it, or a message was written there; null for none. */
    public static JsonNode group(JsonNode update) {
        JsonNode added = update.path("my_chat_member");
        if (added.has("chat") && isGroup(added.get("chat")) && List.of("member", "administrator")
                .contains(added.path("new_chat_member").path("status").asText())) {
            return added.get("chat");
        }
        JsonNode message = update.path("message");
        return message.has("chat") && isGroup(message.get("chat")) ? message.get("chat") : null;
    }

    /** Who wrote a private message. */
    public static Config.Member member(JsonNode update) {
        JsonNode from = update.path("message").path("from");
        return new Config.Member(from.path("id").asLong(), displayName(from));
    }

    /** A team name from a person's first name or a group's title, e.g. "acme-backend"; "personal" when nothing usable is left. */
    public static String teamName(String text) {
        String team = text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return team.isEmpty() ? "personal" : team;
    }

    /** The first line of {@code command --version}, or empty when it cannot run. */
    public static Optional<String> claudeVersion(String command) {
        try {
            Git.Result result = Git.runProcess(List.of(command, "--version"), Path.of(System.getProperty("user.home")), null,
                    COMMAND_TIMEOUT, command + " --version");
            return result.exitCode() == 0 ? result.stdout().strip().lines().findFirst() : Optional.empty();
        } catch (WorkspaceException e) {
            return Optional.empty();
        }
    }

    public static Optional<Path> findClaude(Map<String, String> env) {
        return Executables.find("claude", System.getProperty("os.name"), env, Path.of(System.getProperty("user.home")));
    }

    public static Optional<String> gitEmail() {
        try {
            Git.Result result = Git.runProcess(List.of("git", "config", "--global", "user.email"), Path.of(System.getProperty("user.home")),
                    null, COMMAND_TIMEOUT, "git config --global user.email");
            return result.exitCode() == 0 && !result.stdout().isBlank() ? Optional.of(result.stdout().strip()) : Optional.empty();
        } catch (WorkspaceException e) {
            return Optional.empty();
        }
    }

    /** Writes only what differs from the defaults, so a quick setup renders the same config as it always has. */
    public static String render(Answers answers, Path stateDir) {
        Advanced advanced = answers.advanced();
        Path state = advanced.stateDir() != null ? advanced.stateDir() : stateDir;
        String team = answers.team();
        boolean shared = answers.shared();
        List<Config.Member> members = answers.members();
        Chat chat = answers.chat();
        String claude = answers.claude();
        List<ProjectAddCommand.Project> projects = answers.projects();
        StringBuilder yaml = new StringBuilder()
                .append("# Written by dispatch init. Edit it freely: dispatch check says if something is wrong.\n")
                .append("team: ").append(team).append('\n')
                .append("stateDir: ").append(ConfigText.quoted(state.toAbsolutePath().toString())).append("\n\n")
                .append("telegram:\n");
        if (shared) {
            yaml.append("  admins:            # who lets people join, from Telegram (ADR 0015)\n")
                    .append("    - ").append(members.getFirst().id()).append('\n');
        }
        yaml.append("  groups:\n")
                .append("    - name: ").append(team).append(shared ? "\n" : "     # a personal bot: no group chat, everything stays private\n");
        if (chat != null) {
            yaml.append("      chatId: ").append(chat.id()).append("     # ").append(chat.title().replace('\n', ' ')).append('\n');
        }
        yaml.append("      members:\n");
        members.forEach(member -> yaml.append("        - id: ").append(member.id()).append("\n          name: ")
                .append(ConfigText.quoted(member.name())).append('\n'));
        yaml.append("      projects:\n");
        projects.forEach(project -> yaml.append("        - ").append(ConfigText.yaml(project.name())).append('\n'));
        yaml.append("\ndelivery:\n")
                .append("  authorName: ").append(ConfigText.quoted(answers.authorName())).append('\n')
                .append("  authorEmail: ").append(ConfigText.quoted(answers.authorEmail())).append('\n');
        if (advanced.ghCommand() != null && !advanced.ghCommand().equals("gh")) {
            yaml.append("  ghCommand: ").append(ConfigText.quoted(advanced.ghCommand())).append('\n');
        }
        yaml.append('\n')
                .append("scheduler:\n  maxConcurrentRuns: ")
                .append(advanced.maxConcurrentRuns() != null ? advanced.maxConcurrentRuns() : shared ? 2 : 1).append("\n\n")
                .append("limits:              # per run, for every project; a project may set its own under limits\n")
                .append("  plan:\n    timeout: ").append(timeout(advanced.planTimeout(), "15m"))
                .append("\n    budgetUsd: ").append(budget(advanced.planBudgetUsd(), "2")).append('\n')
                .append("  execute:\n    timeout: ").append(timeout(advanced.executeTimeout(), "60m"))
                .append("\n    budgetUsd: ").append(budget(advanced.executeBudgetUsd(), "10")).append("\n\n")
                .append("agents:\n  claude-code:\n    command: ").append(ConfigText.quoted(claude)).append("\n\n")
                .append("projects:\n");
        for (ProjectAddCommand.Project project : projects) {
            List<String> lines = ProjectAddCommand.projectLines(project);
            for (int i = 0; i < lines.size(); i++) {
                yaml.append(i == 0 ? "  - " : "    ").append(lines.get(i)).append('\n');
            }
        }
        return yaml.toString();
    }

    private static String timeout(String value, String defaultValue) {
        return value == null ? defaultValue : ConfigText.yaml(value);
    }

    private static String budget(BigDecimal value, String defaultValue) {
        return value == null ? defaultValue : value.toPlainString();
    }

    /**
     * Writes the config and the owner-only secrets file. The config is validated as a draft beside it first, so an invalid
     * setup writes nothing at all.
     */
    public static void write(Path configFile, String yaml, String token) {
        Path draft = null;
        try {
            OwnerOnly.createDirectories(configFile.getParent());
            draft = Files.createTempFile(configFile.getParent(), ".dispatch-", ".yaml");
            try {
                Files.writeString(draft, yaml);
                ConfigLoader.load(draft, ProjectAddCommand.validationEnvironment(Map.of()));
            } catch (ConfigException e) {
                Files.deleteIfExists(draft);
                throw new CliException("this setup does not make a valid config: " + e.getMessage().replace(draft.toString(), configFile.toString()));
            }
            SecretsFile.write(SecretsFile.beside(configFile), Map.of("TELEGRAM_BOT_TOKEN", token));
            Files.move(draft, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (draft != null) {
                deleteQuietly(draft);
            }
            throw new CliException("cannot write " + configFile + ": " + e.getMessage());
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort: the write already failed, and this cleanup must not hide that failure
        }
    }

    private static boolean isGroup(JsonNode chat) {
        String type = chat.path("type").asText();
        return type.equals("group") || type.equals("supergroup");
    }

    private static String displayName(JsonNode user) {
        String name = (user.path("first_name").asText("") + " " + user.path("last_name").asText("")).strip();
        return name.isEmpty() ? user.path("username").asText("?") : name;
    }
}
