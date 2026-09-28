package dispatch.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Language;
import dispatch.Text;
import dispatch.config.Config;
import dispatch.domain.Requester;
import dispatch.store.Conversations;
import dispatch.store.Tx;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;

/**
 * What the assistant may propose, and what a member's tap on a proposal does (A-1). A proposal is checked against the task
 * as it stands when the assistant answers, so a button is only ever offered for something the member may do then; the
 * tap runs the same path the chat's own commands and buttons run, which checks everything again.
 */
public final class AssistantActions {

    /** A proposal worth a button, as stored for the tap, or a note saying why not; {@code payload} is what the chat shows. */
    public record Checked(boolean valid, ObjectNode payload) {
    }

    /** How a tap ended, and a refusal's words when the task refused it (ADR 0031): the channel shows those. */
    public record Tapped(Outcome outcome, Optional<Text> refusal) {
    }

    /** What a tap came to, as its proposal records it; the channel words it unless the tap carries a refusal's words. */
    public enum Outcome {
        /** Done, or answered in the chat by the path it ran. */
        DONE,
        /** Tapped before, or not this member's button. */
        USED,
        /** The task moved on since it was proposed: another plan, a question already answered, a phase that no longer fits. */
        STALE,
        NOT_ALLOWED
    }

    /** What a reply shows of an answer or a follow-up; a longer one is refused, so the member confirms all of it. */
    static final int SHOWN_TEXT = 400;

    private final TaskService tasks;
    private final TaskCommands commands;
    private final Groups groups;
    private final Projects projects;
    private final Clock clock;
    private final TaskAccess access;

    public AssistantActions(TaskService tasks, Groups groups, Projects projects, Clock clock) {
        this.tasks = tasks;
        this.commands = tasks.commands();
        this.groups = groups;
        this.projects = projects;
        this.clock = clock;
        this.access = new TaskAccess(groups);
    }

    /** One of the assistant's proposed actions, as its structured output gave it. */
    public Checked check(Tx tx, Requester who, JsonNode action) {
        String type = action.path("type").asText();
        if (type.equals("draft")) {
            return draft(who, action);
        }
        long taskId = action.path("task").asLong(0);
        ObjectNode payload = Json.object().put("type", type).put("taskId", taskId);
        TaskAccess.Verdict verdict = access.of(tx, who.ref(), taskId);
        // A headline includes the title too (ADR 0020): only NONE, where there is nothing to show, leaves it out.
        if (verdict.sight() != TaskAccess.Sight.NONE) {
            payload.put("title", verdict.task().title());
        }
        return switch (type) {
            case "answer" -> answer(tx, who, taskId, verdict, action, payload);
            case "approve" -> decision(verdict, TaskAccess.Action.APPROVE, payload);
            case "reject" -> decision(verdict, TaskAccess.Action.REJECT, payload);
            case "cancel" -> offered(tx, who, new TaskCommand.Cancel(taskId), payload);
            case "retry" -> offered(tx, who, new TaskCommand.Retry(taskId), payload);
            case "followUp" -> followUp(tx, who, taskId, action, payload);
            default -> note(payload, "unknown");
        };
    }

    /**
     * Runs the member's proposed action {@code actionId} once.
     *
     * @param messageRef the assistant's reply that holds the button: where what the action gives comes from, a draft or a
     *                   merged task's follow-up
     */
    public Tapped run(Tx tx, Requester who, long actionId, String messageRef) {
        Optional<JsonNode> taken = Conversations.takeAction(tx, actionId, who.ref(), clock.instant());
        if (taken.isEmpty()) {
            return new Tapped(Outcome.USED, Optional.empty());
        }
        Tapped tapped = carryOut(tx, who, taken.get(), actionId, messageRef);
        Conversations.recordOutcome(tx, actionId, tapped.outcome().name());
        return tapped;
    }

