package dispatch.cli;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.OwnerOnly;
import dispatch.config.Config;
import dispatch.config.ConfigException;
import dispatch.config.ConfigLoader;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * `dispatch init`: sets up a personal instance in five steps (ADR 0014): the bot, who you are on Telegram, Claude Code, your
 * projects, and the commit author. It writes the config, and the bot token into an owner-only secrets file beside it. The
 * token is typed without being shown and never printed.
 */
public final class InitCommand {

    private static final int ATTEMPTS = 3;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern TOKEN = Pattern.compile("\\d+:[A-Za-z0-9_-]+");
    private static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");
    private static final String TEMPLATE = """
            # Your own Dispatch (ADR 0014), written by dispatch init. Edit it freely: dispatch check says if something is wrong.
            team: %s
            stateDir: %s

            telegram:
              groups:
                - name: %s       # a personal bot: no group chat, so everything stays in your private chat with it
                  members:
                    - id: %d
                      name: %s
                  projects:
            %s
            delivery:
              authorName: %s
              authorEmail: %s

            scheduler:
              maxConcurrentRuns: 1

            limits:              # per run, for every project; a project may set its own under limits
              plan:
                timeout: 15m
                budgetUsd: 2
              execute:
                timeout: 60m
                budgetUsd: 10

            agents:
              claude-code:
                command: %s

            projects:
            %s""";

    private final Terminal terminal;
    private final Function<String, BotApi> bots;
    private final Locations locations;
    private final Duration startWait;

    /**
     * @param bots      the Telegram client for a bot token
     * @param startWait how long to wait for the person to press Start
     */
    public InitCommand(Terminal terminal, Function<String, BotApi> bots, Locations locations, Duration startWait) {
        this.terminal = terminal;
        this.bots = bots;
        this.locations = locations;
        this.startWait = startWait;
    }

    public int run(Cli.Init options, Map<String, String> processEnvironment) {
        try {
            init(options.configFile().toAbsolutePath(), options.force(), processEnvironment);
            return 0;
        } catch (CliException e) {
            terminal.say("FAIL " + e.getMessage());
            return 1;
        }
    }

    private record Bot(BotApi api, String token, String username) {
    }

    private void init(Path configFile, boolean force, Map<String, String> env) {
        Path secretsFile = SecretsFile.beside(configFile);
        if (Files.exists(configFile) && !force) {
            throw new CliException(configFile + " already exists; edit it, add projects with dispatch project add, "
                    + "or start over with dispatch init --force");
        }
        terminal.say("This sets up your own Dispatch on this machine. It writes " + configFile);
        terminal.say("and, readable only by you, " + secretsFile + ".");

        step("1/5 Your bot. Create one with @BotFather (/newbot) and paste its token here.");
        Bot bot = bot();
        step("2/5 You. Open https://t.me/" + bot.username() + " and press Start.");
        Config.Member me = me(bot.api());
        step("3/5 Claude Code.");
        String claude = claude(env);
        step("4/5 Projects: the git clones on this machine that Dispatch may work in.");
        List<ProjectAddCommand.Project> projects = projects();
        step("5/5 Commits. Dispatch commits the changes of your tasks as:");
        String authorName = required("Author name", "Dispatch (" + me.name().split("\\s+")[0] + ")");
        String authorEmail = required("Author email", gitEmail().orElse(null));

        write(configFile, secretsFile, render(me, claude, projects, authorName, authorEmail), bot.token());
        terminal.say("");
        terminal.say("OK   wrote " + configFile);
        terminal.say("OK   wrote " + secretsFile + " (only you can read it)");
        terminal.say("Next: dispatch check, then dispatch run.");
    }

