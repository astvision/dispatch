package dispatch.core;

import dispatch.domain.RunStep;
import java.time.Instant;

/** What the Coordinator has to record while a job is still running, because the next run depends on it. */
public interface JobEvents {

    /** The task's worktree now exists: recorded before anything runs in it, so a later run continues there. */
    void worktreeCreated(String worktree, String baseSha);

    /**
     * The run's agent session started: recorded at once, so the next run of this kind resumes it instead of starting over.
     *
     * @param pid          the agent's process, null for a remote worker — the process is on the member's computer and
     *                     startup recovery here only kills this machine's own orphans (ADR 0008)
     * @param processStart when that process started; null with a null pid
     */
    void agentStarted(Long pid, Instant processStart);

    /**
     * A step of the run began (RM-1): the monitor shows it at once. A sink that keeps no steps, such as a test's, ignores it.
     *
     * @param n     the step's place in the run, from 1
     * @param round which test run, fix or review it is, from 1
     */
    default void stepStarted(int n, RunStep.Kind kind, int round) {
    }

    /** @param detail {@link StepDetail}'s JSON, or null */
    default void stepEnded(int n, RunStep.Outcome outcome, String detail) {
    }
}
