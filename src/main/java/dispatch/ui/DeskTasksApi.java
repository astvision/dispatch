package dispatch.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.core.TaskService;
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
        return Map.of("/api/tasks/spend", this::spend);
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

    private static String usd(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