    private Tapped carryOut(Tx tx, Requester who, JsonNode action, long actionId, String messageRef) {
        long taskId = action.path("taskId").asLong();
        int planSeq = action.path("planSeq").asInt();
        return switch (action.path("type").asText()) {
            // Unique per action, while still naming the reply its prompt goes under (as a split's parts do, ADR 0013).
            case "draft" -> new Tapped(switch (tasks.draft(tx, who, action.path("project").asText(null), action.path("text").asText(),
                    messageRef + "#a" + actionId)) {
                case DRAFTED, EMPTY, NO_PROJECTS -> Outcome.DONE;
                case DUPLICATE -> Outcome.USED;
                case NOT_ALLOWED -> Outcome.NOT_ALLOWED;
            }, Optional.empty());
            case "answer" -> {
                // As check stored it: an option's index, or the words the reply showed, which a "you decide" is too.
                TaskCommand.Choice choice = action.has("option") ? new TaskCommand.Choice.Option(action.get("option").asInt())
                        : new TaskCommand.Choice.Written(action.path("answer").asText());
                yield tapped(commands.run(tx, who, new TaskCommand.Answer(taskId, planSeq, action.path("question").asInt(), choice)));
            }
            case "approve" -> new Tapped(switch (tasks.approve(tx, who, taskId, planSeq)) {
                case APPROVED -> Outcome.DONE;
                case STALE_PLAN, WRONG_STATE, OPEN_QUESTIONS -> Outcome.STALE;
                case NOT_ALLOWED, NOT_FOUND, NOT_REQUESTER -> Outcome.NOT_ALLOWED;
            }, Optional.empty());
            case "reject" -> new Tapped(switch (tasks.reject(tx, who, taskId, planSeq)) {
                case REJECTED -> Outcome.DONE;
                case STALE_PLAN, WRONG_STATE -> Outcome.STALE;
                case NOT_ALLOWED, NOT_FOUND, NOT_REQUESTER -> Outcome.NOT_ALLOWED;
            }, Optional.empty());
            case "cancel" -> tapped(commands.run(tx, who, new TaskCommand.Cancel(taskId)));
            case "retry" -> tapped(commands.run(tx, who, new TaskCommand.Retry(taskId)));
            // Unique per action, as a draft's is: a merged task's follow-up becomes a task with this as its origin.
            case "followUp" -> tapped(commands.run(tx, who, new TaskCommand.FollowUp(taskId, action.path("text").asText(),
                    new Origin(messageRef + "#a" + actionId))));
            default -> throw new IllegalStateException("stored action of unknown type: " + action);
        };
    }

    /** A task command's result as a tap records it (ADR 0031): what refuses the member is not allowed, the rest moved on. */
    private static Tapped tapped(CommandResult result) {
        return switch (result) {
            case CommandResult.Refused refused -> new Tapped(switch (refused.reason().kind()) {
                case FORBIDDEN, NOT_FOUND -> Outcome.NOT_ALLOWED;
                case INVALID, CONFLICT -> Outcome.STALE;
            }, Optional.of(refused.words()));
            case CommandResult.Done done -> new Tapped(Outcome.DONE, Optional.empty());
            case CommandResult.Created created -> new Tapped(Outcome.DONE, Optional.empty());
            case CommandResult.Unchanged unchanged -> new Tapped(Outcome.DONE, Optional.empty());
        };
    }

    /** A project the member cannot use is left out, so the draft's prompt asks for one, as for any message. */
    private Checked draft(Requester who, JsonNode action) {
        String text = action.path("text").asText("").strip();
        ObjectNode payload = Json.object().put("type", "draft").put("text", text).put("title", TaskService.title(text));
        if (text.isEmpty()) {
            return note(payload, "empty");
        }
        Set<String> mine = groups.projectsOfMember(who.ref());
        String project = action.hasNonNull("project")
                ? projects.find(action.get("project").asText()).map(Config.Project::name).filter(mine::contains).orElse(null)
                : null;
        return new Checked(true, payload.put("project", project));
    }

