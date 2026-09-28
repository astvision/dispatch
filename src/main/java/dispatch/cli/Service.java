package dispatch.cli;

import dispatch.workspace.Git;
import dispatch.workspace.WorkspaceException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Dispatch kept running for the current user by the OS: a systemd user service on Linux, a launchd agent on macOS, a Task
 * Scheduler task on Windows (ADR 0016). Each starts it at login, restarts it after a failure, and runs it as `dispatch run`
 * with a log file.
 */
public interface Service {

    /**
     * @param java     the Java launcher to run Dispatch with
     * @param path     the PATH setup ran with, so claude, git and gh are found where a service's own PATH would miss them
     * @param stateDir where a service definition that needs a file of its own is kept
     * @param instance null for the default instance, else the name a second instance runs under (Locations.validName)
     */
    record Spec(Path java, Path jar, Path configFile, Path logFile, String path, Path stateDir, String instance) {

        public Spec(Path java, Path jar, Path configFile, Path logFile, String path, Path stateDir) {
            this(java, jar, configFile, logFile, path, stateDir, null);
        }

        /** What follows {@code -jar dispatch.jar}: a named instance is found by name, so its definition survives a moved config folder. */
        public List<String> arguments(Kind kind) {
            List<String> arguments = new java.util.ArrayList<>(kind.command());
            if (instance != null) {
                arguments.addAll(List.of("--instance", instance));
            } else {
                arguments.addAll(List.of("--config", configFile.toString()));
            }
            arguments.addAll(List.of("--log-file", logFile.toString()));
            return arguments;
        }
    }

    /** @param notes what the person should know, e.g. that it stops at logout */
    record Status(boolean installed, boolean running, String detail, List<String> notes) {
    }

    /** Runs a command to completion; how the service tools are called, and replaced in tests. */
    @FunctionalInterface
    interface Commands {
        Git.Result run(List<String> commandLine);
    }

    /** What the OS calls it, e.g. "systemd user service dispatch.service". */
    String describe();

    /** Writes the definition, registers it and starts it; replaces an earlier one. */
    void install(Spec spec);

    void start();

    void stop();

    /** Stops and starts it, e.g. to pick up a changed config; a service manager with its own restart overrides this. */
    default void restart() {
        stop();
        start();
    }

    Status status();

    void uninstall();

    /** What a Kind's service is. */
    Kind kind();

    /**
     * What a service definition runs: the team's Dispatch (`dispatch run`), or a member's own computer working on
     * their tasks (`dispatch worker run`, ADR 0021). The two differ only in their name, their description and the
     * arguments they pass, so one writer per OS produces both.
     */
    enum Kind {
        DISPATCH("dispatch.service", "io.dispatch.agent", "Dispatch", "Dispatch", "dispatch.log", "dispatch service",
                List.of("run")),
        WORKER("dispatch-worker.service", "io.dispatch.worker", "DispatchWorker", "Dispatch worker",
                "dispatch-worker.log", "dispatch worker service", List.of("worker", "run"));

        private final String systemdUnit;
        private final String launchdLabel;
        private final String windowsTask;
        private final String label;
        private final String logName;
        private final String manageCommand;
        private final List<String> command;

        Kind(String systemdUnit, String launchdLabel, String windowsTask, String label, String logName,
             String manageCommand, List<String> command) {
            this.systemdUnit = systemdUnit;
            this.launchdLabel = launchdLabel;
            this.windowsTask = windowsTask;
            this.label = label;
            this.logName = logName;
            this.manageCommand = manageCommand;
            this.command = command;
        }

        public String systemdUnit() {
            return systemdUnit;
        }

        public String systemdUnit(String instance) {
            return instance == null ? systemdUnit : systemdUnit.replace(".service", "-" + instance + ".service");
        }

        public String launchdLabel() {
            return launchdLabel;
        }

        public String launchdLabel(String instance) {
            return instance == null ? launchdLabel : launchdLabel + "." + instance;
        }

        public String windowsTask() {
            return windowsTask;
        }

