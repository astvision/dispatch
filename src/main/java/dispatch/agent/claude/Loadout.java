package dispatch.agent.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.agent.DataAccess;
import dispatch.agent.RunRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What one Claude Code run loads beyond its working directory: Dispatch's skills plugin (ADR 0034), what the machine's
 * owner lists (ADR 0036), the plan's picks (ADR 0037) and the project's data server (spec: data reports). Made once per
 * run, on the machine that runs it; the command line and the sandbox only read it.
 *
 * @param pluginDirs  every plugin the run loads, in the order they are named: the job's own, the owner's and the picks,
 *                    then the plugin of links to the owner's skills
 * @param skillDirs   the owner's skill directories those links point to, which the run must be able to read
 * @param mcpConfig   the one inline MCP configuration, null when the run starts no server
 * @param environment the variables its servers' secrets travel in; on a run with data, only the data server's
 */
record Loadout(List<Path> pluginDirs, List<Path> skillDirs, String mcpConfig, Map<String, String> environment) {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Pinned: the tool sets a level allows were probed on this version, and a newer one may add tools. */
    private static final List<String> DATA_SERVER_ARGS = List.of("-y", "mongodb-mcp-server@2.1.2");
    /** A cold npx download of the server outlasted Claude Code's 30 s connect timeout (probed 2026-10-23). */
    private static final String MCP_TIMEOUT_MS = "120000";

    Loadout {
        pluginDirs = List.copyOf(pluginDirs);
        skillDirs = List.copyOf(skillDirs);
        environment = Map.copyOf(environment);
    }

    /** @param ownerSkillsPlugin the plugin of links to the owner's skills, null when they list none */
    static Loadout of(RunRequest request, OwnerPlugins.Resolved owner, Path ownerSkillsPlugin) {
        List<Path> dirs = new ArrayList<>(request.pluginDirs());
        dirs.addAll(owner.pluginDirs());
        if (ownerSkillsPlugin != null) {
            dirs.add(ownerSkillsPlugin);
        }
        if (request.data() == null) {
            return new Loadout(dirs, owner.skills(), owner.mcpConfig(), owner.environment());
        }
        // The data server alone, and none of the owner's servers or their secrets: another server (a Mongo plugin, say) could
        // read the URI from the environment and query past the level's gate.
        Map<String, String> environment = Map.of(DataAccess.URI_VARIABLE, request.data().connectionString(),
                "MCP_TIMEOUT", MCP_TIMEOUT_MS);
        return new Loadout(dirs, owner.skills(), dataServerConfig(request.data().level()), environment);
    }

    private static String dataServerConfig(DataAccess.Level level) {
        try {
            ObjectNode config = JSON.createObjectNode();
            ObjectNode server = config.putObject("mcpServers").putObject(DataAccess.SERVER);
            server.put("command", "npx");
            ArrayNode args = server.putArray("args");
            DATA_SERVER_ARGS.forEach(args::add);
            ObjectNode env = server.putObject("env");
            level.serverEnvironment().forEach(env::put);
            return JSON.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write the data server's MCP configuration", e);
        }
    }

    /** The data URI masked: it holds the read-only user's password. */
    @Override
    public String toString() {
        Map<String, String> shown = new TreeMap<>(environment);
        shown.computeIfPresent(DataAccess.URI_VARIABLE, (name, uri) -> "***");
        return "Loadout[pluginDirs=" + pluginDirs + ", skillDirs=" + skillDirs + ", mcpConfig=" + mcpConfig
                + ", environment=" + shown + "]";
    }

    /** A plugin's skill cannot be invoked without the Skill tool (probed on Claude Code 2.1.286). */
    boolean skillTool() {
        return !pluginDirs.isEmpty();
    }
}
