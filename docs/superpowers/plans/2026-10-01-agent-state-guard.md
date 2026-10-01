# Agent State Guard (SG) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nothing a sandboxed agent writes to the places a later session of that agent loads (its plugins, skills, subagents, commands, instruction files, MCP config, hooks, trust lists) survives the run: Claude Code's `~/.claude` becomes copy-on-write with only its transcripts, sessions and credentials persisting and a throwaway `~/.claude.json`, and Codex's and Gemini CLI's loader paths become read-only, with any the run creates quarantined afterwards.

**Architecture:** Each agent declares an `AgentState` (its dir, whether it may be copy-on-write, what persists, what gets a throwaway copy, its loader paths). `SandboxPolicies.forRun` turns that into mounts: an overlay plus persisted binds and copies when the machine's bubblewrap supports overlays, otherwise a writable dir with present loaders read-only and absent ones watched. A new `RunGuard`, created by `Confinement.prepare` around each agent process, makes the copies before the run and, once the process exits, deletes them and moves watched paths that appeared into `<stateDir>/quarantine`. `Sandboxes.detect` finds out whether overlays work.

**Tech Stack:** Java 25, no framework (ADR 0002), JUnit 5, bubblewrap 0.10+ (`--overlay-src`, `--tmp-overlay`), Maven wrapper `./mvnw`.

**Spec:** `docs/superpowers/specs/2026-10-01-agent-state-guard-design.md`

## Global Constraints

