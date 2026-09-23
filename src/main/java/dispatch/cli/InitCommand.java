package dispatch.cli;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.config.Config;
import dispatch.config.ConfigException;
import dispatch.telegram.BotApi;
import dispatch.telegram.TelegramException;
import dispatch.workspace.Git;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
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
    /** An Advanced per-phase choice: the first keeps what was chosen for both phases. */
    private static final List<Terminal.Option<String>> PHASE_MODELS = List.of(
            new Terminal.Option<>("Same as above", "the model chosen for both phases", null),
            new Terminal.Option<>("Sonnet", "", "sonnet"),
            new Terminal.Option<>("Opus", "", "opus"),
            new Terminal.Option<>("Fable", "", "fable"));
    private static final List<Terminal.Option<String>> PHASE_EFFORTS = List.of(
            new Terminal.Option<>("Same as above", "the effort chosen for both phases", null),
            new Terminal.Option<>("Low", "fastest", "low"),
            new Terminal.Option<>("Medium", "", "medium"),
            new Terminal.Option<>("High", "", "high"),
            new Terminal.Option<>("Extra high", "", "xhigh"),
            new Terminal.Option<>("Max", "most thorough", "max"));

    private final Terminal terminal;
    private final Function<String, BotApi> bots;
    private final Locations locations;
    private final Duration waitForPeople;
    private final ServiceCommand services;
    private boolean hinted;
    private boolean advanced;

    /**
     * @param bots          the Telegram client for a bot token
     * @param waitForPeople how long to wait for someone to press Start, or for the bot to be added to a group
     * @param services      installs the background service offered at the end
     */
    public InitCommand(Terminal terminal, Function<String, BotApi> bots, Locations locations, Duration waitForPeople,
                       ServiceCommand services) {
        this.terminal = terminal;
        this.bots = bots;
        this.locations = locations;
        this.waitForPeople = waitForPeople;
        this.services = services;
    }

    public int run(Cli.Init options, Map<String, String> processEnvironment) {
        try {
            advanced = options.advanced();
            init(options.configFile().toAbsolutePath(), options.force(), processEnvironment);
            return 0;
        } catch (CliException e) {
            terminal.fail(e.getMessage());
            return 1;
        }
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
        Setup.Bot bot = bot();
        Setup.Updates updates = new Setup.Updates(bot.api());

        terminal.step(team ? "3/6 Your team" : "3/6 You");
        terminal.say("Open https://t.me/" + bot.username() + " and press Start.");
        List<Config.Member> declined = new ArrayList<>();
        Config.Member me = person(updates, "Waiting for you to press Start", "Is %s you?", List.of(), declined)
                .orElseThrow(() -> new CliException("nobody pressed Start within " + minutes() + "; run dispatch init again"));
        terminal.ok("you: " + describe(me) + (team ? ", admin" : ""));
        List<Config.Member> members = new ArrayList<>(List.of(me));
        Setup.Chat chat = null;
        Config.Workers workers = null;
        String name = Setup.teamName(me.name().split("\\s+")[0]);
        if (team) {
            teammates(updates, members, declined, bot.username());
            chat = groupChat(updates, bot.username());
            name = Setup.teamName(required("Team name", chat == null ? name + "-team" : Setup.teamName(chat.title())));
            if (chat != null) {
                workers = workers();
            }
        }

        terminal.step("4/6 Claude Code");
        String claude = claude(env);

        terminal.step("5/6 Projects");
        terminal.say("The git clones on this machine that Dispatch may work in.");
        List<ProjectAddCommand.Project> projects = projects();

        terminal.step("6/6 Commits");
        terminal.say("Dispatch commits each task's changes as:");
        String authorName = required("Author name", "Dispatch (" + me.name().split("\\s+")[0] + ")");
        String authorEmail = required("Author email", Setup.gitEmail().orElse(null));
        Setup.Advanced instance = advanced ? advancedAnswers(team) : Setup.Advanced.NONE;

        summary(bot, team, members, chat, workers, projects, authorName, authorEmail, configFile);
        if (!terminal.confirm("Write this setup?", true)) {
            throw new CliException("cancelled; nothing was written");
        }
        String yaml;
        try {
            yaml = Setup.render(
                    new Setup.Answers(name, team, members, chat, workers, claude, projects, authorName, authorEmail, instance),
                    locations.stateDir());
        } catch (ConfigException e) {
            throw new CliException(e.getMessage());
        }
        Setup.write(configFile, yaml, bot.token());
        try {
            updates.acknowledge();
        } catch (TelegramException e) {
            terminal.warn("could not mark setup's messages as read (" + e.getMessage() + "); the bot may answer them once it runs");
        }
        terminal.ok("wrote " + configFile);
        terminal.ok("wrote " + secretsFile + " (only you can read it)");
        if (terminal.confirm("Keep Dispatch running in the background, also after a restart?", true)) {
            try {
                services.install(configFile, env);
                return;
            } catch (CliException e) {
                terminal.warn("the background service could not be installed: " + e.getMessage());
            }
        }
        terminal.say("");
        terminal.say("Next: dispatch check, then dispatch run (or dispatch service install).");
    }

    private Setup.Bot bot() {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String token = terminal.askSecret("Bot token");
            try {
                Setup.Bot bot = BotApi.isBotToken(token)
                        ? terminal.during("Checking the token with Telegram", () -> Setup.bot(token, bots))
                        : Setup.bot(token, bots);
                terminal.ok("@" + bot.username());
                if (!bot.topicsEnabled()) {
                    terminal.say("  Tip: turn on topics for the bot in @BotFather, and each task gets its own topic.");
                }
                return bot;
            } catch (CliException e) {
                terminal.warn(e.getMessage());
            }
        }
        throw new CliException("no working bot token after " + ATTEMPTS + " tries");
    }

    /** Teammates who press Start now; more can join later, when an admin approves them in Telegram. */
    private void teammates(Setup.Updates updates, List<Config.Member> members, List<Config.Member> declined, String bot) {
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
    private Setup.Chat groupChat(Setup.Updates updates, String bot) {
        if (!terminal.confirm("Should a team group see one-line announcements?", true)) {
            return null;
        }
        terminal.say("Add @" + bot + " to the group now (in the group: Add members, @" + bot + ").");
        Optional<JsonNode> found = terminal.during("Waiting for the bot to be added to a group",
                () -> updates.next(update -> Setup.group(update) != null, waitForPeople));
        if (found.isEmpty()) {
            terminal.warn("the bot was not added to a group within " + minutes() + "; add chatId to the config later");
            return null;
        }
        JsonNode chat = Setup.group(found.get());
        Setup.Chat result = new Setup.Chat(chat.path("id").asLong(), chat.path("title").asText("group"));
        terminal.ok("group: " + result.title() + " (" + result.id() + ")");
        return result;
    }

    /** Where teammates' computers reach this one, once there is a group to announce to (ADR 0021): their tasks run there. */
    private Config.Workers workers() {
        terminal.say("Each teammate's tasks run on their own computer; it must reach this one through a tunnel or reverse proxy.");
        String publicUrl = required("Public URL (e.g. https://team.example.com)", null);
        return new Config.Workers(publicUrl, port());
    }

    private int port() {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String answer = required("Port Dispatch listens on for teammates' computers", "7880");
            try {
                int port = Integer.parseInt(answer);
                if (port >= 1 && port <= 65535) {
                    return port;
                }
            } catch (NumberFormatException e) {
                // falls through to the warning
            }
            terminal.warn("a port is a whole number from 1 to 65535");
        }
        throw new CliException("Port is needed");
    }

    /**
     * The next person who writes to the bot privately and is confirmed. Someone declined as one person is still offered as
     * another later, e.g. a teammate who pressed Start before you.
     */
    private Optional<Config.Member> person(Setup.Updates updates, String waiting, String question, List<Config.Member> taken,
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
            Instant until = hinted ? deadline : min(deadline, Instant.now().plus(min(Setup.HINT_AFTER, waitForPeople.dividedBy(2))));
            Optional<JsonNode> update = terminal.during(waiting, () -> updates.next(Setup::isPrivateMessage, Duration.between(Instant.now(), until)));
            if (update.isEmpty()) {
                if (!hinted && Instant.now().isBefore(deadline)) {
                    hint();
                    continue;
                }
                return Optional.empty();
            }
            Config.Member candidate = Setup.member(update.get());
            if (taken.stream().anyMatch(member -> member.id() == candidate.id())
                    || declined.stream().anyMatch(member -> member.id() == candidate.id())) {
                continue;
            }
            try {
                updates.greet(candidate.id());
            } catch (TelegramException e) {
                terminal.warn("could not answer in Telegram (" + e.getMessage() + ")");
            }
            // Granting access never defaults to yes: a stray Enter must not make a stranger a member.
            if (terminal.confirm(question.formatted(describe(candidate)), false)) {
                return Optional.of(candidate);
            }
            declined.add(candidate);
        }
        return Optional.empty();
    }

    /**
     * Said once, when a wait stays quiet: Telegram can hold a message back without any error reaching Dispatch, e.g. while the
     * bot is connected to the account under Chat Automation.
     */
    private void hint() {
        hinted = true;
        terminal.say("  Nothing has reached the bot yet. If Start was pressed:");
        Setup.QUIET_HINTS.forEach(line -> terminal.say("  - " + line));
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) < 0 ? a : b;
    }

    private String claude(Map<String, String> env) {
        Optional<Path> found = Setup.findClaude(env);
        if (found.isEmpty()) {
            terminal.say("claude was not found; install Claude Code, or give the full path to claude.");
        }
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String command = terminal.ask("claude command", found.map(Path::toString).orElse(null));
            Optional<String> version = terminal.during("Checking " + command, () -> Setup.claudeVersion(command));
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
        if (!advanced) {
            return Optional.of(new ProjectAddCommand.Project(name, null, probe.folder(), probe.originUrl(), base, "claude-code", model, effort));
        }
        String alias = terminal.ask("Alias, a short name to use in tasks (blank for none)", null).strip();
        Config.PhaseSettings plan = phase("Planning");
        Config.PhaseSettings execute = phase("Execution");
        return Optional.of(new ProjectAddCommand.Project(name, alias.isEmpty() ? null : alias, probe.folder(), probe.originUrl(), base,
                "claude-code", model, effort, plan, execute));
    }

    /** A phase's own model and effort; null when both stay as chosen for both phases. */
    private Config.PhaseSettings phase(String label) {
        String model = terminal.choose(label + " model", PHASE_MODELS, 0);
        String effort = terminal.choose(label + " effort", PHASE_EFFORTS, 0);
        return model == null && effort == null ? null : new Config.PhaseSettings(model, effort);
    }

    /** `dispatch init --advanced`: limits, concurrency, state directory and gh; Enter keeps each default. */
    private Setup.Advanced advancedAnswers(boolean team) {
        terminal.step("Advanced");
        String planTimeout = timeout("Planning timeout per run (e.g. 15m, 2h)", "15m");
        BigDecimal planBudget = budget("Planning budget per run in USD", "2");
        String executeTimeout = timeout("Execution timeout per run", "60m");
        BigDecimal executeBudget = budget("Execution budget per run in USD", "10");
        int runs = runs(team ? 2 : 1);
        Path stateDir = stateDir(locations.stateDir().toString());
        String gh = required("GitHub CLI command", "gh");
        return new Setup.Advanced(planTimeout, planBudget, executeTimeout, executeBudget, runs, stateDir, gh);
    }

    private String timeout(String question, String defaultValue) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String answer = required(question, defaultValue);
            try {
                Config.RunLimits.parseDuration(answer);
                return answer;
            } catch (IllegalArgumentException e) {
                terminal.warn(e.getMessage());
            }
        }
        throw new CliException(question + " is needed");
    }

    private BigDecimal budget(String question, String defaultValue) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String answer = required(question, defaultValue);
            try {
                BigDecimal budget = new BigDecimal(answer);
                if (budget.signum() > 0) {
                    return budget;
                }
            } catch (NumberFormatException e) {
                // falls through to the warning
            }
            terminal.warn("a budget is a positive number of dollars, e.g. 2 or 12.5");
        }
        throw new CliException(question + " is needed");
    }

    private int runs(int defaultValue) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String answer = required("Maximum concurrent runs", String.valueOf(defaultValue));
            try {
                int runs = Integer.parseInt(answer);
                if (runs >= 1) {
                    return runs;
                }
            } catch (NumberFormatException e) {
                // falls through to the warning
            }
            terminal.warn("the number of runs at a time is a whole number, at least 1");
        }
        throw new CliException("Maximum concurrent runs is needed");
    }

    private Path stateDir(String defaultValue) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String answer = required("State directory", defaultValue);
            try {
                return ProjectProbe.expandHome(Path.of(answer)).toAbsolutePath();
            } catch (InvalidPathException e) {
                terminal.warn("not a valid path: " + e.getMessage());
            }
        }
        throw new CliException("State directory is needed");
    }

    private void summary(Setup.Bot bot, boolean team, List<Config.Member> members, Setup.Chat chat, Config.Workers workers,
                         List<ProjectAddCommand.Project> projects, String authorName, String authorEmail, Path configFile) {
        terminal.step("Summary");
        terminal.say("  Bot       @" + bot.username() + (team ? " (shared by your team)" : " (just you)"));
        terminal.say("  People    " + members.stream().map(InitCommand::describe).collect(Collectors.joining(", ")));
        if (team) {
            terminal.say("  Group     " + (chat == null ? "none" : chat.title()));
        }
        if (workers != null) {
            terminal.say("  Workers   " + workers.publicUrl() + " (port " + workers.port() + ")");
        }
        terminal.say("  Projects  " + projects.stream().map(project -> project.name() + " (" + project.baseBranch()
                + (project.model() == null ? "" : ", " + project.model()) + (project.effort() == null ? "" : ", " + project.effort()) + ")")
                .collect(Collectors.joining(", ")));
        terminal.say("  Commits   " + authorName + " <" + authorEmail + ">");
        terminal.say("  Config    " + configFile);
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

    private static String describe(Config.Member member) {
        return member.name() + " (" + member.id() + ")";
    }
}
