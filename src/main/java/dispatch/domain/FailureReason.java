package dispatch.domain;

public enum FailureReason {
    /** Preparing the worktree failed (git fetch, worktree add, copyFiles). */
    SETUP,
    /** The agent exited with an error, without a result, or with output that broke the contract. */
    AGENT,
    TIMEOUT,
    BUDGET,
    /** Dispatch stopped or crashed while the run was active. */
    INTERRUPTED,
    DELIVERY,
    /** A bug in Dispatch itself; the log has the stack trace. */
    INTERNAL
}
