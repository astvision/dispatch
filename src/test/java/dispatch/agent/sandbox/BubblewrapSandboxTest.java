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
        // bwrap, sh and both sleeps start asynchronously; an empty tree would make the assertions below vacuous.
        List<ProcessHandle> tree = List.of();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (tree.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(50);
            tree = process.toHandle().descendants().toList();
        }
        assertTrue(tree.size() >= 2, "the sandboxed tree never started: " + tree);

        dispatch.ProcessTrees.terminate(process.toHandle(), java.time.Duration.ofSeconds(2));

        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        tree.forEach(child -> assertFalse(child.isAlive(), "left running: " + child.pid()));
    }

    @Test
    void theAgentCannotRewriteItsWorktreesGitFile() throws Exception {
        // A rewritten .git would decide what the next run in this worktree mounts read-write.
        String before = Files.readString(worktree.resolve(".git"));
        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of(stateDir))
                .forRun(new RunRequest(RunKind.EXECUTE, worktree, "p", UUID.randomUUID(), false, List.of(), null, null, null,
                        worktree.resolve("run")), List.of());

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
                            worktree.resolve("run")), List.of(), Map.of("XDG_RUNTIME_DIR", runtime.toString()));

            Process process = new ProcessBuilder(sandbox.wrap(List.of("sh", "-c",
                    "if [ -e '" + socket + "' ]; then echo visible; else echo hidden; fi"), policy))
                    .redirectErrorStream(true).start();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));

            assertEquals("hidden", new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
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
