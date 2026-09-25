package dispatch.core;

/** The outcome of answering a plan's open question, or of asking to write the answer (G-1d). */
public enum AnswerResult {
    ANSWERED,
    /** The requester was asked to write their own answer. */
    PROMPTED,
    /** The requester's prompt for their own answer is already open; no second one is sent. */
    PROMPT_OPEN,
    EMPTY,
    NOT_ALLOWED,
    NOT_FOUND,
    NOT_REQUESTER,
    /** The question was answered already, and its plan is still the current one. */
    ALREADY_ANSWERED,
    /** A later question while an earlier one is still open: they are answered in order (ADR 0027). */
    OUT_OF_ORDER,
    /** An older plan's question, or a task no longer awaiting approval. */
    STALE
}
