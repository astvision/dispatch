package dispatch.core;

public enum DraftChoice {
    PROJECT_CHOSEN,
    CREATED,
    ALREADY_CREATED,
    EXPIRED,
    NOT_FOUND,
    NOT_REQUESTER,
    CHOOSE_PROJECT_FIRST,
    PROJECT_UNAVAILABLE,
    /** ✂️ was pressed: the agent is looking for the message's topics. */
    SPLITTING,
    /** The message became one draft per proposed part. */
    SPLIT,
    KEPT_WHOLE,
    ALREADY_SPLIT,
    /** Nothing to split now: a part, a split already running or answered, or a message kept whole. */
    CANNOT_SPLIT
}
