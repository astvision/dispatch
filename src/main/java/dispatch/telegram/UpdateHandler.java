package dispatch.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.Redactor;
import dispatch.config.Config;
import dispatch.core.AnswerResult;
import dispatch.core.Assistant;
import dispatch.core.AssistantActions;
import dispatch.core.DraftChoice;
import dispatch.core.DraftResult;
import dispatch.core.GroupLinks;
import dispatch.core.Groups;
import dispatch.core.JoinDecision;
import dispatch.core.JoinRequestResult;
import dispatch.core.Membership;
import dispatch.core.PriorityResult;
import dispatch.core.Projects;
import dispatch.core.TaskService;
import dispatch.domain.Attachment;
import dispatch.domain.OutboxKind;
import dispatch.domain.Phase;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.Task;
import dispatch.store.Conversations;
import dispatch.store.Database;
import dispatch.store.Kv;
import dispatch.store.Outbox;
import dispatch.store.TelegramUsers;
import dispatch.store.Tx;
import dispatch.store.Workers;
import dispatch.worker.WorkerKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turns one Telegram update into core calls. The update's effects and the next offset commit in one transaction, so a
 * crash makes Telegram deliver it again and nothing is lost or applied twice (ADR 0010). The configured groups and
 * members' private chats with the bot are served (ADR 0011, 0012); other groups are left, other private chats ignored. A
 * group sees only its own projects, a member those of all their groups. Besides commands and buttons, a reply to a plan
 * message is a correction, one to a plan's question message answers it (G-1d) and one to a task's result is a follow-up.
 * A member who mentions the bot in their linked group gives a task, drafted in their private chat all the same (G-1b);
 * one who mentions a fellow member there gives it to them, drafted in theirs (G-1c).
 */
public final class UpdateHandler {

    static final String OFFSET_KEY = "telegram.offset";
    private static final Set<String> JOINED_STATUSES = Set.of("member", "administrator");
    /** Messages about a task's outcome; a reply to one is a follow-up (ADR 0006). */
    private static final Set<OutboxKind> RESULTS = Set.of(OutboxKind.TASK_COMPLETED, OutboxKind.TASK_COMPLETED_SHORT,
            OutboxKind.TASK_FAILED, OutboxKind.TASK_FAILED_SHORT);
    private static final Set<String> COMMANDS =
            Set.of("task", "status", "history", "stats", "cancel", "retry", "worker", "manage", "projects", "help", "start");
    /** What Telegram accepts as a deep link's start parameter. */
    private static final java.util.regex.Pattern START_PARAMETER = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Duration UNKNOWN_NOTICE_INTERVAL = Duration.ofHours(24);

    private final Database db;
    private final TaskService tasks;
    private final Membership membership;
    private final Groups groups;
    private final Projects projects;
    private final BotApi api;
    private final Renderer renderer;
    private final Redactor redactor;
    private final String botUsername;
    private final Clock clock;
    private final Runnable wakeOutbox;
    private final WorkerKeys workers;
    private final String workerUrl;
    private final String miniAppUrl;
    private final GroupLinks groupLinks;
    private final Assistant assistant;
    private final AssistantActions assistantActions;

    /** @param redactor masks messages this handler edits directly, as the outbox sender does for everything it sends */
    public UpdateHandler(Database db, TaskService tasks, Membership membership, Groups groups, Projects projects, BotApi api,
                         Renderer renderer, Redactor redactor, String botUsername, Clock clock, Runnable wakeOutbox) {
        this(db, tasks, membership, groups, projects, api, renderer, redactor, botUsername, clock, wakeOutbox, null, null, null, null,
                null, null);
    }

    /**
     * @param redactor   masks messages this handler edits directly, as the outbox sender does for everything it sends
     * @param workers    null in personal mode, where a member has nothing to pair
     * @param workerUrl  the URL members' computers reach this machine on; null in personal mode
     * @param miniAppUrl where Telegram opens the Mini App; null when it is not configured, and /manage says so
     * @param groupLinks asks whoever may manage Dispatch which project an unknown group is for; null to leave every
     *                   unknown group, as before group linking
     * @param assistant  answers a member's plain private messages (A-1); null to draft every one as a task, as before
     */
    public UpdateHandler(Database db, TaskService tasks, Membership membership, Groups groups, Projects projects, BotApi api,
                         Renderer renderer, Redactor redactor, String botUsername, Clock clock, Runnable wakeOutbox,
                         WorkerKeys workers, String workerUrl, String miniAppUrl, GroupLinks groupLinks, Assistant assistant,
                         AssistantActions assistantActions) {
        if (workers != null) {
            Objects.requireNonNull(workerUrl, "workerUrl is required when workers is configured");
        }
        this.db = db;
        this.tasks = tasks;
        this.membership = membership;
        this.groups = groups;
        this.projects = projects;
        this.api = api;
        this.renderer = renderer;
        this.redactor = redactor;
        this.botUsername = botUsername;
        this.clock = clock;
        this.wakeOutbox = wakeOutbox;
        this.workers = workers;
        this.workerUrl = workerUrl;
        this.miniAppUrl = miniAppUrl;
        this.groupLinks = groupLinks;
        this.assistant = assistant;
        this.assistantActions = assistantActions;
    }

    public void handle(JsonNode update) {
        long updateId = update.path("update_id").asLong();
        db.transaction(tx -> {
            if (update.has("message")) {
                JsonNode message = update.get("message");
                recordUsername(tx, message.path("from"), message.path("chat"));
                onMessage(tx, message);
            } else if (update.has("callback_query")) {
                JsonNode callback = update.get("callback_query");
                recordUsername(tx, callback.path("from"), callback.path("message").path("chat"));
                onCallback(tx, callback);
            } else if (update.has("my_chat_member")) {
                onMembershipChange(tx, update.get("my_chat_member"));
            }
            Kv.put(tx, OFFSET_KEY, Long.toString(updateId + 1));
        });
    }

    /**
     * Keeps a member's @username in the username book, so a mention of it in a linked group finds them (G-1c). Only from
     * a member's private chat or a linked group: other chats are not served, and non-members' names are not kept.
     */
    private void recordUsername(Tx tx, JsonNode from, JsonNode chat) {
        if (!from.has("id") || !from.hasNonNull("username") || !groups.isMember(Refs.user(from.get("id").asLong()))) {
            return;
        }
        if (isPrivateChatOf(chat, from) || groups.isGroupChat(Refs.chat(chat.path("id").asLong()))) {
            TelegramUsers.record(tx, from.get("id").asLong(), from.get("username").asText(), clock.instant());
        }
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
        if (privateChat && groups.isMember(Refs.user(from.get("id").asLong()))) {
            onChatMessage(tx, message, true);
            return;
        }
        if (privateChat && groups.isAdmin(Refs.user(from.get("id").asLong())) && isCancelCommand(message)) {
            // An admin outside every group still cancels through the normal path (ADR 0020); TaskService.cancel allows it.
            onChatMessage(tx, message, true);
            return;
        }
        if (privateChat && !groups.admins().isEmpty()) {
            // Someone new writes to a shared bot: they ask to join, and the admins decide (ADR 0015).
            Requester who = new Requester(Refs.user(from.get("id").asLong()), displayName(from));
            JoinRequestResult result = membership.requestJoin(tx, who, from.path("username").asText(null),
                    Refs.message(chatId, message.path("message_id").asLong(), null));
            tx.afterCommit(() -> Log.info("telegram.join_message", "requester", who.ref(), "result", result));
            return;
        }
        if (privateChat && isWorkerCommand(message)) {
            // No admins to ask (so no join flow above): /worker still gets a plain refusal instead of silence.
            onChatMessage(tx, message, true);
            return;
        }
        if (!groups.isGroupChat(Refs.chat(chatId))) {
            ignoreForeignChat(tx, message);
            return;
        }
        if (message.has("migrate_to_chat_id")) {
            long newChatId = message.get("migrate_to_chat_id").asLong();
            if (groupLinks != null) {
                migrated(tx, chatId, newChatId);
                return;
            }
            tx.afterCommit(() -> Log.error("telegram.group_migrated", null, "chat_id", chatId, "new_chat_id", newChatId,
                    "action", "set the group's chatId in telegram.groups to the new id and restart"));
            return;
        }
        if (from.has("id")) {
            onChatMessage(tx, message, false);
        }
    }

