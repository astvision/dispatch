package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Outbox;
import dispatch.store.Runs;
import dispatch.store.TaskCi;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The watch on a delivered pull request's checks (spec: CI watch): what arms it, and what each verdict does. */
class CiWatchTest {

    private static final long BOLD_ID = 100;
    private static final Requester BOLD = new Requester("telegram:" + BOLD_ID, "Bold");
    private static final String PR = "https://github.com/acme/alm/pull/7";
    private static final String HEAD = "abc123";
    /** The result message of the first delivery, as Telegram named it. */
    private static final String RESULT = "telegram:" + BOLD_ID + "/88";

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private final TestClock clock = new TestClock(Instant.parse("2026-10-05T04:00:00Z"));
    private Projects projects;
    private TaskService tasks;
    private RunTransitions transitions;
    /** Whether a delivery arms a watch now; a test turns it off as an edited config would. */
    private boolean watched = true;
    private int woken;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        Config.Project alm = new Config.Project("autoland-management", "alm", "https://github.com/acme/alm.git", null, "main",
                "claude-code", null, null, List.of(), null, null, null, null, null, null, "on");
        projects = new Projects(List.of(alm), project -> Optional.empty());
        Groups groups = new Groups(List.of(new Config.Group("backend", -1001234567890L,
                List.of(new Config.Member(BOLD_ID, "Bold")), List.of("autoland-management"))));
        tasks = new TaskService(groups, projects, new ActiveRuns(), clock, () -> { }, () -> { });
        transitions = new RunTransitions(db, clock, () -> { }, true, project -> watched, () -> woken++);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aDeliveryOnAWatchedProjectArmsItsCommitAndTheResultSaysTheChecksRun() {
        long taskId = delivered();

        Map<String, String> row = row("SELECT head_sha, state, fix_rounds FROM task_ci WHERE task_id = ?", taskId);
        assertEquals(HEAD, row.get("head_sha"));
        assertEquals("PENDING", row.get("state"));
        assertEquals("0", row.get("fix_rounds"));
        assertEquals("PENDING", result(taskId).path("ci").path("state").asText(), "the result says the checks are running");
        assertTrue(woken > 0, "the watcher is woken");
    }

    @Test
    void aProjectThatIsNotWatchedArmsNothing() {
        watched = false;

        long taskId = delivered();

        assertEquals("0", count("SELECT count(*) AS n FROM task_ci"));
        assertTrue(result(taskId).path("ci").isMissingNode());
    }

    @Test
    void aBotThatWatchesNothingArmsNothing() {
        transitions = new RunTransitions(db, clock, () -> { }, true);

        delivered();

        assertEquals("0", count("SELECT count(*) AS n FROM task_ci"));
    }

    @Test
    void aDeliveryThatDidNotSayItsCommitArmsNothing() {
        long taskId = approved();

        transitions.completed(taskId, 2, result(null), "Done", List.of("src/Auth.java"), PR);

        assertEquals("0", count("SELECT count(*) AS n FROM task_ci"));
    }

    @Test
    void aRunThatPushedNothingLeavesTheWatchAsItWas() {
        long taskId = delivered();

        followUp(taskId, "Explain the timeout", List.of(), HEAD);

        assertEquals(HEAD, row("SELECT head_sha FROM task_ci WHERE task_id = ?", taskId).get("head_sha"));
        assertTrue(latestResult(taskId).path("ci").isMissingNode(), "a result that pushed nothing has no checks to show");
    }

    @Test
    void aMembersFollowUpIsWatchedFromItsOwnCommitWithTheFixCountAfresh() {
        long taskId = delivered();
        db.transaction(tx -> TaskCi.fixing(tx, taskId, "[]", clock.instant()));

        followUp(taskId, "Also cover the mobile login", List.of("src/Mobile.java"), "def456");

        Map<String, String> row = row("SELECT head_sha, state, fix_rounds FROM task_ci WHERE task_id = ?", taskId);
        assertEquals("def456", row.get("head_sha"));
        assertEquals("PENDING", row.get("state"));
        assertEquals("0", row.get("fix_rounds"), "new instructions are new work");
    }

