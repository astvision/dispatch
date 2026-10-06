package dispatch.domain;

/** What an outbox message tells the team; channels render each kind from its JSON payload. */
public enum OutboxKind {
    /** Opens the task's own topic in the requester's private chat; later private messages of the task go there. */
    TOPIC_CREATE(Ending.NONE, Form.TOPIC),
    /** The group's line about a task given privately (ADR 0012). */
    TASK_QUEUED(Ending.NONE),
    /** The requester's own line about a task they gave on the desktop (D-2b), so the chat hears of it as of any other. */
    TASK_GIVEN_ON_DESK(Ending.NONE),
    /** Asks for a draft's project and priority; edited in place as they are chosen. */
    DRAFT_PROMPT(Ending.NONE, Form.PRIVATE_FOR_GROUP),
    DRAFT_EXPIRED(Ending.NONE),
    PLAN_READY(Ending.NONE),
    /** To the requester privately: the answer to a task that only asked something (spec: answers). */
    ANSWER_READY(Ending.RESULT),
    /**
     * To the requester privately: one open question of a plan, with its answer options as buttons; sent one at a time and
     * redrawn with the answer (G-1d).
     */
    PLAN_QUESTION(Ending.NONE),
    /** To the requester privately: asks for their own answer to a question, as a forced reply (G-1d). */
    PLAN_ANSWER_PROMPT(Ending.NONE),
    /** /teleport N's answer: the command that continues the task's agent session in a terminal, or why not yet (RM-6). */
    TELEPORT(Ending.NONE),
    /** To the presser of a plan's ✏️: asks for their correction as a forced reply; the reply corrects the plan. */
    PLAN_EDIT_PROMPT(Ending.NONE),
    EXECUTION_QUEUED(Ending.NONE),
    CORRECTION_QUEUED(Ending.NONE),
    TASK_COMPLETED(Ending.RESULT),
    /** The group's one line about a completion whose details went to the requester (ADR 0011). */
    TASK_COMPLETED_SHORT(Ending.RESULT),
    TASK_FAILED(Ending.RESULT),
    /** Not sent since 2026-10-06 (a failure is the requester's news alone); kept so rows sent before then still parse. */
    TASK_FAILED_SHORT(Ending.RESULT),
    TASK_REJECTED(Ending.STOPPED),
    TASK_CANCELLED(Ending.STOPPED),
    STATUS(Ending.NONE),
    HISTORY(Ending.NONE),
    TASK_TIMELINE(Ending.NONE),
    STATS(Ending.NONE),
    TASK_NOT_FOUND(Ending.NONE),
    /**
     * A task command's refusal, under the message that asked for it (ADR 0031): its words, worded where it was refused, and
     * the command to type instead when there is one.
     */
    REFUSED(Ending.NONE),
    /** /retry queued the failed step again. */
    RETRY_QUEUED(Ending.NONE),
    /**
     * To the requester privately: Claude's usage limit cut the run short, and the same run is queued to start by itself
     * after the reset (spec: usage limit). Payload: taskId, type (Claude's window name), resetsAt (ISO instant).
     */
    LIMIT_REQUEUED(Ending.NONE),
    /** A reply to a finished task's result runs as a follow-up (ADR 0006). */
    FOLLOW_UP_QUEUED(Ending.NONE),
    NOT_ALLOWED(Ending.NONE),
    TASK_USAGE(Ending.NONE),
    PRIVATE_ONLY(Ending.NONE),
    NO_PROJECTS(Ending.NONE),
    HELP(Ending.NONE),
    /**
     * To a member privately, from /help's ✍️ button or a project picked after a bare /task (then with that project in the
     * payload): asks for a task as a forced reply. The reply is drafted as a task, never put to the assistant, as /task's
     * text is (ADR 0024).
     */
    TASK_PROMPT(Ending.NONE),
    /** To a member privately, from a bare /task: a button per project of theirs, whose tap asks for the task with TASK_PROMPT. */
    TASK_PROJECT_PICK(Ending.NONE),
    /** The projects a chat can give tasks for, and why any of them cannot take one now. */
    PROJECTS(Ending.NONE),
    /** To an admin: someone asks to use the bot, with a button per group (ADR 0015). */
    JOIN_REQUEST(Ending.NONE),
    /** To the person asking: their request was sent to the admins. */
    JOIN_REQUESTED(Ending.NONE),
    JOIN_APPROVED(Ending.NONE),
    JOIN_DENIED(Ending.NONE),
    /** To a member privately: a one-time pairing code and the computers they have paired (W-3). */
    WORKER_PAIRING(Ending.NONE),
    WORKER_REVOKED(Ending.NONE),
    /** /worker revoke without a valid positive id. */
    WORKER_USAGE(Ending.NONE),
    /** To the requester, once: their task waits because none of their computers is connected. */
    WORKER_WAITING(Ending.NONE),
    /** To the requester privately, once per reason: their computer is connected but cannot do the task, and why. */
    WORKER_BLOCKED(Ending.NONE),
    /** /manage: a button that opens the Mini App, or a note that it is not turned on here (ADR 0019). */
    MANAGE(Ending.NONE),
    /**
     * To whoever added the bot to an unknown group: which project to link it to. Rendered here but sent directly, not
     * through the outbox, so a refusal is seen and the bot can leave.
     */
    GROUP_LINK(Ending.NONE),
    /** To a group just linked: which project's tasks it will hear about. */
    GROUP_LINKED(Ending.NONE),
    /**
     * To a group where a member gave a task by mentioning the bot: its prompt reached their private chat. Enqueued by the
     * sender once that prompt was delivered, so a refused prompt gets the fallback notice instead (G-1b).
     */
    GROUP_TASK_SENT(Ending.NONE),
    /**
     * A reaction on the group message that started a group-origin draft or task, instead of the ✉️ line by default
     * (G-1e); each new state replaces the previous reaction. Payload: {@code emoji}, and for the 👀 state also
     * {@code requester}, the ✉️ line's own text sent instead if Telegram refuses the reaction.
     */
    GROUP_REACTION(Ending.NONE, Form.REACTION),
    /** reactionAndLine's own line, alongside the ✍ reaction when a group-origin task is created (G-1e). */
    GROUP_WORKING(Ending.NONE),
    /** To a linked group: an @username mentioned there is not in the username book, so it gives no task (G-1c). */
    UNKNOWN_USERNAME(Ending.NONE),
    /** To whoever may manage Dispatch in a linked group, privately: it became a supergroup the bot had just left. */
    GROUP_READD(Ending.NONE),
    /**
     * The assistant's answer in a member's private chat (A-1): its reply, a confirm button per proposed action, and a note per
     * proposal it could not offer; or, with {@code failed}, one line saying it could not answer, before the message's draft.
     */
    ASSISTANT_REPLY(Ending.NONE),
    /**
     * To a requester, privately: someone replied in the group to the message their task (or draft) came from. Payload:
     * {@code additionId} for its button, {@code taskId} (absent for a draft), {@code title}, {@code by}, {@code text}, and
     * {@code requester}, the ✉️ line's name if the group's 👀 reaction is refused.
     */
    ADDITION_OFFERED(Ending.NONE, Form.PRIVATE_FOR_GROUP),
    /** To the requester, under the result they tapped Merge on: the pull request is merged. Payload: taskId, base, prUrl. */
    TASK_MERGED(Ending.NONE),
    /** To the requester, under that result: GitHub refused the merge. Payload: taskId, and error (GitHub's words) or closed. */
    MERGE_REFUSED(Ending.NONE),
    /** Under a follow-up to a merged task: it became a new task instead. Payload: taskId (the merged one), newTaskId. */
    FOLLOW_UP_NEW_TASK(Ending.NONE),
    /**
     * To the requester privately: the checks failed on the delivered commit and a fix run is queued (spec: CI watch).
     * Payload: taskId, check (the first failed one), round.
     */
    CI_FIX_QUEUED(Ending.NONE),
    /** To the requester, under the result: every check passed, so the pull request is ready to merge. Payload: taskId. */
    CI_PASSED(Ending.NONE),
    /**
     * To the requester, under the result: the watch hands the pull request back. Payload: taskId, reason (CAP, UNCHANGED,
     * STUCK, CANCELLED), checks ([{name, link}], the failed ones), summary (the fix run's own words, for UNCHANGED).
     */
    CI_GAVE_UP(Ending.NONE);