    /** Moves the link to the supergroup, closes any link prompt that can no longer be answered, and sets its menu. */
    private void migrated(Tx tx, long oldChatId, long newChatId) {
        for (GroupLinks.Prompt closed : groupLinks.migrated(tx, oldChatId, newChatId)) {
            if (closed.messageId() == null) {
                continue;
            }
            ObjectNode payload = Json.object().put("chatId", newChatId).put("title", closed.title()).put("status", "MOVED");
            Renderer.Rendered redrawn = renderer.render(OutboxKind.GROUP_LINK, Json.read(redactor.redact(payload.toString())));
            tx.afterCommit(() -> bestEffort("editMessageText",
                    () -> api.editMessageText(closed.sentTo(), closed.messageId(), redrawn.html(), redrawn.keyboard())));
        }
        // After the running groups were replaced: only a migration that actually moved the link gets the menu.
        tx.afterCommit(() -> {
            if (groups.isGroupChat(Refs.chat(newChatId))) {
                setGroupMenu(newChatId);
            }
        });
    }

    /** Best effort, as at start: the group's command menu, so members pick "/status@bot" rather than typing it. */
    private void setGroupMenu(long chatId) {
        try {
            api.setMyCommands(chatId, renderer.groupCommands());
        } catch (TelegramException e) {
            Log.warn("telegram.command_menu_failed", "chat_id", chatId, "error", e.getMessage());
        }
    }

    private boolean isCancelCommand(JsonNode message) {
        return Command.parse(message).filter(command -> command.name().equals("cancel") && command.addressedTo(botUsername)).isPresent();
    }

    private boolean isWorkerCommand(JsonNode message) {
        return Command.parse(message).filter(command -> command.name().equals("worker") && command.addressedTo(botUsername)).isPresent();
    }

    /** A message in the team group, or in a member's private chat with the bot, from someone with a user id. */
    private void onChatMessage(Tx tx, JsonNode message, boolean privateChat) {
        long chatId = message.path("chat").path("id").asLong();
        JsonNode from = message.path("from");
        Requester who = new Requester(Refs.user(from.get("id").asLong()), displayName(from));
        Long thread = message.path("is_topic_message").asBoolean(false) && message.has("message_thread_id")
                ? message.get("message_thread_id").asLong()
                : null;
        String origin = Refs.message(chatId, message.path("message_id").asLong(), thread);
        String chatRef = Refs.chat(chatId);
        Set<String> visible = privateChat ? groups.projectsOfMember(who.ref()) : groups.projectsOfChat(chatRef);
        Optional<Command> parsed = Command.parse(message);
        Optional<Task> topicTask = privateChat && thread != null
                ? tasks.taskOfTopic(tx, who.ref(), Long.toString(thread))
                : Optional.empty();
        if (topicTask.isPresent() && parsed.map(command -> !COMMANDS.contains(command.name())).orElse(true)) {
            // Inside a task's own topic, anything that is not a command is about that task: a follow-up once it has finished,
            // otherwise a correction of its plan, which is refused with the reason when no plan is waiting.
            if (replyToQuestion(tx, message, who, origin, chatRef)) {
                return;
            }
            Task task = topicTask.get();
            if (task.phase() == Phase.COMPLETED || task.phase() == Phase.FAILED) {
                tasks.followUp(tx, who, task.id(), text(message), origin, chatRef);
            } else {
                tasks.correctLatest(tx, who, task.id(), text(message), origin, chatRef);
            }
            return;
        }
        if (parsed.isEmpty()) {
            if (replyToTask(tx, message, who, origin, chatRef)) {
                return;
            }
            if (privateChat && assistant != null && !text(message).isBlank() && attachments(message).isEmpty()) {
                // A conversation with the assistant, which proposes a task when the message is one (A-1). Files still make
                // a draft directly: the assistant cannot see them.
                assistant.submit(tx, who, text(message), origin, chatRef);
            } else if (privateChat) {
                // Anything else a member writes privately is a task to give (ADR 0012).
                tasks.draft(tx, who, null, text(message), origin, attachments(message));
            } else {
                Optional<String> forBot = withoutBotMentions(message);
                if (forBot.isPresent()) {
                    groupTask(tx, who, null, forBot.get(), message, origin, chatRef);
                } else {
                    mentionTasks(tx, who, message, origin, chatRef);
                }
            }
            return;
        }
        Command command = parsed.get();
        if (!command.addressedTo(botUsername)) {
            return;
        }
        if (command.name().equals("new") && privateChat && assistant != null) {
            // Only where there is a conversation to restart; anywhere else /new is any unknown command, as before.
            assistant.reset(tx, who.ref());
            enqueue(tx, OutboxKind.ASSISTANT_REPLY, chatRef, origin, Json.object().put("new", true));
            return;
        }
        switch (command.name()) {
            case "task" -> {
                if (!privateChat) {
                    Optional<Config.Project> named = firstWordProject(command.args(), groups.projectsOfChat(chatRef));
                    groupTask(tx, who, named.map(Config.Project::name).orElse(null),
                            named.isPresent() ? afterFirstWord(command.args()) : command.args(), message, origin, chatRef);
                    return;
                }
                giveTask(tx, who, command.args(), message, origin);
            }
            case "status" -> tasks.status(tx, visible, privateChat ? who.ref() : null, origin, chatRef);
            case "history" -> {
                String viewer = privateChat ? who.ref() : null;
                taskId(command.args()).ifPresentOrElse(
                        id -> tasks.timeline(tx, visible, viewer, id, origin, chatRef),
                        () -> tasks.history(tx, visible, viewer, origin, chatRef));
            }
            case "cancel" -> {
                if (!privateChat) {
                    privateOnly(tx, chatRef, origin);
                    return;
                }
                taskId(command.args()).ifPresentOrElse(
                        id -> tasks.cancel(tx, who, id, origin, chatRef),
                        () -> help(tx, visible, origin, chatRef, true));
            }
            case "retry" -> {
                if (!privateChat) {
                    privateOnly(tx, chatRef, origin);
                    return;
                }
                taskId(command.args()).ifPresentOrElse(
                        id -> tasks.retry(tx, who, id, origin, chatRef),
                        () -> help(tx, visible, origin, chatRef, true));
            }
            case "worker" -> {
                if (!privateChat) {
                    privateOnly(tx, chatRef, origin);
                    return;
                }
                worker(tx, who, command.args(), origin, chatRef);
            }
            case "manage" -> {
                if (!privateChat) {
                    privateOnly(tx, chatRef, origin);
                    return;
                }
                manage(tx, who, origin, chatRef);
            }
            case "stats" -> tasks.stats(tx, privateChat ? who.ref() : null,
                    privateChat ? groups.groupsOfMember(who.ref()) : groups.groupOfChat(chatRef).map(List::of).orElseThrow(), origin, chatRef);
            case "projects" -> projectList(tx, who, privateChat, visible, origin, chatRef);
            case "help", "start" -> help(tx, visible, origin, chatRef, privateChat);
            default -> {
                // Telegram marks any leading "/word" as a command, so "/api/login fails too" lands here: a correction when
                // it replies to a plan, otherwise a task when written privately.
                if (replyToTask(tx, message, who, origin, chatRef)) {
                    return;
                }
                if (privateChat) {
                    tasks.draft(tx, who, null, text(message), origin, attachments(message));
                } else {
                    tx.afterCommit(() -> Log.info("telegram.command_ignored", "command", command.name()));
                }
            }
        }
    }

