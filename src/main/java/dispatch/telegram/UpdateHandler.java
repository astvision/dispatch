package dispatch.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.Redactor;
import dispatch.config.Config;
import dispatch.core.DraftChoice;
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
import dispatch.store.Database;
import dispatch.store.Kv;
import dispatch.store.Outbox;
import dispatch.store.Tx;
import dispatch.store.Workers;
import dispatch.worker.WorkerKeys;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turns one Telegram update into core calls. The update's effects and the next offset commit in one transaction, so a
 * crash makes Telegram deliver it again and nothing is lost or applied twice (ADR 0010). The configured groups and
 * members' private chats with the bot are served (ADR 0011, 0012); other groups are left, other private chats ignored. A
 * group sees only its own projects, a member those of all their groups. Besides commands and buttons, a reply to a plan
 * message is a correction and a reply to a task's result is a follow-up.
 */
public final class UpdateHandler {

    static final String OFFSET_KEY = "telegram.offset";
    private static final Set<String> JOINED_STATUSES = Set.of("member", "administrator");
    /** Messages about a task's outcome; a reply to one is a follow-up (ADR 0006). */
    private static final Set<OutboxKind> RESULTS = Set.of(OutboxKind.TASK_COMPLETED, OutboxKind.TASK_COMPLETED_SHORT,
            OutboxKind.TASK_FAILED, OutboxKind.TASK_FAILED_SHORT);
    private static final Set<String> COMMANDS =
            Set.of("task", "status", "history", "stats", "cancel", "retry", "worker", "manage", "projects", "help", "start");

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

    /** @param redactor masks messages this handler edits directly, as the outbox sender does for everything it sends */
    public UpdateHandler(Database db, TaskService tasks, Membership membership, Groups groups, Projects projects, BotApi api,
                         Renderer renderer, Redactor redactor, String botUsername, Clock clock, Runnable wakeOutbox) {
        this(db, tasks, membership, groups, projects, api, renderer, redactor, botUsername, clock, wakeOutbox, null, null, null, null);
    }

