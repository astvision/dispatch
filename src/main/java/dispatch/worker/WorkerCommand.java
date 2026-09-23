package dispatch.worker;

import dispatch.Log;
import dispatch.OwnerOnly;
import dispatch.Redactor;
import dispatch.agent.Agent;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.cli.Cli;
import dispatch.cli.CliException;
import dispatch.cli.SecretsFile;
import dispatch.cli.Service;
import dispatch.cli.ServiceCommand;
import dispatch.cli.Terminal;
import dispatch.config.ConfigException;
import dispatch.core.ActiveRuns;
import dispatch.workspace.Delivery;
import dispatch.workspace.Gh;
import dispatch.workspace.Git;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** dispatch worker pair and dispatch worker run. */
public final class WorkerCommand {

    /** The key's name in worker.env, which only its owner may read (ADR 0009). */
    public static final String KEY_VARIABLE = "DISPATCH_WORKER_KEY";
    /** How long shutdown waits for a `git worktree remove` already underway to finish before giving up on it. */
    private static final Duration SWEEPER_STOP_TIMEOUT = Duration.ofSeconds(10);

    private final PrintStream out;

    public WorkerCommand(PrintStream out) {
        this.out = out;
    }

    /** Exchanges the code for a key, writes worker.env owner-only and starts worker.yaml if there is none yet. */
    public int pair(Cli.WorkerPair options) {
        WorkerClient.Paired paired;
        try {
            paired = WorkerClient.pair(HttpClient.newHttpClient(), URI.create(options.url()), options.code(), options.name());
        } catch (RuntimeException e) {
            out.println("pairing failed: " + e.getMessage());
            return 1;
        }
        Path env = SecretsFile.beside(options.workerFile());
        try {
            // A worker machine never runs `dispatch init`, so ~/.config/dispatch (or wherever --config points) usually
            // does not exist yet; without this, the code the member was just given is burned for nothing (SecretsFile.write
            // creates the file itself, but not its parent directory). toAbsolutePath() first: a bare "--config worker.yaml"
            // has no parent as given, and getParent() on it would NPE past this try's IOException catch, burning the code
            // just the same.
            OwnerOnly.createDirectories(options.workerFile().toAbsolutePath().getParent());
            Map<String, String> values = new LinkedHashMap<>(Files.exists(env) ? SecretsFile.read(env) : Map.of());
            values.put(KEY_VARIABLE, paired.key());
            SecretsFile.write(env, values);
            if (!Files.exists(options.workerFile())) {
                OwnerOnly.createFile(options.workerFile());
                Files.writeString(options.workerFile(), """
                        team: %s
                        name: %s
                        maxConcurrentRuns: 1
                        # projects:
                        #   crm:
                        #     path: /absolute/path/to/your/clone
                        """.formatted(options.url(), options.name()));
            }
        } catch (IOException e) {
            out.println("cannot write " + env + ": " + e.getMessage());
            return 1;
        }
        out.println("Paired with " + paired.team() + " as " + options.name() + " (#" + paired.workerId() + ").");
        out.println("Add your clones to " + options.workerFile() + ", then run: dispatch worker run");
        return 0;
    }

    /**
     * What the dispatch-worker service runs: this process's jar, `worker run`, and this worker's own log file.
     *
     * @throws CliException when worker.yaml is invalid, or worker.env holds no key yet — the team side upholds the
     *                       same invariant for its own service ({@link ServiceCommand#specFor(Path, Path, Map)} via
     *                       {@link RunCommand#prepare}): a service must never be installed on a setup that cannot
     *                       start, or it restarts every 10 s forever, failing the same way each time.
     */
    public static Service.Spec workerSpec(Path workerFile, Map<String, String> environment) {
        WorkerConfig config = WorkerConfigLoader.load(workerFile);
        Path envFile = SecretsFile.beside(workerFile);
        if (SecretsFile.environment(workerFile, environment).get(KEY_VARIABLE) == null) {
            throw new CliException("no worker key in " + envFile + "; run: dispatch worker init");
        }
        return ServiceCommand.specFor(ServiceCommand.runningJar(), workerFile, config.stateDir(),
                Service.Kind.WORKER.logName(), environment);
    }

    /** `dispatch worker service install | start | stop | status | uninstall` (ADR 0016, 0021). */
    public static int service(Terminal terminal, Cli.WorkerService options, Map<String, String> environment) {
        ServiceCommand command = new ServiceCommand(terminal, Service.forThisMachine(Service.Kind.WORKER),
                ServiceCommand.runningJar());
        return command.run(options.action(), () -> workerSpec(options.workerFile(), environment));
    }

