package dispatch.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.config.Config;
import dispatch.config.GroupWriter;
import dispatch.domain.OutboxKind;
import dispatch.domain.Requester;
import dispatch.store.Kv;
import dispatch.store.Outbox;
import dispatch.store.Tx;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Links a Telegram group the bot was added to with one of the projects, decided in Telegram by someone who may manage
 * Dispatch. The open prompt (the chat's title and the projects its buttons index into) is kept in the kv table until a
 * button answers it. Linking writes the config first and, once that worked, applies it to the running groups, as
 * {@link Membership} does for a join.
 */
public final class GroupLinks {

    public enum Result { LINKED, DECLINED, NOT_ALLOWED, STALE, CONFIG_FAILED }

    /** @param projects the project names in the order the prompt's buttons index into */
    public record Prompt(String title, List<String> projects) {
    }

    private static final String KEY_PREFIX = "group.link.";
    private static final Duration REASK_AFTER = Duration.ofSeconds(60);

    private final Groups groups;
    private final GroupWriter writer;
    private final Clock clock;
    private final Runnable wakeOutbox;

    public GroupLinks(Groups groups, GroupWriter writer, Clock clock, Runnable wakeOutbox) {
        this.groups = groups;
        this.writer = writer;
        this.clock = clock;
        this.wakeOutbox = wakeOutbox;
    }

    /**
     * Remembers an open prompt; false when one was asked for this chat less than {@link #REASK_AFTER} ago. That only folds
     * the near-simultaneous events of one add (new_chat_members and my_chat_member); an older prompt, ignored or lost,
     * is replaced so that adding the bot again, or a command to it, asks again rather than leaving it silent for good.
     */
    public boolean open(Tx tx, long chatId, String title, List<String> projects) {
        boolean recent = Kv.get(tx, key(chatId)).map(Json::read)
                .map(stored -> clock.millis() - stored.path("askedAt").asLong() < REASK_AFTER.toMillis())
                .orElse(false);
        if (recent) {
            return false;
        }
        ObjectNode prompt = Json.object().put("title", title).put("askedAt", clock.millis());
        projects.forEach(prompt.putArray("projects")::add);
        Kv.put(tx, key(chatId), prompt.toString());
        return true;
    }

    public Optional<Prompt> prompt(Tx tx, long chatId) {
        return Kv.get(tx, key(chatId)).map(Json::read).map(stored -> {
            List<String> projects = new ArrayList<>();
            stored.path("projects").forEach(project -> projects.add(project.asText()));
            return new Prompt(stored.path("title").asText(), List.copyOf(projects));
        });
    }

    /** Drops the open prompt without an answer, e.g. when it could not be delivered and the bot left the chat. */
    public void forget(Tx tx, long chatId) {
        Kv.delete(tx, key(chatId));
    }

    public Result link(Tx tx, Requester presser, long chatId, int projectIndex) {
        if (!groups.mayManage(presser.ref())) {
            tx.afterCommit(() -> Log.warn("group.link_not_allowed", "chat_id", chatId, "presser", presser.ref()));
            return Result.NOT_ALLOWED;
        }
        Optional<Prompt> prompt = prompt(tx, chatId);
        if (groups.isGroupChat(chatRef(chatId))) {
            // Linked meanwhile (e.g. from a second prompt): this one can never be answered now.
            forget(tx, chatId);
            return Result.STALE;
        }
        if (prompt.isEmpty() || projectIndex < 0 || projectIndex >= prompt.get().projects().size()) {
            return Result.STALE;
        }
        String project = prompt.get().projects().get(projectIndex);
        Config.Telegram updated;
        try {
            updated = writer.link(chatId, prompt.get().title(), project);
        } catch (RuntimeException e) {
            tx.afterCommit(() -> Log.error("group.link_failed", e, "chat_id", chatId, "project", project));
            return Result.CONFIG_FAILED;
        }
        forget(tx, chatId);
        Outbox.enqueue(tx, null, OutboxKind.GROUP_LINKED, chatRef(chatId), null, Json.object().put("projects", project), clock.instant());
        tx.afterCommit(() -> groups.replace(updated));
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(() -> Log.info("group.linked", "chat_id", chatId, "project", project, "presser", presser.ref()));
        return Result.LINKED;
    }

    public Result decline(Tx tx, Requester presser, long chatId) {
        if (!groups.mayManage(presser.ref())) {
            tx.afterCommit(() -> Log.warn("group.link_not_allowed", "chat_id", chatId, "presser", presser.ref()));
            return Result.NOT_ALLOWED;
        }
        if (prompt(tx, chatId).isEmpty()) {
            return Result.STALE;
        }
        forget(tx, chatId);
        tx.afterCommit(() -> Log.info("group.declined", "chat_id", chatId, "presser", presser.ref()));
        return Result.DECLINED;
    }

    /** A linked group became a supergroup: its chat id changes, in the config and in the running groups. */
    public void migrated(Tx tx, long oldChatId, long newChatId) {
        Config.Telegram updated;
        try {
            updated = writer.migrate(oldChatId, newChatId);
        } catch (RuntimeException e) {
            tx.afterCommit(() -> Log.error("group.migrate_failed", e, "chat_id", oldChatId, "new_chat_id", newChatId,
                    "action", "set the group's chatId in telegram.groups to the new id and restart"));
            return;
        }
        tx.afterCommit(() -> groups.replace(updated));
        tx.afterCommit(() -> Log.info("group.migrated", "chat_id", oldChatId, "new_chat_id", newChatId));
    }

    private static String key(long chatId) {
        return KEY_PREFIX + chatId;
    }

    private static String chatRef(long chatId) {
        return "telegram:" + chatId;
    }
}
