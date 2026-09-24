package dispatch.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.domain.OutboxKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.MessageFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/** Turns outbox payloads into Telegram HTML. Every piece of task or agent text is escaped. */
public final class Renderer {

    static final int MESSAGE_LIMIT = 4096;
    static final int CAPTION_LIMIT = 1024;
    /** Limits on escaped text, so markup-heavy agent output cannot push a message past Telegram's limit. */
    private static final int DETAIL_LIMIT = 1500;
    private static final int SUMMARY_LIMIT = 2500;
    private static final int DENIAL_LIMIT = 200;
    private static final int DENIALS_SHOWN = 5;
    private static final int TITLE_LIMIT = 80;
    private static final int ACTION_LIMIT = 120;
    private static final int INSTRUCTION_LIMIT = 150;
    /** A plan question's text: long enough for any real question, short enough for its message. */
    private static final int QUESTION_LIMIT = 3000;
    /** Telegram sets no documented limit on a button's label, but a longer one is cut off on screen. */
    private static final int BUTTON_LIMIT = 40;
    /** Ten parts of this length still fit one message. */
    private static final int TOPIC_LIMIT = 300;

    /**
     * An inline button: either one that calls back with {@code data}, or one that opens the Mini App at
     * {@code webAppUrl} (ADR 0019). Exactly one of the two is set.
     */
    public record Button(String text, String data, String webAppUrl) {

        public Button(String text, String data) {
            this(text, data, null);
        }

        public static Button webApp(String text, String url) {
            return new Button(text, null, url);
        }
    }

    public record Document(String fileName, String markdown) {
    }

    /**
     * @param keyboard   rows of inline buttons, empty for none
     * @param document   non-null when the content is sent as a file; {@code html} is then its caption
     * @param forceReply the input field's placeholder when the message asks for a forced reply instead of offering
     *                   buttons; null otherwise
     */
    public record Rendered(String html, List<List<Button>> keyboard, Document document, String forceReply) {

        public Rendered(String html, List<List<Button>> keyboard, Document document) {
            this(html, keyboard, document, null);
        }
    }

    private final ResourceBundle messages;
    private final Clock clock;
    private final String botUsername;

    /** @param botUsername named in the hint that asks a requester to start a private chat with the bot */
    public Renderer(ResourceBundle messages, Clock clock, String botUsername) {
        this.messages = messages;
        this.clock = clock;
        this.botUsername = botUsername;
    }