    /** /task [project] [text]: the first word names the project only if it is one of the member's; a replied message is the text. */
    private void giveTask(Tx tx, Requester who, String args, JsonNode message, String origin) {
        JsonNode repliedTo = message.path("reply_to_message");
        Optional<Config.Project> named = firstWordProject(args, groups.projectsOfMember(who.ref()));
        String own = named.isPresent() ? afterFirstWord(args) : args;
        tasks.draft(tx, who, named.map(Config.Project::name).orElse(null), withRepliedMessage(own, repliedTo), origin,
                attachments(repliedTo, message));
    }

    /** The project the first word of /task's arguments names, by name or alias, if it is one of {@code candidates}. */
    private Optional<Config.Project> firstWordProject(String args, Set<String> candidates) {
        return projects.find(args.split("\\s+", 2)[0]).filter(project -> candidates.contains(project.name()));
    }

    private static String afterFirstWord(String args) {
        String[] firstAndRest = args.split("\\s+", 2);
        return firstAndRest.length > 1 ? firstAndRest[1].strip() : "";
    }

    /**
     * A task given in a linked group, by mentioning the bot or with /task (G-1b): a member of that group gets the usual draft
     * in their private chat, as private /task makes it. The project is the one named, else the group's only one; a replied
     * message, unless the bot's own, comes first in the text and brings its files.
     *
     * @param projectKey a project of this group named with /task, null if none
     */
    private void groupTask(Tx tx, Requester who, String projectKey, String own, JsonNode message, String origin, String chatRef) {
        Set<String> owned = groups.projectsOfChat(chatRef);
        if (!inProjectGroup(who.ref(), owned)) {
            tasks.notAllowed(tx, who, origin, chatRef, clock.instant());
            return;
        }
        JsonNode repliedTo = humanReplied(message);
        String text = withRepliedMessage(own, repliedTo);
        if (text.isBlank()) {
            enqueue(tx, OutboxKind.TASK_USAGE, chatRef, origin, Json.object());
            return;
        }
        String project = projectKey != null ? projectKey : onlyProject(owned);
        String firstName = firstName(message, who);
        DraftResult result = tasks.draft(tx, who, project, text, origin, attachments(repliedTo, message),
                new TaskService.GroupOrigin(chatRef, firstName));
        if (result == DraftResult.NO_PROJECTS) {
            noProjects(tx, chatRef, origin, List.of(firstName));
        }
    }

    /** Tells a group, under the message that gave them tasks, which members have no project there to take one. */
    private void noProjects(Tx tx, String chatRef, String origin, List<String> firstNames) {
        enqueue(tx, OutboxKind.NO_PROJECTS, chatRef, origin, Json.object().put("names", String.join(", ", firstNames)));
    }

    /**
     * Members of the chat's project groups mentioned in a linked group, by @username or by name, each get the message as a
     * task of their own, drafted in their private chat as G-1b drafts the author's (G-1c). The text is the message without
     * those mentions, after a replied message as private /task puts it, and ends with who asked unless they asked
     * themselves. The author may be anyone in the chat, such as a manager who is not a member: the draft is the developer's
     * own, so nothing starts until they choose to. Only a member's unknown @names are answered; an outsider's are left alone.
     */
    private void mentionTasks(Tx tx, Requester author, JsonNode message, String origin, String chatRef) {
        List<Mention> mentions = mentions(message);
        if (mentions.isEmpty()) {
            return;
        }
        Set<String> owned = groups.projectsOfChat(chatRef);
        // Outsiders tag each other all day: answering their unknown names would be noise.
        boolean answersUnknown = inProjectGroup(author.ref(), owned);
        Map<Long, List<Mention>> developers = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (Mention mention : mentions) {
            Optional<Long> userId = mention.userId() != null ? Optional.of(mention.userId()) : TelegramUsers.idOf(tx, mention.username());
            if (userId.isEmpty()) {
                if (answersUnknown && unknown.stream().noneMatch(name -> name.equalsIgnoreCase(mention.username()))) {
                    unknown.add(mention.username());
                }
            } else if (inProjectGroup(Refs.user(userId.get()), owned)) {
                developers.computeIfAbsent(userId.get(), id -> new ArrayList<>()).add(mention);
            }
        }
        long chatId = message.path("chat").path("id").asLong();
        unknown.stream().filter(name -> unknownNoticeDue(tx, chatId, name))
                .forEach(name -> enqueue(tx, OutboxKind.UNKNOWN_USERNAME, chatRef, origin, Json.object().put("username", name)));
        if (developers.isEmpty()) {
            return;
        }
        JsonNode repliedTo = humanReplied(message);
        String own = withRepliedMessage(without(text(message), developers.values().stream().flatMap(List::stream).toList()), repliedTo);
        if (own.isBlank()) {
            // A bare mention is someone being called, not a task.
            tx.afterCommit(() -> Log.info("group.mention_empty", "author", author.ref(), "chat", chatRef));
            return;
        }
        String askedBy = renderer.text("group.requestedBy") + " " + firstName(message, author);
        List<Attachment> files = attachments(repliedTo, message);
        List<String> withoutProjects = new ArrayList<>();
        for (long userId : developers.keySet()) {
            String ref = Refs.user(userId);
            String name = groups.memberName(ref).orElseThrow();
            // The ✉️ line and the prompt call them as the group does: by the first word of the configured name.
            String firstName = name.strip().split("\\s+", 2)[0];
            // One draft per developer: the group message alone would make the second a duplicate of the first.
            String draftOrigin = developers.size() > 1 ? origin + "#" + userId : origin;
            DraftResult result = tasks.draft(tx, new Requester(ref, name), onlyProject(owned),
                    ref.equals(author.ref()) ? own : own + "\n\n" + askedBy, draftOrigin, files, new TaskService.GroupOrigin(chatRef, firstName));
            if (result == DraftResult.NO_PROJECTS) {
                withoutProjects.add(firstName);
            }
        }
        if (!withoutProjects.isEmpty()) {
            noProjects(tx, chatRef, origin, withoutProjects);
        }
    }

    /**
     * Whether an unknown @name may be pointed out in this chat now, and if so notes that it was: at most once a day per
     * chat and name, so a name the team keeps using is not answered every time.
     */
    private boolean unknownNoticeDue(Tx tx, long chatId, String username) {
        // ponytail: one kv row per (chat, name) ever pointed out, never pruned; a table with cleanup if that ever grows large
        String key = "unknown." + chatId + "." + username.toLowerCase(Locale.ROOT);
        Instant now = clock.instant();
        boolean due = Kv.get(tx, key).map(Instant::parse).map(last -> !now.isBefore(last.plus(UNKNOWN_NOTICE_INTERVAL))).orElse(true);
        if (due) {
            Kv.put(tx, key, now.toString());
        }
        return due;
    }

    private boolean inProjectGroup(String requesterRef, Set<String> projects) {
        return projects.stream().anyMatch(project -> groups.isMemberOfProjectGroup(requesterRef, project));
    }

