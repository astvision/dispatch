package dispatch.core;

public enum FollowUpResult {
    QUEUED,
    /** The task's pull request is merged, so the follow-up became a new task of its own. */
    NEW_TASK,
    NOT_ALLOWED,
    NOT_FOUND,
    EMPTY,
    REFUSED
}
