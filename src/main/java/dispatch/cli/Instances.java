package dispatch.cli;

import dispatch.config.Config;
import dispatch.config.ConfigException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** The instances set up on this computer: the default one and each named one in its config folder (M). */
public final class Instances {

    /** @param name null for the default instance; {@code config}/{@code environment} null when {@code error} is set */
    public record Found(String name, Path configFile, Config config, Map<String, String> environment, String error) {
    }

    private Instances() {
    }

    /**
     * The config dirs, state dirs and clones of the other instances on this computer, which an agent of {@code ownConfigFile}'s
     * process must not see. Never throws: discovery must not block startup, so a failure hides fewer paths and is logged.
     */
    public static List<Path> othersPrivate(Path ownConfigFile, Map<String, String> processEnvironment) {
        return othersPrivate(Locations.current(), ownConfigFile, processEnvironment);
    }

    static List<Path> othersPrivate(Locations defaults, Path ownConfigFile, Map<String, String> processEnvironment) {
        try {
            Path own = ownConfigFile.toAbsolutePath().normalize();
            List<Path> hidden = new ArrayList<>();
            for (Found other : discover(defaults, processEnvironment)) {
                Path otherFile = other.configFile().toAbsolutePath().normalize();
                if (otherFile.equals(own)) {
                    continue;
                }
                // Its .env holds its bot token, whether or not its config loads.
                if (!hidden.contains(otherFile.getParent())) {
                    hidden.add(otherFile.getParent());
                }
                if (other.config() == null) {
                    dispatch.Log.warn("sandbox.instance_unreadable", "config", otherFile, "error", other.error());
                    continue;
                }
                hidden.add(other.config().stateDir());
                other.config().projects().stream().map(Config.Project::path).filter(java.util.Objects::nonNull)
                        .map(Path::of).forEach(hidden::add);
            }
            return hidden;
        } catch (RuntimeException e) {
            dispatch.Log.warn("sandbox.instances_not_discovered", "error", e.getMessage());
            return List.of();
        }
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

    /** Secrets the process environment carries for the instance dispatch was actually invoked for (RunCommand, ListCommand's caller, etc). */
    private static final Set<String> OWN_SECRETS = Set.of("TELEGRAM_BOT_TOKEN", "GH_TOKEN");

    private static Found load(String name, Path file, Map<String, String> processEnvironment) {
        try {
            // Never let this process's own instance's secret force itself onto another instance found here: each
            // instance's own .env must supply its own token, or a shared TELEGRAM_BOT_TOKEN in the environment (set
            // by the systemd unit, or by a test) would make every other instance look like it uses the same bot.
            RunCommand.Prepared prepared = RunCommand.prepare(file, withoutOwnSecrets(processEnvironment));
            return new Found(name, file, prepared.config(), prepared.environment(), null);
        } catch (CliException | ConfigException e) {
            return new Found(name, file, null, null, e.getMessage());
        }
    }

    private static Map<String, String> withoutOwnSecrets(Map<String, String> env) {
        if (OWN_SECRETS.stream().noneMatch(env::containsKey)) {
            return env;
        }
        Map<String, String> copy = new HashMap<>(env);
        OWN_SECRETS.forEach(copy::remove);
        return Map.copyOf(copy);
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