    /** The chat's project when it has one only, else null and the prompt asks. */
    private static String onlyProject(Set<String> owned) {
        return owned.size() == 1 ? owned.iterator().next() : null;
    }

    /** The message this one replies to, unless a bot's, such as the ✉️ line: nothing to give as a task. */
    private static JsonNode humanReplied(JsonNode message) {
        JsonNode repliedTo = message.path("reply_to_message");
        return repliedTo.path("from").path("is_bot").asBoolean(false) ? Json.object() : repliedTo;
    }

    /** What the group calls the author: their Telegram first name. */
    private static String firstName(JsonNode message, Requester who) {
        String firstName = TelegramNames.clean(message.path("from").path("first_name").asText(""));
        return firstName.isEmpty() ? who.name() : firstName;
    }

    /** The message's text without its mentions of this bot (and one space after each), or empty when it does not mention the bot. */
    private Optional<String> withoutBotMentions(JsonNode message) {
        List<Mention> toBot = mentions(message).stream().filter(mention -> botUsername.equalsIgnoreCase(mention.username())).toList();
        return toBot.isEmpty() ? Optional.empty() : Optional.of(without(text(message), toBot));
    }

    /**
     * A person named in a message: by @username, or by a text_mention that carries their id.
     *
     * @param end      past the mention, one of ",.:;" right after it and then one space, which go with it when it is removed
     * @param username without the "@", null for a text_mention
     * @param userId   null for an @username
     */
    private record Mention(int start, int end, String username, Long userId) {
    }

    /** The message's mentions of people other than bots, in order. Telegram's offsets count UTF-16 units, as Java strings do. */
    private static List<Mention> mentions(JsonNode message) {
        String text = text(message);
        JsonNode entities = message.has("entities") ? message.get("entities") : message.path("caption_entities");
        List<Mention> found = new ArrayList<>();
        for (JsonNode entity : entities) {
            int start = entity.path("offset").asInt(-1);
            int end = start + entity.path("length").asInt();
            if (start < 0 || end <= start + 1 || end > text.length()) {
                continue;
            }
            int removedEnd = end < text.length() && ",.:;".indexOf(text.charAt(end)) >= 0 ? end + 1 : end;
            removedEnd = removedEnd < text.length() && text.charAt(removedEnd) == ' ' ? removedEnd + 1 : removedEnd;
            JsonNode user = entity.path("user");
            if (entity.path("type").asText().equals("mention")) {
                found.add(new Mention(start, removedEnd, text.substring(start + 1, end), null));
            } else if (entity.path("type").asText().equals("text_mention") && user.has("id") && !user.path("is_bot").asBoolean(false)) {
                found.add(new Mention(start, removedEnd, null, user.get("id").asLong()));
            }
        }
        return found;
    }

    /** {@code text} without {@code removed}, stripped, and with no doubled space where one was taken out. */
    private static String without(String text, List<Mention> removed) {
        StringBuilder rest = new StringBuilder(text);
        // From the last, so the earlier offsets still hold.
        removed.stream().sorted(Comparator.comparingInt(Mention::start).reversed()).forEach(mention -> {
            int at = mention.start();
            rest.delete(at, mention.end());
            while (at > 0 && at < rest.length() && rest.charAt(at - 1) == ' ' && rest.charAt(at) == ' ') {
                rest.deleteCharAt(at);
            }
        });
        return rest.toString().strip();
    }

    private void privateOnly(Tx tx, String chatRef, String origin) {
        enqueue(tx, OutboxKind.PRIVATE_ONLY, chatRef, origin, Json.object().put("bot", botUsername));
    }

    /**
     * A reply to one of the bot's plan messages corrects that plan, and one to a task's result is a follow-up; false for any
     * other message.
     */
    private boolean replyToTask(Tx tx, JsonNode message, Requester who, String origin, String chatRef) {
        JsonNode repliedTo = message.path("reply_to_message");
        if (!repliedTo.has("message_id")) {
            return false;
        }
        if (replyToQuestion(tx, message, who, origin, chatRef)) {
            return true;
        }
        long chatId = message.path("chat").path("id").asLong();
        String repliedRef = Refs.message(chatId, repliedTo.get("message_id").asLong(), null);
        Optional<Outbox.Sent> sent = Outbox.findSent(tx, repliedRef);
        if (sent.isPresent() && RESULTS.contains(sent.get().kind())) {
            tasks.followUp(tx, who, sent.get().taskId(), text(message), origin, chatRef);
            return true;
        }
        if (sent.isEmpty() || sent.get().kind() != OutboxKind.PLAN_READY) {
            String kind = sent.map(found -> found.kind().name()).orElse("unknown");
            tx.afterCommit(() -> Log.info("telegram.reply_ignored", "replied_to", repliedRef, "kind", kind));
            return false;
        }
        int planSeq = Json.read(sent.get().payload()).path("planSeq").asInt();
        tasks.correct(tx, who, sent.get().taskId(), planSeq, text(message), origin, chatRef);
        return true;
    }

    /**
     * A reply to a plan's question message, or to the prompt asking for an answer to one, answers that question (G-1d);
     * false for any other message.
     */
    private boolean replyToQuestion(Tx tx, JsonNode message, Requester who, String origin, String chatRef) {
        JsonNode repliedTo = message.path("reply_to_message");
        if (!repliedTo.has("message_id")) {
            return false;
        }
        String repliedRef = Refs.message(message.path("chat").path("id").asLong(), repliedTo.get("message_id").asLong(), null);
        Optional<Outbox.Sent> sent = Outbox.findSent(tx, repliedRef)
                .filter(found -> found.kind() == OutboxKind.PLAN_QUESTION || found.kind() == OutboxKind.PLAN_ANSWER_PROMPT);
        if (sent.isEmpty()) {
            return false;
        }
        JsonNode question = Json.read(sent.get().payload());
        String questionRef = sent.get().kind() == OutboxKind.PLAN_QUESTION ? repliedRef : question.path("questionRef").asText();
        long taskId = sent.get().taskId();
        AnswerResult result = tasks.answer(tx, who, taskId, question.path("planSeq").asInt(), question.path("index").asInt(),
                text(message), questionRef, origin, chatRef);
        // Every refusal is answered in words: a reply has no callback to answer, and silence would look like it was taken.
        String reason = switch (result) {
            case ANSWERED, PROMPTED, PROMPT_OPEN -> null;
            case EMPTY -> "empty";
            case ALREADY_ANSWERED -> "alreadyAnswered";
            case NOT_ALLOWED -> "notAllowed";
            case NOT_FOUND -> "notFound";
            case NOT_REQUESTER -> "notRequester";
            case STALE -> "stale";
        };
        if (reason != null) {
            enqueue(tx, OutboxKind.CORRECTION_REFUSED, chatRef, origin, Json.object().put("taskId", taskId).put("reason", reason));
        }
        return true;
    }

