# Several Instances on One Computer (M) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A personal bot and a team bot run side by side on one computer, each made by `dispatch init`, each kept running by `dispatch service install`, addressed with `--instance NAME`.

**Architecture:** An instance name maps to sibling paths of the default instance (`team.yaml`, `dispatch-team/`) inside `Locations`; the service writers take the instance at construction and derive every OS name from it. Branch names become `<branchPrefix>/<task>`: the team machine's `Coordinator` puts the full branch name into each `Job`, so a member's worker (which runs team tasks, ADR 0021) uses the team's prefix, and the field is left out of the JSON when the prefix is the default, so older workers keep working.

**Tech Stack:** Java 25, JUnit 6, Maven (`./mvnw`), Jackson, no framework.

**Spec:** `docs/superpowers/specs/2026-09-28-several-instances-design.md` (amended by Task 0 of this plan).

## Global Constraints

- The default instance changes nothing: `dispatch.yaml`, `dispatch.env`, its state dir, `dispatch.service` / `io.dispatch.agent` / `Dispatch`, branches `dispatch/<task>`.
- Instance name: 1–32 chars, `a-z`, `0-9`, `-`, starts with a letter; `dispatch` and `worker` refused.
- Named instance `team`: config `<config dir>/team.yaml` + `team.env`; state sibling `<state parent>/dispatch-team` (Windows `Dispatch-team`); systemd `dispatch-team.service`, launchd `io.dispatch.agent.team`, Task Scheduler `Dispatch-team`; branches `dispatch/team/<task>`.
- `--config FILE` wins over `--instance`. `worker` commands do not take `--instance`.
- The service of a named instance runs `dispatch run --instance NAME --log-file …`; the default instance's unit keeps `--config …`.
- `branchPrefix` is explicit config, default `dispatch`; init writes it only for a named instance.
- Same bot token in two instances: init refuses, check fails. Ports shared by two instances: check warns. Same `branchPrefix` + same clone path in two instances: check fails.
- Tokens are compared by the bot id (digits before `:`), never printed.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Work on branch `m-instances` from `main`; run `./mvnw -q test` before each commit touching Java.

## Review Focus

- **An older worker paired to a team whose prefix is the default** must keep parsing jobs (`Json.MAPPER` fails on unknown properties): the `branch` field is omitted when null. Pinned in Task 3.
- **`--instance` and `--config` together**: `--config` wins, as the spec says, and the command works on that file. Pinned in Task 4 (CliTest).
- **A stray `team.yaml` that is not a Dispatch config** (e.g. some other tool's file in `~/.config/dispatch`): `dispatch list` shows it with the loader error, and `check`'s clash scan skips it rather than failing the whole check. Pinned in Tasks 6 and 7.
- **Init answered "no" to "Add another bot here?"** writes nothing and exits 1 with today's advice. Pinned in Task 5.
- **`dispatch ui` with 7878 taken and no `--port`** takes the next free port instead of failing; with an explicit `--port` that is taken it still fails as today. Pinned in Task 8.

---

### Task 0: Branch and spec amendment

**Files:**
- Modify: `docs/superpowers/specs/2026-09-28-several-instances-design.md`

- [ ] **Step 1: Create the branch**

```bash
git switch -c m-instances main
```

- [ ] **Step 2: Amend the spec** — replace the paragraph under `## Services` that starts "`Service.Kind` stays the two kinds" with:

```markdown
`Service.Kind` stays the two kinds (Dispatch, worker). The instance is given when the service is made
(`Service.forThisMachine(kind, instance)`), because `stop`, `start`, `status` and `uninstall` never see a spec; each OS
writer derives its names from it: the unit, label or task name above. `Service.Spec` carries the instance too, so the
definition runs `dispatch run --instance team --log-file …`; the default instance's definition keeps `--config …`.
```

and append to `## Branch prefix`:

```markdown
In team mode a member's worker creates and pushes the branch (ADR 0021), so the prefix travels with the run: the team
machine's `Coordinator` puts the whole branch name into each `Job` (`branch`), and a worker uses it. The field is left
out when the team uses the default prefix, so a worker older than this change keeps working with such a team; a team
with its own prefix needs its members' workers updated.
```

and replace the `## Clashes` bullet on ports with:

```markdown
- **Ports** (`miniApp.port`, `workers.port`): `dispatch init` suggests the first port from 7880 up that no other
  instance claims; the web setup keeps its 7880 default. `dispatch check` warns when two instances claim the same one.
  `dispatch ui` without `--port` takes 7878, or the next free port when 7878 is in use, and prints the one it took.
```

and in `## Commands`, under the `dispatch list` example, add: "`list` works offline: it shows each bot by its id (the
digits before the token's colon), not its @username, so it never waits on Telegram."

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-09-28-several-instances-design.md
git commit -m "Amend M: service names come with the instance, the branch travels with the job

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 1: Instance names and their locations

**Files:**
- Modify: `src/main/java/dispatch/cli/Locations.java`
- Test: `src/test/java/dispatch/cli/LocationsTest.java`

**Interfaces:**
- Produces:
  - `static String Locations.validName(String name)` — returns `name`, or throws `CliException` with the rule.
  - `Locations Locations.forInstance(String name)` — `null` returns `this`; otherwise the named instance's config file and state dir.
  - `String Locations.instanceOf(Path configFile)` — the instance a config file belongs to: `null` for the default one or a file outside this config folder, else the file's base name.
  - `Path Locations.configDir()` — `configFile.getParent()`.

- [ ] **Step 1: Write the failing tests** (append to `LocationsTest`)

```java
    @Test
    void aNamedInstanceLivesBesideTheDefaultOne() {
        Locations linux = Locations.of("Linux", Map.of(), HOME).forInstance("team");
        Locations windows = Locations.of("Windows 11", Map.of("APPDATA", "roaming", "LOCALAPPDATA", "local"), HOME).forInstance("team");

        assertEquals(HOME.resolve(".config/dispatch/team.yaml"), linux.configFile());
        assertEquals(HOME.resolve(".local/state/dispatch-team"), linux.stateDir());
        assertEquals(Path.of("roaming", "Dispatch", "team.yaml"), windows.configFile());
        assertEquals(Path.of("local", "Dispatch-team"), windows.stateDir());
    }

    @Test
    void noNameIsTheDefaultInstance() {
        Locations defaults = Locations.of("Linux", Map.of(), HOME);

        assertSame(defaults, defaults.forInstance(null));
        assertNull(defaults.instanceOf(defaults.configFile()));
        assertEquals("team", defaults.instanceOf(HOME.resolve(".config/dispatch/team.yaml")));
        assertNull(defaults.instanceOf(Path.of("/etc/dispatch/backend.yaml")), "the server layout is not an instance here");
        assertNull(defaults.instanceOf(HOME.resolve(".config/dispatch/worker.yaml")));
    }

    @Test
    void namesAreShortLowercaseAndNotReserved() {
        assertEquals("team-2", Locations.validName("team-2"));
        for (String bad : List.of("", "Team", "2team", "team_x", "dispatch", "worker", "a".repeat(33), "../x")) {
            assertThrows(CliException.class, () -> Locations.validName(bad), bad);
        }
    }
```

Add imports: `assertNull`, `assertSame`, `assertThrows`, `java.util.List`.

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q test -Dtest=LocationsTest`
Expected: compilation FAIL — `forInstance`, `instanceOf`, `validName` not defined.

- [ ] **Step 3: Implement** (add to `Locations`)

```java
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final java.util.Set<String> RESERVED = java.util.Set.of("dispatch", "worker");

    /** An instance name: a file name, a service name and a branch segment at once, so kept to what all three accept. */
    public static String validName(String name) {
        if (name == null || !NAME.matcher(name).matches() || RESERVED.contains(name)) {
            throw new CliException("an instance name is 1-32 of a-z, 0-9 and '-', starting with a letter, and not "
                    + "'dispatch' or 'worker'; got '" + name + "'");
        }
        return name;
    }

    /** The named instance's files: beside the default instance's, never inside its state directory. */
    public Locations forInstance(String name) {
        if (name == null) {
            return this;
        }
        validName(name);
        return new Locations(configFile.resolveSibling(name + ".yaml"),
                stateDir.resolveSibling(stateDir.getFileName() + "-" + name));
    }

    /** @return the instance {@code file} is the config of; null for the default instance or a file elsewhere */
    public String instanceOf(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.getParent().equals(configDir().toAbsolutePath().normalize())) {
            return null;
        }
        String name = absolute.getFileName().toString();
        if (!name.endsWith(".yaml")) {
            return null;
        }
        String base = name.substring(0, name.length() - ".yaml".length());
        return NAME.matcher(base).matches() && !RESERVED.contains(base) ? base : null;
    }

    public Path configDir() {
        return configFile.getParent();
    }
