package dispatch.domain;

public enum RunKind {
    /** Read-only analysis that produces a plan. */
    PLAN,
    /** Implements an approved plan or a follow-up (M2). */
    EXECUTE,
    /** Repeats only the commit/push/PR step after a delivery failure (M3). */
    DELIVER,
    /** Proposes how to split a draft's message into tasks (ADR 0013); belongs to no task and is never stored as a run. */
    SPLIT
}
