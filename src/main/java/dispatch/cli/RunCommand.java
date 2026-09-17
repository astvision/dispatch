package dispatch.cli;

import dispatch.config.Config;
import dispatch.config.ConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** What `dispatch run` needs before the instance starts: its config and the environment with its secrets. */
public final class RunCommand {

    private RunCommand() {
    }

    /** @param environment the process environment plus the secrets file; the redactor and the agents need them too */
    public record Prepared(Config config, Map<String, String> environment) {
    }

    /** Throws {@link CliException} or {@link dispatch.config.ConfigException} with what to fix. */
    public static Prepared prepare(Path configFile, Map<String, String> processEnvironment) {
        if (!Files.exists(configFile)) {
            throw new CliException("no config at " + configFile + "; create one with: dispatch init");
        }
        Map<String, String> environment = SecretsFile.environment(configFile, processEnvironment);
        return new Prepared(ConfigLoader.load(configFile, environment), environment);
    }
}