    public static ResourceBundle mongolian() {
        return ResourceBundle.getBundle("messages", Locale.of("mn"),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    public String text(String key) {
        return messages.getString(key);
    }

    /** A command menu of {@code names}, each described as command.&lt;name&gt; says. */
    public List<BotApi.BotCommand> commands(String... names) {
        return java.util.Arrays.stream(names).map(name -> new BotApi.BotCommand(name, text("command." + name))).toList();
    }

    /** A linked group's menu: groups only read, tasks are given and cancelled privately (ADR 0012). */
    public List<BotApi.BotCommand> groupCommands() {
        return commands("status", "history", "stats", "projects", "help");
    }

    public Rendered render(OutboxKind kind, JsonNode payload) {
        return render(kind, payload, false);
    }

    /**
     * @param fellBack the message was meant for a private chat Telegram refused; the group gets a content-free notice
     *                 instead (ADR 0020), never {@code kind}'s actual rendering
     */
    public Rendered render(OutboxKind kind, JsonNode payload, boolean fellBack) {
        if (fellBack && kind == OutboxKind.DRAFT_PROMPT) {
            // A task given in the group (G-1b): its giver has to open the private chat first.
            return plain(format("group.taskStartFirst", escape(payload.path("requester").asText()), escape(botUsername)));
        }
        if (fellBack) {
            // Meant for the requester's private chat: the group learns only that it could not be delivered (ADR 0020).
            return plain(format("fallback.private", taskId(payload), escape(botUsername)));
        }
        if (kind == OutboxKind.PLAN_READY) {
            return plan(payload);
        }
        return switch (kind) {
            case TASK_QUEUED -> plain(format("task.queued", taskId(payload), escape(payload.path("project").asText()),
                    escape(payload.path("requester").asText()), icon(payload).strip(),
                    escapeWithin(payload.path("title").asText(), TITLE_LIMIT)));
            case TOPIC_CREATE -> plain(escape(topicName(payload.path("taskId").asLong(), payload.path("project").asText(),
                    payload.path("title").asText(), null)));
            case DRAFT_PROMPT -> draftPrompt(payload);
            case DRAFT_EXPIRED -> plain(text("draft.expired")
                    + (payload.hasNonNull("title") ? "\n" + escapeWithin(payload.get("title").asText(), TITLE_LIMIT) : ""));
            case PLAN_READY -> throw new IllegalStateException("rendered above");
            case PLAN_QUESTION -> planQuestion(payload);
            case PLAN_ANSWER_PROMPT -> new Rendered(format("plan.answerPrompt", taskId(payload), payload.path("index").asInt()), List.of(),
                    null, text("plan.answerPlaceholder"));
            case EXECUTION_QUEUED -> plain(format("task.executionQueued", taskId(payload), escape(payload.path("by").asText())));
            case CORRECTION_QUEUED -> plain(format("task.correctionQueued", taskId(payload)));
            case CORRECTION_REFUSED -> plain(switch (payload.path("reason").asText()) {
                case "stale" -> format("task.correctionStale", taskId(payload));
                case "alreadyAnswered" -> text("plan.alreadyAnswered");
                case "empty" -> text("plan.answerEmpty");
                case "notAllowed" -> text("callback.notAllowed");
                case "notFound" -> text("callback.notFound");
                case "notRequester" -> text("callback.notRequester");
                case "requester" -> format("task.correctionNotRequester", taskId(payload), escape(payload.path("requester").asText()));
                default -> format("task.correctionRefused", taskId(payload), text("phase." + payload.path("phase").asText()));
            });
            case TASK_COMPLETED -> completed(payload);
            case TASK_COMPLETED_SHORT -> plain(format("task.completed", taskId(payload), escape(payload.path("project").asText()))
                    + "\n" + (payload.path("filesChanged").asInt() == 0
                            ? text("task.completedNoChanges")
                            : format("task.completedPr", escape(payload.path("prUrl").asText()))));
            case TASK_FAILED_SHORT -> plain(format("task.failed", taskId(payload), escape(text("failure." + payload.path("reason").asText()))));
            case TASK_FAILED -> plain(format("task.failed", taskId(payload), escape(text("failure." + payload.path("reason").asText())))
                    + detail(payload.path("detail").asText("")) + "\n\n" + format("task.retryHint", taskId(payload)));
            case TASK_REJECTED -> plain(format("task.rejected", taskId(payload), escape(payload.path("by").asText())));
            case TASK_CANCELLED -> plain(format("task.cancelled", taskId(payload), escape(payload.path("by").asText())));
            case STATUS -> status(payload);
            case HISTORY -> history(payload.path("tasks"));
            case TASK_TIMELINE -> timeline(payload);
            case STATS -> stats(payload);
            case TASK_NOT_FOUND -> plain(format("task.notFound", taskId(payload)));
            case CANCEL_REFUSED -> plain(payload.path("reason").asText().equals("requester")
                    ? format("task.cancelNotRequester", taskId(payload), escape(payload.path("requester").asText()))
                    : format("task.cancelRefused", taskId(payload), text("phase." + payload.path("phase").asText())));
            case RETRY_QUEUED -> plain(format("task.retryQueued", taskId(payload), escape(payload.path("by").asText()),
                    text("kind." + payload.path("kind").asText())));
            case RETRY_REFUSED -> plain(payload.path("reason").asText().equals("requester")
                    ? format("task.retryNotRequester", taskId(payload), escape(payload.path("requester").asText()))
                    : format("task.retryRefused", taskId(payload), text("phase." + payload.path("phase").asText())));
            case FOLLOW_UP_QUEUED -> plain(format("task.followUpQueued", taskId(payload), escape(payload.path("by").asText())));
            case FOLLOW_UP_REFUSED -> plain(switch (payload.path("reason").asText()) {
                case "notExecuted" -> format("task.followUpNotExecuted", taskId(payload));
                case "requester" -> format("task.followUpNotRequester", taskId(payload), escape(payload.path("requester").asText()));
                default -> format("task.followUpRefused", taskId(payload), text("phase." + payload.path("phase").asText()));
            });
            case NOT_ALLOWED -> plain(format("member.notAllowed", escape(payload.path("name").asText())));
            case UNKNOWN_PROJECT -> plain(format("project.unknown", escape(payload.path("given").asText()),
                    projectList(payload.path("projects"))));
            case PROJECT_UNAVAILABLE -> plain(format("project.unavailable", escape(payload.path("project").asText()),
                    escape(payload.path("reason").asText())));
            case TASK_USAGE -> plain(text("task.usage"));
            case PRIVATE_ONLY -> plain(format("privateOnly", escape(payload.path("bot").asText())));
            case NO_PROJECTS -> plain(payload.hasNonNull("names")
                    ? format("group.noProjects", escape(payload.get("names").asText()))
                    : text("noProjects"));
            case HELP -> plain(format(payload.path("privateChat").asBoolean() ? "help.private" : "help",
                    projectList(payload.path("projects")), escape(payload.path("bot").asText())));
            case PROJECTS -> projects(payload.path("projects"));
            case WORKER_PAIRING -> workerPairing(payload);
            case WORKER_REVOKED -> plain(payload.path("found").asBoolean()
                    ? format("worker.revoked", String.valueOf(payload.path("workerId").asInt()))
                    : format("worker.revokeNotFound", String.valueOf(payload.path("workerId").asInt())));
            case WORKER_USAGE -> plain(text("worker.usage"));
            case WORKER_WAITING -> plain(format("worker.waiting", taskId(payload)));
            case WORKER_BLOCKED -> plain(format("blocked." + payload.path("code").asText(), taskId(payload),
                    escape(payload.path("detail").asText(""))));
            case JOIN_REQUEST -> joinRequest(payload);
            case JOIN_REQUESTED -> plain(text("join.requested"));
            case JOIN_APPROVED -> plain(format("join.approved", escape(payload.path("group").asText())));
            case JOIN_DENIED -> plain(text("join.denied"));
            case MANAGE -> manage(payload);
            case GROUP_LINK -> groupLink(payload);
            case GROUP_LINKED -> plain(format("group.greeting", escape(payload.path("projects").asText())));
            case GROUP_TASK_SENT -> plain(format("group.taskSent", escape(payload.path("requester").asText())));
            // Never actually sent: OutboxSender reacts directly instead of rendering a message for it.
            case GROUP_REACTION -> plain(escape(payload.path("emoji").asText()));
            case GROUP_WORKING -> plain(format("group.working", escape(payload.path("requester").asText())));
            case UNKNOWN_USERNAME -> plain(format("group.unknownUsername", escape(payload.path("username").asText())));
            case GROUP_READD -> plain(format("group.readdAfterMigration", escape(payload.path("group").asText())));
            case ASSISTANT_REPLY -> assistantReply(payload);
        };
    }

    /**
     * Asks which project a group the bot was added to belongs to; once answered, says what was linked or that it was not.
     * Buttons carry the project's index, not its name, so a long name still fits Telegram's 64 bytes of callback data.
     */
    private Rendered groupLink(JsonNode payload) {
        String title = escapeWithin(payload.path("title").asText(), TITLE_LIMIT);
        switch (payload.path("status").asText()) {
            case "LINKED" -> {
                return plain(format("group.linkedTo", title, escape(payload.path("project").asText())));
            }
            case "DECLINED" -> {
                return plain(format("group.declined", title));
            }
            case "MOVED" -> {
                return plain(format("group.linkMovedTo", title));
            }
            default -> {
                // OPEN: asked below.
            }
        }
        String chatId = String.valueOf(payload.path("chatId").asLong());
        List<List<Button>> keyboard = new ArrayList<>();
        List<Button> row = new ArrayList<>();
        JsonNode projects = payload.path("projects");
        for (int index = 0; index < projects.size(); index++) {
            row.add(new Button(projects.get(index).asText(), "link:" + chatId + ":" + index));
            if (row.size() == 3) {
                keyboard.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            keyboard.add(row);
        }
        keyboard.add(List.of(new Button(text("button.groupNoLink"), "link:" + chatId + ":-")));
        return new Rendered(format("group.linkAsk", title), keyboard, null);
    }

    /**
     * A draft's prompt. While open: project buttons (in rows of three, when there is a choice), a priority row and ✂️ for a
     * whole message; while a split is proposed, its parts and the choice between splitting and keeping the message whole.
     */
    private Rendered draftPrompt(JsonNode payload) {
        String title = escapeWithin(payload.path("title").asText(), TITLE_LIMIT);
        String project = payload.hasNonNull("project") ? escape(projectLabel(payload)) : null;
        JsonNode topics = payload.path("topics");
        switch (payload.path("status").asText()) {
            case "CREATED" -> {
                String key = payload.path("topic").asBoolean() ? "draft.createdInTopic" : "draft.created";
                return plain(format(key, String.valueOf(payload.path("taskId").asLong()), project, icon(payload).strip()) + "\n" + title);
            }
            case "EXPIRED" -> {
                return plain(text("draft.expired") + "\n" + title);
            }
            case "SPLIT" -> {
                return plain(format("draft.split", topics.size()) + "\n" + numbered(topics));
            }
            default -> {
                // OPEN: asked below.
            }
        }
        String id = String.valueOf(payload.path("draftId").asLong());
        String header = payload.hasNonNull("part")
                ? format("draft.headerPart", payload.path("part").asInt(), payload.path("parts").asInt())
                : text("draft.header");
        String split = payload.path("split").asText();
        if (split.equals("PROPOSED")) {
            return new Rendered(header + "\n" + title + "\n\n" + format("draft.proposed", topics.size()) + "\n" + numbered(topics),
                    List.of(List.of(new Button(format("button.splitInto", topics.size()), "draft:" + id + ":split:yes"),
                            new Button(text("button.keepWhole"), "draft:" + id + ":split:no"))), null);
        }
        String note = switch (split) {
            case "SPLITTING" -> "\n\n" + text("draft.splitting");
            case "ONE_TOPIC" -> "\n\n" + text("draft.oneTopic");
            case "FAILED" -> "\n\n" + text("draft.splitFailed");
            default -> "";
        };
        List<String> skipped = new ArrayList<>();
        payload.path("skippedFiles").forEach(file -> skipped.add(escape(file.asText())));
        String tooLarge = skipped.isEmpty() ? "" : "\n\n" + format("draft.filesTooLarge", String.join(", ", skipped));
        String html = header + "\n" + title + "\n\n"
                + (project == null ? text("draft.chooseProject") : format("draft.project", project)) + "\n" + text("draft.choosePriority")
                + note + tooLarge;
        List<List<Button>> keyboard = new ArrayList<>();
        JsonNode projects = payload.path("projects");
        if (projects.size() > 1) {
            List<Button> row = new ArrayList<>();
            for (JsonNode candidate : projects) {
                String name = candidate.path("name").asText();
                String chosen = name.equals(payload.path("project").asText()) ? "✓ " : "";
                row.add(new Button(chosen + label(candidate), "draft:" + id + ":p:" + name));
                if (row.size() == 3) {
                    keyboard.add(row);
                    row = new ArrayList<>();
                }
            }
            if (!row.isEmpty()) {
                keyboard.add(row);
            }
        }
        List<Button> priorities = new ArrayList<>();
        for (String priority : PRIORITIES) {
            priorities.add(new Button(PRIORITY_ICONS.get(priority) + " " + text("priority." + priority), "draft:" + id + ":prio:" + priority));
        }
        keyboard.add(priorities);
        if (payload.path("splittable").asBoolean()) {
            keyboard.add(List.of(new Button(text("button.split"), "draft:" + id + ":split:ask")));
        }
        return new Rendered(html, keyboard, null);
    }

    /** A split message's parts, one numbered line each. */
    private static String numbered(JsonNode topics) {
        List<String> lines = new ArrayList<>();
        for (JsonNode topic : topics) {
            lines.add(lines.size() + 1 + ". " + escapeWithin(topic.asText(), TOPIC_LIMIT));
        }
        return String.join("\n", lines);
    }

    /**
     * A task topic's name, plain text within Telegram's 128 characters.
     *
     * @param outcome the finished task's phase, whose icon then leads the name; null while it runs
     */
    public String topicName(long taskId, String project, String title, String outcome) {
        String icon = outcome == null ? "" : OUTCOME_ICONS.getOrDefault(outcome, "") + " ";
        String name = icon + "#" + taskId + " · " + project + " · " + title;
        return name.length() <= 128 ? name : name.substring(0, 127) + "…";
    }

    /** The member's one-time code, the exact command to run with it, and the computers they already paired. */
    /** /manage: the button that opens the Mini App, or why there is none (ADR 0019). */
    private Rendered manage(JsonNode payload) {
        if (!payload.hasNonNull("url")) {
            return plain(text("manage.off"));
        }
        return new Rendered(text("manage.open"),
                List.of(List.of(Button.webApp(text("manage.button"), payload.get("url").asText()))), null);
    }

    private Rendered workerPairing(JsonNode payload) {
        if (payload.path("personal").asBoolean()) {
            return plain(text("worker.personal"));
        }
        StringBuilder html = new StringBuilder(format("worker.pairing", escape(payload.path("code").asText()),
                String.valueOf(payload.path("minutes").asInt()), escape(payload.path("url").asText())));
        JsonNode workers = payload.path("workers");
        if (workers.isEmpty()) {
            return plain(html.append("\n\n").append(text("worker.none")).toString());
        }
        html.append("\n\n").append(text("worker.list"));
        for (JsonNode worker : workers) {
            String id = String.valueOf(worker.path("id").asInt());
            String name = escape(worker.path("name").asText());
            html.append("\n").append(worker.hasNonNull("lastSeenAt")
                    ? format("worker.item", id, name, age(Instant.parse(worker.get("lastSeenAt").asText())))
                    : format("worker.itemNeverSeen", id, name));
        }
        return plain(html.append("\n\n").append(text("worker.revokeHint")).toString());
    }

    /** Asks an admin about someone who wants to use the bot; once decided, says what was decided and by whom. */
    private Rendered joinRequest(JsonNode payload) {
        String name = escapeWithin(payload.path("name").asText(), TITLE_LIMIT);
        String username = payload.hasNonNull("username") ? " @" + escape(payload.get("username").asText()) : "";
        String userId = String.valueOf(payload.path("userId").asLong());
        String decidedBy = escape(payload.path("decidedBy").asText());
        switch (payload.path("status").asText()) {
            case "APPROVED" -> {
                return plain(format("join.approvedBy", name, username, userId, escape(payload.path("group").asText()), decidedBy));
            }
            case "DENIED" -> {
                return plain(format("join.deniedBy", name, username, userId, decidedBy));
            }
            default -> {
                // OPEN: asked below.
            }
        }
        String id = String.valueOf(payload.path("requestId").asLong());
        List<List<Button>> keyboard = new ArrayList<>();
        List<Button> row = new ArrayList<>();
        for (JsonNode group : payload.path("groups")) {
            row.add(new Button(format("button.joinGroup", group.asText()), "join:" + id + ":" + group.asText()));
            if (row.size() == 3) {
                keyboard.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            keyboard.add(row);
        }
        keyboard.add(List.of(new Button(text("button.joinDeny"), "join:" + id + ":-")));
        return new Rendered(format("join.request", name, username, userId), keyboard, null);
    }

    /** The draft's chosen project as members know it: its alias if it has one. */
    private static String projectLabel(JsonNode draft) {
        String name = draft.path("project").asText();
        for (JsonNode candidate : draft.path("projects")) {
            if (candidate.path("name").asText().equals(name)) {
                return label(candidate);
            }
        }
        return name;
    }

    private static String label(JsonNode project) {
        return project.hasNonNull("alias") ? project.get("alias").asText() : project.path("name").asText();
    }

    private Rendered plan(JsonNode payload) {
        String taskId = taskId(payload);
        JsonNode plan = payload.path("plan");
        String planRef = taskId + ":" + payload.path("planSeq").asInt();
        boolean openQuestions = !plan.path("questions").isEmpty();
        // With open questions there is nothing to approve yet: members answer them under the question messages (G-1d)
        // or by replying, and either way the answers come back as a correction.
        List<List<Button>> buttons = List.of(openQuestions
                ? List.of(new Button(text("button.reject"), "reject:" + planRef))
                : List.of(new Button(text("button.approve"), "approve:" + planRef), new Button(text("button.reject"), "reject:" + planRef)));
        String title = format("plan.title", taskId, escape(payload.path("project").asText()));

        StringBuilder html = new StringBuilder(title).append("\n\n")
                .append("<b>").append(text("plan.understanding")).append("</b>\n")
                .append(escape(plan.path("understanding").asText())).append('\n');
        htmlSection(html, "plan.findings", plan.path("findings"), false);
        htmlSection(html, "plan.steps", plan.path("steps"), true);
        htmlSection(html, "plan.risks", plan.path("risks"), false);
        if (openQuestions) {
            htmlSection(html, "plan.questions", plan.path("questions"), true);
            html.append("<i>").append(text("plan.questionsHint")).append("</i>\n");
        } else {
            html.append("\n<i>").append(text("plan.replyHint")).append("</i>\n");
        }
        html.append("\n<i>").append(modelPrefix(payload)).append(format("plan.footer", money(payload.path("costUsd")),
                duration(Duration.ofSeconds(payload.path("durationSeconds").asLong())))).append("</i>").append(modelWarning(payload));

        if (html.length() <= MESSAGE_LIMIT) {
            return new Rendered(html.toString(), buttons, null);
        }
        String caption = truncate(title + "\n" + text("plan.document"), CAPTION_LIMIT);
        return new Rendered(caption, buttons, new Document("plan-" + taskId + ".md", markdown(payload)));
    }

    /**
     * One open question of a plan, with a button per option and two for answering otherwise (G-1d); once answered, the
     * answer and no buttons. Buttons carry indexes only, so callback data stays within Telegram's 64 bytes.
     */
    private Rendered planQuestion(JsonNode payload) {
        String taskId = taskId(payload);
        int index = payload.path("index").asInt();
        String question = escapeWithin(payload.path("text").asText(), QUESTION_LIMIT);
        if (payload.hasNonNull("answer")) {
            // The answer gets whatever room the rest of the message leaves, so a long question and answer still fit.
            int budget = MESSAGE_LIMIT - format("plan.questionAnswered", taskId, index, payload.path("total").asInt(), question, "").length();
            return plain(format("plan.questionAnswered", taskId, index, payload.path("total").asInt(), question,
                    escapeWithin(payload.get("answer").asText(), budget)));
        }
        String data = "q:" + taskId + ":" + payload.path("planSeq").asInt() + ":" + index + ":";
        List<List<Button>> keyboard = new ArrayList<>();
        JsonNode options = payload.path("options");
        for (int option = 0; option < options.size(); option++) {
            keyboard.add(List.of(new Button(label(options.get(option).asText()), data + option)));
        }
        keyboard.add(List.of(new Button(text("button.answerOwn"), data + "w"), new Button(text("button.youDecide"), data + "d")));
        return new Rendered(format("plan.question", taskId, index, payload.path("total").asInt(), question), keyboard, null);
    }

    /**
     * The assistant's answer (A-1): its reply, then each proposed action spelled out in full with a numbered confirm button,
     * since a button's label is cut short; one already carried out is marked and loses its button. Notes say why a proposal
     * was not offered. Buttons carry the stored action's id only, well within Telegram's 64 bytes.
     */
    private Rendered assistantReply(JsonNode payload) {
        if (payload.path("failed").asBoolean(false)) {
            return plain(text("assistant.failed"));
        }
        if (payload.path("new").asBoolean(false)) {
            return plain(text("assistant.new"));
        }
        StringBuilder html = new StringBuilder(escapeWithin(payload.path("reply").asText(), SUMMARY_LIMIT));
        List<List<Button>> keyboard = new ArrayList<>();
        JsonNode actions = payload.path("actions");
        if (!actions.isEmpty()) {
            html.append("\n\n").append(text("assistant.proposals"));
        }
        int number = 1;
        for (JsonNode action : actions) {
            String type = action.path("type").asText();
            boolean done = action.path("done").asBoolean(false);
            html.append('\n').append(done ? "✔️ " : number + ". ").append(assistantAction(action));
            if (!done) {
                String label = type.equals("draft") ? text("assistant.button.draft") : format("assistant.button." + type, taskId(action));
                keyboard.add(List.of(new Button(label("✅ " + number + ". " + label), "as:" + action.path("id").asLong())));
            }
            number++;
        }
        for (JsonNode note : payload.path("notes")) {
            html.append("\n").append(format("assistant.note." + note.path("reason").asText(), taskId(note)));
        }
        return new Rendered(html.toString(), keyboard, null);
    }

    private String assistantAction(JsonNode action) {
        String title = escapeWithin(action.path("title").asText(), TITLE_LIMIT);
        return switch (action.path("type").asText()) {
            case "draft" -> format("assistant.item.draft", title,
                    action.hasNonNull("project") ? " (" + escape(action.get("project").asText()) + ")" : "");
            case "answer" -> format("assistant.item.answer", taskId(action), action.path("question").asInt(),
                    escapeWithin(action.path("answer").asText(), DETAIL_LIMIT));
            case "followUp" -> format("assistant.item.followUp", taskId(action), escapeWithin(action.path("text").asText(), DETAIL_LIMIT));
            default -> format("assistant.item." + action.path("type").asText(), taskId(action), title);
        };
    }

    /** A plan item's text; a question may be an object with answer options (G-1d). */
    private static String itemText(JsonNode item) {
        return item.isObject() ? item.path("text").asText() : item.asText();
    }

    private void htmlSection(StringBuilder html, String labelKey, JsonNode items, boolean numbered) {
        if (items.isEmpty()) {
            return;
        }
        html.append("\n<b>").append(text(labelKey)).append("</b>\n");
        int number = 1;
        for (JsonNode item : items) {
            html.append(numbered ? number++ + ". " : "• ").append(escape(itemText(item))).append('\n');
        }
    }

    private String markdown(JsonNode payload) {
        JsonNode plan = payload.path("plan");
        StringBuilder md = new StringBuilder("# #").append(taskId(payload)).append(' ')
                .append(payload.path("project").asText()).append("\n\n## ").append(text("plan.understanding")).append("\n\n")
                .append(plan.path("understanding").asText()).append('\n');
        for (String[] section : new String[][] {{"plan.findings", "findings"}, {"plan.steps", "steps"},
                {"plan.risks", "risks"}, {"plan.questions", "questions"}}) {
            JsonNode items = plan.path(section[1]);
            if (items.isEmpty()) {
                continue;
            }
            md.append("\n## ").append(text(section[0])).append("\n\n");
            int number = 1;
            for (JsonNode item : items) {
                md.append(number++).append(". ").append(itemText(item)).append('\n');
            }
        }
        return md.toString();
    }

    private Rendered completed(JsonNode payload) {
        int filesChanged = payload.path("filesChanged").asInt();
        StringBuilder html = new StringBuilder(format("task.completed", taskId(payload), escape(payload.path("project").asText())))
                .append('\n')
                .append(filesChanged == 0 ? text("task.completedNoChanges") : format("task.completedPr", escape(payload.path("prUrl").asText())));
        String summary = payload.path("summary").asText("").strip();
        if (!summary.isEmpty()) {
            html.append("\n\n").append(escapeWithin(summary, SUMMARY_LIMIT));
        }
        JsonNode denials = payload.path("denials");
        if (!denials.isEmpty()) {
            html.append("\n\n").append(format("task.denials", denials.size()));
            int shown = 0;
            for (JsonNode denial : denials) {
                if (shown++ == DENIALS_SHOWN) {
                    html.append("\n…");
                    break;
                }
                html.append("\n• ").append(escapeWithin(denial.asText(), DENIAL_LIMIT));
            }
        }
        html.append("\n\n<i>").append(modelPrefix(payload)).append(format("task.completedFooter", filesChanged, money(payload.path("costUsd")),
                duration(Duration.ofSeconds(payload.path("durationSeconds").asLong())))).append("</i>").append(modelWarning(payload));
        html.append("\n").append(text("task.followUpHint"));
        return plain(html.toString());
    }

    private Rendered status(JsonNode payload) {
        JsonNode running = payload.path("running");
        JsonNode queued = payload.path("queued");
        JsonNode awaiting = payload.path("awaitingApproval");
        if (running.isEmpty() && queued.isEmpty() && awaiting.isEmpty()) {
            return plain(text("status.empty"));
        }
        List<String> blocks = new ArrayList<>();
        if (!running.isEmpty()) {
            blocks.add(text("status.running"));
            for (JsonNode run : running) {
                String line = icon(run) + format("status.runLine", taskId(run), escape(run.path("project").asText()),
                        text("kind." + run.path("kind").asText()), age(Instant.parse(run.path("startedAt").asText())));
                if (run.hasNonNull("steps")) {
                    line += " · " + format("status.steps", run.path("steps").asInt());
                }
                line += "\n   " + escapeWithin(run.path("title").asText(), TITLE_LIMIT);
                if (run.hasNonNull("lastAction")) {
                    line += "\n   └ " + escapeWithin(run.path("lastAction").asText(), ACTION_LIMIT);
                }
                blocks.add(line);
            }
        }
        if (!queued.isEmpty()) {
            blocks.add("\n" + text("status.queued"));
            for (JsonNode run : queued) {
                blocks.add(icon(run) + format("status.queuedLine", taskId(run), escape(run.path("project").asText()),
                        text("kind." + run.path("kind").asText()), age(Instant.parse(run.path("queuedAt").asText())))
                        + (run.path("waitingForWorker").asBoolean() ? " · " + text("status.waitingForWorker") : "")
                        + (run.hasNonNull("blocked") ? " · " + text("status.blocked." + run.get("blocked").asText()) : "")
                        + "\n   " + escapeWithin(run.path("title").asText(), TITLE_LIMIT));
            }
        }
        if (!awaiting.isEmpty()) {
            blocks.add("\n" + text("status.awaiting"));
            for (JsonNode task : awaiting) {
                blocks.add(icon(task) + format("status.awaitingLine", taskId(task), escape(task.path("project").asText()),
                        escape(task.path("requester").asText()), age(Instant.parse(task.path("since").asText())))
                        + "\n   " + escapeWithin(task.path("title").asText(), TITLE_LIMIT));
            }
        }
        List<List<Button>> keyboard = new ArrayList<>();
        for (JsonNode task : payload.path("mine")) {
            String id = taskId(task);
            List<Button> row = new ArrayList<>();
            for (String priority : PRIORITIES) {
                String current = priority.equals(task.path("priority").asText()) ? "✓" : "";
                row.add(new Button("#" + id + " " + current + PRIORITY_ICONS.get(priority), "prio:" + id + ":" + priority));
            }
            keyboard.add(row);
        }
        return new Rendered(joinWithin(blocks, "\n"), keyboard, null);
    }

    private Rendered history(JsonNode tasks) {
        if (tasks.isEmpty()) {
            return plain(text("history.empty"));
        }
        List<String> blocks = new ArrayList<>(List.of(text("history.header")));
        for (JsonNode task : tasks) {
            String phase = task.path("phase").asText();
            String icons = (OUTCOME_ICONS.getOrDefault(phase, "•") + " " + icon(task)).strip();
            String line = task.hasNonNull("costUsd")
                    ? format("history.line", icons, taskId(task), escape(task.path("project").asText()),
                            escape(task.path("requester").asText()), shortDateTime(task.path("createdAt").asText()), money(task.path("costUsd")))
                    : format("history.lineNoCost", icons, taskId(task), escape(task.path("project").asText()),
                            escape(task.path("requester").asText()), shortDateTime(task.path("createdAt").asText()));
            StringBuilder block = new StringBuilder("\n").append(line)
                    .append("\n").append(escapeWithin(task.path("title").asText(), TITLE_LIMIT));
            if (task.hasNonNull("prUrl")) {
                block.append("\n").append(escape(task.path("prUrl").asText()));
            }
            if (task.hasNonNull("failureReason")) {
                block.append("\n").append(text("failure." + task.path("failureReason").asText()));
            }
            blocks.add(block.toString());
        }
        blocks.add("\n" + text("history.more"));
        return plain(joinWithin(blocks, "\n"));
    }

    private Rendered stats(JsonNode payload) {
        String view = payload.path("view").asText();
        String period = payload.path("period").asText();
        String title = switch (view) {
            case "me" -> text("stats.view.me");
            case "people" -> text("stats.view.people");
            default -> escape(view.substring(view.indexOf(':') + 1));
        };
        StringBuilder html = new StringBuilder(format("stats.header", title, text("stats.period." + period)));
        JsonNode summary = payload.path("summary");
        if (summary.path("tasks").asInt() == 0) {
            html.append("\n\n").append(text("stats.empty"));
        } else {
            html.append("\n\n").append(format("stats.tasks", summary.path("tasks").asInt(), summary.path("completed").asInt(),
                    summary.path("failed").asInt(), summary.path("rejected").asInt(), summary.path("cancelled").asInt(),
                    summary.path("active").asInt()));
            html.append("\n").append(summary.hasNonNull("costUsd")
                    ? format("stats.pullRequests", summary.path("pullRequests").asInt(), money(summary.path("costUsd")),
                            money(summary.path("averageCostUsd")))
                    : format("stats.pullRequestsNoCost", summary.path("pullRequests").asInt()));
            if (summary.hasNonNull("medianMinutesToPr")) {
                html.append("\n").append(format("stats.timeToPr", duration(Duration.ofMinutes(summary.path("medianMinutesToPr").asLong()))));
            }
            if (summary.hasNonNull("approvedWithoutCorrectionPercent")) {
                html.append("\n").append(format("stats.firstTime", summary.path("approvedWithoutCorrectionPercent").asInt()));
            }
        }
        if (payload.hasNonNull("chatCostUsd")) {
            html.append("\n").append(format("stats.chat", money(payload.get("chatCostUsd"))));
        }
        List<String> blocks = new ArrayList<>(List.of(html.toString()));
        for (JsonNode person : payload.path("people")) {
            String separator = blocks.size() == 1 ? "\n" : "";
            blocks.add(separator + (person.hasNonNull("costUsd")
                    ? format("stats.person", escape(person.path("name").asText()), person.path("tasks").asInt(),
                            person.path("completed").asInt(), money(person.path("costUsd")))
                    : format("stats.personNoCost", escape(person.path("name").asText()), person.path("tasks").asInt(),
                            person.path("completed").asInt())));
        }

        List<Button> views = new ArrayList<>();
        if (payload.path("canViewMe").asBoolean()) {
            views.add(statsButton(text("stats.view.me"), period, "me", view));
        }
        for (JsonNode group : payload.path("groups")) {
            views.add(statsButton(group.asText(), period, "group:" + group.asText(), view));
        }
        views.add(statsButton(text("stats.view.people"), period, "people", view));
        List<List<Button>> keyboard = new ArrayList<>();
        for (int i = 0; i < views.size(); i += 3) {
            keyboard.add(views.subList(i, Math.min(i + 3, views.size())));
        }
        List<Button> periods = new ArrayList<>();
        for (String option : List.of("week", "month", "all")) {
            String chosen = option.equals(period) ? "✓ " : "";
            periods.add(new Button(chosen + text("stats.period." + option), "stats:" + option + ":" + view));
        }
        keyboard.add(periods);
        return new Rendered(joinWithin(blocks, "\n"), keyboard, null);
    }

    private static Button statsButton(String label, String period, String view, String currentView) {
        return new Button((view.equals(currentView) ? "✓ " : "") + label, "stats:" + period + ":" + view);
    }

    private Rendered timeline(JsonNode payload) {
        List<String> blocks = new ArrayList<>();
        blocks.add(format("timeline.header", taskId(payload), escape(payload.path("project").asText()),
                escape(payload.path("requester").asText()))
                + "\n" + escapeWithin(payload.path("title").asText(), TITLE_LIMIT)
                + "\n" + format("timeline.created", dateTime(payload.path("createdAt").asText())) + "\n");
        if (payload.path("headline").asBoolean()) {
            // Someone else's task: its runs and cost are theirs (ADR 0020).
            blocks.add("\n" + outcome(payload));
            return plain(joinWithin(blocks, "\n"));
        }
        for (JsonNode run : payload.path("runs")) {
            blocks.add(time(run.path("queuedAt").asText()) + " " + runHeadline(run) + runResult(run));
        }
        blocks.add("\n" + outcome(payload) + "\n" + format("timeline.total", money(payload.path("costUsd"))));
        return plain(joinWithin(blocks, "\n"));
    }

    private String runHeadline(JsonNode run) {
        String requestedBy = escape(run.path("requestedBy").asText("—"));
        switch (run.path("cause").asText()) {
            case "RETRY" -> {
                return format("timeline.retry", requestedBy, text("kind." + run.path("kind").asText()));
            }
            case "FOLLOW_UP" -> {
                return format("timeline.followUp", requestedBy, escapeWithin(run.path("instruction").asText(), INSTRUCTION_LIMIT));
            }
            default -> {
                // The first plan, a correction or the approved execution: told apart by kind below.
            }
        }
        return switch (run.path("kind").asText()) {
            case "PLAN" -> run.hasNonNull("instruction")
                    ? format("timeline.correction", requestedBy, escapeWithin(run.path("instruction").asText(), INSTRUCTION_LIMIT))
                    : text("timeline.plan");
            case "EXECUTE" -> format("timeline.execute", requestedBy);
            default -> text("kind." + run.path("kind").asText());
        };
    }

    private String runResult(JsonNode run) {
        List<String> parts = new ArrayList<>();
        switch (run.path("status").asText()) {
            case "QUEUED" -> parts.add(text("timeline.queued"));
            case "RUNNING" -> parts.add(text("timeline.running"));
            case "FAILED" -> parts.add("❌ " + text("failure." + run.path("failureReason").asText()));
            case "CANCELLED" -> parts.add(text("timeline.stopped"));
            default -> {
                // SUCCEEDED: duration and cost say enough.
            }
        }
        if (run.hasNonNull("startedAt") && run.hasNonNull("finishedAt")) {
            parts.add(duration(Duration.between(Instant.parse(run.path("startedAt").asText()),
                    Instant.parse(run.path("finishedAt").asText()))));
        }
        if (run.hasNonNull("costUsd")) {
            parts.add(money(run.path("costUsd")));
        }
        return parts.isEmpty() ? "" : " · " + String.join(" · ", parts);
    }

    private String outcome(JsonNode payload) {
        return switch (payload.path("phase").asText()) {
            case "COMPLETED" -> payload.hasNonNull("prUrl")
                    ? format("timeline.completedPr", escape(payload.path("prUrl").asText()))
                    : text("timeline.completedNoChanges");
            case "FAILED" -> format("timeline.failed", text("failure." + payload.path("failureReason").asText()));
            case "REJECTED" -> text("timeline.rejected");
            case "CANCELLED" -> text("timeline.cancelled");
            default -> format("timeline.active", text("phase." + payload.path("phase").asText()));
        };
    }

    /** Joins blocks until the next would pass the message limit, then marks the cut. */
    private static String joinWithin(List<String> blocks, String separator) {
        StringBuilder html = new StringBuilder();
        for (String block : blocks) {
            String next = html.isEmpty() ? block : separator + block;
            if (html.length() + next.length() > MESSAGE_LIMIT - 2) {
                return html.append("\n…").toString();
            }
            html.append(next);
        }
        return html.toString();
    }

    private Rendered projects(JsonNode projects) {
        if (projects.isEmpty()) {
            return plain(text("projects.empty"));
        }
        List<String> blocks = new ArrayList<>(List.of(text("projects.header")));
        for (JsonNode project : projects) {
            String alias = project.hasNonNull("alias") ? " (" + escape(project.get("alias").asText()) + ")" : "";
            String line = format("projects.line", escape(project.path("name").asText()), alias, escape(project.path("baseBranch").asText()));
            if (project.hasNonNull("unavailable")) {
                line += "\n   " + format("projects.unavailable", escapeWithin(project.get("unavailable").asText(), DETAIL_LIMIT));
            }
            blocks.add(line);
        }
        return plain(joinWithin(blocks, "\n"));
    }

    private String time(String instant) {
        return DateTimeFormatter.ofPattern("HH:mm").withZone(clock.getZone()).format(Instant.parse(instant));
    }

    private String shortDateTime(String instant) {
        return DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(clock.getZone()).format(Instant.parse(instant));
    }

    private String dateTime(String instant) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(clock.getZone()).format(Instant.parse(instant));
    }

    private String projectList(JsonNode projects) {
        List<String> names = new ArrayList<>();
        for (JsonNode project : projects) {
            String name = escape(project.path("name").asText());
            names.add(project.hasNonNull("alias")
                    ? "<code>" + escape(project.get("alias").asText()) + "</code> (" + name + ")"
                    : "<code>" + name + "</code>");
        }
        return String.join(", ", names);
    }

    private String detail(String detail) {
        return detail.isBlank() ? "" : "\n<pre>" + escapeWithin(detail, DETAIL_LIMIT) + "</pre>";
    }

    private String age(Instant createdAt) {
        Duration age = Duration.between(createdAt, clock.instant());
        return age.toMinutes() < 1 ? text("age.justNow") : duration(age.truncatedTo(java.time.temporal.ChronoUnit.MINUTES));
    }

    private String duration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        List<String> parts = new ArrayList<>();
        if (hours > 0) {
            parts.add(format("duration.hours", hours));
        }
        if (minutes > 0) {
            parts.add(format("duration.minutes", minutes));
        }
        if (seconds > 0 || parts.isEmpty()) {
            parts.add(format("duration.seconds", seconds));
        }
        return String.join(" ", parts);
    }

    private static String money(JsonNode costUsd) {
        return costUsd.isTextual() ? "$" + new BigDecimal(costUsd.asText()).setScale(2, RoundingMode.HALF_UP).toPlainString() : "—";
    }

    private static final List<String> PRIORITIES = List.of("URGENT", "NORMAL", "LOW");
    private static final java.util.Map<String, String> PRIORITY_ICONS = java.util.Map.of("URGENT", "🔴", "NORMAL", "🟡", "LOW", "🟢");

    /** The priority icon and a space, or nothing for items from before priorities existed. */
    private static String icon(JsonNode item) {
        String icon = PRIORITY_ICONS.get(item.path("priority").asText());
        return icon == null ? "" : icon + " ";
    }

    private static final java.util.Map<String, String> OUTCOME_ICONS =
            java.util.Map.of("COMPLETED", "✅", "FAILED", "❌", "REJECTED", "🚫", "CANCELLED", "🛑");

    /** "sonnet-5 · " before a footer; nothing for a run recorded before models were. */
    private static String modelPrefix(JsonNode payload) {
        return payload.hasNonNull("model") ? escape(shortModels(payload.get("model").asText())) + " · " : "";
    }

    /** A line saying the run was answered by another model than its config asks for. */
    private String modelWarning(JsonNode payload) {
        if (!payload.hasNonNull("requestedModel") || !payload.hasNonNull("model")) {
            return "";
        }
        return "\n" + format("run.modelDiffers", escape(payload.get("requestedModel").asText()), escape(shortModels(payload.get("model").asText())));
    }

    /** claude-haiku-4-5-20251001 becomes haiku-4-5: family and version are what a reader compares. */
    private static String shortModels(String models) {
        return java.util.Arrays.stream(models.split(", "))
                .map(model -> model.replaceFirst("^claude-", "").replaceFirst("-\\d{8}$", ""))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String taskId(JsonNode payload) {
        return String.valueOf(payload.path("taskId").asLong());
    }

    private String format(String key, Object... args) {
        Object[] texts = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            texts[i] = String.valueOf(args[i]);   // strings, so MessageFormat never applies number grouping
        }
        return new MessageFormat(text(key), Locale.ROOT).format(texts);
    }

    private static Rendered plain(String html) {
        return new Rendered(html, List.of(), null);
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Escapes {@code text} and cuts the result to at most {@code limit} chars, never inside an entity or an emoji. */
    private static String escapeWithin(String text, int limit) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < text.length(); i += Character.charCount(text.codePointAt(i))) {
            String next = escape(Character.toString(text.codePointAt(i)));
            if (escaped.length() + next.length() > limit - 1) {
                return escaped.append('…').toString();
            }
            escaped.append(next);
        }
        return escaped.toString();
    }

    /** A button label of at most {@link #BUTTON_LIMIT} code points, so an emoji is never cut in half. */
    private static String label(String text) {
        return text.codePointCount(0, text.length()) <= BUTTON_LIMIT
                ? text
                : text.substring(0, text.offsetByCodePoints(0, BUTTON_LIMIT - 1)) + "…";
    }

    private static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}