    /** What the message says about how its task ended. */
    public enum Ending {
        /** Nothing: the task goes on, or the message is about no task. */
        NONE,
        /** The task was rejected or cancelled: nothing follows it. */
        STOPPED,
        /** The task completed, failed or was answered: a reply to the message is a follow-up (ADR 0006). */
        RESULT
    }

    /** What a member's reply to the message means. */
    public enum Reply {
        /** Nothing of its own: the reply is read as any other message. */
        NOTHING,
        /** More work on the finished task (ADR 0006). */
        FOLLOW_UP,
        /** A correction of the plan it shows or asks about. */
        CORRECTION,
        /** The answer to the plan question it asks (G-1d). */
        ANSWER,
        /** More text and files for the draft it prompts for. */
        DRAFT_CONTEXT,
        /** A task, given by explicit intent (ADR 0024). */
        TASK
    }

    /** How the message reaches Telegram, and what its group hears of it. */
    public enum Form {
        /** An ordinary message to its chat. */
        MESSAGE,
        /** Opens the task's topic instead of sending a message. */
        TOPIC,
        /** A reaction on a group message instead of a message. */
        REACTION,
        /**
         * A private message that answers something given in a group (G-1b): a refused one asks its developer in the group to
         * open the private chat first, and only a delivered one lets the group hear that it arrived.
         */
        PRIVATE_FOR_GROUP
    }

    private final Ending ending;
    private final Form form;

    OutboxKind(Ending ending) {
        this(ending, Form.MESSAGE);
    }

    OutboxKind(Ending ending, Form form) {
        this.ending = ending;
        this.form = form;
    }

    /** Whether the message says how its task ended; the one sent to the task's own chat renames its topic (ADR 0014). */
    public boolean endsTask() {
        return ending != Ending.NONE;
    }

    /** What a reply to this message means; the chat handler routes a reply by this alone. */
    public Reply replyMeans() {
        return switch (this) {
            case PLAN_QUESTION, PLAN_ANSWER_PROMPT -> Reply.ANSWER;
            case PLAN_READY, PLAN_EDIT_PROMPT -> Reply.CORRECTION;
            case DRAFT_PROMPT -> Reply.DRAFT_CONTEXT;
            case TASK_PROMPT -> Reply.TASK;
            default -> ending == Ending.RESULT ? Reply.FOLLOW_UP : Reply.NOTHING;
        };
    }

    /** Whether a reply to the message continues its task as a follow-up. */
    public boolean replyIsFollowUp() {
        return ending == Ending.RESULT;
    }

    /** Whether the message answers, privately, something given in a group. */
    public boolean answersGroupMessage() {
        return form == Form.PRIVATE_FOR_GROUP;
    }

    /** How the sender delivers the message. */
    public Form form() {
        return form;
    }
}
