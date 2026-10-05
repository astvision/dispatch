package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import dispatch.workspace.Gh;
import dispatch.workspace.WorkspaceException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** What GitHub says of the pull request; a test changes it between passes. */
    private String prState = "OPEN";
    private String prHead = HEAD;
    private List<Gh.Check> checks = List.of();
    /** What a failed log holds; null for one that cannot be read. */
    private String log = "";
    /** Thrown by the next question to GitHub, as when it cannot be reached. */
    private RuntimeException unreachable;
    /** Run in the middle of a question to GitHub: what happens to the task meanwhile. */
    private Runnable meanwhile = () -> { };
    private final List<String> asked = new ArrayList<>();
    private CiWatch watch;

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
        watch = new CiWatch(db, projects, tasks.commands(), new CiWatch.Checks() {
            @Override
            public Gh.PullRequest pullRequest(String url) {
                asked.add("pr");
                if (unreachable != null) {
                    throw unreachable;
                }
                return new Gh.PullRequest(prState, prHead);
            }

            @Override
            public List<Gh.Check> checks(String url) {
                asked.add("checks");
                meanwhile.run();
                return checks;
            }

            @Override
            public String failedLog(String checkLink) {
                asked.add("log " + checkLink);
                if (log == null) {
                    throw new WorkspaceException("gh run view timed out");
                }
                return log;
            }
        }, clock, new Signal(), () -> { });
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

    @Test
    void checksStillRunningLeaveTheWatchAsItIs() {
        long taskId = delivered();
        checks = List.of(check("ui", "pass", 11), check("test", "pending", 11));

        assertEquals(0, watch.pass());

        assertEquals("PENDING", watchRow(taskId).get("state"));
        assertEquals("0", count("SELECT count(*) AS n FROM outbox WHERE kind LIKE 'CI_%'"));
    }

    @Test
    void greenChecksSayThePullRequestIsReadyUnderItsResult() {
        long taskId = delivered();
        checks = List.of(check("ui", "pass", 11), check("docs", "skipping", 11));

        assertEquals(1, watch.pass());

        assertEquals("PASSED", watchRow(taskId).get("state"));
        Map<String, String> reply = row("SELECT chat_ref, reply_to_ref FROM outbox WHERE kind = 'CI_PASSED'");
        assertEquals(BOLD.ref(), reply.get("chat_ref"));
        assertEquals(RESULT, reply.get("reply_to_ref"));
        JsonNode result = redrawn(taskId);
        assertEquals("PASSED", result.path("ci").path("state").asText());
        assertTrue(result.path("merge").asBoolean(), "the redrawn result keeps its Merge button");
        assertEquals("ci passed", row("SELECT reason FROM task_event WHERE task_id = ? AND actor = 'ci'", taskId).get("reason"));

        assertEquals(0, watch.pass(), "a settled watch is not asked about again");
        assertEquals("1", count("SELECT count(*) AS n FROM outbox WHERE kind = 'CI_PASSED'"));
    }

    @Test
    void noChecksForTenMinutesEndTheWatchWithoutAWord() {
        long taskId = delivered();

        clock.advance(Duration.ofMinutes(9));
        assertEquals(0, watch.pass());
        assertEquals("PENDING", watchRow(taskId).get("state"), "checks take a moment to appear");

        clock.advance(Duration.ofMinutes(2));
        assertEquals(1, watch.pass());
        assertEquals("NONE", watchRow(taskId).get("state"));
        assertEquals("0", count("SELECT count(*) AS n FROM outbox WHERE kind LIKE 'CI_%'"));
        assertTrue(redrawn(taskId).path("ci").isMissingNode(), "the result no longer says the checks are running");
    }

    @Test
    void checksStillRunningAfterSixHoursAreHandedBack() {
        long taskId = delivered();
        checks = List.of(check("test", "pending", 11));
        clock.advance(Duration.ofHours(6).plusMinutes(1));

        assertEquals(1, watch.pass());

        assertEquals("GAVE_UP", watchRow(taskId).get("state"));
        assertEquals("STUCK", watchRow(taskId).get("reason"));
        assertEquals("STUCK", said("CI_GAVE_UP").path("reason").asText());
    }

    @Test
    void cancelledChecksAreSaidAndNotFixed() {
        long taskId = delivered();
        checks = List.of(check("ui", "pass", 11), check("test", "cancel", 11));

        assertEquals(1, watch.pass());

        assertEquals("CANCELLED", watchRow(taskId).get("reason"));
        assertEquals("0", count("SELECT count(*) AS n FROM run WHERE cause = 'CI_FIX'"));
        assertEquals("CANCELLED", said("CI_GAVE_UP").path("reason").asText());
    }

    @Test
    void aCommitDispatchDidNotPushStopsTheWatch() {
        long taskId = delivered();
        prHead = "someone-elses";
        checks = List.of(check("ui", "fail", 11));

        assertEquals(1, watch.pass());

        assertEquals("STOPPED", watchRow(taskId).get("state"));
        assertEquals("MOVED", watchRow(taskId).get("reason"));
        assertFalse(asked.contains("checks"), "its checks are not this watch's business");
        assertEquals("0", count("SELECT count(*) AS n FROM outbox WHERE kind LIKE 'CI_%'"));
    }

    @Test
    void aPullRequestMergedOnGitHubIsRecordedOnItsTask() {
        long taskId = delivered();
        prState = "MERGED";

        assertEquals(1, watch.pass());

        assertTrue(row("SELECT merged_at FROM task WHERE id = ?", taskId).get("merged_at") != null);
        assertEquals("MERGED", watchRow(taskId).get("reason"));
        assertEquals(RESULT, row("SELECT reply_to_ref FROM outbox WHERE kind = 'TASK_MERGED'").get("reply_to_ref"));
        assertTrue(Json.read(row("SELECT payload FROM outbox WHERE kind = 'TASK_COMPLETED' AND edit_ref = ?", RESULT).get("payload"))
                .path("merged").asBoolean(), "the result loses its button");
        assertEquals("github", row("SELECT actor FROM task_event WHERE task_id = ? AND reason = 'merged'", taskId).get("actor"));
    }

    @Test
    void aClosedPullRequestStopsTheWatch() {
        long taskId = delivered();
        prState = "CLOSED";

        assertEquals(1, watch.pass());

        assertEquals("CLOSED", watchRow(taskId).get("reason"));
    }

    @Test
    void whenGitHubCannotBeAskedTheRowWaits() {
        long taskId = delivered();
        unreachable = new WorkspaceException("gh pr view failed (exit 1): could not resolve host: api.github.com");

        assertEquals(0, watch.pass());
        assertEquals("PENDING", watchRow(taskId).get("state"));

        unreachable = null;
        checks = List.of(check("ui", "pass", 11));
        assertEquals(1, watch.pass());
        assertEquals("PASSED", watchRow(taskId).get("state"));
    }

    @Test
    void aVerdictForATaskThatMovedOnIsDropped() {
        long taskId = delivered();
        checks = List.of(check("ui", "pass", 11));
        meanwhile = () -> db.transaction(tx -> tasks.commands().run(tx, BOLD,
                new TaskCommand.FollowUp(taskId, "more", new Origin("telegram:" + BOLD_ID + "/20"))));

        assertEquals(0, watch.pass());

        assertEquals("PENDING", watchRow(taskId).get("state"), "the follow-up's own delivery re-arms it");
        assertEquals("0", count("SELECT count(*) AS n FROM outbox WHERE kind = 'CI_PASSED'"));
    }

    @Test
    void aTaskWithARunGoingIsNotAskedAbout() {
        long taskId = delivered();
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.FollowUp(taskId, "more", new Origin("telegram:" + BOLD_ID + "/20"))));

        assertEquals(0, watch.pass());

        assertTrue(asked.isEmpty(), "GitHub is not asked while the branch is being worked on");
    }

    @Test
    void aResultNotYetSentStillGetsItsNews() {
        long taskId = approved();
        transitions.completed(taskId, 2, result(null), "Done", List.of("src/Auth.java"), PR, null, HEAD);
        checks = List.of(check("ui", "pass", 11));

        assertEquals(1, watch.pass());

        assertNull(row("SELECT reply_to_ref FROM outbox WHERE kind = 'CI_PASSED'").get("reply_to_ref"));
        assertEquals("PASSED", redrawn(taskId).path("ci").path("state").asText(), "the redraw waits for its original");
    }

    @Test
    void aProjectThatLeftTheConfigStopsTheWatch() {
        long taskId = delivered();
        CiWatch without = new CiWatch(db, new Projects(List.of(), project -> Optional.empty()), tasks.commands(), null, clock,
                new Signal(), () -> { });

        assertEquals(1, without.pass());

        assertEquals("OFF", watchRow(taskId).get("reason"));
    }

    @Test
    void aFailedCheckStartsAFixRunThatIsToldWhatFailed() {
        long taskId = delivered();
        checks = List.of(check("ui", "pass", 11), check("test (windows-latest)", "fail", 11));
        log = "AuthTest > timeout FAILED\n    expected 30 but was 0";

        assertEquals(1, watch.pass());

        Map<String, String> run = row("SELECT kind, cause, status, instruction FROM run WHERE task_id = ? AND seq = 3", taskId);
        assertEquals("EXECUTE", run.get("kind"));
        assertEquals("CI_FIX", run.get("cause"));
        assertTrue(run.get("instruction").contains("- test (windows-latest): https://github.com/acme/alm/actions/runs/11/job/110"),
                run.get("instruction"));
        assertTrue(run.get("instruction").contains("<log>\nAuthTest > timeout FAILED"), run.get("instruction"));
        assertFalse(run.get("instruction").contains("- ui:"), "only what failed");
        Map<String, String> watchRow = watchRow(taskId);
        assertEquals("FIXING", watchRow.get("state"));
        assertEquals("1", watchRow.get("fix_rounds"));
        assertEquals("test (windows-latest)", Json.read(watchRow.get("checks_json")).get(0).path("name").asText());
        JsonNode news = said("CI_FIX_QUEUED");
        assertEquals("test (windows-latest)", news.path("check").asText());
        assertEquals(1, news.path("round").asInt());
        JsonNode line = redrawn(taskId).path("ci");
        assertEquals("FIXING", line.path("state").asText());
        assertEquals(1, line.path("round").asInt());
        assertEquals("2", count("SELECT count(*) AS n FROM task_event WHERE task_id = ? AND actor = 'ci'", taskId), "ci failed, then ci-fix");

        asked.clear();
        assertEquals(0, watch.pass(), "the run is going: nothing is asked or decided");
        assertTrue(asked.isEmpty());
        assertEquals("1", count("SELECT count(*) AS n FROM run WHERE cause = 'CI_FIX'"));
    }

    @Test
    void theFixesOwnCommitIsWatchedInTurnAndASecondRedRunGetsASecondFixButAThirdNone() {
        long taskId = delivered();
        checks = List.of(check("test", "fail", 11));
        watch.pass();
        finishRun(taskId, List.of("src/Auth.java"), "fix1", "Fixed the timeout");
        assertEquals("PENDING", watchRow(taskId).get("state"));
        assertEquals("fix1", watchRow(taskId).get("head_sha"));
        assertEquals("1", watchRow(taskId).get("fix_rounds"), "a fix run's delivery keeps the count");
        assertEquals("PENDING", latestResult(taskId).path("ci").path("state").asText(), "the fix's own result carries the line now");

        prHead = "fix1";
        assertEquals(1, watch.pass());
        assertEquals("2", watchRow(taskId).get("fix_rounds"));
        finishRun(taskId, List.of("src/Auth.java"), "fix2", "Fixed it again");

        prHead = "fix2";
        assertEquals(1, watch.pass());

        assertEquals("2", count("SELECT count(*) AS n FROM run WHERE cause = 'CI_FIX'"), "no third fix");
        assertEquals("GAVE_UP", watchRow(taskId).get("state"));
        assertEquals("CAP", watchRow(taskId).get("reason"));
        JsonNode gaveUp = said("CI_GAVE_UP");
        assertEquals("CAP", gaveUp.path("reason").asText());
        assertEquals("test", gaveUp.path("checks").get(0).path("name").asText());
        assertEquals("COMPLETED", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"), "the task stays delivered, with its Merge button");
    }

    @Test
    void aFixThatChangesNothingIsSaidOnceInTheAgentsOwnWords() {
        long taskId = delivered();
        checks = List.of(check("test", "fail", 11));
        watch.pass();
        finishRun(taskId, List.of(), HEAD, "The runner lost its network; nothing in this change is involved.");
        asked.clear();

        assertEquals(1, watch.pass());

        assertEquals("GAVE_UP", watchRow(taskId).get("state"));
        assertEquals("UNCHANGED", watchRow(taskId).get("reason"));
        JsonNode gaveUp = said("CI_GAVE_UP");
        assertEquals("The runner lost its network; nothing in this change is involved.", gaveUp.path("summary").asText());
        assertEquals("test", gaveUp.path("checks").get(0).path("name").asText());
        assertTrue(asked.isEmpty(), "settled from the database alone");

        assertEquals(0, watch.pass(), "the same red commit never starts another fix");
        assertEquals("1", count("SELECT count(*) AS n FROM run WHERE cause = 'CI_FIX'"));
    }

    @Test
    void failedLogsAreReadOncePerRunThreeRunsAtMostAndCapped() {
        long taskId = delivered();
        checks = List.of(check("a", "fail", 11), check("b", "fail", 11), check("c", "fail", 12), check("d", "fail", 13), check("e", "fail", 14));
        log = "x".repeat(20_000);

        watch.pass();

        assertEquals(List.of("log https://github.com/acme/alm/actions/runs/11", "log https://github.com/acme/alm/actions/runs/12",
                "log https://github.com/acme/alm/actions/runs/13"), asked.stream().filter(question -> question.startsWith("log ")).toList());
        String instruction = row("SELECT instruction FROM run WHERE task_id = ? AND seq = 3", taskId).get("instruction");
        assertTrue(instruction.contains("- e: "), "every failed check is named");
        int logStart = instruction.indexOf("<log>\n") + "<log>\n".length();
        assertEquals(30_000, instruction.indexOf("\n</log>") - logStart, "the log is capped");
    }

    @Test
    void aCheckThatIsNotAnActionsRunGoesByNameAndLink() {
        long taskId = delivered();
        checks = List.of(new Gh.Check("vercel", "fail", "https://vercel.com/acme/alm/deployments/9"));

        assertEquals(1, watch.pass());

        String instruction = row("SELECT instruction FROM run WHERE task_id = ? AND seq = 3", taskId).get("instruction");
        assertEquals("Failed checks:\n- vercel: https://vercel.com/acme/alm/deployments/9", instruction);
        assertFalse(asked.stream().anyMatch(question -> question.startsWith("log ")), "no Actions run, no log to ask for");
    }

    @Test
    void aLogThatCannotBeReadDoesNotStopTheFix() {
        long taskId = delivered();
        checks = List.of(check("test", "fail", 11));
        log = null;

        assertEquals(1, watch.pass());

        assertEquals("FIXING", watchRow(taskId).get("state"));
        assertFalse(row("SELECT instruction FROM run WHERE task_id = ? AND seq = 3", taskId).get("instruction").contains("<log>"));
    }

    @Test
    void aFixTheRequesterCancelledEndsTheWatch() {
        long taskId = delivered();
        checks = List.of(check("test", "fail", 11));
        watch.pass();
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Cancel(taskId)));

        assertEquals(1, watch.pass());

        assertEquals("STOPPED", watchRow(taskId).get("state"));
        assertEquals("ENDED", watchRow(taskId).get("reason"));
    }

    @Test
    void theRequestersTimelineSaysHowTheChecksStand() {
        long taskId = delivered();
        checks = List.of(check("test", "fail", 11));
        watch.pass();

        JsonNode ci = db.transactionReturning(tx -> tasks.timelinePayload(tx,
                new TaskAccess.Viewer(BOLD.ref(), Set.of("autoland-management")), taskId)).orElseThrow().path("ci");

        assertEquals("FIXING", ci.path("state").asText());
        assertEquals(1, ci.path("fixRounds").asInt());
        assertEquals("test", ci.path("check").asText());
    }

    private static Gh.Check check(String name, String bucket, int run) {
        return new Gh.Check(name, bucket, "https://github.com/acme/alm/actions/runs/" + run + "/job/" + (run * 10));
    }

    private Map<String, String> watchRow(long taskId) {
        return row("SELECT head_sha, state, reason, fix_rounds, checks_json FROM task_ci WHERE task_id = ?", taskId);
    }

    /** The payload of the one message of {@code kind}. */
    private JsonNode said(String kind) {
        return Json.read(row("SELECT payload FROM outbox WHERE kind = ?", kind).get("payload"));
    }

    /** The newest redraw of the task's result. */
    private JsonNode redrawn(long taskId) {
        return Json.read(row("""
                SELECT payload FROM outbox WHERE kind = 'TASK_COMPLETED' AND task_id = ? AND edit_of IS NOT NULL
                ORDER BY id DESC LIMIT 1""", taskId).get("payload"));
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
