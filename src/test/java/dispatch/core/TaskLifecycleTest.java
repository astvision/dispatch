package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.SandboxUse;
import dispatch.config.Config;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.store.Workers;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import dispatch.worker.Readiness;
import dispatch.worker.WorkerKeys;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskLifecycleTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
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
    private Projects projects;
    private Groups groups;
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
        projects = new Projects(List.of(alm), project -> Optional.empty());
        groups = new Groups(List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("autoland-management"))));
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
    void aTaskGivenWhileNoComputerIsConnectedTellsItsRequesterOnce() {
        tasks = teamTaskService();

        long id = create(BOLD, "alm", "Fix login timeout", "95");

        assertEquals("1", row("SELECT count(*) AS n FROM outbox WHERE kind = 'WORKER_WAITING' AND task_id = ?", id).get("n"));
        JsonNode status = tasksStatusPayload();
        assertTrue(status.get("queued").get(0).get("waitingForWorker").asBoolean(), status.toString());
    }

    @Test
    void aTaskGivenWithAConnectedComputerSaysNothingAboutWaiting() {
        tasks = teamTaskService();
        WorkerKeys keys = new WorkerKeys(db, clock);
        long worker = keys.pair(keys.newCode(BOLD), "ann-laptop").orElseThrow().workerId();
        db.transaction(tx -> Workers.touch(tx, worker, clock.instant()));

        long id = create(BOLD, "alm", "Fix login timeout", "96");

        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'WORKER_WAITING' AND task_id = ?", id).get("n"));
        JsonNode status = tasksStatusPayload();
        assertFalse(status.get("queued").get(0).has("waitingForWorker"), status.toString());
    }

    @Test
    void statusStillExplainsATaskPinnedToAnOfflineWorkerEvenWithAnotherLiveOne() {
        tasks = teamTaskService();
        WorkerKeys keys = new WorkerKeys(db, clock);
        long pinned = keys.pair(keys.newCode(BOLD), "ann-laptop").orElseThrow().workerId();
        db.transaction(tx -> Workers.touch(tx, pinned, clock.instant()));
        long id = create(BOLD, "alm", "Fix login timeout", "97");
        db.transaction(tx -> Tasks.recordWorker(tx, id, pinned, clock.instant()));
        long other = keys.pair(keys.newCode(BOLD), "ann-desktop").orElseThrow().workerId();

        // The pinned laptop goes silent, but Bold's desktop is live: the note must still follow the pin, not the member.
        clock.advance(Duration.ofSeconds(Workers.SEEN_WITHIN.toSeconds() + 1));
        db.transaction(tx -> Workers.touch(tx, other, clock.instant()));

        JsonNode status = tasksStatusPayload();
        assertTrue(status.get("queued").get(0).get("waitingForWorker").asBoolean(),
                "pinned to the silent laptop, not the live desktop: " + status);
    }

    @Test
    void aHeldTaskTellsItsRequesterWhichThingIsWrongOnceAndStatusSaysWhy() {
        tasks = teamTaskService();
        long worker = liveWorkerReporting(BOLD, "ann-laptop", new Readiness(new Readiness.Check(false, "cannot run claude"),
                new Readiness.Check(true, null), Map.of()));
        long id = create(BOLD, "alm", "Fix login timeout", "98");

        reportBlocked();
        reportBlocked();

        assertEquals("1", row("SELECT count(*) AS n FROM outbox WHERE kind = 'WORKER_BLOCKED' AND task_id = ?", id).get("n"),
                "said once, not on every idle poll");
        Map<String, String> told = row("SELECT chat_ref, payload FROM outbox WHERE kind = 'WORKER_BLOCKED' AND task_id = ?", id);
        assertEquals(BOLD.ref(), told.get("chat_ref"), "the requester, privately");
        JsonNode payload = Json.read(told.get("payload"));
        assertEquals(id, payload.get("taskId").asLong());
        assertEquals("claude", payload.get("code").asText());
        assertEquals("cannot run claude", payload.get("detail").asText());
        assertEquals("claude", tasksStatusPayload().get("queued").get(0).get("blocked").asText());

        db.transaction(tx -> Workers.saveReadiness(tx, worker, Readiness.READY, clock.instant()));
        reportBlocked();
        assertFalse(tasksStatusPayload().get("queued").get(0).has("blocked"), "fixed: /status stops saying so");

        db.transaction(tx -> Workers.saveReadiness(tx, worker, new Readiness(new Readiness.Check(false, "cannot run claude"),
                new Readiness.Check(true, null), Map.of()), clock.instant()));
        reportBlocked();
        assertEquals("2", row("SELECT count(*) AS n FROM outbox WHERE kind = 'WORKER_BLOCKED' AND task_id = ?", id).get("n"),
                "broken again after being fixed is news again");
    }

    @Test
    void statusSaysOfflineRatherThanAStaleBlockerWhenTheComputerGoesSilent() {
        tasks = teamTaskService();
        liveWorkerReporting(BOLD, "ann-laptop", new Readiness(new Readiness.Check(false, "cannot run claude"),
                new Readiness.Check(true, null), Map.of()));
        create(BOLD, "alm", "Fix login timeout", "100");
        reportBlocked();

        clock.advance(Duration.ofSeconds(Workers.SEEN_WITHIN.toSeconds() + 1));

        JsonNode queued = tasksStatusPayload().get("queued").get(0);
        assertTrue(queued.get("waitingForWorker").asBoolean(), queued.toString());
        assertFalse(queued.has("blocked"), "before the scheduler's next pass clears it: " + queued);
    }

    @Test
    void aTaskWaitingOnlyForItsTurnIsNotReportedAsBlocked() {
        tasks = teamTaskService();
        // gh is broken, but a plan never touches GitHub: this task is not held by it.
        liveWorkerReporting(BOLD, "ann-laptop", new Readiness(new Readiness.Check(true, "2.1.280"),
                new Readiness.Check(false, "not logged in"), Map.of()));
        long id = create(BOLD, "alm", "Fix login timeout", "99");

        reportBlocked();

        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'WORKER_BLOCKED' AND task_id = ?", id).get("n"));
        assertFalse(tasksStatusPayload().get("queued").get(0).has("blocked"));
    }

    @Test
    void wakeUpsHappenOnlyAfterCommit() {
        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            tasks.commands().run(tx, BOLD, new TaskCommand.Give("alm", "Fix login timeout", Priority.NORMAL, new Origin(BOLD.ref() + "/6")));
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
                "Reached maximum budget ($2)", null, null);

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

        CommandResult result = db.transactionReturning(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Reject(id, 1)));

        assertEquals(new CommandResult.Done(id, true), result);
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
    void approvingAPlanQueuesItsExecutionAndSaysWhoApproved() {
        long id = awaitingApproval("70");

        CommandResult result = db.transactionReturning(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Approve(id, 1)));

        assertEquals(new CommandResult.Done(id, true), result);
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
    void correctionReplansWithTheReplyAsInstruction() {
        long id = awaitingApproval("80");

        CommandResult result = db.transactionReturning(tx -> tasks.commands().run(tx, BOLD,
                new TaskCommand.Correct(id, OptionalInt.of(1), "  Use a config property, not an env var\n")));

        assertEquals(new CommandResult.Done(id, true), result);
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
        assertPrivateWithGroupFallback(message, "telegram:100/80");
        assertEquals(id, Json.read(message.get("payload")).get("taskId").asLong());
        assertEquals(2, schedulerWakes.get());
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

        assertEquals(new CommandResult.Done(id, true), reprioritize(id, Priority.URGENT));
        assertEquals(new CommandResult.Unchanged(id), reprioritize(id, Priority.URGENT));

        assertEquals("URGENT", row("SELECT priority FROM task WHERE id = ?", id).get("priority"));
        Map<String, String> event = row("SELECT * FROM task_event WHERE task_id = ? AND reason LIKE 'priority%'", id);
        assertEquals("priority NORMAL -> URGENT", event.get("reason"));
        assertEquals("telegram:100", event.get("actor"));
        assertEquals("PLANNING", event.get("to_phase"));
    }

    @Test
    void cancellingQueuedTaskCancelsItsRun() {
        long id = create(BOLD, "alm", "Fix login timeout", "40");

        CommandResult result = db.transactionReturning(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Cancel(id)));

        assertEquals(new CommandResult.Done(id, true), result);
        assertEquals("CANCELLED", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        Map<String, String> run = row("SELECT * FROM run WHERE task_id = ?", id);
        assertEquals("CANCELLED", run.get("status"));
        assertNotNull(run.get("finished_at"));
        Map<String, String> message = row("SELECT * FROM outbox WHERE kind = 'TASK_CANCELLED' AND chat_ref = ?", CHAT);
        assertNull(message.get("reply_to_ref"));
        assertEquals("Bold", Json.read(message.get("payload")).get("by").asText());
        assertEquals("CANCELLED", row("SELECT to_phase FROM task_event WHERE task_id = ? ORDER BY id DESC LIMIT 1", id).get("to_phase"));
    }

    @Test
    void cancellingRunningTaskStopsTheActiveRunOnlyAfterCommit() {
        long id = create(BOLD, "alm", "Fix login timeout", "42");
        ClaimedRun run = claim();
        ActiveRuns.ActiveRun active = activeRuns.register(id, run.seq());

        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            tasks.commands().run(tx, BOLD, new TaskCommand.Cancel(id));
            throw new IllegalStateException("rolled back");
        }));
        assertNull(active.stopReason());

        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Cancel(id)));

        assertEquals(ActiveRuns.StopReason.CANCELLED, active.stopReason());
        assertEquals("RUNNING", row("SELECT status FROM run WHERE task_id = ?", id).get("status"));
    }

    @Test
    void runFinishingAfterCancelLeavesTaskCancelled() {
        long id = create(BOLD, "alm", "Fix login timeout", "48");
        ClaimedRun run = claim();
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Cancel(id)));

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
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Cancel(id)));
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

    private TaskService teamTaskService() {
        return new TaskService(groups, projects, activeRuns, clock, schedulerWakes::incrementAndGet, outboxWakes::incrementAndGet,
                false, draftId -> { }, true);
    }

    /** A computer of {@code who} that was just seen and whose last report is {@code readiness}. */
    private long liveWorkerReporting(Requester who, String name, Readiness readiness) {
        WorkerKeys keys = new WorkerKeys(db, clock);
        long worker = keys.pair(keys.newCode(who), name).orElseThrow().workerId();
        db.transaction(tx -> Workers.touch(tx, worker, clock.instant()));
        db.transaction(tx -> Workers.saveReadiness(tx, worker, readiness, clock.instant()));
        return worker;
    }

    private void reportBlocked() {
        db.transaction(tx -> tasks.reportBlocked(tx, clock.instant().minus(Workers.SEEN_WITHIN)));
    }

    private JsonNode tasksStatusPayload() {
        // The fixture's alm project is named "autoland-management"; "alm" is only its alias (ADR: project key vs. name).
        return db.transactionReturning(tx -> tasks.statusPayload(tx, new TaskAccess.Viewer(BOLD.ref(), Set.of("autoland-management"))));
    }

    private long create(Requester who, String project, String text, String messageId) {
        CommandResult given = db.transactionReturning(tx -> tasks.commands().run(tx, who,
                new TaskCommand.Give(project, text, Priority.NORMAL, new Origin(who.ref() + "/" + messageId))));
        return assertInstanceOf(CommandResult.Created.class, given).taskId();
    }

    private CommandResult reprioritize(long id, Priority priority) {
        return db.transactionReturning(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Reprioritize(id, priority)));
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
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Approve(id, 1)));
        ClaimedRun run = claim();
        assertEquals(new ClaimedRun(id, 2, RunKind.EXECUTE), run);
        return id;
    }

    private static AgentResult executionResult(List<String> denials) {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", null, "Made the auth timeout configurable.",
                new BigDecimal("0.42"), 12, denials, null, null, null);
    }

    private static AgentResult agentResult(List<String> denials) {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", PLAN.toJson(), null, new BigDecimal("0.168185"), 9, denials,
                null, null, null);
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }

    @Test
    void anUnsandboxedPlanRunIsRecordedAndItsPlanSaysWhy() {
        long id = create(BOLD, "alm", "Fix login timeout", "20");
        ClaimedRun run = claim();

        transitions.planSucceeded(id, run.seq(), PLAN,
                agentResult(List.of()).withSandbox(new SandboxUse("none", "not available on Windows")));

        assertEquals("none", row("SELECT sandbox FROM run WHERE task_id = ?", id).get("sandbox"));
        JsonNode payload = Json.read(row("SELECT * FROM outbox WHERE kind = 'PLAN_READY'").get("payload"));
        assertEquals("not available on Windows", payload.get("unsandboxed").asText());
    }

    @Test
    void aSandboxedPlanRunIsRecordedWithoutAWarning() {
        long id = create(BOLD, "alm", "Fix login timeout", "20");
        ClaimedRun run = claim();

        transitions.planSucceeded(id, run.seq(), PLAN, agentResult(List.of()).withSandbox(new SandboxUse("bubblewrap", null)));

        assertEquals("bubblewrap", row("SELECT sandbox FROM run WHERE task_id = ?", id).get("sandbox"));
        JsonNode payload = Json.read(row("SELECT * FROM outbox WHERE kind = 'PLAN_READY'").get("payload"));
        assertFalse(payload.has("unsandboxed"));
    }

    @Test
    void aCompletedRunCarriesItsVerificationToTheRequester() {
        long id = executing("20");
        Verification verification = new Verification(Verification.Tests.FAILING, 4, "FooTest failed",
                Verification.ReviewState.OK, List.of(), null, null);

        transitions.completed(id, 2, executionResult(List.of()), "Made it configurable.", List.of("A.java"),
                "https://github.com/acme/alm/pull/7", verification);

        JsonNode payload = Json.read(row("SELECT * FROM outbox WHERE kind = 'TASK_COMPLETED'").get("payload"));
        assertEquals("FAILING", payload.path("verification").path("tests").asText());
        assertEquals(4, payload.path("verification").path("testRuns").asInt());
    }
}
