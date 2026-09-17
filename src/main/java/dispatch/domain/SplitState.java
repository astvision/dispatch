package dispatch.domain;

/** How far splitting a draft's message has come (ADR 0013); a draft nobody asked to split has none. */
public enum SplitState {
    /** The agent is looking for the message's topics. */
    SPLITTING,
    /** Several topics were found; the writer splits the message or keeps it whole. */
    PROPOSED,
    /** The message holds one task. */
    ONE_TOPIC,
    /** The writer kept the message as one task. */
    KEPT,
    /** The agent failed, or a restart cut it off; the writer may try again. */
    FAILED
}
