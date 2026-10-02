package dispatch.agent.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dispatch.agent.AgentStartException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The plugins and MCP servers a machine's owner lists for Claude Code runs (spec: owner plugins). The lists are read
 * again from the config file before each run, so an edit applies to the next run without a restart, and each is resolved
 * against this machine's own Claude Code.
 */
public final class OwnerPlugins {

    /** No lists: every run's command line as before. */
    public static final OwnerPlugins NONE = new OwnerPlugins(null, false, null, Duration.ZERO);

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(60);

    /** The two lists as the file has them now. */
    public record Lists(List<String> plugins, List<String> mcpServers) {

        public Lists {
            plugins = List.copyOf(plugins);
            mcpServers = List.copyOf(mcpServers);
        }

        public boolean isEmpty() {
            return plugins.isEmpty() && mcpServers.isEmpty();
        }
    }

    private final Path file;
    private final boolean instance;
    private final Path home;
    private final Duration listTimeout;

    private OwnerPlugins(Path file, boolean instance, Path home, Duration listTimeout) {
        this.file = file;
        this.instance = instance;
        this.home = home;
        this.listTimeout = listTimeout;
    }

    /** An instance's {@code dispatch.yaml}: {@code agents.claude-code.plugins} and {@code mcpServers}. */
    public static OwnerPlugins instance(Path dispatchYaml, Path home) {
        return new OwnerPlugins(dispatchYaml, true, home, LIST_TIMEOUT);
    }

    /** A member's {@code worker.yaml}: {@code claudePlugins} and {@code claudeMcpServers}. */
    public static OwnerPlugins worker(Path workerYaml, Path home) {
        return new OwnerPlugins(workerYaml, false, home, LIST_TIMEOUT);
    }

    String pluginsKey() {
        return instance ? "agents.claude-code.plugins" : "claudePlugins";
    }

    String serversKey() {
        return instance ? "agents.claude-code.mcpServers" : "claudeMcpServers";
    }

    /** The lists as the file has them now; empty when the file has none. */
    public Lists lists() {
        if (file == null) {
            return new Lists(List.of(), List.of());
        }
        JsonNode root;
        try {
            root = YAML.readTree(file.toFile());
        } catch (IOException e) {
            throw new AgentStartException("cannot read the plugin lists from " + file + ": " + e.getMessage(), e);
        }
        JsonNode at = root == null ? MissingNode.getInstance() : instance ? root.path("agents").path("claude-code") : root;
        return new Lists(names(at, instance ? "plugins" : "claudePlugins", pluginsKey()),
                names(at, instance ? "mcpServers" : "claudeMcpServers", serversKey()));
    }

    private List<String> names(JsonNode at, String field, String key) {
        JsonNode list = at.get(field);
        if (list == null || list.isNull()) {
            return List.of();
        }
        if (!list.isArray()) {
            throw new AgentStartException(key + " in " + file + " must be a list of names", null);
        }
        List<String> names = new ArrayList<>();
        for (JsonNode name : list) {
            if (!name.isTextual() || name.asText().isBlank()) {
                throw new AgentStartException(key + " in " + file + " must be a list of names", null);
            }
            names.add(name.asText());
        }
        return names;
    }
}
