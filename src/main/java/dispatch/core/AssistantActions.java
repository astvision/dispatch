package dispatch.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.config.Config;
import dispatch.domain.Phase;
import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.domain.Task;
import dispatch.store.Conversations;
import dispatch.store.Outbox;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;

/**
 * What the assistant may propose, and what a member's tap on a proposal does (A-1). A proposal is checked against the task
 * as it stands when the assistant answers, so a button is only ever offered for something the member may do then; the
 * tap runs the same {@link TaskService} path the chat's own buttons run, which checks everything again.
 */
public final class AssistantActions {

    /** A proposal worth a button, as stored for the tap, or a note saying why not; {@code payload} is what the chat shows. */
    public record Checked(boolean valid, ObjectNode payload) {
    }

    /** How a tap ended; the channel says it in words. */
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
    private final Groups groups;
    private final Projects projects;
    private final Clock clock;
    private final String youDecide;

    /** @param youDecide what a "you decide" answer says, in the same words as the chat's own button */
    public AssistantActions(TaskService tasks, Groups groups, Projects projects, Clock clock, String youDecide) {
        this.tasks = tasks;
        this.groups = groups;
        this.projects = projects;
        this.clock = clock;
        this.youDecide = youDecide;
    }

    /** One of the assistant's proposed actions, as its structured output gave it. */
    public Checked check(Tx tx, Requester who, JsonNode action) {
        String type = action.path("type").asText();
        if (type.equals("draft")) {
            return draft(who, action);
        }
        long taskId = action.path("task").asLong(0);
        ObjectNode payload = Json.object().put("type", type).put("taskId", taskId);
        Optional<Task> found = Tasks.find(tx, taskId)
                .filter(task -> groups.projectsOfMember(who.ref()).contains(task.project()));
        if (found.isEmpty()) {
            return note(payload, "notFound");
        }
        Task task = found.get();
        if (!task.requester().ref().equals(who.ref())) {
            return note(payload, "notYours");
        }
        payload.put("title", task.title());
        return switch (type) {
            case "answer" -> answer(tx, task, action, payload);
            case "approve", "reject" -> decision(tx, task, type, payload);
            case "cancel" -> task.phase().isActive() ? new Checked(true, payload) : note(payload, "phase");
            case "retry" -> task.phase() == Phase.FAILED ? new Checked(true, payload) : note(payload, "phase");
            case "followUp" -> followUp(tx, task, action, payload);
            default -> note(payload, "unknown");
        };
    }

    /**
     * Runs the member's proposed action {@code actionId} once.
     *
     * @param messageRef the assistant's reply that holds the button; what the action's own messages answer under
     * @param chatRef    the member's private chat
     */
    public Outcome run(Tx tx, Requester who, long actionId, String messageRef, String chatRef) {
        Optional<JsonNode> taken = Conversations.takeAction(tx, actionId, who.ref(), clock.instant());
        if (taken.isEmpty()) {
            return Outcome.USED;
        }
        Outcome outcome = carryOut(tx, who, taken.get(), actionId, messageRef, chatRef);
        Conversations.recordOutcome(tx, actionId, outcome.name());
        return outcome;
    }

