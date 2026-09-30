package dispatch.domain;

/** What an outbox message tells the team; channels render each kind from its JSON payload. */
public enum OutboxKind {
    /** Opens the task's own topic in the requester's private chat; later private messages of the task go there. */
    TOPIC_CREATE,
    /** The group's line about a task given privately (ADR 0012). */
    TASK_QUEUED,
    /** The requester's own line about a task they gave on the desktop (D-2b), so the chat hears of it as of any other. */
    TASK_GIVEN_ON_DESK,
    /** Asks for a draft's project and priority; edited in place as they are chosen. */
    DRAFT_PROMPT,
    DRAFT_EXPIRED,
    PLAN_READY,
    /**
     * To the requester privately: one open question of a plan, with its answer options as buttons; sent one at a time and
     * redrawn with the answer (G-1d).
     */
    PLAN_QUESTION,
    /** To the requester privately: asks for their own answer to a question, as a forced reply (G-1d). */
    PLAN_ANSWER_PROMPT,
    /** To the presser of a plan's ✏️: asks for their correction as a forced reply; the reply corrects the plan. */
    PLAN_EDIT_PROMPT,
    EXECUTION_QUEUED,
    CORRECTION_QUEUED,
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
    /**
     * A task command's refusal, under the message that asked for it (ADR 0031): its words, worded where it was refused, and
     * the command to type instead when there is one.
     */
    REFUSED,
    /** /retry queued the failed step again. */
    RETRY_QUEUED,
    /** A reply to a finished task's result runs as a follow-up (ADR 0006). */
    FOLLOW_UP_QUEUED,
    NOT_ALLOWED,
    TASK_USAGE,
    PRIVATE_ONLY,
    NO_PROJECTS,
    HELP,
    /**
     * To a member privately, from /help's ✍️ button: asks for a task as a forced reply. The reply is drafted as a task, never
     * put to the assistant, as /task's text is (ADR 0024).
     */
    TASK_PROMPT,
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
    /**
     * A reaction on the group message that started a group-origin draft or task, instead of the ✉️ line by default
     * (G-1e); each new state replaces the previous reaction. Payload: {@code emoji}, and for the 👀 state also
     * {@code requester}, the ✉️ line's own text sent instead if Telegram refuses the reaction.
     */
    GROUP_REACTION,
    /** reactionAndLine's own line, alongside the ✍ reaction when a group-origin task is created (G-1e). */
    GROUP_WORKING,
    /** To a linked group: an @username mentioned there is not in the username book, so it gives no task (G-1c). */
    UNKNOWN_USERNAME,
    /** To whoever may manage Dispatch in a linked group, privately: it became a supergroup the bot had just left. */
    GROUP_READD,
    /**
     * The assistant's answer in a member's private chat (A-1): its reply, a confirm button per proposed action, and a note per
     * proposal it could not offer; or, with {@code failed}, one line saying it could not answer, before the message's draft.
     */
    ASSISTANT_REPLY,
    /**
     * To a requester, privately: someone replied in the group to the message their task (or draft) came from. Payload:
     * {@code additionId} for its button, {@code taskId} (absent for a draft), {@code title}, {@code by}, {@code text}, and
     * {@code requester}, the ✉️ line's name if the group's 👀 reaction is refused.
     */
    ADDITION_OFFERED,
    /** To the requester, under the result they tapped Merge on: the pull request is merged. Payload: taskId, base, prUrl. */
    TASK_MERGED,
    /** To the requester, under that result: GitHub refused the merge. Payload: taskId, and error (GitHub's words) or closed. */
    MERGE_REFUSED,
    /** Under a follow-up to a merged task: it became a new task instead. Payload: taskId (the merged one), newTaskId. */
    FOLLOW_UP_NEW_TASK
}
