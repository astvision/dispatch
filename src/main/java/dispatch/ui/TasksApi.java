package dispatch.ui;

import dispatch.Text;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.core.ApproveResult;
import dispatch.core.CommandResult;
import dispatch.core.Groups;
import dispatch.core.Origin;
import dispatch.core.RejectResult;
import dispatch.core.TaskAccess;
import dispatch.core.TaskCommand;
import dispatch.core.TaskCommands;
import dispatch.core.TaskService;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Tx;
import dispatch.ui.UiServer.Caller;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
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

    static final Text NOT_ADMIN = Text.of("refusal.notAdminTasks");
    static final Text NOT_YOURS = Text.of("refusal.notYours");

    /** A month of an instance's finished tasks, for the desktop's list (D-2); the Mini App keeps its own ten. */
    private static final int OWNER_HISTORY = 200;

    private final Database db;
    private final TaskService tasks;
    private final TaskCommands commands;
    private final TaskAccess access;
    private final boolean owner;

    public TasksApi(Database db, TaskService tasks, Groups groups) {
        this(db, tasks, groups, false);
    }

    /**
     * @param owner the desk port's view (D-2): every task of the instance in full, acted on as the caller's own member, so
     *              ADR 0020 decides what they may do exactly as in the chat
     */
    public TasksApi(Database db, TaskService tasks, Groups groups, boolean owner) {
        this.db = db;
        this.tasks = tasks;
        this.commands = tasks.commands();
        this.access = new TaskAccess(groups);
        this.owner = owner;
    }

    private TaskAccess.Viewer viewer(Caller caller) {
        return owner ? access.owner(caller.ref()) : access.member(caller.ref());
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
                "/api/tasks/reject", this::reject,
                "/api/tasks/correct", this::correct,
                "/api/tasks/followUp", this::followUp);
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
        TaskAccess.Viewer viewer = viewer(caller);
        return db.transactionReturning(tx -> {
            ObjectNode active = tasks.statusPayload(tx, viewer);
            ObjectNode finished = owner ? tasks.historyPayload(tx, viewer, OWNER_HISTORY) : tasks.historyPayload(tx, viewer);
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
        return db.transactionReturning(tx -> tasks.timelinePayload(tx, viewer(caller), taskId))
                .orElseThrow(() -> new ApiException(404, "not_found", Text.of("refusal.noTask", taskId)));
    }

    /** {@link TaskCommand.Cancel}, exactly as /cancel in the chat; a refusal is answered here and never in the chat (ADR 0031). */
    ObjectNode cancel(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        return answered(db.transactionReturning(tx -> commands.run(tx, requester(caller), new TaskCommand.Cancel(taskId))), "CANCELLED");
    }

    /** {@link TaskCommand.Retry}: the requester alone, and only after the task failed. */
    ObjectNode retry(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        return answered(db.transactionReturning(tx -> commands.run(tx, requester(caller), new TaskCommand.Retry(taskId))), "RETRIED");
    }

    /**
     * A task command's one answer to a page (ADR 0031): {@code {"result": done}}, a new task's number, or its refusal's
     * words with the status its kind calls for. The desk's give answers through {@link #refused} too.
     */
    static ObjectNode answered(CommandResult result, String done) {
        return switch (result) {
            case CommandResult.Refused refused -> throw refused(refused);
            case CommandResult.Created created -> Json.object().put("result", "NEW_TASK").put("taskId", created.taskId());
            case CommandResult.Unchanged unchanged -> Json.object().put("result", "UNCHANGED");
            case CommandResult.Done finished -> Json.object().put("result", done);
        };
    }

    static ApiException refused(CommandResult.Refused refused) {
        int status = switch (refused.reason().kind()) {
            case INVALID -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
        };
        return new ApiException(status, refused.reason().code(), refused.words());
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
     * One answer to one question, the {@link TaskCommand.Answer} the chat's buttons give: an option's index, the requester's
     * own text, or "you decide". Questions are answered in order, as the chat asks them one at a time; the last answer
     * sends them all to the agent as one correction. Answers with the task as it now stands.
     */
    ObjectNode answer(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        int planSeq = number(body, "planSeq");
        int index = number(body, "index");
        TaskCommand.Choice choice = body.path("option").isIntegralNumber() ? new TaskCommand.Choice.Option(body.path("option").asInt())
                : body.path("decide").asBoolean(false) ? new TaskCommand.Choice.YouDecide()
                : new TaskCommand.Choice.Written(body.path("text").asText(""));
        return db.transactionReturning(tx -> {
            CommandResult result = commands.run(tx, requester(caller), new TaskCommand.Answer(taskId, planSeq, index, choice));
            if (result instanceof CommandResult.Refused refused) {
                throw refused(refused);
            }
            return ownTask(tx, caller, taskId).put("result", "ANSWERED");
        });
    }

    /** {@link TaskService#approve}, exactly as the chat's button: refused while the plan still has open questions. */
    ObjectNode approve(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        int planSeq = number(body, "planSeq");
        ApproveResult result = db.transactionReturning(tx -> tasks.approve(tx, requester(caller), taskId, planSeq));
        return switch (result) {
            case APPROVED -> Json.object().put("result", result.name());
            case OPEN_QUESTIONS -> throw new ApiException(409, "open_questions", Text.of("refusal.openQuestions"));
            case STALE_PLAN -> throw stale();
            case WRONG_STATE -> throw new ApiException(409, "wrong_state", Text.of("refusal.notWaiting"));
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
            case WRONG_STATE -> throw new ApiException(409, "wrong_state", Text.of("refusal.notWaiting"));
            case NOT_ALLOWED -> throw notMember();
            case NOT_FOUND -> throw notFound(taskId);
            case NOT_REQUESTER -> throw new ApiException(403, "not_yours", NOT_YOURS);
        };
    }

    /** {@link TaskCommand.Correct}: the requester's reply to a plan, written on the desktop (D-2) as in the chat. */
    ObjectNode correct(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        int planSeq = number(body, "planSeq");
        String text = body.path("text").asText("");
        return answered(db.transactionReturning(tx -> commands.run(tx, requester(caller),
                new TaskCommand.Correct(taskId, OptionalInt.of(planSeq), text))), "CORRECTED");
    }

    /** A merged task's follow-up becomes a new task: {"result": "NEW_TASK", "taskId"}; otherwise {"result": "QUEUED"}. */
    ObjectNode followUp(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        String text = body.path("text").asText("");
        return answered(db.transactionReturning(tx -> commands.run(tx, requester(caller),
                new TaskCommand.FollowUp(taskId, text, Origin.page()))), "QUEUED");
    }

    /** The caller's own task with its plan, what they may do with it, and the question to answer now (ADR 0027). */
    private ObjectNode ownTask(Tx tx, Caller caller, long taskId) {
        TaskAccess.Verdict verdict = access.of(tx, caller.ref(), taskId);
        // How much of it the caller sees is the viewer's question (the owner on the desktop sees it all, D-2); what they
        // may do with it stays the verdict's, as a member's in the chat.
        TaskAccess.Sight sight = verdict.task() == null ? TaskAccess.Sight.NONE : viewer(caller).sees(verdict.task());
        if (sight == TaskAccess.Sight.NONE) {
            throw notFound(taskId);
        }
        if (sight == TaskAccess.Sight.HEADLINE) {
            throw new ApiException(403, "not_yours", NOT_YOURS);
        }
        ObjectNode task = tasks.timelinePayload(tx, viewer(caller), taskId).orElseThrow(() -> notFound(taskId));
        task.remove("runs");
        tasks.currentPlan(tx, taskId).ifPresent(plan -> task.set("plan",
                plan.put("current", verdict.allows(TaskAccess.Action.ANSWER) ? verdict.currentQuestion() : 0)));
        return task;
    }

    private static ApiException stale() {
        return new ApiException(409, "stale", Text.of("refusal.stalePlan"));
    }

    private static ApiException notFound(long taskId) {
        return new ApiException(404, "not_found", Text.of("refusal.noTask", taskId));
    }

    private static ApiException notMember() {
        return new ApiException(403, "not_a_member", Text.of("refusal.notMember"));
    }

    private static int number(JsonNode body, String field) {
        if (!body.path(field).isIntegralNumber()) {
            throw new ApiException(400, "invalid", Text.of("refusal.missingField", field));
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
            throw new ApiException(400, "invalid", Text.of("refusal.missingTaskId"));
        }
        return body.path("taskId").asLong();
    }
}
