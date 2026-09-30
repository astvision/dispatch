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
                Path.of("/state/repos/alm/.git/worktrees/7"),
                List.of(Path.of("/state/attachments/7"), Path.of("/state/worktrees/7/.git"), Path.of("/state/repos/alm/.git/config"),
                        Path.of("/state/repos/alm/.git/worktrees"), Path.of("/home/ann/.claude/settings.json")),
                List.of(Path.of("/home/ann/.claude"), Path.of("/home/ann/.m2")),
                List.of(Path.of("/home/ann/.ssh"), Path.of("/state")));

        List<String> wrapped = new Bubblewrap("/usr/bin/bwrap").wrap(CLAUDE, policy);

        assertEquals(List.of("/usr/bin/bwrap",
                "--die-with-parent", "--unshare-pid", "--unshare-ipc", "--new-session",
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
                "--",
                "claude", "-p", "--output-format", "stream-json"), wrapped);
    }

    @Test
    void aRunWithoutAGitDirMountsOnlyItsWorkdir() {
        SandboxPolicy policy = new SandboxPolicy(Path.of("/state/splits/3"), null, null, List.of(), List.of(), List.of());

        List<String> wrapped = new Bubblewrap("bwrap").wrap(List.of("claude"), policy);

        assertEquals(List.of("bwrap",
                "--die-with-parent", "--unshare-pid", "--unshare-ipc", "--new-session",
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
