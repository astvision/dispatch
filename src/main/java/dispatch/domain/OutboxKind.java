package dispatch.domain;

/** What an outbox message tells the team; channels render each kind from its JSON payload. */
public enum OutboxKind {
    /** Opens the task's own topic in the requester's private chat; later private messages of the task go there. */
    TOPIC_CREATE,
    /** The group's line about a task given privately (ADR 0012). */
    TASK_QUEUED,
    /** Asks for a draft's project and priority; edited in place as they are chosen. */
    DRAFT_PROMPT,
    DRAFT_EXPIRED,
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
    STATS,
    TASK_NOT_FOUND,
    CANCEL_REFUSED,
    /** /retry queued the failed step again. */
    RETRY_QUEUED,
    RETRY_REFUSED,
    /** A reply to a finished task's result runs as a follow-up (ADR 0006). */
    FOLLOW_UP_QUEUED,
    FOLLOW_UP_REFUSED,
    NOT_ALLOWED,
    UNKNOWN_PROJECT,
    PROJECT_UNAVAILABLE,
    TASK_USAGE,
    PRIVATE_ONLY,
    NO_PROJECTS,
    HELP,
    /** To an admin: someone asks to use the bot, with a button per group (ADR 0015). */
    JOIN_REQUEST,
    /** To the person asking: their request was sent to the admins. */
    JOIN_REQUESTED,
    JOIN_APPROVED,
    JOIN_DENIED
}
