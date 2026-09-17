package dispatch.cli;

import dispatch.config.ConfigException;
import dispatch.workspace.Git;
import dispatch.workspace.WorkspaceException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** `dispatch service install | start | stop | status | uninstall` (ADR 0016). */
public final class ServiceCommand {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);

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
        Path home = Path.of(System.getProperty("user.home"));
        String os = System.getProperty("os.name");
        String user = os.startsWith("Windows") && System.getenv("USERDOMAIN") != null
                ? System.getenv("USERDOMAIN") + "\\" + System.getProperty("user.name")
                : System.getProperty("user.name");
        Service.Commands commands = commandLine -> {
            try {
                return Git.runProcess(commandLine, home, null, COMMAND_TIMEOUT, String.join(" ", commandLine));
            } catch (WorkspaceException e) {
                return new Git.Result(127, "", e.getMessage());
            }
        };
        return new ServiceCommand(terminal, Service.forOs(os, home, commands, user), runningJar());
    }

    public int run(Cli.Service options, Map<String, String> processEnvironment) {
        try {
            switch (options.action()) {
                case "install" -> install(options.configFile(), processEnvironment);
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
                default -> throw new CliException("unknown service action " + options.action());
            }
            return 0;
        } catch (CliException | UncheckedIOException e) {
            terminal.fail(e.getMessage());
            return 1;
        }
    }

    /** Checks the config and its secrets first, so a service never starts on a setup that cannot run. */
    void install(Path configFile, Map<String, String> processEnvironment) {
        RunCommand.Prepared prepared;
        try {
            prepared = RunCommand.prepare(configFile, processEnvironment);
        } catch (ConfigException e) {
            throw new CliException(e.getMessage());
        }
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new CliException("the service runs dispatch.jar, but this is not running from it; install Dispatch first");
        }
        Path java = Executables.serviceJava(Path.of(ProcessHandle.current().info().command().orElse("java")),
                System.getProperty("os.name"), processEnvironment, Path.of(System.getProperty("user.home")));
        String path = processEnvironment.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("PATH"))
                .map(Map.Entry::getValue).findFirst().orElse("");
        Service.Spec spec = new Service.Spec(java, jar.toAbsolutePath(), configFile.toAbsolutePath(),
                prepared.config().stateDir().resolve("dispatch.log"), path, prepared.config().stateDir());
        terminal.during("Installing the " + service.describe(), () -> {
            service.install(spec);
            return null;
        });
        terminal.ok("Dispatch runs in the background as " + service.describe());
        terminal.say("  Logs:   " + spec.logFile());
        terminal.say("  Manage: dispatch service status | stop | start | uninstall");
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

    private static Path runningJar() {
        try {
            Path location = Path.of(ServiceCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return location.toString().endsWith(".jar") ? location : null;
        } catch (URISyntaxException | SecurityException e) {
            return null;
        }
    }
}
