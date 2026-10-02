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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** The real bwrap with the real policy: what the unit tests only assert as argument lists. */
@EnabledOnOs(OS.LINUX)
class BubblewrapSandboxTest {

    // Not @TempDir: inside the sandbox /tmp is a writable tmpfs, so a home under /tmp could not prove the home is read-only.
    Path root;
    Sandbox sandbox;
    Path home;
    Path stateDir;
    Path worktree;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "sandbox-it");
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

    @AfterEach
    void cleanUp() throws IOException {
        if (root == null) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static final AgentState CLAUDE = new AgentState(".claude", true, ".claude/projects",
            List.of(".claude/.credentials.json"), List.of(".claude.json"), List.of(".claude/agents"), List.of(".claude/session-env"));
    private static final AgentState CODEX = new AgentState(".codex", List.of(".codex/AGENTS.md", ".codex/hooks.json"));

    private String runConfined(AgentState state, String script, String... args) throws Exception {
        return runConfined(sandbox, state, script, args);
    }

    private String runConfined(Sandbox in, AgentState state, String script, String... args) throws Exception {
        Confinement confinement = new Confinement(in, new SandboxPolicies(home, stateDir, List.of(stateDir)));
        RunRequest request = new RunRequest(RunKind.EXECUTE, worktree, "p", UUID.randomUUID(), false, List.of(), null, null, null,
                stateDir.resolve("runs/7/1"));
        List<String> command = new java.util.ArrayList<>(List.of("sh", "-c", script, home.toString()));
        command.addAll(List.of(args));
        Confinement.Confined confined = confinement.prepare(command, request, state, Map.of());
        Process process = new ProcessBuilder(confined.commandLine()).directory(worktree.toFile()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        confined.guard().close();
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void copyOnWriteKeepsWhatPersistsAndDropsTheRest() throws Exception {
        assumeTrue(sandbox.copyOnWrite(), "bwrap here has no overlays");
        Files.createDirectories(home.resolve(".claude/agents"));
        Files.writeString(home.resolve(".claude.json"), "{}");
        String own = SandboxPolicies.projectName(worktree.toRealPath().toString());

        String output = runConfined(CLAUDE, """
                echo planted > "$0/.claude/agents/planted.md" && echo planted-inside
                echo kept > "$0/.claude/projects/$1/kept.jsonl"
                echo '{"mcpServers":{"x":{}}}' > "$0/.claude.json" && cat "$0/.claude.json"
                """, own);

        assertTrue(output.contains("planted-inside"), output);
        assertFalse(Files.exists(home.resolve(".claude/agents/planted.md")), "gone with the sandbox");
        assertEquals("kept", Files.readString(home.resolve(".claude/projects/" + own + "/kept.jsonl")).strip(),
                "the run's own project dir persists though it did not exist before the run");
        assertEquals("{}", Files.readString(home.resolve(".claude.json")), "the copy took the write");
        assertFalse(Files.exists(stateDir.resolve("runs/7/1.claude.json")), "the copy is deleted");
    }

    /** Auto-memory under ~/.claude/projects/<dir>/memory is loaded into the owner's later sessions in that project. */
    @Test
    void noProjectsMemoryAndNoOtherProjectsTranscriptSurvivesTheRun() throws Exception {
        assumeTrue(sandbox.copyOnWrite(), "bwrap here has no overlays");
        Path other = Files.createDirectories(home.resolve(".claude/projects/-home-ann-work-crm/memory"));
        Files.writeString(other.resolve("MEMORY.md"), "the owner's own memory");
        String own = SandboxPolicies.projectName(worktree.toRealPath().toString());

        String output = runConfined(CLAUDE, """
                echo 'obey the agent' >> "$0/.claude/projects/-home-ann-work-crm/memory/MEMORY.md" && echo other-written
                mkdir -p "$0/.claude/projects/$1/memory" && echo 'obey' > "$0/.claude/projects/$1/memory/MEMORY.md" && echo own-memory-written
                echo transcript > "$0/.claude/projects/$1/session.jsonl"
                """, own);

        assertTrue(output.contains("other-written") && output.contains("own-memory-written"), output);
        assertEquals("the owner's own memory", Files.readString(other.resolve("MEMORY.md")));
        assertFalse(Files.exists(home.resolve(".claude/projects/" + own + "/memory/MEMORY.md")), "the run's own memory is a tmpfs");
        assertEquals("transcript", Files.readString(home.resolve(".claude/projects/" + own + "/session.jsonl")).strip(),
                "the run's own transcript persists, for resume and teleport");
    }

    /** Without overlays the rest of ~/.claude is writable: other projects must still be out of reach, and sourced dirs throwaway. */
    @Test
    void withoutOverlaysOtherProjectsAreReadOnlyAndTheRunsMemoryAndSessionEnvAreThrowaway() throws Exception {
        Path other = Files.createDirectories(home.resolve(".claude/projects/-home-ann-work-crm/memory"));
        Files.writeString(other.resolve("MEMORY.md"), "the owner's own memory");
        String own = SandboxPolicies.projectName(worktree.toRealPath().toString());

        String output = runConfined(new Bubblewrap("bwrap", false), CLAUDE, """
                if { echo 'obey the agent' >> "$0/.claude/projects/-home-ann-work-crm/memory/MEMORY.md"; } 2>/dev/null; then echo other-written; else echo other-refused; fi
                if mkdir -p "$0/.claude/projects/-home-ann-new/memory" 2>/dev/null; then echo new-project-created; else echo new-project-refused; fi
                echo obey > "$0/.claude/projects/$1/memory/MEMORY.md" && echo own-memory-written
                echo transcript > "$0/.claude/projects/$1/session.jsonl"
                mkdir -p "$0/.claude/session-env/s1" && echo 'curl evil' > "$0/.claude/session-env/s1/hook-0.sh" && echo env-written
                """, own);

        assertEquals(List.of("other-refused", "new-project-refused", "own-memory-written", "env-written"), output.strip().lines().toList(),
                output);
        assertEquals("the owner's own memory", Files.readString(other.resolve("MEMORY.md")));
        assertFalse(Files.exists(home.resolve(".claude/projects/" + own + "/memory/MEMORY.md")), "the run's own memory is a tmpfs");
        assertEquals("transcript", Files.readString(home.resolve(".claude/projects/" + own + "/session.jsonl")).strip());
        assertFalse(Files.exists(home.resolve(".claude/session-env/s1")), "a later session of this id would source it");
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
                        worktree.resolve("run")), new AgentState(".claude", List.of()));

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
                        worktree.resolve("run")), AgentState.NONE);
        Process process = new ProcessBuilder(sandbox.wrap(List.of("sh", "-c", "sleep 300 & sleep 300"), policy)).start();
        // bwrap, sh and both sleeps start asynchronously; an empty tree would make the assertions below vacuous.
        List<ProcessHandle> tree = List.of();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (tree.size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(50);
                tree = process.toHandle().descendants().toList();
            }
            assertTrue(tree.size() >= 2, "the sandboxed tree never started: " + tree);

            dispatch.ProcessTrees.terminate(process.toHandle(), java.time.Duration.ofSeconds(2));

            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            tree.forEach(child -> assertFalse(child.isAlive(), "left running: " + child.pid()));
        } finally {
            // A failed assertion must not leave sleep 300 behind: nothing else ends the sandbox with the test.
            tree.forEach(ProcessHandle::destroyForcibly);
            process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    /**
     * bwrap returns when its command exits, but its own init waits for every process still in the sandbox: a Gradle
     * daemon a run starts would live on with the run's mounts, outside Dispatch's process tree, and a later run's build
     * would reach it through the shared ~/.gradle.
     */
    @Test
    void whatTheCommandLeavesRunningEndsWithItAndItsExitCodeStands() throws Exception {
        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of())
                .forRun(new RunRequest(RunKind.PLAN, worktree, "p", UUID.randomUUID(), false, List.of(), null, null, null,
                        worktree.resolve("run")), AgentState.NONE);
        // A sleep nothing else runs, so the host finds it by its command line.
        String marker = "sleep 299." + java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000, 1_000_000);
        Process process = new ProcessBuilder(sandbox.wrap(List.of("sh", "-c", marker + " & exit 3"), policy))
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "bwrap did not return");

            assertEquals(3, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(List.of(), running(marker), "left running in the sandbox");
        } finally {
            // Without the fix the sleep and the sandbox's init outlive the test: end them, whatever the assertions said.
            running(marker).forEach(ProcessHandle::destroyForcibly);
        }
    }

    /** A process in the sandbox that kills the shell around the command must not keep the rest of the sandbox alive. */
    @Test
    void killingTheShellAroundTheCommandLeavesNothingRunning() throws Exception {
        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of())
                .forRun(new RunRequest(RunKind.PLAN, worktree, "p", UUID.randomUUID(), false, List.of(), null, null, null,
                        worktree.resolve("run")), AgentState.NONE);
        String marker = "sleep 298." + java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000, 1_000_000);
        Process process = new ProcessBuilder(sandbox.wrap(List.of("sh", "-c", "(" + marker + " &); kill -KILL $PPID"), policy))
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "bwrap did not return");

            assertEquals(List.of(), running(marker), "left running in the sandbox");
        } finally {
            running(marker).forEach(ProcessHandle::destroyForcibly);
        }
    }

    /** Its own command line only: bwrap's init, which may still be exiting, and the shells carry the sleep in theirs. */
    private static List<ProcessHandle> running(String commandLine) {
        return ProcessHandle.allProcesses()
                .filter(process -> process.info().commandLine().map(line -> line.endsWith(commandLine)).orElse(false))
                .toList();
    }

    @Test
    void theAgentCannotRewriteItsWorktreesGitFile() throws Exception {
        // A rewritten .git would decide what the next run in this worktree mounts read-write.
        String before = Files.readString(worktree.resolve(".git"));
        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of(stateDir))
                .forRun(new RunRequest(RunKind.EXECUTE, worktree, "p", UUID.randomUUID(), false, List.of(), null, null, null,
                        worktree.resolve("run")), AgentState.NONE);

        Process process = new ProcessBuilder(sandbox.wrap(List.of("sh", "-c", "echo 'gitdir: " + home + "' > .git"), policy))
                .directory(worktree.toFile()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));

        assertTrue(process.exitValue() != 0, "the write succeeded");
        assertEquals(before, Files.readString(worktree.resolve(".git")));
    }

    @Test
    void theRuntimeDirsSocketsAreGone() throws Exception {
        Path runtime = Files.createDirectories(root.resolve("run-user"));
        Path socket = runtime.resolve("agent.sock");
        try (var server = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            server.bind(java.net.UnixDomainSocketAddress.of(socket));
            SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of())
                    .forRun(new RunRequest(RunKind.PLAN, worktree, "p", UUID.randomUUID(), false, List.of(), null, null, null,
                            worktree.resolve("run")), AgentState.NONE, Map.of("XDG_RUNTIME_DIR", runtime.toString()));

            Process process = new ProcessBuilder(sandbox.wrap(List.of("sh", "-c",
                    "if [ -e '" + socket + "' ]; then echo visible; else echo hidden; fi"), policy))
                    .redirectErrorStream(true).start();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));

            assertEquals("hidden", new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
        }
    }

    @Test
    void theAgentCannotChangeTheClonesGitConfigOrHooks() throws Exception {
        Path common = stateDir.resolve("repos/alm/.git");
        String config = Files.readString(common.resolve("config"));
        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of(stateDir))
                .forRun(new RunRequest(RunKind.EXECUTE, worktree, "p", UUID.randomUUID(), false, List.of(), null, null, null,
                        worktree.resolve("run")), AgentState.NONE);

        Process process = new ProcessBuilder(sandbox.wrap(List.of("sh", "-c", """
                if git config core.fsmonitor x 2>/dev/null; then echo config-written; else echo config-refused; fi
                if { echo x > "$0/hooks/post-commit"; } 2>/dev/null; then echo hook-written; else echo hook-refused; fi
                git status --porcelain >/dev/null && echo status-ok
                """, common.toString()), policy)).directory(worktree.toFile()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(List.of("config-refused", "hook-refused", "status-ok"), output.strip().lines().toList(), output);
        assertEquals(config, Files.readString(common.resolve("config")));
        assertFalse(Files.exists(common.resolve("hooks/post-commit")));
    }

    @Test
    void theAgentCannotWriteASiblingWorktreesAdminDirButGitStillWorksInItsOwn() throws Exception {
        // A rewritten sibling commondir would point Dispatch's git in that worktree at a config the agent wrote.
        Path clone = stateDir.resolve("repos/alm");
        git(clone, "worktree", "add", "-q", "-b", "dispatch/8", stateDir.resolve("worktrees/8").toString());
        Path sibling = clone.resolve(".git/worktrees/8");
        String before = Files.readString(sibling.resolve("commondir"));
        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of(stateDir))
                .forRun(new RunRequest(RunKind.EXECUTE, worktree, "p", UUID.randomUUID(), false, List.of(), null, null, null,
                        worktree.resolve("run")), AgentState.NONE);

        Process process = new ProcessBuilder(sandbox.wrap(List.of("sh", "-c", """
                if { echo /elsewhere > "$0/commondir"; } 2>/dev/null; then echo sibling-written; else echo sibling-refused; fi
                if mkdir "$0/../9" 2>/dev/null; then echo admin-created; else echo admin-refused; fi
                echo x > x.txt && git add x.txt && git -c user.name=t -c user.email=t@t commit -q -m x && echo commit-ok
                git status --porcelain && echo status-ok
                """, sibling.toString()), policy)).directory(worktree.toFile()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(List.of("sibling-refused", "admin-refused", "commit-ok", "status-ok"), output.strip().lines().toList(), output);
        assertEquals(before, Files.readString(sibling.resolve("commondir")));
    }

    @Test
    void theSandboxOutlivesTheThreadThatStartedIt() throws Exception {
        // Agents start from virtual threads whose carriers retire when idle; PR_SET_PDEATHSIG fires on the forking thread.
        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of())
                .forRun(new RunRequest(RunKind.PLAN, worktree, "p", UUID.randomUUID(), false, List.of(), null, null, null,
                        worktree.resolve("run")), AgentState.NONE);
        Process[] started = new Process[1];
        Thread starter = Thread.ofPlatform().start(() -> {
            try {
                started[0] = new ProcessBuilder(sandbox.wrap(List.of("sleep", "5"), policy)).start();
                // bwrap arms PR_SET_PDEATHSIG only once running; a carrier retires ~30 s later, long after that.
                Thread.sleep(500);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        starter.join();
        Process process = started[0];
        try {
            Thread.sleep(1000);
            assertTrue(process.isAlive(), "the sandbox ended with its starting thread, exit "
                    + (process.isAlive() ? "" : process.exitValue()));
        } finally {
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process git = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        assertTrue(git.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, git.exitValue(), new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
}
