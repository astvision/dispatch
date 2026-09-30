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
                // --unshare-ipc: no SysV IPC or POSIX message queues shared with the owner's processes.
                "--die-with-parent", "--unshare-pid", "--unshare-ipc", "--new-session",
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
        if (policy.worktreeAdmin() != null) {
            // Over the read-only worktrees dir: git writes this worktree's index and HEAD here. Then read-only again
            // what says where its config is (commondir) and its own config (config.worktree).
            bind(args, "--bind", policy.worktreeAdmin());
            bind(args, "--ro-bind-try", policy.worktreeAdmin().resolve("config.worktree"));
            bind(args, "--ro-bind-try", policy.worktreeAdmin().resolve("commondir"));
        }
        args.addAll(List.of("--chdir", policy.workdir().toString(), "--"));
        args.addAll(commandLine);
        return List.copyOf(args);
    }

    private static void bind(List<String> args, String option, Path path) {
        args.addAll(List.of(option, path.toString(), path.toString()));
    }
}
