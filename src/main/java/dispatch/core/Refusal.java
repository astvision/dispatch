package dispatch.core;

/**
 * Why a task command did nothing (ADR 0027, ADR 0031): {@link TaskAccess} gives the first eleven, the commands the last
 * three. A channel words a refusal with the words the command returns; {@link #kind()} is what it may act on without
 * knowing the refusal, such as an HTTP status, and {@link #code()} is what a page and the log receive.
 */
public enum Refusal {
    /** In no group; for cancelling, no admin either. */
    NOT_MEMBER(Kind.FORBIDDEN, "not_a_member"),
    /** No such task, or one this member may not see, so its existence does not leak. */
    NOT_FOUND(Kind.NOT_FOUND, "not_found"),
    /** Someone else's task, seen as its headline (ADR 0020). */
    NOT_REQUESTER(Kind.FORBIDDEN, "not_yours"),
    WRONG_PHASE(Kind.CONFLICT, "wrong_state"),
    /** A tap or reply on a plan a newer one replaced. */
    STALE_PLAN(Kind.CONFLICT, "stale"),
    /** A plan that asks questions is never approved: their answers make the next plan (G-1d). */
    OPEN_QUESTIONS(Kind.CONFLICT, "open_questions"),
    /** A later question while an earlier one is open. */
    OUT_OF_ORDER(Kind.CONFLICT, "out_of_order"),
    /** The question has its answer already, or no question is open. */
    ALREADY_ANSWERED(Kind.CONFLICT, "already_answered"),
    /** Only a failed task's failed step is retried (ADR 0008). */
    NOT_FAILED(Kind.CONFLICT, "wrong_state"),
    /** A follow-up continues an execution, and this task never started one (ADR 0006). */
    NOT_EXECUTED(Kind.CONFLICT, "wrong_state"),
    /** Its pull request is merged already. */
    MERGED(Kind.CONFLICT, "merged"),
    /** No words where words are the command: a blank correction, follow-up, answer or task, or an option the question lacks. */
    EMPTY(Kind.INVALID, "invalid"),
    /** Not one of the projects of the requester's groups. */
    UNKNOWN_PROJECT(Kind.NOT_FOUND, "unknown_project"),
    /** The project cannot take tasks now, e.g. while it is still being cloned. */
    PROJECT_UNAVAILABLE(Kind.CONFLICT, "project_unavailable");

    /** What sort of no it is: a page answers each with its own HTTP status. */
    public enum Kind {
        INVALID,
        FORBIDDEN,
        NOT_FOUND,
        CONFLICT
    }

    private final Kind kind;
    private final String code;

    Refusal(Kind kind, String code) {
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