- Claude Code's state: dir `.claude`, copy-on-write; persisted `.claude/projects`, `.claude/sessions`, `.claude/.credentials.json`; throwaway copy `.claude.json`; loaders (only without copy-on-write) `.claude/settings.json`, `.claude/settings.local.json`, `.claude/CLAUDE.md`, `.claude/agents`, `.claude/skills`, `.claude/plugins`, `.claude/commands`, `.claude/output-styles`, `.claude/hooks`.
- Codex's state: dir `.codex`, not copy-on-write; loaders `.codex/config.toml`, `.codex/AGENTS.md`, `.codex/hooks.json`, `.codex/prompts`, `.codex/skills`, `.codex/plugins`, `.codex/rules`, `.codex/memories`.
- Gemini CLI's state: dir `.gemini`, not copy-on-write; loaders `.gemini/settings.json`, `.gemini/GEMINI.md`, `.gemini/extensions`, `.gemini/commands`, `.gemini/trustedFolders.json`.
- The build tools' read-only config stays for every run: `.gradle/init.d`, `.gradle/gradle.properties`, `.m2/settings.xml`.
- Mount order: read-only root, `/dev`, `/proc`, `/tmp`; hidden tmpfs; workdir; git dir; writable binds; overlays (`--overlay-src <dir> --tmp-overlay <dir>`); persisted (`--bind-try`); copies (`--bind <copy> <original>`); read-only binds; the worktree admin binds.
- The copy of `~/.claude.json` lives at `<logBase>.claude.json`; the quarantine of a run is `<stateDir>/quarantine/<logBase parent name>-<logBase file name>/<path relative to home>`.
- Overlay detection trial: `bwrap --ro-bind / / --unshare-pid --proc /proc --overlay-src /etc --tmp-overlay /etc true`.
- Logs: `sandbox.selected name=… overlay=true|false`; `sandbox.quarantined path=… to=… run=…` (WARN); `sandbox.quarantine_failed path=… error=…` (ERROR); `sandbox.copy_left path=… error=…` (WARN).
- A `~/.claude.json` copy that cannot be made fails the run as `AGENT` (`AgentStartException`) before the agent starts.
- Unsandboxed runs, the worktree, caches, hidden dirs and the skills plugin behave exactly as before.
- Commits: one sentence describing the behaviour, ending with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq
  ```
  Branch `agent-state-guard`; do not push.

## Review Focus

1. **A token refresh during a run** must still reach the owner's credentials file: Claude Code renames over it, which fails with `EBUSY` over a bind, and then writes in place. Pinned in Task 5 (`aRenameOverAPersistedFileFailsAndAnInPlaceWriteLands`): the bind behaves as that fallback needs.
2. **The owner creating a loader path during a run** (Codex creates `memories/` on first use) must not lose it: it is moved, with its content, not deleted. Pinned in Task 3 (`aWatchedPathThatAppearedIsMovedWithItsContentAndLogged`).
3. **A cancelled or timed-out run** still deletes its copy and sweeps. Pinned in Task 3 (`aCancelledRunIsSweptToo`).
4. **A machine where `~/.claude` does not exist yet** must not get an overlay over a missing dir (bwrap would fail the run). Pinned in Task 1 (`aMissingAgentDirIsNeverAnOverlay`).
5. **Resuming a session in a later run** needs the transcript to persist through the overlay. Pinned in Task 5 (`copyOnWriteKeepsWhatPersistsAndDropsTheRest`) and live in Task 6.

---

### Task 1: Each agent's state, as the policy sees it

**Files:**
- Create: `src/main/java/dispatch/agent/sandbox/AgentState.java`
- Modify: `src/main/java/dispatch/agent/sandbox/SandboxPolicy.java`
- Modify: `src/main/java/dispatch/agent/sandbox/SandboxPolicies.java`
- Test: `src/test/java/dispatch/agent/sandbox/SandboxPoliciesTest.java`

**Interfaces:**
- Produces:
  - `record AgentState(String dir, boolean copyOnWrite, List<String> persisted, List<String> runCopies, List<String> loaders)` with `AgentState.NONE`.
  - `SandboxPolicy` gains LAST components `List<Path> overlays, List<Path> persisted, List<SandboxPolicy.FileCopy> copies, List<Path> watched`, a 6-argument constructor that leaves them empty, and `record SandboxPolicy.FileCopy(Path original, Path copy)`.
  - `SandboxPolicies.forRun(RunRequest, AgentState, Map<String, String>, boolean copyOnWrite)` (plus `forRun(RunRequest, AgentState)` and `forRun(RunRequest, AgentState, Map)` with copy-on-write off), `Path home()`, `Path quarantineFor(Path logBase)`.
  - The old `forRun(RunRequest, List<String>[, Map])` stays, unchanged, until Task 3 moves its callers.

- [ ] **Step 1: Write the failing tests** (in `SandboxPoliciesTest`; add `import java.util.Map;` if missing)

```java
    private static final AgentState CLAUDE = new AgentState(".claude", true,
            List.of(".claude/projects", ".claude/sessions", ".claude/.credentials.json"), List.of(".claude.json"),
            List.of(".claude/CLAUDE.md", ".claude/agents"));
    private static final AgentState CODEX = new AgentState(".codex", false, List.of(), List.of(),
            List.of(".codex/AGENTS.md", ".codex/hooks.json"));

    @Test
    void aCopyOnWriteAgentGetsItsDirAsAnOverlayWithItsPersistedPathsAndACopy() throws IOException {
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude.json"), "{}");
        Path workdir = Files.createDirectories(root.resolve("work"));

        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of(stateDir))
                .forRun(request(RunKind.PLAN, workdir, List.of()), CLAUDE, Map.of(), true);

        assertEquals(List.of(home.resolve(".claude")), policy.overlays());
        assertEquals(List.of(home.resolve(".claude/projects"), home.resolve(".claude/sessions"),
                home.resolve(".claude/.credentials.json")), policy.persisted());
        assertEquals(List.of(new SandboxPolicy.FileCopy(home.resolve(".claude.json"), Path.of(workdir.resolve("run") + ".claude.json"))),
                policy.copies());
        assertFalse(policy.writable().contains(home.resolve(".claude")), policy.writable().toString());
        assertFalse(policy.readOnly().contains(home.resolve(".claude/agents")), "the overlay covers the loaders");
        assertEquals(List.of(), policy.watched());
    }

    @Test
    void withoutCopyOnWriteTheAgentsDirIsWritableAndItsLoadersReadOnlyOrWatched() throws IOException {
        Files.createDirectories(home.resolve(".claude/agents"));
        Path workdir = Files.createDirectories(root.resolve("work"));

        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of(stateDir))
                .forRun(request(RunKind.PLAN, workdir, List.of()), CLAUDE, Map.of(), false);

        assertEquals(List.of(), policy.overlays());
        assertTrue(policy.writable().contains(home.resolve(".claude")), policy.writable().toString());
        assertTrue(policy.readOnly().contains(home.resolve(".claude/agents")), policy.readOnly().toString());
        assertEquals(List.of(home.resolve(".claude/CLAUDE.md")), policy.watched());
    }

    @Test
    void aCodexRunsLoadersAreReadOnlyWhenPresentAndWatchedWhenAbsentEvenWithOverlays() throws IOException {
        Files.createDirectories(home.resolve(".codex"));
        Files.writeString(home.resolve(".codex/AGENTS.md"), "be nice");
        Path workdir = Files.createDirectories(root.resolve("work"));

        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of(stateDir))
                .forRun(request(RunKind.EXECUTE, workdir, List.of()), CODEX, Map.of(), true);

        assertEquals(List.of(), policy.overlays(), "Codex is never copy-on-write");
        assertTrue(policy.writable().contains(home.resolve(".codex")));
        assertTrue(policy.readOnly().contains(home.resolve(".codex/AGENTS.md")));
        assertEquals(List.of(home.resolve(".codex/hooks.json")), policy.watched());
    }

    @Test
    void aMissingAgentDirIsNeverAnOverlay() throws IOException {
        Path workdir = Files.createDirectories(root.resolve("work"));

        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of(stateDir))
                .forRun(request(RunKind.PLAN, workdir, List.of()), CLAUDE, Map.of(), true);

        assertEquals(List.of(), policy.overlays(), "an overlay needs an existing lower dir; bwrap would fail the run");
        assertEquals(List.of(), policy.copies(), "no ~/.claude.json, no copy");
    }

    @Test
    void theBuildToolsConfigStaysReadOnlyForEveryRun() throws IOException {
        Path workdir = Files.createDirectories(root.resolve("work"));

        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of(stateDir))
                .forRun(request(RunKind.EXECUTE, workdir, List.of()), AgentState.NONE, Map.of(), true);

        assertTrue(policy.readOnly().containsAll(List.of(home.resolve(".gradle/init.d"), home.resolve(".gradle/gradle.properties"),
                home.resolve(".m2/settings.xml"))), policy.readOnly().toString());
        assertFalse(policy.writable().stream().anyMatch(path -> path.startsWith(home.resolve(".claude"))));
    }

    @Test
    void aRunsQuarantineIsNamedAfterItsLogBase() {
        SandboxPolicies policies = new SandboxPolicies(home, stateDir, List.of(stateDir));

        assertEquals(stateDir.resolve("quarantine/7-2.fix-1"), policies.quarantineFor(stateDir.resolve("runs/7/2.fix-1")));
        assertEquals(home, policies.home());
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest=SandboxPoliciesTest`
Expected: COMPILATION ERROR (`AgentState` does not exist).

- [ ] **Step 3: Write `AgentState`**

```java
package dispatch.agent.sandbox;

import java.util.List;

/**
 * What an agent keeps in the owner's home, relative to it (spec: agent state guard).
 *
 * @param dir         its own directory, e.g. ".claude"; null for a command with none
 * @param copyOnWrite whether the sandbox mounts {@code dir} copy-on-write where it can
 * @param persisted   under a copy-on-write {@code dir}: bound back read-write, so they survive the run
 * @param runCopies   files given a throwaway copy per run, e.g. ".claude.json"
 * @param loaders     what a later session of the agent loads as code or instructions: read-only when present, moved
 *                    to the quarantine after the run when it was absent before; unused while {@code dir} is copy-on-write
 */
public record AgentState(String dir, boolean copyOnWrite, List<String> persisted, List<String> runCopies, List<String> loaders) {

    /** A command with no agent state of its own, such as the verify loop's test command. */
    public static final AgentState NONE = new AgentState(null, false, List.of(), List.of(), List.of());

    public AgentState {
        persisted = List.copyOf(persisted);
        runCopies = List.copyOf(runCopies);
        loaders = List.copyOf(loaders);
    }
}
```

- [ ] **Step 4: Extend `SandboxPolicy`**

Add to the Javadoc:

```
 * @param overlays     agent dirs mounted copy-on-write: the agent sees them, and what it writes ends with the sandbox
 * @param persisted    under an overlay, mounted back read-write so they survive the run
 * @param copies       a throwaway copy of each file, made before the run and mounted over the original
 * @param watched      loader paths absent when the run started; the run guard quarantines any that exist when it ends
```

and change the record to:

```java
public record SandboxPolicy(Path workdir, Path gitCommonDir, Path worktreeAdmin, List<Path> readOnly, List<Path> writable,
                            List<Path> hidden, List<Path> overlays, List<Path> persisted, List<FileCopy> copies,
                            List<Path> watched) {

    /** @param copy where the throwaway copy of {@code original} is made, in the hidden state dir */
    public record FileCopy(Path original, Path copy) {
    }

    public SandboxPolicy {
        readOnly = List.copyOf(readOnly);
        writable = List.copyOf(writable);
        hidden = List.copyOf(hidden);
        overlays = List.copyOf(overlays);
        persisted = List.copyOf(persisted);
        copies = List.copyOf(copies);
        watched = List.copyOf(watched);
    }

    /** A policy with nothing copy-on-write, copied or watched. */
    public SandboxPolicy(Path workdir, Path gitCommonDir, Path worktreeAdmin, List<Path> readOnly, List<Path> writable,
                         List<Path> hidden) {
        this(workdir, gitCommonDir, worktreeAdmin, readOnly, writable, hidden, List.of(), List.of(), List.of(), List.of());
    }
}
```

- [ ] **Step 5: Add the new `forRun` to `SandboxPolicies`**

Add the constant beside `CODE_IN_WRITABLE`:

```java
    /** Run as code by a later build outside any sandbox: init scripts, JVM args. The agents' own are their loaders. */
    private static final List<String> BUILD_CODE = List.of(".gradle/init.d", ".gradle/gradle.properties", ".m2/settings.xml");
```

Add these methods after the existing `forRun` methods. Keep the old ones unchanged: Task 3 removes them.

```java
    public Path home() {
        return home;
    }

    /** Where the run guard moves what a run planted: per run, named after its log base, e.g. {@code quarantine/7-2.fix-1}. */
    public Path quarantineFor(Path logBase) {
        Path base = logBase.toAbsolutePath().normalize();
        String parent = base.getParent() == null ? "" : base.getParent().getFileName() + "-";
        return stateDir.resolve("quarantine").resolve(parent + base.getFileName());
    }

    /** As {@link #forRun(RunRequest, AgentState, Map, boolean)} without copy-on-write, for an environment without XDG_RUNTIME_DIR. */
    public SandboxPolicy forRun(RunRequest request, AgentState state) {
        return forRun(request, state, Map.of(), false);
    }

    /** As {@link #forRun(RunRequest, AgentState, Map, boolean)} without copy-on-write. */
    public SandboxPolicy forRun(RunRequest request, AgentState state, Map<String, String> environment) {
        return forRun(request, state, environment, false);
    }

    /**
     * @param state       what the agent keeps in the owner's home (spec: agent state guard)
     * @param environment the agent's environment; its XDG_RUNTIME_DIR is hidden
     * @param copyOnWrite whether this machine's sandbox can mount a directory copy-on-write
     */
    public SandboxPolicy forRun(RunRequest request, AgentState state, Map<String, String> environment, boolean copyOnWrite) {
        Path workdir = request.workdir().toAbsolutePath().normalize();
        List<Path> writable = new ArrayList<>();
        List<Path> overlays = new ArrayList<>();
        List<Path> persisted = new ArrayList<>();
        Path agentDir = state.dir() == null ? null : home.resolve(state.dir());
        // An overlay needs an existing lower dir; without one there is nothing of the agent's to protect yet.
        boolean overlay = copyOnWrite && state.copyOnWrite() && agentDir != null
                && Files.isDirectory(agentDir, LinkOption.NOFOLLOW_LINKS);
        if (overlay) {
            overlays.add(agentDir);
            state.persisted().forEach(path -> persisted.add(home.resolve(path)));
        } else if (agentDir != null) {
            writable.add(agentDir);
        }
        CACHES.forEach(cache -> writable.add(home.resolve(cache)));
        List<Path> readOnly = new ArrayList<>(request.readOnlyDirs());
        // The skills plugin lives in the hidden state dir: readable, never writable, or one agent could rewrite every later run's skills.
        readOnly.addAll(request.pluginDirs());
        // Codex reads its --output-schema from beside the run's log, under the hidden state dir. Only that file: the log
        // dir holds other runs' logs, and for the assistant every member's conversations. A regular file outside the
        // workdir only, so an agent-planted symlink cannot mount anything it names.
        Path schema = Path.of(request.logBase().toAbsolutePath().normalize() + ".schema.json");
        if (!schema.startsWith(workdir) && Files.isRegularFile(schema, LinkOption.NOFOLLOW_LINKS)) {
            readOnly.add(schema);
        }
        BUILD_CODE.forEach(entry -> readOnly.add(home.resolve(entry)));
        List<Path> watched = new ArrayList<>();
        if (!overlay) {
            // bwrap skips a read-only bind of a missing path, so an absent loader is watched instead (the run guard).
            for (String loader : state.loaders()) {
                Path path = home.resolve(loader);
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    readOnly.add(path);
                } else {
                    watched.add(path);
                }
            }
        }
        List<SandboxPolicy.FileCopy> copies = new ArrayList<>();
        for (String file : state.runCopies()) {
            Path original = home.resolve(file);
            if (Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) {
                String name = original.getFileName().toString().replaceFirst("^\\.", "");
                copies.add(new SandboxPolicy.FileCopy(original, Path.of(request.logBase().toAbsolutePath().normalize() + "." + name)));
            }
        }
        if (request.kind() == RunKind.ASSISTANT) {
            // dispatch ask reads the state file, and SQLite writes -shm even to read a WAL database (AssistantHome).
            Path database = stateDir.resolve("dispatch.db");
            writable.addAll(List.of(database, stateDir.resolve("dispatch.db-wal"), stateDir.resolve("dispatch.db-shm")));
            readOnly.add(stateDir.resolve("assistant-bin"));
        }
        // bwrap cannot create a mount point on the read-only root: a directory that does not exist has nothing to hide.
        List<Path> hidden = Stream.of(SECRETS.stream().map(home::resolve), dispatchPrivate.stream(), runtimeDir(environment))
                .flatMap(paths -> paths)
                .filter(Files::isDirectory)
                .toList();
        // Only a task's worktree has a git dir. A split's or the assistant's workdir is agent-writable and no worktree:
        // a .git an earlier run planted there would otherwise mount any configured clone read-write.
        GitLink git = IN_WORKTREE.contains(request.kind()) ? gitLink(workdir) : null;
        if (git == null) {
            return new SandboxPolicy(workdir, null, null, readOnly, writable, hidden, overlays, persisted, copies, watched);
        }
        // Mounted after the workdir and the git dir: the agent cannot point the next run's mounts somewhere else, nor
        // plant config (core.fsmonitor, filters, remotes) or hooks that Dispatch's own git runs outside the sandbox.
        readOnly.add(workdir.resolve(".git"));
        GIT_CONTROL.forEach(name -> readOnly.add(git.commonDir().resolve(name)));
        // Other worktrees' admin dirs: a rewritten commondir there would redirect Dispatch's git in that worktree.
        readOnly.add(git.commonDir().resolve("worktrees"));
        return new SandboxPolicy(workdir, git.commonDir(), git.worktreeAdmin(), readOnly, writable, hidden, overlays, persisted,
                copies, watched);
    }
