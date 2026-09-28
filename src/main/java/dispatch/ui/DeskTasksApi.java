package dispatch.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Text;
import dispatch.core.TaskService;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.ui.UiServer.Caller;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;

/** The desk's own task routes, which the Mini App does not have (D-2b): the spend per day, and giving a task. */
final class DeskTasksApi {

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 90;

    private final Database db;
    private final TaskService tasks;
    private final Clock clock;

    DeskTasksApi(Database db, TaskService tasks, Clock clock) {
        this.db = db;
        this.tasks = tasks;
        this.clock = clock;
    }

    Map<String, BiFunction<Caller, JsonNode, Object>> routes() {
        return Map.of("/api/tasks/spend", this::spend, "/api/tasks/new", this::give);
    }

    /**
     * {"days": 30} → the cost per day and project, each run by the day it started in the bot's time zone, the whole
     * instance's (the desk is the owner's). A project on Codex or Gemini CLI reports no cost: its runs are counted.
     */
    ObjectNode spend(Caller caller, JsonNode body) {
        int days = Math.clamp(body.path("days").asInt(DEFAULT_DAYS), 1, MAX_DAYS);
        ZoneId zone = clock.getZone();
        LocalDate last = LocalDate.now(clock);
        LocalDate first = last.minusDays(days - 1);
        List<Runs.Started> runs = db.transactionReturning(tx -> Runs.startedSince(tx, first.atStartOfDay(zone).toInstant()));
        Map<LocalDate, Map<String, BigDecimal>> perDay = new TreeMap<>();
        Map<String, BigDecimal> perProject = new TreeMap<>();
        Map<String, Integer> runsOf = new TreeMap<>();
        Map<String, Integer> unpricedOf = new TreeMap<>();
        for (Runs.Started run : runs) {
            runsOf.merge(run.project(), 1, Integer::sum);
            if (run.costUsd() == null) {
                unpricedOf.merge(run.project(), 1, Integer::sum);
                continue;
            }
            perDay.computeIfAbsent(LocalDate.ofInstant(run.startedAt(), zone), day -> new TreeMap<>())
                    .merge(run.project(), run.costUsd(), BigDecimal::add);
            perProject.merge(run.project(), run.costUsd(), BigDecimal::add);
        }
        ObjectNode answer = Json.object().put("from", first.toString()).put("to", last.toString());
        ArrayNode dayList = answer.putArray("days");
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            ObjectNode usd = dayList.addObject().put("day", day.toString()).putObject("usd");
            perDay.getOrDefault(day, Map.of()).forEach((project, amount) -> usd.put(project, usd(amount)));
        }
        ArrayNode projectList = answer.putArray("projects");
        Comparator<String> costliest = Comparator.comparing((String project) -> perProject.getOrDefault(project, BigDecimal.ZERO))
                .reversed().thenComparing(Comparator.naturalOrder());
        runsOf.keySet().stream().sorted(costliest).forEach(project -> projectList.addObject().put("project", project)
                .put("usd", usd(perProject.getOrDefault(project, BigDecimal.ZERO)))
                .put("runs", runsOf.get(project)).put("unpriced", unpricedOf.getOrDefault(project, 0)));
        answer.put("totalUsd", usd(perProject.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
        return answer;
    }

    /**
     * {"project", "text", "priority"} → {"taskId"}: a task of the acting admin's own. A refusal is answered here only
     * ({@link TaskService#give} writes nothing for one), so the chat hears of the tasks the desk gave, not of its typos.
     */
    ObjectNode give(Caller caller, JsonNode body) {
        String project = body.path("project").asText("");
        Priority priority = priority(body.path("priority").asText("NORMAL"));
        TaskService.Given given = db.transactionReturning(tx ->
                tasks.give(tx, new Requester(caller.ref(), caller.name()), project, body.path("text").asText(""), priority));
        return switch (given.result()) {
            case CREATED -> Json.object().put("taskId", given.taskId());
            case EMPTY -> throw new ApiException(400, "empty", Text.of("refusal.taskEmpty"));
            case UNKNOWN_PROJECT -> throw new ApiException(404, "unknown_project", Text.of("refusal.projectNotYours", project));
            case PROJECT_UNAVAILABLE -> throw new ApiException(409, "project_unavailable",
                    Text.of("refusal.projectUnavailable", project, given.reason()));
            case NOT_ALLOWED -> throw new ApiException(403, "not_a_member", Text.of("refusal.notMember"));
            case DUPLICATE -> throw new IllegalStateException("a desk origin is new every time, yet the task came back a duplicate");
        };
    }

    private static Priority priority(String given) {
        try {
            return Priority.valueOf(given);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "bad_priority", Text.of("refusal.badPriority", given));
        }
    }

    private static String usd(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
