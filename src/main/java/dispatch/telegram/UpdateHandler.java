package dispatch.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.core.Projects;
import dispatch.core.TaskService;
import dispatch.domain.OutboxKind;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Kv;
import dispatch.store.Outbox;
import dispatch.store.Tx;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Turns one Telegram update into core calls. The update's effects and the next offset commit in one transaction, so a
 * crash makes Telegram deliver it again and nothing is lost or applied twice (ADR 0010). The configured team group and
 * members' private chats with the bot are served (ADR 0011); other groups are left, other private chats ignored. Besides
 * commands and buttons, a reply to a plan message is a correction.
 */
public final class UpdateHandler {

    static final String OFFSET_KEY = "telegram.offset";
    private static final Set<String> JOINED_STATUSES = Set.of("member", "administrator");

    private final Database db;
    private final TaskService tasks;
    private final Projects projects;
    private final BotApi api;
    private final Renderer renderer;
    private final long groupChatId;
    private final String botUsername;
    private final Clock clock;
    private final Runnable wakeOutbox;

    public UpdateHandler(Database db, TaskService tasks, Projects projects, BotApi api, Renderer renderer, long groupChatId,
                         String botUsername, Clock clock, Runnable wakeOutbox) {
        this.db = db;
        this.tasks = tasks;
        this.projects = projects;
        this.api = api;
        this.renderer = renderer;
        this.groupChatId = groupChatId;
        this.botUsername = botUsername;
        this.clock = clock;
        this.wakeOutbox = wakeOutbox;
    }

    public void handle(JsonNode update) {
        long updateId = update.path("update_id").asLong();
        db.transaction(tx -> {
            if (update.has("message")) {
                onMessage(tx, update.get("message"));
            } else if (update.has("callback_query")) {
                onCallback(tx, update.get("callback_query"));
            } else if (update.has("my_chat_member")) {
                onMembershipChange(tx, update.get("my_chat_member"));
            }
            Kv.put(tx, OFFSET_KEY, Long.toString(updateId + 1));
        });
    }

    /** Moves past an update whose handling failed (already logged by the caller) so it cannot block the ones after it. */
    public void skip(long updateId) {
        db.transaction(tx -> Kv.put(tx, OFFSET_KEY, Long.toString(updateId + 1)));
    }

    public long nextOffset() {
        return db.transactionReturning(tx -> Kv.get(tx, OFFSET_KEY)).map(Long::parseLong).orElse(0L);
    }

    private void onMessage(Tx tx, JsonNode message) {
        JsonNode chat = message.path("chat");
        long chatId = chat.path("id").asLong();
        JsonNode from = message.path("from");
        boolean privateChat = isPrivateChatOf(chat, from);
        if (privateChat && tasks.isMember(Refs.user(from.get("id").asLong()))) {
            onChatMessage(tx, message, true);
            return;
        }
        if (chatId != groupChatId) {
            ignoreForeignChat(tx, chat, from);
            return;
        }
        if (message.has("migrate_to_chat_id")) {
            long newChatId = message.get("migrate_to_chat_id").asLong();
            tx.afterCommit(() -> Log.error("telegram.group_migrated", null, "chat_id", chatId, "new_chat_id", newChatId,
                    "action", "set telegram.groupChatId to the new id and restart"));
            return;
        }
        if (from.has("id")) {
            onChatMessage(tx, message, false);
        }
    }

