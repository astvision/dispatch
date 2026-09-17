package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
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
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** /stats numbers from real transitions: outcomes, pull requests, cost, time to PR and first-time approvals. */
class StatsTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");
    private static final Requester SARA = new Requester("telegram:300", "Sara");
    private static final Plan PLAN = new Plan("Do it", List.of(), List.of("Change it"), List.of(), List.of());
    private static final List<String> BOLDS_GROUPS = List.of("backend", "mobile");

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TestClock clock;
    private TaskService tasks;
    private RunTransitions transitions;
    private int messages;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
        Projects projects = new Projects(List.of(project("alm"), project("life")), project -> Optional.empty());
        Groups groups = new Groups(List.of(
                new Config.Group("backend", -100, List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm")),
                new Config.Group("mobile", -300, List.of(new Config.Member(100, "Bold"), new Config.Member(300, "Sara")), List.of("life"))));
        tasks = new TaskService(groups, projects, new ActiveRuns(), clock, () -> { }, () -> { });
        transitions = new RunTransitions(db, clock, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void myStatsCountOutcomesPullRequestsCostTimeToPrAndPlansApprovedWithoutCorrection() {
        delivered(BOLD, "alm", Duration.ofMinutes(20), false, "0.10", "0.30");
        delivered(BOLD, "life", Duration.ofMinutes(40), true, "0.10", "0.25");
        rejected(BOLD, "alm", "0.05");
        failedExecution(BOLD, "alm", "0.10", "0.20");
        delivered(ALI, "alm", Duration.ofMinutes(5), false, "1.00", "1.00");

        JsonNode stats = payload(BOLD.ref(), BOLDS_GROUPS, "me", "month");

        assertEquals("me", stats.get("view").asText());
        assertEquals("month", stats.get("period").asText());
        JsonNode summary = stats.get("summary");
        assertEquals(4, summary.get("tasks").asInt());
        assertEquals(2, summary.get("completed").asInt());
        assertEquals(1, summary.get("failed").asInt());
        assertEquals(1, summary.get("rejected").asInt());
        assertEquals(0, summary.get("cancelled").asInt());
        assertEquals(0, summary.get("active").asInt());
        assertEquals(2, summary.get("pullRequests").asInt());
        assertEquals("1.15", summary.get("costUsd").asText());
        assertEquals("0.29", summary.get("averageCostUsd").asText());
        assertEquals(30, summary.get("medianMinutesToPr").asLong());
        assertEquals(67, summary.get("approvedWithoutCorrectionPercent").asInt(), "2 of the 3 tasks that reached execution");
        assertEquals(List.of("backend", "mobile"), List.of(stats.get("groups").get(0).asText(), stats.get("groups").get(1).asText()));
        assertTrue(stats.get("canViewMe").asBoolean());
    }

    @Test
    void periodCountsTasksGivenWithinIt() {
        rejected(BOLD, "alm", "0.05");
        clock.advance(Duration.ofDays(10));
        rejected(BOLD, "alm", "0.05");
        clock.advance(Duration.ofDays(5));

        assertEquals(1, payload(BOLD.ref(), BOLDS_GROUPS, "me", "week").get("summary").get("tasks").asInt(), "given 27 Sep, now 2 Oct");
        assertEquals(0, payload(BOLD.ref(), BOLDS_GROUPS, "me", "month").get("summary").get("tasks").asInt(), "October so far");
        assertEquals(2, payload(BOLD.ref(), BOLDS_GROUPS, "me", "all").get("summary").get("tasks").asInt());
    }

    @Test
    void groupViewCoversOnlyThatGroupsProjectsAndOnlyForItsMembers() {
        rejected(BOLD, "alm", "0.05");
        rejected(SARA, "life", "0.05");
        rejected(ALI, "alm", "0.05");

        assertEquals(2, payload(BOLD.ref(), BOLDS_GROUPS, "group:backend", "all").get("summary").get("tasks").asInt());
        assertEquals(1, payload(BOLD.ref(), BOLDS_GROUPS, "group:mobile", "all").get("summary").get("tasks").asInt());
        assertTrue(db.transactionReturning(tx -> tasks.statsPayload(tx, SARA.ref(), List.of("mobile"), "group:backend", "all")).isEmpty(),
                "Sara is not in backend");
        assertTrue(db.transactionReturning(tx -> tasks.statsPayload(tx, null, List.of("backend"), "me", "all")).isEmpty(),
                "a group chat has no 'me'");
    }

    @Test
    void peopleViewListsEveryRequesterOfTheViewersGroupsMostActiveFirst() {
        delivered(ALI, "alm", Duration.ofMinutes(5), false, "0.20", "0.30");
        rejected(SARA, "life", "0.05");
        rejected(SARA, "life", "0.05");

        JsonNode people = payload(BOLD.ref(), BOLDS_GROUPS, "people", "all").get("people");

        assertEquals(2, people.size());
        assertEquals("Sara", people.get(0).get("name").asText());
        assertEquals(2, people.get(0).get("tasks").asInt());
        assertEquals(0, people.get(0).get("completed").asInt());
        assertEquals("0.10", people.get(0).get("costUsd").asText());
        assertEquals("Ali", people.get(1).get("name").asText());
        assertEquals(1, people.get(1).get("completed").asInt());
    }

    @Test
    void statsCommandPostsMyMonthPrivatelyAndTheGroupsMonthInAGroup() {
        db.transaction(tx -> tasks.stats(tx, BOLD.ref(), BOLDS_GROUPS, "telegram:100/1", "telegram:100"));
        db.transaction(tx -> tasks.stats(tx, null, List.of("backend"), "telegram:-100/2", "telegram:-100"));

        JsonNode mine = Json.read(SqlRows.single(dbFile, "SELECT payload FROM outbox WHERE reply_to_ref = 'telegram:100/1'").get("payload"));
        assertEquals("me", mine.get("view").asText());
        assertEquals("month", mine.get("period").asText());
        JsonNode group = Json.read(SqlRows.single(dbFile, "SELECT payload FROM outbox WHERE reply_to_ref = 'telegram:-100/2'").get("payload"));
        assertEquals("group:backend", group.get("view").asText());
        assertEquals(false, group.get("canViewMe").asBoolean());
    }

    private JsonNode payload(String viewerRef, List<String> groupNames, String view, String period) {
        return db.transactionReturning(tx -> tasks.statsPayload(tx, viewerRef, groupNames, view, period)).orElseThrow();
    }

    private long give(Requester who, String project) {
        String origin = who.ref() + "/" + ++messages;
        db.transaction(tx -> tasks.create(tx, who, project, "Task " + messages, Priority.NORMAL, origin));
        return Long.parseLong(SqlRows.single(dbFile, "SELECT id FROM task WHERE origin_ref = ?", origin).get("id"));
    }

    private void planned(long id, int seq, String cost) {
        ClaimedRun run = claim();
        assertEquals(id, run.taskId());
        transitions.planSucceeded(id, seq, PLAN, result(cost));
    }

    private void delivered(Requester who, String project, Duration took, boolean corrected, String planCost, String executionCost) {
        Instant start = clock.instant();
        long id = give(who, project);
        planned(id, 1, planCost);
        int seq = 1;
        if (corrected) {
            db.transaction(tx -> tasks.correct(tx, who, id, 1, "Also this", who.ref() + "/c" + id, who.ref()));
            planned(id, ++seq, "0.05");
        }
        int planSeq = seq;
        db.transaction(tx -> tasks.approve(tx, who, id, planSeq));
        claim();
        clock.advance(took.minus(Duration.between(start, clock.instant())));
        transitions.completed(id, seq + 1, result(executionCost), List.of("README.md"), "https://github.com/acme/" + project + "/pull/" + id);
    }

    private void rejected(Requester who, String project, String planCost) {
        long id = give(who, project);
        planned(id, 1, planCost);
        db.transaction(tx -> tasks.reject(tx, who, id, 1));
    }

    private void failedExecution(Requester who, String project, String planCost, String executionCost) {
        long id = give(who, project);
        planned(id, 1, planCost);
        db.transaction(tx -> tasks.approve(tx, who, id, 1));
        claim();
        transitions.failed(id, 2, FailureReason.AGENT, "boom", result(executionCost));
    }

    private ClaimedRun claim() {
        return db.transactionReturning(tx -> Runs.claimNext(tx, 10, clock.instant())).orElseThrow();
    }

    private static AgentResult result(String costUsd) {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", PLAN.toJson(), "Done.", new BigDecimal(costUsd), 3, List.of(), null);
    }

    private static Config.Project project(String name) {
        return new Config.Project(name, null, "https://github.com/acme/" + name + ".git", null, "main", "claude-code", null, null, List.of(), null);
    }
}
