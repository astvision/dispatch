package dispatch.agent.sandbox;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** What detection asks of the machine; a test answers for any OS. */
public interface Probe {

    String osName();

    /** The executable {@code command} on the PATH, if any. */
    Optional<Path> find(String command);

    /** Runs {@code commandLine} briefly; exit code -1 when it could not run or timed out. */
    Trial trial(List<String> commandLine);

    record Trial(int exitCode, String output) {
    }

    static Probe system(Map<String, String> environment) {
        return new Probe() {
            @Override
            public String osName() {
                return System.getProperty("os.name");
            }

            @Override
            public Optional<Path> find(String command) {
                String path = environment.getOrDefault("PATH", "");
                for (String dir : path.split(File.pathSeparator)) {
                    if (dir.isEmpty()) {
                        continue;
                    }
                    Path candidate = Path.of(dir, command);
                    if (Files.isExecutable(candidate)) {
                        return Optional.of(candidate);
                    }
                }
                return Optional.empty();
            }

            @Override
            public Trial trial(List<String> commandLine) {
                try {
                    Process process = new ProcessBuilder(commandLine).redirectErrorStream(true).start();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        return new Trial(-1, "timed out after 5 s");
                    }
                    return new Trial(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    return new Trial(-1, e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Trial(-1, "interrupted");
                }
            }
        };
    }
}
