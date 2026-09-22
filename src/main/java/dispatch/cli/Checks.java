package dispatch.cli;

import dispatch.OwnerOnly;
import dispatch.Redactor;
import dispatch.config.Config;
import dispatch.config.ConfigException;
import dispatch.telegram.BotApi;
import dispatch.telegram.TelegramException;
import dispatch.workspace.Git;
import dispatch.workspace.WorkspaceException;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Whether an instance can work, before it starts: the config and secrets, the bot token, the agent CLI, each project's clone
 * and base branch, the state directory, and the GitHub CLI. Every problem is a finding with what to do about it. Shown by
 * `dispatch check` and on the web UI's overview.
 */
public final class Checks {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    public enum Level { OK, WARN, FAIL }

    /**
     * @param area    what was checked: config, bot, an agent's name, "project NAME", state or gh
     * @param message the whole line as `dispatch check` prints it, with what to do about a problem
     */
    public record Finding(Level level, String area, String message) {
    }

    private final Function<String, BotApi> bots;

    /** @param bots the Telegram client for a bot token */
    public Checks(Function<String, BotApi> bots) {
        this.bots = bots;
    }

    /** @param onEach sees each finding as soon as it is known, so a slow check does not hold back the ones before it */
    public List<Finding> run(Path configFile, Map<String, String> processEnvironment, Consumer<Finding> onEach) {
        Run run = new Run(onEach);
        RunCommand.Prepared prepared;
        try {
            prepared = RunCommand.prepare(configFile, processEnvironment);
        } catch (CliException | ConfigException e) {
            run.add(Level.FAIL, "config", "config: " + Redactor.fromEnvironment(processEnvironment).redact(e.getMessage()));
            return run.findings;
        }
        run.add(Level.OK, "config", "config " + configFile);
        Config config = prepared.config();
        checkBot(run, config.secrets().telegramBotToken());
        config.agents().forEach((name, agent) -> checkAgent(run, name, agent.command(), configFile));
        Workspaces workspaces = new Workspaces(config.stateDir(), new Git("git", null, COMMAND_TIMEOUT));
        config.projects().forEach(project -> checkProject(run, project, workspaces));
        checkStateDir(run, config.stateDir());
        checkGh(run, config.delivery().ghCommand(), config.secrets().ghToken() != null, configFile);
        return run.findings;
    }

    public static boolean failed(List<Finding> findings) {
        return findings.stream().anyMatch(finding -> finding.level() == Level.FAIL);
    }

    private void checkBot(Run run, String token) {
        if (!BotApi.isBotToken(token)) {
            run.add(Level.FAIL, "bot", "bot: TELEGRAM_BOT_TOKEN is not a bot token: @BotFather gives digits, a colon, then letters, digits, '_' and '-'");
            return;
        }
        try {
            var me = bots.apply(token).getMe();
            String topics = me.path("has_topics_enabled").asBoolean(false) ? "topics on" : "topics off";
            run.add(Level.OK, "bot", "bot @" + me.path("username").asText() + " (" + topics + ")");
        } catch (TelegramException e) {
            run.add(Level.FAIL, "bot", "bot: Telegram refused the token or could not be reached (" + e.getMessage() + "); get a fresh one from @BotFather");
        }
    }

    private static void checkAgent(Run run, String name, String command, Path configFile) {
        Optional<Git.Result> version = command(List.of(command, "--version"), configFile);
        if (version.isEmpty() || version.get().exitCode() != 0) {
            run.add(Level.FAIL, name, name + ": cannot run " + command + "; install Claude Code or set agents." + name + ".command to its full path");
            return;
        }
        run.add(Level.OK, name, name + ": " + version.get().stdout().strip().lines().findFirst().orElse(command));
    }

