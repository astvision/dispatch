package dispatch.cli;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * Where an instance keeps its config and state unless told otherwise (ADR 0014). It follows each OS's convention, in the
 * same places the GitHub CLI uses: %APPDATA% and %LOCALAPPDATA% on Windows, the XDG directories on macOS and Linux.
 */
public record Locations(Path configFile, Path stateDir) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Set<String> RESERVED = Set.of("dispatch", "worker");

    public static Locations current() {
        return of(System.getProperty("os.name"), System.getenv(), Path.of(System.getProperty("user.home")));
    }

    /** The member's own worker settings, beside the config file. */
    public Path workerFile() {
        return configFile.resolveSibling("worker.yaml");
    }

    static Locations of(String osName, Map<String, String> env, Path home) {
        if (osName.startsWith("Windows")) {
            Path roaming = directory(env, "APPDATA", home.resolve("AppData").resolve("Roaming"));
            Path local = directory(env, "LOCALAPPDATA", home.resolve("AppData").resolve("Local"));
            return new Locations(roaming.resolve("Dispatch").resolve("dispatch.yaml"), local.resolve("Dispatch"));
        }
        Path config = directory(env, "XDG_CONFIG_HOME", home.resolve(".config"));
        Path state = directory(env, "XDG_STATE_HOME", home.resolve(".local").resolve("state"));
        return new Locations(config.resolve("dispatch").resolve("dispatch.yaml"), state.resolve("dispatch"));
    }

    /** A blank variable counts as unset, as the XDG specification says. */
    private static Path directory(Map<String, String> env, String variable, Path fallback) {
        String value = env.get(variable);
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }

    /** An instance name: a file name, a service name and a branch segment at once, so kept to what all three accept. */
    public static String validName(String name) {
        if (name == null || !NAME.matcher(name).matches() || RESERVED.contains(name)) {
            throw new CliException("an instance name is 1-32 of a-z, 0-9 and '-', starting with a letter, and not "
                    + "'dispatch' or 'worker'; got '" + name + "'");
        }
        return name;
    }

    /** The named instance's files: beside the default instance's, never inside its state directory. */
    public Locations forInstance(String name) {
        if (name == null) {
            return this;
        }
        validName(name);
        return new Locations(configFile.resolveSibling(name + ".yaml"),
                stateDir.resolveSibling(stateDir.getFileName() + "-" + name));
    }

    /** @return the instance {@code file} is the config of; null for the default instance or a file elsewhere */
    public String instanceOf(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.getParent().equals(configDir().toAbsolutePath().normalize())) {
            return null;
        }
        String name = absolute.getFileName().toString();
        if (!name.endsWith(".yaml")) {
            return null;
        }
        String base = name.substring(0, name.length() - ".yaml".length());
        return NAME.matcher(base).matches() && !RESERVED.contains(base) ? base : null;
    }

    public Path configDir() {
        return configFile.getParent();
    }
}
