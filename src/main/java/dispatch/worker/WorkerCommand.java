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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** dispatch worker pair and dispatch worker run. */
public final class WorkerCommand {

    /** The key's name in worker.env, which only its owner may read (ADR 0009). */
    public static final String KEY_VARIABLE = "DISPATCH_WORKER_KEY";

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

    /** What the dispatch-worker service runs: this process's jar, `worker run`, and this worker's own log file. */
    public static Service.Spec workerSpec(Path workerFile, Map<String, String> environment) {
        WorkerConfig config = WorkerConfigLoader.load(workerFile);
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
        Git git = new Git("git", environment.get("GH_TOKEN"), Duration.ofMinutes(5));
        Workspaces workspaces = new Workspaces(config.stateDir(), git);
        workspaces.createDirectories().ifPresent(warning -> Log.warn("state.permissions_too_open", "detail", warning));
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
            loop.stop();
        }, "dispatch-worker-shutdown"));
        out.println("Running " + setup.team() + " tasks on this computer as " + config.name() + ". Ctrl+C to stop.");
        loop.run();
        sweeper.stop();
        sweeperThread.join(Duration.ofSeconds(10));
        return 0;
    }
}