    /** A button under a plan's question: an option's index, "w" to write one's own answer or "d" to let the agent decide (G-1d). */
    private void onQuestionButton(Tx tx, JsonNode callback, Requester who, String[] parts) {
        String callbackId = callback.path("id").asText();
        Optional<Long> taskId = taskId(parts[1]);
        Optional<Long> planSeq = taskId(parts[2]);
        Optional<Long> index = taskId(parts[3]);
        Optional<Long> option = taskId(parts[4]);
        boolean known = parts[4].equals("w") || parts[4].equals("d") || option.isPresent();
        if (taskId.isEmpty() || planSeq.isEmpty() || index.isEmpty() || !known) {
            answer(tx, callbackId, "callback.unknown");
            return;
        }
        JsonNode message = callback.path("message");
        long chatId = message.path("chat").path("id").asLong();
        String questionRef = Refs.message(chatId, message.path("message_id").asLong(), null);
        String chatRef = Refs.chat(chatId);
        int seq = planSeq.get().intValue();
        int question = index.get().intValue();
        AnswerResult result = switch (parts[4]) {
            case "w" -> tasks.askForAnswer(tx, who, taskId.get(), seq, question, questionRef);
            case "d" -> tasks.answer(tx, who, taskId.get(), seq, question, renderer.text("plan.youDecide"), questionRef, questionRef,
                    chatRef);
            default -> tasks.chooseOption(tx, who, taskId.get(), seq, question, option.get().intValue(), questionRef, questionRef,
                    chatRef);
        };
        answer(tx, callbackId, switch (result) {
            case ANSWERED -> "callback.answered";
            case PROMPTED -> "callback.writeAnswer";
            case PROMPT_OPEN -> "plan.answerPromptOpen";
            case EMPTY -> "callback.unknown";
            case NOT_ALLOWED -> "callback.notAllowed";
            case NOT_FOUND -> "callback.notFound";
            case NOT_REQUESTER -> "callback.notRequester";
            case ALREADY_ANSWERED -> "plan.alreadyAnswered";
            case STALE -> "callback.stale";
        });
    }

