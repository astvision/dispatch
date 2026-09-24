package dispatch.core;

import dispatch.Json;
import dispatch.domain.GroupAck;
import dispatch.domain.GroupReaction;
import dispatch.domain.OutboxKind;
import dispatch.domain.Task;
import dispatch.store.MemberPrefs;
import dispatch.store.Outbox;
import dispatch.store.Tx;
import java.time.Instant;

/**
 * Reacts on the group message that started a group-origin task instead of the old ✉️ line, by the requester's own
 * choice (G-1e). A task with no real group to react in — a personal bot's own chat (ADR 0014), or one given directly
 * rather than by mentioning the bot in a group — gets nothing here; TASK_QUEUED and the outcome lines already cover
 * a real group on their own.
 */
final class GroupAcks {

    private GroupAcks() {
    }

    static void react(Tx tx, Task task, GroupReaction state, Instant now) {
        if (!task.hasGroupChat()) {
            return;
        }
        String origin = task.groupOriginRef();
        if (origin == null) {
            return;
        }
        GroupAck pref = MemberPrefs.groupAck(tx, userId(task.requester().ref()));
        if (pref == GroupAck.SILENT) {
            return;
        }
        Outbox.enqueue(tx, task.id(), OutboxKind.GROUP_REACTION, task.chatRef(), origin,
                Json.object().put("emoji", state.emoji()), now);
        if (pref == GroupAck.REACTION_AND_LINE && state == GroupReaction.TASK_CREATED) {
            Outbox.enqueue(tx, task.id(), OutboxKind.GROUP_WORKING, task.chatRef(), origin,
                    Json.object().put("requester", firstName(task.requester().name())), now);
        }
    }

    /** The configured name's first word, as the group's own mention texts already call a member (G-1b/G-1c). */
    private static String firstName(String name) {
        return name.strip().split("\\s+", 2)[0];
    }

    static long userId(String ref) {
        return Long.parseLong(ref.substring("telegram:".length()));
    }
}
