package dispatch.worker;

/**
 * The version of what a worker and its team machine send each other (ADR 0039): jobs, results, readiness and progress.
 * Each side says its number on every poll, and work passes only between equal numbers, so neither ever reads a shape it
 * does not know. WireContractTest fails when the wire changes: raise the number then.
 */
public final class WorkerProtocol {

    /** 4 since 2026-10-06: AgentOutcome.LIMITED, FailureReason.USAGE_LIMIT and AgentResult.limit (spec: usage limit). */
    public static final int VERSION = 4;

    private WorkerProtocol() {
    }
}
