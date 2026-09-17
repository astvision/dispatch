package dispatch.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Finds a command as a shell would on each OS, then in the directories Claude Code installs itself into. */
final class Executables {

    private Executables() {
    }

    static Optional<Path> find(String name, String osName, Map<String, String> env, Path home) {
        boolean windows = osName.startsWith("Windows");
        List<Path> dirs = new ArrayList<>();
        String path = variable(env, "PATH");
        if (path != null) {
            for (String entry : path.split(windows ? ";" : ":")) {
                try {
                    if (!entry.isBlank()) {
                        dirs.add(Path.of(entry.strip()));
                    }
                } catch (InvalidPathException e) {
                    // A malformed PATH entry holds nothing to run.
                }
            }
        }
        dirs.add(home.resolve(".local").resolve("bin"));
        dirs.add(home.resolve(".claude").resolve("local"));
        List<String> extensions = windows ? extensions(env) : List.of("");
        for (Path dir : dirs) {
            for (String extension : extensions) {
                Path candidate = dir.resolve(name + extension);
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The running Java by the name Dispatch's launcher runs it with, JAVA_HOME's java or else the one on PATH, when that name
     * leads to the same binary. A service must not name the running binary's own path where a name like that exists: with
     * Homebrew, Fedora and the Windows installers that path is versioned (…/Cellar/openjdk/25.0.1/…), gone after an update.
     */
    static Path serviceJava(Path running, String osName, Map<String, String> env, Path home) {
        String javaHome = variable(env, "JAVA_HOME");
        Optional<Path> named;
        try {
            named = javaHome == null || javaHome.isBlank()
                    ? find("java", osName, env, home)
                    : Optional.of(Path.of(javaHome.strip(), "bin", osName.startsWith("Windows") ? "java.exe" : "java"));
        } catch (InvalidPathException e) {
            return running;
        }
        return named.filter(candidate -> sameFile(candidate, running)).orElse(running);
    }

    private static boolean sameFile(Path candidate, Path running) {
        try {
            return Files.isSameFile(candidate, running);
        } catch (IOException e) {
            return false;
        }
    }

    private static List<String> extensions(Map<String, String> env) {
        String pathext = variable(env, "PATHEXT");
        if (pathext == null) {
            return List.of(".exe", ".cmd", ".bat", ".com");
        }
        return Arrays.stream(pathext.split(";")).filter(extension -> !extension.isBlank()).map(String::toLowerCase).toList();
    }

    /** Environment variable names are case-insensitive on Windows, where PATH is usually spelled Path. */
    private static String variable(Map<String, String> env, String name) {
        return env.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }
}
