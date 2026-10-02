package dispatch.agent.claude;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.domain.CuratedPlugins;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where this machine's copy of the official marketplace keeps a plan's picks (spec: plugin picks). Claude Code keeps the
 * marketplace cloned under ~/.claude, and a plugin hosted in it is a directory there, so nothing is installed or fetched.
 * A pick this machine cannot find is skipped, not fatal: the agent chose it, the owner did not.
 */
final class OfficialPlugins {

    private OfficialPlugins() {
    }

    /** The marketplace's directory under {@code home}. */
    static Path marketplace(Path home) {
        return home.resolve(".claude/plugins/marketplaces").resolve(CuratedPlugins.MARKETPLACE);
    }

    /** Each pick found in {@code home}'s marketplace copy, by name, in the order given; the rest are skipped and logged. */
    static Map<String, Path> find(Path home, List<String> picks) {
        Map<String, Path> found = new LinkedHashMap<>();
        if (picks.isEmpty()) {
            return found;
        }
        if (home == null) {
            picks.forEach(pick -> skipped(pick, "this machine has no home directory to look in"));
            return found;
        }
        Path marketplace = marketplace(home).toAbsolutePath().normalize();
        Path catalog = marketplace.resolve(".claude-plugin/marketplace.json");
        JsonNode entries;
        try {
            entries = Json.MAPPER.readTree(catalog.toFile()).path("plugins");
        } catch (IOException e) {
            picks.forEach(pick -> skipped(pick, "cannot read " + catalog + ": " + e.getMessage()));
            return found;
        }
        for (String pick : picks) {
            Path dir = directory(marketplace, entries, pick);
            if (dir != null) {
                found.put(pick, dir);
            }
        }
        return found;
    }

    private static Path directory(Path marketplace, JsonNode entries, String pick) {
        for (JsonNode entry : entries) {
            if (!entry.path("name").asText().equals(pick)) {
                continue;
            }
            JsonNode source = entry.get("source");
            // A third-party plugin's source is an object (git or URL) that only an install fetches.
            if (source == null || !source.isTextual()) {
                skipped(pick, "its marketplace source is not a directory in the marketplace");
                return null;
            }
            Path dir = marketplace.resolve(source.asText()).normalize();
            if (!dir.startsWith(marketplace)) {
                skipped(pick, "its marketplace source " + source.asText() + " is outside the marketplace");
                return null;
            }
            if (!Files.isRegularFile(dir.resolve(".claude-plugin/plugin.json"))) {
                skipped(pick, "no plugin at " + dir);
                return null;
            }
            return dir;
        }
        skipped(pick, "the marketplace at " + marketplace + " has no such plugin");
        return null;
    }

    private static void skipped(String pick, String why) {
        Log.warn("agent.plugin_skipped", "plugin", pick, "reason", why);
    }
}