```

- [ ] **Step 6: Run the tests**

Run: `./mvnw -q test -Dtest='SandboxPoliciesTest,BubblewrapTest,ConfinementTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dispatch/agent/sandbox src/test/java/dispatch/agent/sandbox/SandboxPoliciesTest.java
git commit -m "A sandbox policy can mount an agent's state copy-on-write with what must persist bound back, give a file a throwaway copy, and watch loader paths that do not exist yet

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq"
```

---

### Task 2: Bubblewrap mounts copy-on-write state

**Files:**
- Modify: `src/main/java/dispatch/agent/sandbox/Sandbox.java`
- Modify: `src/main/java/dispatch/agent/sandbox/Bubblewrap.java`
- Test: `src/test/java/dispatch/agent/sandbox/BubblewrapTest.java`

**Interfaces:**
- Consumes: `SandboxPolicy.overlays()`, `persisted()`, `copies()` (Task 1).
- Produces: `default boolean copyOnWrite()` on `Sandbox` (false), `Bubblewrap(String command, boolean overlay)` (the 1-argument constructor keeps `overlay` false), `Bubblewrap.copyOnWrite()`.

- [ ] **Step 1: Write the failing tests** (in `BubblewrapTest`)

```java
    @Test
    void copyOnWriteStateIsMountedAfterTheWritableDirsWithWhatPersistsAndTheCopiesOverIt() {
        SandboxPolicy policy = new SandboxPolicy(Path.of("/state/worktrees/7"), null, null,
                List.of(Path.of("/state/plugins/dispatch")), List.of(Path.of("/home/ann/.m2")), List.of(Path.of("/state")),
                List.of(Path.of("/home/ann/.claude")),
                List.of(Path.of("/home/ann/.claude/projects"), Path.of("/home/ann/.claude/.credentials.json")),
                List.of(new SandboxPolicy.FileCopy(Path.of("/home/ann/.claude.json"), Path.of("/state/runs/7/2.claude.json"))),
                List.of(Path.of("/home/ann/.codex/AGENTS.md")));

        List<String> wrapped = new Bubblewrap("bwrap", true).wrap(List.of("claude"), policy);

        int writable = wrapped.indexOf("/home/ann/.m2");
        int overlay = wrapped.indexOf("--overlay-src");
        int persisted = wrapped.indexOf("/home/ann/.claude/projects");
        int copy = wrapped.indexOf("/state/runs/7/2.claude.json");
        int readOnly = wrapped.indexOf("/state/plugins/dispatch");
        assertTrue(writable < overlay && overlay < persisted && persisted < copy && copy < readOnly, wrapped.toString());
        assertEquals(List.of("--overlay-src", "/home/ann/.claude", "--tmp-overlay", "/home/ann/.claude"),
                wrapped.subList(overlay, overlay + 4));
        assertEquals(List.of("--bind-try", "/home/ann/.claude/projects", "/home/ann/.claude/projects"),
                wrapped.subList(persisted - 1, persisted + 2));
        assertEquals(List.of("--bind", "/state/runs/7/2.claude.json", "/home/ann/.claude.json"),
                wrapped.subList(copy - 1, copy + 2));
        assertFalse(wrapped.contains("/home/ann/.codex/AGENTS.md"), "watched paths are the guard's, not a mount");
    }

    @Test
    void onlyABubblewrapThatFoundOverlaysIsCopyOnWrite() {
        assertTrue(new Bubblewrap("bwrap", true).copyOnWrite());
        assertFalse(new Bubblewrap("bwrap").copyOnWrite());
        assertFalse(new NoSandbox("bubblewrap (bwrap) is not installed").copyOnWrite());
    }
```

(Add `import static org.junit.jupiter.api.Assertions.assertFalse;` and `assertTrue` if missing.)

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest=BubblewrapTest`
Expected: COMPILATION ERROR (no 2-argument `Bubblewrap`, no `copyOnWrite()`).

- [ ] **Step 3: Implement**

In `Sandbox`:

