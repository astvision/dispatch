package dispatch.agent.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * What one agent run may touch. Everything else is the read-only root, and {@code hidden} is replaced by empty
 * directories before anything is mounted back, so a workdir inside a hidden directory stays reachable.
 *
 * @param workdir      read-write, and where the agent starts
 * @param gitCommonDir the clone's .git that a worktree's .git file points to, read-write; null when the run has none
 * @param worktreeAdmin {@code <gitCommonDir>/worktrees/<name>} of the run's own worktree, read-write over the read-only
 *                     {@code worktrees} dir, its {@code commondir} and {@code config.worktree} read-only again; null when none
 * @param readOnly     mounted back read-only when they exist
 * @param writable     mounted back read-write when they exist
 * @param hidden       existing directories replaced by empty ones
 * @param overlays     agent dirs mounted copy-on-write: the agent sees them, and what it writes ends with the sandbox
 * @param persisted    under an overlay, mounted back read-write so they survive the run
 * @param copies       a throwaway copy of each file, made before the run and mounted over the original
 * @param watched      loader paths absent when the run started; the run guard quarantines any that exist when it ends
 */
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