    private Bot bot() {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String token = answered(terminal.askSecret("Bot token"));
            if (!TOKEN.matcher(token).matches()) {
                terminal.say("WARN that is not a bot token: @BotFather gives digits, a colon, then letters and digits");
                continue;
            }
            try {
                BotApi api = bots.apply(token);
                JsonNode me = api.getMe();
                String username = me.path("username").asText();
                terminal.say("OK   @" + username);
                if (!me.path("has_topics_enabled").asBoolean(false)) {
                    terminal.say("     Tip: turn on topics for the bot in @BotFather, and each task gets its own topic.");
                }
                return new Bot(api, token, username);
            } catch (TelegramException e) {
                terminal.say("WARN Telegram refused that token or could not be reached (" + e.getMessage() + ")");
            }
        }
        throw new CliException("no working bot token after " + ATTEMPTS + " tries");
    }

    /** The first private message's sender, once confirmed, is the only member; someone else may have found the bot first. */
    private Config.Member me(BotApi api) {
        terminal.say("     Waiting for your message...");
        Instant deadline = Instant.now().plus(startWait);
        long offset = 0;
        while (Instant.now().isBefore(deadline)) {
            int wait = (int) Math.clamp(Duration.between(Instant.now(), deadline).toSeconds(), 1, 30);
            List<JsonNode> updates;
            try {
                updates = api.getUpdates(offset, wait);
            } catch (TelegramException e) {
                throw new CliException("cannot read the bot's messages (" + e.getMessage() + "); if Dispatch is already running "
                        + "with this bot, stop it first");
            }
            for (JsonNode update : updates) {
                offset = update.path("update_id").asLong() + 1;
                JsonNode message = update.path("message");
                if (!message.path("chat").path("type").asText().equals("private") || !message.path("from").has("id")) {
                    continue;
                }
                Config.Member sender = new Config.Member(message.get("from").get("id").asLong(), displayName(message.get("from")));
                String answer = answered(terminal.ask("Is " + sender.name() + " (" + sender.id() + ") you? (y/n)", "y"));
                if (answer.strip().toLowerCase().startsWith("y")) {
                    // Confirms these messages, so Dispatch does not answer them once it runs.
                    api.getUpdates(offset, 0);
                    terminal.say("OK   you are " + sender.name() + " (" + sender.id() + ")");
                    return sender;
                }
            }
        }
        throw new CliException("no message from you reached the bot within " + startWait.toMinutes() + " minutes; run dispatch init again");
    }

    private String claude(Map<String, String> env) {
        Optional<Path> found = Executables.find("claude", System.getProperty("os.name"), env, Path.of(System.getProperty("user.home")));
        if (found.isEmpty()) {
            terminal.say("     claude was not found; install Claude Code, or give the full path to claude.");
        }
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String command = answered(terminal.ask("claude command", found.map(Path::toString).orElse(null)));
            Optional<String> version = version(command);
            if (version.isPresent()) {
                terminal.say("OK   " + version.get());
                return command;
            }
            terminal.say("WARN cannot run " + command + " --version");
        }
        throw new CliException("Claude Code could not be run; install it and run dispatch init again");
    }

    private List<ProjectAddCommand.Project> projects() {
        Git git = new Git("git", null, COMMAND_TIMEOUT);
        List<ProjectAddCommand.Project> projects = new ArrayList<>();
        while (true) {
            String folder = answered(projects.isEmpty()
                    ? terminal.ask("Folder of a git clone", null)
                    : terminal.ask("Folder of another git clone (empty when done)", ""));
            if (folder.isBlank()) {
                if (!projects.isEmpty()) {
                    return projects;
                }
                terminal.say("WARN Dispatch needs at least one project");
                continue;
            }
            ProjectProbe probe;
            try {
                probe = ProjectProbe.of(Path.of(folder.strip()), git);
            } catch (CliException | InvalidPathException e) {
                terminal.say("WARN " + e.getMessage());
                continue;
            }
            String name = required("Name", probe.defaultName());
            if (projects.stream().anyMatch(project -> project.name().equalsIgnoreCase(name))) {
                terminal.say("WARN you already added a project named " + name);
                continue;
            }
            String base = required("Branch tasks start from", probe.defaultBranch());
            String model = optional("Model (sonnet, opus or fable; empty for Claude Code's default)");
            String effort = effort();
            if (probe.originHadCredentials()) {
                terminal.say("WARN origin's URL holds credentials; it is not copied into the config");
            }
            projects.add(new ProjectAddCommand.Project(name, null, probe.folder(), probe.originUrl(), base, "claude-code", model, effort));
            terminal.say("OK   " + name + ": " + probe.folder() + " (base " + base + ")");
        }
    }

    private String effort() {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String effort = optional("Effort (low, medium, high, xhigh or max; empty for Claude Code's default)");
            if (effort == null || EFFORTS.contains(effort)) {
                return effort;
            }
            terminal.say("WARN effort is one of " + String.join(", ", EFFORTS));
        }
        return null;
    }

    private String render(Config.Member me, String claude, List<ProjectAddCommand.Project> projects, String authorName, String authorEmail) {
        String team = teamName(me.name());
        StringBuilder groupProjects = new StringBuilder();
        StringBuilder blocks = new StringBuilder();
        for (ProjectAddCommand.Project project : projects) {
            groupProjects.append("        - ").append(ProjectAddCommand.yaml(project.name())).append('\n');
            List<String> lines = ProjectAddCommand.projectLines(project);
            for (int i = 0; i < lines.size(); i++) {
                blocks.append(i == 0 ? "  - " : "    ").append(lines.get(i)).append('\n');
            }
        }
        return TEMPLATE.formatted(team, ProjectAddCommand.quoted(locations.stateDir().toAbsolutePath().toString()), team, me.id(),
                ProjectAddCommand.quoted(me.name()), groupProjects, ProjectAddCommand.quoted(authorName),
                ProjectAddCommand.quoted(authorEmail), ProjectAddCommand.quoted(claude), blocks);
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

    private void step(String line) {
        terminal.say("");
        terminal.say(line);
    }

    private String required(String question, String defaultValue) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String answer = answered(terminal.ask(question, defaultValue));
            if (!answer.isBlank()) {
                return answer.strip();
            }
        }
        throw new CliException(question + " is needed");
    }

    private String optional(String question) {
        String answer = answered(terminal.ask(question, ""));
        return answer.isBlank() ? null : answer.strip();
    }

    private static String answered(String answer) {
        if (answer == null) {
            throw new CliException("setup cancelled; nothing was written");
        }
        return answer;
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

    /** A team name from the person's first name, e.g. "bold"; "personal" when it has no Latin letters or digits. */
    private static String teamName(String name) {
        String team = name.split("\\s+")[0].toLowerCase().replaceAll("[^a-z0-9-]", "").replaceAll("^-+", "");
        return team.isEmpty() ? "personal" : team;
    }

    private static String displayName(JsonNode user) {
        String name = (user.path("first_name").asText("") + " " + user.path("last_name").asText("")).strip();
        return name.isEmpty() ? user.path("username").asText("?") : name;
    }
}
