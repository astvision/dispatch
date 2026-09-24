package dispatch.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.core.AnswerResult;
import dispatch.core.ApproveResult;
import dispatch.core.CancelResult;
import dispatch.core.Groups;
import dispatch.core.RejectResult;
import dispatch.core.RetryResult;
import dispatch.core.TaskService;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Outbox;
import dispatch.store.Tx;
import dispatch.telegram.Renderer;
import dispatch.ui.UiServer.Caller;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * The Mini App's task pages (spec: Task pages). Only the running bot holds the queue, so these are served by
 * {@code dispatch run} and by nothing else.
 *
 * <p>Two scopes, one list: "me" is a member's own tasks, and "group" is every task of an admin's groups, where
 * someone else's shows its headline alone (ADR 0020). Both read the payloads {@link TaskService} builds for Telegram,
 * so the rule about what another member may see lives in one place rather than two.
 */
public final class TasksApi {

    static final String NOT_ADMIN = "only an admin may see the whole group's tasks";
    static final String NOT_YOURS = "only the member who gave this task may see its plan and decide on it";
    /** What the chat's "you decide" button answers, so the agent reads the same words from either place. */
    private static final String YOU_DECIDE = Renderer.mongolian().getString("plan.youDecide");

    private final Database db;
    private final TaskService tasks;
    private final Groups groups;

    public TasksApi(Database db, TaskService tasks, Groups groups) {
        this.db = db;
        this.tasks = tasks;
        this.groups = groups;
    }

    public Map<String, BiFunction<Caller, JsonNode, Object>> routes() {
        return Map.of(
                "/api/tasks/list", this::list,
                "/api/tasks/timeline", this::timeline,
                "/api/tasks/cancel", this::cancel,
                "/api/tasks/retry", this::retry,
                "/api/tasks/detail", this::detail,
                "/api/tasks/answer", this::answer,
                "/api/tasks/approve", this::approve,
                "/api/tasks/reject", this::reject);
    }

    /**
     * What is running, queued, waiting for approval and finished. In "me" scope the caller's own tasks are kept and
     * nobody else's is listed at all — My tasks is theirs, not a page of teammates' headlines.
     */
    ObjectNode list(Caller caller, JsonNode body) {
        boolean wholeGroup = body.path("scope").asText("me").equals("group");
        if (wholeGroup && !caller.admin()) {
            throw new ApiException(403, "not_admin", NOT_ADMIN);
        }
        Set<String> visible = groups.projectsOfMember(caller.ref());
        return db.transactionReturning(tx -> {
            ObjectNode active = tasks.statusPayload(tx, visible, caller.ref());
            ObjectNode finished = tasks.historyPayload(tx, visible, caller.ref());
            ObjectNode answer = Json.object();
            ArrayNode listed = answer.putArray("tasks");
            for (ObjectNode task : merge(active, finished)) {
                if (wholeGroup || task.path("mine").asBoolean(false)) {
                    listed.add(task);
                }
            }
            return answer;
        });
    }

    ObjectNode timeline(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        Set<String> visible = groups.projectsOfMember(caller.ref());
        return db.transactionReturning(tx -> tasks.timelinePayload(tx, visible, caller.ref(), taskId))
                .orElseThrow(() -> new ApiException(404, "not_found", "no task #" + taskId + " here"));
    }

    /** The requester or an admin; the rules are {@link TaskService}'s, exactly as for /cancel in the chat. */
    ObjectNode cancel(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        CancelResult result = db.transactionReturning(tx ->
                tasks.cancel(tx, requester(caller), taskId, null, caller.ref()));
        return switch (result) {
            case CANCELLED -> Json.object().put("result", result.name());
            case NOT_FOUND -> throw new ApiException(404, "not_found", "no task #" + taskId + " here");
            case REFUSED -> throw new ApiException(403, "not_yours", "only the member who gave this task, or an admin, may cancel it");
            case NOT_ALLOWED -> throw new ApiException(403, "not_a_member", "you are not in a group of this Dispatch");
        };
    }

    /**
     * The requester alone: an admin may stop someone's task but never start it again for them. {@code REFUSED} covers
     * both "not yours" and "it has not failed", and {@link TaskService} does not say which, so the message names both
     * rather than telling a requester something untrue about their own task.
     */
    ObjectNode retry(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        RetryResult result = db.transactionReturning(tx ->
                tasks.retry(tx, requester(caller), taskId, null, caller.ref()));
        return switch (result) {
            case RETRIED -> Json.object().put("result", result.name());
            case NOT_FOUND -> throw new ApiException(404, "not_found", "no task #" + taskId + " here");
            case REFUSED -> throw new ApiException(403, "cannot_retry",
                    "this task cannot be retried: only the member who gave it may retry it, and only after it failed");
            case NOT_ALLOWED -> throw new ApiException(403, "not_a_member", "you are not in a group of this Dispatch");
        };
    }

    /**
     * The requester's own task with its latest plan, questions and the answers given so far: what the Mini App's task sheet
     * shows. Someone else's task is refused rather than cut to a headline, because a plan is its requester's alone.
     */
    ObjectNode detail(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        return db.transactionReturning(tx -> ownTask(tx, caller, taskId));
    }

