package dispatch.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.core.ActiveRuns;
import dispatch.core.Groups;
import dispatch.core.Projects;
import dispatch.core.TaskService;
import dispatch.store.Database;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code dispatch ask}: what the bot's assistant reads about the member it talks to (A-1). Who is asking, which projects
 * they see and where the state file is come only from the environment the bot starts the assistant with: the one Bash
 * command the assistant may run takes no option, so the model cannot widen its own scope. The payloads are
 * {@link TaskService}'s, so someone else's task stops at its headline here too (ADR 0020).
 */
public final class AskCommand {

    static final String MEMBER = "DISPATCH_ASK_MEMBER";
    static final String PROJECTS = "DISPATCH_ASK_PROJECTS";
    static final String DATABASE = "DISPATCH_ASK_DB";

    private AskCommand() {
    }

    /** The environment the bot gives its assistant, so {@code dispatch ask} answers for {@code memberRef} alone. */
    public static Map<String, String> environment(String memberRef, Set<String> visibleProjects, Path database) {
        return Map.of(MEMBER, memberRef, PROJECTS, String.join(",", visibleProjects), DATABASE, database.toString());
    }

    /** Prints one JSON document; exit code 0 with the answer, 1 for a task not found, 2 outside the assistant. */
    public static int run(Cli.Ask ask, Map<String, String> env, PrintStream out) {
        String member = env.get(MEMBER);
        String database = env.get(DATABASE);
        if (member == null || member.isBlank() || database == null || !Files.isRegularFile(Path.of(database))) {
            out.println(error("not_configured", "dispatch ask answers only inside the bot's assistant"));
            return 2;
        }
        Set<String> visible = Arrays.stream(env.getOrDefault(PROJECTS, "").split(","))
                .map(String::strip).filter(name -> !name.isEmpty()).collect(Collectors.toSet());
        // Only the read-only payloads are used, and none of them looks at groups or projects: those come from the environment.
        TaskService tasks = new TaskService(new Groups(List.of()), new Projects(List.of(), project -> Optional.empty()), new ActiveRuns(),
                Clock.systemUTC(), () -> { }, () -> { });
        try (Database db = Database.open(Path.of(database))) {
            Optional<ObjectNode> answer = db.transactionReturning(tx -> {
                if (ask.taskId() == null) {
                    ObjectNode all = Json.object();
                    all.set("active", tasks.statusPayload(tx, visible, member));
                    all.set("finished", tasks.historyPayload(tx, visible, member).path("tasks"));
                    return Optional.of(all);
                }
                return tasks.timelinePayload(tx, visible, member, ask.taskId()).map(task -> {
                    if (!task.path("headline").asBoolean(false)) {
                        tasks.currentPlan(tx, ask.taskId()).ifPresent(plan -> task.set("plan", plan));
                    }
                    return task;
                });
            });
            if (answer.isEmpty()) {
                out.println(error("not_found", "no task #" + ask.taskId() + " among this member's projects"));
                return 1;
            }
            out.println(answer.get());
            return 0;
        }
    }

    private static String error(String code, String message) {
        return Json.object().put("error", code).put("message", message).toString();
    }
}
