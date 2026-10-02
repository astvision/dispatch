package dispatch.agent.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dispatch.Json;
import dispatch.Log;
import dispatch.agent.AgentStartException;
import dispatch.domain.CuratedPlugins;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The plugins and MCP servers a machine's owner lists for Claude Code runs (spec: owner plugins). The lists are read
 * again from the config file before each run, so an edit applies to the next run without a restart, and each is resolved
 * against this machine's own Claude Code.
 */
public final class OwnerPlugins {

    private static final Lists NOTHING_LISTED = new Lists(List.of(), List.of(), List.of());
    /** No lists: every run's command line as before. */
    public static final OwnerPlugins NONE = new OwnerPlugins(null, false, null, Duration.ZERO);

    private static final YAMLMapper YAML = new YAMLMapper();
    /** A directory directly under ~/.claude/skills: never a path, nor a hidden directory. */
    private static final java.util.regex.Pattern SKILL_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(60);

    /** The lists as the file has them now. */
    public record Lists(List<String> plugins, List<String> mcpServers, List<String> skills) {

        public Lists {
            plugins = List.copyOf(plugins);
            mcpServers = List.copyOf(mcpServers);
            skills = List.copyOf(skills);
        }

        public boolean isEmpty() {
            return plugins.isEmpty() && mcpServers.isEmpty() && skills.isEmpty();
        }
    }

    /**
     * What one run loads: the plugin directories, the --mcp-config JSON (null when there is no server), the variables
     * its servers' {@code env} and {@code headers} values travel in, for the agent's environment, and the directories of
     * the owner's own skills.
     */
    public record Resolved(List<Path> pluginDirs, String mcpConfig, Map<String, String> environment, List<Path> skills) {

        public static final Resolved NONE = new Resolved(List.of(), null, Map.of(), List.of());

        public Resolved {
            pluginDirs = List.copyOf(pluginDirs);
            environment = Map.copyOf(environment);
            skills = List.copyOf(skills);
        }
    }

    private final Path file;
    private final boolean instance;
    private final Path home;
    private final Duration listTimeout;
    /** The lists at the last read that worked: whether a file that cannot be read fails a run depends on them. */
    private volatile Lists lastRead = NOTHING_LISTED;

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

    /** For tests: how long {@code claude plugin list --json} may take. */
    OwnerPlugins withListTimeout(Duration timeout) {
        return new OwnerPlugins(file, instance, home, timeout);
    }

    /** Whether {@code name} can name one of the owner's skills: a plain directory name under ~/.claude/skills. */
    public static boolean isSkillName(String name) {
        return name != null && SKILL_NAME.matcher(name).matches();
    }

    String pluginsKey() {
        return instance ? "agents.claude-code.plugins" : "claudePlugins";
    }

    String serversKey() {
        return instance ? "agents.claude-code.mcpServers" : "claudeMcpServers";
    }

    String skillsKey() {
        return instance ? "agents.claude-code.skills" : "claudeSkills";
    }

