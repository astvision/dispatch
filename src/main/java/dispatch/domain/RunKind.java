package dispatch.domain;

public enum RunKind {
    /** Read-only analysis that produces a plan. */
    PLAN,
    /** Implements an approved plan or a follow-up (M2). */
    EXECUTE,
    /** Repeats only the commit/push/PR step after a delivery failure (M3). */
    DELIVER
}
