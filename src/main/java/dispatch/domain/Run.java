package dispatch.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A run as stored. Nullable: requestedByName (runs from before schema v2), pid, pidStart, startedAt, finishedAt, and the
 * results set when it ends: costUsd, turns, failureReason.
 */
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
        Instant finishedAt,
        BigDecimal costUsd,
        Integer turns,
        FailureReason failureReason) {
}