    /** A message in the team group, or in a member's private chat with the bot, from someone with a user id. */
    private void onChatMessage(Tx tx, JsonNode message, boolean privateChat) {
        long chatId = message.path("chat").path("id").asLong();
        JsonNode from = message.path("from");
        Requester who = new Requester(Refs.user(from.get("id").asLong()), displayName(from));
        String origin = Refs.message(chatId, message.path("message_id").asLong());
        String chatRef = Refs.chat(chatId);
        Optional<Command> parsed = Command.parse(message);
        if (parsed.isEmpty()) {
            correction(tx, message, who, origin, chatRef);
            return;
        }
        Command command = parsed.get();
        if (!command.addressedTo(botUsername)) {
            return;
        }
        switch (command.name()) {
            // Tasks start in the group, so the whole team sees every one arrive (ADR 0011).
            case "task" -> {
                if (privateChat) {
                    enqueue(tx, OutboxKind.TASK_IN_GROUP_ONLY, chatRef, origin, Json.object().put("bot", botUsername));
                    return;
                }
                String[] projectAndText = command.args().split("\\s+", 2);
                String text = projectAndText.length > 1 ? projectAndText[1].strip() : "";
                tasks.create(tx, who, projectAndText[0], withRepliedMessage(text, message.path("reply_to_message")), origin, chatRef);
            }
            case "status" -> tasks.status(tx, origin, chatRef);
            case "history" -> taskId(command.args()).ifPresentOrElse(
                    id -> tasks.timeline(tx, id, origin, chatRef),
                    () -> tasks.history(tx, origin, chatRef));
            case "cancel" -> taskId(command.args()).ifPresentOrElse(
                    id -> tasks.cancel(tx, who, id, origin, chatRef),
                    () -> help(tx, origin, chatRef, privateChat));
            case "help", "start" -> help(tx, origin, chatRef, privateChat);
            default -> {
                // Telegram marks any leading "/word" as a command, so a correction like "/api/login fails too" lands here.
                if (!correction(tx, message, who, origin, chatRef)) {
                    tx.afterCommit(() -> Log.info("telegram.command_ignored", "command", command.name()));
                }
            }
        }
    }

    /** Treats a reply to one of the bot's plan messages as a correction of that plan; false for any other message. */
    private boolean correction(Tx tx, JsonNode message, Requester who, String origin, String chatRef) {
        JsonNode repliedTo = message.path("reply_to_message");
        if (!repliedTo.has("message_id")) {
            return false;
        }
        long chatId = message.path("chat").path("id").asLong();
        String repliedRef = Refs.message(chatId, repliedTo.get("message_id").asLong());
        Optional<Outbox.Sent> sent = Outbox.findSent(tx, repliedRef);
        if (sent.isEmpty() || sent.get().kind() != OutboxKind.PLAN_READY) {
            String kind = sent.map(found -> found.kind().name()).orElse("unknown");
            tx.afterCommit(() -> Log.info("telegram.reply_ignored", "replied_to", repliedRef, "kind", kind));
            return false;
        }
        int planSeq = Json.read(sent.get().payload()).path("planSeq").asInt();
        tasks.correct(tx, who, sent.get().taskId(), planSeq, text(message), origin, chatRef);
        return true;
    }

    private void onCallback(Tx tx, JsonNode callback) {
        String callbackId = callback.path("id").asText();
        JsonNode from = callback.path("from");
        JsonNode chat = callback.path("message").path("chat");
        boolean servedChat = chat.path("id").asLong() == groupChatId || isPrivateChatOf(chat, from);
        String[] parts = callback.path("data").asText().split(":");
        boolean known = parts.length == 3 && (parts[0].equals("approve") || parts[0].equals("reject"));
        if (!servedChat || !known || !from.has("id")) {
            answer(tx, callbackId, "callback.unknown");
            return;
        }
        Optional<Long> taskId = taskId(parts[1]);
        Optional<Long> planSeq = taskId(parts[2]);
        if (taskId.isEmpty() || planSeq.isEmpty()) {
            answer(tx, callbackId, "callback.unknown");
            return;
        }
        Requester who = new Requester(Refs.user(from.get("id").asLong()), displayName(from));
        int seq = planSeq.get().intValue();
        String answer = parts[0].equals("approve")
                ? switch (tasks.approve(tx, who, taskId.get(), seq)) {
                    case APPROVED -> "callback.approved";
                    case NOT_ALLOWED -> "callback.notAllowed";
                    case NOT_FOUND -> "callback.notFound";
                    case NOT_REQUESTER -> "callback.notRequester";
                    case WRONG_STATE -> "callback.wrongState";
                    case STALE_PLAN -> "callback.stale";
                    case OPEN_QUESTIONS -> "callback.openQuestions";
                }
                : switch (tasks.reject(tx, who, taskId.get(), seq)) {
                    case REJECTED -> "callback.rejected";
                    case NOT_ALLOWED -> "callback.notAllowed";
                    case NOT_FOUND -> "callback.notFound";
                    case NOT_REQUESTER -> "callback.notRequester";
                    case WRONG_STATE -> "callback.wrongState";
                    case STALE_PLAN -> "callback.stale";
                };
        answer(tx, callbackId, answer);
    }

    private void onMembershipChange(Tx tx, JsonNode change) {
        JsonNode chat = change.path("chat");
        long chatId = chat.path("id").asLong();
        String status = change.path("new_chat_member").path("status").asText();
        if (chatId == groupChatId) {
            tx.afterCommit(() -> Log.info("telegram.membership_changed", "chat_id", chatId, "status", status));
        } else if (JOINED_STATUSES.contains(status) && isGroup(chat)) {
            leave(tx, chatId, change.path("from"));
        }
    }