```java
    /** Whether this sandbox can mount a directory copy-on-write (spec: agent state guard); found once, at startup. */
    default boolean copyOnWrite() {
        return false;
    }
```

In `Bubblewrap`: add the field `private final boolean overlay;`, and make the constructors:

```java
    /** Without overlays, as on bubblewrap older than 0.10. */
    public Bubblewrap(String command) {
        this(command, false);
    }

    /** @param overlay whether this machine's bwrap can mount an overlay (Sandboxes.detect's trial) */
    public Bubblewrap(String command, boolean overlay) {
        this.command = command;
        this.overlay = overlay;
    }

    @Override
    public boolean copyOnWrite() {
        return overlay;
    }
```

In `wrap`, between the writable binds and the read-only binds:

```java
        policy.writable().forEach(path -> bind(args, "--bind-try", path));
        // Copy-on-write (spec: agent state guard): the agent sees its whole state dir, and what it writes there lands in
        // a tmpfs that ends with the sandbox; then what must persist, and the throwaway copies, are mounted over it.
        policy.overlays().forEach(dir -> args.addAll(List.of("--overlay-src", dir.toString(), "--tmp-overlay", dir.toString())));
        policy.persisted().forEach(path -> bind(args, "--bind-try", path));
        policy.copies().forEach(copy -> args.addAll(List.of("--bind", copy.copy().toString(), copy.original().toString())));
        policy.readOnly().forEach(path -> bind(args, "--ro-bind-try", path));
```

- [ ] **Step 4: Run the tests**

Run: `./mvnw -q test -Dtest='BubblewrapTest,ConfinementTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/agent/sandbox/Sandbox.java src/main/java/dispatch/agent/sandbox/Bubblewrap.java \
        src/test/java/dispatch/agent/sandbox/BubblewrapTest.java
git commit -m "Bubblewrap mounts an agent's state dir copy-on-write, then binds back what persists and the throwaway copies

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq"
```

---

### Task 3: The run guard, and every agent declares its state

**Files:**
- Create: `src/main/java/dispatch/agent/sandbox/RunGuard.java`
- Modify: `src/main/java/dispatch/agent/sandbox/Confinement.java`
- Modify: `src/main/java/dispatch/agent/sandbox/SandboxPolicies.java` (remove the old `forRun(…, List<String>…)` methods and `CODE_IN_WRITABLE`)
- Modify: `src/main/java/dispatch/agent/ProcessRun.java` (`start`, the constructor, `await`)
- Modify: `src/main/java/dispatch/agent/claude/ClaudeCodeAgent.java:34,56-57`, `src/main/java/dispatch/agent/codex/CodexAgent.java:46,97-98`, `src/main/java/dispatch/agent/gemini/GeminiAgent.java:32,73-74`
- Modify: `src/main/java/dispatch/core/TestCommand.java:61`
- Test: `src/test/java/dispatch/agent/sandbox/RunGuardTest.java` (new), `ConfinementTest.java`, `SandboxPoliciesTest.java`, `BubblewrapSandboxTest.java`, `src/test/java/dispatch/agent/claude/ClaudeCodeAgentTest.java`

**Interfaces:**
- Consumes: Task 1's `AgentState`, `SandboxPolicy` fields, `forRun(…, AgentState, Map, boolean)`, `home()` and `quarantineFor(Path)`; Task 2's `Sandbox.copyOnWrite()`.
- Produces:
  - `RunGuard.NONE`, `RunGuard.start(SandboxPolicy, Path home, Path quarantine, Path logBase): RunGuard` (package-private) and `RunGuard.end()` (idempotent).
  - `Confinement.Confined(List<String> commandLine, RunGuard guard)`, `Confinement.prepare(List<String>, RunRequest, AgentState, Map<String, String>): Confined`, and `Confinement.wrap(List<String>, RunRequest, Map<String, String>): List<String>` for a command with no agent state.
  - `ProcessRun.start(…, Confinement confinement, AgentState state)`.
  - `public static final AgentState STATE` on `ClaudeCodeAgent`, `CodexAgent` and `GeminiAgent`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/dispatch/agent/sandbox/RunGuardTest.java`:

```java
package dispatch.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.AgentStartException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunGuardTest {

    @TempDir
    Path root;

    @Test
    void theCopyIsMadeBeforeTheRunAndDeletedAfterIt() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path original = Files.writeString(home.resolve(".claude.json"), "{\"mcpServers\":{}}");
        Path copy = root.resolve("state/runs/7/2.claude.json");

        RunGuard guard = RunGuard.start(policy(List.of(new SandboxPolicy.FileCopy(original, copy)), List.of()), home,
                root.resolve("state/quarantine/7-2"), root.resolve("state/runs/7/2"));
        assertEquals("{\"mcpServers\":{}}", Files.readString(copy));

        Files.writeString(copy, "{\"mcpServers\":{\"planted\":{}}}");
        guard.end();

        assertFalse(Files.exists(copy));
        assertEquals("{\"mcpServers\":{}}", Files.readString(original), "the run's writes never reach the original");
    }

    @Test
    void aWatchedPathThatAppearedIsMovedWithItsContentAndLogged() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path watched = home.resolve(".codex/memories");
        Path quarantine = root.resolve("state/quarantine/7-2");
        RunGuard guard = RunGuard.start(policy(List.of(), List.of(watched)), home, quarantine, root.resolve("state/runs/7/2"));
        Files.createDirectories(watched);
        Files.writeString(watched.resolve("note.md"), "remember this");

        String logged = capturingLog(guard::end);

        assertFalse(Files.exists(watched));
        assertEquals("remember this", Files.readString(quarantine.resolve(".codex/memories/note.md")));
        assertTrue(logged.contains("level=WARN event=sandbox.quarantined path=" + watched), logged);
    }

    @Test
    void aWatchedPathThatNeverAppearedIsLeftAloneAndEndingTwiceDoesNothingMore() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path quarantine = root.resolve("state/quarantine/7-2");
        RunGuard guard = RunGuard.start(policy(List.of(), List.of(home.resolve(".codex/hooks.json"))), home, quarantine,
                root.resolve("state/runs/7/2"));

        guard.end();
        Files.createDirectories(home.resolve(".codex"));
        Files.writeString(home.resolve(".codex/hooks.json"), "{}");
        guard.end();

        assertTrue(Files.exists(home.resolve(".codex/hooks.json")), "the owner's file after the run is theirs");
        assertFalse(Files.exists(quarantine));
    }

    @Test
    void aCopyThatCannotBeMadeFailsTheRunBeforeItStarts() {
        Path home = root.resolve("home");
        Path missing = home.resolve(".claude.json");

        AgentStartException error = assertThrows(AgentStartException.class, () -> RunGuard.start(
                policy(List.of(new SandboxPolicy.FileCopy(missing, root.resolve("state/runs/7/2.claude.json"))), List.of()),
                home, root.resolve("state/quarantine/7-2"), root.resolve("state/runs/7/2")));

        assertTrue(error.getMessage().contains(missing.toString()), error.getMessage());
    }

    private Path workdir() {
        return root.resolve("work");
    }

    private SandboxPolicy policy(List<SandboxPolicy.FileCopy> copies, List<Path> watched) {
        return new SandboxPolicy(workdir(), null, null, List.of(), List.of(), List.of(), List.of(), List.of(), copies, watched);
    }

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
}
```

In `ClaudeCodeAgentTest`, two tests that go through `ProcessRun` (add `import dispatch.agent.sandbox.AgentState;` if needed):

```java
    /** A sandbox that plants a loader path, then runs the agent, as a prompt-injected agent could. */
    private Confinement planting(Path home, Path planted) {
        Sandbox planter = new Sandbox() {
            @Override
            public String name() {
                return "planting";
            }

            @Override
            public String unavailableReason() {
                return null;
            }

            @Override
            public List<String> wrap(List<String> commandLine, SandboxPolicy policy) {
                List<String> wrapped = new ArrayList<>(List.of("sh", "-c", "mkdir -p \"$0\" && exec \"$@\"", planted.toString()));
                wrapped.addAll(commandLine);
                return wrapped;
            }
        };
        return new Confinement(planter, new SandboxPolicies(home, dir.resolve("state"), List.of()));
    }

    @Test
    void aLoaderPathTheRunCreatesIsQuarantinedWhenItEnds() throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path planted = home.resolve(".claude/agents");
        ClaudeCodeAgent confined = new ClaudeCodeAgent(FakeClaude.install(Files.createDirectories(dir.resolve("planting"))).toString(),
                FakeClaude.environment(), Duration.ofSeconds(2), planting(home, planted));

        confined.start(new RunRequest(RunKind.PLAN, workdir, "Plan it", SESSION, false, List.of(), null, null, null,
                dir.resolve("state/runs/1/1"))).await();

        assertFalse(Files.exists(planted), "moved out of the owner's home");
        assertTrue(Files.isDirectory(dir.resolve("state/quarantine/1-1/.claude/agents")));
    }

    @Test
    void aCancelledRunIsSweptToo() throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path planted = home.resolve(".claude/agents");
        ClaudeCodeAgent confined = new ClaudeCodeAgent(FakeClaude.install(Files.createDirectories(dir.resolve("planting"))).toString(),
                FakeClaude.environment(), Duration.ofSeconds(2), planting(home, planted));
        RunHandle handle = confined.start(new RunRequest(RunKind.PLAN, workdir, "SCENARIO:sleep", SESSION, false, List.of(), null,
                null, null, dir.resolve("state/runs/1/2")));
        awaitChildPid();

        handle.cancel();
        CompletableFuture.supplyAsync(() -> awaitQuietly(handle)).get(10, TimeUnit.SECONDS);

        assertFalse(Files.exists(planted));
        assertTrue(Files.isDirectory(dir.resolve("state/quarantine/1-2/.claude/agents")));
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest='RunGuardTest,ClaudeCodeAgentTest'`
Expected: COMPILATION ERROR (`RunGuard` does not exist).

- [ ] **Step 3: Write `RunGuard`**

```java
package dispatch.agent.sandbox;

