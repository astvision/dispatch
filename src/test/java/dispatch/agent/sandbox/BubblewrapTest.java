package dispatch.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

@DisabledOnOs(value = OS.WINDOWS, disabledReason = "bubblewrap is Linux-only; Windows prints these paths with backslashes")
class BubblewrapTest {

    private static final List<String> CLAUDE = List.of("claude", "-p", "--output-format", "stream-json");

    @Test
    void hidesFirstThenMountsBackWhatTheRunNeedsThenRunsTheCommand() {
        SandboxPolicy policy = new SandboxPolicy(
                Path.of("/state/worktrees/7"),
                Path.of("/state/repos/alm/.git"),
                Path.of("/state/repos/alm/.git/worktrees/7"),
                List.of(Path.of("/state/attachments/7"), Path.of("/state/worktrees/7/.git"), Path.of("/state/repos/alm/.git/config"),
                        Path.of("/state/repos/alm/.git/worktrees"), Path.of("/home/ann/.claude/settings.json")),
                List.of(Path.of("/home/ann/.claude"), Path.of("/home/ann/.m2")),
                List.of(Path.of("/home/ann/.ssh"), Path.of("/state")));

        List<String> wrapped = new Bubblewrap("/usr/bin/bwrap").wrap(CLAUDE, policy);

        assertEquals(List.of("/usr/bin/bwrap",
                "--unshare-pid", "--as-pid-1", "--unshare-ipc", "--new-session",
                "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp",
                "--tmpfs", "/home/ann/.ssh",
                "--tmpfs", "/state",
                "--bind", "/state/worktrees/7", "/state/worktrees/7",
                "--bind", "/state/repos/alm/.git", "/state/repos/alm/.git",
                "--bind-try", "/home/ann/.claude", "/home/ann/.claude",
                "--bind-try", "/home/ann/.m2", "/home/ann/.m2",
                "--ro-bind-try", "/state/attachments/7", "/state/attachments/7",
                // Read-only over the read-write mounts above: order is what makes them read-only.
                "--ro-bind-try", "/state/worktrees/7/.git", "/state/worktrees/7/.git",
                "--ro-bind-try", "/state/repos/alm/.git/config", "/state/repos/alm/.git/config",
                "--ro-bind-try", "/state/repos/alm/.git/worktrees", "/state/repos/alm/.git/worktrees",
                "--ro-bind-try", "/home/ann/.claude/settings.json", "/home/ann/.claude/settings.json",
                // Other worktrees' admin dirs stay read-only; git writes this run's own index and HEAD, but not where its
                // config comes from.
                "--bind", "/state/repos/alm/.git/worktrees/7", "/state/repos/alm/.git/worktrees/7",
                "--ro-bind-try", "/state/repos/alm/.git/worktrees/7/config.worktree", "/state/repos/alm/.git/worktrees/7/config.worktree",
                "--ro-bind-try", "/state/repos/alm/.git/worktrees/7/commondir", "/state/repos/alm/.git/worktrees/7/commondir",
                "--chdir", "/state/worktrees/7",
                // Under a shell that ends what the command leaves running (BubblewrapSandboxTest).
                "--", "/bin/sh", "-c", Bubblewrap.END_WITH_COMMAND, "sh",
                "claude", "-p", "--output-format", "stream-json"), wrapped);
    }

    @Test
    void aRunWithoutAGitDirMountsOnlyItsWorkdir() {
        SandboxPolicy policy = new SandboxPolicy(Path.of("/state/splits/3"), null, null, List.of(), List.of(), List.of());

        List<String> wrapped = new Bubblewrap("bwrap").wrap(List.of("claude"), policy);

        assertEquals(List.of("bwrap",
                "--unshare-pid", "--as-pid-1", "--unshare-ipc", "--new-session",
                "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp",
                "--bind", "/state/splits/3", "/state/splits/3",
                "--chdir", "/state/splits/3", "--", "/bin/sh", "-c", Bubblewrap.END_WITH_COMMAND, "sh", "claude"), wrapped);
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

    @Test
    void copyOnWriteStateIsMountedAfterTheWritableDirsWithWhatPersistsAndTheCopiesOverIt() {
        SandboxPolicy policy = new SandboxPolicy(Path.of("/state/worktrees/7"), null, null,
                List.of(Path.of("/state/plugins/dispatch")), List.of(Path.of("/home/ann/.m2")), List.of(Path.of("/state")),
                List.of(Path.of("/home/ann/.claude")),
                List.of(Path.of("/home/ann/.claude/projects/-w"), Path.of("/home/ann/.claude/.credentials.json")),
                List.of(new SandboxPolicy.FileCopy(Path.of("/home/ann/.claude.json"), Path.of("/state/runs/7/2.claude.json"))),
                List.of(Path.of("/home/ann/.codex/AGENTS.md")), List.of(Path.of("/home/ann/.claude/projects/-w/memory")), List.of());

        List<String> wrapped = new Bubblewrap("bwrap", true).wrap(List.of("claude"), policy);

        int writable = wrapped.indexOf("/home/ann/.m2");
        int overlay = wrapped.indexOf("--overlay-src");
        int copy = wrapped.indexOf("/state/runs/7/2.claude.json");
        int readOnly = wrapped.indexOf("/state/plugins/dispatch");
        int persisted = wrapped.indexOf("/home/ann/.claude/projects/-w");
        int tmpfs = wrapped.indexOf("/home/ann/.claude/projects/-w/memory");
        // Persisted after the read-only paths: without overlays the run's own project dir is bound over the read-only
        // projects dir. A tmpfs last, over what it hides.
        assertTrue(writable < overlay && overlay < copy && copy < readOnly && readOnly < persisted && persisted < tmpfs,
                wrapped.toString());
        assertEquals(List.of("--overlay-src", "/home/ann/.claude", "--tmp-overlay", "/home/ann/.claude"),
                wrapped.subList(overlay, overlay + 4));
        assertEquals(List.of("--bind-try", "/home/ann/.claude/projects/-w", "/home/ann/.claude/projects/-w"),
                wrapped.subList(persisted - 1, persisted + 2));
        assertEquals(List.of("--tmpfs", "/home/ann/.claude/projects/-w/memory"), wrapped.subList(tmpfs - 1, tmpfs + 1));
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
}
