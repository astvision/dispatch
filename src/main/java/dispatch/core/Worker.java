package dispatch.core;

/**
 * Where a run's machine work happens: the worktree, the agent and the delivery. Personal mode runs {@link JobRunner} in
 * this process; in a team, a member's own computer does the same work behind HTTP (W-3). A worker is given everything in
 * the {@link Job} and gives everything back in the {@link JobResult}: it reads no store and no config of its own.
 */
public interface Worker {

    /**
     * Carries one job to its end on the calling thread.
     *
     * @param events  the two store writes the Coordinator must make while the job is still running
     * @param control the run's stop handle: the worker stops its agent as soon as this has a stop reason
     * @throws RuntimeException only when the worker itself broke; the Coordinator then fails the run as INTERNAL
     */
    JobResult run(Job job, JobEvents events, ActiveRuns.ActiveRun control);
}
