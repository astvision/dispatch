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
    /** The projects a chat can give tasks for, and why any of them cannot take one now. */
    PROJECTS,
    /** To an admin: someone asks to use the bot, with a button per group (ADR 0015). */
    JOIN_REQUEST,
    /** To the person asking: their request was sent to the admins. */
    JOIN_REQUESTED,
    JOIN_APPROVED,
    JOIN_DENIED,
    /** To a member privately: a one-time pairing code and the computers they have paired (W-3). */
    WORKER_PAIRING,
    WORKER_REVOKED,
    /** /worker revoke without a valid positive id. */
    WORKER_USAGE,
    /** To the requester, once: their task waits because none of their computers is connected. */
    WORKER_WAITING,
    /** To the requester privately, once per reason: their computer is connected but cannot do the task, and why. */
    WORKER_BLOCKED,
    /** /manage: a button that opens the Mini App, or a note that it is not turned on here (ADR 0019). */
    MANAGE,
    /**
     * To whoever added the bot to an unknown group: which project to link it to. Rendered here but sent directly, not
     * through the outbox, so a refusal is seen and the bot can leave.
     */
    GROUP_LINK,
    /** To a group just linked: which project's tasks it will hear about. */
    GROUP_LINKED,
    /**
     * To a group where a member gave a task by mentioning the bot: its prompt reached their private chat. Enqueued by the
     * sender once that prompt was delivered, so a refused prompt gets the fallback notice instead (G-1b).
     */
    GROUP_TASK_SENT,
    /** To a linked group: an @username mentioned there is not in the username book, so it gives no task (G-1c). */
    UNKNOWN_USERNAME
}