    private static void checkProject(Run run, Config.Project project, Workspaces workspaces) {
        String area = "project " + project.name();
        if (workspaces.needsClone(project)) {
            run.add(Level.WARN, area, area + ": not cloned yet; Dispatch clones " + project.repo() + " into "
                    + workspaces.repo(project) + " when it starts");
            return;
        }
        Optional<String> unavailable = workspaces.unavailableReason(project);
        if (unavailable.isPresent()) {
            run.add(Level.FAIL, area, area + ": " + unavailable.get());
            return;
        }
        Path repo = workspaces.repo(project);
        Optional<Git.Result> base = command(List.of("git", "-C", repo.toString(), "rev-parse", "--verify", "--quiet",
                "refs/remotes/origin/" + project.baseBranch()), repo);
        if (base.isEmpty() || base.get().exitCode() != 0) {
            run.add(Level.WARN, area, area + ": origin/" + project.baseBranch() + " not found in " + repo
                    + "; run git fetch there, or fix baseBranch");
            return;
        }
        run.add(Level.OK, area, area + ": " + repo + " (base " + project.baseBranch() + ")");
        checkInstructions(run, project, repo);
    }

    /** Agents work in worktrees of origin/baseBranch, so only a CLAUDE.md committed there reaches them. */
    private static void checkInstructions(Run run, Config.Project project, Path repo) {
        String base = "origin/" + project.baseBranch();
        for (String file : List.of("CLAUDE.md", ".claude/CLAUDE.md")) {
            Optional<Git.Result> found = command(List.of("git", "-C", repo.toString(), "cat-file", "-e", base + ":" + file), repo);
            if (found.isPresent() && found.get().exitCode() == 0) {
                return;
            }
        }
        String area = "project " + project.name();
        run.add(Level.WARN, area, area + ": no CLAUDE.md on " + base + ", so every run first spends turns finding its way around; "
                + "commit a short one with the layout and the build and test commands");
    }

    private static void checkStateDir(Run run, Path stateDir) {
        if (!Files.exists(stateDir)) {
            run.add(Level.OK, "state", "state " + stateDir + " (created on the first run)");
            return;
        }
        try {
            OwnerOnly.othersAccess(stateDir).ifPresentOrElse(
                    permissions -> run.add(Level.WARN, "state", "state " + stateDir + " is open to other users (" + permissions
                            + "); run: chmod o-rwx " + stateDir),
                    () -> run.add(Level.OK, "state", "state " + stateDir));
        } catch (IOException e) {
            run.add(Level.WARN, "state", "state " + stateDir + ": " + e.getMessage());
        }
    }

    private static void checkGh(Run run, String command, boolean tokenSet, Path configFile) {
        if (tokenSet) {
            run.add(Level.OK, "gh", "gh: GH_TOKEN is set");
            return;
        }
        Optional<Git.Result> status = command(List.of(command, "auth", "status"), configFile);
        if (status.isPresent() && status.get().exitCode() == 0) {
            run.add(Level.OK, "gh", "gh: logged in");
            return;
        }
        run.add(Level.WARN, "gh", "gh: not logged in or not installed (" + command + "); pull requests will fail until you run: gh auth login");
    }

    /** Empty when the command cannot start or hangs. */
    private static Optional<Git.Result> command(List<String> commandLine, Path near) {
        Path dir = Files.isDirectory(near) ? near : near.toAbsolutePath().getParent();
        try {
            return Optional.of(Git.runProcess(commandLine, dir, null, COMMAND_TIMEOUT, String.join(" ", commandLine)));
        } catch (WorkspaceException e) {
            return Optional.empty();
        }
    }

    /** One run's findings, passed on as they come. */
    private static final class Run {

        private final List<Finding> findings = new ArrayList<>();
        private final Consumer<Finding> onEach;

        Run(Consumer<Finding> onEach) {
            this.onEach = onEach;
        }

        void add(Level level, String area, String message) {
            Finding finding = new Finding(level, area, message);
            findings.add(finding);
            onEach.accept(finding);
        }
    }
}
