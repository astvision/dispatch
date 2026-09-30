# Agent Sandbox (SB) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every agent process Dispatch starts runs inside bubblewrap by default on Linux, and runs unsandboxed with a visible warning where the machine cannot sandbox.

**Architecture:** A new package `dispatch.agent.sandbox` holds a `Sandbox` (bubblewrap or none), the per-run `SandboxPolicy` built by `SandboxPolicies`, and `Sandboxes.detect`, which picks one sandbox at startup. `ProcessRun.start`, which every agent already goes through, wraps the command line through a `Confinement` (sandbox + policies) and stamps the result with the `SandboxUse`. The run's sandbox is stored in `run.sandbox` and an unsandboxed plan or result gets a ⚠️ line in Telegram.

**Tech Stack:** Java 25, plain JDK (no framework, ADR 0002), JUnit 5, SQLite migrations in `src/main/resources/db/`, Jackson 2.22, Maven wrapper (`./mvnw`), bubblewrap 0.12 on the dev laptop.

**Spec:** `docs/superpowers/specs/2026-09-30-agent-sandbox-design.md`

## Global Constraints

- Setting: `sandbox: auto | off`, default `auto`, in the instance config (`dispatch.yaml`/`team.yaml`) and in `worker.yaml`; anything else is refused at config load.
- Linux only in v1: macOS → `NoSandbox("the macOS sandbox is not built yet")`, Windows → `NoSandbox("not available on Windows")`.
- Network stays open. Permission modes, deny rules and withheld variables (`ProcessRun.WITHHELD_VARIABLES`) do not change.
- No retry without the sandbox when bwrap fails to start a run: the run fails as `AGENT` with the stderr tail.
- Hidden (empty tmpfs): `~/.ssh`, `~/.config/gh`, `~/.gnupg`, Dispatch's config dir, Dispatch's state dir, every configured project's clone.
- Writable: the workdir, the clone's git common dir, the agent's state (`~/.claude`, `~/.claude.json`; `~/.codex`; `~/.gemini`), `~/.m2`, `~/.gradle`, `~/.npm`, `~/.cache`; a fresh tmpfs `/tmp`.
- bwrap flags: `--die-with-parent --unshare-pid --new-session --ro-bind / / --dev /dev --proc /proc --tmpfs /tmp`, then hidden, then mount-backs (required `--bind`, optional `--bind-try`/`--ro-bind-try`), then `--chdir <workdir> --`.
- Telegram bot text is Mongolian (`messages_mn.properties`); `dispatch check` text exists in `texts_en.properties` and `texts_mn.properties`.
- Code style: comments explain why, small classes, no `var` in public APIs beyond what the file already does; commit messages are one sentence describing the behaviour, ending with the `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>` line.
- Work on branch `agent-sandbox`. Do not push without the user's go.

## Review Focus

1. **A worktree whose git dir lives in a member's own clone outside the state dir** (worker with `projects.<name>.path: ~/code/x`): the clone is hidden, and its `.git` must be mounted back or `git status` fails. Pinned in Task 2 (`gitCommonDirOfAMembersOwnCloneIsMountedBackThoughTheCloneIsHidden`).
2. **A hidden path that does not exist** (no `~/.gnupg` on a fresh machine): bwrap cannot create a mount point on the read-only root and the run would fail to start. Pinned in Task 2 (`missingSecretDirsAreNotHidden`).
3. **The assistant's `dispatch ask` reading `dispatch.db` inside the hidden state dir**: without the db, `-wal`, `-shm` and `assistant-bin` mounted back, every assistant turn loses its task data. Pinned in Task 2 (`assistantRunGetsTheStateFileAndItsCommandBack`) and Task 9's live check.
4. **A newer worker reporting `sandbox` to an older team machine**: `Json.MAPPER` fails on unknown properties, so the team machine must be upgraded first. Pinned in Task 4 (`aResultWithoutSandboxStillReads`) and documented in Task 8.
5. **A run started with the agent's fake/test constructor** (no confinement given): must behave exactly as today and record `none` rather than crash. Pinned in Task 4 (`agentWithoutConfinementRunsTheCommandUnwrapped`).

---

### Task 1: Sandbox, NoSandbox and Bubblewrap

