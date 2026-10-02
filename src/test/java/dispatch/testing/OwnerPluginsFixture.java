package dispatch.testing;

import dispatch.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
}
