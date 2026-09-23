package dispatch.worker;

import dispatch.Log;
import dispatch.core.ActiveRuns;
import dispatch.core.Signal;
import dispatch.workspace.WorkspaceException;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.nio.file.Files;
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
 * <p>This side holds no task state, so it decides idleness from the files themselves — the newer of the worktree's own
 * last-modified time and that of the run logs under {@code runs/&lt;task&gt;} — and, unlike the team machine's sweeper,
 * it never discards a worktree that still holds work: anything with uncommitted changes or commits that are not on
 * origin is kept and logged, whatever the task's phase turned out to be. The {@code dispatch/&lt;task&gt;} branch stays
 * in this computer's clone, so a later retry or follow-up recreates the worktree here.
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

    /** One pass; returns how many worktrees it removed. */
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
            Long taskId = taskIdOf(worktree);
            if (taskId == null || activeRuns.isActive(taskId) || !isIdle(taskId, worktree)) {
                continue;
            }
            try {
                removed += remove(taskId, worktree) ? 1 : 0;
            } catch (WorkspaceException e) {
                Log.warn("worker_sweeper.failed", "task", taskId, "error", e.getMessage());
            }
        }
        if (removed > 0) {
            Log.info("worker_sweeper.done", "removed", removed);
        }
        return removed;
    }

    private boolean remove(long taskId, Path worktree) {
        Workspaces.WorktreeState state = workspaces.localOnlyState(worktree);
        if (!state.disposable()) {
            Log.warn("worker_sweeper.kept", "task", taskId, "uncommitted", state.uncommitted().size(),
                    "pushed", state.pushed(), "worktree", worktree);
            return false;
        }
        workspaces.removeWorktree(workspaces.repoOf(worktree), taskId);
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

    private static Long taskIdOf(Path worktree) {
        try {
            return Files.isDirectory(worktree) ? Long.valueOf(worktree.getFileName().toString()) : null;
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