    /**
     * The lists as the file has them now; empty when the file has none, or when it cannot be read and the last read that
     * worked listed nothing.
     */
    public Lists lists() {
        if (file == null) {
            return NOTHING_LISTED;
        }
        JsonNode root;
        try {
            root = YAML.readTree(file.toFile());
        } catch (IOException e) {
            if (lastRead.isEmpty()) {
                // Nothing was listed: the run goes on as before the lists, when a bad save mattered only at the next start.
                Log.warn("agent.owner_lists_unreadable", "file", file,
                        "error", String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
                return NOTHING_LISTED;
            }
            throw new AgentStartException("cannot read the plugin lists from " + file + ": " + e.getMessage(), e);
        }
        JsonNode at = root == null ? MissingNode.getInstance() : instance ? root.path("agents").path("claude-code") : root;
        List<String> skills = names(at, instance ? "skills" : "claudeSkills", skillsKey());
        for (String name : skills) {
            if (!isSkillName(name)) {
                throw new AgentStartException(skillsKey() + " in " + file + ": " + name
                        + " is not a directory name in ~/.claude/skills", null);
            }
        }
        Lists lists = new Lists(names(at, instance ? "plugins" : "claudePlugins", pluginsKey()),
                names(at, instance ? "mcpServers" : "claudeMcpServers", serversKey()), skills);
        lastRead = lists;
        return lists;
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

    /**
     * What a run loads, resolved on this machine now: each listed plugin's directory, and one MCP configuration holding
     * the listed servers and the listed plugins' own. Fails the run before its agent starts when something listed is
     * missing.
     */
    public Resolved resolve(String claudeCommand, Map<String, String> environment) {
        return resolve(claudeCommand, environment, List.of());
    }

    /**
     * As {@link #resolve(String, Map)}, plus the official plugins the task's plan picked (spec: plugin picks), found in
     * this machine's marketplace copy with their servers in the same configuration. A pick the owner also lists loads
     * once, as listed. A pick this machine lacks is skipped.
     */
    public Resolved resolve(String claudeCommand, Map<String, String> environment, List<String> picks) {
        Lists lists = lists();
        if (lists.isEmpty() && picks.isEmpty()) {
            return Resolved.NONE;
        }
        ObjectNode servers = Json.MAPPER.createObjectNode();
        List<Path> dirs = new ArrayList<>();
        Map<String, Path> installed = lists.plugins().isEmpty() ? Map.of() : installed(claudeCommand, environment);
        for (String id : lists.plugins()) {
            Path dir = installed.get(id);
            if (dir == null) {
                throw new AgentStartException("claude-code plugin " + id + " is not installed on this computer: install it "
                        + "(claude plugin install " + id + ") or remove it from " + pluginsKey() + " in " + file, null);
            }
            dirs.add(dir);
            // Named as Claude Code names a plugin's server in the owner's own sessions, so its tools are called the same.
            addServers(servers, id.contains("@") ? id.substring(0, id.indexOf('@')) : id, dir);
        }
        List<String> unlisted = picks.stream()
                .filter(pick -> !lists.plugins().contains(pick + "@" + CuratedPlugins.MARKETPLACE)).toList();
        for (Map.Entry<String, Path> pick : OfficialPlugins.find(home, unlisted).entrySet()) {
            dirs.add(pick.getValue());
            addServers(servers, pick.getKey(), pick.getValue());
        }
        if (!lists.mcpServers().isEmpty()) {
            JsonNode defined = userServers();
            for (String name : lists.mcpServers()) {
                JsonNode server = defined.get(name);
                if (server == null) {
                    throw new AgentStartException("claude-code MCP server " + name + " is not defined on this computer: add it "
                            + "(claude mcp add --scope user " + name + " ...) or remove it from " + serversKey() + " in " + file,
                            null);
                }
                add(servers, name, server);
            }
        }
        List<Path> skills = new ArrayList<>();
        for (String name : lists.skills()) {
            Path skill = home.resolve(".claude").resolve("skills").resolve(name);
            if (!Files.isRegularFile(skill.resolve("SKILL.md"))) {
                throw new AgentStartException("claude-code skill " + name + " is not in ~/.claude/skills on this computer: "
                        + "add it there or remove it from " + skillsKey() + " in " + file, null);
            }
            skills.add(skill);
        }
        if (servers.isEmpty()) {
            return new Resolved(dirs, null, Map.of(), skills);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (JsonNode server : servers) {
            moveToEnvironment(server.get("env"), values);
            moveToEnvironment(server.get("headers"), values);
        }
        ObjectNode config = Json.MAPPER.createObjectNode();
        config.set("mcpServers", servers);
        return new Resolved(dirs, Json.write(config), values, skills);
    }

    /**
     * A plugin named {@code owner-skills} in {@code dir}, holding the owner's skills, so a run loads them as
     * {@code owner-skills:<name>} (probed on Claude Code 2.1.287): each a link to the skill's own directory, so an edit
     * reaches the next run, or a copy where this system allows no link (Windows without the privilege).
     */
    public static Path skillsPlugin(Path dir, List<Path> skills) {
        try {
            Files.createDirectories(dir.resolve(".claude-plugin"));
            Files.writeString(dir.resolve(".claude-plugin").resolve("plugin.json"), "{\"name\": \"owner-skills\"}");
            Path into = Files.createDirectories(dir.resolve("skills"));
            for (Path skill : skills) {
                Path link = into.resolve(skill.getFileName().toString());
                if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    Files.createSymbolicLink(link, skill);
                } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
                    copy(skill, link);
                }
            }
            return dir;
        } catch (IOException e) {
            throw new AgentStartException("cannot prepare the owner's skills in " + dir + ": " + e.getMessage(), e);
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(from)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }

    /**
     * Each literal value becomes a {@code ${DISPATCH_MCP_<n>}} reference, and the value goes to {@code environment}: any
     * local user can read a process's command line, only the owner its environment (SECURITY.md). Claude Code expands the
     * reference from its own environment (probed on 2.1.287); a value that already holds one stays as written.
     */
    private static void moveToEnvironment(JsonNode values, Map<String, String> environment) {
        if (!(values instanceof ObjectNode object)) {
            return;
        }
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            JsonNode value = object.get(name);
            if (!value.isTextual() || value.asText().contains("${")) {
                continue;
            }
            String variable = "DISPATCH_MCP_" + (environment.size() + 1);
            environment.put(variable, value.asText());
            object.put(name, "${" + variable + "}");
        }
    }

    /** A plugin's own servers, named {@code plugin_<plugin>_<server>}, with its directory as ${CLAUDE_PLUGIN_ROOT}. */
    private static void addServers(ObjectNode servers, String plugin, Path dir) {
        for (Iterator<Map.Entry<String, JsonNode>> it = pluginServers(dir); it.hasNext(); ) {
            Map.Entry<String, JsonNode> server = it.next();
            add(servers, "plugin_" + plugin + "_" + server.getKey(), withRoot(server.getValue(), dir));
        }
    }

    private static void add(ObjectNode servers, String name, JsonNode server) {
        if (servers.has(name)) {
            throw new AgentStartException("two MCP servers are named " + name + "; rename one of them", null);
        }
        servers.set(name, server);
    }

    /** The plugins installed on this machine, by id; asked of its own Claude Code, outside any sandbox. */
    private Map<String, Path> installed(String claudeCommand, Map<String, String> environment) {
        Path out = null;
        try {
            // A file, not a pipe: a CLI that hangs without output must not block the wait below.
            out = Files.createTempFile("dispatch-plugins", ".json");
            ProcessBuilder builder = new ProcessBuilder(claudeCommand, "plugin", "list", "--json").redirectOutput(out.toFile());
            builder.environment().clear();
            builder.environment().putAll(environment);
            Process process = builder.start();
            if (!process.waitFor(listTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new AgentStartException("claude plugin list --json did not answer within " + listTimeout.toSeconds() + " s",
                        null);
            }
            if (process.exitValue() != 0) {
                String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                throw new AgentStartException("cannot list Claude Code plugins (claude plugin list --json): " + error, null);
            }
            Map<String, Path> installed = new LinkedHashMap<>();
            for (JsonNode plugin : Json.MAPPER.readTree(out.toFile())) {
                installed.put(plugin.path("id").asText(), Path.of(plugin.path("installPath").asText()));
            }
            return installed;
        } catch (IOException e) {
            throw new AgentStartException("cannot list Claude Code plugins (claude plugin list --json): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentStartException("interrupted while listing Claude Code plugins", e);
        } finally {
            deleteQuietly(out);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            Log.warn("agent.temp_left", "path", file, "error", e.toString());
        }
    }

    /** The owner's user-scope servers, as {@code claude mcp add --scope user} keeps them. */
    private JsonNode userServers() {
        Path config = home.resolve(".claude.json");
        try {
            JsonNode servers = Files.isRegularFile(config) ? Json.MAPPER.readTree(config.toFile()).get("mcpServers") : null;
            return servers != null && servers.isObject() ? servers : Json.MAPPER.createObjectNode();
        } catch (IOException e) {
            throw new AgentStartException("cannot read the MCP servers in " + config + ": " + e.getMessage(), e);
        }
    }

    /** A plugin's servers: from the file its plugin.json names, inline there, or from its .mcp.json; none when absent. */
    private static Iterator<Map.Entry<String, JsonNode>> pluginServers(Path dir) {
        Path manifest = dir.resolve(".claude-plugin/plugin.json");
        Path source = dir.resolve(".mcp.json");
        try {
            JsonNode declared = Files.isRegularFile(manifest) ? Json.MAPPER.readTree(manifest.toFile()).get("mcpServers") : null;
            JsonNode servers;
            if (declared != null && declared.isObject()) {
                servers = declared;
            } else {
                if (declared != null && declared.isTextual()) {
                    source = dir.resolve(declared.asText()).normalize();
                }
                if (!Files.isRegularFile(source)) {
                    return Collections.emptyIterator();
                }
                servers = Json.MAPPER.readTree(source.toFile());
            }
            JsonNode map = servers.has("mcpServers") ? servers.get("mcpServers") : servers;
            return map.fields();
        } catch (IOException e) {
            throw new AgentStartException("cannot read the MCP servers of the plugin in " + dir + " (" + source + "): "
                    + e.getMessage(), e);
        }
    }

    /** {@code ${CLAUDE_PLUGIN_ROOT}} set to the plugin's directory, JSON-escaped (a Windows path has backslashes). */
    private static JsonNode withRoot(JsonNode server, Path dir) {
        String escaped = Json.write(dir.toString());
        String text = Json.write(server).replace("${CLAUDE_PLUGIN_ROOT}", escaped.substring(1, escaped.length() - 1));
        try {
            return Json.MAPPER.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read back a server definition: " + text, e);
        }
    }
}
