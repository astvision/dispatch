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
