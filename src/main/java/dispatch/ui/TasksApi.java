package dispatch.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.core.CancelResult;
import dispatch.core.Groups;
import dispatch.core.RetryResult;
import dispatch.core.TaskService;
import dispatch.domain.Requester;
import dispatch.store.Database;
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
                "/api/tasks/retry", this::retry);
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
