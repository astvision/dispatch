package dispatch.domain;

/** What an outbox message tells the team; channels render each kind from its JSON payload. */
public enum OutboxKind {
    TASK_QUEUED,
    PLAN_READY,
    TASK_FAILED,
    TASK_REJECTED,
    TASK_CANCELLED,
    TASK_LIST,
    TASK_NOT_FOUND,
    CANCEL_REFUSED,
    NOT_ALLOWED,
    UNKNOWN_PROJECT,
    PROJECT_UNAVAILABLE,
    TASK_USAGE,
    HELP
}