    /**
     * One answer to one question, by the same {@link TaskService#answer} the chat's buttons use: an option's index, the
     * requester's own text, or "you decide". Questions are answered in order, as the chat asks them one at a time; the last
     * answer sends them all to the agent as one correction. Answers with the task as it now stands.
     */
    ObjectNode answer(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        int planSeq = number(body, "planSeq");
        int index = number(body, "index");
        return db.transactionReturning(tx -> {
            ObjectNode task = ownTask(tx, caller, taskId);
            JsonNode plan = task.path("plan");
            if (plan.path("planSeq").asInt() != planSeq || !task.path("phase").asText().equals("AWAITING_APPROVAL")) {
                throw stale();
            }
            if (index != firstUnanswered(plan)) {
                throw new ApiException(409, "out_of_order", "answer question " + firstUnanswered(plan) + " first; they go in order");
            }
            String questionRef = Outbox.sentQuestion(tx, taskId, planSeq, index).orElse(null);
            String chatRef = caller.ref();
            AnswerResult result;
            if (body.path("option").isIntegralNumber()) {
                result = tasks.chooseOption(tx, requester(caller), taskId, planSeq, index, body.path("option").asInt(), questionRef,
                        questionRef, chatRef);
            } else if (body.path("decide").asBoolean(false)) {
                result = tasks.answer(tx, requester(caller), taskId, planSeq, index, YOU_DECIDE, questionRef, questionRef, chatRef);
            } else {
                result = tasks.answer(tx, requester(caller), taskId, planSeq, index, body.path("text").asText(""), questionRef,
                        questionRef, chatRef);
            }
            return switch (result) {
                case ANSWERED -> ownTask(tx, caller, taskId).put("result", result.name());
                case EMPTY -> throw new ApiException(400, "invalid", "the answer is empty, or that option is not one of the question's");
                case ALREADY_ANSWERED -> throw new ApiException(409, "already_answered", "this question already has its answer");
                case STALE -> throw stale();
                case NOT_ALLOWED -> throw notMember();
                case NOT_FOUND -> throw notFound(taskId);
                case NOT_REQUESTER -> throw new ApiException(403, "not_yours", NOT_YOURS);
                case PROMPTED, PROMPT_OPEN -> throw new IllegalStateException("answering never prompts: " + result);
            };
        });
    }

    /** {@link TaskService#approve}, exactly as the chat's button: refused while the plan still has open questions. */
    ObjectNode approve(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        int planSeq = number(body, "planSeq");
        ApproveResult result = db.transactionReturning(tx -> tasks.approve(tx, requester(caller), taskId, planSeq));
        return switch (result) {
            case APPROVED -> Json.object().put("result", result.name());
            case OPEN_QUESTIONS -> throw new ApiException(409, "open_questions",
                    "this plan still has open questions: answer them, and the agent plans again with the answers");
            case STALE_PLAN -> throw stale();
            case WRONG_STATE -> throw new ApiException(409, "wrong_state", "this task is not waiting for a decision any more");
            case NOT_ALLOWED -> throw notMember();
            case NOT_FOUND -> throw notFound(taskId);
            case NOT_REQUESTER -> throw new ApiException(403, "not_yours", NOT_YOURS);
        };
    }

    /** {@link TaskService#reject}, exactly as the chat's button. */
    ObjectNode reject(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        int planSeq = number(body, "planSeq");
        RejectResult result = db.transactionReturning(tx -> tasks.reject(tx, requester(caller), taskId, planSeq));
        return switch (result) {
            case REJECTED -> Json.object().put("result", result.name());
            case STALE_PLAN -> throw stale();
            case WRONG_STATE -> throw new ApiException(409, "wrong_state", "this task is not waiting for a decision any more");
            case NOT_ALLOWED -> throw notMember();
            case NOT_FOUND -> throw notFound(taskId);
            case NOT_REQUESTER -> throw new ApiException(403, "not_yours", NOT_YOURS);
        };
    }

    /** The caller's own task with its plan; a task outside their groups is not found, so its existence does not leak. */
    private ObjectNode ownTask(Tx tx, Caller caller, long taskId) {
        Set<String> visible = groups.projectsOfMember(caller.ref());
        ObjectNode task = tasks.timelinePayload(tx, visible, caller.ref(), taskId).orElseThrow(() -> notFound(taskId));
        if (task.path("headline").asBoolean(false)) {
            throw new ApiException(403, "not_yours", NOT_YOURS);
        }
        task.remove("runs");
        tasks.currentPlan(tx, taskId).ifPresent(plan -> task.set("plan", plan));
        return task;
    }

    private static int firstUnanswered(JsonNode plan) {
        for (JsonNode question : plan.path("questions")) {
            if (question.path("answer").isNull() || question.path("answer").isMissingNode()) {
                return question.path("index").asInt();
            }
        }
        return 0;
    }

    private static ApiException stale() {
        return new ApiException(409, "stale", "this plan was replaced by a newer one, or no longer waits for you; reload the task");
    }

    private static ApiException notFound(long taskId) {
        return new ApiException(404, "not_found", "no task #" + taskId + " here");
    }

    private static ApiException notMember() {
        return new ApiException(403, "not_a_member", "you are not in a group of this Dispatch");
    }

    private static int number(JsonNode body, String field) {
        if (!body.path(field).isIntegralNumber()) {
            throw new ApiException(400, "invalid", field + " is missing");
        }
        return body.path(field).asInt();
    }

    /** Running and queued runs, plans awaiting approval, then finished tasks: one list, newest work first. */
    private static List<ObjectNode> merge(ObjectNode active, ObjectNode finished) {
        List<ObjectNode> all = new ArrayList<>();
        for (String group : List.of("running", "queued", "awaitingApproval")) {
            active.withArray(group).forEach(item -> all.add(((ObjectNode) item).put("state", group)));
        }
        finished.withArray("tasks").forEach(item -> all.add(((ObjectNode) item).put("state", "finished")));
        return all;
    }

    private static Requester requester(Caller caller) {
        return new Requester(caller.ref(), caller.name());
    }

    private static long taskId(JsonNode body) {
        if (!body.path("taskId").isIntegralNumber()) {
            throw new ApiException(400, "invalid", "which task? taskId is missing");
        }
        return body.path("taskId").asLong();
    }
}
