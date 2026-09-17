package dispatch.domain;

import java.time.Instant;

/**
 * A member's private message waiting to become a task (ADR 0012). Nullable: project (until chosen), taskId (until created).
 *
 * @param chatRef   the private chat the message was written in
 * @param originRef the message itself; the task keeps it as its origin
 */
public record Draft(
        long id,
        String requesterRef,
        String requesterName,
        String chatRef,
        String originRef,
        String description,
        String project,
        DraftStatus status,
        Long taskId,
        Instant createdAt,
        Instant updatedAt) {
}