import dispatch.Log;
import dispatch.agent.AgentStartException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What a sandboxed run leaves in the owner's home (spec: agent state guard). Before the run, the throwaway copies the
 * sandbox mounts are made; once its process has exited, however it ended, they are deleted, and every watched loader
 * path that now exists is moved to the run's quarantine. Moved, not deleted: the owner may have created it meanwhile.
 */
public final class RunGuard {

    /** An unsandboxed run, or a command with no agent state: nothing to make, nothing to check. */
    public static final RunGuard NONE = new RunGuard(List.of(), List.of(), null, null, null);

    private final List<SandboxPolicy.FileCopy> copies;
    private final List<Path> watched;
    private final Path home;
    private final Path quarantine;
    private final Path logBase;
    private final AtomicBoolean ended = new AtomicBoolean();

    private RunGuard(List<SandboxPolicy.FileCopy> copies, List<Path> watched, Path home, Path quarantine, Path logBase) {
        this.copies = List.copyOf(copies);
        this.watched = List.copyOf(watched);
        this.home = home;
        this.quarantine = quarantine;
        this.logBase = logBase;
    }

    /** Makes the copies; a copy that cannot be made fails the run before its agent starts. */
    static RunGuard start(SandboxPolicy policy, Path home, Path quarantine, Path logBase) {
        for (SandboxPolicy.FileCopy copy : policy.copies()) {
            try {
                Files.createDirectories(copy.copy().getParent());
                Files.copy(copy.original(), copy.copy(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new AgentStartException("cannot copy " + copy.original() + " for the run: " + e, e);
            }
        }
        return new RunGuard(policy.copies(), policy.watched(), home, quarantine, logBase);
    }

    /** Once, after the process exited; later calls do nothing. */
    public void end() {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        for (SandboxPolicy.FileCopy copy : copies) {
            try {
                Files.deleteIfExists(copy.copy());
            } catch (IOException e) {
                Log.warn("sandbox.copy_left", "path", copy.copy(), "error", e.toString());
            }
        }
        for (Path path : watched) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path target = quarantine.resolve(home.relativize(path).toString());
            try {
                Files.createDirectories(target.getParent());
                Files.move(path, target);
                Log.warn("sandbox.quarantined", "path", path, "to", target, "run", logBase);
            } catch (IOException e) {
                Log.error("sandbox.quarantine_failed", e, "path", path, "error", e.toString());
            }
        }
    }
}
```

- [ ] **Step 4: `Confinement` prepares a run and its guard**

Replace `wrap(List<String>, RunRequest, List<String>, Map)` with:

```java
    /** The command line inside the sandbox, and what to make and check around the process (spec: agent state guard). */
    public record Confined(List<String> commandLine, RunGuard guard) {
    }

    /** @param environment the agent process's environment, whose XDG_RUNTIME_DIR the sandbox hides */
    public Confined prepare(List<String> commandLine, RunRequest request, AgentState state, java.util.Map<String, String> environment) {
        if (sandbox.unavailableReason() != null) {
            return new Confined(List.copyOf(commandLine), RunGuard.NONE);
        }
        SandboxPolicy policy = policies.forRun(request, state, environment, sandbox.copyOnWrite());
        RunGuard guard = RunGuard.start(policy, policies.home(), policies.quarantineFor(request.logBase()), request.logBase());
        return new Confined(sandbox.wrap(commandLine, policy), guard);
    }

