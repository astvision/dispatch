package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentActivity;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.RunHandle;
import dispatch.config.Config;
import dispatch.domain.ClaimedRun;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What /status and /history report, read from real SQLite state and the in-memory active runs. */
class StatusAndHistoryTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");
    private static final java.util.Set<String> LIFE = java.util.Set.of("life");
    private static final String CHAT = "telegram:-100";
    private static final Plan PLAN = new Plan("Make the auth timeout configurable", List.of(), List.of("Read auth.timeout"),
            List.of(), List.of());

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TestClock clock;
    private ActiveRuns activeRuns;
    private TaskService tasks;
    private RunTransitions transitions;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
        Config.Project life = new Config.Project("life", null, "https://github.com/acme/life.git", null, "master", "claude-code", null,
                null,
                List.of(), null, null, null);
        Config.Project alm = new Config.Project("alm", null, "https://github.com/acme/alm.git", null, "main", "claude-code", null,
                null,
                List.of(), null, null, null);
        activeRuns = new ActiveRuns();
        Groups groups = new Groups(List.of(
                new Config.Group("mobile", -100L, List.of(new Config.Member(100, "Bold")), List.of("life")),
                new Config.Group("backend", -200L, List.of(new Config.Member(200, "Ali")), List.of("alm"))));
        tasks = new TaskService(groups, new Projects(List.of(life, alm), p -> Optional.empty()), activeRuns, clock, () -> { }, () -> { });
        transitions = new RunTransitions(db, clock, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void statusShowsRunningWorkWithTheAgentsLatestActionThenQueuedThenAwaitingApproval() {
        long awaiting = create("Add make help", "1");
        claim();
        transitions.planSucceeded(awaiting, 1, PLAN, result("0.16"));
        clock.advance(Duration.ofMinutes(2));
        long running = create("Fix the login timeout", "2");
        claim();
        activeRuns.register(running, 1).attach(new ActivityHandle(new AgentActivity(5, "Bash: ./gradlew test")));
        long queued = create("Rename the report", "3");
        clock.advance(Duration.ofMinutes(4));

        db.transaction(tx -> tasks.changePriority(tx, BOLD, queued, Priority.URGENT));
        db.transaction(tx -> tasks.status(tx, LIFE, null, CHAT + "/99", CHAT));

        Map<String, String> message = row("SELECT * FROM outbox WHERE kind = 'STATUS'");
        assertEquals(CHAT + "/99", message.get("reply_to_ref"));
        JsonNode payload = Json.read(message.get("payload"));
        assertEquals(1, payload.get("running").size());
        JsonNode run = payload.get("running").get(0);
        assertEquals(running, run.get("taskId").asLong());
        assertEquals("life", run.get("project").asText());
        assertEquals("Fix the login timeout", run.get("title").asText());
        assertEquals("PLAN", run.get("kind").asText());
        assertEquals("2026-09-17T10:02:00Z", run.get("startedAt").asText());
        assertEquals(5, run.get("steps").asInt());
        assertEquals("Bash: ./gradlew test", run.get("lastAction").asText());
        assertEquals(queued, payload.get("queued").get(0).get("taskId").asLong());
        assertEquals("URGENT", payload.get("queued").get(0).get("priority").asText());
        assertEquals("NORMAL", run.get("priority").asText());
        assertEquals("NORMAL", payload.get("awaitingApproval").get(0).get("priority").asText());
        assertEquals(0, payload.get("mine").size(), "no priority buttons in a group chat");
        assertEquals("2026-09-17T10:02:00Z", payload.get("queued").get(0).get("queuedAt").asText());
        JsonNode plan = payload.get("awaitingApproval").get(0);
        assertEquals(awaiting, plan.get("taskId").asLong());
        assertEquals("Bold", plan.get("requester").asText());
        assertEquals("2026-09-17T10:00:00Z", plan.get("since").asText());
    }

    @Test
    void statusWithNothingGoingOnHasEmptySections() {
        db.transaction(tx -> tasks.status(tx, LIFE, null, CHAT + "/99", CHAT));

        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'STATUS'").get("payload"));
        assertEquals(0, payload.get("running").size() + payload.get("queued").size() + payload.get("awaitingApproval").size());
    }

    @Test
    void historyListsTheTenMostRecentlyFinishedTasksNewestFirstWithTheirTotalCost() {
        for (int i = 0; i < 10; i++) {
            rejected("Old idea " + i, "2" + i);
            clock.advance(Duration.ofMinutes(1));
        }
        long completed = completed("Add make help", "40");
        create("Still planning", "41");

        db.transaction(tx -> tasks.history(tx, LIFE, CHAT + "/99", CHAT));

        JsonNode listed = Json.read(row("SELECT payload FROM outbox WHERE kind = 'HISTORY'").get("payload")).get("tasks");
        assertEquals(10, listed.size());
        JsonNode newest = listed.get(0);
        assertEquals(completed, newest.get("taskId").asLong());
        assertEquals("COMPLETED", newest.get("phase").asText());
        assertEquals("https://github.com/acme/life/pull/1", newest.get("prUrl").asText());
        assertEquals("0.42", newest.get("costUsd").asText(), "plan and execution runs together");
        assertEquals("Old idea 9", listed.get(1).get("title").asText());
        assertEquals("REJECTED", listed.get(1).get("phase").asText());
        assertEquals("Old idea 1", listed.get(9).get("title").asText(), "the oldest finished task falls off");
    }

    @Test
    void timelineListsTheRunsInOrderWithTheCorrectionAndTheOutcome() {
        long id = create("Add make help", "50");
        claim();
        clock.advance(Duration.ofSeconds(90));
        transitions.planSucceeded(id, 1, PLAN, result("0.16"));
        db.transaction(tx -> tasks.correct(tx, BOLD, id, 1, "Also describe the logs target", CHAT + "/51", CHAT));
        claim();
        transitions.planSucceeded(id, 2, PLAN, result("0.10"));
        db.transaction(tx -> tasks.approve(tx, BOLD, id, 2));
        claim();
        clock.advance(Duration.ofSeconds(62));
        transitions.completed(id, 3, result("0.26"), List.of("Makefile"), "https://github.com/acme/life/pull/1");

        db.transaction(tx -> tasks.timeline(tx, LIFE, id, CHAT + "/52", CHAT));

        Map<String, String> message = row("SELECT * FROM outbox WHERE kind = 'TASK_TIMELINE'");
        assertEquals(CHAT + "/52", message.get("reply_to_ref"));
        JsonNode payload = Json.read(message.get("payload"));
        assertEquals(id, payload.get("taskId").asLong());
        assertEquals("Add make help", payload.get("title").asText());
        assertEquals("Bold", payload.get("requester").asText());
        assertEquals("COMPLETED", payload.get("phase").asText());
        assertEquals("https://github.com/acme/life/pull/1", payload.get("prUrl").asText());
        assertEquals("0.52", payload.get("costUsd").asText());
        JsonNode runs = payload.get("runs");
        assertEquals(3, runs.size());
        assertEquals("PLAN", runs.get(0).get("kind").asText());
        assertTrue(runs.get(0).get("instruction").isNull(), "the first plan's instruction is the task itself");
        assertEquals("2026-09-17T10:00:00Z", runs.get(0).get("startedAt").asText());
        assertEquals("2026-09-17T10:01:30Z", runs.get(0).get("finishedAt").asText());
        assertEquals("Also describe the logs target", runs.get(1).get("instruction").asText());
        assertEquals("Bold", runs.get(1).get("requestedBy").asText());
        assertEquals("EXECUTE", runs.get(2).get("kind").asText());
        assertTrue(runs.get(2).get("instruction").isNull(), "an execution's instruction is the plan, shown elsewhere");
        assertEquals("SUCCEEDED", runs.get(2).get("status").asText());
        assertEquals("0.26", runs.get(2).get("costUsd").asText());
    }

    @Test
    void timelineOfAnUnknownTaskSaysItWasNotFound() {
        db.transaction(tx -> tasks.timeline(tx, LIFE, 999, CHAT + "/60", CHAT));

        Map<String, String> message = row("SELECT * FROM outbox");
        assertEquals("TASK_NOT_FOUND", message.get("kind"));
        assertEquals(CHAT + "/60", message.get("reply_to_ref"));
    }

    @Test
    void statusHistoryAndTimelineShowOnlyTheViewersProjects() {
        long other = createFor(ALI, "alm", "Backend work", "70");
        claim();
        transitions.planSucceeded(other, 1, PLAN, result("0.10"));
        db.transaction(tx -> tasks.reject(tx, ALI, other, 1));
        long mine = create("Mobile work", "71");

        db.transaction(tx -> tasks.status(tx, LIFE, null, CHAT + "/72", CHAT));
        db.transaction(tx -> tasks.history(tx, LIFE, CHAT + "/73", CHAT));
        db.transaction(tx -> tasks.timeline(tx, LIFE, other, CHAT + "/74", CHAT));

        JsonNode status = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = ?", CHAT + "/72").get("payload"));
        assertEquals(1, status.get("queued").size());
        assertEquals(mine, status.get("queued").get(0).get("taskId").asLong());
        JsonNode history = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = ?", CHAT + "/73").get("payload"));
        assertEquals(0, history.get("tasks").size(), "the rejected backend task is not the viewer's to see");
        assertEquals("TASK_NOT_FOUND", row("SELECT kind FROM outbox WHERE reply_to_ref = ?", CHAT + "/74").get("kind"));
    }

    @Test
    void privateStatusOffersPriorityChangesForTheViewersOwnActiveTasks() {
        long mine = create("Mobile work", "80");
        long awaiting = create("Other mobile work", "81");
        claim();
        transitions.planSucceeded(mine, 1, PLAN, result("0.1"));

        db.transaction(tx -> tasks.status(tx, LIFE, BOLD.ref(), "telegram:100/82", "telegram:100"));

        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'STATUS'").get("payload"));
        assertEquals(2, payload.get("mine").size());
        assertEquals(mine, payload.get("mine").get(0).get("taskId").asLong());
        assertEquals("NORMAL", payload.get("mine").get(0).get("priority").asText());
        assertEquals(awaiting, payload.get("mine").get(1).get("taskId").asLong());
    }

    @Test
    void historyShowsWhoGaveEachTaskWhenAndHowUrgent() {
        clock.advance(Duration.ofMinutes(5));
        long id = completed("Add make help", "83");
        db.transaction(tx -> tx.update("UPDATE task SET priority = 'LOW' WHERE id = ?", id));

        db.transaction(tx -> tasks.history(tx, LIFE, CHAT + "/84", CHAT));

        JsonNode listed = Json.read(row("SELECT payload FROM outbox WHERE kind = 'HISTORY'").get("payload")).get("tasks").get(0);
        assertEquals("LOW", listed.get("priority").asText());
        assertEquals("Bold", listed.get("requester").asText());
        assertEquals("2026-09-17T10:05:00Z", listed.get("createdAt").asText());
    }

    private long createFor(Requester who, String project, String text, String messageId) {
        db.transaction(tx -> tasks.create(tx, who, project, text, Priority.NORMAL, who.ref() + "/" + messageId));
        return Long.parseLong(row("SELECT id FROM task WHERE origin_ref = ?", who.ref() + "/" + messageId).get("id"));
    }

    private long create(String text, String messageId) {
        return createFor(BOLD, "life", text, messageId);
    }

    private ClaimedRun claim() {
        return db.transactionReturning(tx -> Runs.claimNext(tx, 10, clock.instant())).orElseThrow();
    }

    private void rejected(String text, String messageId) {
        long id = create(text, messageId);
        claim();
        transitions.planSucceeded(id, 1, PLAN, result("0.05"));
        db.transaction(tx -> tasks.reject(tx, BOLD, id, 1));
    }

    private long completed(String text, String messageId) {
        long id = create(text, messageId);
        claim();
        transitions.planSucceeded(id, 1, PLAN, result("0.16"));
        db.transaction(tx -> tasks.approve(tx, BOLD, id, 1));
        claim();
        transitions.completed(id, 2, result("0.26"), List.of("Makefile"), "https://github.com/acme/life/pull/1");
        return id;
    }

    private static AgentResult result(String costUsd) {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", PLAN.toJson(), "Done.", new BigDecimal(costUsd), 4,
                List.of(), null);
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }

    private record ActivityHandle(AgentActivity activity) implements RunHandle {

        @Override
        public ProcessHandle process() {
            return ProcessHandle.current();
        }

        @Override
        public AgentResult await() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public void cancel() {
        }
    }
}