    /**
     * An answer to the named question, refused as the command would refuse it (ADR 0031). A question the waiting plan does
     * not have, and words longer than the reply shows, are notes of their own.
     */
    private Checked answer(Tx tx, Requester who, long taskId, TaskAccess.Verdict verdict, JsonNode action, ObjectNode payload) {
        int index = action.path("question").asInt(0);
        TaskCommand.Answer answer = new TaskCommand.Answer(taskId, verdict.planSeq(), index, choice(action));
        Optional<Refusal> refused = verdict.refusal(TaskAccess.Action.ANSWER);
        // Which question is named matters only on a plan that waits for its answers: the command refuses anything else first.
        if (refused.isPresent() && refused.get() != Refusal.ALREADY_ANSWERED) {
            return offered(tx, who, answer, payload);
        }
        JsonNode question = null;
        for (JsonNode candidate : tasks.currentPlan(tx, taskId).orElseThrow().withArray("questions")) {
            if (candidate.path("index").asInt() == index) {
                question = candidate;
            }
        }
        if (question == null) {
            return note(payload, "noQuestion");
        }
        payload.put("planSeq", verdict.planSeq()).put("question", index);
        switch (answer.choice()) {
            case TaskCommand.Choice.Option option ->
                    payload.put("option", option.index()).put("answer", question.path("options").path(option.index()).asText());
            case TaskCommand.Choice.YouDecide decide -> payload.put("answer", Text.of("answer.youDecide").render(Language.MN));
            case TaskCommand.Choice.Written written -> {
                String text = written.text().strip();
                if (!shown(text)) {
                    return note(payload, "tooLong");
                }
                payload.put("answer", text);
            }
        }
        return offered(tx, who, answer, payload);
    }

    /** What a proposed answer says: its option counts from 1, as the member reads the question, where a button's counts from 0. */
    private static TaskCommand.Choice choice(JsonNode action) {
        if (action.has("option")) {
            return new TaskCommand.Choice.Option(action.path("option").asInt() - 1);
        }
        return action.path("decide").asBoolean(false) ? new TaskCommand.Choice.YouDecide()
                : new TaskCommand.Choice.Written(action.path("text").asText(""));
    }

    private static boolean shown(String text) {
        return text.codePointCount(0, text.length()) <= SHOWN_TEXT;
    }

    /** Approval needs a plan without questions: answering them makes the agent plan again, and that plan is approved. */
    private static Checked decision(TaskAccess.Verdict verdict, TaskAccess.Action action, ObjectNode payload) {
        Optional<Refusal> refused = verdict.refusal(action);
        if (refused.isPresent()) {
            return note(payload, reason(refused.get()));
        }
        return new Checked(true, payload.put("planSeq", verdict.planSeq()));
    }

    /**
     * More work on a finished task, refused as the command would refuse it. The check reads a follow-up's origin only to
     * find a repeat, and a page's never repeats; the tap gives the proposal's own.
     */
    private Checked followUp(Tx tx, Requester who, long taskId, JsonNode action, ObjectNode payload) {
        String text = action.path("text").asText("").strip();
        if (!shown(text)) {
            return note(payload, "tooLong");
        }
        return offered(tx, who, new TaskCommand.FollowUp(taskId, text, Origin.page()), payload.put("text", text));
    }

    /** A button only for what would run now; otherwise a note in the refusal's own words (ADR 0031). */
    private Checked offered(Tx tx, Requester who, TaskCommand command, ObjectNode payload) {
        return commands.check(tx, who, command)
                .map(refused -> new Checked(false, payload.put("words", refused.words().render(Language.MN))))
                .orElseGet(() -> new Checked(true, payload));
    }

    /** The note (assistant.note.*) the reply shows for a proposal task access refuses. */
    private static String reason(Refusal refusal) {
        return switch (refusal) {
            case NOT_MEMBER, NOT_FOUND -> "notFound";
            case NOT_REQUESTER -> "notYours";
            case OPEN_QUESTIONS -> "openQuestions";
            case OUT_OF_ORDER -> "order";
            case ALREADY_ANSWERED -> "answered";
            case WRONG_PHASE, STALE_PLAN, NOT_FAILED, NOT_EXECUTED, MERGED -> "phase";
            // Given by a task command, never by task access, so never a proposal's own refusal (ADR 0031).
            case EMPTY, UNKNOWN_PROJECT, PROJECT_UNAVAILABLE ->
                    throw new IllegalStateException("task access never refuses a proposal as " + refusal);
        };
    }

    private static Checked note(ObjectNode payload, String reason) {
        return new Checked(false, payload.put("reason", reason));
    }
}