    private Outcome carryOut(Tx tx, Requester who, JsonNode action, long actionId, String messageRef, String chatRef) {
        long taskId = action.path("taskId").asLong();
        int planSeq = action.path("planSeq").asInt();
        return switch (action.path("type").asText()) {
            // Unique per action, while still naming the reply its prompt goes under (as a split's parts do, ADR 0013).
            case "draft" -> switch (tasks.draft(tx, who, action.path("project").asText(null), action.path("text").asText(),
                    messageRef + "#a" + actionId)) {
                case DRAFTED, EMPTY, NO_PROJECTS -> Outcome.DONE;
                case DUPLICATE -> Outcome.USED;
                case NOT_ALLOWED -> Outcome.NOT_ALLOWED;
            };
            case "answer" -> answered(tx, who, action, taskId, planSeq, messageRef, chatRef);
            case "approve" -> switch (tasks.approve(tx, who, taskId, planSeq)) {
                case APPROVED -> Outcome.DONE;
                case STALE_PLAN, WRONG_STATE, OPEN_QUESTIONS -> Outcome.STALE;
                case NOT_ALLOWED, NOT_FOUND, NOT_REQUESTER -> Outcome.NOT_ALLOWED;
            };
            case "reject" -> switch (tasks.reject(tx, who, taskId, planSeq)) {
                case REJECTED -> Outcome.DONE;
                case STALE_PLAN, WRONG_STATE -> Outcome.STALE;
                case NOT_ALLOWED, NOT_FOUND, NOT_REQUESTER -> Outcome.NOT_ALLOWED;
            };
            // These three answer in the chat themselves, a refusal included, exactly as their commands do.
            case "cancel" -> {
                tasks.cancel(tx, who, taskId, messageRef, chatRef);
                yield Outcome.DONE;
            }
            case "retry" -> {
                tasks.retry(tx, who, taskId, messageRef, chatRef);
                yield Outcome.DONE;
            }
            case "followUp" -> {
                tasks.followUp(tx, who, taskId, action.path("text").asText(), messageRef, chatRef);
                yield Outcome.DONE;
            }
            default -> throw new IllegalStateException("stored action of unknown type: " + action);
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

    private Checked answer(Tx tx, Task task, JsonNode action, ObjectNode payload) {
        Optional<ObjectNode> plan = task.phase() == Phase.AWAITING_APPROVAL ? tasks.currentPlan(tx, task.id()) : Optional.empty();
        if (plan.isEmpty()) {
            return note(payload, "phase");
        }
        int index = action.path("question").asInt(0);
        JsonNode question = null;
        for (JsonNode candidate : plan.get().withArray("questions")) {
            if (candidate.path("index").asInt() == index) {
                question = candidate;
            }
        }
        if (question == null) {
            return note(payload, "noQuestion");
        }
        if (!question.path("answer").isNull()) {
            return note(payload, "answered");
        }
        // The chat asks one question at a time; answering a later one first would ask the current one again.
        for (JsonNode earlier : plan.get().withArray("questions")) {
            if (earlier.path("index").asInt() < index && earlier.path("answer").isNull()) {
                return note(payload, "order");
            }
        }
        payload.put("planSeq", plan.get().path("planSeq").asInt()).put("question", index);
        JsonNode options = question.path("options");
        int option = action.path("option").asInt(0);
        if (option >= 1 && option <= options.size()) {
            return new Checked(true, payload.put("option", option - 1).put("answer", options.get(option - 1).asText()));
        }
        if (action.path("decide").asBoolean(false)) {
            return new Checked(true, payload.put("answer", youDecide));
        }
        String text = action.path("text").asText("").strip();
        return text.isEmpty() ? note(payload, "empty") : shown(text) ? new Checked(true, payload.put("answer", text)) : note(payload, "tooLong");
    }

    private static boolean shown(String text) {
        return text.codePointCount(0, text.length()) <= SHOWN_TEXT;
    }

    /** Approval needs a plan without open questions: answering them makes the agent plan again, and that plan is approved. */
    private Checked decision(Tx tx, Task task, String type, ObjectNode payload) {
        Optional<ObjectNode> plan = task.phase() == Phase.AWAITING_APPROVAL ? tasks.currentPlan(tx, task.id()) : Optional.empty();
        if (plan.isEmpty()) {
            return note(payload, "phase");
        }
        boolean open = false;
        for (JsonNode question : plan.get().withArray("questions")) {
            open |= question.path("answer").isNull();
        }
        if (type.equals("approve") && open) {
            return note(payload, "openQuestions");
        }
        return new Checked(true, payload.put("planSeq", plan.get().path("planSeq").asInt()));
    }

    /** A follow-up continues the task's building session, so the task must have got as far as execution. */
    private static Checked followUp(Tx tx, Task task, JsonNode action, ObjectNode payload) {
        String text = action.path("text").asText("").strip();
        boolean finished = task.phase() == Phase.COMPLETED || task.phase() == Phase.FAILED;
        if (!finished || !Runs.agentStartedBefore(tx, task.id(), RunKind.EXECUTE, Integer.MAX_VALUE)) {
            return note(payload, "phase");
        }
        if (text.isEmpty()) {
            return note(payload, "empty");
        }
        return shown(text) ? new Checked(true, payload.put("text", text)) : note(payload, "tooLong");
    }

    private Outcome answered(Tx tx, Requester who, JsonNode action, long taskId, int planSeq, String messageRef, String chatRef) {
        int index = action.path("question").asInt();
        // The question's own message is redrawn with the answer, as when its button is pressed.
        String questionRef = Outbox.sentQuestion(tx, taskId, planSeq, index).orElse(null);
        AnswerResult result = action.has("option")
                ? tasks.chooseOption(tx, who, taskId, planSeq, index, action.get("option").asInt(), questionRef, messageRef, chatRef)
                : tasks.answer(tx, who, taskId, planSeq, index, action.path("answer").asText(), questionRef, messageRef, chatRef);
        return switch (result) {
            case ANSWERED, PROMPTED, PROMPT_OPEN -> Outcome.DONE;
            case ALREADY_ANSWERED, STALE, EMPTY -> Outcome.STALE;
            case NOT_ALLOWED, NOT_FOUND, NOT_REQUESTER -> Outcome.NOT_ALLOWED;
        };
    }

    private static Checked note(ObjectNode payload, String reason) {
        return new Checked(false, payload.put("reason", reason));
    }
}