    /** For a command with no agent state of its own, such as the verify loop's tests: nothing to guard. */
    public List<String> wrap(List<String> commandLine, RunRequest request, java.util.Map<String, String> environment) {
        return prepare(commandLine, request, AgentState.NONE, environment).commandLine();
    }
```

- [ ] **Step 5: `ProcessRun` runs the guard around the process**

Change `start`'s last parameter to `AgentState state` (Javadoc: `@param state what the agent keeps in the owner's home; the sandbox guards it`) and its body around the command:

```java
        Confinement.Confined confined = confinement.prepare(commandLine, request, state, builder.environment());
        builder.command(confined.commandLine());
        Path stdoutLog = Path.of(request.logBase() + ".jsonl");
        Path stderrLog = Path.of(request.logBase() + ".stderr");
        Process process;
        try {
            Files.createDirectories(stdoutLog.getParent());
            builder.redirectError(stderrLog.toFile());
            process = builder.start();
        } catch (IOException e) {
            confined.guard().end();
            throw new AgentStartException("cannot start " + confined.commandLine().getFirst() + ": " + e.getMessage(), e);
        }
        // Whoever ends the process (the agent, a cancel, the watchdog, a crash of Dispatch's run thread), the guard runs.
        process.onExit().thenRun(confined.guard()::end);
```

pass `confined.guard()` to the constructor (`new ProcessRun(process, parser, stdoutLog, stderrLog, cancelGrace, confinement.use(), confined.guard())`), keep it in a field `private final RunGuard guard;`, and in `await` end it before the result is read, so a caller sees the home already swept:

```java
    @Override
    public AgentResult await() throws InterruptedException {
        int exitCode = process.waitFor();
        stdoutReader.join();
        guard.end();
        return parser.result(exitCode, stderrTail()).withSandbox(sandbox);
    }
```

(Add `import dispatch.agent.sandbox.AgentState;` and `import dispatch.agent.sandbox.RunGuard;`.)

- [ ] **Step 6: Each agent declares its state**

`ClaudeCodeAgent` (replace `STATE_IN_HOME`):

```java
    /** What Claude Code keeps in the owner's home (spec: agent state guard); what persists was probed on 2.1.286. */
    public static final AgentState STATE = new AgentState(".claude", true,
            List.of(".claude/projects", ".claude/sessions", ".claude/.credentials.json"),
            List.of(".claude.json"),
            List.of(".claude/settings.json", ".claude/settings.local.json", ".claude/CLAUDE.md", ".claude/agents",
                    ".claude/skills", ".claude/plugins", ".claude/commands", ".claude/output-styles", ".claude/hooks"));
```

`CodexAgent`:

```java
    /** What Codex keeps in the owner's home: never copy-on-write, since its databases live in the dir's root (spec: agent state guard). */
    public static final AgentState STATE = new AgentState(".codex", false, List.of(), List.of(),
            List.of(".codex/config.toml", ".codex/AGENTS.md", ".codex/hooks.json", ".codex/prompts", ".codex/skills",
                    ".codex/plugins", ".codex/rules", ".codex/memories"));
```

`GeminiAgent`:

```java
    /** What Gemini CLI keeps in the owner's home: never copy-on-write, since it renames files into place (spec: agent state guard). */
    public static final AgentState STATE = new AgentState(".gemini", false, List.of(), List.of(),
            List.of(".gemini/settings.json", ".gemini/GEMINI.md", ".gemini/extensions", ".gemini/commands",
                    ".gemini/trustedFolders.json"));
```

and pass `STATE` as `ProcessRun.start`'s last argument in each (import `dispatch.agent.sandbox.AgentState`).

- [ ] **Step 7: The test command and the old API**

`TestCommand`: `new ProcessBuilder(confinement.wrap(shell, asRun, runEnvironment))`.

`SandboxPolicies`: delete `forRun(RunRequest, List<String>)`, `forRun(RunRequest, List<String>, Map)` and `CODE_IN_WRITABLE`.

Update the tests that called them:
- `ConfinementTest`: `confinement.wrap(List.of("claude", "-p"), request(root), List.of(".claude"), Map.of())` → `confinement.prepare(List.of("claude", "-p"), request(root), AgentState.NONE, java.util.Map.of()).commandLine()`, in both tests. In `theAgentEnvironmentsRuntimeDirIsHidden`: `confinement.wrap(List.of("claude"), request(root), java.util.Map.of("XDG_RUNTIME_DIR", runtime.toString()))`.
- `SandboxPoliciesTest`: each `forRun(request(...), List.of(), …)` → `forRun(request(...), AgentState.NONE, …)`; each `List.of(".claude")`, `List.of(".codex")` or `List.of(".claude", ".claude.json")` → `new AgentState(".claude", false, List.of(), List.of(), List.of())` (or `".codex"`).
  - In `executeRunWritesItsWorktreeGitDirAgentStateAndCachesAndSeesNoSecrets`, the expected lists become:
    - read-only: `attachments, home/.gradle/init.d, home/.gradle/gradle.properties, home/.m2/settings.xml, worktree/.git, common/config, common/hooks, common/info, common/config.worktree, common/worktrees`;
    - writable: `home/.claude, home/.m2, home/.gradle, home/.npm, home/.cache`.
  - The agents' config files are their loaders now, read-only only when present.
- `BubblewrapSandboxTest`: each `forRun(…, List.of())` → `forRun(…, AgentState.NONE)` (keeping the `Map` argument where there is one); `List.of(".claude")` → `new AgentState(".claude", false, List.of(), List.of(), List.of())`.

- [ ] **Step 8: Run the tests**

Run: `./mvnw -q test -Dtest='RunGuardTest,ConfinementTest,SandboxPoliciesTest,BubblewrapSandboxTest,BubblewrapTest,ClaudeCodeAgentTest,CodexAgentTest,GeminiAgentTest,TestCommandTest,JobRunnerTest'`
Expected: PASS (`BubblewrapSandboxTest` skips where bwrap does not work).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/dispatch/agent src/main/java/dispatch/core/TestCommand.java src/test/java/dispatch/agent
git commit -m "Each agent declares the state it keeps in the owner's home, and a run guard makes a run's throwaway copies and quarantines the loader paths it planted once its process exits

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq"
```

---

### Task 4: Startup finds out whether overlays work

**Files:**
- Modify: `src/main/java/dispatch/agent/sandbox/Sandboxes.java:33-38`
- Modify: `src/main/java/dispatch/App.java:146-147`, `src/main/java/dispatch/worker/WorkerCommand.java` (the `sandbox.selected` line)
- Modify: `src/main/java/dispatch/cli/Checks.java:116-120`
- Modify: `src/main/resources/texts_en.properties`, `src/main/resources/texts_mn.properties` (after `check.sandboxNone`)
- Test: `src/test/java/dispatch/agent/sandbox/SandboxesTest.java`, `src/test/java/dispatch/cli/ChecksTest.java`

**Interfaces:**
- Consumes: `Bubblewrap(String, boolean)`, `Sandbox.copyOnWrite()` (Task 2).
- Produces: text key `check.sandboxNoOverlay`.

- [ ] **Step 1: Write the failing tests**

In `SandboxesTest`, change the probe helper so each trial gets its own answer in order, and add a test:

```java
    private Probe probe(String os, String bwrap, Probe.Trial... answers) {
        java.util.Deque<Probe.Trial> left = new java.util.ArrayDeque<>(List.of(answers));
        return new Probe() {
            @Override
            public String osName() {
                return os;
            }

            @Override
            public Optional<Path> find(String command) {
                return "bwrap".equals(command) && bwrap != null ? Optional.of(Path.of(bwrap)) : Optional.empty();
            }

            @Override
            public Trial trial(List<String> commandLine) {
                trials.add(commandLine);
                return left.isEmpty() ? new Trial(1, "no answer") : left.pop();
            }
        };
    }

