package dispatch.core;

import java.time.Instant;

/** What the Coordinator has to record while a job is still running, because the next run depends on it. */
public interface JobEvents {

    /** The task's worktree now exists: recorded before anything runs in it, so a later run continues there. */
    void worktreeCreated(String worktree, String baseSha);

    /**
     * The agent's process started: recorded so a restart recognises its orphan, and so the next run of this kind knows
     * the session was started and resumes it.
     */
    void agentStarted(long pid, Instant processStart);
}
