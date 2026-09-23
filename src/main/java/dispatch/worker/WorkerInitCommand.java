package dispatch.worker;

import dispatch.OwnerOnly;
import dispatch.cli.Cli;
import dispatch.cli.CliException;
import dispatch.cli.Locations;
import dispatch.cli.ProjectProbe;
import dispatch.cli.SecretsFile;
import dispatch.cli.ServiceCommand;
import dispatch.cli.Setup;
import dispatch.cli.Terminal;
import dispatch.config.ConfigException;
import dispatch.config.ConfigLoader;
import dispatch.config.ConfigText;
import dispatch.workspace.Git;
import dispatch.workspace.WorkspaceException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * `dispatch worker init`: sets this computer up to run its owner's own tasks (ADR 0021). It pairs with the team, asks
 * the team machine which projects the member has, maps each to a clone here (one the member already has, or one it
 * clones), checks Claude Code and the GitHub CLI, and writes {@code worker.yaml} beside an owner-only
 * {@code worker.env}.
 *
 * <p>Unlike `dispatch init`, one thing is written before the summary: the pairing code is one-time, so the key it
 * buys is saved the moment it arrives rather than lost if the member stops halfway. Stopping after that point and
 * running this again reuses the saved key instead of asking for a new code. The first line says so.
 */
public final class WorkerInitCommand {

    private static final int ATTEMPTS = 3;
    private static final Duration CLONE_TIMEOUT = Duration.ofMinutes(30);
    /** A worker's override of the team's choice; the first option keeps whatever the team configured. */
    private static final List<Terminal.Option<String>> MODELS = List.of(
            new Terminal.Option<>("The team's setting", "whatever the project says on the team machine", null),
            new Terminal.Option<>("Sonnet", "", "sonnet"),
            new Terminal.Option<>("Opus", "", "opus"),
            new Terminal.Option<>("Fable", "", "fable"));
    private static final List<Terminal.Option<String>> EFFORTS = List.of(
            new Terminal.Option<>("The team's setting", "", null),
            new Terminal.Option<>("Low", "fastest", "low"),
            new Terminal.Option<>("Medium", "", "medium"),
            new Terminal.Option<>("High", "", "high"),
            new Terminal.Option<>("Extra high", "", "xhigh"),
            new Terminal.Option<>("Max", "most thorough", "max"));

    private final Terminal terminal;
    private final Locations locations;
    private final ServiceCommand services;

    /** @param services installs the dispatch-worker service offered at the end; null when this build has no jar */
    public WorkerInitCommand(Terminal terminal, Locations locations, ServiceCommand services) {
        this.terminal = terminal;
        this.locations = locations;
        this.services = services;
    }

    public int run(Cli.WorkerInit options, Map<String, String> processEnvironment) {
        try {
            init(options.workerFile().toAbsolutePath(), options.force(), processEnvironment);
            return 0;
        } catch (CliException | ConfigException e) {
            terminal.fail(e.getMessage());
            return 1;
        }
    }