```

- [ ] **Step 4: Run to verify they pass**

Run: `./mvnw -q test -Dtest=LocationsTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/cli/Locations.java src/test/java/dispatch/cli/LocationsTest.java
git commit -m "Name instances and place their files beside the default one's

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: A service per instance

**Files:**
- Modify: `src/main/java/dispatch/cli/Service.java` (Kind names, `Spec`, `forOs`, `forThisMachine`)
- Modify: `src/main/java/dispatch/cli/SystemdService.java`, `LaunchdService.java`, `WindowsTaskService.java`
- Modify: `src/main/java/dispatch/cli/ServiceCommand.java` (`specFor`, `forThisMachine`)
- Test: `src/test/java/dispatch/cli/ServiceTest.java`

**Interfaces:**
- Consumes: `Locations.validName` (Task 1).
- Produces:
  - `record Service.Spec(Path java, Path jar, Path configFile, Path logFile, String path, Path stateDir, String instance)` plus the old 6-arg constructor delegating with `instance = null`.
  - `String Kind.systemdUnit(String instance)`, `Kind.launchdLabel(String instance)`, `Kind.windowsTask(String instance)`, `Kind.manageCommand(String instance)`; the no-arg ones stay and mean `null`.
  - `List<String> Spec.arguments(Kind kind)` — `kind.command()` then `--instance NAME` (named) or `--config FILE` (default), then `--log-file FILE`.
  - `static Service Service.forOs(String osName, Path home, Commands commands, String user, Kind kind, String instance)`; the existing overloads pass `null`.
  - `static Service Service.forThisMachine(Kind kind, String instance)`; `forThisMachine()` and `forThisMachine(kind)` pass `null`.
  - `static ServiceCommand ServiceCommand.forThisMachine(Terminal terminal, String instance)`.
  - `static Service.Spec ServiceCommand.specFor(Path jar, Path configFile, String instance, Map<String,String> env)`.

- [ ] **Step 1: Write the failing tests** (append to `ServiceTest`; reuse its `Recorder`, `spec(home)` helper and `JAVA`)

```java
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a systemd unit only ever holds Linux paths")
    void aNamedInstanceGetsItsOwnUnitThatRunsByName() throws IOException {
        Path home = dir.resolve("home");
        Service service = Service.forOs("Linux", home, commands, "bold", Service.Kind.DISPATCH, "team");

        service.install(named(home, "team"));

        String unit = Files.readString(home.resolve(".config/systemd/user/dispatch-team.service"));
        assertTrue(unit.contains(" run --instance team --log-file "), unit);
        assertFalse(unit.contains("--config"), unit);
        assertTrue(commands.run.contains("systemctl --user enable --now dispatch-team.service"), commands.run.toString());
        assertEquals("systemd user service dispatch-team.service", service.describe());
        assertFalse(Files.exists(home.resolve(".config/systemd/user/dispatch.service")), "the default unit is untouched");
    }

    @Test
    void aNamedInstanceHasItsOwnLaunchdLabelAndWindowsTask() throws IOException {
        Path home = dir.resolve("home");
        commands.answer("id -u", 0, "501\n");
        Service mac = Service.forOs("Mac OS X", home, commands, "bold", Service.Kind.DISPATCH, "team");
        mac.install(named(home, "team"));

        String plist = Files.readString(home.resolve("Library/LaunchAgents/io.dispatch.agent.team.plist"));
        assertTrue(plist.contains("<string>--instance</string>\n    <string>team</string>"), plist);

        Service windows = Service.forOs("Windows 11", home, commands, "PC\\bold", Service.Kind.DISPATCH, "team");
        assertEquals("Task Scheduler task Dispatch-team", windows.describe());
        assertEquals("dispatch service --instance team", Service.Kind.DISPATCH.manageCommand("team"));
    }

    private Service.Spec named(Path home, String instance) {
        Service.Spec base = spec(home);
        return new Service.Spec(base.java(), base.jar(), home.resolve(".config/dispatch/" + instance + ".yaml"),
                home.resolve("state-" + instance + "/dispatch.log"), base.path(), home.resolve("state-" + instance), instance);
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q test -Dtest=ServiceTest`
Expected: compilation FAIL — 6-arg `forOs` and 7-arg `Spec` not defined.

- [ ] **Step 3: Implement**

In `Service.java`:

```java
    record Spec(Path java, Path jar, Path configFile, Path logFile, String path, Path stateDir, String instance) {

        public Spec(Path java, Path jar, Path configFile, Path logFile, String path, Path stateDir) {
            this(java, jar, configFile, logFile, path, stateDir, null);
        }

        /** What follows {@code -jar dispatch.jar}: a named instance is found by name, so its definition survives a moved config folder. */
        public List<String> arguments(Kind kind) {
            List<String> arguments = new java.util.ArrayList<>(kind.command());
            if (instance != null) {
                arguments.addAll(List.of("--instance", instance));
            } else {
                arguments.addAll(List.of("--config", configFile.toString()));
            }
            arguments.addAll(List.of("--log-file", logFile.toString()));
            return arguments;
        }
    }
```

