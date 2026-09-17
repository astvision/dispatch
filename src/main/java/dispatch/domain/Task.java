package dispatch.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * A task as stored. Nullable: baseSha, worktree, planJson, prUrl, failureReason, failureDetail, startedAt, completedAt.
 *
 * @param originRef channel reference of the message that created the task; replies thread under it
 * @param chatRef   channel reference of the chat the task belongs to
 */
public record Task(
        long id,
        String project,
        String title,
        String description,
        Phase phase,
        Requester requester,
        String originRef,
        String chatRef,
        UUID sessionId,
        String baseBranch,
        String baseSha,
        Path worktree,
        String planJson,
        String prUrl,
        FailureReason failureReason,
        String failureDetail,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt) {
}
