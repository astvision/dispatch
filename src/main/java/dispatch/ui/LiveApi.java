package dispatch.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.core.Groups;
import dispatch.core.TaskAccess;
import dispatch.core.TaskService;
import dispatch.store.Database;
import dispatch.store.Runs;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** GET /api/live on the desk port (D-2): what the strip's lamps and the Overview count, in the owner's view. */
final class LiveApi {

    record Waiting(long taskId, String title) {
    }

    record Live(String version, String name, int running, int queued, List<Waiting> waitingOnYou, int waitingOnOthers,
                String todayUsd, String monthUsd) {
    }

    private final Database db;
    private final TaskService tasks;
    private final TaskAccess access;
    private final Clock clock;
    private final String version;
    private final String name;

    LiveApi(Database db, TaskService tasks, Groups groups, Clock clock, String version, String name) {
        this.db = db;
        this.tasks = tasks;
        this.access = new TaskAccess(groups);
        this.clock = clock;
        this.version = version;
        this.name = name;
    }

    Live get(UiServer.Caller caller) {
        TaskAccess.Viewer viewer = access.owner(caller.ref());
        ZonedDateTime now = clock.instant().atZone(clock.getZone());
        Instant today = now.truncatedTo(ChronoUnit.DAYS).toInstant();
        Instant month = now.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
        return db.transactionReturning(tx -> {
            ObjectNode status = tasks.statusPayload(tx, viewer);
            List<Waiting> mine = new ArrayList<>();
            int others = 0;
            for (JsonNode item : status.withArray("awaitingApproval")) {
                if (item.path("mine").asBoolean()) {
                    mine.add(new Waiting(item.path("taskId").asLong(), item.path("title").asText()));
                } else {
                    others++;
                }
            }
            return new Live(version, name, status.withArray("running").size(), status.withArray("queued").size(), mine, others,
                    usd(Runs.spentSince(tx, today)), usd(Runs.spentSince(tx, month)));
        });
    }

    private static String usd(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
