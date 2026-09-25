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
import java.time.Instant;
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

    /**
     * @param projects  the project names in the order the prompt's buttons index into
     * @param sentTo    the private chat the prompt was delivered to, null until it was
     * @param messageId the prompt's message there, null until it was delivered
     */
    public record Prompt(String title, List<String> projects, Long sentTo, Long messageId) {
    }

    private static final String KEY_PREFIX = "group.link.";
    private static final Duration REASK_AFTER = Duration.ofSeconds(60);
    private static final String LEFT_PREFIX = "left.";
    /** A migration this soon after the bot left the new chat is taken to be why it left: its own add event came first. */
    private static final Duration LEFT_RECENTLY = Duration.ofMinutes(10);

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
            return new Prompt(stored.path("title").asText(), List.copyOf(projects),
                    stored.hasNonNull("sentTo") ? stored.get("sentTo").asLong() : null,
                    stored.hasNonNull("messageId") ? stored.get("messageId").asLong() : null);
        });
    }

    /** Records where the open prompt was delivered, so that a migration closing it can edit it; nothing if none is open. */
    public void sent(Tx tx, long chatId, long sentTo, long messageId) {
        Kv.get(tx, key(chatId)).map(Json::read).ifPresent(stored ->
                Kv.put(tx, key(chatId), ((ObjectNode) stored).put("sentTo", sentTo).put("messageId", messageId).toString()));
    }

    /** Drops the open prompt without an answer, e.g. when it could not be delivered and the bot left the chat. */
    public void forget(Tx tx, long chatId) {
        Kv.delete(tx, key(chatId));
    }

    /** Remembers that the bot left an unknown chat, so a migration naming it soon after can say the bot is not there. */
    public void left(Tx tx, long chatId) {
        Kv.put(tx, LEFT_PREFIX + chatId, clock.instant().toString());
    }

    /** @param projectIndex as the button carries it: a long, so a crafted value past int's range is stale, not wrapped round */
    public Result link(Tx tx, Requester presser, long chatId, long projectIndex) {
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
        return write(tx, presser, chatId, prompt.get().title(), prompt.get().projects().get((int) projectIndex));
    }

    /**
     * Links the chat straight to {@code project}, as adding the bot through the project's add link asks (ADR 0025), and
     * forgets any prompt the add event opened meanwhile; the caller closes it where it was delivered.
     */
    public Result linkTo(Tx tx, Requester presser, long chatId, String title, String project) {
        if (!groups.mayManage(presser.ref())) {
            tx.afterCommit(() -> Log.warn("group.link_not_allowed", "chat_id", chatId, "presser", presser.ref()));
            return Result.NOT_ALLOWED;
        }
        return write(tx, presser, chatId, title, project);
    }

    private Result write(Tx tx, Requester presser, long chatId, String title, String project) {
        Config.Telegram updated;
        try {
            updated = writer.link(chatId, title, project);
        } catch (GroupWriter.UnknownProject e) {
            // Removed from the config since the prompt listed it: no answer to this prompt can link anything now.
            forget(tx, chatId);
            tx.afterCommit(() -> Log.info("group.link_stale", "chat_id", chatId, "project", project));
            return Result.STALE;
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

    /**
     * A linked group became a supergroup: its chat id changes, in the config and in the running groups. A prompt opened
     * for the new id (its own events can arrive before this) or the old one can never be answered now and is forgotten.
     * When the config could not be written they stay: linking the new id from its prompt is then the way to repair it.
     *
     * @return the prompts forgotten, for the caller to close in the chats they were delivered to
     */
    public List<Prompt> migrated(Tx tx, long oldChatId, long newChatId) {
        Config.Telegram updated;
        try {
            updated = writer.migrate(oldChatId, newChatId);
        } catch (RuntimeException e) {
            tx.afterCommit(() -> Log.error("group.migrate_failed", e, "chat_id", oldChatId, "new_chat_id", newChatId,
                    "action", "set the group's chatId in telegram.groups to the new id and restart"));
            return List.of();
        }
        if (leftRecently(tx, newChatId)) {
            askToReadd(tx, oldChatId, newChatId);
        }
        List<Prompt> forgotten = new ArrayList<>();
        for (long chatId : List.of(newChatId, oldChatId)) {
            prompt(tx, chatId).ifPresent(forgotten::add);
            forget(tx, chatId);
        }
        tx.afterCommit(() -> groups.replace(updated));
        tx.afterCommit(() -> Log.info("group.migrated", "chat_id", oldChatId, "new_chat_id", newChatId, "prompts_closed", forgotten.size()));
        return List.copyOf(forgotten);
    }

    private boolean leftRecently(Tx tx, long chatId) {
        return Kv.get(tx, LEFT_PREFIX + chatId).map(Instant::parse)
                .filter(leftAt -> leftAt.isAfter(clock.instant().minus(LEFT_RECENTLY)))
                .isPresent();
    }

    /**
     * The link moved to a chat the bot is no longer in, so nothing reaches it until someone adds the bot back: whoever
     * may manage Dispatch among the group's members is told privately. Read before the running groups are replaced.
     */
    private void askToReadd(Tx tx, long oldChatId, long newChatId) {
        Optional<Config.Group> group = groups.all().stream()
                .filter(candidate -> Long.valueOf(oldChatId).equals(candidate.chatId())).findFirst();
        String name = group.map(Config.Group::name).orElse(String.valueOf(newChatId));
        List<String> managers = group.stream().flatMap(linked -> linked.members().stream())
                .map(member -> chatRef(member.id())).filter(groups::mayManage).toList();
        for (String manager : managers) {
            Outbox.enqueue(tx, null, OutboxKind.GROUP_READD, manager, null, Json.object().put("group", name), clock.instant());
        }
        tx.afterCommit(() -> Log.error("group.migrated_after_leaving", null, "chat_id", oldChatId, "new_chat_id", newChatId,
                "group", name, "told", managers.size()));
        tx.afterCommit(wakeOutbox);
    }

    private static String key(long chatId) {
        return KEY_PREFIX + chatId;
    }

    private static String chatRef(long chatId) {
        return "telegram:" + chatId;
    }
}