    private void ignoreForeignChat(Tx tx, JsonNode chat, JsonNode from) {
        long chatId = chat.path("id").asLong();
        if (isGroup(chat)) {
            leave(tx, chatId, from);
            return;
        }
        String type = chat.path("type").asText();
        long userId = from.path("id").asLong();
        tx.afterCommit(() -> Log.warn("telegram.chat_ignored", "chat_id", chatId, "type", type, "user_id", userId));
    }

    private void leave(Tx tx, long chatId, JsonNode from) {
        long userId = from.path("id").asLong();
        tx.afterCommit(() -> {
            Log.warn("telegram.leaving_foreign_group", "chat_id", chatId, "user_id", userId);
            bestEffort("leaveChat", () -> api.leaveChat(chatId));
        });
    }

    private void answer(Tx tx, String callbackId, String textKey) {
        String text = renderer.text(textKey);
        tx.afterCommit(() -> bestEffort("answerCallbackQuery", () -> api.answerCallbackQuery(callbackId, text)));
    }

    private void help(Tx tx, String origin, String chatRef, boolean privateChat) {
        ObjectNode payload = Json.object().put("bot", botUsername).put("privateChat", privateChat);
        ArrayNode listed = payload.putArray("projects");
        projects.all().forEach(project -> listed.addObject().put("name", project.name()).put("alias", project.alias()));
        enqueue(tx, OutboxKind.HELP, chatRef, origin, payload);
    }

    private void enqueue(Tx tx, OutboxKind kind, String chatRef, String origin, ObjectNode payload) {
        Outbox.enqueue(tx, null, kind, chatRef, origin, payload, clock.instant());
        tx.afterCommit(wakeOutbox);
    }

    /** A private chat is its user's own: Telegram gives it the user's id. */
    private static boolean isPrivateChatOf(JsonNode chat, JsonNode from) {
        return chat.path("type").asText().equals("private") && from.has("id") && chat.path("id").asLong() == from.get("id").asLong();
    }

    private static String withRepliedMessage(String text, JsonNode repliedTo) {
        String replied = text(repliedTo);
        if (replied.isBlank()) {
            return text;
        }
        return text.isBlank() ? replied : replied + "\n\n" + text;
    }

    /** A message's text, or the caption of a photo or document. */
    private static String text(JsonNode message) {
        return message.hasNonNull("text") ? message.get("text").asText() : message.path("caption").asText("");
    }

    private static Optional<Long> taskId(String text) {
        try {
            return Optional.of(Long.parseLong(text.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static boolean isGroup(JsonNode chat) {
        String type = chat.path("type").asText();
        return type.equals("group") || type.equals("supergroup");
    }

    private static String displayName(JsonNode user) {
        String first = user.path("first_name").asText("");
        String last = user.path("last_name").asText("");
        String name = (first + " " + last).strip();
        return name.isEmpty() ? user.path("username").asText("?") : name;
    }

    /** Side effects that only improve the chat experience; a failure is logged, not retried. */
    private static void bestEffort(String method, Runnable call) {
        try {
            call.run();
        } catch (TelegramException e) {
            Log.warn("telegram.best_effort_failed", "method", method, "error", e.getMessage());
        }
    }

    /**
     * A /command at the start of a message.
     *
     * @param bot the bot named in /command@bot, null when none was named
     */
    record Command(String name, String bot, String args) {

        static Optional<Command> parse(JsonNode message) {
            String text = text(message);
            JsonNode entities = message.has("entities") ? message.get("entities") : message.path("caption_entities");
            for (JsonNode entity : entities) {
                if (!entity.path("type").asText().equals("bot_command") || entity.path("offset").asInt() != 0) {
                    continue;
                }
                int length = entity.path("length").asInt();
                String token = text.substring(1, length);
                int at = token.indexOf('@');
                String name = (at >= 0 ? token.substring(0, at) : token).toLowerCase(Locale.ROOT);
                return Optional.of(new Command(name, at >= 0 ? token.substring(at + 1) : null, text.substring(length).strip()));
            }
            return Optional.empty();
        }

        boolean addressedTo(String botUsername) {
            return bot == null || bot.equalsIgnoreCase(botUsername);
        }
    }
}