In `Kind`, add (the no-arg getters stay as they are):

```java
        public String systemdUnit(String instance) {
            return instance == null ? systemdUnit : systemdUnit.replace(".service", "-" + instance + ".service");
        }

        public String launchdLabel(String instance) {
            return instance == null ? launchdLabel : launchdLabel + "." + instance;
        }

        public String windowsTask(String instance) {
            return instance == null ? windowsTask : windowsTask + "-" + instance;
        }

        public String manageCommand(String instance) {
            return instance == null ? manageCommand : manageCommand + " --instance " + instance;
        }
```

`forOs`/`forThisMachine`: add the `String instance` parameter as listed in Interfaces, pass it to each writer's constructor; the existing signatures delegate with `null`.

In each writer: add `private final String instance;` set in the constructor, and replace every `kind.systemdUnit()` / `kind.launchdLabel()` / `kind.windowsTask()` with the `(instance)` variant (SystemdService `:19,32,59,64,69,74,82,94`; LaunchdService `:20,32,70,72,83,94,104,115`; WindowsTaskService `:34,76,89,90,95,100,107,113,124,125`). Replace the hand-built argument text:

- SystemdService `install`: `ExecStart=%s -jar %s %s` with the third value `spec.arguments(kind).stream().map(SystemdService::quotedIfPath).collect(joining(" "))`, where values that came from paths are quoted as today. Keep it simple: quote every argument after the command words:

```java
        List<String> arguments = spec.arguments(kind);
        String line = String.join(" ", kind.command()) + " " + arguments.subList(kind.command().size(), arguments.size()).stream()
                .map(argument -> argument.startsWith("--") ? argument : quoted(argument))
                .collect(java.util.stream.Collectors.joining(" "));
```

  then `ExecStart=%s -jar %s %s` with `quoted(spec.java()), quoted(spec.jar()), line`. The default instance's line is byte-identical to today's (`run --config "<file>" --log-file "<file>"`), which the existing `linuxGetsASystemdUserUnit…` test asserts.
- LaunchdService: build the whole `<string>` list from `spec.arguments(kind)` instead of `kind.command()` + the fixed `--config`/`--log-file` lines.
- WindowsTaskService `:40`: `"-jar " + quoted(spec.jar()) + " " + spec.arguments(kind).stream().map(a -> a.startsWith("--") ? a : quoted(a))…` in place of the fixed text, keeping `kind.command()` words unquoted as today.

In `ServiceCommand`:

```java
    public static ServiceCommand forThisMachine(Terminal terminal, String instance) {
        return new ServiceCommand(terminal, Service.forThisMachine(Service.Kind.DISPATCH, instance), runningJar());
    }

    public static Service.Spec specFor(Path jar, Path configFile, String instance, Map<String, String> processEnvironment) {
        Service.Spec spec = specFor(jar, configFile, processEnvironment);
        return new Service.Spec(spec.java(), spec.jar(), spec.configFile(), spec.logFile(), spec.path(), spec.stateDir(), instance);
    }
```

`forThisMachine(terminal)` delegates with `null`. `install(...)` prints `service.kind().manageCommand(instance)` — keep an `instance` field in `ServiceCommand` (constructor overload with `String instance`, old constructor passes `null`) and use it in `install` and `status` messages.

- [ ] **Step 4: Run to verify they pass**