    private void init(Path workerFile, boolean force, Map<String, String> environment) {
        if (Files.exists(workerFile) && !force) {
            throw new CliException(workerFile + " already exists; edit it, or start over with: "
                    + "dispatch worker init --force");
        }
        Path envFile = SecretsFile.beside(workerFile);
        terminal.say("Dispatch worker setup. Your tasks will run here, with your own Claude Code login and clones.");
        terminal.say("Your key is saved as soon as you pair (the code works once); if you stop before finishing, running "
                + "this again reuses that saved key -- nothing else is written until you confirm the summary.");

        terminal.step("1/5 Your team");
        Paired paired = pair(workerFile, envFile);
        WorkerClient client = new WorkerClient(HttpClient.newHttpClient(), URI.create(paired.teamUrl()), paired.key());
        WorkerClient.Setup team;
        try {
            team = terminal.during("Asking " + paired.teamUrl() + " for your projects", client::setup);
        } catch (WorkerClient.RevokedException e) {
            // Each team runs its own key store (WorkerApi.authenticate), so a stranded key saved for a different
            // team's URL answers exactly like a revoked one -- either way --force alone cannot fix it; only a fresh
            // code from this team can.
            throw new CliException("the key saved in " + envFile + " is not valid for " + paired.teamUrl()
                    + " (wrong team, or revoked); get a fresh code from /worker for this team, then run "
                    + "dispatch worker init --force");
        } catch (RuntimeException e) {
            // The key is saved, so this is worth retrying without a new code: say so instead of a stack trace.
            throw new CliException("cannot ask " + paired.teamUrl() + " for your projects (" + e.getMessage()
                    + "); your key is saved in " + envFile + ", so run dispatch worker init --force again once it "
                    + "answers -- no new code needed");
        }
        terminal.ok("paired with " + team.team() + " as " + paired.name());

        terminal.step("2/5 Your projects");
        Path stateDir = locations.stateDir().resolve("worker");
        Map<String, WorkerConfig.Project> projects = projects(team.projects(), stateDir);

        terminal.step("3/5 Claude Code");
        String claude = claude(environment);

        terminal.step("4/5 GitHub CLI");
        String gh = gh();

        terminal.step("5/5 Summary");
        summary(team.team(), paired, projects, claude, gh, workerFile);
        if (!terminal.confirm("Write this setup?", true)) {
            throw new CliException("cancelled; " + workerFile + " was not written, but your key stays in " + envFile
                    + " -- no new code needed next time");
        }
        write(workerFile, render(paired, projects, claude, gh, stateDir));
        terminal.ok("wrote " + workerFile);
        offerService(workerFile, environment);
    }

    /** What pairing settled: where the team is, what this computer is called there, and the key it may use. */
    private record Paired(String teamUrl, String name, String key) {
    }

