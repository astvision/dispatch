package dispatch.cli;

import dispatch.OwnerOnly;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The secrets beside a config file: {@code dispatch.env} for {@code dispatch.yaml}. It holds KEY=VALUE lines, as systemd's
 * EnvironmentFile reads them, and only its owner may read it (ADR 0009, 0014).
 */
public final class SecretsFile {

    private SecretsFile() {
    }

    public static Path beside(Path configFile) {
        String name = configFile.getFileName().toString().replaceFirst("\\.ya?ml$", "");
        return configFile.resolveSibling(name + ".env");
    }

    /**
     * The environment to run with: the process environment, plus this config's secrets for anything it lacks. A file the
     * process cannot read is fine when the token is already in the environment, as when systemd read it as root.
     */
    public static Map<String, String> environment(Path configFile, Map<String, String> processEnvironment) {
        Path file = beside(configFile);
        if (!Files.exists(file)) {
            return processEnvironment;
        }
        if (!Files.isReadable(file)) {
            if (processEnvironment.containsKey("TELEGRAM_BOT_TOKEN")) {
                return processEnvironment;
            }
            throw new CliException("cannot read " + file + "; it must belong to the user who runs Dispatch");
        }
        try {
            Optional<String> open = OwnerOnly.groupOrOthersAccess(file);
            if (open.isPresent()) {
                throw new CliException(file + " holds secrets but other users can read it (" + open.get() + "); run: chmod 600 " + file);
            }
            Map<String, String> merged = new HashMap<>(read(file));
            merged.putAll(processEnvironment);
            return Map.copyOf(merged);
        } catch (IOException e) {
            throw new CliException("cannot read " + file + ": " + e.getMessage());
        }
    }

    /** Values of KEY=VALUE lines, unquoted; blank values are left out. A malformed line is reported without its content. */
    static Map<String, String> read(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                throw new CliException("line " + (i + 1) + " of " + file + " is not KEY=VALUE");
            }
            String value = unquote(line.substring(equals + 1).strip());
            if (!value.isEmpty()) {
                values.put(line.substring(0, equals).strip(), value);
            }
        }
        return values;
    }

    /** Replaces the file with one that only its owner can read, created so before a secret is written. */
    static void write(Path file, Map<String, String> values) throws IOException {
        Files.deleteIfExists(file);
        OwnerOnly.createFile(file);
        StringBuilder text = new StringBuilder("# Dispatch secrets, readable only by you. Never commit or share this file.\n");
        values.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        Files.writeString(file, text);
    }

    private static String unquote(String value) {
        boolean quoted = value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"));
        return quoted ? value.substring(1, value.length() - 1) : value;
    }
}