**Files:**
- Create: `src/main/java/dispatch/agent/sandbox/SandboxPolicy.java`
- Create: `src/main/java/dispatch/agent/sandbox/Sandbox.java`
- Create: `src/main/java/dispatch/agent/sandbox/NoSandbox.java`
- Create: `src/main/java/dispatch/agent/sandbox/Bubblewrap.java`
- Test: `src/test/java/dispatch/agent/sandbox/BubblewrapTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `record SandboxPolicy(Path workdir, Path gitCommonDir /* nullable */, List<Path> readOnly, List<Path> writable, List<Path> hidden)`
  - `interface Sandbox { String name(); String unavailableReason(); List<String> wrap(List<String> commandLine, SandboxPolicy policy); }`
  - `record NoSandbox(String reason) implements Sandbox` — `name()` = `"none"`, `unavailableReason()` = `reason`, `wrap` returns the command unchanged.
  - `final class Bubblewrap implements Sandbox` — `new Bubblewrap(String command)`, `name()` = `"bubblewrap"`, `unavailableReason()` = `null`.

- [ ] **Step 1: Write the failing test**

```java
package dispatch.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BubblewrapTest {

    private static final List<String> CLAUDE = List.of("claude", "-p", "--output-format", "stream-json");

    @Test
    void hidesFirstThenMountsBackWhatTheRunNeedsThenRunsTheCommand() {
        SandboxPolicy policy = new SandboxPolicy(
                Path.of("/state/worktrees/7"),
                Path.of("/state/repos/alm/.git"),
                List.of(Path.of("/state/attachments/7")),
                List.of(Path.of("/home/ann/.claude"), Path.of("/home/ann/.m2")),
                List.of(Path.of("/home/ann/.ssh"), Path.of("/state")));

        List<String> wrapped = new Bubblewrap("/usr/bin/bwrap").wrap(CLAUDE, policy);

        assertEquals(List.of("/usr/bin/bwrap",
                "--die-with-parent", "--unshare-pid", "--new-session",
                "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp",
                "--tmpfs", "/home/ann/.ssh",
                "--tmpfs", "/state",
                "--bind", "/state/worktrees/7", "/state/worktrees/7",
                "--bind", "/state/repos/alm/.git", "/state/repos/alm/.git",
                "--bind-try", "/home/ann/.claude", "/home/ann/.claude",
                "--bind-try", "/home/ann/.m2", "/home/ann/.m2",
                "--ro-bind-try", "/state/attachments/7", "/state/attachments/7",
                "--chdir", "/state/worktrees/7",
                "--",
                "claude", "-p", "--output-format", "stream-json"), wrapped);
    }

    @Test
    void aRunWithoutAGitDirMountsOnlyItsWorkdir() {
        SandboxPolicy policy = new SandboxPolicy(Path.of("/state/splits/3"), null, List.of(), List.of(), List.of());

        List<String> wrapped = new Bubblewrap("bwrap").wrap(List.of("claude"), policy);

        assertEquals(List.of("bwrap",
                "--die-with-parent", "--unshare-pid", "--new-session",
                "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp",
                "--bind", "/state/splits/3", "/state/splits/3",
                "--chdir", "/state/splits/3", "--", "claude"), wrapped);
    }

    @Test
    void bubblewrapIsolatesAndNoSandboxSaysWhyNot() {
        assertEquals("bubblewrap", new Bubblewrap("bwrap").name());
        assertNull(new Bubblewrap("bwrap").unavailableReason());

        NoSandbox none = new NoSandbox("bubblewrap (bwrap) is not installed");
        assertEquals("none", none.name());
        assertEquals("bubblewrap (bwrap) is not installed", none.unavailableReason());
        assertEquals(CLAUDE, none.wrap(CLAUDE, null));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -Dtest=BubblewrapTest test`
Expected: compilation FAIL, `cannot find symbol: class Bubblewrap`.

- [ ] **Step 3: Write the implementation**

`SandboxPolicy.java`:

```java
package dispatch.agent.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * What one agent run may touch. Everything else is the read-only root, and {@code hidden} is replaced by empty
 * directories before anything is mounted back, so a workdir inside a hidden directory stays reachable.
 *
 * @param workdir      read-write, and where the agent starts
 * @param gitCommonDir the clone's .git that a worktree's .git file points to, read-write; null when the run has none
 * @param readOnly     mounted back read-only when they exist
 * @param writable     mounted back read-write when they exist
 * @param hidden       existing directories replaced by empty ones
 */
public record SandboxPolicy(Path workdir, Path gitCommonDir, List<Path> readOnly, List<Path> writable, List<Path> hidden) {

    public SandboxPolicy {
        readOnly = List.copyOf(readOnly);
        writable = List.copyOf(writable);
        hidden = List.copyOf(hidden);
    }
}
```

`Sandbox.java`:

```java
package dispatch.agent.sandbox;

import java.util.List;

/** Confines an agent process to what its run needs (spec: 2026-09-30-agent-sandbox-design). */
public interface Sandbox {

    /** "bubblewrap", or "none" when runs are not isolated; stored with each run. */
    String name();

    /** Why runs are not isolated on this machine; null when this sandbox isolates them. */
    String unavailableReason();

    /** The command line that runs {@code commandLine} inside the sandbox. */
    List<String> wrap(List<String> commandLine, SandboxPolicy policy);
}
```

`NoSandbox.java`:

```java
package dispatch.agent.sandbox;

import java.util.List;

/** A machine that cannot sandbox: runs go ahead as before, and the reason is shown wherever that matters. */
public record NoSandbox(String reason) implements Sandbox {

    @Override
    public String name() {
        return "none";
    }

    @Override
    public String unavailableReason() {
        return reason;
    }

    @Override
    public List<String> wrap(List<String> commandLine, SandboxPolicy policy) {
        return List.copyOf(commandLine);
    }
}
```

`Bubblewrap.java`:

```java
package dispatch.agent.sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * bubblewrap on Linux. The whole root is read-only, then the hidden directories become empty, then the run's own paths
 * are mounted back: the order matters, since a worktree lives under the hidden state dir. The network is left open
 * (ADR 0009: an allowlist needs tuning per project).
 */
public final class Bubblewrap implements Sandbox {

    private final String command;

    public Bubblewrap(String command) {
        this.command = command;
    }

    @Override
    public String name() {
        return "bubblewrap";
    }

    @Override
    public String unavailableReason() {
        return null;
    }

    @Override
    public List<String> wrap(List<String> commandLine, SandboxPolicy policy) {
        List<String> args = new ArrayList<>(List.of(command,
                // The sandbox ends with Dispatch, and the agent cannot inject input into Dispatch's terminal.
                "--die-with-parent", "--unshare-pid", "--new-session",
                "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp"));
        for (Path hidden : policy.hidden()) {
            args.addAll(List.of("--tmpfs", hidden.toString()));
        }
        // Required: a wrong path must fail the run loudly rather than run without its worktree.
        bind(args, "--bind", policy.workdir());
        if (policy.gitCommonDir() != null) {
            bind(args, "--bind", policy.gitCommonDir());
        }
        // Optional: a machine without ~/.gemini or ~/.m2 runs all the same.
        policy.writable().forEach(path -> bind(args, "--bind-try", path));
        policy.readOnly().forEach(path -> bind(args, "--ro-bind-try", path));
        args.addAll(List.of("--chdir", policy.workdir().toString(), "--"));
        args.addAll(commandLine);
        return List.copyOf(args);
    }

    private static void bind(List<String> args, String option, Path path) {
        args.addAll(List.of(option, path.toString(), path.toString()));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -Dtest=BubblewrapTest test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/agent/sandbox src/test/java/dispatch/agent/sandbox/BubblewrapTest.java
git commit -m "A Sandbox wraps an agent's command line: bubblewrap hides secrets and state before mounting back what the run needs, and NoSandbox runs it unchanged with its reason

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: SandboxPolicies — what each run may touch

**Files:**
- Create: `src/main/java/dispatch/agent/sandbox/SandboxPolicies.java`
- Test: `src/test/java/dispatch/agent/sandbox/SandboxPoliciesTest.java`

**Interfaces:**
- Consumes: `SandboxPolicy` (Task 1); `dispatch.agent.RunRequest` (existing: `kind()`, `workdir()`, `readOnlyDirs()`); `dispatch.domain.RunKind`; `dispatch.agent.AgentStartException(String, Throwable)`.
- Produces:
  - `new SandboxPolicies(Path home, Path stateDir, List<Path> dispatchPrivate)` — `dispatchPrivate` = Dispatch's config dir, state dir, every configured project clone.
  - `SandboxPolicy forRun(RunRequest request, List<String> agentStateInHome)` — `agentStateInHome` relative to `home`, e.g. `List.of(".claude", ".claude.json")`.
  - `static Path gitCommonDir(Path workdir)` (package-private, tested).

- [ ] **Step 1: Write the failing test**

```java
package dispatch.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.RunRequest;
import dispatch.domain.RunKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxPoliciesTest {

    @TempDir
    Path root;
    Path home;
    Path stateDir;
    Path configDir;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createDirectories(root.resolve("home"));
        stateDir = Files.createDirectories(root.resolve("state"));
        configDir = Files.createDirectories(home.resolve(".config/dispatch"));
        Files.createDirectories(home.resolve(".ssh"));
        Files.createDirectories(home.resolve(".config/gh"));
    }

    @Test
    void executeRunWritesItsWorktreeGitDirAgentStateAndCachesAndSeesNoSecrets() throws IOException {
        Path gitDir = Files.createDirectories(stateDir.resolve("repos/alm/.git/worktrees/7"));
        Path worktree = Files.createDirectories(stateDir.resolve("worktrees/7"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDir + "\n");
        Path attachments = Files.createDirectories(stateDir.resolve("attachments/7"));
        SandboxPolicies policies = new SandboxPolicies(home, stateDir, List.of(configDir, stateDir));

        SandboxPolicy policy = policies.forRun(request(RunKind.EXECUTE, worktree, List.of(attachments)),
                List.of(".claude", ".claude.json"));

        assertEquals(worktree, policy.workdir());
        assertEquals(stateDir.resolve("repos/alm/.git"), policy.gitCommonDir());
        assertEquals(List.of(attachments), policy.readOnly());
        assertEquals(List.of(home.resolve(".claude"), home.resolve(".claude.json"), home.resolve(".m2"),
                home.resolve(".gradle"), home.resolve(".npm"), home.resolve(".cache")), policy.writable());
        assertEquals(List.of(home.resolve(".ssh"), home.resolve(".config/gh"), configDir, stateDir), policy.hidden());
    }

    @Test
    void missingSecretDirsAreNotHidden() {
        // bwrap cannot create a mount point on the read-only root, so hiding ~/.gnupg that does not exist would fail the run.
        SandboxPolicies policies = new SandboxPolicies(home, stateDir, List.of(root.resolve("no-such-clone")));

        SandboxPolicy policy = policies.forRun(request(RunKind.PLAN, stateDir, List.of()), List.of(".codex"));

        assertFalse(policy.hidden().contains(home.resolve(".gnupg")));
        assertFalse(policy.hidden().contains(root.resolve("no-such-clone")));
        assertTrue(policy.hidden().contains(home.resolve(".ssh")));
    }

    @Test
    void gitCommonDirOfAMembersOwnCloneIsMountedBackThoughTheCloneIsHidden() throws IOException {
        Path clone = Files.createDirectories(home.resolve("code/alm"));
        Path gitDir = Files.createDirectories(clone.resolve(".git/worktrees/9"));
        Path worktree = Files.createDirectories(stateDir.resolve("worktrees/9"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDir);
        SandboxPolicies policies = new SandboxPolicies(home, stateDir, List.of(configDir, stateDir, clone));

        SandboxPolicy policy = policies.forRun(request(RunKind.EXECUTE, worktree, List.of()), List.of(".claude"));

        assertTrue(policy.hidden().contains(clone));
        assertEquals(clone.resolve(".git"), policy.gitCommonDir());
    }

    @Test
    void aRelativeGitdirResolvesAgainstTheWorktree() throws IOException {
        Path worktree = Files.createDirectories(stateDir.resolve("worktrees/4"));
        Files.createDirectories(stateDir.resolve("repos/alm/.git/worktrees/4"));
        Files.writeString(worktree.resolve(".git"), "gitdir: ../../repos/alm/.git/worktrees/4");

        assertEquals(stateDir.resolve("repos/alm/.git"), SandboxPolicies.gitCommonDir(worktree));
    }

    @Test
    void aSplitHasNoGitDir() {
        assertNull(SandboxPolicies.gitCommonDir(stateDir));
    }

    @Test
    void assistantRunGetsTheStateFileAndItsCommandBack() throws IOException {
        Path assistantHome = Files.createDirectories(stateDir.resolve("assistant"));
        SandboxPolicies policies = new SandboxPolicies(home, stateDir, List.of(configDir, stateDir));

        SandboxPolicy policy = policies.forRun(request(RunKind.ASSISTANT, assistantHome, List.of()), List.of(".claude"));

        // dispatch ask reads the state file; SQLite needs -wal and -shm writable even to read.
        assertTrue(policy.writable().containsAll(List.of(stateDir.resolve("dispatch.db"),
                stateDir.resolve("dispatch.db-wal"), stateDir.resolve("dispatch.db-shm"))));
        assertTrue(policy.readOnly().contains(stateDir.resolve("assistant-bin")));
        assertTrue(policy.hidden().contains(stateDir));
    }

    @Test
    void onlyTheAssistantGetsTheStateFile() throws IOException {
        Path worktree = Files.createDirectories(stateDir.resolve("worktrees/7"));
        SandboxPolicies policies = new SandboxPolicies(home, stateDir, List.of(stateDir));

        SandboxPolicy policy = policies.forRun(request(RunKind.PLAN, worktree, List.of()), List.of(".claude"));

        assertFalse(policy.writable().contains(stateDir.resolve("dispatch.db")));
        assertFalse(policy.readOnly().contains(stateDir.resolve("assistant-bin")));
    }

    private static RunRequest request(RunKind kind, Path workdir, List<Path> readOnlyDirs) {
        return new RunRequest(kind, workdir, "prompt", UUID.randomUUID(), false, readOnlyDirs, null, null, null,
                workdir.resolve("run"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -Dtest=SandboxPoliciesTest test`
Expected: compilation FAIL, `cannot find symbol: class SandboxPolicies`.

- [ ] **Step 3: Write the implementation**

```java
package dispatch.agent.sandbox;

import dispatch.agent.AgentStartException;
import dispatch.agent.RunRequest;
import dispatch.domain.RunKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** What each run may touch on this machine (spec: 2026-09-30-agent-sandbox-design, "The policy"). */
public final class SandboxPolicies {

    /** Writable so builds fetch new dependencies as they do outside the sandbox; a poisoned cache is the accepted cost. */
    private static final List<String> CACHES = List.of(".m2", ".gradle", ".npm", ".cache");
    private static final List<String> SECRETS = List.of(".ssh", ".config/gh", ".gnupg");
    private static final String GITDIR_PREFIX = "gitdir: ";

    private final Path home;
    private final Path stateDir;
    private final List<Path> dispatchPrivate;

    /**
     * @param home            the user's home, which the agent's state and caches are relative to
     * @param stateDir        this instance's or worker's state dir; its state file is the assistant's to read
     * @param dispatchPrivate Dispatch's config dir, state dir and every configured project's clone
     */
    public SandboxPolicies(Path home, Path stateDir, List<Path> dispatchPrivate) {
        this.home = home;
        this.stateDir = stateDir;
        this.dispatchPrivate = List.copyOf(dispatchPrivate);
    }

    /** @param agentStateInHome the agent's own files, relative to home, e.g. ".claude" and ".claude.json" */
    public SandboxPolicy forRun(RunRequest request, List<String> agentStateInHome) {
        Path workdir = request.workdir().toAbsolutePath().normalize();
        List<Path> writable = new ArrayList<>();
        agentStateInHome.forEach(entry -> writable.add(home.resolve(entry)));
        CACHES.forEach(cache -> writable.add(home.resolve(cache)));
        List<Path> readOnly = new ArrayList<>(request.readOnlyDirs());
        if (request.kind() == RunKind.ASSISTANT) {
            // dispatch ask reads the state file, and SQLite writes -shm even to read a WAL database (AssistantHome).
            Path database = stateDir.resolve("dispatch.db");
            writable.addAll(List.of(database, stateDir.resolve("dispatch.db-wal"), stateDir.resolve("dispatch.db-shm")));
            readOnly.add(stateDir.resolve("assistant-bin"));
        }
        // bwrap cannot create a mount point on the read-only root: a directory that does not exist has nothing to hide.
        List<Path> hidden = Stream.concat(SECRETS.stream().map(home::resolve), dispatchPrivate.stream())
                .filter(Files::isDirectory)
                .toList();
        return new SandboxPolicy(workdir, gitCommonDir(workdir), readOnly, writable, hidden);
    }

    /**
     * A worktree's .git is a file naming {@code <common>/worktrees/<name>}; git needs the common dir to read objects
     * and update the index. Null when the workdir is not a worktree (a split, the assistant) or holds its own .git.
     */
    static Path gitCommonDir(Path workdir) {
        Path dotGit = workdir.resolve(".git");
        if (!Files.isRegularFile(dotGit)) {
            return null;
        }
        String line;
        try {
            line = Files.readString(dotGit).strip();
        } catch (IOException e) {
            throw new AgentStartException("cannot read " + dotGit + ": " + e.getMessage(), e);
        }
        if (!line.startsWith(GITDIR_PREFIX)) {
            throw new AgentStartException(dotGit + " does not name a git dir: " + line, null);
        }
        Path gitDir = workdir.resolve(line.substring(GITDIR_PREFIX.length())).normalize();
        Path parent = gitDir.getParent();
        boolean linkedWorktree = parent != null && parent.getFileName().toString().equals("worktrees");
        return linkedWorktree ? parent.getParent() : gitDir;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -Dtest=SandboxPoliciesTest test`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/agent/sandbox/SandboxPolicies.java src/test/java/dispatch/agent/sandbox/SandboxPoliciesTest.java
git commit -m "Each run's sandbox policy writes only its worktree, its clone's git dir, the agent's own state and build caches, hides secrets, Dispatch's files and clones, and gives the assistant its state file back

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Detecting the sandbox at startup

**Files:**
- Create: `src/main/java/dispatch/agent/sandbox/SandboxSetting.java`
- Create: `src/main/java/dispatch/agent/sandbox/Probe.java`
- Create: `src/main/java/dispatch/agent/sandbox/Sandboxes.java`
- Test: `src/test/java/dispatch/agent/sandbox/SandboxesTest.java`

**Interfaces:**
- Consumes: `Sandbox`, `Bubblewrap`, `NoSandbox` (Task 1).
- Produces:
  - `enum SandboxSetting { AUTO, OFF; static Optional<SandboxSetting> fromConfig(String value) }` — `null` → `AUTO`; `"auto"`/`"off"` → value; anything else → empty.
  - `interface Probe { String osName(); Optional<Path> find(String command); Trial trial(List<String> commandLine); record Trial(int exitCode, String output) {}; static Probe system(Map<String, String> environment) }`
  - `static Sandbox Sandboxes.detect(SandboxSetting setting, Probe probe)`

- [ ] **Step 1: Write the failing test**

```java
package dispatch.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SandboxesTest {

    private final List<List<String>> trials = new ArrayList<>();

    @Test
    void linuxWithAWorkingBwrapIsSandboxed() {
        Sandbox sandbox = Sandboxes.detect(SandboxSetting.AUTO, probe("Linux", "/usr/bin/bwrap", new Probe.Trial(0, "")));

        assertInstanceOf(Bubblewrap.class, sandbox);
        assertEquals(List.of(List.of("/usr/bin/bwrap", "--ro-bind", "/", "/", "--unshare-pid", "--proc", "/proc", "true")), trials);
    }

    @Test
    void offInConfigIsNeverSandboxedAndNothingIsTried() {
        Sandbox sandbox = Sandboxes.detect(SandboxSetting.OFF, probe("Linux", "/usr/bin/bwrap", new Probe.Trial(0, "")));

        assertEquals(new NoSandbox("turned off in config (sandbox: off)"), sandbox);
        assertEquals(List.of(), trials);
    }

    @Test
    void linuxWithoutBwrapSaysToInstallIt() {
        assertEquals(new NoSandbox("bubblewrap (bwrap) is not installed"),
                Sandboxes.detect(SandboxSetting.AUTO, probe("Linux", null, null)));
    }

    @Test
    void aBwrapThatCannotCreateASandboxGivesItsOwnFirstLine() {
        Probe.Trial blocked = new Probe.Trial(1, "bwrap: setting up uid map: Permission denied\nmore detail");

        assertEquals(new NoSandbox("bwrap cannot create a sandbox here: bwrap: setting up uid map: Permission denied"),
                Sandboxes.detect(SandboxSetting.AUTO, probe("Linux", "/usr/bin/bwrap", blocked)));
    }

    @Test
    void macOsAndWindowsFallBack() {
        assertEquals(new NoSandbox("the macOS sandbox is not built yet"),
                Sandboxes.detect(SandboxSetting.AUTO, probe("Mac OS X", null, null)));
        assertEquals(new NoSandbox("not available on Windows"),
                Sandboxes.detect(SandboxSetting.AUTO, probe("Windows 11", null, null)));
    }

    @Test
    void settingReadsAutoOffOrNothing() {
        assertEquals(Optional.of(SandboxSetting.AUTO), SandboxSetting.fromConfig(null));
        assertEquals(Optional.of(SandboxSetting.AUTO), SandboxSetting.fromConfig("auto"));
        assertEquals(Optional.of(SandboxSetting.OFF), SandboxSetting.fromConfig("off"));
        assertEquals(Optional.empty(), SandboxSetting.fromConfig("on"));
        assertEquals(Optional.empty(), SandboxSetting.fromConfig("OFF"));
    }

    private Probe probe(String os, String bwrap, Probe.Trial trial) {
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
                return trial;
            }
        };
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -Dtest=SandboxesTest test`
Expected: compilation FAIL, `cannot find symbol: class Sandboxes`.

- [ ] **Step 3: Write the implementation**

`SandboxSetting.java`:

```java
package dispatch.agent.sandbox;

import java.util.Optional;

/** {@code sandbox:} in the instance config and in worker.yaml. */
public enum SandboxSetting {
    AUTO,
    OFF;

    /** Empty for anything but auto or off, which the config loaders refuse; a missing setting is auto. */
    public static Optional<SandboxSetting> fromConfig(String value) {
        if (value == null || value.equals("auto")) {
            return Optional.of(AUTO);
        }
        return value.equals("off") ? Optional.of(OFF) : Optional.empty();
    }
}
```

`Probe.java`:

```java
package dispatch.agent.sandbox;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** What detection asks of the machine; a test answers for any OS. */
public interface Probe {

    String osName();

    /** The executable {@code command} on the PATH, if any. */
    Optional<Path> find(String command);

    /** Runs {@code commandLine} briefly; exit code -1 when it could not run or timed out. */
    Trial trial(List<String> commandLine);

    record Trial(int exitCode, String output) {
    }

    static Probe system(Map<String, String> environment) {
        return new Probe() {
            @Override
            public String osName() {
                return System.getProperty("os.name");
            }

            @Override
            public Optional<Path> find(String command) {
                String path = environment.getOrDefault("PATH", "");
                for (String dir : path.split(File.pathSeparator)) {
                    if (dir.isEmpty()) {
                        continue;
                    }
                    Path candidate = Path.of(dir, command);
                    if (Files.isExecutable(candidate)) {
                        return Optional.of(candidate);
                    }
                }
                return Optional.empty();
            }

            @Override
            public Trial trial(List<String> commandLine) {
                try {
                    Process process = new ProcessBuilder(commandLine).redirectErrorStream(true).start();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        return new Trial(-1, "timed out after 5 s");
                    }
                    return new Trial(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    return new Trial(-1, e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Trial(-1, "interrupted");
                }
            }
        };
    }
}
```

`Sandboxes.java`:

```java
package dispatch.agent.sandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Picks this machine's sandbox once, at startup (spec: "Detection"). */
public final class Sandboxes {

    private Sandboxes() {
    }

    public static Sandbox detect(SandboxSetting setting, Probe probe) {
        if (setting == SandboxSetting.OFF) {
            return new NoSandbox("turned off in config (sandbox: off)");
        }
        String os = probe.osName().toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return new NoSandbox("not available on Windows");
        }
        if (os.startsWith("mac")) {
            return new NoSandbox("the macOS sandbox is not built yet");
        }
        if (!os.startsWith("linux")) {
            return new NoSandbox("not available on " + probe.osName());
        }
        Optional<Path> bwrap = probe.find("bwrap");
        if (bwrap.isEmpty()) {
            return new NoSandbox("bubblewrap (bwrap) is not installed");
        }
        // The same namespaces a run needs: Ubuntu 24's AppArmor rule, for one, refuses them to unprivileged users.
        Probe.Trial trial = probe.trial(List.of(bwrap.get().toString(), "--ro-bind", "/", "/", "--unshare-pid", "--proc", "/proc", "true"));
        if (trial.exitCode() != 0) {
            return new NoSandbox("bwrap cannot create a sandbox here: " + trial.output().strip().lines().findFirst().orElse("exit " + trial.exitCode()));
        }
        return new Bubblewrap(bwrap.get().toString());
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -Dtest=SandboxesTest test`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/agent/sandbox/SandboxSetting.java src/main/java/dispatch/agent/sandbox/Probe.java src/main/java/dispatch/agent/sandbox/Sandboxes.java src/test/java/dispatch/agent/sandbox/SandboxesTest.java
git commit -m "Startup picks bubblewrap when a trial sandbox works on Linux, and otherwise no sandbox with the reason: turned off, not installed, blocked, macOS or Windows

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Every agent process goes through the sandbox, and its result says which

**Files:**
- Create: `src/main/java/dispatch/agent/SandboxUse.java`
- Create: `src/main/java/dispatch/agent/sandbox/Confinement.java`
- Modify: `src/main/java/dispatch/agent/AgentResult.java`
- Modify: `src/main/java/dispatch/agent/ProcessRun.java:56-80` (`start`) and `:98-103` (`await`)
- Modify: `src/main/java/dispatch/agent/claude/ClaudeCodeAgent.java:31-47`
- Modify: `src/main/java/dispatch/agent/codex/CodexAgent.java:47` and its `ProcessRun.start` call at `:86`
- Modify: `src/main/java/dispatch/agent/gemini/GeminiAgent.java:33` and its `ProcessRun.start` call at `:61`
- Modify: `src/main/java/dispatch/agent/Agents.java`
- Test: `src/test/java/dispatch/agent/sandbox/ConfinementTest.java`
- Test: `src/test/java/dispatch/agent/claude/ClaudeCodeAgentTest.java` (add one test)
- Test: `src/test/java/dispatch/core/JobJsonTest.java` (add two tests)

**Interfaces:**
- Consumes: `Sandbox`, `NoSandbox`, `SandboxPolicies.forRun(RunRequest, List<String>)` (Tasks 1–2).
- Produces:
  - `record SandboxUse(String name, String unsandboxedReason)` in `dispatch.agent`.
  - `AgentResult` gains a 12th component `SandboxUse sandbox` (nullable: runs from fakes and from before this version); the existing 11-arg constructor stays and passes `null`; `AgentResult withSandbox(SandboxUse sandbox)`.
  - `record Confinement(Sandbox sandbox, SandboxPolicies policies)` with `static Confinement none(String reason)`, `List<String> wrap(List<String> commandLine, RunRequest request, List<String> agentStateInHome)`, `SandboxUse use()`.
  - `ProcessRun.start(String agent, List<String> commandLine, RunRequest request, Map<String, String> environment, String prompt, OutputParser parser, Duration cancelGrace, Confinement confinement, List<String> agentStateInHome)`.
  - `new ClaudeCodeAgent(String, Map, Duration, Confinement)`, `new CodexAgent(String, Map, Duration, Path, Confinement)`, `new GeminiAgent(String, Map, Duration, Confinement)`; each old constructor stays and passes `Confinement.none("no sandbox configured")`.
  - `Agents.create(Map<String, String> commands, Map<String, String> environment, Path stateDir, Confinement confinement)`; the old 3-arg `create` stays and passes `Confinement.none("no sandbox configured")`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/dispatch/agent/sandbox/ConfinementTest.java`:

```java
package dispatch.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.agent.RunRequest;
import dispatch.agent.SandboxUse;
import dispatch.domain.RunKind;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfinementTest {

    @TempDir
    Path root;

    @Test
    void anIsolatingSandboxWrapsTheCommandWithTheRunsPolicy() {
        Confinement confinement = new Confinement(new Bubblewrap("bwrap"), new SandboxPolicies(root, root.resolve("state"), List.of()));

        List<String> wrapped = confinement.wrap(List.of("claude", "-p"), request(root), List.of(".claude"));

        assertEquals("bwrap", wrapped.getFirst());
        assertEquals(List.of("--", "claude", "-p"), wrapped.subList(wrapped.size() - 3, wrapped.size()));
        assertEquals(new SandboxUse("bubblewrap", null), confinement.use());
    }

    @Test
    void noSandboxLeavesTheCommandAloneAndNeedsNoPolicies() {
        Confinement confinement = Confinement.none("bubblewrap (bwrap) is not installed");

        assertEquals(List.of("claude", "-p"), confinement.wrap(List.of("claude", "-p"), request(root), List.of(".claude")));
        assertEquals(new SandboxUse("none", "bubblewrap (bwrap) is not installed"), confinement.use());
    }

    private static RunRequest request(Path workdir) {
        return new RunRequest(RunKind.PLAN, workdir, "prompt", UUID.randomUUID(), false, List.of(), null, null, null,
                workdir.resolve("run"));
    }
}
```

Add to `src/test/java/dispatch/agent/claude/ClaudeCodeAgentTest.java` (its `setUp()` builds `agent` with the 3-arg constructor from `FakeClaude.install(dir)`; the fake records its args in `fake-claude.args` and its environment in `fake-claude.env` in the workdir). Add imports `dispatch.agent.SandboxUse`, `dispatch.agent.sandbox.Confinement`, `dispatch.agent.sandbox.Sandbox`, `dispatch.agent.sandbox.SandboxPolicies`, `dispatch.agent.sandbox.SandboxPolicy`, `java.util.ArrayList`:

```java
    @Test
    void agentWithoutConfinementRunsTheCommandUnwrapped() throws Exception {
        RunRequest request = new RunRequest(RunKind.PLAN, workdir, "Plan it", SESSION, false, List.of(), null, null, null,
                dir.resolve("runs/1/1"));

        AgentResult result = agent.start(request).await();

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
        assertTrue(Files.readAllLines(workdir.resolve("fake-claude.args")).contains("-p"), "the fake claude itself ran");
        assertEquals(new SandboxUse("none", "no sandbox configured"), result.sandbox());
    }

    @Test
    void aConfinedAgentStartsThroughItsSandbox() throws Exception {
        Sandbox recording = new Sandbox() {
            @Override
            public String name() {
                return "recording";
            }

            @Override
            public String unavailableReason() {
                return null;
            }

            @Override
            public List<String> wrap(List<String> commandLine, SandboxPolicy policy) {
                // env runs the command unchanged, so the fake claude still answers; the marker proves the wrap happened.
                List<String> wrapped = new ArrayList<>(List.of("env", "SANDBOXED_BY=recording"));
                wrapped.addAll(commandLine);
                return wrapped;
            }
        };
        Confinement confinement = new Confinement(recording, new SandboxPolicies(workdir, workdir.resolve("state"), List.of()));
        ClaudeCodeAgent confined = new ClaudeCodeAgent(FakeClaude.install(dir).toString(), FakeClaude.environment(),
                Duration.ofSeconds(2), confinement);
        RunRequest request = new RunRequest(RunKind.PLAN, workdir, "Plan it", SESSION, false, List.of(), null, null, null,
                dir.resolve("runs/1/1"));

        AgentResult result = confined.start(request).await();

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
        assertTrue(Files.readString(workdir.resolve("fake-claude.env")).contains("SANDBOXED_BY=recording"));
        assertEquals(new SandboxUse("recording", null), result.sandbox());
    }
```

If `FakeClaude.install(dir)` refuses to install twice into the same `dir`, give the confined agent its own `dir.resolve("confined")` directory.

Add to `src/test/java/dispatch/core/JobJsonTest.java`:

```java
    @Test
    void aResultWithItsSandboxSurvivesJsonUnchanged() throws Exception {
        AgentResult agent = new AgentResult(AgentOutcome.SUCCEEDED, 0, "s1", null, "done", new BigDecimal("0.12"), 3,
                List.of(), null, "claude-sonnet-5", null).withSandbox(new SandboxUse("none", "not available on Windows"));
        JobResult result = JobResult.succeeded(agent);

        assertEquals(result, Json.MAPPER.readValue(Json.write(result), JobResult.class));
    }

    @Test
    void aResultWithoutSandboxStillReads() throws Exception {
        // A worker from before the sandbox sends no "sandbox" field; the team machine must still read its results.
        ObjectNode json = (ObjectNode) Json.MAPPER.readTree(Json.write(JobResult.succeeded(new AgentResult(AgentOutcome.SUCCEEDED, 0,
                "s1", null, "done", null, null, List.of(), null, null, null))));
        ((ObjectNode) json.get("agent")).remove("sandbox");

        JobResult read = Json.MAPPER.treeToValue(json, JobResult.class);

        assertEquals(null, read.agent().sandbox());
    }
```

(Add `import dispatch.agent.SandboxUse;`.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -Dtest='ConfinementTest,ClaudeCodeAgentTest,JobJsonTest' test`
Expected: compilation FAIL, `cannot find symbol: class Confinement` / `SandboxUse`.

- [ ] **Step 3: Write the implementation**

`src/main/java/dispatch/agent/SandboxUse.java`:

```java
package dispatch.agent;

/**
 * The sandbox a run ran in, as the machine that ran it reports it.
 *
 * @param name              "bubblewrap", or "none"
 * @param unsandboxedReason why the run was not isolated; null when it was
 */
public record SandboxUse(String name, String unsandboxedReason) {
}
```

`AgentResult.java` — add the component, keep the old constructor, add the wither:

```java
 * @param requestedModel   the model the run asked for, only when an answer came from another one; null otherwise
 * @param sandbox          the sandbox the run ran in; null for results built without an agent process (tests, older workers)
 */
public record AgentResult(
        AgentOutcome outcome,
        int exitCode,
        String sessionId,
        String structuredOutput,
        String summary,
        BigDecimal costUsd,
        Integer turns,
        List<String> denials,
        String error,
        String model,
        String requestedModel,
        SandboxUse sandbox) {

    /** A result as each agent's parser builds it; {@link ProcessRun} adds the sandbox it ran in. */
    public AgentResult(AgentOutcome outcome, int exitCode, String sessionId, String structuredOutput, String summary,
                       BigDecimal costUsd, Integer turns, List<String> denials, String error, String model,
                       String requestedModel) {
        this(outcome, exitCode, sessionId, structuredOutput, summary, costUsd, turns, denials, error, model, requestedModel, null);
    }

    public AgentResult withSandbox(SandboxUse sandbox) {
        return new AgentResult(outcome, exitCode, sessionId, structuredOutput, summary, costUsd, turns, denials, error, model,
                requestedModel, sandbox);
    }
}
```

If `JobJsonTest` then fails with a Jackson creator conflict, mark the canonical constructor explicitly: add an explicit canonical constructor annotated `@com.fasterxml.jackson.annotation.JsonCreator` with the 12 parameters assigning nothing (a compact form `@JsonCreator public AgentResult {}` is not allowed on records with annotations in all Jackson versions, so write it as the full canonical form with `this.x = x` assignments).

`src/main/java/dispatch/agent/sandbox/Confinement.java`:

```java
package dispatch.agent.sandbox;

import dispatch.agent.RunRequest;
import dispatch.agent.SandboxUse;
import java.util.List;

/** This machine's sandbox and what each run may touch in it; one per process, shared by every agent. */
public record Confinement(Sandbox sandbox, SandboxPolicies policies) {

    /** No sandbox, for a reason every run records; needs no policies. */
    public static Confinement none(String reason) {
        return new Confinement(new NoSandbox(reason), null);
    }

    public List<String> wrap(List<String> commandLine, RunRequest request, List<String> agentStateInHome) {
        if (sandbox.unavailableReason() != null) {
            return List.copyOf(commandLine);
        }
        return sandbox.wrap(commandLine, policies.forRun(request, agentStateInHome));
    }

    public SandboxUse use() {
        return new SandboxUse(sandbox.name(), sandbox.unavailableReason());
    }
}
```

`ProcessRun.java` — new parameters on `start`, a field for the use, and `await` stamps it:

```java
    private final SandboxUse sandbox;

    private ProcessRun(Process process, OutputParser parser, Path stdoutLog, Path stderrLog, Duration cancelGrace,
                       SandboxUse sandbox) {
        // ... existing assignments ...
        this.sandbox = sandbox;
    }

    /**
     * Starts {@code commandLine}, inside {@code confinement}'s sandbox, in the run's workdir with {@code environment} plus
     * the run's own variables, minus Dispatch's secrets, and writes {@code prompt} to its stdin. ...
     *
     * @param agent            names the agent in the log, e.g. "codex"
     * @param agentStateInHome the agent's own files relative to home, which its sandbox leaves writable
     */
    public static ProcessRun start(String agent, List<String> commandLine, RunRequest request, Map<String, String> environment,
                                   String prompt, OutputParser parser, Duration cancelGrace, Confinement confinement,
                                   List<String> agentStateInHome) {
        List<String> confined = confinement.wrap(commandLine, request, agentStateInHome);
        ProcessBuilder builder = new ProcessBuilder(confined).directory(request.workdir().toFile());
        // ... unchanged up to builder.start(), with the AgentStartException message using confined.getFirst() ...
        Log.info("agent.started", "agent", agent, "pid", process.pid(), "kind", request.kind(),
                "workdir", request.workdir(), "resume", request.resume(), "sandbox", confinement.sandbox().name());
        ProcessRun run = new ProcessRun(process, parser, stdoutLog, stderrLog, cancelGrace, confinement.use());
        writePrompt(process, prompt);
        return run;
    }

    @Override
    public AgentResult await() throws InterruptedException {
        int exitCode = process.waitFor();
        stdoutReader.join();
        return parser.result(exitCode, stderrTail()).withSandbox(sandbox);
    }
```

`ClaudeCodeAgent.java`:

```java
    /** Claude Code's login, settings and sessions: writable in the sandbox, or every run would fail to save its session. */
    private static final List<String> STATE_IN_HOME = List.of(".claude", ".claude.json");

    private final Confinement confinement;

    /** @param environment the base environment for agent processes, normally {@code System.getenv()} */
    public ClaudeCodeAgent(String command, Map<String, String> environment, Duration cancelGrace) {
        this(command, environment, cancelGrace, Confinement.none("no sandbox configured"));
    }

    public ClaudeCodeAgent(String command, Map<String, String> environment, Duration cancelGrace, Confinement confinement) {
        this.command = command;
        this.environment = Map.copyOf(environment);
        this.cancelGrace = cancelGrace;
        this.confinement = confinement;
    }

    @Override
    public RunHandle start(RunRequest request) {
        String permissionMode = permissionMode(request.kind());
        return ProcessRun.start("claude-code", commandLine(request, permissionMode), request, environment, request.prompt(),
                new StreamParser(permissionMode, request.model(), request.workdir()), cancelGrace, confinement, STATE_IN_HOME);
    }
```

`CodexAgent.java`: add `private static final List<String> STATE_IN_HOME = List.of(".codex");`, a `Confinement confinement` field, a 5-arg constructor `(String command, Map<String, String> environment, Duration cancelGrace, Path sessionsDir, Confinement confinement)` holding the current body, the old 4-arg constructor delegating with `Confinement.none("no sandbox configured")`, and pass `confinement, STATE_IN_HOME` as the last two arguments of its `ProcessRun.start(...)` call.

`GeminiAgent.java`: the same with `List.of(".gemini")` and a 4-arg constructor `(String command, Map<String, String> environment, Duration cancelGrace, Confinement confinement)`.

`Agents.java`:

```java
    public static Map<String, Agent> create(Map<String, String> commands, Map<String, String> environment, Path stateDir) {
        return create(commands, environment, stateDir, Confinement.none("no sandbox configured"));
    }

    /** @param confinement this machine's sandbox, shared by every agent it runs */
    public static Map<String, Agent> create(Map<String, String> commands, Map<String, String> environment, Path stateDir,
                                            Confinement confinement) {
        Map<String, Agent> agents = new LinkedHashMap<>();
        commands.forEach((type, command) -> agents.put(type, switch (type) {
            case "claude-code" -> new ClaudeCodeAgent(command, environment, CANCEL_GRACE, confinement);
            case "codex" -> new CodexAgent(command, environment, CANCEL_GRACE, stateDir.resolve("agent-sessions").resolve("codex"), confinement);
            case "gemini" -> new GeminiAgent(command, environment, CANCEL_GRACE, confinement);
            default -> throw new IllegalArgumentException("unsupported agent type: " + type);
        }));
        return Map.copyOf(agents);
    }
```

- [ ] **Step 4: Run the tests to verify they pass, then the whole suite**

Run: `./mvnw -q -Dtest='ConfinementTest,ClaudeCodeAgentTest,CodexAgentTest,GeminiAgentTest,JobJsonTest' test`
Expected: PASS.
Run: `./mvnw -q test`
Expected: PASS (nothing else changed behaviour: every other agent is built through the old constructors).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/agent src/test/java/dispatch/agent src/test/java/dispatch/core/JobJsonTest.java
git commit -m "Every agent process starts through this machine's sandbox, and its result says which sandbox it ran in or why it ran in none

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: A real bubblewrap run proves the policy, in CI too

**Files:**
- Test: `src/test/java/dispatch/agent/sandbox/BubblewrapSandboxTest.java`
- Modify: `.github/workflows/ci.yml` (Linux step before "Build and test")

**Interfaces:**
- Consumes: `Sandboxes.detect`, `Probe.system`, `SandboxPolicies`, `Bubblewrap` (Tasks 1–3).
- Produces: nothing new.

- [ ] **Step 1: Write the test**

```java
package dispatch.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dispatch.agent.RunRequest;
import dispatch.domain.RunKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The real bwrap with the real policy: what the unit tests only assert as argument lists. */
@EnabledOnOs(OS.LINUX)
class BubblewrapSandboxTest {

    @TempDir
    Path root;
    Sandbox sandbox;
    Path home;
    Path stateDir;
    Path worktree;

    @BeforeEach
    void setUp() throws Exception {
        sandbox = Sandboxes.detect(SandboxSetting.AUTO, Probe.system(System.getenv()));
        assumeTrue(sandbox instanceof Bubblewrap, () -> "no working bwrap here: " + sandbox.unavailableReason());
        home = Files.createDirectories(root.resolve("home"));
        Files.writeString(Files.createDirectories(home.resolve(".ssh")).resolve("id_test"), "SECRET");
        stateDir = Files.createDirectories(root.resolve("state"));
        Path clone = stateDir.resolve("repos/alm");
        git(root, "init", "-q", "-b", "main", clone.toString());
        git(clone, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "init");
        worktree = stateDir.resolve("worktrees/7");
        git(clone, "worktree", "add", "-q", "-b", "dispatch/7", worktree.toString());
    }

    @Test
    void theAgentWritesItsWorktreeUsesGitAndSeesNoSecretsOrHome() throws Exception {
        // In the worktree: /tmp is a fresh tmpfs inside the sandbox, so a script under the test's /tmp dir would vanish.
        Path script = worktree.resolve("agent.sh");
        Files.writeString(script, """
                #!/bin/sh
                echo made > made.txt
                git status --porcelain > status.txt 2>&1 || echo "git failed" > status.txt
                if [ -e "$1/.ssh/id_test" ]; then echo visible > ssh.txt; else echo hidden > ssh.txt; fi
                if touch "$1/escaped" 2>/dev/null; then echo wrote > home.txt; else echo refused > home.txt; fi
                """);
        script.toFile().setExecutable(true);
        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of(stateDir))
                .forRun(new RunRequest(RunKind.EXECUTE, worktree, "p", UUID.randomUUID(), false, List.of(), null, null, null,
                        worktree.resolve("run")), List.of(".claude"));

        Process process = new ProcessBuilder(sandbox.wrap(List.of(script.toString(), home.toString()), policy))
                .directory(worktree.toFile()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);

        assertEquals("made", Files.readString(worktree.resolve("made.txt")).strip());
        assertTrue(Files.readString(worktree.resolve("status.txt")).contains("made.txt"), "git works in the worktree");
        assertEquals("hidden", Files.readString(worktree.resolve("ssh.txt")).strip());
        assertEquals("refused", Files.readString(worktree.resolve("home.txt")).strip());
        assertFalse(Files.exists(home.resolve("escaped")));
    }

    @Test
    void cancellingEndsTheSandboxAndEverythingInIt() throws Exception {
        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of())
                .forRun(new RunRequest(RunKind.PLAN, worktree, "p", UUID.randomUUID(), false, List.of(), null, null, null,
                        worktree.resolve("run")), List.of());
        Process process = new ProcessBuilder(sandbox.wrap(List.of("sh", "-c", "sleep 300 & sleep 300"), policy)).start();
        Thread.sleep(500);
        List<ProcessHandle> tree = process.toHandle().descendants().toList();

        dispatch.ProcessTrees.terminate(process.toHandle(), java.time.Duration.ofSeconds(2));

        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        tree.forEach(child -> assertFalse(child.isAlive(), "left running: " + child.pid()));
    }

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process git = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        assertTrue(git.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, git.exitValue(), new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
}
```

Check `dispatch.ProcessTrees.terminate(ProcessHandle, Duration)` is public with that signature (it is called that way in `ProcessRun.cancel`); if it blocks until done, the test above is correct as is.

- [ ] **Step 2: Run it on the laptop (bwrap 0.12 is installed)**

Run: `./mvnw -q -Dtest=BubblewrapSandboxTest test`
Expected: PASS (2 tests). If the first fails on `git status`, the git common dir mount is wrong: fix `SandboxPolicies.gitCommonDir`, not the test.

- [ ] **Step 3: Let the Linux CI job run it**

In `.github/workflows/ci.yml`, in the Java matrix job, add before the `Build and test` step:

```yaml
      - name: Allow bubblewrap sandboxes (Linux)
        if: runner.os == 'Linux'
        # Ubuntu 24 refuses unprivileged user namespaces by default; the agent sandbox test needs them.
        run: |
          sudo apt-get install -y bubblewrap
          sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0
```

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dispatch/agent/sandbox/BubblewrapSandboxTest.java .github/workflows/ci.yml
git commit -m "A real bubblewrap sandbox lets an agent write its worktree and use git but not see ~/.ssh or write the home, and ends with its whole tree; CI's Linux job runs it

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: The `sandbox` setting, wiring at startup, and `dispatch check`

**Files:**
- Modify: `src/main/java/dispatch/config/Config.java:14-28` (add component + convenience constructor)
- Modify: `src/main/java/dispatch/config/ConfigLoader.java:64-95` (validate) and `:459-472` (`ConfigFile.sandbox`)
- Modify: `src/main/java/dispatch/worker/WorkerConfig.java` (add component + keep the 7- and 9-arg constructors)
- Modify: `src/main/java/dispatch/worker/WorkerConfigLoader.java:34-60` (`WorkerFile.sandbox`, validate)
- Modify: `src/main/java/dispatch/App.java:134-136`
- Modify: `src/main/java/dispatch/worker/WorkerCommand.java:154`
- Modify: `src/main/java/dispatch/cli/Checks.java:76-105`
- Modify: `src/main/java/dispatch/worker/WorkerChecks.java:44-72`
- Modify: `src/main/resources/texts_en.properties`, `src/main/resources/texts_mn.properties`
- Test: `src/test/java/dispatch/config/ConfigLoaderTest.java`, `src/test/java/dispatch/worker/WorkerConfigLoaderTest.java`, `src/test/java/dispatch/cli/ChecksTest.java` (add tests; each already exists — if a name differs, use the class that tests that loader/check)

**Interfaces:**
- Consumes: `SandboxSetting.fromConfig`, `Sandboxes.detect`, `Probe.system`, `SandboxPolicies`, `Confinement`, `Agents.create(…, Confinement)` (Tasks 1–4).
- Produces:
  - `Config.sandbox()` → `SandboxSetting` (last component); `WorkerConfig.sandbox()` → `SandboxSetting` (last component).
  - `static Confinement Confinements.of(Sandbox sandbox, Path stateDir, Path configFile, List<Path> projectClones)` in `dispatch.agent.sandbox` — builds `SandboxPolicies(Path.of(System.getProperty("user.home")), stateDir, [configDir, stateDir, clones…])`.
  - `static Checks.Finding Checks.sandbox(Sandbox sandbox)`.

- [ ] **Step 1: Write the failing tests**

In `ConfigLoaderTest` (its `VALID` constant is a minimal valid config, `write(yaml)` saves it as `backend.yaml`, and `ENV` holds the bot token):

```java
    @Test
    void sandboxIsAutoUnlessTurnedOff() throws Exception {
        assertEquals(SandboxSetting.AUTO, ConfigLoader.load(write(VALID), ENV).sandbox());
        assertEquals(SandboxSetting.OFF, ConfigLoader.load(write(VALID + "sandbox: off\n"), ENV).sandbox());
    }

    @Test
    void anUnknownSandboxSettingIsRefused() throws Exception {
        Path file = write(VALID + "sandbox: on\n");

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(file, ENV));

        assertTrue(error.getMessage().contains("sandbox: auto or off, not on"), error.getMessage());
    }
```

In `WorkerConfigLoaderTest` (its `write(yaml)` saves a `worker.yaml` under `dir`):

```java
    private static final String MINIMAL_WORKER = """
            team: https://team.example.com
            name: ann-laptop
            claudeCommand: /usr/local/bin/claude
            projects: {}
            """;

    @Test
    void workerSandboxIsAutoUnlessTurnedOff() throws Exception {
        assertEquals(SandboxSetting.AUTO, WorkerConfigLoader.load(write(MINIMAL_WORKER)).sandbox());
        assertEquals(SandboxSetting.OFF, WorkerConfigLoader.load(write(MINIMAL_WORKER + "sandbox: off\n")).sandbox());
    }

    @Test
    void anUnknownWorkerSandboxSettingIsRefused() throws Exception {
        Path file = write(MINIMAL_WORKER + "sandbox: on\n");

        ConfigException error = assertThrows(ConfigException.class, () -> WorkerConfigLoader.load(file));

        assertTrue(error.getMessage().contains("sandbox: auto or off, not on"), error.getMessage());
    }
```

(If the loader refuses `projects: {}`, copy the `projects:` block of `aWorkerConfigMapsProjectsToLocalClones` into `MINIMAL_WORKER`.)

In `ChecksTest`:

```java
    @Test
    void sandboxFindingSaysWhichOrWhyNone() {
        Checks.Finding ok = Checks.sandbox(new Bubblewrap("/usr/bin/bwrap"));
        assertEquals(Checks.Level.OK, ok.level());
        assertEquals("sandbox", ok.area());
        assertEquals("sandbox: bubblewrap", ok.message().english());

        Checks.Finding none = Checks.sandbox(new NoSandbox("bubblewrap (bwrap) is not installed"));
        assertEquals(Checks.Level.WARN, none.level());
        assertTrue(none.message().english().contains("bubblewrap (bwrap) is not installed"), none.message().english());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -Dtest='ConfigLoaderTest,WorkerConfigLoaderTest,ChecksTest' test`
Expected: compilation FAIL, `cannot find symbol: method sandbox()`.

- [ ] **Step 3: Write the implementation**

`Config.java` — add `SandboxSetting sandbox` after `Secrets secrets`, and a constructor with the 13 existing components that passes `SandboxSetting.AUTO`, so the 6 existing `new Config(` calls compile unchanged.

`ConfigLoader.java` — `ConfigFile` gets `String sandbox` as its last component; in `load`:

```java
        Optional<SandboxSetting> sandbox = SandboxSetting.fromConfig(raw.sandbox());
        if (sandbox.isEmpty()) {
            errors.add(Text.of("config.sandbox", raw.sandbox()));
        }
        // ... after the errors check:
        return new Config(raw.team(), stateDir, telegram, raw.scheduler(), worktrees, raw.limits(), Map.copyOf(agents),
                projects, delivery, workers, miniApp, raw.branchPrefix(), new Config.Secrets(token, ghToken), sandbox.orElseThrow());
```

`texts_en.properties`:

```
config.sandbox=sandbox: auto or off, not {0}
check.sandbox=sandbox: {0}
check.sandboxNone=sandbox: none, agents run unsandboxed ({0})
```

`texts_mn.properties`:

```
config.sandbox=sandbox: auto эсвэл off байна, {0} биш
check.sandbox=sandbox: {0}
check.sandboxNone=sandbox: байхгүй, агент sandbox-гүй ажиллана ({0})
```

`WorkerConfig.java` — add `SandboxSetting sandbox` as the last component; the existing 9-arg constructor becomes a convenience one passing `SandboxSetting.AUTO`, and the 7-arg one keeps delegating to it.
`WorkerConfigLoader.java` — `WorkerFile` gets `String sandbox`; validate with `errors.add("sandbox: auto or off, not " + raw.sandbox())` in the same style as `maxConcurrentRuns`, and pass `sandbox.orElseThrow()` to the full constructor.

Create `src/main/java/dispatch/agent/sandbox/Confinements.java`:

```java
package dispatch.agent.sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The one place a process turns its sandbox and its own files into the Confinement its agents share. */
public final class Confinements {

    private Confinements() {
    }

    /**
     * @param configFile    the instance's or worker's config; its directory holds every instance's secrets file
     * @param projectClones the clones configured here, each hidden from runs of the others
     */
    public static Confinement of(Sandbox sandbox, Path stateDir, Path configFile, List<Path> projectClones) {
        List<Path> dispatchPrivate = new ArrayList<>();
        dispatchPrivate.add(configFile.toAbsolutePath().getParent());
        dispatchPrivate.add(stateDir.toAbsolutePath());
        projectClones.forEach(clone -> dispatchPrivate.add(clone.toAbsolutePath()));
        return new Confinement(sandbox, new SandboxPolicies(Path.of(System.getProperty("user.home")), stateDir.toAbsolutePath(), dispatchPrivate));
    }
}
```

`App.java` — replace the `Agents.create(agentCommands, environment, stateDir)` line:

```java
        Sandbox sandbox = Sandboxes.detect(config.sandbox(), Probe.system(environment));
        if (sandbox.unavailableReason() == null) {
            Log.info("sandbox.selected", "name", sandbox.name());
        } else {
            Log.warn("sandbox.unavailable", "reason", sandbox.unavailableReason());
        }
        List<Path> clones = config.projects().stream().map(Config.Project::path).filter(java.util.Objects::nonNull).map(Path::of).toList();
        Map<String, Agent> agents = Agents.create(agentCommands, environment, stateDir,
                Confinements.of(sandbox, stateDir, configFile, clones));
```

(`Config.Project.path()` is a nullable `String`; a project without one is cloned under `stateDir/repos`, which the state dir already hides.)

`WorkerCommand.java:154` — the same five lines of detection and logging, then:

```java
            List<Path> clones = config.projects().values().stream().map(WorkerConfig.Project::path).map(Path::of).toList();
            Map<String, Agent> agents = Agents.create(config.agentCommands(), environment, config.stateDir(),
                    Confinements.of(sandbox, config.stateDir(), options.workerFile(), clones));
```

`Checks.java` — add the public helper and call it in `run` right after the agents' checks:

```java
    /** Which sandbox this machine's agents run in; never a failure, since runs go ahead without one (spec). */
    public static Finding sandbox(Sandbox sandbox) {
        return sandbox.unavailableReason() == null
                ? new Finding(Level.OK, "sandbox", Text.of("check.sandbox", sandbox.name()))
                : new Finding(Level.WARN, "sandbox", Text.of("check.sandboxNone", sandbox.unavailableReason()));
    }
```

```java
        config.agents().forEach((name, agent) -> checkAgent(run, name, agent.command(), configFile, team));
        Finding sandbox = sandbox(Sandboxes.detect(config.sandbox(), Probe.system(prepared.environment())));
        run.add(sandbox.level(), sandbox.area(), sandbox.message());
```

`WorkerChecks.java` — after `checkClaude(config, add);`:

```java
        add.accept(Checks.sandbox(Sandboxes.detect(config.sandbox(), Probe.system(environment.values()))));
```

- [ ] **Step 4: Run the tests, then the whole suite and the check command**

Run: `./mvnw -q -Dtest='ConfigLoaderTest,WorkerConfigLoaderTest,ChecksTest' test` — Expected: PASS.
Run: `./mvnw -q test` — Expected: PASS.
Run: `./mvnw -q -DskipTests package && java -jar target/dispatch.jar check --config ~/.config/dispatch/dispatch.yaml | grep sandbox`
Expected: `sandbox: bubblewrap` with an OK mark. (Adjust the jar name to what `target/` holds.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/config src/main/java/dispatch/worker src/main/java/dispatch/App.java src/main/java/dispatch/cli/Checks.java src/main/java/dispatch/agent/sandbox/Confinements.java src/main/resources/texts_en.properties src/main/resources/texts_mn.properties src/test/java
git commit -m "Dispatch and its workers sandbox their agents unless sandbox: off, log which sandbox they chose or why none, and dispatch check shows it

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Each run records its sandbox, and an unsandboxed plan or result says so

**Files:**
- Create: `src/main/resources/db/029-run-sandbox.sql`
- Modify: `src/main/java/dispatch/store/Database.java:45` (`MIGRATIONS`)
- Modify: `src/main/java/dispatch/store/Runs.java:41-51` (`Finish`) and `:309-316` (`finish`)
- Modify: `src/main/java/dispatch/core/RunTransitions.java:166-205` (`finishRun`, `putRunDetails`)
- Modify: `src/main/java/dispatch/telegram/Renderer.java:560`, `:710`, `:1045`
- Modify: `src/main/resources/messages_mn.properties`
- Test: `src/test/java/dispatch/telegram/RendererTest.java`
- Test: `src/test/java/dispatch/core/TaskLifecycleTest.java` (one test)

**Interfaces:**
- Consumes: `AgentResult.sandbox()` → `SandboxUse` (Task 4).
- Produces: column `run.sandbox TEXT` (NULL before 029); `Runs.Finish` gains a last component `String sandbox`; payload field `unsandboxed` (string reason) on plan-ready and completed payloads when the run was not isolated.

- [ ] **Step 1: Write the failing tests**

`RendererTest.java`:

```java
    @Test
    void anUnsandboxedPlanSaysWhyUnderItsFooter() {
        ObjectNode payload = planPayload(List.of("Raise the timeout"), List.of()).put("unsandboxed", "not available on <Windows>");

        String html = renderer.render(OutboxKind.PLAN_READY, payload).html();

        assertTrue(html.contains("⚠️ Sandbox-гүй ажилласан: not available on &lt;Windows&gt;"), html);
    }

    @Test
    void aSandboxedOrOlderPlanHasNoSandboxLine() {
        String html = renderer.render(OutboxKind.PLAN_READY, planPayload(List.of("Raise the timeout"), List.of())).html();

        assertFalse(html.contains("Sandbox-гүй"), html);
    }
```

```java
    @Test
    void anUnsandboxedResultSaysWhyUnderItsFooter() {
        ObjectNode payload = completedPayload("https://github.com/acme/alm/pull/7", 2, List.of())
                .put("unsandboxed", "bubblewrap (bwrap) is not installed");

        String html = renderer.render(OutboxKind.TASK_COMPLETED, payload).html();

        assertTrue(html.contains("⚠️ Sandbox-гүй ажилласан: bubblewrap (bwrap) is not installed"), html);
    }
```

(`completedPayload(prUrl, files, denials)` is the helper `completedTaskLinksThePullRequestWithSummaryDenialsCostAndDuration` uses; if it returns `JsonNode`, cast to `ObjectNode`.)

`TaskLifecycleTest.java` (its helpers: `create(BOLD, "alm", text, messageId)`, `claim()`, `transitions.planSucceeded(id, seq, PLAN, result)`, `agentResult(denials)`, `row(sql, params…)`; add `import dispatch.agent.SandboxUse;`):

```java
    @Test
    void anUnsandboxedPlanRunIsRecordedAndItsPlanSaysWhy() {
        long id = create(BOLD, "alm", "Fix login timeout", "20");
        ClaimedRun run = claim();

        transitions.planSucceeded(id, run.seq(), PLAN,
                agentResult(List.of()).withSandbox(new SandboxUse("none", "not available on Windows")));

        assertEquals("none", row("SELECT sandbox FROM run WHERE task_id = ?", id).get("sandbox"));
        JsonNode payload = Json.read(row("SELECT * FROM outbox WHERE kind = 'PLAN_READY'").get("payload"));
        assertEquals("not available on Windows", payload.get("unsandboxed").asText());
    }

    @Test
    void aSandboxedPlanRunIsRecordedWithoutAWarning() {
        long id = create(BOLD, "alm", "Fix login timeout", "20");
        ClaimedRun run = claim();

        transitions.planSucceeded(id, run.seq(), PLAN, agentResult(List.of()).withSandbox(new SandboxUse("bubblewrap", null)));

        assertEquals("bubblewrap", row("SELECT sandbox FROM run WHERE task_id = ?", id).get("sandbox"));
        JsonNode payload = Json.read(row("SELECT * FROM outbox WHERE kind = 'PLAN_READY'").get("payload"));
        assertFalse(payload.has("unsandboxed"));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -Dtest='RendererTest,TaskLifecycleTest' test`
Expected: FAIL — no ⚠️ line; `no such column: sandbox`.

- [ ] **Step 3: Write the implementation**

`src/main/resources/db/029-run-sandbox.sql`:

```sql
-- Spec 2026-09-30-agent-sandbox-design: the sandbox a run's agent ran in ("bubblewrap" or "none"); NULL for runs
-- from before it and for runs without an agent (DELIVER).
ALTER TABLE run ADD COLUMN sandbox TEXT;
```

Register it: in `src/main/java/dispatch/store/Database.java:45`, append `"db/029-run-sandbox.sql"` to `MIGRATIONS` after `"db/028-worker-agent.sql"`.

`Runs.java` — `Finish` gets `String sandbox` last; the `UPDATE` sets `model = ?, sandbox = ?` and passes `finish.sandbox()` after `finish.model()`.

`RunTransitions.finishRun` — the `Runs.Finish` construction gains
`result == null || result.sandbox() == null ? null : result.sandbox().name()` as the last argument, and after the model-differs warning:

```java
        if (result != null && result.sandbox() != null && result.sandbox().unsandboxedReason() != null) {
            tx.afterCommit(() -> Log.warn("agent.unsandboxed", "task", run.taskId(), "run", run.seq(),
                    "reason", result.sandbox().unsandboxedReason()));
        }
```

`RunTransitions.putRunDetails` — after the `requestedModel` block:

```java
        if (result.sandbox() != null && result.sandbox().unsandboxedReason() != null) {
            payload.put("unsandboxed", result.sandbox().unsandboxedReason());
        }
```

`Renderer.java` — next to `modelWarning`:

```java
    /** A line saying the run's agent was not sandboxed, and why (spec: 2026-09-30-agent-sandbox-design). */
    private String sandboxWarning(JsonNode payload) {
        if (!payload.hasNonNull("unsandboxed")) {
            return "";
        }
        return "\n" + format("run.unsandboxed", escape(payload.get("unsandboxed").asText()));
    }
```

and at lines 560 and 710 change `.append(modelWarning(payload));` to `.append(modelWarning(payload)).append(sandboxWarning(payload));`.

`messages_mn.properties`, beside `run.modelDiffers`:

```
run.unsandboxed=⚠️ Sandbox-гүй ажилласан: {0}
```

- [ ] **Step 4: Run the tests, then the whole suite**

Run: `./mvnw -q -Dtest='RendererTest,TaskLifecycleTest' test` — Expected: PASS.
Run: `./mvnw -q test` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/029-run-sandbox.sql src/main/java/dispatch/store/Runs.java src/main/java/dispatch/core/RunTransitions.java src/main/java/dispatch/telegram/Renderer.java src/main/resources/messages_mn.properties src/test/java
git commit -m "Each run records the sandbox its agent ran in, and a plan or result from an unsandboxed run says so and why

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: ADR 0032 and the documentation

**Files:**
- Create: `docs/adr/0032-agent-runs-are-sandboxed-by-default.md`
- Modify: `docs/adr/0009-agent-permissions-plan-mode-then-auto-mode.md` (one "Amended by ADR 0032" line)
- Modify: `docs/ARCHITECTURE.md` ("Agent boundary", "Decisions at a glance")
- Modify: `SECURITY.md`
- Modify: `README.md`, `README.en.md`
- Modify: `docs/superpowers/specs/2026-09-30-agent-sandbox-design.md` (as-built amendments)

- [ ] **Step 1: Write ADR 0032**

```markdown
# Agent runs are sandboxed by default

Amends ADR 0009, which left an OS sandbox as the next hardening step.

Every agent process Dispatch starts, for a plan, an execution, a split or an assistant turn, runs inside bubblewrap on
Linux. The whole root is read-only; the run's worktree, its clone's git dir, the agent's own state (`~/.claude`,
`~/.codex`, `~/.gemini`) and the build caches (`~/.m2`, `~/.gradle`, `~/.npm`, `~/.cache`) are writable; `/tmp` is fresh
per run; `~/.ssh`, `~/.config/gh`, `~/.gnupg`, Dispatch's config and state and every configured clone are hidden. The
network stays open. Permission modes and deny rules are unchanged: the sandbox is a layer under them.

A machine that cannot sandbox (no bwrap, user namespaces blocked, macOS, Windows, or `sandbox: off`) runs its agents as
before. It says so in `dispatch check`, at startup, and under every plan and result of such a run, so an unsandboxed run
is never silent.

We chose a wrapper Dispatch builds itself over each agent's own sandbox (three behaviours, no single policy), a user-written
wrapper command (nobody would get a sandbox by default) and Docker (an image per project, logins inside the container,
slower builds on macOS and Windows).

## Consequences

- An agent can no longer read the owner's SSH keys, GitHub token or other repositories, nor write outside its task.
- It can still read its own login (it needs it), the worktree's code, and anything else readable under the root, and it
  can still reach the network: the sandbox narrows what a prompt injection can take, it does not remove it.
- A poisoned build cache is possible, since caches are writable; accepted on a developer's own machine.
- A worker reports the sandbox with each result, and a team machine from before this version refuses such a result:
  upgrade the team machine before its workers.
- macOS gets its own sandbox (Seatbelt) only once someone can run it on a Mac.
```

- [ ] **Step 2: Update the other documents**

- ADR 0009: under its title add `Amended by ADR 0032: agent runs are sandboxed by default where the machine can.`
- ARCHITECTURE.md "Agent boundary": after the Gemini table, add a paragraph "Every agent's command line is wrapped by this machine's `Sandbox` (ADR 0032)…" with the policy table from the spec and the note that the result carries `SandboxUse`; add ADR 0032 to "Decisions at a glance".
- SECURITY.md: a "Sandbox" section listing what it protects and what it does not (the Consequences above), and the upgrade order for teams.
- README.md / README.en.md: under the requirements line, "Linux: install `bubblewrap` so agents run sandboxed (`sandbox: off` turns it off)" / the Mongolian equivalent "Linux: агентыг sandbox-д ажиллуулахын тулд `bubblewrap` суулгана уу (`sandbox: off` унтраана)".
- The spec: in "Warnings and recording" replace "in Telegram, the Mini App and `dispatch ui`, in Mongolian and English" with "in Telegram (Mongolian), where the model-mismatch line is; the Mini App and `dispatch ui` show neither yet"; in "The policy" add the assistant row "`dispatch.db`, `-wal`, `-shm` read-write and `assistant-bin` read-only, for the assistant only"; in "Detection" record the trial command as `bwrap --ro-bind / / --unshare-pid --proc /proc true`.

- [ ] **Step 3: Commit**

```bash
git add docs SECURITY.md README.md README.en.md
git commit -m "ADR 0032 records that agent runs are sandboxed by default, amending ADR 0009, with the architecture, security notes, README and the spec as built

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Live check on the personal bot (only with the user's go)

This deploys to the live instance and runs a real task that pushes a draft PR: ask the user first.

- [ ] **Step 1: Build the jar with the UI**

Run: `./mvnw -q -Pui -DskipTests package`
Expected: `target/dispatch*.jar` built.

- [ ] **Step 2: Swap the jar with the services stopped**

```bash
systemctl --user stop dispatch-worker dispatch-team dispatch
cp ~/.local/share/dispatch/dispatch.jar ~/.local/share/dispatch/dispatch.jar.bak-before-sandbox
cp target/dispatch.jar ~/.local/share/dispatch/dispatch.jar
systemctl --user start dispatch dispatch-team dispatch-worker
journalctl --user -u dispatch -n 50 --no-pager | grep -E 'sandbox\.(selected|unavailable)'
```

Expected: `sandbox.selected name=bubblewrap` for `dispatch` (and in the worker's log file for `dispatch-worker`). The team machine is upgraded in the same step as its worker, which is the required order.

- [ ] **Step 3: Run one real task end to end**

The user gives the personal bot a small task on a test project, approves the plan and waits for the draft PR. Then:

```bash
sqlite3 ~/.local/state/dispatch/dispatch.db "SELECT task_id, seq, kind, status, sandbox FROM run ORDER BY rowid DESC LIMIT 3"
```

Expected: the PLAN and EXECUTE runs `SUCCEEDED` with `sandbox = bubblewrap`, no ⚠️ sandbox line in Telegram, and a draft PR.

- [ ] **Step 4: Check the assistant**

The user asks the bot "#<that task> ямар байна?" in the private chat.
Expected: an answer that uses the task's data (so `dispatch ask` read `dispatch.db` inside the sandbox). If it answers without task data, check `~/.local/state/dispatch/assistant-logs/` for a `dispatch ask` error, and fix the assistant mounts in `SandboxPolicies`.

- [ ] **Step 5: Report**

Report the run rows, the PR link and the assistant's answer to the user. Roll back with the `.bak-before-sandbox` jar (stop services, copy back, start) if any step failed.
