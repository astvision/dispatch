package dispatch.core;

/** The outcome of answering a plan's open question, or of asking to write the answer (G-1d). */
public enum AnswerResult {
    ANSWERED,
    /** The requester was asked to write their own answer. */
    PROMPTED,
    EMPTY,
    NOT_ALLOWED,
    NOT_FOUND,
    NOT_REQUESTER,
    /** An older plan's question, one already answered, or a task no longer awaiting approval. */
    STALE
}