Run: `./mvnw -q test -Dtest=ServiceTest`
Expected: PASS, including the existing default-instance tests unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/cli/*Service*.java src/test/java/dispatch/cli/ServiceTest.java
git commit -m "Give each instance its own background service

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Branch prefix, carried by the job

**Files:**
- Modify: `src/main/java/dispatch/config/Config.java` (component `branchPrefix`, method `branch(long)`)
- Modify: `src/main/java/dispatch/config/ConfigLoader.java` (`ConfigFile.branchPrefix`, validation, `new Config`)
- Modify: `src/main/java/dispatch/core/Job.java` (component `branch`, method `branchName()`)
- Modify: `src/main/java/dispatch/core/Coordinator.java:126-135`, constructor
- Modify: `src/main/java/dispatch/core/JobRunner.java:108,129,150,165`
- Modify: `src/main/java/dispatch/core/Sweeper.java:35,96`
- Modify: `src/main/java/dispatch/workspace/Workspaces.java:150-200`, `Delivery.java:43-77`
- Modify: `src/main/java/dispatch/worker/WorkerLoop.java:214-217`
- Modify: `src/main/java/dispatch/App.java:107-109,154,216`
- Modify: every `new Config(` in tests (`WorkerApiFixture`, `TeamWorkersTest`, `AppTest` — 5 sites): add `null` for `branchPrefix`.
- Test: `src/test/java/dispatch/core/JobJsonTest.java`, `src/test/java/dispatch/config/ConfigLoaderTest.java`, `src/test/java/dispatch/core/JobRunnerTest.java`

**Interfaces:**
- Produces:
  - `Config.branchPrefix()` (String, null = default) and `String Config.branch(long taskId)` → `(branchPrefix == null ? "dispatch" : branchPrefix) + "/" + taskId`.
  - `static String Config.defaultBranch(long taskId)` → `"dispatch/" + taskId`.
  - `Job.branch()` (String, `@JsonInclude(NON_NULL)`, null = default) and `String Job.branchName()` → `branch != null ? branch : Config.defaultBranch(taskId)`. The old 19-arg `Job` constructor stays and passes `branch = null`.
  - `Workspaces.createWorktree(Config.Project, long taskId, String branch)`, `recreateWorktree(Config.Project, long taskId, String branch)`, `state(Path worktree, String branch, String baseSha)`.
  - `Delivery.deliver(Path, long taskId, String branch, String baseBranch, String startSha, Commit, String prUrl)`, `redeliver(Path, long taskId, String branch, String baseBranch, String baseSha, Commit, String prUrl)`.
  - `Coordinator(..., String branchPrefix)` overload; `Sweeper(..., String branchPrefix)` overload; old constructors pass `null`.

- [ ] **Step 1: Write the failing tests**

`JobJsonTest` (add):

```java
    @Test
    void theDefaultBranchIsLeftOutSoOlderWorkersStillReadTheJob() throws Exception {
        Job job = new Job(7, 1, RunKind.PLAN, new Job.Project("alm", "r", null, "main", "claude-code", List.of()), "main",
                null, null, null, null, false, "p", null, null, 1000, BigDecimal.ONE, List.of(), "s", List.of(), null);

        String json = Json.write(job);

        assertFalse(json.contains("\"branch\""), json);
        assertEquals("dispatch/7", Json.MAPPER.readValue(json, Job.class).branchName());
    }

    @Test
    void aTeamsOwnPrefixTravelsWithTheJob() throws Exception {
        Job job = new Job(7, 1, RunKind.PLAN, new Job.Project("alm", "r", null, "main", "claude-code", List.of()), "main",
                null, null, null, null, false, "p", null, null, 1000, BigDecimal.ONE, List.of(), "s", List.of(), null,
                "dispatch/team/7");

        assertEquals("dispatch/team/7", Json.MAPPER.readValue(Json.write(job), Job.class).branchName());
    }
```

`ConfigLoaderTest` (add; follow the file's existing helper that writes a minimal valid YAML — call it with an extra line `branchPrefix: dispatch/team` / `branchPrefix: /bad`):

```java
    @Test
    void branchPrefixDefaultsToDispatchAndMustBeARefPath() {
        assertEquals("dispatch/12", load(minimal()).branch(12));
        assertEquals("dispatch/team/12", load(minimal() + "branchPrefix: dispatch/team\n").branch(12));
        for (String bad : List.of("/x", "x/", "a..b", "a b", "a//b", "a~b")) {
            ConfigException e = assertThrows(ConfigException.class, () -> load(minimal() + "branchPrefix: '" + bad + "'\n"));
            assertTrue(e.getMessage().contains("branchPrefix"), e.getMessage());
        }
    }
```

`JobRunnerTest` (add; the file already makes a real origin repo in `repos` and builds jobs with `job(...)`): a PLAN job with branch `dispatch/team/<TASK>` creates that local branch:

```java
    @Test
    void theWorktreeIsOnTheBranchTheJobNames() throws Exception {
        Job plan = withBranch(job(1, RunKind.PLAN, null, null, null), "dispatch/team/" + TASK);

        runner.run(plan, events, control());

        assertEquals("dispatch/team/" + TASK, git(Path.of(events.worktree), "rev-parse", "--abbrev-ref", "HEAD"));
    }
```

with helper `withBranch(Job j, String b)` returning a copy through the 20-arg constructor. (Use the file's existing `git(...)`/`runner`/`events`/`control()` names; if a helper is named differently there, use that name.)

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q test -Dtest='JobJsonTest,ConfigLoaderTest,JobRunnerTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement**

`Config`: add component `String branchPrefix` after `miniApp` (before `secrets`), and:

```java
    public String branch(long taskId) {
        return (branchPrefix == null ? "dispatch" : branchPrefix) + "/" + taskId;
    }

    public static String defaultBranch(long taskId) {
        return "dispatch/" + taskId;
    }
```

`ConfigLoader`: add `String branchPrefix` to `record ConfigFile` (last), validate before the error check:

```java
        if (raw.branchPrefix() != null && !BRANCH_PREFIX.matcher(raw.branchPrefix()).matches()) {
            errors.add("branchPrefix: segments of letters, digits, '.', '_' or '-' separated by single '/', e.g. dispatch/team; got '"
                    + raw.branchPrefix() + "'");
        }
```

with `private static final Pattern BRANCH_PREFIX = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*(/[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*)*");` and pass `raw.branchPrefix()` into `new Config(...)`.

`Job`: add last component `@JsonInclude(JsonInclude.Include.NON_NULL) String branch`, keep a 19-arg constructor delegating with `null`, and:

```java
    /** The task's branch: the team's prefix when it sent one, else dispatch/<task>. */
    public String branchName() {
        return branch != null ? branch : dispatch.config.Config.defaultBranch(taskId);
    }
