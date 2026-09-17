package dispatch.core;

public enum JoinDecision {
    APPROVED,
    DENIED,
    NOT_ADMIN,
    NOT_FOUND,
    ALREADY_DECIDED,
    UNKNOWN_GROUP,
    /** The config file could not take the new member; the request stays open and the cause is logged. */
    CONFIG_FAILED
}