    @Test
    void aSecondTrialFindsOutWhetherOverlaysWork() {
        Sandbox with = Sandboxes.detect(SandboxSetting.AUTO, probe("Linux", "/usr/bin/bwrap", new Probe.Trial(0, ""), new Probe.Trial(0, "")));
        assertTrue(with.copyOnWrite());
        assertEquals(List.of(Path.of("/usr/bin/bwrap").toString(), "--ro-bind", "/", "/", "--unshare-pid", "--proc", "/proc",
                "--overlay-src", "/etc", "--tmp-overlay", "/etc", "true"), trials.get(1));

        Sandbox without = Sandboxes.detect(SandboxSetting.AUTO, probe("Linux", "/usr/bin/bwrap", new Probe.Trial(0, ""),
                new Probe.Trial(1, "bwrap: Unknown option --overlay-src")));
        assertInstanceOf(Bubblewrap.class, without);
        assertFalse(without.copyOnWrite());
    }
```

In it, `linuxWithAWorkingBwrapIsSandboxed` now passes two answers (`new Probe.Trial(0, ""), new Probe.Trial(0, "")`) and expects `trials.getFirst()` to equal the first trial's command line, instead of `trials` being that single line. The other calls keep compiling through the varargs. Add `assertTrue` and `assertFalse` imports if missing.

In `ChecksTest.sandboxFindingSaysWhichOrWhyNone`, the OK case becomes `Checks.sandbox(new Bubblewrap("/usr/bin/bwrap", true))`, and add:

```java
    @Test
    void aSandboxWithoutOverlaysIsAWarning() {
        Checks.Finding finding = Checks.sandbox(new Bubblewrap("/usr/bin/bwrap", false));

        assertEquals(Checks.Level.WARN, finding.level());
        assertTrue(finding.message().english().contains("without overlays"), finding.message().english());
    }
```


- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest='SandboxesTest,ChecksTest'`
Expected: FAIL. Only one trial runs, so `copyOnWrite()` is false; and `Checks` gives OK for a bubblewrap without overlays.

- [ ] **Step 3: Implement**

`Sandboxes.detect`, replacing the final `return new Bubblewrap(bwrap.get().toString());`:

```java
        // Copy-on-write state (spec: agent state guard) needs bwrap 0.10+ and overlayfs in a user namespace; a trial answers both.
        Probe.Trial overlay = probe.trial(List.of(bwrap.get().toString(), "--ro-bind", "/", "/", "--unshare-pid", "--proc", "/proc",
                "--overlay-src", "/etc", "--tmp-overlay", "/etc", "true"));
        return new Bubblewrap(bwrap.get().toString(), overlay.exitCode() == 0);
```

`App` (and the same line in `WorkerCommand`): `Log.info("sandbox.selected", "name", sandbox.name(), "overlay", sandbox.copyOnWrite());`

`Checks.sandbox`:

```java
    public static Finding sandbox(Sandbox sandbox) {
        if (sandbox.unavailableReason() != null) {
            return new Finding(Level.WARN, "sandbox", Text.of("check.sandboxNone", sandbox.unavailableReason()));
        }
        if (!sandbox.copyOnWrite()) {
            return new Finding(Level.WARN, "sandbox", Text.of("check.sandboxNoOverlay", sandbox.name()));
        }
        return new Finding(Level.OK, "sandbox", Text.of("check.sandbox", sandbox.name()));
    }
```

Texts:
- `texts_en.properties`: `check.sandboxNoOverlay=sandbox: {0} without overlays (bubblewrap older than 0.10): Claude Code's state is guarded on its listed paths only`
- `texts_mn.properties`: `check.sandboxNoOverlay=sandbox: {0}, overlay-гүй (bubblewrap 0.10-аас хуучин): Claude Code-ийн төлөвийг зөвхөн жагсаасан замаар хамгаална`

- [ ] **Step 4: Run the tests**

Run: `./mvnw -q test -Dtest='SandboxesTest,ChecksTest,AppTest,WorkerCommandTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/agent/sandbox/Sandboxes.java src/main/java/dispatch/App.java \
        src/main/java/dispatch/worker/WorkerCommand.java src/main/java/dispatch/cli/Checks.java src/main/resources/texts_*.properties \
        src/test/java/dispatch/agent/sandbox/SandboxesTest.java src/test/java/dispatch/cli/ChecksTest.java
git commit -m "Startup tries an overlay in a trial sandbox, logs whether agents' state can be copy-on-write, and dispatch check warns when it cannot

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq"
```

---

### Task 5: The real sandbox keeps what persists and drops the rest

**Files:**
- Test: `src/test/java/dispatch/agent/sandbox/BubblewrapSandboxTest.java`

**Interfaces:**
- Consumes: `Confinement.prepare` (Task 3), `Sandboxes.detect` with overlays (Task 4), `AgentState`.

- [ ] **Step 1: Write the tests** (they exercise code from Tasks 1–4, so they pass at once; a failure is a finding)

```java
    private static final AgentState CLAUDE = new AgentState(".claude", true,
            List.of(".claude/projects", ".claude/sessions", ".claude/.credentials.json"), List.of(".claude.json"),
            List.of(".claude/agents"));
    private static final AgentState CODEX = new AgentState(".codex", false, List.of(), List.of(),
            List.of(".codex/AGENTS.md", ".codex/hooks.json"));

    private String runConfined(AgentState state, String script) throws Exception {
        Confinement confinement = new Confinement(sandbox, new SandboxPolicies(home, stateDir, List.of(stateDir)));
        RunRequest request = new RunRequest(RunKind.EXECUTE, worktree, "p", UUID.randomUUID(), false, List.of(), null, null, null,
                stateDir.resolve("runs/7/1"));
        Confinement.Confined confined = confinement.prepare(List.of("sh", "-c", script, home.toString()), request, state, Map.of());
        Process process = new ProcessBuilder(confined.commandLine()).directory(worktree.toFile()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        confined.guard().end();
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void copyOnWriteKeepsWhatPersistsAndDropsTheRest() throws Exception {
        assumeTrue(sandbox.copyOnWrite(), "bwrap here has no overlays");
        Files.createDirectories(home.resolve(".claude/agents"));
        Files.createDirectories(home.resolve(".claude/projects"));
        Files.writeString(home.resolve(".claude.json"), "{}");

        String output = runConfined(CLAUDE, """
                echo planted > "$0/.claude/agents/planted.md" && echo planted-inside
                echo kept > "$0/.claude/projects/kept.jsonl"
                echo '{"mcpServers":{"x":{}}}' > "$0/.claude.json" && cat "$0/.claude.json"
                """);

        assertTrue(output.contains("planted-inside"), output);
        assertFalse(Files.exists(home.resolve(".claude/agents/planted.md")), "gone with the sandbox");
        assertEquals("kept", Files.readString(home.resolve(".claude/projects/kept.jsonl")).strip());
        assertEquals("{}", Files.readString(home.resolve(".claude.json")), "the copy took the write");
        assertFalse(Files.exists(stateDir.resolve("runs/7/1.claude.json")), "the copy is deleted");
    }

    /** Claude Code renames its credentials into place and falls back to writing in place when the rename fails (2.1.286). */
    @Test
    void aRenameOverAPersistedFileFailsAndAnInPlaceWriteLands() throws Exception {
        assumeTrue(sandbox.copyOnWrite(), "bwrap here has no overlays");
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude/.credentials.json"), "old-token");

        String output = runConfined(CLAUDE, """
                echo new-token > "$0/.claude/.credentials.json.tmp"
                if mv "$0/.claude/.credentials.json.tmp" "$0/.claude/.credentials.json" 2>/dev/null; then echo renamed; else echo rename-refused; fi
                printf 'new-token' > "$0/.claude/.credentials.json"
                """);

        assertTrue(output.contains("rename-refused"), output);
        assertEquals("new-token", Files.readString(home.resolve(".claude/.credentials.json")));
    }

    @Test
    void aCodexLoaderIsReadOnlyAndOneThePlantedIsQuarantined() throws Exception {
        Files.createDirectories(home.resolve(".codex"));
        Files.writeString(home.resolve(".codex/hooks.json"), "{}");

        String output = runConfined(CODEX, """
                if echo x > "$0/.codex/hooks.json" 2>/dev/null; then echo hooks-written; else echo hooks-refused; fi
                echo 'obey me' > "$0/.codex/AGENTS.md" && echo agents-planted
                """);

        assertTrue(output.contains("hooks-refused"), output);
        assertTrue(output.contains("agents-planted"), output);
        assertEquals("{}", Files.readString(home.resolve(".codex/hooks.json")));
        assertFalse(Files.exists(home.resolve(".codex/AGENTS.md")));
        assertEquals("obey me", Files.readString(stateDir.resolve("quarantine/7-1/.codex/AGENTS.md")).strip());
    }
```

(Add imports `java.util.Map` and `dispatch.agent.sandbox.AgentState` / `Confinement` if missing; the class is in the same package.)

- [ ] **Step 2: Run them**

Run: `./mvnw -q test -Dtest=BubblewrapSandboxTest`
Expected: PASS on this machine (bwrap 0.12 has overlays). On a machine without overlays, the two copy-on-write tests are skipped.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/dispatch/agent/sandbox/BubblewrapSandboxTest.java
git commit -m "Real-sandbox tests show copy-on-write state drops a planted file but keeps transcripts and in-place credential writes, and a Codex loader is read-only or quarantined

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq"
```

---

### Task 6: A live Claude Code run under the guard

**Files:**
- Create: `src/test/java/dispatch/core/LiveStateGuardTest.java`

**Interfaces:**
- Consumes: `ClaudeCodeAgent(String, Map, Duration, Confinement)`, `Sandboxes.detect`, `SandboxPolicies`, `Confinement`.

- [ ] **Step 1: Write the test**

```java
package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.RunRequest;
import dispatch.agent.claude.ClaudeCodeAgent;
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
 * Real Claude Code in the real sandbox with the owner's own ~/.claude (spec: agent state guard): a file the agent writes
 * under ~/.claude/agents is gone after the run, and a second run still resumes the first one's session. Off unless
 * DISPATCH_LIVE_CLAUDE=1, since it spends the account's quota.
 */
class LiveStateGuardTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "DISPATCH_LIVE_CLAUDE", matches = "1")
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void aPlantedAgentFileIsGoneAndTheSessionStillResumes() throws Exception {
        Sandbox sandbox = Sandboxes.detect(SandboxSetting.AUTO, Probe.system(System.getenv()));
        assumeTrue(sandbox.copyOnWrite(), "needs bwrap with overlays");
        Path root = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "live-guard");
        Path stateDir = Files.createDirectories(root.resolve("state"));
        Path workdir = Files.createDirectories(root.resolve("work"));
        Path home = Path.of(System.getProperty("user.home"));
        Path planted = home.resolve(".claude/agents/dispatch-live-guard-probe.md");
        Files.deleteIfExists(planted);
        Confinement confinement = new Confinement(sandbox, new SandboxPolicies(home, stateDir, List.of(stateDir)));
        ClaudeCodeAgent claude = new ClaudeCodeAgent("claude", System.getenv(), Duration.ofSeconds(10), confinement);
        UUID session = UUID.randomUUID();

        AgentResult first = claude.start(new RunRequest(RunKind.EXECUTE, workdir,
                "Create the file " + planted + " containing the word probe, then reply with just: done", session, false,
                List.of(), null, "sonnet", null, stateDir.resolve("runs/1/1"))).await();
        AgentResult second = claude.start(new RunRequest(RunKind.EXECUTE, workdir,
                "Which file did you create in this session? Reply with its full path only.", session, true,
                List.of(), null, "sonnet", null, stateDir.resolve("runs/1/2"))).await();

        // Claude Code may itself refuse to write under ~/.claude; either way the file must not exist on the host. That a
        // planted file vanishes is proven in BubblewrapSandboxTest; this run proves real Claude works and resumes under the overlay.
        System.out.println("LIVE guard: first=" + first.outcome() + " (" + first.summary() + ") second=" + second.outcome()
                + " says: " + second.summary());
        assertEquals(AgentOutcome.SUCCEEDED, first.outcome(), first.error());
        assertFalse(Files.exists(planted), "the copy-on-write layer took the write");
        assertEquals(AgentOutcome.SUCCEEDED, second.outcome(), "resume needs the first run's transcript: " + second.error());
    }
}
```

- [ ] **Step 2: It is skipped by default**

Run: `./mvnw -q test -Dtest=LiveStateGuardTest`
Expected: PASS with 1 test skipped.

- [ ] **Step 3: Run it for real once**

Run: `DISPATCH_LIVE_CLAUDE=1 ./mvnw -q test -Dtest=LiveStateGuardTest`
Expected: PASS; the printed line shows both runs SUCCEEDED and the second naming the file. Cost: two short Sonnet runs.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dispatch/core/LiveStateGuardTest.java
git commit -m "An opt-in live test shows real Claude Code in the sandbox losing a planted agent file while its session still resumes

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq"
```