        public String windowsTask(String instance) {
            return instance == null ? windowsTask : windowsTask + "-" + instance;
        }

        /** What it is called in a sentence, e.g. "Dispatch worker runs in the background as …". */
        public String label() {
            return label;
        }

        public String logName() {
            return logName;
        }

        /** The command that manages it, for the line setup prints. */
        public String manageCommand() {
            return manageCommand;
        }

        public String manageCommand(String instance) {
            return instance == null ? manageCommand : manageCommand + " --instance " + instance;
        }

        /** The dispatch arguments before --config and --log-file. */
        public List<String> command() {
            return command;
        }
    }

    /** @param user the current user: a login name, or DOMAIN\name on Windows */
    static Service forOs(String osName, Path home, Commands commands, String user) {
        return forOs(osName, home, commands, user, Kind.DISPATCH, null);
    }

    static Service forOs(String osName, Path home, Commands commands, String user, Kind kind) {
        return forOs(osName, home, commands, user, kind, null);
    }

    /** @param instance null for the default instance, else the name a second instance runs under */
    static Service forOs(String osName, Path home, Commands commands, String user, Kind kind, String instance) {
        if (osName.startsWith("Windows")) {
            return new WindowsTaskService(commands, user, kind, instance);
        }
        if (osName.startsWith("Mac")) {
            return new LaunchdService(home, commands, kind, instance);
        }
        return new SystemdService(home, commands, user, kind, instance);
    }

    /** The service for this OS and user, as `dispatch service` and the web UI manage it. */
    static Service forThisMachine() {
        return forThisMachine(Kind.DISPATCH);
    }

    /** @param kind DISPATCH for the team's own instance, WORKER for this computer's `dispatch worker run` */
    static Service forThisMachine(Kind kind) {
        return forThisMachine(kind, null);
    }

    /** @param instance null for the default instance, else the name a second instance runs under */
    static Service forThisMachine(Kind kind, String instance) {
        Path home = Path.of(System.getProperty("user.home"));
        String os = System.getProperty("os.name");
        String user = os.startsWith("Windows") && System.getenv("USERDOMAIN") != null
                ? System.getenv("USERDOMAIN") + "\\" + System.getProperty("user.name")
                : System.getProperty("user.name");
        Commands commands = commandLine -> {
            try {
                return Git.runProcess(commandLine, home, null, Duration.ofSeconds(60), String.join(" ", commandLine));
            } catch (WorkspaceException e) {
                return new Git.Result(127, "", e.getMessage());
            }
        };
        return forOs(os, home, commands, user, kind, instance);
    }

    /** Runs a service tool; a failure becomes a {@link CliException} with the tool's own words. */
    static Git.Result required(Commands commands, List<String> commandLine) {
        Git.Result result = commands.run(commandLine);
        if (result.exitCode() != 0) {
            String output = (result.stderr() + " " + result.stdout()).strip();
            throw new CliException(String.join(" ", commandLine) + " failed" + (output.isEmpty() ? "" : ": " + output));
        }
        return result;
    }

    /**
     * {@code kind.command()} unquoted, then {@code spec.arguments(kind)} quoted with {@code quote} — except a flag
     * ("--config", "--instance", "--log-file") and an {@code --instance} value, which are never quoted: an instance name
     * is already validated safe (Locations.validName), unlike a path, which may hold spaces.
     */
    static String argumentsLine(Spec spec, Kind kind, java.util.function.UnaryOperator<String> quote) {
        List<String> arguments = spec.arguments(kind);
        StringBuilder line = new StringBuilder(String.join(" ", kind.command()));
        for (int i = kind.command().size(); i < arguments.size(); i++) {
            String argument = arguments.get(i);
            boolean isFlag = argument.startsWith("--");
            boolean isInstanceName = i > 0 && arguments.get(i - 1).equals("--instance");
            line.append(' ').append(isFlag || isInstanceName ? argument : quote.apply(argument));
        }
        return line.toString();
    }

    static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
