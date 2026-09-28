package dispatch.cli;

import dispatch.Json;
import dispatch.OwnerOnly;
import dispatch.Redactor;
import dispatch.Text;
import dispatch.config.Config;
import dispatch.config.ConfigException;
import dispatch.telegram.BotApi;
import dispatch.telegram.TelegramException;
import dispatch.worker.WorkerApi;
import dispatch.workspace.Git;
import dispatch.workspace.WorkspaceException;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * and base branch, the state directory, and either the GitHub CLI (personal mode) or the workers block members connect
 * through (team mode). Every problem is a finding with what to do about it. Shown by `dispatch check`, in English, and on
 * the web UI's overview, in the page's language.
 */
public final class Checks {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    /** A probe only has to reach a server on this machine, or a proxy in front of it. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    /**
     * The Mini App route a probe asks for. It is a GET route and the probe sends a POST, which does not matter:
     * {@code UiServer} authenticates before it looks a route up, so an unsigned request is the same 401 either way.
     */
    private static final String ME = "/api/me";

    public enum Level { OK, WARN, FAIL }

    /**
     * @param area    what was checked: config, bot, an agent's name, "project NAME", state, gh or workers
     * @param message the whole line as `dispatch check` prints it (in English), with what to do about a problem
     */
    public record Finding(Level level, String area, Text message) {
    }

    private final Function<String, BotApi> bots;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();

    /** @param bots the Telegram client for a bot token */
    public Checks(Function<String, BotApi> bots) {
        this.bots = bots;
    }

    /** @param onEach sees each finding as soon as it is known, so a slow check does not hold back the ones before it */
    public List<Finding> run(Path configFile, Map<String, String> processEnvironment, Consumer<Finding> onEach) {
        RunCommand.Prepared prepared;
        try {
            prepared = RunCommand.prepare(configFile, processEnvironment);
        } catch (CliException | ConfigException e) {
            Run failed = new Run(onEach, Redactor.fromEnvironment(processEnvironment));
            Text why = e instanceof CliException refused ? refused.text() : Text.raw(e.getMessage());
            failed.add(Level.FAIL, "config", Text.of("check.configInvalid", why));
            return failed.findings;
        }
        // The merged environment includes the secrets file, so tokens findings quote (e.g. a repo URL's userinfo,
        // or a check command's own output) are masked too, not only the ones already known from processEnvironment.
        Run run = new Run(onEach, Redactor.fromEnvironment(prepared.environment()));
        run.add(Level.OK, "config", Text.of("check.config", configFile));
        Config config = prepared.config();
        checkBot(run, config.secrets().telegramBotToken());
        boolean team = config.workers() != null;
        config.agents().forEach((name, agent) -> checkAgent(run, name, agent.command(), configFile, team));
        Workspaces workspaces = new Workspaces(config.stateDir(), new Git("git", null, COMMAND_TIMEOUT));
        config.projects().forEach(project -> checkProject(run, project, workspaces));
        checkStateDir(run, config.stateDir());
        if (team) {
            // Tasks run on members' computers (ADR 0021), so nothing here opens a pull request.
            run.add(Level.OK, "gh", Text.of("check.ghNotNeeded"));
            checkWorkers(run, config.workers());
        } else {
            checkGh(run, config.delivery().ghCommand(), config.secrets().ghToken() != null, configFile);
        }
        checkMiniApp(run, config.miniApp());
        return run.findings;
    }

    public static boolean failed(List<Finding> findings) {
        return findings.stream().anyMatch(finding -> finding.level() == Level.FAIL);
    }

    private void checkBot(Run run, String token) {
        if (!BotApi.isBotToken(token)) {
            run.add(Level.FAIL, "bot", Text.of("check.botTokenShape"));
            return;
        }
        try {
            var me = bots.apply(token).getMe();
            Text topics = me.path("has_topics_enabled").asBoolean(false) ? Text.of("check.topicsOn") : Text.of("check.topicsOff");
            run.add(Level.OK, "bot", Text.of("check.bot", me.path("username").asText(), topics));
        } catch (TelegramException e) {
            run.add(Level.FAIL, "bot", Text.of("check.botRefused", e.getMessage()));
        }
    }