    /**
     * Exchanges a code for this computer's key and saves it at once. An existing key is offered for re-use first: a
     * member adding a project should not have to fetch a new code, nor leave a second computer in their /worker list.
     * A key saved by an earlier attempt that never reached a full pairing (no valid {@code worker.yaml} to go with
     * it) is reused too, once the team URL and computer name are confirmed, rather than asking for a fresh code the
     * old one already spent.
     */
    private Paired pair(Path workerFile, Path envFile) {
        Optional<Paired> existing = existingPairing(workerFile, envFile);
        if (existing.isPresent() && !terminal.confirm("This computer is already paired with " + existing.get().teamUrl()
                + " as " + existing.get().name() + ". Pair again?", false)) {
            return existing.get();
        }
        String url = requiredTeamUrl(existing.map(Paired::teamUrl).orElse(null));
        String name = requiredComputerName(existing.map(Paired::name).orElse(Cli.hostName()));
        if (existing.isEmpty()) {
            Optional<String> stranded = strandedKey(envFile);
            if (stranded.isPresent()) {
                terminal.ok("reusing the key already saved in " + envFile + " -- its one-time code is already spent");
                return new Paired(url, name, stranded.get());
            }
        }
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            // A member who mistyped the URL (missing https://, a LAN address instead of the public one) should not
            // have to burn three codes before being told to check it: re-ask it too, not just the code.
            if (attempt > 1) {
                url = requiredTeamUrl(url);
            }
            String code = required("Pairing code (send /worker to the bot in Telegram)", null);
            String pairUrl = url;
            try {
                WorkerClient.Paired answer = terminal.during("Pairing with " + pairUrl,
                        () -> WorkerClient.pair(HttpClient.newHttpClient(), URI.create(pairUrl), code, name));
                saveKey(workerFile, envFile, answer.key());
                terminal.ok("this computer is #" + answer.workerId() + " in your /worker list");
                return new Paired(pairUrl, name, answer.key());
            } catch (RuntimeException e) {
                terminal.warn("pairing failed: " + e.getMessage());
            }
        }
        throw new CliException("could not pair after " + ATTEMPTS + " tries; check the URL and ask for a fresh code with /worker");
    }

    /** A team URL is worth nothing until {@link ConfigLoader#isWorkerUrl} accepts it: a bad one wastes a whole code first. */
    private String requiredTeamUrl(String defaultValue) {
        String value = defaultValue;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String answer = required("Team URL (your team owner has it, e.g. https://team.example.com)", value);
            if (ConfigLoader.isWorkerUrl(answer)) {
                return answer;
            }
            terminal.warn("a team URL must start with https://, or http:// only for 127.0.0.1; got '" + answer + "'");
            value = null;
        }
        throw new CliException("a valid Team URL is needed");
    }

    /** Same idea as {@link #requiredTeamUrl}: {@link WorkerConfigLoader}'s own name rule, checked before the code is spent. */
    private String requiredComputerName(String defaultValue) {
        String value = defaultValue;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String answer = required("A name for this computer, as your /worker list will show it", value);
            if (WorkerConfigLoader.isValidName(answer)) {
                return answer;
            }
            terminal.warn("a name is letters, digits, '.', '_' and '-', at most 40 characters; got '" + answer + "'");
            value = null;
        }
        throw new CliException("a valid computer name is needed");
    }

    private Optional<Paired> existingPairing(Path workerFile, Path envFile) {
        if (!Files.exists(envFile) || !Files.exists(workerFile)) {
            return Optional.empty();
        }
        try {
            String key = SecretsFile.read(envFile).get(WorkerCommand.KEY_VARIABLE);
            WorkerConfig config = WorkerConfigLoader.load(workerFile);
            return key == null ? Optional.empty() : Optional.of(new Paired(config.team(), config.name(), key));
        } catch (IOException | ConfigException | CliException e) {
            // An unreadable or invalid pair of files is simply no pairing to offer; init writes fresh ones.
            return Optional.empty();
        }
    }

    /**
     * A key saved by a pairing that got no further: {@code worker.yaml} may be missing or invalid (an aborted first
     * attempt), but the key itself is still good, and its one-time code is already spent either way.
     */
    private Optional<String> strandedKey(Path envFile) {
        if (!Files.exists(envFile)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(SecretsFile.read(envFile).get(WorkerCommand.KEY_VARIABLE));
        } catch (IOException | CliException e) {
            // Unreadable or malformed: nothing usable to reuse; pairing starts fresh below instead.
            return Optional.empty();
        }
    }

    private void saveKey(Path workerFile, Path envFile, String key) {
        try {
            // A worker machine never runs `dispatch init`, so the config directory usually does not exist yet.
            OwnerOnly.createDirectories(workerFile.getParent());
            Map<String, String> values = new LinkedHashMap<>(existingSecrets(envFile));
            values.put(WorkerCommand.KEY_VARIABLE, key);
            SecretsFile.write(envFile, values);
            terminal.ok("wrote " + envFile + " (only you can read it)");
        } catch (IOException e) {
            throw new CliException("cannot write " + envFile + ": " + e.getMessage());
        }
    }

    /**
     * Whatever {@code envFile} already holds, as a starting point for the new key. A malformed file must never cost
     * the member the key a one-time code just bought, so it is replaced (with a warning) instead of aborting the
     * save the way an ordinary read failure does.
     */
    private Map<String, String> existingSecrets(Path envFile) {
        if (!Files.exists(envFile)) {
            return Map.of();
        }
        try {
            return SecretsFile.read(envFile);
        } catch (CliException e) {
            terminal.warn(envFile + " could not be read as it was (" + e.getMessage() + "); it will be replaced");
            return Map.of();
        } catch (IOException e) {
            throw new CliException("cannot read " + envFile + ": " + e.getMessage());
        }
    }

    /** One entry per project the team says this member has; a project nobody maps here simply cannot run here. */
    private Map<String, WorkerConfig.Project> projects(List<WorkerClient.ProjectInfo> team, Path stateDir) {
        if (team.isEmpty()) {
            terminal.warn("your team has no projects for you yet; add them later in " + stateDir.getParent());
            return Map.of();
        }
        Git git = new Git("git", null, Duration.ofSeconds(30));
        Map<String, WorkerConfig.Project> projects = new LinkedHashMap<>();
        for (WorkerClient.ProjectInfo project : team) {
            terminal.say("");
            terminal.say("  " + project.name() + (project.repo() == null ? "" : " · " + project.repo())
                    + (project.baseBranch() == null ? "" : " · base " + project.baseBranch()));
            Optional<Path> path = folderFor(project, git, stateDir);
            if (path.isEmpty()) {
                terminal.warn(project.name() + " is not set up here; tasks for it will wait until you run "
                        + "dispatch worker init --force again");
                continue;
            }
            projects.put(project.name(), new WorkerConfig.Project(path.get().toString(),
                    terminal.choose("Model on this computer", MODELS, 0),
                    terminal.choose("Effort on this computer", EFFORTS, 0)));
            terminal.ok(project.name() + " → " + path.get());
        }
        return projects;
    }

    private Optional<Path> folderFor(WorkerClient.ProjectInfo project, Git git, Path stateDir) {
        List<Terminal.Option<String>> options = project.repo() == null
                ? List.of(new Terminal.Option<>("A clone I already have", "give its folder", "existing"),
                        new Terminal.Option<>("Skip it", "you can add it later", "skip"))
                : List.of(new Terminal.Option<>("A clone I already have", "give its folder", "existing"),
                        new Terminal.Option<>("Clone it here", "into " + stateDir.resolve("repos").resolve(project.name()), "clone"),
                        new Terminal.Option<>("Skip it", "you can add it later", "skip"));
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            switch (terminal.choose("How does " + project.name() + " get onto this computer?", options, 0)) {
                case "existing" -> {
                    Optional<Path> folder = existingClone(project, git);
                    if (folder.isPresent()) {
                        return folder;
                    }
                }
                case "clone" -> {
                    Optional<Path> cloned = clone(project, stateDir);
                    if (cloned.isPresent()) {
                        return cloned;
                    }
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Path> existingClone(WorkerClient.ProjectInfo project, Git git) {
        String folder = required("Folder of your " + project.name() + " clone", null);
        ProjectProbe probe;
        try {
            probe = terminal.during("Reading " + folder, () -> ProjectProbe.of(Path.of(folder), git));
        } catch (CliException | InvalidPathException e) {
            terminal.warn(e.getMessage());
            return Optional.empty();
        }
        if (probe.originHadCredentials()) {
            terminal.warn("origin's URL holds credentials; it is not checked against the team's repo");
        } else if (project.repo() != null && !ProjectProbe.sameRepo(probe.originUrl(), project.repo())) {
            terminal.warn(probe.folder() + " is not a clone of " + project.repo()
                    + " (its origin is " + probe.originUrl() + ")");
            if (!terminal.confirm("Use it anyway?", false)) {
                return Optional.empty();
            }
        }
        return Optional.of(probe.folder());
    }

    /**
     * A failed clone must not discard every other answer already given: it warns and falls back to the same menu
     * {@link #existingClone} already recovers to, so the member can pick "a clone I already have" or skip the
     * project instead of losing the whole wizard over one project's network hiccup.
     */
    private Optional<Path> clone(WorkerClient.ProjectInfo project, Path stateDir) {
        Path target = stateDir.resolve("repos").resolve(project.name());
        // Only a directory this attempt itself creates is ever removed below: one already there is the member's own,
        // whatever put it there, and is never ours to delete.
        boolean existedBefore = Files.exists(target);
        try {
            OwnerOnly.createDirectories(target.getParent());
        } catch (IOException e) {
            terminal.warn("cannot create " + target.getParent() + ": " + e.getMessage());
            return Optional.empty();
        }
        try {
            terminal.during("Cloning " + project.repo() + " into " + target, () -> new Git("git", null, CLONE_TIMEOUT)
                    .run(target.getParent(), "clone", "--quiet", project.repo(), target.toString()));
        } catch (WorkspaceException e) {
            terminal.warn("cannot clone " + project.repo() + ": " + e.getMessage());
            // A partly-populated target left by the failed clone would otherwise fail every retry ("already exists
            // and is not an empty directory") until the member deletes it by hand.
            if (!existedBefore) {
                deleteRecursively(target);
            }
            return Optional.empty();
        }
        return Optional.of(target);
    }

    /** Best-effort recursive delete of what this run's own failed clone created; never touches a pre-existing path. */
    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(WorkerInitCommand::deleteQuietly);
        } catch (IOException ignored) {
            // best effort: a stray leftover here is better than turning a warned clone failure into a crash
        }
    }

    private String claude(Map<String, String> environment) {
        Optional<Path> found = Setup.findClaude(environment);
        if (found.isEmpty()) {
            terminal.say("claude was not found; install Claude Code, or give the full path to claude.");
        }
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String command = required("claude command", found.map(Path::toString).orElse("claude"));
            Optional<String> version = terminal.during("Checking " + command, () -> Setup.claudeVersion(command));
            if (version.isPresent()) {
                terminal.ok(version.get());
                return command;
            }
            terminal.warn("cannot run " + command + " --version");
        }
        throw new CliException("Claude Code could not be run; install it and run dispatch worker init --force again");
    }

    /** Not fatal: a member can log in to gh later, and only delivery needs it. */
    private String gh() {
        String command = required("GitHub CLI command", "gh");
        if (Setup.ghLoggedIn(command)) {
            terminal.ok("gh: logged in");
        } else {
            terminal.warn("gh is not logged in or not installed (" + command
                    + "); pull requests will fail until you run: gh auth login");
        }
        return command;
    }

    private void summary(String team, Paired paired, Map<String, WorkerConfig.Project> projects, String claude,
                         String gh, Path workerFile) {
        terminal.say("  Team      " + team + " (" + paired.teamUrl() + ") as " + paired.name());
        terminal.say("  Projects  " + (projects.isEmpty() ? "none yet" : projects.entrySet().stream()
                .map(entry -> entry.getKey() + " → " + entry.getValue().path())
                .collect(Collectors.joining(", "))));
        terminal.say("  Claude    " + claude);
        terminal.say("  GitHub    " + gh);
        terminal.say("  Config    " + workerFile);
    }

    private String render(Paired paired, Map<String, WorkerConfig.Project> projects, String claude, String gh,
                          Path stateDir) {
        StringBuilder yaml = new StringBuilder()
                .append("# Written by dispatch worker init. Edit it freely: dispatch check says if something is wrong.\n")
                .append("team: ").append(ConfigText.quoted(paired.teamUrl())).append('\n')
                .append("name: ").append(ConfigText.quoted(paired.name())).append('\n')
                .append("maxConcurrentRuns: 1\n")
                .append("stateDir: ").append(ConfigText.quoted(stateDir.toString())).append('\n');
        if (!claude.equals("claude")) {
            yaml.append("claudeCommand: ").append(ConfigText.quoted(claude)).append('\n');
        }
        if (!gh.equals("gh")) {
            yaml.append("ghCommand: ").append(ConfigText.quoted(gh)).append('\n');
        }
        if (projects.isEmpty()) {
            yaml.append("# projects:\n#   crm:\n#     path: /absolute/path/to/your/clone\n");
            return yaml.toString();
        }
        yaml.append("projects:\n");
        projects.forEach((name, project) -> {
            yaml.append("  ").append(ConfigText.yaml(name)).append(":\n")
                    .append("    path: ").append(ConfigText.quoted(project.path())).append('\n');
            if (project.model() != null) {
                yaml.append("    model: ").append(ConfigText.yaml(project.model())).append('\n');
            }
            if (project.effort() != null) {
                yaml.append("    effort: ").append(ConfigText.yaml(project.effort())).append('\n');
            }
        });
        return yaml.toString();
    }

    /** Written as a draft beside the file and loaded once, so an invalid setup never replaces a working one. */
    private static void write(Path workerFile, String yaml) {
        Path draft = null;
        try {
            OwnerOnly.createDirectories(workerFile.getParent());
            draft = Files.createTempFile(workerFile.getParent(), ".worker-", ".yaml");
            Files.writeString(draft, yaml);
            WorkerConfigLoader.load(draft);
            Files.move(draft, workerFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (ConfigException e) {
            deleteQuietly(draft);
            throw new CliException("this setup does not make a valid worker.yaml: "
                    + e.getMessage().replace(String.valueOf(draft), workerFile.toString()));
        } catch (IOException e) {
            deleteQuietly(draft);
            throw new CliException("cannot write " + workerFile + ": " + e.getMessage());
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            if (file != null) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
            // best effort: the write already failed, and this cleanup must not hide that failure
        }
    }

    private void offerService(Path workerFile, Map<String, String> environment) {
        // ServiceCommand.run already catches CliException/ConfigException itself (terminal.fail, return 1) rather
        // than letting one escape, so a try/catch here around it never fires; the returned status is what a failed
        // install actually looks like, and skipping it dropped the "Next:" hint below on that path.
        if (services != null && terminal.confirm("Keep your worker running in the background, also after a restart?", true)
                && services.run("install", () -> WorkerCommand.workerSpec(workerFile, environment)) == 0) {
            return;
        }
        terminal.say("");
        terminal.say("Next: dispatch check, then dispatch worker run (or dispatch worker service install).");
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
}
