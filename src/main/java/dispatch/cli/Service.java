package dispatch.cli;

import dispatch.workspace.Git;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
     */
    record Spec(Path java, Path jar, Path configFile, Path logFile, String path, Path stateDir) {
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

    Status status();

    void uninstall();

    /** @param user the current user: a login name, or DOMAIN\name on Windows */
    static Service forOs(String osName, Path home, Commands commands, String user) {
        if (osName.startsWith("Windows")) {
            return new WindowsTaskService(commands, user);
        }
        if (osName.startsWith("Mac")) {
            return new LaunchdService(home, commands);
        }
        return new SystemdService(home, commands, user);
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

    static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