    public int run(Cli.WorkerRun options, Map<String, String> environment) throws InterruptedException {
        WorkerConfig config;
        String key;
        try {
            config = WorkerConfigLoader.load(options.workerFile());
            Map<String, String> secrets = SecretsFile.environment(options.workerFile(), environment);
            key = secrets.get(KEY_VARIABLE);
            if (key == null) {
                throw new CliException("no worker key in " + SecretsFile.beside(options.workerFile())
                        + "; run: dispatch worker pair <url> <code>");
            }
            environment = secrets;
        } catch (ConfigException | CliException e) {
            out.println(e.getMessage());
            return 2;
        }
        Log.useRedactor(Redactor.fromEnvironment(environment));
        Git git = new Git("git", environment.get("GH_TOKEN"), Duration.ofMinutes(5));
        Workspaces workspaces = new Workspaces(config.stateDir(), git);
        workspaces.createDirectories().ifPresent(warning -> Log.warn("state.permissions_too_open", "detail", warning));
        FileChannel lock;
        try {
            lock = lockStateDir(config.stateDir());
        } catch (CliException e) {
            out.println(e.getMessage());
            return 1;
        }
        try {
            WorkerClient client = new WorkerClient(HttpClient.newHttpClient(), URI.create(config.team()), key);
            WorkerClient.Setup setup;
            try {
                setup = client.setup();
            } catch (WorkerClient.RevokedException e) {
                out.println("paired key revoked: pair again with a new code from /worker");
                return 1;
            } catch (RuntimeException e) {
                out.println("cannot reach " + config.team() + ": " + e.getMessage());
                return 1;
            }
            Delivery delivery = new Delivery(git, new Gh(config.ghCommand(), environment.get("GH_TOKEN"), Duration.ofMinutes(2)),
                    setup.authorName(), setup.authorEmail());
            Map<String, Agent> agents = Map.of("claude-code",
                    new ClaudeCodeAgent(config.claudeCommand(), environment, Duration.ofSeconds(10)));
            ActiveRuns activeRuns = new ActiveRuns();
            WorkerLoop loop = new WorkerLoop(config, client, agents, workspaces, delivery,
                    Redactor.fromEnvironment(environment), activeRuns);
            WorkerSweeper sweeper = new WorkerSweeper(config.stateDir(), workspaces, activeRuns, Clock.systemUTC());
            Thread sweeperThread = Thread.ofVirtual().name("worker-sweeper").start(sweeper);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                sweeper.stop();
                // Joined here, not left to the code after loop.run() below: on Ctrl+C the JVM can halt as soon as every
                // shutdown hook returns, without waiting for the main thread to unwind — a removal caught mid `git
                // worktree remove --force` would leave a half-deleted directory that wedges that task for good.
                joinQuietly(sweeperThread, SWEEPER_STOP_TIMEOUT);
                loop.stop();
            }, "dispatch-worker-shutdown"));
            out.println("Running " + setup.team() + " tasks on this computer as " + config.name() + ". Ctrl+C to stop.");
            loop.run();
            sweeper.stop();
            joinQuietly(sweeperThread, SWEEPER_STOP_TIMEOUT);
            return 0;
        } finally {
            // Not released on the abrupt Ctrl+C/kill-9 path (the shutdown hook above does not run this far up its own
            // stack) — the OS drops the advisory lock with the process either way, exactly as ConfigFile.edit's own
            // .lock relies on.
            closeQuietly(lock);
        }
    }

    /**
     * Refuses a second {@code dispatch worker run} sharing this state directory: two of them would otherwise kill
     * each other's agents through {@link LocalAgents#killOrphans} the moment the second one starts. A lock file
     * beside {@code agents/}, held exclusively for as long as this process runs — the same idea as
     * {@link dispatch.config.ConfigFile#edit}'s own {@code .lock}, but held for the run's whole lifetime rather than
     * one edit.
     */
    private static FileChannel lockStateDir(Path stateDir) {
        Path lockFile = stateDir.resolve("agents.lock");
        FileChannel channel;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new CliException("cannot lock " + lockFile + ": " + e.getMessage());
        }
        try {
            // A second FileChannel on the same file within THIS process throws OverlappingFileLockException instead
            // of returning null (file locks are held per JVM, not per channel); a second dispatch process gets null.
            // Either way, a run already holds this state directory.
            if (channel.tryLock() == null) {
                throw heldElsewhere(stateDir, channel);
            }
        } catch (OverlappingFileLockException e) {
            throw heldElsewhere(stateDir, channel);
        } catch (IOException e) {
            closeQuietly(channel);
            throw new CliException("cannot lock " + lockFile + ": " + e.getMessage());
        }
        return channel;
    }

    private static CliException heldElsewhere(Path stateDir, FileChannel channel) {
        closeQuietly(channel);
        return new CliException("another dispatch worker run already holds " + stateDir
                + "; stop it first (dispatch worker service stop, if it runs as the service), then try again");
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            Log.warn("worker.lock_not_closed", "error", e.getMessage());
        }
    }

    private static void joinQuietly(Thread thread, Duration timeout) {
        try {
            thread.join(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
