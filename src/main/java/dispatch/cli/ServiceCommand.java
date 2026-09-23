package dispatch.cli;

import dispatch.config.ConfigException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/** `dispatch service install | start | stop | status | uninstall` (ADR 0016). */
public final class ServiceCommand {

    private final Terminal terminal;
    private final Service service;
    private final Path jar;

    /** @param jar the dispatch.jar the service runs; the one this process runs from */
    public ServiceCommand(Terminal terminal, Service service, Path jar) {
        this.terminal = terminal;
        this.service = service;
        this.jar = jar;
    }

    /** The service for this OS and user, run with this process's Java and jar. */
    public static ServiceCommand forThisMachine(Terminal terminal) {
        return new ServiceCommand(terminal, Service.forThisMachine(), runningJar());
    }

    public int run(Cli.Service options, Map<String, String> processEnvironment) {
        return run(options.action(), () -> specFor(jar, options.configFile(), processEnvironment));
    }

    /** @param spec read only when the action needs it, so status/stop work on a config this process cannot load */
    public int run(String action, Supplier<Service.Spec> spec) {
        try {
            switch (action) {
                case "install" -> install(spec.get());
                case "start" -> {
                    service.start();
                    terminal.ok("started " + service.describe());
                }
                case "stop" -> {
                    service.stop();
                    terminal.ok("stopped " + service.describe());
                }
                case "status" -> status();
                case "uninstall" -> {
                    service.uninstall();
                    terminal.ok("removed " + service.describe());
                }
                default -> throw new CliException("unknown service action " + action);
            }
            return 0;
        } catch (CliException | ConfigException | UncheckedIOException e) {
            terminal.fail(e.getMessage());
            return 1;
        }
    }

    /** Checks the config and its secrets first, so a service never starts on a setup that cannot run. */
    void install(Path configFile, Map<String, String> processEnvironment) {
        install(specFor(jar, configFile, processEnvironment));
    }

    void install(Service.Spec spec) {
        terminal.during("Installing the " + service.describe(), () -> {
            service.install(spec);
            return null;
        });
        terminal.ok(service.kind().label() + " runs in the background as " + service.describe());
        terminal.say("  Logs:   " + spec.logFile());
        terminal.say("  Manage: " + service.kind().manageCommand() + " status | stop | start | uninstall");
        service.status().notes().forEach(terminal::warn);
    }

    private void status() {
        Service.Status status = service.status();
        if (!status.installed()) {
            terminal.warn("not installed; install it with: dispatch service install");
            return;
        }
        if (status.running()) {
            terminal.ok(service.describe() + ": " + status.detail());
        } else {
            terminal.warn(service.describe() + ": " + status.detail());
        }
        status.notes().forEach(terminal::warn);
    }

    public static Path runningJar() {
        try {
            Path location = Path.of(ServiceCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return location.toString().endsWith(".jar") ? location : null;
        } catch (URISyntaxException | SecurityException e) {
            return null;
        }
    }

    /** What the team's service runs: this process's Java, {@code jar}, and the config, whose secrets are checked first. */
    public static Service.Spec specFor(Path jar, Path configFile, Map<String, String> processEnvironment) {
        RunCommand.Prepared prepared;
        try {
            prepared = RunCommand.prepare(configFile, processEnvironment);
        } catch (ConfigException e) {
            throw new CliException(e.getMessage());
        }
        return specFor(jar, configFile, prepared.config().stateDir(), Service.Kind.DISPATCH.logName(), processEnvironment);
    }

    /**
     * The same spec without loading a {@code dispatch.yaml}: a worker's config is a {@code worker.yaml}, which its own
     * caller has already read (see {@code WorkerCommand.workerSpec}).
     */
    public static Service.Spec specFor(Path jar, Path configFile, Path stateDir, String logName,
                                       Map<String, String> processEnvironment) {
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new CliException("the service runs dispatch.jar, but this is not running from it; install Dispatch first");
        }
        Path java = Executables.serviceJava(Path.of(ProcessHandle.current().info().command().orElse("java")),
                System.getProperty("os.name"), processEnvironment, Path.of(System.getProperty("user.home")));
        String path = processEnvironment.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("PATH"))
                .map(Map.Entry::getValue).findFirst().orElse("");
        return new Service.Spec(java, jar.toAbsolutePath(), configFile.toAbsolutePath(), stateDir.resolve(logName),
                path, stateDir);
    }
}
