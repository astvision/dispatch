package dispatch.domain;

import java.time.Instant;

/** A run as stored. Nullable: requestedByName (runs from before schema v2), pid, pidStart, startedAt, finishedAt. */
public record Run(
        long taskId,
        int seq,
        RunKind kind,
        RunStatus status,
        String instruction,
        String requestedBy,
        String requestedByName,
        Long pid,
        Instant pidStart,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt) {
}
