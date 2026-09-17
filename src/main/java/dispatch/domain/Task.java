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
        Priority priority,
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

    /** The message that gave the task, if it was written in the task's group chat (before ADR 0012); otherwise null. */
    public String groupOriginRef() {
        return originRef.startsWith(chatRef + "/") ? originRef : null;
    }

    /** The message that gave the task, if it was written in the requester's private chat (ADR 0012); otherwise null. */
    public String privateOriginRef() {
        return originRef.startsWith(requester.ref() + "/") ? originRef : null;
    }
}
