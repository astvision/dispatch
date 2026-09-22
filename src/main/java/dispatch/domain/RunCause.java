package dispatch.domain;

/** Why a run was queued; it decides what the agent is told. */
public enum RunCause {
    /** The task's first plan. */
    TASK,
    /** The requester's reply to a plan. */
    CORRECTION,
    /** The requester approved the plan: the first execution. */
    APPROVAL,
    /** A member's reply to a finished task's result (ADR 0006). */
    FOLLOW_UP,
    /** A member's /retry of the failed step (ADR 0008). */
    RETRY
}
