package dispatch.domain;

import java.time.Instant;
import java.util.List;

/**
 * A member's private message waiting to become a task (ADR 0012), or one part of such a message (ADR 0013). Nullable:
 * project (until chosen), taskId (until created), promptRef and splitState (until ✂️ is pressed), parentId and part (for
 * a whole message).
 *
 * @param chatRef   the private chat the message was written in
 * @param originRef the message itself, with "#part" appended for a part; the task keeps it as its origin
 * @param promptRef the prompt ✂️ was pressed on, redrawn when the split ends
 * @param topics    the split's proposed parts; empty unless proposed
 * @param parentId  the draft of the whole message this part came from
 * @param part      this part's number, from 1
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
        String promptRef,
        SplitState splitState,
        List<String> topics,
        Long parentId,
        Integer part,
        Instant createdAt,
        Instant updatedAt) {
}
