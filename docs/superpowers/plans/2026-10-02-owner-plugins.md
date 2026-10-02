# Owner Plugins and MCP Servers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Claude Code plan, execution and review runs load the plugins and user-scope MCP servers each machine's owner lists, read again from the config file before every run.

**Architecture:** A new `OwnerPlugins` (in `dispatch.agent.claude`) reads the two lists from `dispatch.yaml` or `worker.yaml` before each run, resolves each listed plugin's directory with `claude plugin list --json`, and builds one MCP configuration from the listed user servers (`~/.claude.json`) and the listed plugins' own servers. `ClaudeCodeAgent` adds `--plugin-dir`/`--add-dir` per plugin, `Skill` to `--tools`, and the configuration inline after `--mcp-config`, keeping `--strict-mcp-config`. Config loaders accept and validate the new keys; `dispatch check` reports what is missing; the stream parser warns about a server that failed to start.

**Tech Stack:** Java 21, Maven (`./mvnw`), JUnit 5, Jackson (YAML via `YAMLMapper`, JSON via `dispatch.Json.MAPPER`), POSIX shell fakes (`src/test/resources/fake-claude.sh`).

**Spec:** `docs/superpowers/specs/2026-10-02-owner-plugins-and-mcp-design.md`

## Global Constraints

- Claude Code only: PLAN, EXECUTE (fix rounds are EXECUTE) and REVIEW runs. SPLIT and ASSISTANT runs, Codex and Gemini CLI are unchanged.
- Keys: `agents.claude-code.plugins` and `agents.claude-code.mcpServers` in `dispatch.yaml`; `claudePlugins` and `claudeMcpServers` in `worker.yaml`. Each machine's own lists apply to its runs.
- The lists are read again from the file on disk before each run: an edit applies to the next run without a restart. Every other setting still applies at startup.
- `--strict-mcp-config` stays. The MCP configuration goes inline, one line of JSON, right after `--mcp-config`, and a flag always follows it (the option takes every following non-flag argument).
- A plugin's server is named `plugin_<plugin>_<server>` (`<plugin>` = the id before `@`); `${CLAUDE_PLUGIN_ROOT}` in its definition becomes the plugin's directory. A listed server keeps its own name and definition.
- Empty or absent lists: the command line exactly as today.
- Something listed and missing fails the run as `AGENT` before its agent starts, with these exact texts:
  `claude-code plugin <id> is not installed on this computer: install it (claude plugin install <id>) or remove it from <key> in <file>`
  `claude-code MCP server <name> is not defined on this computer: add it (claude mcp add --scope user <name> ...) or remove it from <key> in <file>`
- A server that failed to start: `agent.mcp_failed server=<name> run=<logBase>` (WARN).
- The repo is public: no token-shaped literals or personal paths in fixtures (`fake-connection-string-not-a-secret`, `/home/ann`).
- Every new user-facing text key goes into both `texts_en.properties` and `texts_mn.properties`; no bare apostrophes (MessageFormat).
- Commit messages: one sentence describing behavior, then the two trailer lines the session uses.

## Ruling against the spec (recorded in the spec, commit with this plan)

The spec first had Dispatch write the MCP configuration to `<logBase>.mcp.json` (owner-only, mounted read-only, deleted by the run guard). Claude Code's `--mcp-config` takes JSON strings as well as files (`claude --help`, 2.1.287), and Dispatch already passes `--json-schema` inline, so the configuration goes inline and no file exists: nothing to protect, mount, delete or leave behind after a crash. The spec's success criterion 6, its MCP section, sandbox section, errors and tests were updated accordingly.

## Review Focus

1. **A server's credentials in the log.** The configuration (with a server's `env`) travels on the command line; Dispatch must never log it. Pinned in Task 4 (`listedPluginsAndServersReachTheCommandLineAndAnEditReachesTheNextRun` asserts the log never holds the fake secret).
2. **`claude plugin list --json` that hangs** (a marketplace refresh, a broken install): the run must fail within a bound, not wait forever. Pinned in Task 3 (`aPluginListThatHangsFailsTheRunInsteadOfWaitingForever`, 1 s timeout).
3. **A list emptied while Dispatch runs:** the next run's command line must be exactly today's. Pinned in Task 4 (second half of the same test).
4. **The variadic `--mcp-config`** swallowing the next argument: the argument after the JSON must be a flag. Pinned in Task 4 (`args.get(index + 2).startsWith("--")`).
5. **A half-saved config file when a run starts:** the run fails as `AGENT` naming the file, and a retry reads it again. Pinned in Task 2 (`aFileThatCannotBeParsedFailsTheRunNamingIt`).

## File Structure

