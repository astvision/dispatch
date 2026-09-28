package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.Groups;
import dispatch.core.Origin;
import dispatch.core.Projects;
import dispatch.core.RunTransitions;
import dispatch.core.TaskCommand;
import dispatch.core.TaskService;
import dispatch.domain.ClaimedRun;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.testing.TestClock;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The desk's own task routes (D-2b). */
class DeskTasksApiTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final UiServer.Caller OWNER = new UiServer.Caller(BOLD.ref(), BOLD.name(), true);

    @TempDir
    Path dir;

    private Database db;
    private TestClock clock;
    private TaskService tasks;
    private RunTransitions transitions;

    @BeforeEach
    void setUp() {
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
        // 23:30 on the 27th in Ulaanbaatar (UTC+8).
        clock = new TestClock(Instant.parse("2026-09-27T15:30:00Z"));
        Groups groups = new Groups(new Config.Telegram(List.of(100L), List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold")), List.of("alm", "life")))));
        tasks = new TaskService(groups, new Projects(List.of(project("alm", "claude-code"), project("life", "codex")),
                project -> Optional.empty()), new ActiveRuns(), clock, () -> { }, () -> { });
        transitions = new RunTransitions(db, clock, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aRunLateInTheEveningEastOfUtcCountsOnTheOwnersDayAndARunWithoutCostIsCounted() {
        planned("alm", new BigDecimal("0.10"));
        clock.advance(Duration.ofHours(1)); // 00:30 on the 28th in Ulaanbaatar, still the 27th in UTC
        planned("alm", new BigDecimal("0.20"));
        planned("life", null);              // Codex reports no cost
        startedOnly("alm");                 // still running: nothing to count yet
        DeskTasksApi api = new DeskTasksApi(db, tasks, Clock.fixed(Instant.parse("2026-09-28T02:00:00Z"), ZoneId.of("Asia/Ulaanbaatar")));

        JsonNode spend = api.spend(OWNER, Json.read("{\"days\": 2}"));

        assertEquals("2026-09-27", spend.path("days").get(0).path("day").asText());
        assertEquals("0.10", spend.path("days").get(0).path("usd").path("alm").asText());
        assertEquals("2026-09-28", spend.path("days").get(1).path("day").asText());
        assertEquals("0.20", spend.path("days").get(1).path("usd").path("alm").asText());
        JsonNode alm = spend.path("projects").get(0);
        assertEquals("alm", alm.path("project").asText());
        assertEquals("0.30", alm.path("usd").asText());
        assertEquals(2, alm.path("runs").asInt());
        JsonNode life = spend.path("projects").get(1);
        assertEquals("life", life.path("project").asText());
        assertEquals("0.00", life.path("usd").asText());
        assertEquals(1, life.path("unpriced").asInt());
        assertEquals("0.30", spend.path("totalUsd").asText());
    }

    @Test
    void theWindowIsThirtyDaysUnlessAskedAndNeverMoreThanNinety() {
        DeskTasksApi api = new DeskTasksApi(db, tasks, Clock.fixed(Instant.parse("2026-09-28T02:00:00Z"), ZoneId.of("Asia/Ulaanbaatar")));

        assertEquals(30, api.spend(OWNER, Json.read("{}")).path("days").size());
        assertEquals(90, api.spend(OWNER, Json.read("{\"days\": 400}")).path("days").size());
        assertEquals(1, api.spend(OWNER, Json.read("{\"days\": 0}")).path("days").size());
    }

    /** A task in {@code project} whose plan ran now and reported {@code cost} (null: none). */
    private void planned(String project, BigDecimal cost) {
        ClaimedRun run = started(project);
        Plan plan = new Plan("Make the timeout configurable", List.of(), List.of("Read auth.timeout"), List.of(), List.of());
        transitions.planSucceeded(run.taskId(), run.seq(), plan,
                new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", plan.toJson(), null, cost, 3, List.of(), null, null, null));
    }

    private void startedOnly(String project) {
        started(project);
    }

    private ClaimedRun started(String project) {
        Origin origin = new Origin(BOLD.ref() + "/" + System.nanoTime());
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Give(project, "Fix the login timeout", Priority.NORMAL, origin)));
        return db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
    }

    private static Config.Project project(String name, String agent) {
        return new Config.Project(name, null, "https://github.com/acme/" + name + ".git", null, "main", agent,
                null, null, List.of(), null, null, null);
    }
}
