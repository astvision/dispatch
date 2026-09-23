package dispatch.cli;

import java.nio.file.Path;
import java.util.Map;

/**
 * Where an instance keeps its config and state unless told otherwise (ADR 0014). It follows each OS's convention, in the
 * same places the GitHub CLI uses: %APPDATA% and %LOCALAPPDATA% on Windows, the XDG directories on macOS and Linux.
 */
public record Locations(Path configFile, Path stateDir) {

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
}
