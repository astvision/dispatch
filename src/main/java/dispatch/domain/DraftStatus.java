package dispatch.domain;

public enum DraftStatus {
    /** Waiting for its project and priority. */
    OPEN,
    CREATED,
    /** Nobody answered within a day. */
    EXPIRED,
    /** Split into parts, each a draft of its own (ADR 0013). */
    SPLIT,
    /** Its writer said it is not a task, e.g. a question that mentioned them in a group. */
    DISCARDED
}
