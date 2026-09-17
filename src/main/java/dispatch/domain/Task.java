package dispatch.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * A task as stored. Nullable: buildSessionId, baseSha, worktree, planJson, prUrl, topicRef, failureReason, failureDetail,
 * startedAt, completedAt.
 *
 * @param sessionId      the planning session: the plan and its corrections (ADR 0017)
 * @param buildSessionId the building session, once the first execution run started it from the approved plan
 * @param originRef channel reference of the message that created the task; replies thread under it
 * @param chatRef   channel reference of the chat the task belongs to: its project's group chat, or the requester's private
 *                  chat when that group has none (ADR 0014)
 * @param topicRef  the task's own topic in the requester's private chat, once the channel created one
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
        UUID buildSessionId,
        String baseBranch,
        String baseSha,
        Path worktree,
        String planJson,
        String prUrl,
        String topicRef,
        FailureReason failureReason,
        String failureDetail,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt) {

    /**
     * The message that gave the task, if it was written in the chat the task belongs to: its group chat before ADR 0012,
     * or the requester's private chat for a group without a chat; otherwise null.
     */
    public String groupOriginRef() {
        return originRef.startsWith(chatRef + "/") ? originRef : null;
    }

    /** False when the task belongs to its requester's private chat, where its details go anyway (ADR 0014). */
    public boolean hasGroupChat() {
        return !chatRef.equals(requester.ref());
    }

    /** The message that gave the task, if it was written in the requester's private chat (ADR 0012); otherwise null. */
    public String privateOriginRef() {
        return originRef.startsWith(requester.ref() + "/") ? originRef : null;
    }
}
