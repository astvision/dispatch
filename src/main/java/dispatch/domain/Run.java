package dispatch.domain;

import java.time.Instant;

/** A run as stored. Nullable: pid, pidStart, startedAt, finishedAt. */
public record Run(
        long taskId,
        int seq,
        RunKind kind,
        RunStatus status,
        String instruction,
        String requestedBy,
        Long pid,
        Instant pidStart,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt) {
}
