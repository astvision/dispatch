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

    /**
     * Runs the command, then ends whatever it left running, then exits with the command's status. bwrap returns when its
     * command exits, but its init waits for every process still in the sandbox: a Gradle daemon would live on with the
     * run's mounts, outside Dispatch's process tree, and a later run's build would reach it. {@code kill -1} reaches only
     * the sandbox's own processes, since they are all a pid namespace holds; the wait, at most 5 s, lets bwrap's init
     * reap them, so nothing of the run is left once bwrap has returned.
     */
    static final String END_WITH_COMMAND = "\"$@\"; s=$?; kill -KILL -1 2>/dev/null; n=0; "
            + "while kill -0 -1 2>/dev/null && [ $n -lt 50 ]; do sleep 0.1; n=$((n+1)); done; exit $s";

    private final String command;
    private final boolean overlay;

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
                // No --die-with-parent: its PR_SET_PDEATHSIG fires when the forking THREAD exits, and runs start from
                // virtual threads whose carriers retire when idle, which killed every run ~30 s in. A sandbox left by a
                // crashed Dispatch is ended by the orphan kill (recorded pid and start time) and systemd's cgroup.
                // --new-session: the agent cannot inject input into Dispatch's terminal.
                // --unshare-ipc: no SysV IPC or POSIX message queues shared with the owner's processes.
                "--unshare-pid", "--unshare-ipc", "--new-session",
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
        // Copy-on-write (spec: agent state guard): the agent sees its whole state dir, and what it writes there lands in
        // a tmpfs that ends with the sandbox. The throwaway copies are mounted over their originals.
        policy.overlays().forEach(dir -> args.addAll(List.of("--overlay-src", dir.toString(), "--tmp-overlay", dir.toString())));
        policy.copies().forEach(copy -> args.addAll(List.of("--bind", copy.copy().toString(), copy.original().toString())));
        policy.readOnly().forEach(path -> bind(args, "--ro-bind-try", path));
        // After the read-only paths: without an overlay the run's own project dir is bound over the read-only projects dir.
        policy.persisted().forEach(path -> bind(args, "--bind-try", path));
        if (policy.worktreeAdmin() != null) {
            // Over the read-only worktrees dir: git writes this worktree's index and HEAD here. Then read-only again
            // what says where its config is (commondir) and its own config (config.worktree).
            bind(args, "--bind", policy.worktreeAdmin());
            bind(args, "--ro-bind-try", policy.worktreeAdmin().resolve("config.worktree"));
            bind(args, "--ro-bind-try", policy.worktreeAdmin().resolve("commondir"));
        }
        // Last, over whatever they hide: the guard made each one, so bwrap needs no mount point on a read-only path.
        policy.tmpfs().forEach(dir -> args.addAll(List.of("--tmpfs", dir.toString())));
        args.addAll(List.of("--chdir", policy.workdir().toString(), "--", "/bin/sh", "-c", END_WITH_COMMAND, "sh"));
        args.addAll(commandLine);
        return List.copyOf(args);
    }

    private static void bind(List<String> args, String option, Path path) {
        args.addAll(List.of(option, path.toString(), path.toString()));
    }
}
