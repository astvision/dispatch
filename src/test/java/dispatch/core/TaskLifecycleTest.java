package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import dispatch.domain.RunKind;
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
    private static final Plan PLAN_WITH_QUESTION = new Plan("Make the auth timeout configurable", List.of(),
            List.of("Read auth.timeout"), List.of(), List.of("Which environments need a longer timeout?"));

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
        Config.Project alm = new Config.Project("autoland-management", "alm", "https://github.com/acme/alm.git", null, "main",
                "claude-code", null, null, List.of(), null, null, null);
        Config.Project crm = new Config.Project("crm", null, "https://github.com/acme/crm.git", null, "develop",
                "claude-code", null, null, List.of(), null, null, null);
        Config.Project life = new Config.Project("life", null, "https://github.com/acme/life.git", null, "master",
                "claude-code", null, null, List.of(), null, null, null);
        Projects projects = new Projects(List.of(alm, crm, life),
                project -> project.name().equals("crm") ? Optional.of("repos/crm is not cloned") : Optional.empty());
        Groups groups = new Groups(List.of(
                new Config.Group("backend", -100L, List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")),
                        List.of("autoland-management", "crm")),
                new Config.Group("mobile", -300L, List.of(new Config.Member(300, "Sara")), List.of("life"))));
        activeRuns = new ActiveRuns();
        tasks = new TaskService(groups, projects, activeRuns, clock, schedulerWakes::incrementAndGet, outboxWakes::incrementAndGet);
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
        assertEquals("NORMAL", task.get("priority"));
        assertEquals("telegram:100/5", task.get("origin_ref"));
        assertEquals(CHAT, task.get("chat_ref"), "the group of its project");
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
        assertNull(message.get("reply_to_ref"), "the task was given privately, so nothing in the group to reply to");
        assertEquals("PENDING", message.get("status"));
        JsonNode payload = Json.read(message.get("payload"));
        assertEquals(id, payload.get("taskId").asLong());
        assertEquals("autoland-management", payload.get("project").asText());
        assertEquals("Bold", payload.get("requester").asText());
        assertEquals("NORMAL", payload.get("priority").asText());
        assertEquals("Fix login timeout on staging", payload.get("title").asText());

        assertEquals(1, schedulerWakes.get());
        assertEquals(1, outboxWakes.get());
    }

    @Test
    void wakeUpsHappenOnlyAfterCommit() {
        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            tasks.create(tx, BOLD, "alm", "Fix login timeout", Priority.NORMAL, BOLD.ref() + "/6");
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
        CreateResult result = db.transactionReturning(tx -> tasks.create(tx, STRANGER, "alm", "Drop tables", Priority.NORMAL, STRANGER.ref() + "/10"));

        assertEquals(CreateResult.NOT_ALLOWED, result);
        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"));
        Map<String, String> message = row("SELECT * FROM outbox");
        assertEquals("NOT_ALLOWED", message.get("kind"));
        assertEquals("telegram:999", message.get("chat_ref"));
        assertEquals("telegram:999/10", message.get("reply_to_ref"));
        assertEquals("Sara", Json.read(message.get("payload")).get("name").asText());
    }

    @Test
    void unknownProjectListsConfiguredProjects() {
        CreateResult result = db.transactionReturning(tx -> tasks.create(tx, BOLD, "billing", "x", Priority.NORMAL, BOLD.ref() + "/11"));

        assertEquals(CreateResult.UNKNOWN_PROJECT, result);
        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'UNKNOWN_PROJECT'").get("payload"));
        assertEquals("billing", payload.get("given").asText());
        assertEquals(2, payload.get("projects").size());
        assertEquals("alm", payload.get("projects").get(0).get("alias").asText());
        assertEquals("crm", payload.get("projects").get(1).get("name").asText());
    }

    @Test
    void unavailableProjectExplainsWhy() {
        CreateResult result = db.transactionReturning(tx -> tasks.create(tx, BOLD, "crm", "x", Priority.NORMAL, BOLD.ref() + "/12"));

        assertEquals(CreateResult.PROJECT_UNAVAILABLE, result);
        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'PROJECT_UNAVAILABLE'").get("payload"));
        assertEquals("crm", payload.get("project").asText());
        assertEquals("repos/crm is not cloned", payload.get("reason").asText());
        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"));
    }

    @Test
    void blankDescriptionAsksForUsage() {
        CreateResult result = db.transactionReturning(tx -> tasks.create(tx, BOLD, "alm", "  \n ", Priority.NORMAL, BOLD.ref() + "/13"));

        assertEquals(CreateResult.EMPTY, result);
        assertEquals("TASK_USAGE", row("SELECT kind FROM outbox").get("kind"));
    }

    @Test
    void missingProjectAsksMembersForUsageButStillRefusesStrangers() {
        CreateResult member = db.transactionReturning(tx -> tasks.create(tx, BOLD, " ", "", Priority.NORMAL, BOLD.ref() + "/15"));
        CreateResult stranger = db.transactionReturning(tx -> tasks.create(tx, STRANGER, "", "", Priority.NORMAL, STRANGER.ref() + "/16"));

        assertEquals(CreateResult.EMPTY, member);
        assertEquals(CreateResult.NOT_ALLOWED, stranger);
        assertEquals("TASK_USAGE", row("SELECT kind FROM outbox WHERE reply_to_ref = ?", "telegram:100/15").get("kind"));
        assertEquals("NOT_ALLOWED", row("SELECT kind FROM outbox WHERE reply_to_ref = ?", "telegram:999/16").get("kind"));
    }

    @Test
    void redeliveredCommandCreatesOneTask() {
        create(BOLD, "alm", "Fix login timeout", "14");

        CreateResult again = db.transactionReturning(tx -> tasks.create(tx, BOLD, "alm", "Fix login timeout", Priority.NORMAL, BOLD.ref() + "/14"));

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
        assertPrivateWithGroupFallback(message, "telegram:100/20");
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
        AgentResult budget = new AgentResult(AgentOutcome.BUDGET_EXCEEDED, 1, "s", null, null, new BigDecimal("2.1"), 4, List.of(),
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
        Map<String, String> message = row("SELECT * FROM outbox WHERE kind = 'TASK_FAILED'");
        assertPrivateWithGroupFallback(message, "telegram:100/21");
        JsonNode payload = Json.read(message.get("payload"));
        assertEquals("BUDGET", payload.get("reason").asText());
        assertEquals("Reached maximum budget ($2)", payload.get("detail").asText());
        Map<String, String> inGroup = row("SELECT * FROM outbox WHERE kind = 'TASK_FAILED_SHORT'");
        assertEquals(CHAT, inGroup.get("chat_ref"));
        assertNull(inGroup.get("reply_to_ref"));
        JsonNode brief = Json.read(inGroup.get("payload"));
        assertEquals("BUDGET", brief.get("reason").asText());
        assertFalse(brief.has("detail"), "details stay private");
    }

    @Test
    void rejectingPlanClosesTaskAndSaysWhoRejected() {
        long id = awaitingApproval("30");

        RejectResult result = db.transactionReturning(tx -> tasks.reject(tx, BOLD, id, 1));

        assertEquals(RejectResult.REJECTED, result);
        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("REJECTED", task.get("phase"));
        assertNotNull(task.get("completed_at"));
        Map<String, String> event = row("SELECT * FROM task_event WHERE task_id = ? AND to_phase = 'REJECTED'", id);
        assertEquals("AWAITING_APPROVAL", event.get("from_phase"));
        assertEquals("telegram:100", event.get("actor"));
        Map<String, String> message = row("SELECT * FROM outbox WHERE kind = 'TASK_REJECTED'");
        assertEquals(CHAT, message.get("chat_ref"), "the group sees the outcome");
        assertNull(message.get("reply_to_ref"));
        assertEquals("Bold", Json.read(message.get("payload")).get("by").asText());
    }

    @Test
    void rejectIsRefusedForStalePlansOtherPhasesUnknownTasksAndNonMembers() {
        long awaiting = awaitingApproval("31");
        long planning = create(BOLD, "alm", "Another task", "32");

        assertEquals(RejectResult.STALE_PLAN, db.transactionReturning(tx -> tasks.reject(tx, BOLD, awaiting, 2)));
        assertEquals(RejectResult.WRONG_STATE, db.transactionReturning(tx -> tasks.reject(tx, BOLD, planning, 1)));
        assertEquals(RejectResult.NOT_FOUND, db.transactionReturning(tx -> tasks.reject(tx, BOLD, 999, 1)));
        assertEquals(RejectResult.NOT_ALLOWED, db.transactionReturning(tx -> tasks.reject(tx, STRANGER, awaiting, 1)));
        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", awaiting).get("phase"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'TASK_REJECTED'").get("n"));
    }

    @Test
    void approvingAPlanQueuesItsExecutionAndSaysWhoApproved() {
        long id = awaitingApproval("70");

        ApproveResult result = db.transactionReturning(tx -> tasks.approve(tx, BOLD, id, 1));

        assertEquals(ApproveResult.APPROVED, result);
        assertEquals("EXECUTING", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        Map<String, String> run = row("SELECT * FROM run WHERE task_id = ? AND seq = 2", id);
        assertEquals("EXECUTE", run.get("kind"));
        assertEquals("QUEUED", run.get("status"));
        assertEquals(PLAN, Plan.parse(run.get("instruction")), "the run implements exactly the approved plan");
        assertEquals("telegram:100", run.get("requested_by"));
        assertEquals("Bold", run.get("requested_by_name"));
        Map<String, String> event = row("SELECT * FROM task_event WHERE task_id = ? AND to_phase = 'EXECUTING'", id);
        assertEquals("AWAITING_APPROVAL", event.get("from_phase"));
        assertEquals("telegram:100", event.get("actor"));
        Map<String, String> message = row("SELECT * FROM outbox WHERE kind = 'EXECUTION_QUEUED'");
        assertPrivateWithGroupFallback(message, "telegram:100/70");
        assertEquals("Bold", Json.read(message.get("payload")).get("by").asText());
        assertEquals(2, schedulerWakes.get(), "woken when the task was created and when it was approved");
    }

    @Test
    void secondApprovalOfTheSamePlanIsRefused() {
        long id = awaitingApproval("71");
        db.transaction(tx -> tasks.approve(tx, BOLD, id, 1));

        assertEquals(ApproveResult.WRONG_STATE, db.transactionReturning(tx -> tasks.approve(tx, BOLD, id, 1)));
        assertEquals("2", row("SELECT count(*) AS n FROM run WHERE task_id = ?", id).get("n"));
    }

    @Test
    void planWithOpenQuestionsCannotBeApproved() {
        long id = awaitingApproval("72", PLAN_WITH_QUESTION);

        assertEquals(ApproveResult.OPEN_QUESTIONS, db.transactionReturning(tx -> tasks.approve(tx, BOLD, id, 1)));
        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals("1", row("SELECT count(*) AS n FROM run WHERE task_id = ?", id).get("n"));
    }

    @Test
    void approveIsRefusedForStalePlansOtherPhasesUnknownTasksAndNonMembers() {
        long awaiting = awaitingApproval("73");
        long planning = create(BOLD, "alm", "Another task", "74");

        assertEquals(ApproveResult.STALE_PLAN, db.transactionReturning(tx -> tasks.approve(tx, BOLD, awaiting, 2)));
        assertEquals(ApproveResult.WRONG_STATE, db.transactionReturning(tx -> tasks.approve(tx, BOLD, planning, 1)));
        assertEquals(ApproveResult.NOT_FOUND, db.transactionReturning(tx -> tasks.approve(tx, BOLD, 999, 1)));
        assertEquals(ApproveResult.NOT_ALLOWED, db.transactionReturning(tx -> tasks.approve(tx, STRANGER, awaiting, 1)));
        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", awaiting).get("phase"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'EXECUTION_QUEUED'").get("n"));
    }

    @Test
    void onlyTheRequesterDecidesOnTheirPlan() {
        long id = awaitingApproval("75");

        assertEquals(ApproveResult.NOT_REQUESTER, db.transactionReturning(tx -> tasks.approve(tx, ALI, id, 1)));
        assertEquals(RejectResult.NOT_REQUESTER, db.transactionReturning(tx -> tasks.reject(tx, ALI, id, 1)));
        assertEquals(CorrectResult.REFUSED, db.transactionReturning(tx -> tasks.correct(tx, ALI, id, 1, "Use YAML", CHAT + "/76", CHAT)));

        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals("1", row("SELECT count(*) AS n FROM run WHERE task_id = ?", id).get("n"));
        Map<String, String> refused = row("SELECT * FROM outbox WHERE reply_to_ref = ?", CHAT + "/76");
        assertEquals("CORRECTION_REFUSED", refused.get("kind"));
        JsonNode payload = Json.read(refused.get("payload"));
        assertEquals("requester", payload.get("reason").asText());
        assertEquals("Bold", payload.get("requester").asText());
    }

    @Test
    void correctionReplansWithTheReplyAsInstruction() {
        long id = awaitingApproval("80");

        CorrectResult result = db.transactionReturning(
                tx -> tasks.correct(tx, BOLD, id, 1, "  Use a config property, not an env var\n", CHAT + "/81", CHAT));

        assertEquals(CorrectResult.CORRECTED, result);
        assertEquals("PLANNING", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        Map<String, String> run = row("SELECT * FROM run WHERE task_id = ? AND seq = 2", id);
        assertEquals("PLAN", run.get("kind"));
        assertEquals("QUEUED", run.get("status"));
        assertEquals("Use a config property, not an env var", run.get("instruction"));
        assertEquals("Bold", run.get("requested_by_name"));
        Map<String, String> event = row("SELECT * FROM task_event WHERE task_id = ? AND from_phase = 'AWAITING_APPROVAL'", id);
        assertEquals("PLANNING", event.get("to_phase"));
        assertEquals("telegram:100", event.get("actor"));
        Map<String, String> message = row("SELECT * FROM outbox WHERE kind = 'CORRECTION_QUEUED'");
        assertEquals(CHAT + "/81", message.get("reply_to_ref"));
        assertEquals(id, Json.read(message.get("payload")).get("taskId").asLong());
        assertEquals(2, schedulerWakes.get());
    }

    @Test
    void correctionOfASupersededPlanOrABusyTaskIsRefusedWithTheReason() {
        long id = awaitingApproval("82");

        assertEquals(CorrectResult.REFUSED, db.transactionReturning(tx -> tasks.correct(tx, BOLD, id, 9, "old plan", CHAT + "/83", CHAT)));
        db.transaction(tx -> tasks.correct(tx, BOLD, id, 1, "first correction", CHAT + "/84", CHAT));
        assertEquals(CorrectResult.REFUSED, db.transactionReturning(tx -> tasks.correct(tx, BOLD, id, 1, "second", CHAT + "/85", CHAT)));

        JsonNode stale = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = ?", CHAT + "/83").get("payload"));
        assertEquals("stale", stale.get("reason").asText());
        Map<String, String> busy = row("SELECT * FROM outbox WHERE reply_to_ref = ?", CHAT + "/85");
        assertEquals("CORRECTION_REFUSED", busy.get("kind"));
        assertEquals("phase", Json.read(busy.get("payload")).get("reason").asText());
        assertEquals("PLANNING", Json.read(busy.get("payload")).get("phase").asText());
        assertEquals("2", row("SELECT count(*) AS n FROM run WHERE task_id = ?", id).get("n"));
    }

    @Test
    void correctionFromNonMemberIsToldNoAndBlankReplyIsIgnored() {
        long id = awaitingApproval("86");

        assertEquals(CorrectResult.NOT_ALLOWED, db.transactionReturning(tx -> tasks.correct(tx, STRANGER, id, 1, "do it", CHAT + "/87", CHAT)));
        assertEquals(CorrectResult.EMPTY, db.transactionReturning(tx -> tasks.correct(tx, BOLD, id, 1, " \n ", CHAT + "/88", CHAT)));

        assertEquals("NOT_ALLOWED", row("SELECT kind FROM outbox WHERE reply_to_ref = ?", CHAT + "/87").get("kind"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE reply_to_ref = ?", CHAT + "/88").get("n"));
        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
    }

    @Test
    void deliveredExecutionCompletesTheTaskWithItsPullRequestAndSummary() {
        long id = executing("90");
        clock.advance(Duration.ofMinutes(4));

        transitions.completed(id, 2, executionResult(List.of("Bash: git push origin dispatch/1")),
                List.of("README.md", "src/Auth.java"), "https://github.com/acme/alm/pull/7");

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("COMPLETED", task.get("phase"));
        assertEquals("https://github.com/acme/alm/pull/7", task.get("pr_url"));
        assertEquals("2026-09-17T10:04:00.000Z", task.get("completed_at"));
        Map<String, String> run = row("SELECT * FROM run WHERE task_id = ? AND seq = 2", id);
        assertEquals("SUCCEEDED", run.get("status"));
        assertEquals("Made the auth timeout configurable.", run.get("output"));
        assertEquals("EXECUTING", row("SELECT from_phase FROM task_event WHERE task_id = ? AND to_phase = 'COMPLETED'", id).get("from_phase"));
        Map<String, String> message = row("SELECT * FROM outbox WHERE kind = 'TASK_COMPLETED'");
        assertPrivateWithGroupFallback(message, "telegram:100/90");
        JsonNode payload = Json.read(message.get("payload"));
        assertEquals(id, payload.get("taskId").asLong());
        assertEquals("autoland-management", payload.get("project").asText());
        assertEquals("https://github.com/acme/alm/pull/7", payload.get("prUrl").asText());
        assertEquals(2, payload.get("filesChanged").asInt());
        assertEquals("Made the auth timeout configurable.", payload.get("summary").asText());
        assertEquals("Bash: git push origin dispatch/1", payload.get("denials").get(0).asText());
        assertEquals("0.42", payload.get("costUsd").asText());
        assertEquals(240, payload.get("durationSeconds").asLong());
        Map<String, String> inGroup = row("SELECT * FROM outbox WHERE kind = 'TASK_COMPLETED_SHORT'");
        assertEquals(CHAT, inGroup.get("chat_ref"));
        assertNull(inGroup.get("reply_to_ref"));
        JsonNode brief = Json.read(inGroup.get("payload"));
        assertEquals("https://github.com/acme/alm/pull/7", brief.get("prUrl").asText());
        assertEquals(2, brief.get("filesChanged").asInt());
        assertFalse(brief.has("summary"), "the summary stays private");
    }

    @Test
    void executionWithoutChangesCompletesWithoutPullRequest() {
        long id = executing("91");

        transitions.completed(id, 2, executionResult(List.of()), List.of(), null);

        assertEquals("COMPLETED", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertNull(row("SELECT pr_url FROM task WHERE id = ?", id).get("pr_url"));
        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'TASK_COMPLETED'").get("payload"));
        assertTrue(payload.get("prUrl").isNull());
        assertEquals(0, payload.get("filesChanged").asInt());
        assertEquals(0, Json.read(row("SELECT payload FROM outbox WHERE kind = 'TASK_COMPLETED_SHORT'").get("payload"))
                .get("filesChanged").asInt());
    }

    @Test
    void failedDeliveryFailsTheExecutingTask() {
        long id = executing("92");

        transitions.failed(id, 2, FailureReason.DELIVERY, "git push failed", executionResult(List.of()));

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("FAILED", task.get("phase"));
        assertEquals("DELIVERY", task.get("failure_reason"));
        assertEquals("EXECUTING", row("SELECT from_phase FROM task_event WHERE task_id = ? AND to_phase = 'FAILED'", id).get("from_phase"));
    }

    @Test
    void requesterChangesTheirTasksPriority() {
        long id = create(BOLD, "alm", "Fix login timeout", "93");

        assertEquals(PriorityResult.CHANGED, db.transactionReturning(tx -> tasks.changePriority(tx, BOLD, id, Priority.URGENT)));
        assertEquals(PriorityResult.UNCHANGED, db.transactionReturning(tx -> tasks.changePriority(tx, BOLD, id, Priority.URGENT)));

        assertEquals("URGENT", row("SELECT priority FROM task WHERE id = ?", id).get("priority"));
        Map<String, String> event = row("SELECT * FROM task_event WHERE task_id = ? AND reason LIKE 'priority%'", id);
        assertEquals("priority NORMAL -> URGENT", event.get("reason"));
        assertEquals("telegram:100", event.get("actor"));
        assertEquals("PLANNING", event.get("to_phase"));
    }

    @Test
    void priorityIsTheRequestersToChangeAndOnlyWhileTheTaskIsActive() {
        long finished = awaitingApproval("95");
        db.transaction(tx -> tasks.reject(tx, BOLD, finished, 1));
        long active = create(BOLD, "alm", "Fix login timeout", "94");

        assertEquals(PriorityResult.NOT_REQUESTER, db.transactionReturning(tx -> tasks.changePriority(tx, ALI, active, Priority.LOW)));
        assertEquals(PriorityResult.NOT_ALLOWED, db.transactionReturning(tx -> tasks.changePriority(tx, STRANGER, active, Priority.LOW)));
        assertEquals(PriorityResult.NOT_FOUND, db.transactionReturning(tx -> tasks.changePriority(tx, BOLD, 999, Priority.LOW)));
        assertEquals(PriorityResult.FINISHED, db.transactionReturning(tx -> tasks.changePriority(tx, BOLD, finished, Priority.LOW)));
        assertEquals("NORMAL", row("SELECT priority FROM task WHERE id = ?", active).get("priority"));
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
        assertEquals(CHAT, message.get("chat_ref"));
        assertNull(message.get("reply_to_ref"));
        assertEquals("Ali", Json.read(message.get("payload")).get("by").asText());
        assertEquals("CANCELLED", row("SELECT to_phase FROM task_event WHERE task_id = ? ORDER BY id DESC LIMIT 1", id).get("to_phase"));
    }

    @Test
    void cancelOfATaskOutsideTheMembersGroupsLooksLikeAnUnknownTask() {
        long id = create(BOLD, "alm", "Fix login timeout", "52");
        Requester sara = new Requester("telegram:300", "Sara");

        CancelResult result = db.transactionReturning(tx -> tasks.cancel(tx, sara, id, "telegram:300/1", "telegram:300"));

        assertEquals(CancelResult.NOT_FOUND, result);
        assertEquals("PLANNING", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals("TASK_NOT_FOUND", row("SELECT kind FROM outbox WHERE reply_to_ref = 'telegram:300/1'").get("kind"));
    }

    @Test
    void taskIsOnlyForProjectsOfTheMembersOwnGroups() {
        CreateResult result = db.transactionReturning(tx -> tasks.create(tx, BOLD, "life", "Fix it", Priority.NORMAL, BOLD.ref() + "/53"));

        assertEquals(CreateResult.UNKNOWN_PROJECT, result);
        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = ?", "telegram:100/53").get("payload"));
        assertEquals(2, payload.get("projects").size(), "only the member's own projects are offered");
    }

    @Test
    void cancelFromAPrivateChatIsAnnouncedInTheGroupAndAnsweredThere() {
        long id = create(BOLD, "alm", "Fix login timeout", "44");

        db.transaction(tx -> tasks.cancel(tx, ALI, id, "telegram:200/9", "telegram:200"));

        List<Map<String, String>> messages = SqlRows.query(dbFile, "SELECT * FROM outbox WHERE kind = 'TASK_CANCELLED' ORDER BY id");
        assertEquals(2, messages.size());
        assertEquals(CHAT, messages.get(0).get("chat_ref"));
        assertNull(messages.get(0).get("reply_to_ref"));
        assertEquals("telegram:200", messages.get(1).get("chat_ref"));
        assertEquals("telegram:200/9", messages.get(1).get("reply_to_ref"));
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
        db.transaction(tx -> tasks.reject(tx, BOLD, rejected, 1));

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

    /** Sent to the requester's private chat under the message that gave the task, falling back to the group. */
    private static void assertPrivateWithGroupFallback(Map<String, String> message, String taskMessage) {
        assertEquals(taskMessage.substring(0, taskMessage.indexOf('/')), message.get("chat_ref"));
        assertEquals(taskMessage, message.get("reply_to_ref"));
        assertEquals(CHAT, message.get("fallback_chat_ref"));
        assertNull(message.get("fallback_reply_to_ref"), "the group has no message of the task to reply to");
    }

    private long create(Requester who, String project, String text, String messageId) {
        String origin = who.ref() + "/" + messageId;
        CreateResult result = db.transactionReturning(tx -> tasks.create(tx, who, project, text, Priority.NORMAL, origin));
        assertEquals(CreateResult.CREATED, result);
        return Long.parseLong(row("SELECT id FROM task WHERE origin_ref = ?", origin).get("id"));
    }

    private ClaimedRun claim() {
        return db.transactionReturning(tx -> Runs.claimNext(tx, 10, clock.instant())).orElseThrow();
    }

    private long awaitingApproval(String messageId) {
        return awaitingApproval(messageId, PLAN);
    }

    private long awaitingApproval(String messageId, Plan plan) {
        long id = create(BOLD, "alm", "Fix login timeout", messageId);
        ClaimedRun run = claim();
        assertEquals(id, run.taskId(), "claim() takes the oldest queued run; create earlier tasks after this helper");
        transitions.planSucceeded(id, run.seq(), plan, agentResult(List.of()));
        return id;
    }

    /** A task whose approved plan's execution run (seq 2) is running. */
    private long executing(String messageId) {
        long id = awaitingApproval(messageId);
        db.transaction(tx -> tasks.approve(tx, BOLD, id, 1));
        ClaimedRun run = claim();
        assertEquals(new ClaimedRun(id, 2, RunKind.EXECUTE), run);
        return id;
    }

    private static AgentResult executionResult(List<String> denials) {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", null, "Made the auth timeout configurable.",
                new BigDecimal("0.42"), 12, denials, null);
    }

    private static AgentResult agentResult(List<String> denials) {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", PLAN.toJson(), null, new BigDecimal("0.168185"), 9, denials,
                null);
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