    /** What to install for each agent type (ADR 0026). */
    private static final Map<String, Text> AGENT_INSTALLS = Map.of(
            "claude-code", Text.of("check.installClaude"),
            "codex", Text.of("check.installCodex"),
            "gemini", Text.of("check.installGemini"));

    private static void checkAgent(Run run, String name, String command, Path configFile, boolean team) {
        Optional<Git.Result> version = command(List.of(command, "--version"), configFile);
        if (version.isEmpty() || version.get().exitCode() != 0) {
            // In team mode no task's agent runs here, but splitting a message with ✂️ still does (ADR 0013), so this
            // is worth saying and not worth failing on.
            run.add(team ? Level.WARN : Level.FAIL, name, team
                    ? Text.of("check.agentCannotRunTeam", name, command)
                    : Text.of("check.agentCannotRun", name, command, AGENT_INSTALLS.getOrDefault(name, Text.raw(name))));
            return;
        }
        String shown = version.get().stdout().strip().lines().findFirst().orElse(command);
        if (name.equals("codex") && !team) {
            // Its runs fail at once without a login, which --version does not reveal.
            Optional<Git.Result> login = command(List.of(command, "login", "status"), configFile);
            if (login.isEmpty() || login.get().exitCode() != 0) {
                run.add(Level.WARN, name, Text.of("check.agentNotLoggedIn", name, shown, command));
                return;
            }
        }
        run.add(Level.OK, name, Text.of("check.agent", name, shown));
    }

    private static void checkProject(Run run, Config.Project project, Workspaces workspaces) {
        String area = "project " + project.name();
        Text named = Text.of("check.projectArea", project.name());
        if (workspaces.needsClone(project)) {
            run.add(Level.WARN, area, Text.of("check.notCloned", named, project.repo(), workspaces.repo(project)));
            return;
        }
        Optional<String> unavailable = workspaces.unavailableReason(project);
        if (unavailable.isPresent()) {
            run.add(Level.FAIL, area, Text.of("check.projectUnavailable", named, unavailable.get()));
            return;
        }
        Path repo = workspaces.repo(project);
        Optional<Git.Result> base = command(List.of("git", "-C", repo.toString(), "rev-parse", "--verify", "--quiet",
                "refs/remotes/origin/" + project.baseBranch()), repo);
        if (base.isEmpty() || base.get().exitCode() != 0) {
            run.add(Level.WARN, area, Text.of("check.noBaseBranch", named, project.baseBranch(), repo));
            return;
        }
        run.add(Level.OK, area, Text.of("check.project", named, repo, project.baseBranch()));
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
        run.add(Level.WARN, area, Text.of("check.noClaudeMd", Text.of("check.projectArea", project.name()), base));
    }

    private static void checkStateDir(Run run, Path stateDir) {
        if (!Files.exists(stateDir)) {
            run.add(Level.OK, "state", Text.of("check.stateNew", stateDir));
            return;
        }
        try {
            OwnerOnly.othersAccess(stateDir).ifPresentOrElse(
                    permissions -> run.add(Level.WARN, "state", Text.of("check.stateOpen", stateDir, permissions)),
                    () -> run.add(Level.OK, "state", Text.of("check.state", stateDir)));
        } catch (IOException e) {
            run.add(Level.WARN, "state", Text.of("check.stateUnreadable", stateDir, e.getMessage()));
        }
    }

    private static void checkGh(Run run, String command, boolean tokenSet, Path configFile) {
        if (tokenSet) {
            run.add(Level.OK, "gh", Text.of("check.ghToken"));
            return;
        }
        Optional<Git.Result> status = command(List.of(command, "auth", "status"), configFile);
        if (status.isPresent() && status.get().exitCode() == 0) {
            run.add(Level.OK, "gh", Text.of("check.ghLoggedIn"));
            return;
        }
        run.add(Level.WARN, "gh", Text.of("check.ghNotLoggedIn", command));
    }