- Create `src/main/java/dispatch/agent/claude/OwnerPlugins.java`: reads the two lists from a config file (per call), resolves them on this machine (`claude plugin list --json`, `~/.claude.json`, plugins' MCP files) into plugin dirs and one inline MCP configuration. One responsibility: what the owner lists, resolved.
- Create `src/test/java/dispatch/testing/OwnerPluginsFixture.java`: installed-plugin fixtures shared by the agent, check and resolution tests.
- Modify `src/main/java/dispatch/agent/claude/ClaudeCodeAgent.java`: takes an `OwnerPlugins`, resolves it for PLAN/EXECUTE/REVIEW, adds the flags.
- Modify `src/main/java/dispatch/agent/Agents.java`, `src/main/java/dispatch/App.java`, `src/main/java/dispatch/worker/WorkerCommand.java`: pass each machine's `OwnerPlugins`.
- Modify `src/main/java/dispatch/agent/claude/StreamParser.java`: `agent.mcp_failed`.
- Modify `src/main/java/dispatch/config/Config.java`, `ConfigLoader.java`, `src/main/java/dispatch/worker/WorkerConfigLoader.java`: accept and validate the keys at startup.
- Modify `src/main/java/dispatch/cli/Checks.java`, `src/main/java/dispatch/worker/WorkerChecks.java`: the check line.
- Modify `src/main/resources/texts_en.properties`, `texts_mn.properties`, `src/test/resources/fake-claude.sh`.
- Docs: `docs/adr/0036-claude-runs-load-the-plugins-and-mcp-servers-the-owner-lists.md` (new), `SECURITY.md`, `README.en.md`, `README.md`, `docs/ARCHITECTURE.md`.

---

### Task 1: The config files accept the lists

**Files:**
- Modify: `src/main/java/dispatch/config/Config.java:164-165` (record `Agent`)
- Modify: `src/main/java/dispatch/config/ConfigLoader.java:335-347` (`validateAgents`)
- Modify: `src/main/java/dispatch/worker/WorkerConfigLoader.java:36-37` (record `WorkerFile`) and its error list in `load`
- Modify: `src/main/resources/texts_en.properties`, `src/main/resources/texts_mn.properties`
- Test: `src/test/java/dispatch/config/ConfigLoaderTest.java`, `src/test/java/dispatch/worker/WorkerConfigLoaderTest.java`

**Interfaces:**
- Produces: `Config.Agent(String command, List<String> plugins, List<String> mcpServers)` with `plugins()`/`mcpServers()` never null, and `Config.Agent(String command)` (both lists empty). `WorkerConfigLoader.WorkerFile` gains `List<String> claudePlugins, List<String> claudeMcpServers` (parsed and validated only; `WorkerConfig` is unchanged, since runs read the file again).

- [ ] **Step 1: Write the failing tests**

In `ConfigLoaderTest` (its `VALID` has `agents:\n  claude-code:\n    command: /usr/local/bin/claude\n`):

```java
    @Test
    void claudeCodeListsThePluginsAndMcpServersItsRunsLoad() throws IOException {
        Config config = ConfigLoader.load(write(VALID.replace("    command: /usr/local/bin/claude\n",
                "    command: /usr/local/bin/claude\n    plugins: [frontend-design@claude-plugins-official]\n"
                        + "    mcpServers: [mongodb]\n")), ENV);

        assertEquals(List.of("frontend-design@claude-plugins-official"), config.agents().get("claude-code").plugins());
        assertEquals(List.of("mongodb"), config.agents().get("claude-code").mcpServers());
    }

    @Test
    void onlyClaudeCodeListsPluginsAndMcpServersAndNeverABlankName() throws IOException {
        ConfigException codex = assertThrows(ConfigException.class, () -> ConfigLoader.load(write(VALID.replace(
                "    command: /usr/local/bin/claude\n",
                "    command: /usr/local/bin/claude\n  codex:\n    command: codex\n    plugins: [x@y]\n")), ENV));
        ConfigException blank = assertThrows(ConfigException.class, () -> ConfigLoader.load(write(VALID.replace(
                "    command: /usr/local/bin/claude\n", "    command: /usr/local/bin/claude\n    mcpServers: ['']\n")), ENV));

        assertTrue(codex.getMessage().contains("agents.codex: plugins and mcpServers are for claude-code only"), codex.getMessage());
        assertTrue(blank.getMessage().contains("agents.claude-code: plugins and mcpServers must list names, without blank entries"),
                blank.getMessage());
    }
```

In `WorkerConfigLoaderTest`:

```java
    @Test
    void aWorkerListsThePluginsAndMcpServersItsRunsLoadAndNeverABlankName() throws Exception {
        WorkerConfigLoader.load(write("""
                team: https://team.example.com
                name: ann-laptop
                claudePlugins: [frontend-design@claude-plugins-official]
                claudeMcpServers: [mongodb]
                """));
        Path blank = write("""
                team: https://team.example.com
                name: ann-laptop
                claudePlugins: ['']
                """);

        ConfigException error = assertThrows(ConfigException.class, () -> WorkerConfigLoader.load(blank));

        assertTrue(error.getMessage().contains("claudePlugins and claudeMcpServers: list names, without blank entries"),
                error.getMessage());
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest='ConfigLoaderTest,WorkerConfigLoaderTest'`
Expected: FAIL. The first test with an unrecognized field `plugins` (FAIL_ON_UNKNOWN_PROPERTIES), the worker test with an unrecognized field `claudePlugins`.

- [ ] **Step 3: Implement**

`Config.java`, replace `public record Agent(String command) {\n    }` with:

```java
    /**
     * @param plugins    Claude Code plugins its runs load, by id (spec: owner plugins); runs read them again from the file
     * @param mcpServers user-scope MCP servers its runs load, by name; likewise
     */
    public record Agent(String command, List<String> plugins, List<String> mcpServers) {

        public Agent {
            // A null entry (an empty YAML item) becomes "" for validateAgents to report, never a NullPointerException.
            plugins = plugins == null ? List.of() : plugins.stream().map(name -> name == null ? "" : name).toList();
            mcpServers = mcpServers == null ? List.of() : mcpServers.stream().map(name -> name == null ? "" : name).toList();
        }

        public Agent(String command) {
            this(command, List.of(), List.of());
        }
    }
```

`ConfigLoader.validateAgents`, extend the chain inside `agents.forEach`:

```java
            } else if (agent == null || isBlank(agent.command())) {
                errors.add(Text.of("config.agentsCommand", type));
            } else if (!type.equals("claude-code") && (!agent.plugins().isEmpty() || !agent.mcpServers().isEmpty())) {
                errors.add(Text.of("config.agentsClaudeOnlyLists", type));
            } else if (java.util.stream.Stream.concat(agent.plugins().stream(), agent.mcpServers().stream()).anyMatch(String::isBlank)) {
                errors.add(Text.of("config.agentsListEntry", type));
            }
```

`WorkerConfigLoader.WorkerFile`, add two components at the end:

```java
    record WorkerFile(String team, String name, Integer maxConcurrentRuns, String claudeCommand, String ghCommand,
                      String stateDir, Map<String, Project> projects, String codexCommand, String geminiCommand, String sandbox,
                      List<String> claudePlugins, List<String> claudeMcpServers) {
```

and in `load`, beside the other `errors.add` checks (before `if (!errors.isEmpty())`):

```java
        boolean blank = java.util.stream.Stream.of(raw.claudePlugins(), raw.claudeMcpServers()).filter(java.util.Objects::nonNull)
                .flatMap(List::stream).anyMatch(name -> name == null || name.isBlank());
        if (blank) {
            errors.add("claudePlugins and claudeMcpServers: list names, without blank entries");
        }
```

`texts_en.properties` (beside `config.agentsCommand`):

```properties
config.agentsClaudeOnlyLists=agents.{0}: plugins and mcpServers are for claude-code only
config.agentsListEntry=agents.{0}: plugins and mcpServers must list names, without blank entries
```

`texts_mn.properties`:

```properties
config.agentsClaudeOnlyLists=agents.{0}: plugins, mcpServers нь зөвхөн claude-code-д зориулагдсан
config.agentsListEntry=agents.{0}: plugins, mcpServers-д хоосон бус нэрс жагсаана уу
```

- [ ] **Step 4: Run them to see them pass**

Run: `./mvnw -q test -Dtest='ConfigLoaderTest,WorkerConfigLoaderTest,TextTest'`
Expected: PASS (TextTest checks both bundles have the same keys).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/config/Config.java src/main/java/dispatch/config/ConfigLoader.java \
  src/main/java/dispatch/worker/WorkerConfigLoader.java src/main/resources/texts_en.properties \
  src/main/resources/texts_mn.properties src/test/java/dispatch/config/ConfigLoaderTest.java \
  src/test/java/dispatch/worker/WorkerConfigLoaderTest.java
git commit -m "dispatch.yaml and worker.yaml accept the Claude Code plugins and MCP servers a machine's runs load"
```

---

### Task 2: OwnerPlugins reads the lists again for each run

**Files:**
- Create: `src/main/java/dispatch/agent/claude/OwnerPlugins.java`
- Test: `src/test/java/dispatch/agent/claude/OwnerPluginsTest.java`

**Interfaces:**
- Produces: `OwnerPlugins.instance(Path dispatchYaml, Path home)`, `OwnerPlugins.worker(Path workerYaml, Path home)`, `OwnerPlugins.NONE`; `record OwnerPlugins.Lists(List<String> plugins, List<String> mcpServers)` with `boolean isEmpty()`; `OwnerPlugins.Lists lists()` (reads the file now; throws `AgentStartException`); package-private `String pluginsKey()`, `String serversKey()`.

- [ ] **Step 1: Write the failing tests**

```java
package dispatch.agent.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.AgentStartException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerPluginsTest {

    @TempDir
    Path dir;

    @Test
    void anInstanceReadsItsListsUnderClaudeCodeAgainForEachRun() throws IOException {
        Path config = Files.writeString(dir.resolve("dispatch.yaml"),
                "agents:\n  claude-code:\n    command: claude\n    plugins: [a@m]\n    mcpServers: [mongodb]\n");
        OwnerPlugins owner = OwnerPlugins.instance(config, dir);
        assertEquals(new OwnerPlugins.Lists(List.of("a@m"), List.of("mongodb")), owner.lists());

        Files.writeString(config, "agents:\n  claude-code:\n    command: claude\n    plugins: [a@m, b@m]\n");

        assertEquals(new OwnerPlugins.Lists(List.of("a@m", "b@m"), List.of()), owner.lists(), "read again, no restart");
    }

    @Test
    void aWorkerReadsItsOwnKeys() throws IOException {
        Path config = Files.writeString(dir.resolve("worker.yaml"),
                "team: https://team.example.com\nname: ann\nclaudePlugins: [a@m]\nclaudeMcpServers: [mongodb]\n");

        assertEquals(new OwnerPlugins.Lists(List.of("a@m"), List.of("mongodb")), OwnerPlugins.worker(config, dir).lists());
    }

    @Test
    void absentListsAndNoneAreEmpty() throws IOException {
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    command: claude\n");

        assertTrue(OwnerPlugins.instance(config, dir).lists().isEmpty());
        assertTrue(OwnerPlugins.NONE.lists().isEmpty());
    }

    @Test
    void aFileThatCannotBeParsedFailsTheRunNamingIt() throws IOException {
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code: [\n");

        AgentStartException error = assertThrows(AgentStartException.class, () -> OwnerPlugins.instance(config, dir).lists());

        assertTrue(error.getMessage().startsWith("cannot read the plugin lists from " + config + ": "), error.getMessage());
    }

    @Test
    void aListThatIsNotNamesIsRefused() throws IOException {
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    plugins: frontend\n");

        AgentStartException error = assertThrows(AgentStartException.class, () -> OwnerPlugins.instance(config, dir).lists());

        assertEquals("agents.claude-code.plugins in " + config + " must be a list of names", error.getMessage());
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest=OwnerPluginsTest`
Expected: compilation FAILS: `cannot find symbol: class OwnerPlugins`.

- [ ] **Step 3: Implement the reading half**

```java
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
```

- [ ] **Step 4: Run them to see them pass**

Run: `./mvnw -q test -Dtest=OwnerPluginsTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/agent/claude/OwnerPlugins.java src/test/java/dispatch/agent/claude/OwnerPluginsTest.java
git commit -m "The Claude Code plugin and MCP server lists are read from the config file again for each run"
```

---

### Task 3: OwnerPlugins resolves the lists on this machine

**Files:**
- Modify: `src/main/java/dispatch/agent/claude/OwnerPlugins.java`
- Create: `src/test/java/dispatch/testing/OwnerPluginsFixture.java`
- Modify: `src/test/resources/fake-claude.sh` (answers `plugin list --json`)
- Test: `src/test/java/dispatch/agent/claude/OwnerPluginsTest.java`

**Interfaces:**
- Consumes: Task 2's `lists()`, `pluginsKey()`, `serversKey()`.
- Produces: `record OwnerPlugins.Resolved(List<Path> pluginDirs, String mcpConfig)` with `Resolved.NONE`; `Resolved resolve(String claudeCommand, Map<String, String> environment)`; package-private `OwnerPlugins withListTimeout(Duration)`. `OwnerPluginsFixture.plugin(Path dir, String servers)`, `OwnerPluginsFixture.installed(Path claude, Map<String, Path> plugins)`.

- [ ] **Step 1: The fixture and the fake's answer**

`src/test/java/dispatch/testing/OwnerPluginsFixture.java`:

```java
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
```

`fake-claude.sh`, right after the `--version` block:

```sh
# Dispatch lists the owner's installed plugins before a run (spec: owner plugins); answer from beside this script,
# without recording anything.
if [ "$1" = "plugin" ]; then
  cat "$(dirname "$0")/fake-claude.plugins.json" 2>/dev/null || echo '[]'
  exit 0
fi
```

- [ ] **Step 2: Write the failing tests** (add to `OwnerPluginsTest`; imports `com.fasterxml.jackson.databind.JsonNode`, `dispatch.Json`, `dispatch.testing.FakeClaude`, `dispatch.testing.OwnerPluginsFixture`, `java.time.Duration`, `java.util.Map`, `java.util.Set`, `java.util.HashSet`, `org.junit.jupiter.api.condition.DisabledOnOs`, `org.junit.jupiter.api.condition.OS`, `static org.junit.jupiter.api.Assertions.assertFalse`)

```java
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void listedPluginsResolveToTheirDirectoriesAndEveryServerIntoOneLine() throws IOException {
        Path claude = FakeClaude.install(Files.createDirectories(dir.resolve("bin")));
        Path playwright = OwnerPluginsFixture.plugin(dir.resolve("cache/playwright"),
                "{\"playwright\": {\"command\": \"npx\", \"args\": [\"@playwright/mcp@latest\"]}}");
        Path mongo = OwnerPluginsFixture.plugin(dir.resolve("cache/mongodb"), null);
        Files.writeString(mongo.resolve(".claude-plugin/plugin.json"), "{\"name\": \"mongodb\", \"mcpServers\": \"./mcp.json\"}");
        Files.writeString(mongo.resolve("mcp.json"), "{\"mcpServers\": {\"mongodb\": {\"command\": \"${CLAUDE_PLUGIN_ROOT}/bin/server\"}}}");
        OwnerPluginsFixture.installed(claude, Map.of("playwright@claude-plugins-official", playwright,
                "mongodb@claude-plugins-official", mongo));
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.writeString(home.resolve(".claude.json"),
                "{\"mcpServers\": {\"mongodb\": {\"command\": \"mongodb-mcp-server\"}, \"unlisted\": {\"command\": \"x\"}}}");
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n"
                + "    plugins: [playwright@claude-plugins-official, mongodb@claude-plugins-official]\n    mcpServers: [mongodb]\n");

        OwnerPlugins.Resolved resolved = OwnerPlugins.instance(config, home).resolve(claude.toString(), FakeClaude.environment());

        assertEquals(List.of(playwright, mongo), resolved.pluginDirs());
        assertFalse(resolved.mcpConfig().contains("\n"), "one line, for the command line");
        JsonNode servers = Json.MAPPER.readTree(resolved.mcpConfig()).get("mcpServers");
        Set<String> names = new HashSet<>();
        servers.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("plugin_playwright_playwright", "plugin_mongodb_mongodb", "mongodb"), names, "unlisted stays out");
        assertEquals(mongo + "/bin/server", servers.get("plugin_mongodb_mongodb").get("command").asText());
        assertEquals("mongodb-mcp-server", servers.get("mongodb").get("command").asText());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aListedPluginThatIsNotInstalledSaysWhatToDo() throws IOException {
        Path claude = FakeClaude.install(Files.createDirectories(dir.resolve("bin")));
        OwnerPluginsFixture.installed(claude, Map.of());
        Path config = Files.writeString(dir.resolve("dispatch.yaml"),
                "agents:\n  claude-code:\n    plugins: [frontend-design@claude-plugins-official]\n");

        AgentStartException error = assertThrows(AgentStartException.class,
                () -> OwnerPlugins.instance(config, dir).resolve(claude.toString(), FakeClaude.environment()));

        assertEquals("claude-code plugin frontend-design@claude-plugins-official is not installed on this computer: install it "
                + "(claude plugin install frontend-design@claude-plugins-official) or remove it from agents.claude-code.plugins in "
                + config, error.getMessage());
    }

    @Test
    void aListedServerThatIsNotDefinedSaysWhatToDo() throws IOException {
        Path config = Files.writeString(dir.resolve("worker.yaml"), "claudeMcpServers: [mongodb]\n");

        AgentStartException error = assertThrows(AgentStartException.class,
                () -> OwnerPlugins.worker(config, dir).resolve("claude", Map.of()));

        assertEquals("claude-code MCP server mongodb is not defined on this computer: add it (claude mcp add --scope user "
                + "mongodb ...) or remove it from claudeMcpServers in " + config, error.getMessage());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void twoServersWithOneNameFailTheRun() throws IOException {
        Path claude = FakeClaude.install(Files.createDirectories(dir.resolve("bin")));
        Path playwright = OwnerPluginsFixture.plugin(dir.resolve("cache/playwright"), "{\"playwright\": {\"command\": \"npx\"}}");
        OwnerPluginsFixture.installed(claude, Map.of("playwright@m", playwright));
        Files.writeString(dir.resolve(".claude.json"), "{\"mcpServers\": {\"plugin_playwright_playwright\": {\"command\": \"x\"}}}");
        Path config = Files.writeString(dir.resolve("dispatch.yaml"),
                "agents:\n  claude-code:\n    plugins: [playwright@m]\n    mcpServers: [plugin_playwright_playwright]\n");

        AgentStartException error = assertThrows(AgentStartException.class,
                () -> OwnerPlugins.instance(config, dir).resolve(claude.toString(), FakeClaude.environment()));

        assertEquals("two MCP servers are named plugin_playwright_playwright; rename one of them", error.getMessage());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aClaudeCodeThatCannotListItsPluginsFailsTheRunWithItsError() throws IOException {
        Path claude = Files.writeString(dir.resolve("claude"), "#!/bin/sh\necho 'unknown command: plugin' >&2\nexit 1\n");
        claude.toFile().setExecutable(true);
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    plugins: [a@m]\n");

        AgentStartException error = assertThrows(AgentStartException.class, () -> OwnerPlugins.instance(config, dir)
                .resolve(claude.toString(), Map.of("PATH", System.getenv("PATH"))));

        assertEquals("cannot list Claude Code plugins (claude plugin list --json): unknown command: plugin", error.getMessage());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aPluginListThatHangsFailsTheRunInsteadOfWaitingForever() throws IOException {
        Path claude = Files.writeString(dir.resolve("claude"), "#!/bin/sh\nexec sleep 30\n");
        claude.toFile().setExecutable(true);
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    plugins: [a@m]\n");
        long start = System.nanoTime();

        AgentStartException error = assertThrows(AgentStartException.class, () -> OwnerPlugins.instance(config, dir)
                .withListTimeout(Duration.ofSeconds(1)).resolve(claude.toString(), Map.of("PATH", System.getenv("PATH"))));

        assertEquals("claude plugin list --json did not answer within 1 s", error.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
    }

    @Test
    void noListsResolveToNothing() throws IOException {
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    command: claude\n");

        assertEquals(OwnerPlugins.Resolved.NONE, OwnerPlugins.instance(config, dir).resolve("claude", Map.of()));
    }
```

- [ ] **Step 3: Run them to see them fail**

Run: `./mvnw -q test -Dtest=OwnerPluginsTest`
Expected: compilation FAILS: `cannot find symbol: method resolve` / `class Resolved` / `method withListTimeout`.

- [ ] **Step 4: Implement the resolving half** (add to `OwnerPlugins`; imports `com.fasterxml.jackson.databind.node.ObjectNode`, `dispatch.Json`, `dispatch.Log`, `java.nio.charset.StandardCharsets`, `java.nio.file.Files`, `java.util.Collections`, `java.util.Iterator`, `java.util.LinkedHashMap`, `java.util.Map`, `java.util.concurrent.TimeUnit`)

```java
    /** What one run loads: the plugin directories, and the --mcp-config JSON, null when there is no server. */
    public record Resolved(List<Path> pluginDirs, String mcpConfig) {

        public static final Resolved NONE = new Resolved(List.of(), null);

        public Resolved {
            pluginDirs = List.copyOf(pluginDirs);
        }
    }

    /** For tests: how long {@code claude plugin list --json} may take. */
    OwnerPlugins withListTimeout(Duration timeout) {
        return new OwnerPlugins(file, instance, home, timeout);
    }

    /**
     * What a run loads, resolved on this machine now: each listed plugin's directory, and one MCP configuration holding
     * the listed servers and the listed plugins' own. Fails the run before its agent starts when something listed is
     * missing.
     */
    public Resolved resolve(String claudeCommand, Map<String, String> environment) {
        Lists lists = lists();
        if (lists.isEmpty()) {
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
            String plugin = id.contains("@") ? id.substring(0, id.indexOf('@')) : id;
            for (Iterator<Map.Entry<String, JsonNode>> it = pluginServers(dir); it.hasNext(); ) {
                Map.Entry<String, JsonNode> server = it.next();
                add(servers, "plugin_" + plugin + "_" + server.getKey(), withRoot(server.getValue(), dir));
            }
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
        if (servers.isEmpty()) {
            return new Resolved(dirs, null);
        }
        ObjectNode config = Json.MAPPER.createObjectNode();
        config.set("mcpServers", servers);
        return new Resolved(dirs, Json.write(config));
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
```

- [ ] **Step 5: Run them to see them pass**

Run: `./mvnw -q test -Dtest=OwnerPluginsTest`
Expected: PASS, 12 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dispatch/agent/claude/OwnerPlugins.java src/test/java/dispatch/agent/claude/OwnerPluginsTest.java \
  src/test/java/dispatch/testing/OwnerPluginsFixture.java src/test/resources/fake-claude.sh
git commit -m "The listed plugins and MCP servers resolve on the machine that runs the agent, and a missing one says what to do"
```

---

### Task 4: Claude Code runs load what is listed

**Files:**
- Modify: `src/main/java/dispatch/agent/claude/ClaudeCodeAgent.java` (constructors ~50-61, `start` ~64-68, `commandLine` ~80-128)
- Modify: `src/main/java/dispatch/agent/Agents.java:31-40`
- Modify: `src/main/java/dispatch/App.java:156`, `src/main/java/dispatch/worker/WorkerCommand.java:177`
- Test: `src/test/java/dispatch/agent/claude/ClaudeCodeAgentTest.java`

**Interfaces:**
- Consumes: Task 3's `OwnerPlugins.resolve(String, Map<String, String>)`, `Resolved.pluginDirs()`, `Resolved.mcpConfig()`; `OwnerPluginsFixture`.
- Produces: `ClaudeCodeAgent(String command, Map<String, String> environment, Duration cancelGrace, Confinement confinement, OwnerPlugins ownerPlugins)`; `Agents.create(Map<String, String>, Map<String, String>, Path, Confinement, OwnerPlugins)`.

- [ ] **Step 1: Write the failing tests** (add to `ClaudeCodeAgentTest`; imports `dispatch.testing.OwnerPluginsFixture`, `java.io.ByteArrayOutputStream`, `java.io.PrintStream`, `java.nio.charset.StandardCharsets`, `org.junit.jupiter.api.condition.DisabledOnOs`, `org.junit.jupiter.api.condition.OS`; `AgentStartException` and `Map` are already imported)

```java
    private static final String FAKE_SECRET = "fake-connection-string-not-a-secret";

    /** What Log printed while {@code action} ran. */
    private static String capturingLog(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setOut(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return logged.toString(StandardCharsets.UTF_8);
    }

    private ClaudeCodeAgent owning(Path config, Path home, Map<String, Path> installed) throws IOException {
        Path claude = FakeClaude.install(Files.createDirectories(dir.resolve("owner")));
        OwnerPluginsFixture.installed(claude, installed);
        return new ClaudeCodeAgent(claude.toString(), FakeClaude.environment(), Duration.ofSeconds(2),
                Confinement.none("no sandbox configured"), OwnerPlugins.instance(config, home));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void listedPluginsAndServersReachTheCommandLineAndAnEditReachesTheNextRun() throws Exception {
        Path plugin = OwnerPluginsFixture.plugin(dir.resolve("cache/playwright"), "{\"playwright\": {\"command\": \"npx\"}}");
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.writeString(home.resolve(".claude.json"), "{\"mcpServers\": {\"mongodb\": {\"command\": \"mongodb-mcp-server\", "
                + "\"env\": {\"MDB_MCP_CONNECTION_STRING\": \"" + FAKE_SECRET + "\"}}}}");
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    command: claude\n"
                + "    plugins: [playwright@claude-plugins-official]\n    mcpServers: [mongodb]\n");
        ClaudeCodeAgent owned = owning(config, home, Map.of("playwright@claude-plugins-official", plugin));

        String logged = capturingLog(() -> awaitQuietly(owned.start(plan("Plan it"))));
        List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));

        assertEquals(plugin.toString(), args.get(args.indexOf("--plugin-dir") + 1));
        assertEquals(plugin.toString(), args.get(args.indexOf("--add-dir") + 1), "plan runs may read its skill files");
        assertTrue(args.get(args.indexOf("--tools") + 1).endsWith(",Skill"), args.toString());
        int mcp = args.indexOf("--mcp-config");
        assertTrue(args.get(mcp + 1).contains("\"plugin_playwright_playwright\"") && args.get(mcp + 1).contains("\"mongodb\""));
        assertTrue(args.get(mcp + 2).startsWith("--"), "a flag ends --mcp-config's values: " + args);
        assertTrue(args.contains("--strict-mcp-config"));
        assertFalse(logged.contains(FAKE_SECRET), "a server's credentials never reach the log");

        Files.writeString(config, "agents:\n  claude-code:\n    command: claude\n");
        awaitQuietly(owned.start(plan("Plan it")));
        List<String> after = Files.readAllLines(workdir.resolve("fake-claude.args"));

        assertFalse(after.contains("--mcp-config") || after.contains("--plugin-dir"), "emptied, no restart: " + after);
        assertFalse(after.get(after.indexOf("--tools") + 1).contains("Skill"), after.toString());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aSplitLoadsNoneOfTheOwnersPlugins() throws Exception {
        Path plugin = OwnerPluginsFixture.plugin(dir.resolve("cache/playwright"), null);
        Path config = Files.writeString(dir.resolve("dispatch.yaml"),
                "agents:\n  claude-code:\n    plugins: [playwright@claude-plugins-official]\n");
        ClaudeCodeAgent owned = owning(config, dir, Map.of("playwright@claude-plugins-official", plugin));

        awaitQuietly(owned.start(new RunRequest(RunKind.SPLIT, workdir, "fix X, add Y", null, false, List.of(), null, null,
                null, dir.resolve("runs/split/1"))));

        assertFalse(Files.readAllLines(workdir.resolve("fake-claude.args")).contains("--plugin-dir"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aListedPluginThatIsNotInstalledFailsTheRunBeforeItsAgentStarts() throws Exception {
        Path config = Files.writeString(dir.resolve("dispatch.yaml"),
                "agents:\n  claude-code:\n    plugins: [frontend-design@claude-plugins-official]\n");
        ClaudeCodeAgent owned = owning(config, dir, Map.of());

        AgentStartException error = assertThrows(AgentStartException.class, () -> owned.start(plan("Plan it")));

        assertTrue(error.getMessage().startsWith("claude-code plugin frontend-design@claude-plugins-official is not installed"));
        assertFalse(Files.exists(workdir.resolve("fake-claude.args")), "the agent never started");
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest=ClaudeCodeAgentTest`
Expected: compilation FAILS: no `ClaudeCodeAgent` constructor taking an `OwnerPlugins`.

- [ ] **Step 3: Implement**

`ClaudeCodeAgent`: a field `private final OwnerPlugins ownerPlugins;`, the 4-argument constructor delegates to a new 5-argument one with `OwnerPlugins.NONE`, and:

```java
    /** Runs that do the task's work; a split and an assistant turn keep only what Dispatch gives them. */
    private static final java.util.Set<RunKind> OWNER_KINDS = java.util.Set.of(RunKind.PLAN, RunKind.EXECUTE, RunKind.REVIEW);

    @Override
    public RunHandle start(RunRequest request) {
        String permissionMode = permissionMode(request.kind());
        // Read and resolved now, on the machine that runs the agent: an edit or an install applies to this run.
        OwnerPlugins.Resolved owner = OWNER_KINDS.contains(request.kind())
                ? ownerPlugins.resolve(command, ProcessRun.agentEnvironment(environment)) : OwnerPlugins.Resolved.NONE;
        return ProcessRun.start("claude-code", commandLine(request, permissionMode, owner), request, environment, request.prompt(),
                new StreamParser(permissionMode, request.model(), request.workdir(), request.logBase()), cancelGrace, confinement, STATE);
    }
```

In `commandLine(RunRequest request, String permissionMode, OwnerPlugins.Resolved owner)`, right after the initial `List.of(...)` that ends with `"--strict-mcp-config"`:

```java
        if (owner.mcpConfig() != null) {
            // Inline, as --json-schema is: nothing written to disk. --mcp-config takes every following argument that is
            // not a flag, and a flag always follows here.
            args.addAll(List.of("--mcp-config", owner.mcpConfig()));
        }
```

and replace the plugin-dir loop and the `skill` line with:

```java
        List<Path> plugins = new ArrayList<>(request.pluginDirs());
        plugins.addAll(owner.pluginDirs());
        for (Path dir : plugins) {
            args.addAll(List.of("--plugin-dir", dir.toString(), "--add-dir", dir.toString()));
        }
        // A listed skill cannot be invoked without the Skill tool (probed on Claude Code 2.1.286).
        String skill = plugins.isEmpty() ? "" : ",Skill";
```

`Agents`: the 4-argument `create` delegates to a new 5-argument one with `OwnerPlugins.NONE`; the new one builds `new ClaudeCodeAgent(command, environment, CANCEL_GRACE, confinement, ownerPlugins)` (javadoc: `@param ownerPlugins what this machine's owner lists for Claude Code runs (spec: owner plugins)`).

`App.java:156`:

```java
        Map<String, Agent> agents = Agents.create(agentCommands, environment, stateDir, confinement,
                dispatch.agent.claude.OwnerPlugins.instance(configFile, Path.of(System.getProperty("user.home"))));
```

`WorkerCommand.java:177` (the worker file is the one loaded at line 123):

```java
            Map<String, Agent> agents = Agents.create(config.agentCommands(), environment, config.stateDir(), confinement,
                    dispatch.agent.claude.OwnerPlugins.worker(options.workerFile(), Path.of(System.getProperty("user.home"))));
```

- [ ] **Step 4: Run them to see them pass**

Run: `./mvnw -q test -Dtest='ClaudeCodeAgentTest,AgentsTest,AppTest,WorkerCommandTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/agent/claude/ClaudeCodeAgent.java src/main/java/dispatch/agent/Agents.java \
  src/main/java/dispatch/App.java src/main/java/dispatch/worker/WorkerCommand.java \
  src/test/java/dispatch/agent/claude/ClaudeCodeAgentTest.java
git commit -m "Claude Code plan, execution and review runs load the plugins and MCP servers the machine's owner lists"
```

---

### Task 5: A server that failed to start is logged

**Files:**
- Modify: `src/main/java/dispatch/agent/claude/StreamParser.java:69-77` (the init branch)
- Test: `src/test/java/dispatch/agent/claude/StreamParserTest.java`

- [ ] **Step 1: Write the failing test**

```java
    /** The run goes on without it; a server that needs what the sandbox hides fails here (spec: owner plugins). */
    @Test
    void aServerThatFailedToStartIsLogged() {
        Path logBase = Path.of("/s/runs/7/2");
        StreamParser parser = new StreamParser("auto", null, WORKTREE, logBase);

        String logged = capturingLog(() -> parser.accept("""
                {"type":"system","subtype":"init","session_id":"s","permissionMode":"auto","mcp_servers":[{"name":"plugin_playwright_playwright","status":"connected"},{"name":"mongodb","status":"failed"}]}"""));

        assertTrue(logged.contains("level=WARN event=agent.mcp_failed server=mongodb run=" + logBase), logged);
        assertFalse(logged.contains("server=plugin_playwright_playwright"), logged);
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q test -Dtest=StreamParserTest`
Expected: FAIL: the log holds no `agent.mcp_failed`.

- [ ] **Step 3: Implement** (in the init branch, after the `plugin_errors` check)

```java
            for (JsonNode server : event.path("mcp_servers")) {
                if (server.path("status").asText().equals("failed")) {
                    // The run goes on without it (spec: owner plugins).
                    Log.warn("agent.mcp_failed", "server", server.path("name").asText(), "run", logBase);
                }
            }
```

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q test -Dtest=StreamParserTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/agent/claude/StreamParser.java src/test/java/dispatch/agent/claude/StreamParserTest.java
git commit -m "A listed MCP server that fails to start in a run is logged as agent.mcp_failed"
```

---

### Task 6: dispatch check reports what is missing

**Files:**
- Modify: `src/main/java/dispatch/cli/Checks.java` (a new public static `ownerPlugins`, called from `run` after the agents loop at line 97)
- Modify: `src/main/java/dispatch/worker/WorkerChecks.java` (call after `checkClaude(config, add)` at line 70)
- Modify: `src/main/resources/texts_en.properties`, `src/main/resources/texts_mn.properties`
- Test: `src/test/java/dispatch/cli/ChecksTest.java`

**Interfaces:**
- Consumes: `OwnerPlugins.lists()`, `OwnerPlugins.resolve(...)`, `OwnerPluginsFixture`.
- Produces: `public static Optional<Checks.Finding> Checks.ownerPlugins(OwnerPlugins owner, String command, Map<String, String> environment)`.

- [ ] **Step 1: Write the failing tests** (imports `dispatch.agent.claude.OwnerPlugins`, `dispatch.testing.FakeClaude`, `dispatch.testing.OwnerPluginsFixture`)

```java
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aListedPluginThatIsMissingFailsTheCheckWithTheRunsText() throws IOException {
        Path claude = FakeClaude.install(Files.createDirectories(dir.resolve("bin")));
        OwnerPluginsFixture.installed(claude, Map.of());
        Path config = Files.writeString(dir.resolve("plugins.yaml"), "agents:\n  claude-code:\n    plugins: [a@m]\n");

        Checks.Finding finding = Checks.ownerPlugins(OwnerPlugins.instance(config, dir), claude.toString(),
                FakeClaude.environment()).orElseThrow();

        assertEquals(Checks.Level.FAIL, finding.level());
        assertTrue(finding.message().english().startsWith("claude-code plugin a@m is not installed on this computer"),
                finding.message().english());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void listedPluginsThatAreAllFoundPassAndNoListsSayNothing() throws IOException {
        Path claude = FakeClaude.install(Files.createDirectories(dir.resolve("bin")));
        OwnerPluginsFixture.installed(claude, Map.of("a@m", OwnerPluginsFixture.plugin(dir.resolve("cache/a"), null)));
        Path listed = Files.writeString(dir.resolve("plugins.yaml"), "agents:\n  claude-code:\n    plugins: [a@m]\n");
        Path none = Files.writeString(dir.resolve("none.yaml"), "agents:\n  claude-code:\n    command: claude\n");

        Checks.Finding finding = Checks.ownerPlugins(OwnerPlugins.instance(listed, dir), claude.toString(),
                FakeClaude.environment()).orElseThrow();

        assertEquals(Checks.Level.OK, finding.level());
        assertEquals("claude-code: 1 listed plugin(s) and 0 MCP server(s) found", finding.message().english());
        assertTrue(Checks.ownerPlugins(OwnerPlugins.instance(none, dir), claude.toString(), FakeClaude.environment()).isEmpty());
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest=ChecksTest`
Expected: compilation FAILS: no method `ownerPlugins` in `Checks`.

- [ ] **Step 3: Implement**

`Checks.java`:

```java
    /** The plugins and MCP servers listed for Claude Code runs, resolved as the next run will resolve them (spec: owner plugins). */
    public static Optional<Finding> ownerPlugins(OwnerPlugins owner, String command, Map<String, String> environment) {
        try {
            OwnerPlugins.Lists lists = owner.lists();
            if (lists.isEmpty()) {
                return Optional.empty();
            }
            owner.resolve(command, ProcessRun.agentEnvironment(environment));
            return Optional.of(new Finding(Level.OK, "claude-code",
                    Text.of("check.ownerPlugins", lists.plugins().size(), lists.mcpServers().size())));
        } catch (AgentStartException e) {
            return Optional.of(new Finding(Level.FAIL, "claude-code", Text.raw(e.getMessage())));
        }
    }
```

In `run`, after `config.agents().forEach(... checkAgent ...)`:

```java
        Config.Agent claude = config.agents().get("claude-code");
        if (claude != null && !team) {
            // A team machine runs no task's agent; its members' computers check their own lists.
            ownerPlugins(OwnerPlugins.instance(configFile, Path.of(System.getProperty("user.home"))), claude.command(),
                    prepared.environment()).ifPresent(finding -> run.add(finding.level(), finding.area(), finding.message()));
        }
```

`WorkerChecks.run`, after `checkClaude(config, add);`:

```java
        Checks.ownerPlugins(OwnerPlugins.worker(workerFile, Path.of(System.getProperty("user.home"))), config.claudeCommand(),
                processEnvironment).ifPresent(add);
```

`texts_en.properties`: `check.ownerPlugins=claude-code: {0} listed plugin(s) and {1} MCP server(s) found`
`texts_mn.properties`: `check.ownerPlugins=claude-code: жагсаасан {0} plugin, {1} MCP сервер бүгд олдсон`

- [ ] **Step 4: Run them to see them pass**

Run: `./mvnw -q test -Dtest='ChecksTest,WorkerChecksTest,TextTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/cli/Checks.java src/main/java/dispatch/worker/WorkerChecks.java \
  src/main/resources/texts_en.properties src/main/resources/texts_mn.properties src/test/java/dispatch/cli/ChecksTest.java
git commit -m "dispatch check reports a listed Claude Code plugin or MCP server that this computer does not have"
```

---

### Task 7: The live check and the docs

**Files:**
- Create: `src/test/java/dispatch/core/LiveOwnerPluginsTest.java`
- Create: `docs/adr/0036-claude-runs-load-the-plugins-and-mcp-servers-the-owner-lists.md`
- Modify: `SECURITY.md` (the sandbox section's protects list), `README.en.md` (the "Skills in Claude's runs" section), `README.md` (the Skills row), `docs/ARCHITECTURE.md` (the ClaudeCodeAgent "Always" row)

- [ ] **Step 1: The live test**

```java
package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.AgentStartException;
import dispatch.agent.ProcessRun;
import dispatch.agent.RunRequest;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.agent.claude.OwnerPlugins;
import dispatch.agent.sandbox.Bubblewrap;
import dispatch.agent.sandbox.Confinement;
import dispatch.agent.sandbox.Probe;
import dispatch.agent.sandbox.Sandbox;
import dispatch.agent.sandbox.SandboxPolicies;
import dispatch.agent.sandbox.SandboxSetting;
import dispatch.agent.sandbox.Sandboxes;
import dispatch.domain.RunKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Real Claude Code in the real sandbox with the owner's Playwright plugin listed (spec: owner plugins): its server is
 * connected and the agent uses one of its tools. Off unless DISPATCH_LIVE_CLAUDE=1, since it spends the account's quota.
 */
class LiveOwnerPluginsTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "DISPATCH_LIVE_CLAUDE", matches = "1")
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void aListedPluginsServerRunsInTheRealSandbox() throws Exception {
        Sandbox sandbox = Sandboxes.detect(SandboxSetting.AUTO, Probe.system(System.getenv()));
        assumeTrue(sandbox instanceof Bubblewrap, "needs bwrap");
        Path root = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "live-owner");
        Path stateDir = Files.createDirectories(root.resolve("state"));
        Path workdir = Files.createDirectories(root.resolve("work"));
        Path home = Path.of(System.getProperty("user.home"));
        Path config = Files.writeString(root.resolve("dispatch.yaml"),
                "agents:\n  claude-code:\n    command: claude\n    plugins: [playwright@claude-plugins-official]\n");
        OwnerPlugins owner = OwnerPlugins.instance(config, home);
        try {
            owner.resolve("claude", ProcessRun.agentEnvironment(System.getenv()));
        } catch (AgentStartException e) {
            assumeTrue(false, "the playwright plugin is not installed here: " + e.getMessage());
        }
        ClaudeCodeAgent claude = new ClaudeCodeAgent("claude", System.getenv(), Duration.ofSeconds(10),
                new Confinement(sandbox, new SandboxPolicies(home, stateDir, List.of(stateDir))), owner);
        Path logBase = stateDir.resolve("runs/1/1");

        AgentResult result = claude.start(new RunRequest(RunKind.EXECUTE, workdir,
                "Use the Playwright MCP server's browser_navigate tool to open about:blank, then reply with just: done",
                UUID.randomUUID(), false, List.of(), null, "sonnet", null, logBase)).await();

        JsonNode init = Files.readAllLines(Path.of(logBase + ".jsonl")).stream().map(Json::read)
                .filter(event -> event.path("subtype").asText().equals("init")).findFirst().orElseThrow();
        System.out.println("LIVE owner plugins: " + result.outcome() + " (" + result.summary() + ") mcp_servers="
                + init.path("mcp_servers"));
        assertEquals(AgentOutcome.SUCCEEDED, result.outcome(), result.error());
        boolean connected = false;
        for (JsonNode server : init.path("mcp_servers")) {
            connected |= server.path("name").asText().equals("plugin_playwright_playwright")
                    && server.path("status").asText().equals("connected");
        }
        assertTrue(connected, init.path("mcp_servers").toString());
    }
}
```

Run: `DISPATCH_LIVE_CLAUDE=1 ./mvnw -q test -Dtest=LiveOwnerPluginsTest`
Expected: PASS with a `LIVE owner plugins: SUCCEEDED (done) mcp_servers=[...connected...]` line; without the variable: 1 test, skipped. If the server is `failed` in the real sandbox, stop: that is a finding about what Playwright needs from the sandbox, not something to work around in the test.

- [ ] **Step 2: ADR 0036** (`docs/adr/0036-claude-runs-load-the-plugins-and-mcp-servers-the-owner-lists.md`)

```markdown
# Claude Code runs load the plugins and MCP servers the machine's owner lists

Amends ADR 0034 (the owner's own plugins never load into a run) and the strict MCP configuration of every Claude Code
run (ADR 0009, ADR 0024).

A machine's owner names, in `dispatch.yaml` (`agents.claude-code.plugins`, `agents.claude-code.mcpServers`) or a member's
`worker.yaml` (`claudePlugins`, `claudeMcpServers`), the plugins and user-scope MCP servers that Claude Code plan,
execution and review runs on that machine load; the agent picks what fits the task, such as frontend-design for a page.
The lists are read again from the file before each run, so an edit applies without a restart. Each plugin is found with
`claude plugin list --json`; Dispatch builds one MCP configuration from the listed servers (`~/.claude.json`) and the
listed plugins' own (named `plugin_<plugin>_<server>`, as Claude Code names them) and passes it inline after
`--mcp-config`, keeping `--strict-mcp-config`, so nothing unlisted starts: no claude.ai connector, no repository's
`.mcp.json`. Something listed and missing fails the run before its agent starts, and `dispatch check` says what to do.

We chose this over:
- **Everything the owner has enabled.** Plugins built for a person at the keyboard (a hook that tells the agent to stop
  and ask) would load into runs nobody answers.
- **The repository deciding** (its `.claude/settings.json` and `.mcp.json`). A commit per repository, and MCP
  credentials must not live in a repository.
- **Letting Claude Code start a plugin's servers itself**, with the claude.ai connectors switched off
  (`ENABLE_CLAUDEAI_MCP_SERVERS=false`). Less code, but a repository's approved `.mcp.json` servers could start, and so
  could any MCP source a later Claude Code adds.

Consequences: a listed server's tools and credentials are the agent's, so a prompt-injected agent can use them; listing
is the owner's decision, per machine. A plugin's hooks and servers run inside the sandbox, and one that needs what the
sandbox hides fails (`agent.mcp_failed`). Only user-scope servers can be listed. A team machine's lists never reach a
member's computer. Each run pays one `claude plugin list --json`.
```

- [ ] **Step 3: SECURITY.md, the READMEs, ARCHITECTURE**

- `SECURITY.md`, in the sandbox section's "Does not protect" bullet, append: ` A plugin or MCP server the owner lists for Claude Code runs (ADR 0036) runs inside the sandbox, and a listed server's tools and credentials are the agent's.`
- `README.en.md`, at the end of "Skills in Claude's runs", add a paragraph: `A machine can add its own Claude Code plugins and MCP servers to those runs (ADR 0036): list them in dispatch.yaml under agents.claude-code (plugins: [frontend-design@claude-plugins-official], mcpServers: [mongodb]) or in a member's worker.yaml (claudePlugins, claudeMcpServers). Each run reads the lists again, so an edit applies without a restart; something listed and missing fails the run and dispatch check says what to do. Nothing unlisted loads: not your other plugins, your claude.ai connectors, or a repository's .mcp.json.`
- `README.md`, at the end of the Skills row, before the closing `|`: ` Өөрийн plugin, MCP серверүүдийг dispatch.yaml-ийн agents.claude-code-д (plugins, mcpServers) жагсааж болно; ажил бүр жагсаалтыг дахин уншина (ADR 0036).`
- `docs/ARCHITECTURE.md`, in the ClaudeCodeAgent table's "Always" cell after `--add-dir <attachments>`: `, and for plan, execute and review runs the owner's listed plugins (--plugin-dir, --add-dir) and MCP servers (--mcp-config <one line of JSON>), read again before each run (ADR 0036)`

- [ ] **Step 4: The whole suite**

Run: `./mvnw -q verify`
Expected: exit 0, no failing suite.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/dispatch/core/LiveOwnerPluginsTest.java docs/adr/0036-claude-runs-load-the-plugins-and-mcp-servers-the-owner-lists.md \
  SECURITY.md README.en.md README.md docs/ARCHITECTURE.md
git commit -m "ADR 0036, the security notes and the READMEs describe the plugins and MCP servers a machine's owner lists, and an opt-in live test runs Playwright's"
```
