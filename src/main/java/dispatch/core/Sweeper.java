package dispatch.core;

import dispatch.Log;
import dispatch.config.Config;
import dispatch.domain.Phase;
import dispatch.domain.Task;
import dispatch.store.Database;
import dispatch.store.Tasks;
import dispatch.workspace.WorkspaceException;
import dispatch.workspace.Workspaces;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Removes worktrees of tasks that finished and stayed idle for {@code idle}, checking every {@code interval}. A completed or
 * failed task's worktree goes only when nothing in it would be lost, which for a merged task means only that it is clean;
 * a rejected or cancelled task's goes anyway, with the discarded paths logged. Its branch stays, so a later run can
 * recreate it.
 */
public final class Sweeper implements Runnable {

    private final Database db;
    private final Projects projects;
    private final Workspaces workspaces;
    private final Clock clock;
    private final Duration idle;
    private final Duration interval;
    private final Signal signal = new Signal();
    private final String branchPrefix;
    private volatile boolean stopped;

    public Sweeper(Database db, Projects projects, Workspaces workspaces, Clock clock, Duration idle, Duration interval) {
        this(db, projects, workspaces, clock, idle, interval, null);
    }

    /** @param branchPrefix the instance's own prefix for task branches (M: several instances on one computer); null for "dispatch" */
    public Sweeper(Database db, Projects projects, Workspaces workspaces, Clock clock, Duration idle, Duration interval,
                   String branchPrefix) {
        this.db = db;
        this.projects = projects;
        this.workspaces = workspaces;
        this.clock = clock;
        this.idle = idle;
        this.interval = interval;
        this.branchPrefix = branchPrefix;
    }

    @Override
    public void run() {
        Log.info("sweeper.started", "idle_days", idle.toDays());
        while (!stopped) {
            sweep();
            try {
                signal.await(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.info("sweeper.stopped");
    }

    public void stop() {
        stopped = true;
        signal.wake();
    }

    /**
     * One pass; returns how many worktrees it removed. A follow-up or retry queued during the pass can still find its
     * worktree gone, and then recreates it.
     */
    public int sweep() {
        List<Task> candidates = db.transactionReturning(tx -> Tasks.finishedIdleWithWorktree(tx, clock.instant().minus(idle)));
        int removed = 0;
        for (Task task : candidates) {
            // Only the worktree this machine made for the task, recorded in the words it wrote them in, is its to sweep:
            // a member's computer reports its own in its own spelling (ADR 0021), which is never read as a path here.
            Path worktree = workspaces.worktree(task.id());
            if (!worktree.toString().equals(task.worktree()) || !Files.isDirectory(worktree) || stopped) {
                continue;
            }
            try {
                removed += sweep(task, worktree) ? 1 : 0;
            } catch (WorkspaceException e) {
                Log.warn("sweeper.failed", "task", task.id(), "error", e.getMessage());
            }
        }
        if (removed > 0) {
            Log.info("sweeper.done", "removed", removed);
        }
        return removed;
    }

    private boolean sweep(Task task, Path worktree) {
        Optional<Config.Project> project = projects.byName(task.project());
        if (project.isEmpty()) {
            Log.warn("sweeper.project_gone", "task", task.id(), "project", task.project(), "worktree", task.worktree());
            return false;
        }
        String prefixed = Config.branchFor(branchPrefix, task.id());
        String branch = prefixed == null ? Config.defaultBranch(task.id()) : prefixed;
        Workspaces.WorktreeState state = workspaces.state(worktree, branch, task.baseSha());
        boolean abandoned = task.phase() == Phase.REJECTED || task.phase() == Phase.CANCELLED;
        // Merged: its delivered work is on the base branch, while its own branch may be gone from origin, deleted by the
        // merge. That excuses "not pushed" only; changes never delivered (a follow-up refused at delivery) are kept.
        boolean merged = task.mergedAt() != null && state.uncommitted().isEmpty();
        if (!abandoned && !merged && !state.disposable()) {
            Log.warn("sweeper.kept", "task", task.id(), "phase", task.phase(), "uncommitted", state.uncommitted().size(),
                    "pushed", state.pushed(), "worktree", task.worktree());
            return false;
        }
        // A follow-up or retry since the pass began may have a run starting in this worktree right now.
        // ponytail: re-read just before removing leaves a window of milliseconds; a per-task lock shared with Coordinator closes it.
        if (db.transactionReturning(tx -> Tasks.find(tx, task.id())).map(current -> current.phase().isActive()).orElse(true)) {
            Log.info("sweeper.skipped_active", "task", task.id());
            return false;
        }
        if (!state.uncommitted().isEmpty()) {
            Log.warn("sweeper.discarding", "task", task.id(), "phase", task.phase(), "paths", String.join(", ", state.uncommitted()));
        }
        workspaces.removeWorktree(project.get(), task.id());
        Log.info("sweeper.removed", "task", task.id(), "phase", task.phase(), "worktree", task.worktree());
        return true;
    }
}
