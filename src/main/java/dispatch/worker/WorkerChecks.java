package dispatch.worker;

import dispatch.Redactor;
import dispatch.cli.Checks;
import dispatch.cli.CliException;
import dispatch.cli.ProjectProbe;
import dispatch.cli.SecretsFile;
import dispatch.cli.Setup;
import dispatch.config.ConfigException;
import dispatch.workspace.Git;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Whether this computer can run its owner's tasks, before it tries: {@code worker.yaml}, the key in
 * {@code worker.env}, the team machine answering with that key, Claude Code, the GitHub CLI, and one clone per
 * project the team says the member has. Produces the same {@link Checks.Finding}s as the team machine's checks, so
 * `dispatch check` prints both the same way (ADR 0021).
 */
public final class WorkerChecks {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private WorkerChecks() {
    }

    /** @param onEach sees each finding as soon as it is known, as {@link Checks#run} does */
    public static List<Checks.Finding> run(Path workerFile, Map<String, String> processEnvironment,
                                           Consumer<Checks.Finding> onEach) {
        List<Checks.Finding> findings = new ArrayList<>();
        // The merged environment includes worker.env, so a finding that ever quoted the key (none does today, but a
        // future one, or an unexpected error message, might) would still be masked — the same reasoning as Checks'
        // own redactor, which is built from RunCommand.prepare's merged environment rather than the raw process one.
        EnvironmentResult environment = readEnvironment(workerFile, processEnvironment);
        Redactor redactor = Redactor.fromEnvironment(environment.values());
        Consumer<Checks.Finding> add = finding -> {
            Checks.Finding redacted = new Checks.Finding(finding.level(), finding.area(),
                    redactor.redact(finding.message()));
            findings.add(redacted);
            onEach.accept(redacted);
        };
        WorkerConfig config;
        try {
            config = WorkerConfigLoader.load(workerFile);
        } catch (ConfigException | CliException e) {
            add.accept(new Checks.Finding(Checks.Level.FAIL, "worker", "worker: " + e.getMessage()));
            return findings;
        }
        add.accept(new Checks.Finding(Checks.Level.OK, "worker",
                "worker: " + config.name() + ", team " + config.team() + " (" + workerFile + ")"));
        Optional<WorkerClient.Setup> team = checkPairing(workerFile, config, environment, add);
        checkClaude(config, add);
        checkGh(config, add);
        checkProjects(config, team, add);
        return findings;
    }

    /** @param error null on success; set when worker.env exists but this process cannot read it */
    private record EnvironmentResult(Map<String, String> values, String error) {
    }

    /** Never throws: a worker.env this process cannot read is reported as a pairing failure, not a crash. */
    private static EnvironmentResult readEnvironment(Path workerFile, Map<String, String> processEnvironment) {
        try {
            return new EnvironmentResult(SecretsFile.environment(workerFile, processEnvironment), null);
        } catch (CliException e) {
            return new EnvironmentResult(processEnvironment, e.getMessage());
        }
    }

    /**
     * The key, and what the team machine says about it. One real call decides all three cases: unreachable, revoked,
     * or paired — nothing about the files alone can tell those apart.
     */
    private static Optional<WorkerClient.Setup> checkPairing(Path workerFile, WorkerConfig config,
                                                             EnvironmentResult environment,
                                                             Consumer<Checks.Finding> add) {
        Path envFile = SecretsFile.beside(workerFile);
        if (environment.error() != null) {
            add.accept(new Checks.Finding(Checks.Level.FAIL, "pairing", "pairing: " + environment.error()));
            return Optional.empty();
        }
        String key = environment.values().get(WorkerCommand.KEY_VARIABLE);
        if (key == null) {
            add.accept(new Checks.Finding(Checks.Level.FAIL, "pairing",
                    "pairing: no worker key in " + envFile + "; run: dispatch worker init"));
            return Optional.empty();
        }
        WorkerClient client = new WorkerClient(HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build(),
                URI.create(config.team()), key);
        try {
            WorkerClient.Setup setup = client.setup();
            add.accept(new Checks.Finding(Checks.Level.OK, "team", "team: " + config.team() + " answers"));
            add.accept(new Checks.Finding(Checks.Level.OK, "pairing",
                    "pairing: paired with " + setup.team() + " as " + config.name()));
            return Optional.of(setup);
        } catch (WorkerClient.RevokedException e) {
            add.accept(new Checks.Finding(Checks.Level.OK, "team", "team: " + config.team() + " answers"));
            add.accept(new Checks.Finding(Checks.Level.FAIL, "pairing",
                    "pairing: this computer's key is not valid any more; pair again: dispatch worker init"));
            return Optional.empty();
        } catch (RuntimeException e) {
            add.accept(new Checks.Finding(Checks.Level.FAIL, "team",
                    "team: cannot reach " + config.team() + " (" + e.getMessage() + ")"));
            return Optional.empty();
        }
    }

