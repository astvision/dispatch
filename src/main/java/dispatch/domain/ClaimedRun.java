package dispatch.domain;

/** A run the scheduler has just moved from QUEUED to RUNNING. */
public record ClaimedRun(long taskId, int seq, RunKind kind) {
}
