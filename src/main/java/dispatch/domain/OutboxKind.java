package dispatch.domain;

/** What an outbox message tells the team; channels render each kind from its JSON payload. */
public enum OutboxKind {
    TASK_QUEUED,
    PLAN_READY,
    EXECUTION_QUEUED,
    CORRECTION_QUEUED,
    CORRECTION_REFUSED,
    TASK_COMPLETED,
    /** The group's one line about a completion whose details went to the requester (ADR 0011). */
    TASK_COMPLETED_SHORT,
    TASK_FAILED,
    TASK_FAILED_SHORT,
    TASK_REJECTED,
    TASK_CANCELLED,
    STATUS,
    HISTORY,
    TASK_TIMELINE,
    TASK_NOT_FOUND,
    CANCEL_REFUSED,
    NOT_ALLOWED,
    UNKNOWN_PROJECT,
    PROJECT_UNAVAILABLE,
    TASK_USAGE,
    TASK_IN_GROUP_ONLY,
    HELP
}
