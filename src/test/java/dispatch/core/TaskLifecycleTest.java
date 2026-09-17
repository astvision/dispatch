package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.Plan;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskLifecycleTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");
    private static final Requester STRANGER = new Requester("telegram:999", "Sara");
    private static final String CHAT = "telegram:-100";
    private static final Plan PLAN = new Plan("Make the auth timeout configurable", List.of("AuthClient.java:14 hard-codes 30s"),
            List.of("Read auth.timeout", "Add AuthClientTimeoutTest"), List.of(), List.of());

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TestClock clock;
    private ActiveRuns activeRuns;
    private final AtomicInteger schedulerWakes = new AtomicInteger();
    private final AtomicInteger outboxWakes = new AtomicInteger();
    private TaskService tasks;
    private RunTransitions transitions;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
        Config.Project alm = new Config.Project("autoland-management", "alm", "https://github.com/acme/alm.git", "main",
                "claude-code", null, List.of(), null);
        Config.Project crm = new Config.Project("crm", null, "https://github.com/acme/crm.git", "develop",
                "claude-code", null, List.of(), null);
        Projects projects = new Projects(List.of(alm, crm),
                project -> project.name().equals("crm") ? Optional.of("repos/crm is not cloned") : Optional.empty());
        Members members = new Members(List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")));
        activeRuns = new ActiveRuns();
        tasks = new TaskService(members, projects, activeRuns, clock, schedulerWakes::incrementAndGet, outboxWakes::incrementAndGet);
        transitions = new RunTransitions(db, clock, outboxWakes::incrementAndGet);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void memberCreatesTaskWithQueuedPlanRunEventAndAcknowledgement() {
        long id = create(BOLD, "alm", "Fix login timeout on staging\nLogs show 30s", "5");

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("PLANNING", task.get("phase"));
        assertEquals("autoland-management", task.get("project"));
        assertEquals("Fix login timeout on staging", task.get("title"));
        assertEquals("Fix login timeout on staging\nLogs show 30s", task.get("description"));
        assertEquals("telegram:100", task.get("requester_ref"));
        assertEquals("Bold", task.get("requester_name"));
        assertEquals("main", task.get("base_branch"));
        assertEquals("2026-09-17T10:00:00.000Z", task.get("created_at"));
        assertNotNull(task.get("session_id"));

        Map<String, String> run = row("SELECT * FROM run WHERE task_id = ?", id);
        assertEquals("1", run.get("seq"));
        assertEquals("PLAN", run.get("kind"));
        assertEquals("QUEUED", run.get("status"));
        assertEquals("Fix login timeout on staging\nLogs show 30s", run.get("instruction"));
        assertEquals("telegram:100", run.get("requested_by"));

        Map<String, String> event = row("SELECT * FROM task_event WHERE task_id = ?", id);
        assertNull(event.get("from_phase"));
        assertEquals("PLANNING", event.get("to_phase"));
        assertEquals("telegram:100", event.get("actor"));

        Map<String, String> message = row("SELECT * FROM outbox");
        assertEquals("TASK_QUEUED", message.get("kind"));
        assertEquals(CHAT, message.get("chat_ref"));
        assertEquals(CHAT + "/5", message.get("reply_to_ref"));
        assertEquals("PENDING", message.get("status"));
        JsonNode payload = Json.read(message.get("payload"));
        assertEquals(id, payload.get("taskId").asLong());
        assertEquals("autoland-management", payload.get("project").asText());

        assertEquals(1, schedulerWakes.get());
        assertEquals(1, outboxWakes.get());
    }

    @Test
    void wakeUpsHappenOnlyAfterCommit() {
        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            tasks.create(tx, BOLD, "alm", "Fix login timeout", CHAT + "/6", CHAT);
            throw new IllegalStateException("rolled back");
        }));

        assertEquals(0, schedulerWakes.get());
        assertEquals(0, outboxWakes.get());
        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"));
    }

    @Test
    void titleIsFirstNonBlankLineCutTo80Characters() {
        long id = create(BOLD, "alm", "\n  \n" + "a".repeat(100) + "\nsecond line", "7");

        assertEquals("a".repeat(79) + "…", row("SELECT title FROM task WHERE id = ?", id).get("title"));
    }

    @Test
    void projectIsMatchedByNameOrAliasIgnoringCase() {
        long byAlias = create(BOLD, "ALM", "one", "8");
        long byName = create(BOLD, "Autoland-Management", "two", "9");

        assertEquals("autoland-management", row("SELECT project FROM task WHERE id = ?", byAlias).get("project"));
        assertEquals("autoland-management", row("SELECT project FROM task WHERE id = ?", byName).get("project"));
    }

    @Test
    void nonMemberIsToldNoAndNoTaskIsCreated() {
        CreateResult result = db.transactionReturning(tx -> tasks.create(tx, STRANGER, "alm", "Drop tables", CHAT + "/10", CHAT));

        assertEquals(CreateResult.NOT_ALLOWED, result);
        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"));
        Map<String, String> message = row("SELECT * FROM outbox");
        assertEquals("NOT_ALLOWED", message.get("kind"));
        assertEquals(CHAT + "/10", message.get("reply_to_ref"));
        assertEquals("Sara", Json.read(message.get("payload")).get("name").asText());
    }

    @Test
    void unknownProjectListsConfiguredProjects() {
        CreateResult result = db.transactionReturning(tx -> tasks.create(tx, BOLD, "billing", "x", CHAT + "/11", CHAT));

        assertEquals(CreateResult.UNKNOWN_PROJECT, result);
        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'UNKNOWN_PROJECT'").get("payload"));
        assertEquals("billing", payload.get("given").asText());
        assertEquals(2, payload.get("projects").size());
        assertEquals("alm", payload.get("projects").get(0).get("alias").asText());
        assertEquals("crm", payload.get("projects").get(1).get("name").asText());
    }

    @Test
    void unavailableProjectExplainsWhy() {
        CreateResult result = db.transactionReturning(tx -> tasks.create(tx, BOLD, "crm", "x", CHAT + "/12", CHAT));

        assertEquals(CreateResult.PROJECT_UNAVAILABLE, result);
        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'PROJECT_UNAVAILABLE'").get("payload"));
        assertEquals("crm", payload.get("project").asText());
        assertEquals("repos/crm is not cloned", payload.get("reason").asText());
        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"));
    }

    @Test
    void blankDescriptionAsksForUsage() {
        CreateResult result = db.transactionReturning(tx -> tasks.create(tx, BOLD, "alm", "  \n ", CHAT + "/13", CHAT));

        assertEquals(CreateResult.EMPTY, result);
        assertEquals("TASK_USAGE", row("SELECT kind FROM outbox").get("kind"));
    }

    @Test
    void missingProjectAsksMembersForUsageButStillRefusesStrangers() {
        CreateResult member = db.transactionReturning(tx -> tasks.create(tx, BOLD, " ", "", CHAT + "/15", CHAT));
        CreateResult stranger = db.transactionReturning(tx -> tasks.create(tx, STRANGER, "", "", CHAT + "/16", CHAT));

        assertEquals(CreateResult.EMPTY, member);
        assertEquals(CreateResult.NOT_ALLOWED, stranger);
        assertEquals("TASK_USAGE", row("SELECT kind FROM outbox WHERE reply_to_ref = ?", CHAT + "/15").get("kind"));
        assertEquals("NOT_ALLOWED", row("SELECT kind FROM outbox WHERE reply_to_ref = ?", CHAT + "/16").get("kind"));
    }

    @Test
    void redeliveredCommandCreatesOneTask() {
        create(BOLD, "alm", "Fix login timeout", "14");

        CreateResult again = db.transactionReturning(tx -> tasks.create(tx, BOLD, "alm", "Fix login timeout", CHAT + "/14", CHAT));

        assertEquals(CreateResult.DUPLICATE, again);
        assertEquals("1", row("SELECT count(*) AS n FROM task").get("n"));
        assertEquals("1", row("SELECT count(*) AS n FROM outbox").get("n"));
    }

    @Test
    void successfulPlanRunAwaitsApprovalAndPostsThePlan() {
        long id = create(BOLD, "alm", "Fix login timeout", "20");
        ClaimedRun run = claim();
        clock.advance(Duration.ofSeconds(110));

        transitions.planSucceeded(id, run.seq(), PLAN, agentResult(List.of("Bash: mkdir -p /tmp/plans")));

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("AWAITING_APPROVAL", task.get("phase"));
        assertEquals(PLAN, Plan.parse(task.get("plan_json")));
        Map<String, String> finished = row("SELECT * FROM run WHERE task_id = ?", id);
        assertEquals("SUCCEEDED", finished.get("status"));
        assertEquals("0.168185", finished.get("cost_usd"));
        assertEquals("9", finished.get("turns"));
        assertEquals("0", finished.get("exit_code"));
        assertEquals("2026-09-17T10:01:50.000Z", finished.get("finished_at"));
        assertEquals("[\"Bash: mkdir -p /tmp/plans\"]", finished.get("denials"));

        Map<String, String> message = row("SELECT * FROM outbox WHERE kind = 'PLAN_READY'");
        assertEquals(CHAT + "/20", message.get("reply_to_ref"));
        JsonNode payload = Json.read(message.get("payload"));
        assertEquals(id, payload.get("taskId").asLong());
        assertEquals(1, payload.get("planSeq").asInt());
        assertEquals("autoland-management", payload.get("project").asText());
        assertEquals(PLAN, Plan.parse(payload.get("plan").toString()));
        assertEquals("0.168185", payload.get("costUsd").asText());
        assertEquals(110, payload.get("durationSeconds").asLong());

        Map<String, String> event = row("SELECT * FROM task_event WHERE task_id = ? AND to_phase = 'AWAITING_APPROVAL'", id);
        assertEquals("PLANNING", event.get("from_phase"));
        assertEquals("dispatch", event.get("actor"));
    }

    @Test
    void failedRunFailsTheTaskWithItsReason() {
        long id = create(BOLD, "alm", "Fix login timeout", "21");
        ClaimedRun run = claim();
        AgentResult budget = new AgentResult(AgentOutcome.BUDGET_EXCEEDED, 1, "s", null, new BigDecimal("2.1"), 4, List.of(),
                "Reached maximum budget ($2)");

        transitions.failed(id, run.seq(), FailureReason.BUDGET, "Reached maximum budget ($2)", budget);

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("FAILED", task.get("phase"));
        assertEquals("BUDGET", task.get("failure_reason"));
        assertEquals("Reached maximum budget ($2)", task.get("failure_detail"));
        assertEquals("2026-09-17T10:00:00.000Z", task.get("completed_at"));
        Map<String, String> finished = row("SELECT * FROM run WHERE task_id = ?", id);
        assertEquals("FAILED", finished.get("status"));
        assertEquals("BUDGET", finished.get("failure_reason"));
        assertEquals("2.1", finished.get("cost_usd"));
        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'TASK_FAILED'").get("payload"));
        assertEquals("BUDGET", payload.get("reason").asText());
        assertEquals("Reached maximum budget ($2)", payload.get("detail").asText());
    }

    @Test
    void rejectingPlanClosesTaskAndSaysWhoRejected() {
        long id = awaitingApproval("30");

        RejectResult result = db.transactionReturning(tx -> tasks.reject(tx, ALI, id, 1));

        assertEquals(RejectResult.REJECTED, result);
        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("REJECTED", task.get("phase"));
        assertNotNull(task.get("completed_at"));
        Map<String, String> event = row("SELECT * FROM task_event WHERE task_id = ? AND to_phase = 'REJECTED'", id);
        assertEquals("AWAITING_APPROVAL", event.get("from_phase"));
        assertEquals("telegram:200", event.get("actor"));
        Map<String, String> message = row("SELECT * FROM outbox WHERE kind = 'TASK_REJECTED'");
        assertEquals(CHAT + "/30", message.get("reply_to_ref"));
        assertEquals("Ali", Json.read(message.get("payload")).get("by").asText());
    }

    @Test
    void rejectIsRefusedForStalePlansOtherPhasesUnknownTasksAndNonMembers() {
        long awaiting = awaitingApproval("31");
        long planning = create(BOLD, "alm", "Another task", "32");

        assertEquals(RejectResult.STALE_PLAN, db.transactionReturning(tx -> tasks.reject(tx, ALI, awaiting, 2)));
        assertEquals(RejectResult.WRONG_STATE, db.transactionReturning(tx -> tasks.reject(tx, ALI, planning, 1)));
        assertEquals(RejectResult.NOT_FOUND, db.transactionReturning(tx -> tasks.reject(tx, ALI, 999, 1)));
        assertEquals(RejectResult.NOT_ALLOWED, db.transactionReturning(tx -> tasks.reject(tx, STRANGER, awaiting, 1)));
        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", awaiting).get("phase"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'TASK_REJECTED'").get("n"));
    }

    @Test
    void cancellingQueuedTaskCancelsItsRun() {
        long id = create(BOLD, "alm", "Fix login timeout", "40");

        CancelResult result = db.transactionReturning(tx -> tasks.cancel(tx, ALI, id, CHAT + "/41", CHAT));

        assertEquals(CancelResult.CANCELLED, result);
        assertEquals("CANCELLED", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        Map<String, String> run = row("SELECT * FROM run WHERE task_id = ?", id);
        assertEquals("CANCELLED", run.get("status"));
        assertNotNull(run.get("finished_at"));
        Map<String, String> message = row("SELECT * FROM outbox WHERE kind = 'TASK_CANCELLED'");
        assertEquals(CHAT + "/40", message.get("reply_to_ref"));
        assertEquals("Ali", Json.read(message.get("payload")).get("by").asText());
        assertEquals("CANCELLED", row("SELECT to_phase FROM task_event WHERE task_id = ? ORDER BY id DESC LIMIT 1", id).get("to_phase"));
    }

    @Test
    void cancellingRunningTaskStopsTheActiveRunOnlyAfterCommit() {
        long id = create(BOLD, "alm", "Fix login timeout", "42");
        ClaimedRun run = claim();
        ActiveRuns.ActiveRun active = activeRuns.register(id, run.seq());

        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            tasks.cancel(tx, ALI, id, CHAT + "/43", CHAT);
            throw new IllegalStateException("rolled back");
        }));
        assertNull(active.stopReason());

        db.transaction(tx -> tasks.cancel(tx, ALI, id, CHAT + "/43", CHAT));

        assertEquals(ActiveRuns.StopReason.CANCELLED, active.stopReason());
        assertEquals("RUNNING", row("SELECT status FROM run WHERE task_id = ?", id).get("status"));
    }

    @Test
    void cancelIsRefusedForFinishedAndUnknownTasksAndNonMembers() {
        long rejected = awaitingApproval("44");
        db.transaction(tx -> tasks.reject(tx, ALI, rejected, 1));

        assertEquals(CancelResult.REFUSED, db.transactionReturning(tx -> tasks.cancel(tx, ALI, rejected, CHAT + "/45", CHAT)));
        assertEquals(CancelResult.NOT_FOUND, db.transactionReturning(tx -> tasks.cancel(tx, ALI, 999, CHAT + "/46", CHAT)));
        assertEquals(CancelResult.NOT_ALLOWED, db.transactionReturning(tx -> tasks.cancel(tx, STRANGER, rejected, CHAT + "/47", CHAT)));

        Map<String, String> refused = row("SELECT * FROM outbox WHERE kind = 'CANCEL_REFUSED'");
        assertEquals(CHAT + "/45", refused.get("reply_to_ref"));
        assertEquals("REJECTED", Json.read(refused.get("payload")).get("phase").asText());
        assertEquals(CHAT + "/46", row("SELECT reply_to_ref FROM outbox WHERE kind = 'TASK_NOT_FOUND'").get("reply_to_ref"));
        assertEquals(CHAT + "/47", row("SELECT reply_to_ref FROM outbox WHERE kind = 'NOT_ALLOWED'").get("reply_to_ref"));
    }

    @Test
    void runFinishingAfterCancelLeavesTaskCancelled() {
        long id = create(BOLD, "alm", "Fix login timeout", "48");
        ClaimedRun run = claim();
        db.transaction(tx -> tasks.cancel(tx, ALI, id, CHAT + "/49", CHAT));

        transitions.planSucceeded(id, run.seq(), PLAN, agentResult(List.of()));

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("CANCELLED", task.get("phase"));
        assertNull(task.get("plan_json"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'PLAN_READY'").get("n"));
    }

    @Test
    void cancelledRunIsRecordedWithoutTouchingTheTask() {
        long id = create(BOLD, "alm", "Fix login timeout", "50");
        ClaimedRun run = claim();
        db.transaction(tx -> tasks.cancel(tx, ALI, id, CHAT + "/51", CHAT));
        long messagesBefore = Long.parseLong(row("SELECT count(*) AS n FROM outbox").get("n"));

        transitions.cancelled(id, run.seq(), null);

        assertEquals("CANCELLED", row("SELECT status FROM run WHERE task_id = ?", id).get("status"));
        assertEquals("CANCELLED", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals(messagesBefore, Long.parseLong(row("SELECT count(*) AS n FROM outbox").get("n")));
    }

    @Test
    void listPostsActiveTasksOnly() {
        long awaiting = awaitingApproval("61");
        long rejected = awaitingApproval("62");
        db.transaction(tx -> tasks.reject(tx, ALI, rejected, 1));
        long planning = create(BOLD, "alm", "Newest task", "60");

        db.transaction(tx -> tasks.list(tx, CHAT + "/63", CHAT));

        Map<String, String> message = row("SELECT * FROM outbox WHERE kind = 'TASK_LIST'");
        assertEquals(CHAT + "/63", message.get("reply_to_ref"));
        JsonNode listed = Json.read(message.get("payload")).get("tasks");
        assertEquals(2, listed.size());
        assertEquals(awaiting, listed.get(0).get("id").asLong());
        assertEquals("AWAITING_APPROVAL", listed.get(0).get("phase").asText());
        assertEquals(planning, listed.get(1).get("id").asLong());
        assertEquals("PLANNING", listed.get(1).get("phase").asText());
        assertEquals("Newest task", listed.get(1).get("title").asText());
    }

    private long create(Requester who, String project, String text, String messageId) {
        CreateResult result = db.transactionReturning(tx -> tasks.create(tx, who, project, text, CHAT + "/" + messageId, CHAT));
        assertEquals(CreateResult.CREATED, result);
        return Long.parseLong(row("SELECT id FROM task WHERE origin_ref = ?", CHAT + "/" + messageId).get("id"));
    }

    private ClaimedRun claim() {
        return db.transactionReturning(tx -> Runs.claimNext(tx, 10, clock.instant())).orElseThrow();
    }

    private long awaitingApproval(String messageId) {
        long id = create(BOLD, "alm", "Fix login timeout", messageId);
        ClaimedRun run = claim();
        assertEquals(id, run.taskId(), "claim() takes the oldest queued run; create earlier tasks after this helper");
        transitions.planSucceeded(id, run.seq(), PLAN, agentResult(List.of()));
        return id;
    }

    private static AgentResult agentResult(List<String> denials) {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", PLAN.toJson(), new BigDecimal("0.168185"), 9, denials, null);
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
