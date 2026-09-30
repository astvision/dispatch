package dispatch.core;

import dispatch.domain.RunStatus;
import dispatch.domain.Task;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where task #N's agent conversation can go on in a terminal (RM-6): Claude Code keeps a session's transcript by the
 * directory it ran in, so resuming it in the task's worktree finds it. The CLI runs it; the chat and the Mini App show the
 * command. The task keeps its state, so a later correction or follow-up resumes the same session.
 *
 * @param workdir null only for NO_TASK
 * @param worker  the computer that ran the task, for ON_WORKER; null otherwise
 */
public record Teleport(long taskId, Path workdir, UUID session, Refusal refusal, String worker) {

    /**
     * RUNNING: a run of the task is active, and two writers on one conversation would interleave. ON_WORKER: the session is
     * on the member's own computer, where the same command works.
     */
    public enum Refusal { NO_TASK, RUNNING, NOT_CLAUDE, ON_WORKER, NO_WORKTREE }

    /**
     * @param plan  the planning session even when the task has a building one
     * @param agent the task's project's agent type
     */
    public static Teleport of(Tx tx, long taskId, boolean plan, String agent) {
        Optional<Task> found = Tasks.find(tx, taskId);
        if (found.isEmpty()) {
            return new Teleport(taskId, null, null, Refusal.NO_TASK, null);
        }
        Task task = found.get();
        UUID session = plan || task.buildSessionId() == null ? task.sessionId() : task.buildSessionId();
        Path workdir = task.worktree() == null ? null : Path.of(task.worktree());
        boolean active = Runs.latest(tx, taskId)
                .filter(run -> run.status() == RunStatus.RUNNING || run.status() == RunStatus.QUEUED).isPresent();
        if (active) {
            return new Teleport(taskId, workdir, session, Refusal.RUNNING, null);
        }
        if (!agent.equals("claude-code")) {
            return new Teleport(taskId, workdir, session, Refusal.NOT_CLAUDE, null);
        }
        Optional<Long> worker = Tasks.workerOf(tx, taskId);
        if (worker.isPresent()) {
            String name = tx.one("SELECT name FROM worker WHERE id = ?", row -> row.string("name"), worker.get()).orElse("its worker");
            return new Teleport(taskId, workdir, session, Refusal.ON_WORKER, name);
        }
        if (workdir == null || !Files.isDirectory(workdir)) {
            return new Teleport(taskId, workdir, session, Refusal.NO_WORKTREE, null);
        }
        return new Teleport(taskId, workdir, session, null, null);
    }

    public List<String> command(String claude) {
        return List.of(claude, "--resume", session.toString());
    }

    /** The same as a line to paste, for another computer or a shell without Dispatch. */
    public String shellLine() {
        return "cd '" + workdir.toString().replace("'", "'\\''") + "' && claude --resume " + session;
    }
}
