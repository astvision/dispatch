package dispatch.testing;

import dispatch.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/** Installed Claude Code plugins for the tests of the owner's plugins (spec: owner plugins). */
public final class OwnerPluginsFixture {

    private OwnerPluginsFixture() {
    }

    /** A plugin directory with its manifest and, unless {@code servers} is null, a {@code .mcp.json} holding it. */
    public static Path plugin(Path dir, String servers) throws IOException {
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"), "{\"name\": \"" + dir.getFileName() + "\"}");
        if (servers != null) {
            Files.writeString(dir.resolve(".mcp.json"), servers);
        }
        return dir;
    }

    /** What the fake claude at {@code claude} answers to {@code plugin list --json}. */
    public static void installed(Path claude, Map<String, Path> plugins) throws IOException {
        String entries = plugins.entrySet().stream()
                .map(e -> "{\"id\": \"" + e.getKey() + "\", \"installPath\": " + Json.write(e.getValue().toString()) + "}")
                .collect(Collectors.joining(","));
        Files.writeString(claude.resolveSibling("fake-claude.plugins.json"), "[" + entries + "]");
    }

    /**
     * An official marketplace copy under {@code home}, as Claude Code keeps it, hosting each plugin at ./plugins/<name>
     * with its {@code .mcp.json} holding the given servers, or none when they are "".
     */
    public static Path marketplace(Path home, Map<String, String> plugins) throws IOException {
        Path marketplace = home.resolve(".claude/plugins/marketplaces/claude-plugins-official");
        Files.createDirectories(marketplace.resolve(".claude-plugin"));
        StringJoiner entries = new StringJoiner(",");
        for (Map.Entry<String, String> plugin : plugins.entrySet()) {
            plugin(marketplace.resolve("plugins").resolve(plugin.getKey()), plugin.getValue().isEmpty() ? null : plugin.getValue());
            entries.add("{\"name\": \"" + plugin.getKey() + "\", \"source\": \"./plugins/" + plugin.getKey() + "\"}");
        }
        Files.writeString(marketplace.resolve(".claude-plugin/marketplace.json"), "{\"plugins\": [" + entries + "]}");
        return marketplace;
    }
}
