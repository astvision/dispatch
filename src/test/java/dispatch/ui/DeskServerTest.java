package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.Groups;
import dispatch.core.Projects;
import dispatch.core.RunTransitions;
import dispatch.core.TaskService;
import dispatch.domain.ClaimedRun;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.testing.TestClock;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The running bot's desk port, over real HTTP (D-2). */
class DeskServerTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private Path state;
    private Database db;
    private TestClock clock;
    private TaskService tasks;
    private RunTransitions transitions;
    private DeskServer desk;

    @BeforeEach
    void setUp() throws IOException {
        state = dir.resolve("state");
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-28T10:00:00Z"));
        Config.Project alm = new Config.Project("alm", null, "https://github.com/acme/alm.git", null, "main", "claude-code",
                null, null, List.of(), null, null, null);
        // Bold is the only admin, so the desk acts as Bold without being told.
        Groups groups = new Groups(new Config.Telegram(List.of(100L), List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm")))));
        tasks = new TaskService(groups, new Projects(List.of(alm), project -> Optional.empty()), new ActiveRuns(), clock,
                () -> { }, () -> { });
        transitions = new RunTransitions(db, clock, () -> { });
        desk = DeskServer.start(state, db, tasks, groups, clock, "0.3.0", "acme");
    }

    @AfterEach
    void tearDown() {
        desk.close();
        db.close();
    }

    @Test
    void theFileNamesThePortAndACallWithoutTheTokenIsRefused() throws Exception {
        DeskFile file = DeskFile.read(state).orElseThrow();
        HttpResponse<String> refused = http.send(HttpRequest.newBuilder(uri("/api/live")).build(), BodyHandlers.ofString());

        assertEquals(desk.port(), file.port());
        assertEquals(401, refused.statusCode());
    }

    @Test
    void theLiveSummaryCountsWhatWaitsOnTheOwnerAndTodaysSpend() throws Exception {
        planned(ALI);
        planned(BOLD);

        JsonNode live = Json.read(get("/api/live").body());

        assertEquals(1, live.path("waitingOnYou").size(), live.toString());
        assertEquals(1, live.path("waitingOnOthers").asInt());
        assertEquals("0.20", live.path("todayUsd").asText());
        assertEquals("0.20", live.path("monthUsd").asText());
        assertEquals("acme", live.path("name").asText());
        assertEquals("0.3.0", live.path("version").asText());
    }

    @Test
    void theTasksAreServedInTheOwnersView() throws Exception {
        long alis = planned(ALI);

        JsonNode listed = Json.read(post("/api/tasks/list", "{\"scope\":\"group\"}").body());

        assertEquals(alis, listed.path("tasks").get(0).path("taskId").asLong());
        assertEquals(false, listed.path("tasks").get(0).path("mine").asBoolean());
    }

    @Test
    void closingRemovesTheFile() {
        desk.close();

        assertEquals(Optional.empty(), DeskFile.read(state));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(authorized(path).GET().build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(authorized(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), BodyHandlers.ofString());
    }

    private HttpRequest.Builder authorized(String path) {
        return HttpRequest.newBuilder(uri(path)).header("Authorization", "desk " + DeskFile.read(state).orElseThrow().token());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + desk.port() + path);
    }

    /** A task of {@code who} whose first plan is ready, asking nothing, and which cost 0.1. */
    private long planned(Requester who) {
        String origin = who.ref() + "/" + System.nanoTime();
        db.transaction(tx -> tasks.create(tx, who, "alm", "Fix the login timeout", Priority.NORMAL, origin));
        ClaimedRun run = db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        Plan plan = new Plan("Make the timeout configurable", List.of(), List.of("Read auth.timeout"), List.of(), List.of());
        transitions.planSucceeded(run.taskId(), run.seq(), plan,
                new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", plan.toJson(), null, new BigDecimal("0.1"), 3, List.of(), null, null, null));
        return run.taskId();
    }
}
