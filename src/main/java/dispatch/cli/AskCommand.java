package dispatch.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.AssistantHome;
import dispatch.core.Groups;
import dispatch.core.Projects;
import dispatch.core.TaskAccess;
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
 * they see and where the state file is come only from the environment, which the member's own {@code dispatch} command
 * sets ({@link AssistantHome}); the command takes no option, so the model cannot widen its own scope. The payloads are
 * {@link TaskService}'s, so someone else's task stops at its headline here too (ADR 0020).
 */
public final class AskCommand {

    static final String MEMBER = AssistantHome.MEMBER_VARIABLE;
    static final String PROJECTS = AssistantHome.PROJECTS_VARIABLE;
    static final String DATABASE = AssistantHome.DATABASE_VARIABLE;

    private AskCommand() {
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
        Optional<Long> memberId = telegramId(member);
        if (memberId.isEmpty()) {
            out.println(error("not_configured", "dispatch ask answers only inside the bot's assistant"));
            return 2;
        }
        // The member and their projects as the bot's groups have them: all task access needs to decide what they see and
        // may do. The config itself is not the assistant's to read.
        Groups asked = new Groups(List.of(new Config.Group("ask", null, List.of(new Config.Member(memberId.get(), "")),
                List.copyOf(visible))));
        TaskService tasks = new TaskService(asked, new Projects(List.of(), project -> Optional.empty()), new ActiveRuns(),
                Clock.systemUTC(), () -> { }, () -> { });
        TaskAccess.Viewer viewer = new TaskAccess.Viewer(member, visible);
        try (Database db = Database.open(Path.of(database))) {
            Optional<ObjectNode> answer = db.transactionReturning(tx -> {
                if (ask.taskId() == null) {
                    ObjectNode all = Json.object();
                    all.set("active", tasks.statusPayload(tx, viewer));
                    all.set("finished", tasks.historyPayload(tx, viewer).path("tasks"));
                    return Optional.of(all);
                }
                return tasks.timelinePayload(tx, viewer, ask.taskId()).map(task -> {
                    if (!task.path("headline").asBoolean(false)) {
                        tasks.currentPlan(tx, ask.taskId()).ifPresent(plan -> task.set("plan", plan));
                    }
                    return task;
                });
            });
            if (answer.isEmpty()) {
                out.println(error("not_found", "no task #" + ask.taskId() + " this member may see"));
                return 1;
            }
            out.println(answer.get());
            return 0;
        }
    }

    private static Optional<Long> telegramId(String ref) {
        if (!ref.startsWith("telegram:")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(ref.substring("telegram:".length())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String error(String code, String message) {
        return Json.object().put("error", code).put("message", message).toString();
    }
}
