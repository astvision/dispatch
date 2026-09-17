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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * `dispatch check`: whether an instance can work, before it starts. It checks, in order: the config and secrets, the bot
 * token, the agent CLI, each project's clone and base branch, the state directory, and the GitHub CLI. Every problem is
 * listed with what to do about it. Exit code 1 when anything would stop tasks from running; warnings do not count.
 */
public final class CheckCommand {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private final Terminal terminal;
    private final Function<String, BotApi> bots;
    private boolean failed;

    /** @param bots the Telegram client for a bot token */
    public CheckCommand(Terminal terminal, Function<String, BotApi> bots) {
        this.terminal = terminal;
        this.bots = bots;
    }

    public int run(Path configFile, Map<String, String> processEnvironment) {
        RunCommand.Prepared prepared;
        try {
            prepared = RunCommand.prepare(configFile, processEnvironment);
        } catch (CliException | ConfigException e) {
            fail("config: " + Redactor.fromEnvironment(processEnvironment).redact(e.getMessage()));
            return 1;
        }
        ok("config " + configFile);
        Config config = prepared.config();
        checkBot(config.secrets().telegramBotToken());
        config.agents().forEach((name, agent) -> checkAgent(name, agent.command(), configFile));
        Workspaces workspaces = new Workspaces(config.stateDir(), new Git("git", null, COMMAND_TIMEOUT));
        config.projects().forEach(project -> checkProject(project, workspaces));
        checkStateDir(config.stateDir());
        checkGh(config.delivery().ghCommand(), config.secrets().ghToken() != null, configFile);
        return failed ? 1 : 0;
    }

    private void checkBot(String token) {
        if (!BotApi.isBotToken(token)) {
            fail("bot: TELEGRAM_BOT_TOKEN is not a bot token: @BotFather gives digits, a colon, then letters, digits, '_' and '-'");
            return;
        }
        try {
            var me = bots.apply(token).getMe();
            String topics = me.path("has_topics_enabled").asBoolean(false) ? "topics on" : "topics off";
            ok("bot @" + me.path("username").asText() + " (" + topics + ")");
        } catch (TelegramException e) {
            fail("bot: Telegram refused the token or could not be reached (" + e.getMessage() + "); get a fresh one from @BotFather");
        }
    }

    private void checkAgent(String name, String command, Path configFile) {
        Optional<Git.Result> version = run(List.of(command, "--version"), configFile);
        if (version.isEmpty() || version.get().exitCode() != 0) {
            fail(name + ": cannot run " + command + "; install Claude Code or set agents." + name + ".command to its full path");
            return;
        }
        ok(name + ": " + version.get().stdout().strip().lines().findFirst().orElse(command));
    }

    private void checkProject(Config.Project project, Workspaces workspaces) {
        Optional<String> unavailable = workspaces.unavailableReason(project);
        if (unavailable.isPresent()) {
            fail("project " + project.name() + ": " + unavailable.get());
            return;
        }
        Path repo = workspaces.repo(project);
        Optional<Git.Result> base = run(List.of("git", "-C", repo.toString(), "rev-parse", "--verify", "--quiet",
                "refs/remotes/origin/" + project.baseBranch()), repo);
        if (base.isEmpty() || base.get().exitCode() != 0) {
            warn("project " + project.name() + ": origin/" + project.baseBranch() + " not found in " + repo
                    + "; run git fetch there, or fix baseBranch");
            return;
        }
        ok("project " + project.name() + ": " + repo + " (base " + project.baseBranch() + ")");
        checkInstructions(project, repo);
    }

    /** Agents work in worktrees of origin/baseBranch, so only a CLAUDE.md committed there reaches them. */
    private void checkInstructions(Config.Project project, Path repo) {
        String base = "origin/" + project.baseBranch();
        for (String file : List.of("CLAUDE.md", ".claude/CLAUDE.md")) {
            Optional<Git.Result> found = run(List.of("git", "-C", repo.toString(), "cat-file", "-e", base + ":" + file), repo);
            if (found.isPresent() && found.get().exitCode() == 0) {
                return;
            }
        }
        warn("project " + project.name() + ": no CLAUDE.md on " + base + ", so every run first spends turns finding its way around; "
                + "commit a short one with the layout and the build and test commands");
    }

    private void checkStateDir(Path stateDir) {
        if (!Files.exists(stateDir)) {
            ok("state " + stateDir + " (created on the first run)");
            return;
        }
        try {
            OwnerOnly.othersAccess(stateDir).ifPresentOrElse(
                    permissions -> warn("state " + stateDir + " is open to other users (" + permissions + "); run: chmod o-rwx " + stateDir),
                    () -> ok("state " + stateDir));
        } catch (IOException e) {
            warn("state " + stateDir + ": " + e.getMessage());
        }
    }

    private void checkGh(String command, boolean tokenSet, Path configFile) {
        if (tokenSet) {
            ok("gh: GH_TOKEN is set");
            return;
        }
        Optional<Git.Result> status = run(List.of(command, "auth", "status"), configFile);
        if (status.isPresent() && status.get().exitCode() == 0) {
            ok("gh: logged in");
            return;
        }
        warn("gh: not logged in or not installed (" + command + "); pull requests will fail until you run: gh auth login");
    }

    /** Empty when the command cannot start or hangs. */
    private static Optional<Git.Result> run(List<String> commandLine, Path near) {
        Path dir = Files.isDirectory(near) ? near : near.toAbsolutePath().getParent();
        try {
            return Optional.of(Git.runProcess(commandLine, dir, null, COMMAND_TIMEOUT, String.join(" ", commandLine)));
        } catch (WorkspaceException e) {
            return Optional.empty();
        }
    }

    private void ok(String line) {
        terminal.ok(line);
    }

    private void warn(String line) {
        terminal.warn(line);
    }

    private void fail(String line) {
        failed = true;
        terminal.fail(line);
    }
}
