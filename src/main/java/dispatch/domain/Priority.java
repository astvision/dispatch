package dispatch.domain;

/** How urgently a task should run; declared from most to least urgent (ADR 0012). */
public enum Priority {
    URGENT,
    NORMAL,
    LOW
}