    private static void checkClaude(WorkerConfig config, Consumer<Checks.Finding> add) {
        Optional<String> version = Setup.claudeVersion(config.claudeCommand());
        add.accept(version
                .map(line -> new Checks.Finding(Checks.Level.OK, "claude", "claude: " + line))
                .orElseGet(() -> new Checks.Finding(Checks.Level.FAIL, "claude", "claude: cannot run "
                        + config.claudeCommand() + "; install Claude Code, or set claudeCommand to its full path")));
    }

    private static void checkGh(WorkerConfig config, Consumer<Checks.Finding> add) {
        add.accept(Setup.ghLoggedIn(config.ghCommand())
                ? new Checks.Finding(Checks.Level.OK, "gh", "gh: logged in")
                : new Checks.Finding(Checks.Level.WARN, "gh", "gh: not logged in or not installed ("
                        + config.ghCommand() + "); pull requests will fail until you run: gh auth login"));
    }

    /**
     * One finding per project: the team's list decides what must be here, this computer's config decides where. When
     * the team could not be reached there is no list to compare against, so only what is mapped here is checked.
     */
    private static void checkProjects(WorkerConfig config, Optional<WorkerClient.Setup> team,
                                      Consumer<Checks.Finding> add) {
        if (team.isEmpty()) {
            return;
        }
        Git git = new Git("git", null, Duration.ofSeconds(30));
        Set<String> known = new HashSet<>();
        for (WorkerClient.ProjectInfo project : team.get().projects()) {
            known.add(project.name());
            String area = "project " + project.name();
            WorkerConfig.Project mine = config.projects().get(project.name());
            if (mine == null) {
                add.accept(new Checks.Finding(Checks.Level.FAIL, area, area
                        + ": the team has this project, but this computer does not; run: dispatch worker init"));
                continue;
            }
            Path path = Path.of(mine.path());
            if (!Files.exists(path.resolve(".git"))) {
                add.accept(new Checks.Finding(Checks.Level.FAIL, area, area + ": no git clone at " + path
                        + "; run: dispatch worker init"));
                continue;
            }
            ProjectProbe probe;
            try {
                probe = ProjectProbe.of(path, git);
            } catch (CliException e) {
                add.accept(new Checks.Finding(Checks.Level.FAIL, area, area + ": " + e.getMessage()));
                continue;
            }
            if (project.repo() != null && !probe.originHadCredentials()
                    && !ProjectProbe.sameRepo(probe.originUrl(), project.repo())) {
                add.accept(new Checks.Finding(Checks.Level.WARN, area, area + ": " + path + " has origin "
                        + probe.originUrl() + ", but the team's project is " + project.repo()));
                continue;
            }
            add.accept(new Checks.Finding(Checks.Level.OK, area, area + ": " + path));
        }
        config.projects().keySet().stream().filter(name -> !known.contains(name)).forEach(name ->
                add.accept(new Checks.Finding(Checks.Level.WARN, "project " + name, "project " + name
                        + ": this computer has it, but the team does not; remove it from worker.yaml")));
    }
}
