package dispatch.domain;

/** Where a task is in its lifecycle; see docs/ARCHITECTURE.md "Task phases". */
public enum Phase {
    PLANNING,
    AWAITING_APPROVAL,
    EXECUTING,
    COMPLETED,
    FAILED,
    REJECTED,
    CANCELLED;

    /** Active tasks can still be cancelled and are listed by /tasks. */
    public boolean isActive() {
        return this == PLANNING || this == AWAITING_APPROVAL || this == EXECUTING;
    }
}
