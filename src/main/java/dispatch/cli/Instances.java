package dispatch.cli;

import dispatch.config.Config;
import dispatch.config.ConfigException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** The instances set up on this computer: the default one and each named one in its config folder (M). */
public final class Instances {

    /** @param name null for the default instance; {@code config}/{@code environment} null when {@code error} is set */
    public record Found(String name, Path configFile, Config config, Map<String, String> environment, String error) {
    }

    private Instances() {
    }

    /** The default instance first (only if its file exists), then named ones sorted by name; never throws for a single bad file. */
    public static List<Found> discover(Locations defaults, Map<String, String> processEnvironment) {
        List<Found> found = new ArrayList<>();
        if (Files.exists(defaults.configFile())) {
            found.add(load(null, defaults.configFile(), processEnvironment));
        }
        List<Path> named;
        try (Stream<Path> files = Files.list(defaults.configDir())) {
            named = files.filter(file -> defaults.instanceOf(file) != null).sorted().toList();
        } catch (NoSuchFileException e) {
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + defaults.configDir() + ": " + e.getMessage(), e);
        }
        named.forEach(file -> found.add(load(defaults.instanceOf(file), file, processEnvironment)));
        return found;
    }

    private static Found load(String name, Path file, Map<String, String> processEnvironment) {
        try {
            RunCommand.Prepared prepared = RunCommand.prepare(file, processEnvironment);
            return new Found(name, file, prepared.config(), prepared.environment(), null);
        } catch (CliException | ConfigException e) {
            return new Found(name, file, null, null, e.getMessage());
        }
    }

    /** The digits before the token's ':', or null: never the part that must stay secret. */
    public static String botId(String token) {
        if (token == null) {
            return null;
        }
        int colon = token.indexOf(':');
        return colon > 0 ? token.substring(0, colon) : null;
    }
}
