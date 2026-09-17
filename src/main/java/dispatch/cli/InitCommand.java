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
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * `dispatch init`: sets up a bot for just you (ADR 0014) or a team's shared bot (ADR 0015, 0016). It asks for the bot token,
 * finds you and your teammates when you press Start, finds the team group when the bot is added to it, and takes Claude Code,
 * projects and the commit author. Nothing is written before the summary is confirmed; the token goes only into an owner-only
 * secrets file, and is never shown.
 */
public final class InitCommand {

    private static final int ATTEMPTS = 3;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final List<Terminal.Option<String>> MODELS = List.of(
            new Terminal.Option<>("Claude Code's default", "whatever your Claude account uses", null),
            new Terminal.Option<>("Sonnet", "", "sonnet"),
            new Terminal.Option<>("Opus", "", "opus"),
            new Terminal.Option<>("Fable", "", "fable"));
    private static final List<Terminal.Option<String>> EFFORTS = List.of(
            new Terminal.Option<>("Claude Code's default", "", null),
            new Terminal.Option<>("Low", "fastest", "low"),
            new Terminal.Option<>("Medium", "", "medium"),
            new Terminal.Option<>("High", "", "high"),
            new Terminal.Option<>("Extra high", "", "xhigh"),
            new Terminal.Option<>("Max", "most thorough", "max"));

    private final Terminal terminal;
    private final Function<String, BotApi> bots;
    private final Locations locations;
    private final Duration waitForPeople;

    /**
     * @param bots          the Telegram client for a bot token
     * @param waitForPeople how long to wait for someone to press Start, or for the bot to be added to a group
     */
    public InitCommand(Terminal terminal, Function<String, BotApi> bots, Locations locations, Duration waitForPeople) {
        this.terminal = terminal;
        this.bots = bots;
        this.locations = locations;
        this.waitForPeople = waitForPeople;
    }

    public int run(Cli.Init options, Map<String, String> processEnvironment) {
        try {
            init(options.configFile().toAbsolutePath(), options.force(), processEnvironment);
            return 0;
        } catch (CliException e) {
            terminal.fail(e.getMessage());
            return 1;
        }
    }

    private record Bot(BotApi api, String token, String username) {
    }

    /** The team's group chat, when it has one. */
    private record Chat(long id, String title) {
    }

    private void init(Path configFile, boolean force, Map<String, String> env) {
        Path secretsFile = SecretsFile.beside(configFile);
        if (Files.exists(configFile) && !force) {
            throw new CliException(configFile + " already exists; edit it, add projects with dispatch project add, "
                    + "or start over with dispatch init --force");
        }
        terminal.say("Dispatch setup. Nothing is written until you confirm the summary at the end.");

        terminal.step("1/6 Who will use this bot?");
        boolean team = terminal.choose("Who will use this bot?", List.of(
                new Terminal.Option<>("Just me", "tasks and results stay in your private chat with the bot", false),
                new Terminal.Option<>("My team", "teammates join when you approve them; a team group can see announcements", true)), 0);

        terminal.step("2/6 The bot");
        terminal.say("Create one with @BotFather (/newbot) in Telegram, then paste its token.");
        Bot bot = bot();
        Updates updates = new Updates(bot.api());

        terminal.step(team ? "3/6 Your team" : "3/6 You");
        terminal.say("Open https://t.me/" + bot.username() + " and press Start.");
        List<Config.Member> declined = new ArrayList<>();
        Config.Member me = person(updates, "Waiting for you to press Start", "Is %s you?", List.of(), declined)
                .orElseThrow(() -> new CliException("nobody pressed Start within " + minutes() + "; run dispatch init again"));
        terminal.ok("you: " + describe(me) + (team ? ", admin" : ""));
        List<Config.Member> members = new ArrayList<>(List.of(me));
        Chat chat = null;
        String name = teamName(me.name().split("\\s+")[0]);
        if (team) {
            teammates(updates, members, declined, bot.username());
            chat = groupChat(updates, bot.username());
            name = teamName(required("Team name", chat == null ? name + "-team" : teamName(chat.title())));
        }

        terminal.step("4/6 Claude Code");
        String claude = claude(env);

        terminal.step("5/6 Projects");
        terminal.say("The git clones on this machine that Dispatch may work in.");
        List<ProjectAddCommand.Project> projects = projects();

        terminal.step("6/6 Commits");
        terminal.say("Dispatch commits each task's changes as:");
        String authorName = required("Author name", "Dispatch (" + me.name().split("\\s+")[0] + ")");
        String authorEmail = required("Author email", gitEmail().orElse(null));

        summary(bot, team, members, chat, projects, authorName, authorEmail, configFile);
        if (!terminal.confirm("Write this setup?", true)) {
            throw new CliException("cancelled; nothing was written");
        }
        write(configFile, secretsFile, render(name, team, members, chat, claude, projects, authorName, authorEmail), bot.token());
        updates.acknowledge();
        terminal.ok("wrote " + configFile);
        terminal.ok("wrote " + secretsFile + " (only you can read it)");
        terminal.say("");
        terminal.say("Next: dispatch check, then dispatch run.");
    }