    /**
     * @param redactor   masks messages this handler edits directly, as the outbox sender does for everything it sends
     * @param workers    null in personal mode, where a member has nothing to pair
     * @param workerUrl  the URL members' computers reach this machine on; null in personal mode
     * @param miniAppUrl where Telegram opens the Mini App; null when it is not configured, and /manage says so
     * @param groupLinks asks whoever may manage Dispatch which project an unknown group is for; null to leave every
     *                   unknown group, as before group linking
     */
    public UpdateHandler(Database db, TaskService tasks, Membership membership, Groups groups, Projects projects, BotApi api,
                         Renderer renderer, Redactor redactor, String botUsername, Clock clock, Runnable wakeOutbox,
                         WorkerKeys workers, String workerUrl, String miniAppUrl, GroupLinks groupLinks) {
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
                groupLinks.migrated(tx, chatId, newChatId);
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
            Task task = topicTask.get();
            if (task.phase() == Phase.COMPLETED || task.phase() == Phase.FAILED) {
                tasks.followUp(tx, who, task.id(), text(message), origin, chatRef);
            } else {
                tasks.correctLatest(tx, who, task.id(), text(message), origin, chatRef);
            }
            return;
        }
        if (parsed.isEmpty()) {
            if (!replyToTask(tx, message, who, origin, chatRef) && privateChat) {
                // Anything else a member writes privately is a task to give (ADR 0012).
                tasks.draft(tx, who, null, text(message), origin, attachments(message));
            }
            return;
        }
        Command command = parsed.get();
        if (!command.addressedTo(botUsername)) {
            return;
        }
        switch (command.name()) {
            case "task" -> {
                if (!privateChat) {
                    privateOnly(tx, chatRef, origin);
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
            case "projects" -> projectList(tx, visible, origin, chatRef);
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
        String[] firstAndRest = args.split("\\s+", 2);
        Set<String> mine = groups.projectsOfMember(who.ref());
        Optional<Config.Project> named = projects.find(firstAndRest[0]).filter(project -> mine.contains(project.name()));
        String own = named.isPresent() ? (firstAndRest.length > 1 ? firstAndRest[1].strip() : "") : args;
        tasks.draft(tx, who, named.map(Config.Project::name).orElse(null), withRepliedMessage(own, repliedTo), origin,
                attachments(repliedTo, message));
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
     * A button on a draft's prompt: project, priority, or ✂️ and its proposal's split and keep-whole choices (ADR 0013).
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
        });
        if (!Set.of(DraftChoice.PROJECT_CHOSEN, DraftChoice.CREATED, DraftChoice.SPLITTING, DraftChoice.SPLIT, DraftChoice.KEPT_WHOLE)
                .contains(choice)) {
            return;
        }
        ObjectNode payload = tasks.draftPayload(tx, draftId).orElseThrow();
        Renderer.Rendered prompt = renderer.render(OutboxKind.DRAFT_PROMPT, Json.read(redactor.redact(payload.toString())));
        tx.afterCommit(() -> bestEffort("editMessageText", () -> api.editMessageText(chatId, messageId, prompt.html(), prompt.keyboard())));
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
            boolean asks = message.has("new_chat_members")
                    || Command.parse(message).filter(command -> command.addressedTo(botUsername)).isPresent();
            if (asks && mayLink(from)) {
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

    /** Whether {@code from} is asked which project an unknown group is for, instead of the bot leaving it. */
    private boolean mayLink(JsonNode from) {
        return groupLinks != null && from.has("id") && groups.mayManage(Refs.user(from.get("id").asLong()));
    }

    /**
     * Asks {@code fromId} privately which project the group is for, once per open prompt. Sent directly rather than through
     * the outbox so that a refusal (they never pressed Start) is seen here: the bot then leaves.
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
            try {
                api.sendMessage(fromId, null, prompt.html(), null, prompt.keyboard());
                Log.info("group.link_asked", "chat_id", chatId, "user_id", fromId);
            } catch (RuntimeException e) {
                Log.warn("group.link_prompt_refused", "chat_id", chatId, "user_id", fromId, "error", e.getMessage());
                db.transaction(later -> groupLinks.forget(later, chatId));
                bestEffort("leaveChat", () -> api.leaveChat(chatId));
            }
        });
    }

    /** A button on a link prompt: a project's index in the prompt's list, or "-" to leave the group unlinked. */
    private void onLinkButton(Tx tx, JsonNode callback, Requester presser, long chatId, String choice) {
        Optional<GroupLinks.Prompt> prompt = groupLinks.prompt(tx, chatId);
        Optional<Long> index = taskId(choice);
        GroupLinks.Result result;
        if (choice.equals("-")) {
            result = groupLinks.decline(tx, presser, chatId);
        } else if (index.isPresent()) {
            result = groupLinks.link(tx, presser, chatId, index.get().intValue());
        } else {
            answer(tx, callback.path("id").asText(), "callback.unknown");
            return;
        }
        answer(tx, callback.path("id").asText(), switch (result) {
            case LINKED -> "callback.groupLinked";
            case DECLINED -> "callback.groupDeclined";
            case STALE -> "callback.groupStale";
            case CONFIG_FAILED -> "callback.groupLinkFailed";
            case NOT_ALLOWED -> "callback.notAdmin";
        });
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

    /** The chat's projects in config order, each with its base branch and, if it cannot take tasks now, why not. */
    private void projectList(Tx tx, Set<String> visible, String origin, String chatRef) {
        ObjectNode payload = Json.object();
        ArrayNode listed = payload.putArray("projects");
        projects.all().stream().filter(project -> visible.contains(project.name()))
                .forEach(project -> listed.addObject().put("name", project.name()).put("alias", project.alias())
                        .put("baseBranch", project.baseBranch()).put("unavailable", projects.unavailableReason(project).orElse(null)));
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
