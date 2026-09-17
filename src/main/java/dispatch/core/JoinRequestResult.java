package dispatch.core;

public enum JoinRequestResult {
    /** A new request: the person is told, and every admin is asked. */
    REQUESTED,
    /** An earlier request still waits; nothing is sent again. */
    PENDING,
    /** An admin said no within the last day; nothing is sent. */
    RECENTLY_DENIED
}