---

### Task 7: ADR 0035 and the documents

**Files:**
- Create: `docs/adr/0035-an-agents-own-state-is-copy-on-write-or-read-only.md`
- Modify: `SECURITY.md` ("## Sandbox"), `docs/ARCHITECTURE.md` ("## Agent boundary"), `docs/superpowers/specs/2026-10-01-agent-state-guard-design.md` (status line)

- [ ] **Step 1: Write ADR 0035**

~~~markdown
# An agent's own state is copy-on-write, or read-only where a later session loads it

Amends ADR 0032 (the sandbox).

A sandboxed agent may write its own state directory, because it needs to: transcripts, credentials, caches. Much of what
is there is loaded by the owner's next session of that agent, outside any sandbox, as code or instructions: plugins and
their hooks, skills, subagents, commands, global instruction files, MCP servers, hook files and trust lists. ADR 0032
protected only the config files. Now each agent declares its state, and the sandbox treats it as follows:

- **Claude Code:** `~/.claude` is mounted copy-on-write (`--overlay-src --tmp-overlay`): the agent sees all of it and its
  writes land in a tmpfs that ends with the sandbox. Only `projects/` (transcripts, which resume and teleport need),
  `sessions/` and `.credentials.json` are bound back. Claude Code renames its credentials into place and writes in place
  when that rename fails with `EBUSY`, so a refreshed token still reaches the owner. `~/.claude.json` gets a throwaway
  copy per run, which also closes the MCP-server gap ADR 0032 accepted.
- **Codex and Gemini CLI:** their loader paths (Codex: `config.toml`, `AGENTS.md`, `hooks.json`, `prompts/`, `skills/`,
  `plugins/`, `rules/`, `memories/`; Gemini: `settings.json`, `GEMINI.md`, `extensions/`, `commands/`,
  `trustedFolders.json`) are bound read-only when present. Bubblewrap cannot protect a path that does not exist, so a
  run guard records the absent ones and, after the run, moves any that appeared to `<stateDir>/quarantine` and logs it.
- **Bubblewrap without overlays** (older than 0.10): Claude Code gets the read-only loader paths and the guard instead,
  and startup and `dispatch check` say so.

We chose this over:
- **Copy-on-write for every agent.** Codex keeps live SQLite databases in the root of `~/.codex`, and Gemini CLI renames
  files into place; neither persists safely through binds that cannot be checked until their live check.
- **Read-only loader paths for every agent.** It misses what a later version of an agent starts to load, which
  copy-on-write covers by construction.
- **Leaving it.** A planted plugin hook runs as the owner the next time they open the agent.

Consequences: a planted loader path exists until its run ends, so a session the owner starts in that window could load
it. Claude's fallback protects only the listed paths. Claude Code's persisted paths are 2.1.286's; a version that keeps
something new under `~/.claude` loses it at the end of each run until the list is updated. If the owner's own session
refreshes the token during a run, the run keeps the file it started with; a refresh it then needs can fail, loudly.
~~~

- [ ] **Step 2: SECURITY.md**

In "## Sandbox":
- In the **Protects** bullet, replace the clause `and the agent and build-tool config that runs code (`~/.claude/settings*.json`, `~/.codex/config.toml`, `~/.gemini/settings.json`, `~/.gradle/init.d`, `~/.gradle/gradle.properties`, `~/.m2/settings.xml`)` with `and the build tools' config that runs code (`~/.gradle/init.d`, `~/.gradle/gradle.properties`, `~/.m2/settings.xml`)`, and append this sentence to the bullet: `An agent's own state is guarded (ADR 0035): Claude Code's `~/.claude` is copy-on-write, so nothing a run writes there survives except its transcripts, sessions and credentials, and `~/.claude.json` is a throwaway copy per run; Codex's and Gemini CLI's loader paths (instructions, hooks, prompts, skills, plugins, rules, memories, extensions, commands, trust list) are read-only, and one a run creates is moved to `<stateDir>/quarantine` afterwards and logged.`
- In the **Does not protect** bullet, replace `~/.claude.json` stays writable (Claude Code writes it), so an `mcpServers` entry planted there persists.` with `A loader path a Codex or Gemini CLI run creates exists until the run ends; without overlays (bubblewrap older than 0.10) Claude Code's state is guarded on its listed paths only.`

- [ ] **Step 3: ARCHITECTURE**

In "## Agent boundary", after the `RunHandle` description paragraph, add:

```
A sandboxed run is prepared by `Confinement.prepare`, which turns the agent's `AgentState` into mounts (ADR 0035) and returns a `RunGuard`: it makes the run's throwaway copies (Claude Code's `~/.claude.json`) before the process starts and, once the process exits however it ended, deletes them and moves any loader path the run created to `<stateDir>/quarantine`.
```

- [ ] **Step 4: Mark the spec built**

The spec's status line becomes: `Status: approved design, 2026-10-01; built on branch agent-state-guard (see [ADR 0035](../../adr/0035-an-agents-own-state-is-copy-on-write-or-read-only.md)). Amends ADR 0032 (the sandbox).`

- [ ] **Step 5: The whole build**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS, every test green.

- [ ] **Step 6: Commit**

```bash
git add docs SECURITY.md
git commit -m "ADR 0035, SECURITY.md and the architecture say an agent's own state is copy-on-write or read-only where a later session loads it

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq"
```