```

Mark `branchName` `@JsonIgnore` so it is not serialised.

`Coordinator`: new field `branchPrefix`, overload constructor; in `newJob` pass `branchPrefix == null ? null : branchPrefix + "/" + task.id()` as the 20th argument.

`Workspaces`: `createWorktree(project, taskId, branch)` uses `branch` in `worktree add -b`; `recreateWorktree(project, taskId, branch)` uses it for `String branch`; `state(worktree, branch, baseSha)` uses `"refs/heads/" + branch` in `ls-remote`. Remove the old signatures (all callers change in this task).

`Delivery`: `deliver`/`redeliver` take `String branch` after `taskId` and drop the `"dispatch/" + taskId` lines.

`JobRunner`: pass `job.branchName()` at `:108`, `:129`, `:150`, `:165`.

`Sweeper`: field `branchPrefix`, overload constructor; `:96` → `workspaces.state(worktree, branchPrefix == null ? Config.defaultBranch(task.id()) : branchPrefix + "/" + task.id(), task.baseSha())`.

`WorkerLoop:214`: pass `job.branch()` as the 20th argument of the copied `Job`.

`App`: `new Coordinator(..., config.branchPrefix())` at `:154`, `new Sweeper(..., config.branchPrefix())` at `:216`.

Test `new Config(` sites: add `null` before the `Secrets` argument.

- [ ] **Step 4: Run the whole suite** (the signature changes reach many tests)

Run: `./mvnw -q test`
Expected: PASS. Fix any remaining caller of the old `Workspaces`/`Delivery` signatures the compiler names, passing `Config.defaultBranch(taskId)`.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "Name task branches <branchPrefix>/<task>, and send the team's branch with each job

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `--instance` on every command

**Files:**
- Modify: `src/main/java/dispatch/cli/Cli.java` (records `Init`, `Service`, `Ui` gain `String instance`; `VALUE_OPTIONS`; `Arguments.configFile`; usage)
- Modify: `src/main/java/dispatch/Main.java` (per-instance `Locations` and `Service`)
- Modify: `src/main/java/dispatch/ui/MiniApp.java:62`
- Test: `src/test/java/dispatch/cli/CliTest.java`

**Interfaces:**
- Consumes: `Locations.forInstance`, `Locations.instanceOf` (Task 1); `Service.forThisMachine(kind, instance)`, `ServiceCommand.forThisMachine(terminal, instance)`, `ServiceCommand.specFor(jar, file, instance, env)` (Task 2).
- Produces:
  - `record Cli.Init(Path configFile, boolean force, boolean advanced, String instance)` (+ old 2/3-arg constructors → `instance = null`).
  - `record Cli.Service(Path configFile, String action, String instance)` (+ 2-arg).
  - `record Cli.Ui(Path configFile, int port, boolean openBrowser, String instance, boolean portGiven)` (+ 3-arg → `null, true`). `portGiven` is used by Task 8.
  - `Cli.Run`, `Check`, `ProjectAdd` unchanged: `--instance` only changes their `configFile`.

- [ ] **Step 1: Write the failing tests** (append to `CliTest`; it already has a `Locations` fixture — use it as `defaults`)

```java
    @Test
    void instanceNamesTheConfigOfEveryCommand() {
        Path team = defaults.forInstance("team").configFile();

        assertEquals(team, ((Cli.Run) Cli.parse(new String[]{"run", "--instance", "team"}, defaults)).configFile());
        assertEquals(team, ((Cli.Check) Cli.parse(new String[]{"check", "--instance", "team"}, defaults)).configFile());
        Cli.Service service = (Cli.Service) Cli.parse(new String[]{"service", "status", "--instance", "team"}, defaults);
        assertEquals(team, service.configFile());
        assertEquals("team", service.instance());
        assertEquals("team", ((Cli.Init) Cli.parse(new String[]{"init", "--instance", "team"}, defaults)).instance());
        assertEquals("team", ((Cli.Ui) Cli.parse(new String[]{"ui", "--instance", "team"}, defaults)).instance());
    }

    @Test
    void configWinsOverInstance() {
        Cli.Run run = (Cli.Run) Cli.parse(new String[]{"run", "--instance", "team", "--config", "/etc/dispatch/x.yaml"}, defaults);

        assertEquals(Path.of("/etc/dispatch/x.yaml"), run.configFile());
    }

    @Test
    void aBadInstanceNameAndWorkerCommandsAreRefused() {
        assertThrows(CliException.class, () -> Cli.parse(new String[]{"run", "--instance", "Team"}, defaults));
        assertThrows(CliException.class, () -> Cli.parse(new String[]{"worker", "run", "--instance", "team"}, defaults));
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q test -Dtest=CliTest`
Expected: FAIL — `run does not take --instance`.

- [ ] **Step 3: Implement**

`Cli`: add `"instance"` to `VALUE_OPTIONS`; allow it for `run`, `service`, `project add`, `init`, `check`, `ui` (and `ask` — add it to that `allow` set too; `Ask` keeps reading its env); not for `worker …`.

```java
        String instance() {
            return values.containsKey("instance") ? Locations.validName(values.get("instance")) : null;
        }

        Path configFile(Locations defaults) {
            if (values.containsKey("config")) {
                return Path.of(values.get("config"));
            }
            return defaults.forInstance(instance()).configFile();
        }
```

Build `Init`, `Service`, `Ui` with `arguments.instance()`; `Ui` also gets `portGiven = arguments.values().containsKey("port")`. Usage text: first line `usage: dispatch [command] [--instance NAME | --config FILE]`, and a line under commands: `  list     the bots set up on this computer` (Task 6 adds the command), and at the end `NAME is another bot on this computer (dispatch list); without it, the first one.`

`Main`:

```java
            case Cli.Init init -> {
                JLineTerminal terminal = JLineTerminal.system();
                Locations here = defaults.forInstance(init.instance());
                System.exit(new InitCommand(terminal, BotApi::create, here, Duration.ofMinutes(3),
                        ServiceCommand.forThisMachine(terminal, init.instance())).run(init, System.getenv()));
            }
            case Cli.Service service -> {
                JLineTerminal terminal = JLineTerminal.system();
                System.exit(ServiceCommand.forThisMachine(terminal, service.instance()).run(service, System.getenv()));
            }
```

and in `ui(...)`: `new UiCommand(System.out, BotApi::create, defaults.forInstance(options.instance()), Service.forThisMachine(Service.Kind.DISPATCH, options.instance()), "/ui")`.

`ServiceCommand.run(Cli.Service options, env)`: use `specFor(jar, options.configFile(), options.instance(), env)`.

`MiniApp:62`: the running bot knows only its config file, so:

```java
        Locations here = Locations.current();
        String instance = here.instanceOf(configFile);
        UiRoutes management = UiRoutes.management(configFile, here.forInstance(instance), bots,
                Service.forThisMachine(Service.Kind.DISPATCH, instance), environment, version(), groups::replace);
```

- [ ] **Step 4: Run to verify they pass**

Run: `./mvnw -q test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/cli/Cli.java src/main/java/dispatch/Main.java src/main/java/dispatch/cli/ServiceCommand.java src/main/java/dispatch/ui/MiniApp.java src/test/java/dispatch/cli/CliTest.java
git commit -m "Address another bot on this computer with --instance NAME

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: The instances on this computer, and `dispatch list`

**Files:**
- Create: `src/main/java/dispatch/cli/Instances.java`
- Create: `src/main/java/dispatch/cli/ListCommand.java`
- Modify: `src/main/java/dispatch/cli/Cli.java` (`record List() implements Invocation`, `case "list"`, permits)
- Modify: `src/main/java/dispatch/Main.java`
- Test: `src/test/java/dispatch/cli/InstancesTest.java`, `src/test/java/dispatch/cli/ListCommandTest.java`

**Interfaces:**
- Consumes: `Locations.instanceOf`, `forInstance` (Task 1); `RunCommand.prepare(Path, Map)` (existing: loads config + secrets beside it); `Service.forThisMachine(kind, instance)` (Task 2).
- Produces:
  - `record Instances.Found(String name, Path configFile, Config config, Map<String,String> environment, String error)` — `name` null for the default instance; `config`/`environment` null when `error` is set.
  - `static List<Instances.Found> Instances.discover(Locations defaults, Map<String,String> processEnvironment)` — the default instance first (only if its file exists), then named ones sorted by name; never throws for a single bad file.
  - `static String Instances.botId(String token)` — digits before `:`, or null.
  - `ListCommand(PrintStream out, Function<String, Service> services)`, `int run(Locations defaults, Map<String,String> env)`.

- [ ] **Step 1: Write the failing tests**

`InstancesTest`:

```java
class InstancesTest {

    @TempDir
    Path home;

    @Test
    void findsTheDefaultAndNamedInstancesAndKeepsOneThatDoesNotLoad() throws IOException {
        Locations defaults = Locations.of("Linux", Map.of(), home);
        TestConfigs.write(defaults.configFile(), "111:AAA", "alm");                  // valid personal config + .env
        TestConfigs.write(defaults.forInstance("team").configFile(), "222:BBB", "alm");
        Files.writeString(defaults.configDir().resolve("broken.yaml"), "team: [");
        Files.writeString(defaults.configDir().resolve("worker.yaml"), "team: https://x\n");

        List<Instances.Found> found = Instances.discover(defaults, Map.of());

        assertEquals(Arrays.asList(null, "broken", "team"), found.stream().map(Instances.Found::name).toList());
        assertNotNull(found.get(1).error());
        assertEquals("222", Instances.botId(found.get(2).config().secrets().telegramBotToken()));
    }
}
```

Create `src/test/java/dispatch/cli/TestConfigs.java` with `static void write(Path yaml, String token, String project)`: writes a minimal valid personal config (copy the smallest valid YAML used in `RunCommandTest`/`CheckCommandTest`, with `stateDir` = `yaml.getParent().resolve("state-" + base name)` and one project whose `path` is a `git init`-ed temp folder named `project`), and `TELEGRAM_BOT_TOKEN=<token>` into the `.env` beside it via `SecretsFile`'s existing writer. If `CheckCommandTest` already has such a helper, move it here and use it from both.

`ListCommandTest`:

```java
    @Test
    void printsOneLinePerInstanceWithModeAndServiceState() throws IOException {
        Locations defaults = Locations.of("Linux", Map.of(), home);
        TestConfigs.write(defaults.configFile(), "111:AAA", "alm");
        TestConfigs.write(defaults.forInstance("team").configFile(), "222:BBB", "alm");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exit = new ListCommand(new PrintStream(out, true, UTF_8), instance -> new FakeService(instance == null))
                .run(defaults, Map.of());

        String text = out.toString(UTF_8);
        assertEquals(0, exit);
        assertTrue(text.contains("default") && text.contains("personal") && text.contains("running"), text);
        assertTrue(text.contains("team") && text.contains("stopped") && text.contains("dispatch service start --instance team"), text);
    }
```

`FakeService` (nested in the test): implements `Service` with `status()` returning `new Status(true, running, running ? "running" : "stopped", List.of())`, `describe()` returning `"fake"`, other methods no-ops, `kind()` → `DISPATCH`.

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q test -Dtest='InstancesTest,ListCommandTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement**

`Instances`:

```java
/** The instances set up on this computer: the default one and each named one in its config folder (M). */
public final class Instances {

    public record Found(String name, Path configFile, Config config, Map<String, String> environment, String error) {
    }

    private Instances() {
    }

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

    private static Found load(String name, Path file, Map<String, String> processEnvironment) {
        try {
            RunCommand.Prepared prepared = RunCommand.prepare(file, processEnvironment);
            return new Found(name, file, prepared.config(), prepared.environment(), null);
        } catch (CliException | ConfigException e) {
            return new Found(name, file, null, null, e.getMessage());
        }
    }

    public static String botId(String token) {
        if (token == null) {
            return null;
        }
        int colon = token.indexOf(':');
        return colon > 0 ? token.substring(0, colon) : null;
    }
}
```

`ListCommand.run`: for each `Found`, print `%-10s %-22s %-9s %s` of name-or-`default`, `@<username>` — do **not** call Telegram here (keep `list` offline and fast): print `bot <botId>` instead of the username —, `personal`/`team` (`config.isTeam()`), and the service state from `services.apply(name).status()` (`running`, `stopped   (dispatch service start[ --instance NAME])`, or `no service  (dispatch service install[ --instance NAME])`). For an error: `name  (does not load: <first line of error>)`. Print `no Dispatch set up on this computer yet: dispatch init` when nothing is found. Return 0.

`Cli`: `record List() implements Invocation {}` — name it `ListInstances` to avoid clashing with `java.util.List`; `case "list" -> { arguments.allow(0, Set.of()); yield new ListInstances(); }`; add to `permits`.

`Main`: `case Cli.ListInstances _ -> System.exit(new ListCommand(System.out, instance -> Service.forThisMachine(Service.Kind.DISPATCH, instance)).run(defaults, System.getenv()));`

- [ ] **Step 4: Run to verify they pass**

Run: `./mvnw -q test -Dtest='InstancesTest,ListCommandTest,CliTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/cli/Instances.java src/main/java/dispatch/cli/ListCommand.java src/main/java/dispatch/cli/Cli.java src/main/java/dispatch/Main.java src/test/java/dispatch/cli/InstancesTest.java src/test/java/dispatch/cli/ListCommandTest.java src/test/java/dispatch/cli/TestConfigs.java
git commit -m "List the bots set up on this computer

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Init adds another bot

**Files:**
- Modify: `src/main/java/dispatch/cli/InitCommand.java:78-127,150` (constructor, `run`, `init`, `bot()`)
- Modify: `src/main/java/dispatch/cli/Setup.java:72-80,240-255` (`Answers.branchPrefix`, render it)
- Test: `src/test/java/dispatch/cli/InitCommandTest.java`, `src/test/java/dispatch/cli/SetupTest.java`

**Interfaces:**
- Consumes: `Instances.discover`, `Instances.botId` (Task 5); `Locations.forInstance`, `validName` (Task 1); `Cli.Init.instance()` (Task 4).
- Produces:
  - `Setup.Answers` gains last component `String branchPrefix` (null = not written); existing constructors pass `null`.
  - `InitCommand(Terminal, Function<String,BotApi>, Locations defaults, Duration, Function<String, ServiceCommand> servicesFor)` — the service command is chosen after the instance is known. Keep the old constructor delegating with `instance -> services`.

- [ ] **Step 1: Write the failing tests** (in `InitCommandTest`, which drives `InitCommand` through `ScriptedTerminal` and a fake `BotApi`; reuse its existing helpers for a full personal run — below they are called `answersForAPersonalBot(...)` and `fakeBots`; use the file's real names)

```java
    @Test
    void withADefaultBotAlreadyHereItOffersAnotherAndWritesItBesideIt() throws IOException {
        Locations defaults = Locations.of("Linux", Map.of(), home);
        TestConfigs.write(defaults.configFile(), "111:AAA", "alm");
        ScriptedTerminal terminal = new ScriptedTerminal(concat(List.of("y", "team"), answersForAPersonalBot("222:BBB")));

        int exit = init(terminal, defaults).run(new Cli.Init(defaults.configFile(), false), Map.of());

        assertEquals(0, exit, terminal.transcript());
        Path team = defaults.forInstance("team").configFile();
        String yaml = Files.readString(team);
        assertTrue(yaml.contains("branchPrefix: dispatch/team"), yaml);
        assertTrue(yaml.contains(defaults.forInstance("team").stateDir().toString()), yaml);
        assertTrue(Files.exists(team.resolveSibling("team.env")));
        assertTrue(terminal.transcript().contains("You already have"), terminal.transcript());
    }

    @Test
    void sayingNoToAnotherBotWritesNothing() throws IOException {
        Locations defaults = Locations.of("Linux", Map.of(), home);
        TestConfigs.write(defaults.configFile(), "111:AAA", "alm");
        ScriptedTerminal terminal = new ScriptedTerminal(List.of("n"));

        int exit = init(terminal, defaults).run(new Cli.Init(defaults.configFile(), false), Map.of());

        assertEquals(1, exit);
        assertFalse(Files.exists(defaults.forInstance("team").configFile()));
        assertTrue(terminal.transcript().contains("dispatch init --force"), terminal.transcript());
    }

    @Test
    void aBotAnotherInstanceRunsIsRefused() throws IOException {
        Locations defaults = Locations.of("Linux", Map.of(), home);
        TestConfigs.write(defaults.configFile(), "111:AAA", "alm");
        ScriptedTerminal terminal = new ScriptedTerminal(concat(List.of("y", "team"), answersForAPersonalBot("111:AAA")));

        init(terminal, defaults).run(new Cli.Init(defaults.configFile(), false), Map.of());

        assertTrue(terminal.transcript().contains("already runs as instance default"), terminal.transcript());
    }
```

`SetupTest`: `Setup.render` with `branchPrefix = "dispatch/team"` contains the line; with `null` it contains no `branchPrefix`.

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q test -Dtest='InitCommandTest,SetupTest'`
Expected: FAIL

- [ ] **Step 3: Implement**

`Setup.render`: after the `stateDir:` line, `if (answers.branchPrefix() != null) yaml.append("branchPrefix: ").append(answers.branchPrefix()).append('\n');`

`InitCommand.run`:

```java
    public int run(Cli.Init options, Map<String, String> processEnvironment) {
        try {
            advanced = options.advanced();
            String instance = options.instance();
            Path configFile = options.configFile().toAbsolutePath();
            if (instance == null && !options.force() && Files.exists(configFile)
                    && configFile.equals(defaults.configFile().toAbsolutePath())) {
                instance = anotherInstance(processEnvironment);
                configFile = defaults.forInstance(instance).configFile().toAbsolutePath();
            }
            this.instance = instance;
            this.locations = defaults.forInstance(instance);
            this.services = servicesFor.apply(instance);
            init(configFile, options.force(), processEnvironment);
            return 0;
        } catch (CliException e) {
            terminal.fail(e.getMessage());
            return 1;
        }
    }

    /** The default instance exists: offer a second bot beside it rather than stopping (M). */
    private String anotherInstance(Map<String, String> env) {
        List<Instances.Found> here = Instances.discover(defaults, env);
        String existing = here.stream().filter(found -> found.name() == null && found.config() != null)
                .map(found -> "bot " + Instances.botId(found.config().secrets().telegramBotToken())).findFirst().orElse("a bot");
        terminal.say("You already have " + existing + " on this computer.");
        if (!terminal.confirm("Add another bot here?", false)) {
            throw new CliException(defaults.configFile() + " already exists; edit it, add projects with dispatch project add, "
                    + "or start over with dispatch init --force");
        }
        Set<String> taken = here.stream().map(Instances.Found::name).filter(Objects::nonNull).collect(toSet());
        String suggestion = "team";
        for (int n = 2; taken.contains(suggestion); n++) {
            suggestion = "team-" + n;
        }
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String name = required("Name for it", suggestion);
            try {
                Locations.validName(name);
                if (!taken.contains(name)) {
                    return name;
                }
                terminal.warn("an instance named " + name + " already exists");
            } catch (CliException e) {
                terminal.warn(e.getMessage());
            }
        }
        throw new CliException("no usable name after " + ATTEMPTS + " tries");
    }
```

Fields `locations`, `services` become non-final and are set in `run`; the constructor stores `defaults` and `servicesFor`. In `bot()`, after `Setup.bot(token, bots)` succeeds and before returning, refuse a token another instance uses:

```java
                String id = Instances.botId(token);
                Optional<Instances.Found> clash = Instances.discover(defaults, env).stream()
                        .filter(found -> found.config() != null && !Objects.equals(found.name(), instance))
                        .filter(found -> id != null && id.equals(Instances.botId(found.config().secrets().telegramBotToken())))
                        .findFirst();
                if (clash.isPresent()) {
                    terminal.warn("@" + bot.username() + " already runs as instance "
                            + (clash.get().name() == null ? "default" : clash.get().name()) + "; create another bot with @BotFather");
                    continue;
                }
```

(`bot()` needs `env`: pass it in from `init`.) In `port()` (`:233`), the default becomes the first port from 7880 up
that no other instance's `miniApp.port` or `workers.port` claims (from `Instances.discover`), instead of the fixed
`"7880"`; add a test in `InitCommandTest` where the default instance has `workers.port: 7880` and the team init's
suggested port is `7881` (assert the transcript shows `(7881)`). In `init`, build `Setup.Answers` with `instance == null ? null : "dispatch/" + instance` as `branchPrefix`, and `Main` constructs `InitCommand` with `defaults` (not `defaults.forInstance(...)`) and `instance -> ServiceCommand.forThisMachine(terminal, instance)` — update Task 4's `Main` lines accordingly.

- [ ] **Step 4: Run to verify they pass**

Run: `./mvnw -q test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/cli/InitCommand.java src/main/java/dispatch/cli/Setup.java src/main/java/dispatch/Main.java src/test/java/dispatch/cli/InitCommandTest.java src/test/java/dispatch/cli/SetupTest.java
git commit -m "Init offers another bot beside the one already here

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: `dispatch check` finds clashes between instances

**Files:**
- Modify: `src/main/java/dispatch/cli/Checks.java:62-95` (new `checkOtherInstances`)
- Modify: `src/main/java/dispatch/cli/CheckCommand.java` (pass `Locations defaults`)
- Test: `src/test/java/dispatch/cli/ChecksTest.java`

**Interfaces:**
- Consumes: `Instances.discover`, `Instances.botId` (Task 5); `Locations.instanceOf` (Task 1); `Config.branch` (Task 3).
- Produces: `Checks(Function<String,BotApi> bots, Locations defaults)`; the 1-arg constructor uses `Locations.current()`. Findings in area `"instances"`.

- [ ] **Step 1: Write the failing test** (in `ChecksTest`)

```java
    @Test
    void anotherInstanceWithTheSameBotOrPortOrBranchesInTheSameCloneIsReported() throws IOException {
        Locations defaults = Locations.of("Linux", Map.of(), home);
        TestConfigs.write(defaults.configFile(), "111:AAA", "alm");
        TestConfigs.write(defaults.forInstance("team").configFile(), "111:AAA", "alm");   // same bot, same clone, no prefix

        List<Checks.Finding> findings = new Checks(fakeBots, defaults).run(defaults.forInstance("team").configFile(), Map.of(), f -> { });

        assertTrue(findings.stream().anyMatch(f -> f.level() == Checks.Level.FAIL && f.message().contains("same bot as instance default")),
                findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.level() == Checks.Level.FAIL && f.message().contains("dispatch/<task> branches")),
                findings.toString());
    }

    @Test
    void aFileThatDoesNotLoadDoesNotFailAnotherInstancesCheck() throws IOException {
        Locations defaults = Locations.of("Linux", Map.of(), home);
        TestConfigs.write(defaults.configFile(), "111:AAA", "alm");
        Files.writeString(defaults.configDir().resolve("broken.yaml"), "team: [");

        List<Checks.Finding> findings = new Checks(fakeBots, defaults).run(defaults.configFile(), Map.of(), f -> { });

        assertTrue(findings.stream().noneMatch(f -> f.area().equals("instances") && f.level() == Checks.Level.FAIL), findings.toString());
    }
```

(`fakeBots` = the file's existing fake `BotApi` factory.) Add a third assertion variant for ports: both configs with `miniApp.port: 7879` → a `WARN` containing `port 7879 is also instance`.

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q test -Dtest=ChecksTest`
Expected: FAIL

- [ ] **Step 3: Implement** — at the end of `run(...)`, before `return run.findings;`:

```java
        checkOtherInstances(run, configFile, config, processEnvironment);
```

```java
    /** Two instances on one computer must not share a bot, a port, or branch names in one clone (M). */
    private void checkOtherInstances(Run run, Path configFile, Config config, Map<String, String> env) {
        Path self = configFile.toAbsolutePath().normalize();
        String myBot = Instances.botId(config.secrets().telegramBotToken());
        for (Instances.Found other : Instances.discover(defaults, env)) {
            if (other.config() == null || other.configFile().toAbsolutePath().normalize().equals(self)) {
                continue;
            }
            String name = other.name() == null ? "default" : other.name();
            if (myBot != null && myBot.equals(Instances.botId(other.config().secrets().telegramBotToken()))) {
                run.add(Level.FAIL, "instances", "instances: the same bot as instance " + name
                        + "; Telegram gives a bot's messages to one of them only: create another bot with @BotFather");
            }
            ports(config).forEach(port -> {
                if (ports(other.config()).contains(port)) {
                    run.add(Level.WARN, "instances", "instances: port " + port + " is also instance " + name
                            + "'s; only one of them can listen on it");
                }
            });
            if (config.branch(0).equals(other.config().branch(0))) {
                Set<Path> mine = clones(config);
                clones(other.config()).stream().filter(mine::contains).forEach(clone -> run.add(Level.FAIL, "instances",
                        "instances: " + clone + " is also instance " + name + "'s, and both name their "
                                + config.branch(0).replace("/0", "/<task>") + " branches the same; set branchPrefix in one of them"));
            }
        }
    }

    private static Set<Integer> ports(Config config) {
        Set<Integer> ports = new HashSet<>();
        if (config.miniApp() != null) ports.add(config.miniApp().port());
        if (config.workers() != null) ports.add(config.workers().port());
        return ports;
    }

    /** Only clones given by path can be shared; one under a state directory belongs to that instance alone. */
    private static Set<Path> clones(Config config) {
        return config.projects().stream().filter(project -> project.path() != null)
                .map(project -> Path.of(project.path()).toAbsolutePath().normalize()).collect(Collectors.toSet());
    }
```

The failure text for the default prefix reads `dispatch/<task> branches`, as the test expects.

- [ ] **Step 4: Run to verify they pass**

Run: `./mvnw -q test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/cli/Checks.java src/main/java/dispatch/cli/CheckCommand.java src/test/java/dispatch/cli/ChecksTest.java
git commit -m "Check finds a bot, port or clone another instance already uses

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: `dispatch ui` takes a free port when 7878 is busy

**Files:**
- Modify: `src/main/java/dispatch/ui/UiCommand.java:40-53`
- Test: `src/test/java/dispatch/ui/UiCommandTest.java` (create if absent; else append)

**Interfaces:**
- Consumes: `Cli.Ui.portGiven()` (Task 4).

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void withoutPortItMovesOnFromABusyOne() throws IOException {
        try (ServerSocket busy = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            UiServer server = command().start(new Cli.Ui(configFile, busy.getLocalPort(), false, null, false), Map.of());
            try {
                assertNotEquals(busy.getLocalPort(), server.port());
            } finally {
                server.close();
            }
            assertThrows(CliException.class,
                    () -> command().start(new Cli.Ui(configFile, busy.getLocalPort(), false, null, true), Map.of()));
        }
    }
```

`command()` builds `UiCommand` with a `resourceRoot` that has pages — use the test resource root existing UI tests use (search `hasUi(` in `src/test`); if none exists, create `src/test/resources/ui-test/index.html` with `<!doctype html>` and use `"/ui-test"`.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=UiCommandTest`
Expected: FAIL — `port … is in use`.

- [ ] **Step 3: Implement** — wrap the `UiServer.start` call:

```java
            server = startOn(options, postRoutes, management);
```

```java
    /** An explicit --port is kept or refused; the default one gives way to the next free port (another instance's UI may hold it). */
    private UiServer startOn(Cli.Ui options, Map<String, BiFunction<UiServer.Caller, JsonNode, Object>> post, UiRoutes management)
            throws IOException {
        int last = options.portGiven() ? options.port() : Math.min(65535, options.port() + 20);
        for (int port = options.port(); ; port++) {
            try {
                return UiServer.start(port, resourceRoot, management.get(false), post);
            } catch (BindException e) {
                if (port >= last) {
                    throw e;
                }
            }
        }
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=UiCommandTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/ui/UiCommand.java src/test/java/dispatch/ui/UiCommandTest.java src/test/resources
git commit -m "dispatch ui moves on from a busy default port

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Docs, ADR, CI, and the laptop

**Files:**
- Create: `docs/adr/0028-several-instances-on-one-computer.md`
- Modify: `README.en.md` (section "Get started", after the service paragraph at `:88`), `README.md` (same place, Mongolian)
- Modify: `deploy/example.yaml` (commented `# branchPrefix: dispatch/backend` under `stateDir`)

- [ ] **Step 1: ADR 0028** — same shape as ADR 0021: title, what was decided (default instance unchanged; named instances beside it; `--instance`; services per instance; `branchPrefix` explicit and carried by the job), why (personal + team bot on one computer; config-derived prefixes would rename branches of existing server configs), consequences (older workers keep working with default-prefix teams, need updating for a named one; one worker per computer until W).

- [ ] **Step 2: README.en.md** — add after the service paragraph:

```markdown
### Another bot on the same computer

Run `dispatch init` again: it sees your first bot and offers another beside it, e.g. a team bot next to your personal one.
Each has its own name, config, state and background service; `dispatch list` shows them all. Address the second one with
`--instance NAME` on any command: `dispatch check --instance team`, `dispatch service status --instance team`,
`dispatch ui --instance team`. Its task branches are `dispatch/team/<task>`, so both bots can work in the same clone.
```

and the Mongolian equivalent in `README.md` (translate the paragraph; keep command lines as they are).

- [ ] **Step 3: Full verification**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit and push; watch CI**

```bash
git add docs README.md README.en.md deploy/example.yaml
git commit -m "Document several bots on one computer (ADR 0028)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
git push -u origin m-instances
gh run watch --exit-status
```

Expected: CI green on Linux, macOS, Windows. Fix red before calling it done.

- [ ] **Step 5: Laptop, after review and merge** (per the milestone workflow; only a build that contains `main`)

```bash
systemctl --user disable --now dispatch-team.service 2>/dev/null; rm -f ~/.config/systemd/user/dispatch-team.service
# stop the personal bot only when no run is RUNNING/QUEUED (see live-instances memory), swap the jar, start it
dispatch init            # answers: y, team, My team, the new team bot's token, ...
dispatch service install --instance team
dispatch list
```

Expected: `dispatch list` shows `default … personal running` and `team … team running`.