    @Test
    void aCiFixIsOneMoreExecutionInTheRequestersNameThatTheWatcherAsked() {
        long taskId = delivered();

        boolean queued = db.transactionReturning(tx -> tasks.commands().ciFix(tx, taskId, HEAD, "Failed checks:\n- ui: https://x",
                Json.object().put("check", "ui").put("round", 1)));

        assertTrue(queued);
        Map<String, String> run = row("SELECT kind, cause, status, instruction, requested_by FROM run WHERE task_id = ? AND seq = 3", taskId);
        assertEquals("EXECUTE", run.get("kind"));
        assertEquals("CI_FIX", run.get("cause"));
        assertEquals("QUEUED", run.get("status"));
        assertEquals("Failed checks:\n- ui: https://x", run.get("instruction"));
        assertEquals(BOLD.ref(), run.get("requested_by"));
        assertEquals("EXECUTING", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        Map<String, String> event = row("SELECT actor, reason, run_seq FROM task_event WHERE task_id = ? ORDER BY rowid DESC LIMIT 1", taskId);
        assertEquals("ci", event.get("actor"));
        assertEquals("ci-fix", event.get("reason"));
        assertEquals("3", event.get("run_seq"));
        Map<String, String> news = row("SELECT chat_ref, payload FROM outbox WHERE kind = 'CI_FIX_QUEUED'");
        assertEquals(BOLD.ref(), news.get("chat_ref"));
        JsonNode payload = Json.read(news.get("payload"));
        assertEquals("ui", payload.path("check").asText());
        assertEquals(1, payload.path("round").asInt());
        assertEquals(taskId, payload.path("taskId").asLong());
    }

    @Test
    void aCiFixForATaskThatMovedOnWritesNothing() {
        long taskId = delivered();

        assertFalse(ciFix(taskId, "another"), "another head");
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.FollowUp(taskId, "more", new Origin("telegram:" + BOLD_ID + "/20"))));
        assertFalse(ciFix(taskId, HEAD), "a follow-up is running");

        assertEquals("0", count("SELECT count(*) AS n FROM run WHERE cause = 'CI_FIX'"));
        assertEquals("0", count("SELECT count(*) AS n FROM outbox WHERE kind = 'CI_FIX_QUEUED'"));
    }

    /** Whether a fix run was queued for the task as it stands at {@code head}. */
    private boolean ciFix(long taskId, String head) {
        return db.transactionReturning(tx -> tasks.commands().ciFix(tx, taskId, head, "x", Json.object()));
    }

    /** A task Bold gave, planned and approved; its execution is run 2, claimed and started. */
    private long approved() {
        long taskId = assertInstanceOf(CommandResult.Created.class, db.transactionReturning(tx -> tasks.commands().run(tx, BOLD,
                new TaskCommand.Give("alm", "Fix the login timeout", Priority.NORMAL, new Origin("telegram:" + BOLD_ID + "/10"))))).taskId();
        db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        Plan plan = new Plan("Make the timeout configurable", List.of(), List.of("Read auth.timeout"), List.of(), List.of());
        transitions.planSucceeded(taskId, 1, plan, result(plan.toJson()));
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Approve(taskId, 1)));
        db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        transitions.agentStarted(taskId, 2, null, null);
        return taskId;
    }

    /** The same, delivered as {@link #PR} at {@link #HEAD}; its result was sent as {@link #RESULT}. */
    private long delivered() {
        long taskId = approved();
        transitions.completed(taskId, 2, result(null), "Done", List.of("src/Auth.java"), PR, null, HEAD);
        long resultId = Long.parseLong(row("SELECT id FROM outbox WHERE kind = 'TASK_COMPLETED' AND task_id = ?", taskId).get("id"));
        db.transaction(tx -> Outbox.markSent(tx, resultId, 1, RESULT, clock.instant()));
        return taskId;
    }

    /** Bold's follow-up on the delivered task, run to its end: it changed {@code files} and left the branch at {@code head}. */
    private void followUp(long taskId, String text, List<String> files, String head) {
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.FollowUp(taskId, text, new Origin("telegram:" + BOLD_ID + "/20"))));
        finishRun(taskId, files, head, "Done");
    }

    /** Claims the task's queued run, starts it and ends it as delivered. */
    private void finishRun(long taskId, List<String> files, String head, String summary) {
        db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        int seq = Integer.parseInt(row("SELECT max(seq) AS seq FROM run WHERE task_id = ?", taskId).get("seq"));
        transitions.agentStarted(taskId, seq, null, null);
        transitions.completed(taskId, seq, result(null), summary, files, null, null, head);
    }

    /** The first result's payload. */
    private JsonNode result(long taskId) {
        return Json.read(row("SELECT payload FROM outbox WHERE kind = 'TASK_COMPLETED' AND task_id = ? ORDER BY id LIMIT 1", taskId)
                .get("payload"));
    }

    /** The newest result's payload, redraws aside. */
    private JsonNode latestResult(long taskId) {
        return Json.read(row("""
                SELECT payload FROM outbox WHERE kind = 'TASK_COMPLETED' AND task_id = ? AND edit_ref IS NULL AND edit_of IS NULL
                ORDER BY id DESC LIMIT 1""", taskId).get("payload"));
    }

    private static AgentResult result(String structuredOutput) {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", structuredOutput, "Done", new BigDecimal("0.10"), 3, List.of(), null, null,
                null);
    }

    private String count(String sql, Object... params) {
        return row(sql, params).get("n");
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