    private Bot bot() {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String token = terminal.askSecret("Bot token");
            if (!BotApi.isBotToken(token)) {
                terminal.warn("that is not a bot token: @BotFather gives digits, a colon, then letters and digits");
                continue;
            }
            BotApi api = bots.apply(token);
            try {
                JsonNode me = terminal.during("Checking the token with Telegram", api::getMe);
                String username = me.path("username").asText();
                terminal.ok("@" + username);
                if (!me.path("has_topics_enabled").asBoolean(false)) {
                    terminal.say("  Tip: turn on topics for the bot in @BotFather, and each task gets its own topic.");
                }
                return new Bot(api, token, username);
            } catch (TelegramException e) {
                terminal.warn("Telegram refused that token or could not be reached (" + e.getMessage() + ")");
            }
        }
        throw new CliException("no working bot token after " + ATTEMPTS + " tries");
    }

    /** Teammates who press Start now; more can join later, when an admin approves them in Telegram. */
    private void teammates(Updates updates, List<Config.Member> members, List<Config.Member> declined, String bot) {
        terminal.say("Teammates open https://t.me/" + bot + " and press Start. Anyone can also ask later: you approve them in Telegram.");
        String question = "Wait for a teammate to press Start now?";
        while (terminal.confirm(question, false)) {
            Optional<Config.Member> teammate = person(updates, "Waiting for a teammate to press Start", "Add %s to the team?", members,
                    declined);
            teammate.ifPresentOrElse(member -> {
                members.add(member);
                terminal.ok("added " + describe(member));
            }, () -> terminal.warn("nobody pressed Start within " + minutes()));
            question = "Wait for another teammate?";
        }
    }

    /** The group that gets one-line announcements, found when the bot is added to it (or someone writes there). */
    private Chat groupChat(Updates updates, String bot) {
        if (!terminal.confirm("Should a team group see one-line announcements?", true)) {
            return null;
        }
        terminal.say("Add @" + bot + " to the group now (in the group: Add members, @" + bot + ").");
        Optional<JsonNode> found = updates.next(update -> group(update) != null, "Waiting for the bot to be added to a group", waitForPeople);
        if (found.isEmpty()) {
            terminal.warn("the bot was not added to a group within " + minutes() + "; add chatId to the config later");
            return null;
        }
        JsonNode chat = group(found.get());
        Chat result = new Chat(chat.path("id").asLong(), chat.path("title").asText("group"));
        terminal.ok("group: " + result.title() + " (" + result.id() + ")");
        return result;
    }

    /** A chat the update shows the bot in: it was added to it, or a message was written there. */
    private static JsonNode group(JsonNode update) {
        JsonNode added = update.path("my_chat_member");
        if (added.has("chat") && isGroup(added.get("chat")) && List.of("member", "administrator")
                .contains(added.path("new_chat_member").path("status").asText())) {
            return added.get("chat");
        }
        JsonNode message = update.path("message");
        return message.has("chat") && isGroup(message.get("chat")) ? message.get("chat") : null;
    }

    /**
     * The next person who writes to the bot privately and is confirmed. Someone declined as one person is still offered as
     * another later, e.g. a teammate who pressed Start before you.
     */
    private Optional<Config.Member> person(Updates updates, String waiting, String question, List<Config.Member> taken,
                                           List<Config.Member> declined) {
        Iterator<Config.Member> earlier = declined.iterator();
        while (earlier.hasNext()) {
            Config.Member candidate = earlier.next();
            if (taken.stream().noneMatch(member -> member.id() == candidate.id())
                    && terminal.confirm(question.formatted(describe(candidate)), false)) {
                earlier.remove();
                return Optional.of(candidate);
            }
        }
        Instant deadline = Instant.now().plus(waitForPeople);
        while (Instant.now().isBefore(deadline)) {
            Optional<JsonNode> update = updates.next(InitCommand::isPrivateMessage, waiting, Duration.between(Instant.now(), deadline));
            if (update.isEmpty()) {
                return Optional.empty();
            }
            JsonNode from = update.get().path("message").path("from");
            Config.Member candidate = new Config.Member(from.path("id").asLong(), displayName(from));
            if (taken.stream().anyMatch(member -> member.id() == candidate.id())
                    || declined.stream().anyMatch(member -> member.id() == candidate.id())) {
                continue;
            }
            // Granting access never defaults to yes: a stray Enter must not make a stranger a member.
            if (terminal.confirm(question.formatted(describe(candidate)), false)) {
                return Optional.of(candidate);
            }
            declined.add(candidate);
        }
        return Optional.empty();
    }

    private String claude(Map<String, String> env) {
        Optional<Path> found = Executables.find("claude", System.getProperty("os.name"), env, Path.of(System.getProperty("user.home")));
        if (found.isEmpty()) {
            terminal.say("claude was not found; install Claude Code, or give the full path to claude.");
        }
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String command = terminal.ask("claude command", found.map(Path::toString).orElse(null));
            Optional<String> version = terminal.during("Checking " + command, () -> version(command));
            if (version.isPresent()) {
                terminal.ok(version.get());
                return command;
            }
            terminal.warn("cannot run " + command + " --version");
        }
        throw new CliException("Claude Code could not be run; install it and run dispatch init again");
    }

    private List<ProjectAddCommand.Project> projects() {
        Git git = new Git("git", null, COMMAND_TIMEOUT);
        List<ProjectAddCommand.Project> projects = new ArrayList<>();
        do {
            Optional<ProjectAddCommand.Project> project = project(git, projects);
            project.ifPresent(projects::add);
        } while (projects.isEmpty() || terminal.confirm("Add another project?", false));
        return projects;
    }

    private Optional<ProjectAddCommand.Project> project(Git git, List<ProjectAddCommand.Project> added) {
        String folder = required("Folder of a git clone", null);
        ProjectProbe probe;
        try {
            probe = terminal.during("Reading " + folder, () -> ProjectProbe.of(Path.of(folder), git));
        } catch (CliException | InvalidPathException e) {
            terminal.warn(e.getMessage());
            return Optional.empty();
        }
        terminal.ok(probe.folder() + (probe.defaultBranch() == null ? "" : " · base " + probe.defaultBranch())
                + (probe.originUrl() == null ? "" : " · " + probe.originUrl()));
        if (probe.originHadCredentials()) {
            terminal.warn("origin's URL holds credentials; it is not copied into the config");
        }
        String name = required("Name", probe.defaultName());
        if (added.stream().anyMatch(project -> project.name().equalsIgnoreCase(name))) {
            terminal.warn("you already added a project named " + name);
            return Optional.empty();
        }
        String base = required("Branch tasks start from", probe.defaultBranch());
        String model = terminal.choose("Model", MODELS, 0);
        String effort = terminal.choose("Effort", EFFORTS, 0);
        return Optional.of(new ProjectAddCommand.Project(name, null, probe.folder(), probe.originUrl(), base, "claude-code", model, effort));
    }

    private void summary(Bot bot, boolean team, List<Config.Member> members, Chat chat, List<ProjectAddCommand.Project> projects,
                         String authorName, String authorEmail, Path configFile) {
        terminal.step("Summary");
        terminal.say("  Bot       @" + bot.username() + (team ? " (shared by your team)" : " (just you)"));
        terminal.say("  People    " + members.stream().map(InitCommand::describe).collect(Collectors.joining(", ")));
        if (team) {
            terminal.say("  Group     " + (chat == null ? "none" : chat.title()));
        }
        terminal.say("  Projects  " + projects.stream().map(project -> project.name() + " (" + project.baseBranch()
                + (project.model() == null ? "" : ", " + project.model()) + (project.effort() == null ? "" : ", " + project.effort()) + ")")
                .collect(Collectors.joining(", ")));
        terminal.say("  Commits   " + authorName + " <" + authorEmail + ">");
        terminal.say("  Config    " + configFile);
    }

    private String render(String team, boolean shared, List<Config.Member> members, Chat chat, String claude,
                          List<ProjectAddCommand.Project> projects, String authorName, String authorEmail) {
        StringBuilder yaml = new StringBuilder()
                .append("# Written by dispatch init. Edit it freely: dispatch check says if something is wrong.\n")
                .append("team: ").append(team).append('\n')
                .append("stateDir: ").append(ConfigText.quoted(locations.stateDir().toAbsolutePath().toString())).append("\n\n")
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
                .append("  authorName: ").append(ConfigText.quoted(authorName)).append('\n')
                .append("  authorEmail: ").append(ConfigText.quoted(authorEmail)).append("\n\n")
                .append("scheduler:\n  maxConcurrentRuns: ").append(shared ? 2 : 1).append("\n\n")
                .append("limits:              # per run, for every project; a project may set its own under limits\n")
                .append("  plan:\n    timeout: 15m\n    budgetUsd: 2\n")
                .append("  execute:\n    timeout: 60m\n    budgetUsd: 10\n\n")
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

    private void write(Path configFile, Path secretsFile, String yaml, String token) {
        try {
            OwnerOnly.createDirectories(configFile.getParent());
            SecretsFile.write(secretsFile, Map.of("TELEGRAM_BOT_TOKEN", token));
            Files.writeString(configFile, yaml);
        } catch (IOException e) {
            throw new CliException("cannot write " + configFile + ": " + e.getMessage());
        }
        try {
            ConfigLoader.load(configFile, ProjectAddCommand.validationEnvironment(SecretsFile.environment(configFile, Map.of())));
        } catch (ConfigException e) {
            throw new CliException("the written config does not validate, please report this: " + e.getMessage());
        }
    }

    private String required(String question, String defaultValue) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String answer = terminal.ask(question, defaultValue);
            if (!answer.isBlank()) {
                return answer.strip();
            }
        }
        throw new CliException(question + " is needed");
    }

    private String minutes() {
        long minutes = waitForPeople.toMinutes();
        return minutes >= 1 ? minutes + " minutes" : waitForPeople.toSeconds() + " seconds";
    }

    /**
     * The bot's updates during setup. Updates read while waiting for one thing stay here for the next wait, and all of them
     * are confirmed at the end, so the running bot does not answer the Start presses of setup.
     */
    private final class Updates {

        private final BotApi api;
        private final Deque<JsonNode> read = new ArrayDeque<>();
        private long offset;

        Updates(BotApi api) {
            this.api = api;
        }

        Optional<JsonNode> next(Predicate<JsonNode> wanted, String waiting, Duration wait) {
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
                long seconds = Duration.between(Instant.now(), deadline).toSeconds();
                if (seconds <= 0) {
                    return Optional.empty();
                }
                List<JsonNode> fetched;
                try {
                    fetched = terminal.during(waiting, () -> api.getUpdates(offset, (int) Math.min(seconds, 25)));
                } catch (TelegramException e) {
                    throw new CliException("cannot read the bot's messages (" + e.getMessage() + "); if Dispatch is already running "
                            + "with this bot, stop it first");
                }
                for (JsonNode update : fetched) {
                    offset = Math.max(offset, update.path("update_id").asLong() + 1);
                    read.add(update);
                }
            }
        }

        /** Best effort: if it fails, the running bot answers these messages once. */
        void acknowledge() {
            try {
                api.getUpdates(offset, 0);
            } catch (TelegramException e) {
                terminal.warn("could not mark setup's messages as read (" + e.getMessage() + "); the bot may answer them once it runs");
            }
        }
    }

    private static boolean isPrivateMessage(JsonNode update) {
        JsonNode message = update.path("message");
        return message.path("chat").path("type").asText().equals("private") && message.path("from").has("id")
                && !message.path("from").path("is_bot").asBoolean(false);
    }

    private static boolean isGroup(JsonNode chat) {
        String type = chat.path("type").asText();
        return type.equals("group") || type.equals("supergroup");
    }

    private static String describe(Config.Member member) {
        return member.name() + " (" + member.id() + ")";
    }

    private static Optional<String> version(String command) {
        try {
            Git.Result result = Git.runProcess(List.of(command, "--version"), Path.of(System.getProperty("user.home")), null,
                    COMMAND_TIMEOUT, command + " --version");
            return result.exitCode() == 0 ? result.stdout().strip().lines().findFirst() : Optional.empty();
        } catch (WorkspaceException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> gitEmail() {
        try {
            Git.Result result = Git.runProcess(List.of("git", "config", "--global", "user.email"), Path.of(System.getProperty("user.home")),
                    null, COMMAND_TIMEOUT, "git config --global user.email");
            return result.exitCode() == 0 && !result.stdout().isBlank() ? Optional.of(result.stdout().strip()) : Optional.empty();
        } catch (WorkspaceException e) {
            return Optional.empty();
        }
    }

    /** A team name from a person's first name or a group's title, e.g. "acme-backend"; "personal" when nothing usable is left. */
    private static String teamName(String text) {
        String team = text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return team.isEmpty() ? "personal" : team;
    }

    private static String displayName(JsonNode user) {
        String name = (user.path("first_name").asText("") + " " + user.path("last_name").asText("")).strip();
        return name.isEmpty() ? user.path("username").asText("?") : name;
    }
}