    /**
     * Whether members' computers can reach this machine: the loopback port Dispatch listens on, and the public URL
     * their workers are given. The public URL is only probed once the local port answered as Dispatch — otherwise a
     * check run before the first `dispatch run` would blame the tunnel for a Dispatch that is not running.
     */
    private void checkWorkers(Run run, Config.Workers workers) {
        String local = "http://127.0.0.1:" + workers.port();
        switch (probe(local)) {
            case DISPATCH -> {
                run.add(Level.OK, "workers", Text.of("check.workersLocal", workers.port()));
                if (probe(workers.publicUrl()) == Answer.DISPATCH) {
                    run.add(Level.OK, "workers", Text.of("check.workersPublic", workers.publicUrl()));
                } else {
                    run.add(Level.WARN, "workers", Text.of("check.workersUnreachable", workers.publicUrl(), workers.port()));
                }
            }
            case OTHER -> run.add(Level.WARN, "workers", Text.of("check.workersOther", workers.port()));
            case NONE -> run.add(Level.OK, "workers", Text.of("check.workersNone", workers.port()));
        }
    }

    /**
     * Whether the Mini App is off, or on and reachable (spec: Security). The public URL is only probed once the local
     * port answered as Dispatch, for the same reason as the workers check above.
     */
    private void checkMiniApp(Run run, Config.MiniApp miniApp) {
        if (miniApp == null) {
            run.add(Level.OK, "miniApp", Text.of("check.miniAppOff"));
            return;
        }
        String local = "http://127.0.0.1:" + miniApp.port();
        switch (probe(local, ME)) {
            case DISPATCH -> {
                run.add(Level.OK, "miniApp", Text.of("check.miniAppLocal", miniApp.port()));
                if (probe(miniApp.publicUrl(), ME) == Answer.DISPATCH) {
                    run.add(Level.OK, "miniApp", Text.of("check.miniAppPublic", miniApp.publicUrl()));
                } else {
                    run.add(Level.WARN, "miniApp", Text.of("check.miniAppUnreachable", miniApp.publicUrl(), miniApp.port()));
                }
            }
            case OTHER -> run.add(Level.WARN, "miniApp", Text.of("check.miniAppOther", miniApp.port()));
            case NONE -> run.add(Level.OK, "miniApp", Text.of("check.miniAppNone", miniApp.port()));
        }
    }

    /** What answered a worker request that carried no key. */
    private enum Answer { DISPATCH, OTHER, NONE }

    private Answer probe(String base) {
        return probe(base, WorkerApi.PROJECTS);
    }

    private Answer probe(String base, String path) {
        HttpResponse<String> answer;
        try {
            answer = http.send(HttpRequest.newBuilder(WorkerApi.url(base, path)).timeout(PROBE_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | IllegalArgumentException e) {
            return Answer.NONE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Answer.NONE;
        }
        if (answer.statusCode() != 401) {
            return Answer.OTHER;
        }
        try {
            // Dispatch's own refusal; a proxy's 401 page is not JSON with this code, and no other server answers it.
            return Json.read(answer.body()).path("error").asText().equals("unauthorized") ? Answer.DISPATCH : Answer.OTHER;
        } catch (RuntimeException e) {
            return Answer.OTHER;
        }
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

    /**
     * One run's findings, passed on as they come; every message is redacted in the language it is read in, before it is
     * kept or handed to onEach.
     */
    private static final class Run {

        private final List<Finding> findings = new ArrayList<>();
        private final Consumer<Finding> onEach;
        private final Redactor redactor;

        Run(Consumer<Finding> onEach, Redactor redactor) {
            this.onEach = onEach;
            this.redactor = redactor;
        }

        void add(Level level, String area, Text message) {
            Finding finding = new Finding(level, area, message.map(redactor::redact));
            findings.add(finding);
            onEach.accept(finding);
        }
    }
}
