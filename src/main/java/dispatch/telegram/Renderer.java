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

    public record Button(String text, String data) {
    }

    public record Document(String fileName, String markdown) {
    }

    /** @param document non-null when the content is sent as a file; {@code html} is then its caption */
    public record Rendered(String html, List<Button> buttons, Document document) {
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

    public Rendered render(OutboxKind kind, JsonNode payload) {
        return render(kind, payload, false);
    }

    /** @param fellBack the message was meant for a private chat and goes to the group instead, with a Start hint */
    public Rendered render(OutboxKind kind, JsonNode payload, boolean fellBack) {
        String hint = fellBack ? format("fallback.hint", escape(botUsername)) : null;
        if (kind == OutboxKind.PLAN_READY) {
            // The hint has to count towards the length that decides between a message and a document.
            return plan(payload, hint);
        }
        Rendered rendered = switch (kind) {
            case TASK_QUEUED -> plain(format("task.queued", taskId(payload), escape(payload.path("project").asText()),
                    escape(payload.path("requester").asText())));
            case PLAN_READY -> throw new IllegalStateException("rendered above");
            case EXECUTION_QUEUED -> plain(format("task.executionQueued", taskId(payload), escape(payload.path("by").asText())));
            case CORRECTION_QUEUED -> plain(format("task.correctionQueued", taskId(payload)));
            case CORRECTION_REFUSED -> plain(switch (payload.path("reason").asText()) {
                case "stale" -> format("task.correctionStale", taskId(payload));
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
                    + detail(payload.path("detail").asText("")));
            case TASK_REJECTED -> plain(format("task.rejected", taskId(payload), escape(payload.path("by").asText())));
            case TASK_CANCELLED -> plain(format("task.cancelled", taskId(payload), escape(payload.path("by").asText())));
            case STATUS -> status(payload);
            case HISTORY -> history(payload.path("tasks"));
            case TASK_TIMELINE -> timeline(payload);
            case TASK_NOT_FOUND -> plain(format("task.notFound", taskId(payload)));
            case CANCEL_REFUSED -> plain(format("task.cancelRefused", taskId(payload), text("phase." + payload.path("phase").asText())));
            case NOT_ALLOWED -> plain(format("member.notAllowed", escape(payload.path("name").asText())));
            case UNKNOWN_PROJECT -> plain(format("project.unknown", escape(payload.path("given").asText()),
                    projectList(payload.path("projects"))));
            case PROJECT_UNAVAILABLE -> plain(format("project.unavailable", escape(payload.path("project").asText()),
                    escape(payload.path("reason").asText())));
            case TASK_USAGE -> plain(text("task.usage"));
            case TASK_IN_GROUP_ONLY -> plain(format("task.inGroupOnly", escape(payload.path("bot").asText())));
            case HELP -> plain(format(payload.path("privateChat").asBoolean() ? "help.private" : "help",
                    projectList(payload.path("projects")), escape(payload.path("bot").asText())));
        };
        return hint == null ? rendered : new Rendered(rendered.html() + "\n\n" + hint, rendered.buttons(), rendered.document());
    }

    /** @param hint the Start hint of a message that fell back to the group, null otherwise */
    private Rendered plan(JsonNode payload, String hint) {
        String taskId = taskId(payload);
        JsonNode plan = payload.path("plan");
        String planRef = taskId + ":" + payload.path("planSeq").asInt();
        boolean openQuestions = !plan.path("questions").isEmpty();
        // With open questions there is nothing to approve yet: members answer by replying (a correction).
        List<Button> buttons = openQuestions
                ? List.of(new Button(text("button.reject"), "reject:" + planRef))
                : List.of(new Button(text("button.approve"), "approve:" + planRef), new Button(text("button.reject"), "reject:" + planRef));
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
        html.append("\n<i>").append(format("plan.footer", money(payload.path("costUsd")),
                duration(Duration.ofSeconds(payload.path("durationSeconds").asLong())))).append("</i>");
        String withHint = hint == null ? "" : "\n\n" + hint;

        if (html.length() + withHint.length() <= MESSAGE_LIMIT) {
            return new Rendered(html + withHint, buttons, null);
        }
        String caption = truncate(title + "\n" + text("plan.document") + withHint, CAPTION_LIMIT);
        return new Rendered(caption, buttons, new Document("plan-" + taskId + ".md", markdown(payload)));
    }

    private void htmlSection(StringBuilder html, String labelKey, JsonNode items, boolean numbered) {
        if (items.isEmpty()) {
            return;
        }
        html.append("\n<b>").append(text(labelKey)).append("</b>\n");
        int number = 1;
        for (JsonNode item : items) {
            html.append(numbered ? number++ + ". " : "• ").append(escape(item.asText())).append('\n');
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
                md.append(number++).append(". ").append(item.asText()).append('\n');
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
        html.append("\n\n<i>").append(format("task.completedFooter", filesChanged, money(payload.path("costUsd")),
                duration(Duration.ofSeconds(payload.path("durationSeconds").asLong())))).append("</i>");
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
                String line = format("status.runLine", taskId(run), escape(run.path("project").asText()),
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
                blocks.add(format("status.queuedLine", taskId(run), escape(run.path("project").asText()),
                        text("kind." + run.path("kind").asText()), age(Instant.parse(run.path("queuedAt").asText())))
                        + "\n   " + escapeWithin(run.path("title").asText(), TITLE_LIMIT));
            }
        }
        if (!awaiting.isEmpty()) {
            blocks.add("\n" + text("status.awaiting"));
            for (JsonNode task : awaiting) {
                blocks.add(format("status.awaitingLine", taskId(task), escape(task.path("project").asText()),
                        escape(task.path("requester").asText()), age(Instant.parse(task.path("since").asText())))
                        + "\n   " + escapeWithin(task.path("title").asText(), TITLE_LIMIT));
            }
        }
        return plain(joinWithin(blocks, "\n"));
    }

    private Rendered history(JsonNode tasks) {
        if (tasks.isEmpty()) {
            return plain(text("history.empty"));
        }
        List<String> blocks = new ArrayList<>(List.of(text("history.header")));
        for (JsonNode task : tasks) {
            String phase = task.path("phase").asText();
            StringBuilder block = new StringBuilder("\n").append(format("history.line", OUTCOME_ICONS.getOrDefault(phase, "•"),
                    taskId(task), escape(task.path("project").asText()), money(task.path("costUsd")),
                    age(Instant.parse(task.path("completedAt").asText()))))
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

    private Rendered timeline(JsonNode payload) {
        List<String> blocks = new ArrayList<>();
        blocks.add(format("timeline.header", taskId(payload), escape(payload.path("project").asText()),
                escape(payload.path("requester").asText()))
                + "\n" + escapeWithin(payload.path("title").asText(), TITLE_LIMIT)
                + "\n" + format("timeline.created", dateTime(payload.path("createdAt").asText())) + "\n");
        for (JsonNode run : payload.path("runs")) {
            blocks.add(time(run.path("queuedAt").asText()) + " " + runHeadline(run) + runResult(run));
        }
        blocks.add("\n" + outcome(payload) + "\n" + format("timeline.total", money(payload.path("costUsd"))));
        return plain(joinWithin(blocks, "\n"));
    }

    private String runHeadline(JsonNode run) {
        String requestedBy = escape(run.path("requestedBy").asText("—"));
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

    private String time(String instant) {
        return DateTimeFormatter.ofPattern("HH:mm").withZone(clock.getZone()).format(Instant.parse(instant));
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

    private static final java.util.Map<String, String> OUTCOME_ICONS =
            java.util.Map.of("COMPLETED", "✅", "FAILED", "❌", "REJECTED", "🚫", "CANCELLED", "🛑");

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

    /** Escapes {@code text} and cuts the result to at most {@code limit} chars, never inside an entity. */
    private static String escapeWithin(String text, int limit) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String next = escape(String.valueOf(text.charAt(i)));
            if (escaped.length() + next.length() > limit - 1) {
                return escaped.append('…').toString();
            }
            escaped.append(next);
        }
        return escaped.toString();
    }

    private static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}
