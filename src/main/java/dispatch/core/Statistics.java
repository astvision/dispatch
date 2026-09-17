package dispatch.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.domain.Phase;
import dispatch.domain.RunKind;
import dispatch.domain.Task;
import dispatch.store.Runs;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The numbers /stats shows for a set of tasks and their runs (ADR 0012). */
final class Statistics {

    private Statistics() {
    }

    /**
     * Tasks by outcome, pull requests, total and average cost, median time from task to pull request, and the share of
     * tasks that reached execution with their first plan. Figures without data (no cost, no PR) are null.
     */
    static ObjectNode summary(List<Task> tasks, List<Runs.Cost> runs) {
        Map<Long, BigDecimal> costs = costsByTask(runs);
        Map<Long, Integer> plans = new HashMap<>();
        Set<Long> executed = new HashSet<>();
        for (Runs.Cost run : runs) {
            if (run.kind() == RunKind.PLAN) {
                plans.merge(run.taskId(), 1, Integer::sum);
            } else if (run.kind() == RunKind.EXECUTE) {
                executed.add(run.taskId());
            }
        }
        BigDecimal total = costs.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Long> minutesToPr = new ArrayList<>();
        for (Task task : tasks) {
            if (task.phase() == Phase.COMPLETED && task.prUrl() != null && task.completedAt() != null) {
                minutesToPr.add(Duration.between(task.createdAt(), task.completedAt()).toMinutes());
            }
        }
        long firstPlan = executed.stream().filter(id -> plans.getOrDefault(id, 0) == 1).count();
        return Json.object()
                .put("tasks", tasks.size())
                .put("completed", count(tasks, Phase.COMPLETED))
                .put("failed", count(tasks, Phase.FAILED))
                .put("rejected", count(tasks, Phase.REJECTED))
                .put("cancelled", count(tasks, Phase.CANCELLED))
                .put("active", tasks.stream().filter(task -> task.phase().isActive()).count())
                .put("pullRequests", tasks.stream().filter(task -> task.phase() == Phase.COMPLETED && task.prUrl() != null).count())
                .put("costUsd", costs.isEmpty() ? null : money(total))
                .put("averageCostUsd", costs.isEmpty() ? null : money(total.divide(BigDecimal.valueOf(costs.size()), 6, RoundingMode.HALF_UP)))
                .put("medianMinutesToPr", median(minutesToPr))
                .put("approvedWithoutCorrectionPercent", executed.isEmpty() ? null : Math.round(100.0 * firstPlan / executed.size()));
    }

    /** One line per requester: tasks given, completed, and their cost; the most active first. */
    static ArrayNode people(List<Task> tasks, List<Runs.Cost> runs) {
        Map<Long, BigDecimal> costs = costsByTask(runs);
        Map<String, List<Task>> byRequester = new LinkedHashMap<>();
        tasks.forEach(task -> byRequester.computeIfAbsent(task.requester().ref(), ref -> new ArrayList<>()).add(task));
        ArrayNode people = Json.MAPPER.createArrayNode();
        byRequester.values().stream()
                .sorted(Comparator.<List<Task>>comparingInt(List::size).reversed()
                        .thenComparing(given -> given.getLast().requester().name()))
                .forEach(given -> {
                    BigDecimal cost = given.stream().map(task -> costs.getOrDefault(task.id(), BigDecimal.ZERO))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    people.addObject().put("name", given.getLast().requester().name()).put("tasks", given.size())
                            .put("completed", count(given, Phase.COMPLETED)).put("costUsd", money(cost));
                });
        return people;
    }

    private static Map<Long, BigDecimal> costsByTask(List<Runs.Cost> runs) {
        Map<Long, BigDecimal> costs = new HashMap<>();
        runs.stream().filter(run -> run.costUsd() != null).forEach(run -> costs.merge(run.taskId(), run.costUsd(), BigDecimal::add));
        return costs;
    }

    private static long count(List<Task> tasks, Phase phase) {
        return tasks.stream().filter(task -> task.phase() == phase).count();
    }

    private static Long median(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    private static String money(BigDecimal usd) {
        return usd.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