    private void onCallback(Tx tx, JsonNode callback) {
        String callbackId = callback.path("id").asText();
        JsonNode from = callback.path("from");
        JsonNode chat = callback.path("message").path("chat");
        boolean servedChat = groups.isGroupChat(Refs.chat(chat.path("id").asLong())) || isPrivateChatOf(chat, from);
        String data = callback.path("data").asText();
        if (servedChat && from.has("id") && data.startsWith("stats:")) {
            onStatsButton(tx, callback, data);
            return;
        }
        String[] parts = data.split(":");
        if (servedChat && from.has("id") && parts.length == 3 && parts[0].equals("join") && taskId(parts[1]).isPresent()) {
            onJoinButton(tx, callback, new Requester(Refs.user(from.get("id").asLong()), displayName(from)), taskId(parts[1]).get(), parts[2]);
            return;
        }
        if (servedChat && from.has("id") && parts.length == 4 && parts[0].equals("draft") && taskId(parts[1]).isPresent()) {
            Requester presser = new Requester(Refs.user(from.get("id").asLong()), displayName(from));
            onDraftButton(tx, callback, presser, taskId(parts[1]).get(), parts[2], parts[3]);
            return;
        }
        if (groupLinks != null && isPrivateChatOf(chat, from) && parts.length == 3 && parts[0].equals("link") && taskId(parts[1]).isPresent()) {
            // The prompt only ever goes to a private chat; parts[1] is the negative group chat id, which taskId also parses.
            onLinkButton(tx, callback, new Requester(Refs.user(from.get("id").asLong()), displayName(from)), taskId(parts[1]).get(), parts[2]);
            return;
        }
        if (assistantActions != null && isPrivateChatOf(chat, from) && parts.length == 2 && parts[0].equals("as")
                && taskId(parts[1]).isPresent()) {
            onAssistantButton(tx, callback, new Requester(Refs.user(from.get("id").asLong()), displayName(from)), taskId(parts[1]).get());
            return;
        }
        if (servedChat && from.has("id") && parts.length == 5 && parts[0].equals("q")) {
            onQuestionButton(tx, callback, new Requester(Refs.user(from.get("id").asLong()), displayName(from)), parts);
            return;
        }
        boolean known = parts.length == 3 && Set.of("approve", "reject", "prio").contains(parts[0]);
        if (!servedChat || !known || !from.has("id")) {
            answer(tx, callbackId, "callback.unknown");
            return;
        }
        Optional<Long> taskId = taskId(parts[1]);
        if (taskId.isEmpty()) {
            answer(tx, callbackId, "callback.unknown");
            return;
        }
        Requester who = new Requester(Refs.user(from.get("id").asLong()), displayName(from));
        if (parts[0].equals("prio")) {
            onPriorityButton(tx, callback, who, taskId.get(), parts[2]);
            return;
        }
        Optional<Long> planSeq = taskId(parts[2]);
        if (planSeq.isEmpty()) {
            answer(tx, callbackId, "callback.unknown");
            return;
        }
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

    /**
     * A button on a draft's prompt: project, priority, 🗑 not a task, or ✂️ and its proposal's split and keep-whole choices
     * (ADR 0013).
     * The prompt is redrawn to show the choice, the new task or the parts.
     */
    private void onDraftButton(Tx tx, JsonNode callback, Requester who, long draftId, String kind, String value) {
        String callbackId = callback.path("id").asText();
        JsonNode message = callback.path("message");
        long chatId = message.path("chat").path("id").asLong();
        long messageId = message.path("message_id").asLong();
        DraftChoice choice;
        if (kind.equals("p")) {
            choice = tasks.chooseProject(tx, who, draftId, value);
        } else if (kind.equals("prio") && Set.of("URGENT", "NORMAL", "LOW").contains(value)) {
            choice = tasks.choosePriority(tx, who, draftId, Priority.valueOf(value));
        } else if (kind.equals("split") && value.equals("ask")) {
            // This prompt is redrawn again when the split's answer arrives.
            choice = tasks.split(tx, who, draftId, Refs.message(chatId, messageId, null));
        } else if (kind.equals("split") && value.equals("yes")) {
            choice = tasks.acceptSplit(tx, who, draftId);
        } else if (kind.equals("split") && value.equals("no")) {
            choice = tasks.keepWhole(tx, who, draftId);
        } else if (kind.equals("discard")) {
            choice = tasks.discard(tx, who, draftId);
        } else {
            answer(tx, callbackId, "callback.unknown");
            return;
        }
        answer(tx, callbackId, switch (choice) {
            case PROJECT_CHOSEN -> "callback.projectChosen";
            case CREATED -> "callback.taskCreated";
            case ALREADY_CREATED -> "callback.alreadyCreated";
            case EXPIRED -> "callback.draftExpired";
            case NOT_FOUND -> "callback.notFound";
            case NOT_REQUESTER -> "callback.notRequester";
            case CHOOSE_PROJECT_FIRST -> "callback.chooseProjectFirst";
            case PROJECT_UNAVAILABLE -> "callback.projectUnavailable";
            case SPLITTING -> "callback.splitting";
            case SPLIT -> "callback.split";
            case KEPT_WHOLE -> "callback.keptWhole";
            case ALREADY_SPLIT -> "callback.alreadySplit";
            case CANNOT_SPLIT -> "callback.cannotSplit";
            case DISCARDED -> "callback.discarded";
            case ALREADY_DISCARDED -> "callback.alreadyDiscarded";
        });
        if (!Set.of(DraftChoice.PROJECT_CHOSEN, DraftChoice.CREATED, DraftChoice.SPLITTING, DraftChoice.SPLIT, DraftChoice.KEPT_WHOLE,
                        DraftChoice.DISCARDED)
                .contains(choice)) {
            return;
        }
        ObjectNode payload = tasks.draftPayload(tx, draftId).orElseThrow();
        Renderer.Rendered prompt = renderer.render(OutboxKind.DRAFT_PROMPT, Json.read(redactor.redact(payload.toString())));
        tx.afterCommit(() -> bestEffort("editMessageText", () -> api.editMessageText(chatId, messageId, prompt.html(), prompt.keyboard())));
    }

    /**
     * A confirm button under the assistant's reply (A-1): runs that proposal once, then redraws the reply with every proposal
     * taken so far marked and without its button.
     */
    private void onAssistantButton(Tx tx, JsonNode callback, Requester who, long actionId) {
        JsonNode message = callback.path("message");
        long chatId = message.path("chat").path("id").asLong();
        long messageId = message.path("message_id").asLong();
        String messageRef = Refs.message(chatId, messageId, null);
        AssistantActions.Outcome outcome = assistantActions.run(tx, who, actionId, messageRef, Refs.chat(chatId));
        answer(tx, callback.path("id").asText(), switch (outcome) {
            case DONE -> "callback.assistantDone";
            case USED -> "callback.assistantUsed";
            case STALE -> "callback.wrongState";
            case NOT_ALLOWED -> "callback.notAllowed";
        });
        Optional<Outbox.Sent> reply = Outbox.findSent(tx, messageRef).filter(sent -> sent.kind() == OutboxKind.ASSISTANT_REPLY);
        if (reply.isEmpty() || outcome == AssistantActions.Outcome.NOT_ALLOWED) {
            return;
        }
        ObjectNode payload = (ObjectNode) Json.read(reply.get().payload());
        List<Long> ids = new ArrayList<>();
        payload.withArray("actions").forEach(action -> ids.add(action.path("id").asLong()));
        Map<Long, String> taken = Conversations.outcomes(tx, ids);
        payload.withArray("actions").forEach(action -> ((ObjectNode) action).put("outcome", taken.get(action.path("id").asLong())));
        Renderer.Rendered redrawn = renderer.render(OutboxKind.ASSISTANT_REPLY, Json.read(redactor.redact(payload.toString())));
        tx.afterCommit(() -> bestEffort("editMessageText", () -> api.editMessageText(chatId, messageId, redrawn.html(), redrawn.keyboard())));
    }

    /** An admin's button on a join request: a group to add the person to, or "-" to deny. The request is redrawn with the decision. */
    private void onJoinButton(Tx tx, JsonNode callback, Requester presser, long requestId, String choice) {
        JoinDecision decision = choice.equals("-") ? membership.deny(tx, presser, requestId) : membership.approve(tx, presser, requestId, choice);
        answer(tx, callback.path("id").asText(), switch (decision) {
            case APPROVED -> "callback.joinApproved";
            case DENIED -> "callback.joinDenied";
            case NOT_ADMIN -> "callback.notAdmin";
            case NOT_FOUND -> "callback.notFound";
            case ALREADY_DECIDED -> "callback.joinDecided";
            case UNKNOWN_GROUP -> "callback.unknown";
            case CONFIG_FAILED -> "callback.joinFailed";
        });
        if (decision != JoinDecision.APPROVED && decision != JoinDecision.DENIED && decision != JoinDecision.ALREADY_DECIDED) {
            return;
        }
        JsonNode message = callback.path("message");
        long chatId = message.path("chat").path("id").asLong();
        long messageId = message.path("message_id").asLong();
        ObjectNode payload = membership.requestPayload(tx, requestId).orElseThrow();
        Renderer.Rendered redrawn = renderer.render(OutboxKind.JOIN_REQUEST, Json.read(redactor.redact(payload.toString())));
        tx.afterCommit(() -> bestEffort("editMessageText", () -> api.editMessageText(chatId, messageId, redrawn.html(), redrawn.keyboard())));
    }

    /** A view or period button under statistics; the same message is redrawn, for the groups its chat may see. */
    private void onStatsButton(Tx tx, JsonNode callback, String data) {
        String callbackId = callback.path("id").asText();
        JsonNode message = callback.path("message");
        long chatId = message.path("chat").path("id").asLong();
        boolean privateChat = isPrivateChatOf(message.path("chat"), callback.path("from"));
        String viewer = privateChat ? Refs.user(callback.path("from").path("id").asLong()) : null;
        List<String> visibleGroups = privateChat
                ? groups.groupsOfMember(viewer)
                : groups.groupOfChat(Refs.chat(chatId)).map(List::of).orElse(List.of());
        String[] parts = data.split(":", 3);
        Optional<ObjectNode> payload = parts.length == 3 && !visibleGroups.isEmpty()
                ? tasks.statsPayload(tx, viewer, visibleGroups, parts[2], parts[1])
                : Optional.empty();
        if (payload.isEmpty()) {
            answer(tx, callbackId, "callback.unknown");
            return;
        }
        answer(tx, callbackId, "callback.done");
        long messageId = message.path("message_id").asLong();
        Renderer.Rendered stats = renderer.render(OutboxKind.STATS, Json.read(redactor.redact(payload.get().toString())));
        tx.afterCommit(() -> bestEffort("editMessageText", () -> api.editMessageText(chatId, messageId, stats.html(), stats.keyboard())));
    }

    /** A priority button under a status report: change it, then redraw that report so it shows the new order. */
    private void onPriorityButton(Tx tx, JsonNode callback, Requester who, long taskId, String value) {
        String callbackId = callback.path("id").asText();
        Priority priority;
        try {
            priority = Priority.valueOf(value);
        } catch (IllegalArgumentException e) {
            answer(tx, callbackId, "callback.unknown");
            return;
        }
        PriorityResult result = tasks.changePriority(tx, who, taskId, priority);
        answer(tx, callbackId, switch (result) {
            case CHANGED -> "callback.priorityChanged";
            case UNCHANGED -> "callback.priorityUnchanged";
            case NOT_ALLOWED -> "callback.notAllowed";
            case NOT_FOUND -> "callback.notFound";
            case NOT_REQUESTER -> "callback.notRequester";
            case FINISHED -> "callback.wrongState";
        });
        if (result != PriorityResult.CHANGED) {
            return;
        }
        JsonNode message = callback.path("message");
        long chatId = message.path("chat").path("id").asLong();
        long messageId = message.path("message_id").asLong();
        boolean privateChat = isPrivateChatOf(message.path("chat"), callback.path("from"));
        Set<String> visible = privateChat ? groups.projectsOfMember(who.ref()) : groups.projectsOfChat(Refs.chat(chatId));
        ObjectNode payload = tasks.statusPayload(tx, visible, privateChat ? who.ref() : null);
        Renderer.Rendered status = renderer.render(OutboxKind.STATUS, Json.read(redactor.redact(payload.toString())));
        tx.afterCommit(() -> bestEffort("editMessageText", () -> api.editMessageText(chatId, messageId, status.html(), status.keyboard())));
    }

    private void onMembershipChange(Tx tx, JsonNode change) {
        JsonNode chat = change.path("chat");
        long chatId = chat.path("id").asLong();
        String status = change.path("new_chat_member").path("status").asText();
        if (groups.isGroupChat(Refs.chat(chatId))) {
            tx.afterCommit(() -> Log.info("telegram.membership_changed", "chat_id", chatId, "status", status));
        } else if (JOINED_STATUSES.contains(status) && isGroup(chat)) {
            JsonNode from = change.path("from");
            if (mayLink(from)) {
                askToLink(tx, chatId, chat.path("title").asText(), from.get("id").asLong());
            } else {
                leave(tx, chatId, from);
            }
        }
    }

    private void ignoreForeignChat(Tx tx, JsonNode message) {
        JsonNode chat = message.path("chat");
        JsonNode from = message.path("from");
        long chatId = chat.path("id").asLong();
        if (isGroup(chat) && message.has("migrate_from_chat_id")
                && groups.isGroupChat(Refs.chat(message.get("migrate_from_chat_id").asLong()))) {
            // A linked group's new supergroup, announced before the old chat's migrate_to_chat_id moved the link here.
            tx.afterCommit(() -> Log.info("telegram.migrated_chat_seen", "chat_id", chatId));
            return;
        }
        if (isGroup(chat)) {
            // The bot may already be in the group (no "added" event then): a command to it from someone who may manage
            // Dispatch asks, as does the service message about adding it, which can arrive before my_chat_member.
            boolean asks = addsThisBot(message)
                    || Command.parse(message).filter(command -> command.addressedTo(botUsername)).isPresent();
            if (!asks && message.has("new_chat_members") && mayLink(from)) {
                // A manager adding someone else to a group the bot waits in (its prompt open, or not delivered yet).
                tx.afterCommit(() -> Log.info("group.members_added_ignored", "chat_id", chatId));
                return;
            }
            Optional<String> added = addLinkProject(message);
            if (added.isPresent() && mayLink(from)) {
                linkFromAddLink(tx, message, added.get());
            } else if (asks && mayLink(from)) {
                askToLink(tx, chatId, chat.path("title").asText(), from.get("id").asLong());
            } else {
                leave(tx, chatId, from);
            }
            return;
        }
        String type = chat.path("type").asText();
        long userId = from.path("id").asLong();
        tx.afterCommit(() -> Log.warn("telegram.chat_ignored", "chat_id", chatId, "type", type, "user_id", userId));
    }

    /** Whether the message is the service message about adding this bot, and not only other members. */
    private boolean addsThisBot(JsonNode message) {
        for (JsonNode member : message.path("new_chat_members")) {
            if (member.path("is_bot").asBoolean(false) && botUsername.equalsIgnoreCase(member.path("username").asText())) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code from} is asked which project an unknown group is for, instead of the bot leaving it. */
    private boolean mayLink(JsonNode from) {
        return groupLinks != null && from.has("id") && groups.mayManage(Refs.user(from.get("id").asLong()));
    }

    /**
     * The project a group message "/start KEY" names, which is what Telegram sends right after the bot was added through
     * that project's add link (?startgroup=KEY, ADR 0025). The key is the project's name or alias; empty for anything else.
     */
    private Optional<String> addLinkProject(JsonNode message) {
        return Command.parse(message)
                .filter(command -> command.name().equals("start") && command.addressedTo(botUsername))
                .map(command -> command.args().strip())
                .filter(key -> !key.isEmpty())
                .flatMap(key -> projects.all().stream()
                        .filter(project -> key.equalsIgnoreCase(project.name()) || key.equalsIgnoreCase(project.alias()))
                        .map(Config.Project::name).findFirst());
    }

    /** Links the group to the add link's project at once, and closes the prompt its add event may have sent already. */
    private void linkFromAddLink(Tx tx, JsonNode message, String project) {
        long chatId = message.path("chat").path("id").asLong();
        String title = message.path("chat").path("title").asText();
        Requester who = new Requester(Refs.user(message.path("from").get("id").asLong()), displayName(message.path("from")));
        Optional<GroupLinks.Prompt> open = groupLinks.prompt(tx, chatId);
        GroupLinks.Result result = groupLinks.linkTo(tx, who, chatId, title, project);
        if (result != GroupLinks.Result.LINKED) {
            // The config could not be written (already logged): the manager can still link from a prompt.
            askToLink(tx, chatId, title, message.path("from").get("id").asLong());
            return;
        }
        tx.afterCommit(() -> setGroupMenu(chatId));
        open.filter(prompt -> prompt.messageId() != null).ifPresent(prompt -> {
            ObjectNode payload = Json.object().put("chatId", chatId).put("title", prompt.title()).put("status", "LINKED").put("project", project);
            Renderer.Rendered redrawn = renderer.render(OutboxKind.GROUP_LINK, Json.read(redactor.redact(payload.toString())));
            tx.afterCommit(() -> bestEffort("editMessageText",
                    () -> api.editMessageText(prompt.sentTo(), prompt.messageId(), redrawn.html(), redrawn.keyboard())));
        });
    }

    /**
     * A project's add link, which adds the bot to a group the manager picks and links it there (ADR 0025); empty when
     * neither its name nor its alias fits Telegram's start parameter (A-Z, a-z, 0-9, _ and -, at most 64).
     */
    static Optional<String> addLink(String botUsername, Config.Project project) {
        return java.util.stream.Stream.of(project.name(), project.alias())
                .filter(key -> key != null && START_PARAMETER.matcher(key).matches())
                .findFirst()
                .map(key -> "https://t.me/" + botUsername + "?startgroup=" + key);
    }

    /**
     * Asks {@code fromId} privately which project the group is for, once per open prompt. Sent directly rather than through
     * the outbox so that a refusal (they never pressed Start) is seen here: the bot then leaves. Any other failure keeps
     * the bot and the open prompt.
     */
    private void askToLink(Tx tx, long chatId, String title, long fromId) {
        List<String> names = projects.all().stream().map(Config.Project::name).toList();
        if (!groupLinks.open(tx, chatId, title, names)) {
            return;
        }
        ObjectNode payload = Json.object().put("chatId", chatId).put("title", title).put("status", "OPEN");
        names.forEach(payload.putArray("projects")::add);
        Renderer.Rendered prompt = renderer.render(OutboxKind.GROUP_LINK, Json.read(redactor.redact(payload.toString())));
        tx.afterCommit(() -> {
            long messageId;
            try {
                messageId = api.sendMessage(fromId, null, prompt.html(), null, prompt.keyboard());
            } catch (RuntimeException e) {
                if (!isRefusal(e)) {
                    // A timeout or a Telegram outage: the bot stays, and a later add or command to it asks again.
                    Log.warn("group.link_prompt_failed", "chat_id", chatId, "user_id", fromId, "error", e.getMessage());
                    return;
                }
                Log.warn("group.link_prompt_refused", "chat_id", chatId, "user_id", fromId, "error", e.getMessage());
                db.transaction(later -> groupLinks.forget(later, chatId));
                bestEffort("leaveChat", () -> api.leaveChat(chatId));
                return;
            }
            Log.info("group.link_asked", "chat_id", chatId, "user_id", fromId);
            try {
                db.transaction(later -> groupLinks.sent(later, chatId, fromId, messageId));
            } catch (RuntimeException e) {
                // The prompt arrived and its buttons work; only a migration closing it cannot edit it now.
                Log.error("group.link_prompt_record_failed", e, "chat_id", chatId, "user_id", fromId, "message_id", messageId);
            }
        });
    }

    /** Telegram refusing the private chat itself (they never pressed Start, or blocked the bot): asking again cannot help. */
    private static boolean isRefusal(RuntimeException e) {
        if (!(e instanceof TelegramException telegram)) {
            return false;
        }
        String message = String.valueOf(telegram.getMessage());
        return telegram.errorCode() == 403 || message.contains("chat not found") || message.contains("can't initiate conversation");
    }

    /** A button on a link prompt: a project's index in the prompt's list, or "-" to leave the group unlinked. */
    private void onLinkButton(Tx tx, JsonNode callback, Requester presser, long chatId, String choice) {
        Optional<GroupLinks.Prompt> prompt = groupLinks.prompt(tx, chatId);
        Optional<Long> index = taskId(choice);
        GroupLinks.Result result;
        if (choice.equals("-")) {
            result = groupLinks.decline(tx, presser, chatId);
        } else if (index.isPresent()) {
            result = groupLinks.link(tx, presser, chatId, index.get());
        } else {
            answer(tx, callback.path("id").asText(), "callback.unknown");
            return;
        }
        // Answered first: after-commits run in order, and the button's spinner should not wait on the calls below.
        answer(tx, callback.path("id").asText(), switch (result) {
            case LINKED -> "callback.groupLinked";
            case DECLINED -> "callback.groupDeclined";
            case STALE -> "callback.groupStale";
            case CONFIG_FAILED -> "callback.groupLinkFailed";
            case NOT_ALLOWED -> "callback.notAdmin";
        });
        if (result == GroupLinks.Result.LINKED) {
            tx.afterCommit(() -> setGroupMenu(chatId));
        }
        if (result != GroupLinks.Result.LINKED && result != GroupLinks.Result.DECLINED) {
            return;
        }
        ObjectNode payload = Json.object().put("chatId", chatId).put("title", prompt.orElseThrow().title()).put("status", result.name());
        if (result == GroupLinks.Result.LINKED) {
            payload.put("project", prompt.get().projects().get(index.orElseThrow().intValue()));
        } else {
            tx.afterCommit(() -> bestEffort("leaveChat", () -> api.leaveChat(chatId)));
        }
        Renderer.Rendered redrawn = renderer.render(OutboxKind.GROUP_LINK, Json.read(redactor.redact(payload.toString())));
        JsonNode message = callback.path("message");
        long promptChatId = message.path("chat").path("id").asLong();
        long messageId = message.path("message_id").asLong();
        tx.afterCommit(() -> bestEffort("editMessageText", () -> api.editMessageText(promptChatId, messageId, redrawn.html(), redrawn.keyboard())));
    }

    private void leave(Tx tx, long chatId, JsonNode from) {
        long userId = from.path("id").asLong();
        if (groupLinks != null) {
            groupLinks.left(tx, chatId);
        }
        tx.afterCommit(() -> {
            Log.warn("telegram.leaving_foreign_group", "chat_id", chatId, "user_id", userId);
            bestEffort("leaveChat", () -> api.leaveChat(chatId));
        });
    }

    private void answer(Tx tx, String callbackId, String textKey) {
        String text = renderer.text(textKey);
        tx.afterCommit(() -> bestEffort("answerCallbackQuery", () -> api.answerCallbackQuery(callbackId, text)));
    }

    private void help(Tx tx, Set<String> visible, String origin, String chatRef, boolean privateChat) {
        ObjectNode payload = Json.object().put("bot", botUsername).put("privateChat", privateChat);
        ArrayNode listed = payload.putArray("projects");
        projects.all().stream().filter(project -> visible.contains(project.name()))
                .forEach(project -> listed.addObject().put("name", project.name()).put("alias", project.alias()));
        enqueue(tx, OutboxKind.HELP, chatRef, origin, payload);
    }

    /**
     * The chat's projects in config order, each with its base branch and, if it cannot take tasks now, why not. Someone who
     * may link groups also gets each project's add link, privately: in a group it would invite anyone to try it.
     */
    private void projectList(Tx tx, Requester who, boolean privateChat, Set<String> visible, String origin, String chatRef) {
        boolean withAddLinks = privateChat && groupLinks != null && groups.mayManage(who.ref());
        ObjectNode payload = Json.object();
        ArrayNode listed = payload.putArray("projects");
        projects.all().stream().filter(project -> visible.contains(project.name())).forEach(project -> {
            ObjectNode line = listed.addObject().put("name", project.name()).put("alias", project.alias())
                    .put("baseBranch", project.baseBranch()).put("unavailable", projects.unavailableReason(project).orElse(null));
            if (withAddLinks) {
                addLink(botUsername, project).ifPresent(link -> line.put("addToGroup", link));
            }
        });
        enqueue(tx, OutboxKind.PROJECTS, chatRef, origin, payload);
    }

    /** /manage answers a member with a button that opens the Mini App, and everyone else as other commands do. */
    private void manage(Tx tx, Requester who, String origin, String chatRef) {
        if (!groups.isMember(who.ref()) && !groups.isAdmin(who.ref())) {
            enqueue(tx, OutboxKind.NOT_ALLOWED, chatRef, origin, Json.object().put("name", who.name()));
            return;
        }
        enqueue(tx, OutboxKind.MANAGE, chatRef, origin, Json.object().put("url", miniAppUrl));
    }

    /** /worker gives a one-time pairing code and lists the member's computers; /worker revoke N takes one away. */
    private void worker(Tx tx, Requester who, String args, String origin, String chatRef) {
        if (!groups.isMember(who.ref())) {
            enqueue(tx, OutboxKind.NOT_ALLOWED, chatRef, origin, Json.object().put("name", who.name()));
            return;
        }
        if (workers == null) {
            enqueue(tx, OutboxKind.WORKER_PAIRING, chatRef, origin, Json.object().put("personal", true));
            return;
        }
        String[] words = args.strip().split("\\s+");
        if (words[0].equals("revoke")) {
            revokeWorker(tx, who, words.length >= 2 ? words[1] : null, origin, chatRef);
            return;
        }
        ObjectNode payload = Json.object().put("personal", false)
                .put("code", workers.newCode(tx, who))
                .put("minutes", (int) WorkerKeys.CODE_LIFETIME.toMinutes())
                .put("url", workerUrl);
        ArrayNode list = payload.putArray("workers");
        for (Workers.Paired paired : workers.of(tx, who.ref())) {
            ObjectNode item = list.addObject().put("id", paired.id()).put("name", paired.name());
            item.put("lastSeenAt", paired.lastSeenAt() == null ? null : paired.lastSeenAt().toString());
        }
        enqueue(tx, OutboxKind.WORKER_PAIRING, chatRef, origin, payload);
    }

    /** @param number the argument after "revoke", or null when none was given; anything but a positive id is a usage refusal */
    private void revokeWorker(Tx tx, Requester who, String number, String origin, String chatRef) {
        long workerId = number == null ? -1 : parsePositiveLong(number);
        if (workerId <= 0) {
            enqueue(tx, OutboxKind.WORKER_USAGE, chatRef, origin, Json.object());
            return;
        }
        boolean found = workers.revoke(tx, workerId, who.ref(), groups.isAdmin(who.ref()));
        enqueue(tx, OutboxKind.WORKER_REVOKED, chatRef, origin, Json.object().put("workerId", workerId).put("found", found));
    }

    /** -1 for anything that is not a positive integer, so the caller can treat "not a valid id" as one case. */
    private static long parsePositiveLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return -1;
        }
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

    /** The photos (each at its largest size) and documents of {@code messages}, numbered in order. */
    static List<Attachment> attachments(JsonNode... messages) {
        List<Attachment> found = new ArrayList<>();
        for (JsonNode message : messages) {
            JsonNode largest = null;
            for (JsonNode size : message.path("photo")) {
                if (largest == null || area(size) > area(largest)) {
                    largest = size;
                }
            }
            if (largest != null && largest.has("file_id")) {
                found.add(new Attachment(largest.get("file_id").asText(), Attachment.safeName(found.size() + 1, "photo.jpg"), bytes(largest)));
            }
            JsonNode document = message.path("document");
            if (document.has("file_id")) {
                found.add(new Attachment(document.get("file_id").asText(),
                        Attachment.safeName(found.size() + 1, document.path("file_name").asText("file")), bytes(document)));
            }
        }
        return found;
    }

    private static long area(JsonNode photoSize) {
        return photoSize.path("width").asLong() * photoSize.path("height").asLong();
    }

    private static Long bytes(JsonNode file) {
        return file.has("file_size") ? file.get("file_size").asLong() : null;
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
        String name = TelegramNames.clean(first + " " + last);
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
