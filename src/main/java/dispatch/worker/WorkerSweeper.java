package dispatch.worker;

import dispatch.Log;
import dispatch.core.ActiveRuns;
import dispatch.core.Signal;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * Removes this computer's own worktrees once nothing has run in them for {@code idle}. The team machine's
 * {@code Sweeper} cannot: since W-3 the worktrees are here, on the member's own machine (ADR 0021), and it never sees
 * them.
 *
 * <p>This side holds no task state, so it decides idleness from the files themselves — the newer of the worktree
 * directory's own last-modified time (not a recursive scan: an edit deep inside does not bump it) and that of the
 * run logs under {@code runs/&lt;task&gt;} — and, unlike the team machine's sweeper, it never discards a worktree
 * that still holds work git can see: anything with uncommitted changes or commits that are not on origin is kept
 * and logged, whatever the task's phase turned out to be. That check is what actually protects real work, not the
 * directory's mtime. Git-ignored content (build output, scratch files) is not part of that check and goes with the
 * worktree. The {@code dispatch/&lt;task&gt;} branch stays in this computer's clone, so a later retry or follow-up
 * recreates the worktree here.
 */
public final class WorkerSweeper implements Runnable {

    /** The same window as the team machine's worktrees.idleDays default; worker.yaml has no setting of its own. */
    public static final Duration IDLE = Duration.ofDays(7);
    private static final Duration INTERVAL = Duration.ofHours(1);

    private final Path stateDir;
    private final Workspaces workspaces;
    private final ActiveRuns activeRuns;
    private final Clock clock;
    private final Duration idle;
    private final Duration interval;
    private final Signal signal = new Signal();
    private volatile boolean stopped;

    public WorkerSweeper(Path stateDir, Workspaces workspaces, ActiveRuns activeRuns, Clock clock) {
        this(stateDir, workspaces, activeRuns, clock, IDLE, INTERVAL);
    }

    WorkerSweeper(Path stateDir, Workspaces workspaces, ActiveRuns activeRuns, Clock clock, Duration idle,
                  Duration interval) {
        this.stateDir = stateDir;
        this.workspaces = workspaces;
        this.activeRuns = activeRuns;
        this.clock = clock;
        this.idle = idle;
        this.interval = interval;
    }

    @Override
    public void run() {
        Log.info("worker_sweeper.started", "idle_days", idle.toDays());
        while (!stopped) {
            sweep();
            try {
                signal.await(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.info("worker_sweeper.stopped");
    }

    public void stop() {
        stopped = true;
        signal.wake();
    }

    /**
     * One pass; returns how many worktrees it removed. Each worktree is handled independently: a {@link RuntimeException}
     * from one (a broken clone, an unreadable path, a git subprocess failure) is logged and skipped rather than ending
     * the pass — an hourly sweeper that dies silently on the first bad worktree brings back the disk-growth problem this
     * class exists to fix, with nothing in the log to say so.
     */
    public int sweep() {
        Path worktrees = stateDir.resolve("worktrees");
        if (!Files.isDirectory(worktrees)) {
            return 0;
        }
        int removed = 0;
        for (Path worktree : listed(worktrees)) {
            if (stopped) {
                break;
            }
            try {
                removed += sweepOne(worktree) ? 1 : 0;
            } catch (RuntimeException e) {
                Log.warn("worker_sweeper.failed", "worktree", worktree, "error", e.getMessage());
            }
        }
        if (removed > 0) {
            Log.info("worker_sweeper.done", "removed", removed);
        }
        return removed;
    }

    private boolean sweepOne(Path worktree) {
        Long taskId = taskIdOf(worktree);
        if (taskId == null || activeRuns.isActive(taskId) || !isIdle(taskId, worktree)) {
            return false;
        }
        return remove(taskId, worktree);
    }

    private boolean remove(long taskId, Path worktree) {
        // The path inspected above and the path removed below must be the one and same directory: a mismatch (e.g. a
        // directory named with a leading zero, whose parsed task id resolves to a different path than the one listed)
        // must never fall through to deleting whatever that recomputed path happens to be.
        Path expected = stateDir.resolve("worktrees").resolve(Long.toString(taskId));
        if (!expected.equals(worktree)) {
            Log.warn("worker_sweeper.path_mismatch", "task", taskId, "listed", worktree, "expected", expected);
            return false;
        }
        Workspaces.WorktreeState state = workspaces.localOnlyState(worktree);
        if (!state.disposable()) {
            Log.warn("worker_sweeper.kept", "task", taskId, "uncommitted", state.uncommitted().size(),
                    "pushed", state.pushed(), "worktree", worktree);
            return false;
        }
        Path repo = workspaces.repoOf(worktree);
        // Three git subprocesses ran between the loop's own activeRuns check and here (status, branch, rev-parse);
        // a run for this exact task can have been claimed and registered in that window, so it is checked again,
        // immediately before the removal it would otherwise race.
        if (activeRuns.isActive(taskId)) {
            Log.info("worker_sweeper.skipped_active", "task", taskId);
            return false;
        }
        workspaces.removeWorktree(repo, taskId);
        Log.info("worker_sweeper.removed", "task", taskId, "worktree", worktree);
        return true;
    }

    /** Nothing has touched this task here for {@code idle}: neither its worktree nor its run logs. */
    private boolean isIdle(long taskId, Path worktree) {
        Instant cutoff = clock.instant().minus(idle);
        return lastTouched(worktree).isBefore(cutoff)
                && lastTouched(stateDir.resolve("runs").resolve(Long.toString(taskId))).isBefore(cutoff);
    }

    /**
     * {@link Instant#MIN} for a path that is not there (nothing ever happened), {@link Instant#MAX} for one that
     * cannot be read (never idle, so nothing is removed on a guess).
     */
    private static Instant lastTouched(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toInstant() : Instant.MIN;
        } catch (IOException e) {
            return Instant.MAX;
        }
    }

    /** {@code NOFOLLOW_LINKS}: a symlink named like a task id must never be treated as that task's worktree. */
    private static Long taskIdOf(Path worktree) {
        try {
            return Files.isDirectory(worktree, LinkOption.NOFOLLOW_LINKS) ? Long.valueOf(worktree.getFileName().toString()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Path> listed(Path worktrees) {
        try (Stream<Path> entries = Files.list(worktrees)) {
            return entries.toList();
        } catch (IOException e) {
            Log.warn("worker_sweeper.unreadable", "dir", worktrees, "error", e.getMessage());
            return List.of();
        }
    }
}
