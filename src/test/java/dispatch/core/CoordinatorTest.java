package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The store side of a run: what the Coordinator puts into a job, and what it does with the job's result. */
class CoordinatorTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Config.Project ALM = new Config.Project("alm", null, "git@github.com:acme/alm.git", "/home/bold/alm",
            "main", "claude-code", null, "high", List.of(".env"), null, new Config.PhaseSettings("opus", null),
            new Config.PhaseSettings(null, "low"));
    private static final String PLAN_JSON = new Plan("The login times out", List.of("AUTH_TIMEOUT_SECONDS is 5"),
            List.of("Raise the timeout"), List.of(), List.of()).toJson();

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private ActiveRuns activeRuns;
    private TaskService tasks;
    private final TestClock clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
    private final AtomicReference<Job> given = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        activeRuns = new ActiveRuns();
        Groups groups = new Groups(List.of(new Config.Group("backend", -100L, List.of(new Config.Member(100, "Bold")),
                List.of("alm"))));
        tasks = new TaskService(groups, projects(List.of(ALM)), activeRuns, clock, () -> { }, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aPlanJobCarriesEverythingTheWorkerNeedsAndItsResultBecomesThePlan() {
        long id = queue("Fix the login timeout");

        coordinator(projects(List.of(ALM)), remember(JobResult.succeeded(agentResult(PLAN_JSON)))).execute(claim());

        Job job = given.get();
        assertEquals(id, job.taskId());
        assertEquals(1, job.seq());
        assertEquals(RunKind.PLAN, job.kind());
        assertEquals("alm", job.project().name());
        assertEquals("/home/bold/alm", job.project().path(), "the worker is told where the clone is");
        assertEquals(List.of(".env"), job.project().copyFiles());
        assertEquals("main", job.baseBranch());
        assertNull(job.worktree(), "the first run makes it");
        assertEquals("opus", job.model(), "planning's own model");
        assertEquals("high", job.effort(), "the project's effort, as planning sets none of its own");
        assertEquals(Duration.ofMinutes(30).toMillis(), job.timeoutMillis());
        assertEquals(new BigDecimal("2"), job.budgetUsd());
        assertFalse(job.resume(), "no earlier planning run started the session");
        assertTrue(job.prompt().contains("Fix the login timeout"), job.prompt());
        assertEquals("dispatch #" + id + ": Fix the login timeout", job.commitSubject());
        assertEquals(List.of(), job.commitTrailers(), "a PLAN job never delivers, so it never pays for Runs.forTask");
        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals("1", row("SELECT count(*) AS n FROM outbox WHERE kind = 'PLAN_READY'").get("n"));
    }

    @Test
    void theJobCarriesTheInstancesBranchPrefixExceptForTheDefaultOne() {
        long id = queue("Fix the login timeout");

        coordinator(projects(List.of(ALM)), remember(JobResult.succeeded(agentResult(PLAN_JSON))), "dispatch/team").execute(claim());
        assertEquals("dispatch/team/" + id, given.get().branch(), "a named instance's own prefix travels with the job");

        queue("Rename the report");
        coordinator(projects(List.of(ALM)), remember(JobResult.succeeded(agentResult(PLAN_JSON))), null).execute(claim());
        assertNull(given.get().branch(), "no prefix: the field is left out so an older worker keeps parsing");

        queue("Log the retry count");
        coordinator(projects(List.of(ALM)), remember(JobResult.succeeded(agentResult(PLAN_JSON))), "dispatch").execute(claim());
        assertNull(given.get().branch(), "the default prefix is left out too, same as no prefix at all");
    }

    @Test
    void whatTheWorkerReportsWhileItRunsIsRecordedAtOnce() {
        long id = queue("Fix the login timeout");
        Worker worker = (job, events, control) -> {
            events.worktreeCreated("/var/lib/dispatch/worktrees/" + id, "abc123");
            events.agentStarted(4242L, Instant.parse("2026-09-17T10:00:01Z"));
            return JobResult.succeeded(agentResult(PLAN_JSON));
        };

        coordinator(projects(List.of(ALM)), worker).execute(claim());

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("/var/lib/dispatch/worktrees/" + id, task.get("worktree"));
        assertEquals("abc123", task.get("base_sha"));
        Map<String, String> run = row("SELECT pid, agent_started_at FROM run WHERE task_id = ?", id);
        assertEquals("4242", run.get("pid"));
        assertNotNull(run.get("agent_started_at"));
    }

    @Test
    void aWorkersWorktreeIsHandedBackExactlyAsItReportedIt() {
        // Each computer spells its worktree its own way; the team machine keeps the text. Read as a path here, a Windows
        // server would hand /home/ann/… back as \home\ann\…, and this one tidies the "//".
        long id = queue("Fix the login timeout");
        String reported = "/home/ann//work/alm-7";
        Worker planner = (job, events, control) -> {
            events.worktreeCreated(reported, "abc123");
            return JobResult.succeeded(agentResult(PLAN_JSON));
        };
        coordinator(projects(List.of(ALM)), planner).execute(claim());
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Approve(id, 1)));

        coordinator(projects(List.of(ALM)), remember(JobResult.failed(FailureReason.SETUP, "stops here", null))).execute(claim());

        assertEquals(reported, given.get().worktree());
    }

    @Test
    void aRemoteWorkersAgentIsRecordedAsStartedWithoutAProcess() {
        long id = queue("Fix the login timeout");
        Worker worker = (job, events, control) -> {
            events.agentStarted(null, null);
            return JobResult.failed(FailureReason.AGENT, "model overloaded", null);
        };

        coordinator(projects(List.of(ALM)), worker).execute(claim());

        Map<String, String> run = row("SELECT pid, pid_start, agent_started_at FROM run WHERE task_id = ?", id);
        assertNull(run.get("pid"), "the process is on the member's computer, not here");
        assertNull(run.get("pid_start"));
        assertNotNull(run.get("agent_started_at"), "the next run of this kind must know the session was started");
    }

    @Test
    void aProjectThatIsGoneFailsTheRunBeforeAnyWorkerSeesIt() {
        long id = queue("Fix the login timeout");

        coordinator(projects(List.of()), remember(JobResult.succeeded(agentResult(PLAN_JSON)))).execute(claim());

        assertNull(given.get(), "no job is given out for a project that is no longer configured");
        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("FAILED", task.get("phase"));
        assertEquals("SETUP", task.get("failure_reason"));
        assertEquals("project alm is no longer configured", task.get("failure_detail"));
    }

    @Test
    void aFailedJobResultFailsTheRunWithItsReasonAndDetail() {
        long id = queue("Fix the login timeout");

        coordinator(projects(List.of(ALM)), remember(JobResult.failed(FailureReason.DELIVERY, "git push failed", null)))
                .execute(claim());

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("FAILED", task.get("phase"));
        assertEquals("DELIVERY", task.get("failure_reason"));
        assertEquals("git push failed", task.get("failure_detail"));
        assertEquals("FAILED", row("SELECT status FROM run WHERE task_id = ?", id).get("status"));
    }

    @Test
    void aWorkerThatBreaksFailsTheRunAsInternal() {
        long id = queue("Fix the login timeout");
        Worker worker = (job, events, control) -> {
            throw new IllegalStateException("worker broke");
        };

        coordinator(projects(List.of(ALM)), worker).execute(claim());

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("FAILED", task.get("phase"));
        assertEquals("INTERNAL", task.get("failure_reason"));
        assertEquals("Dispatch error: worker broke", task.get("failure_detail"));
        assertTrue(activeRuns.activity(id).isEmpty(), "the run is unregistered whatever happened");
    }

    @Test
    void aCancelDuringTheJobReachesTheWorkersControlHandle() {
        long id = queue("Fix the login timeout");
        Worker worker = (job, events, control) -> {
            db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Cancel(id)));
            return control.stopReason() == ActiveRuns.StopReason.CANCELLED
                    ? JobResult.cancelled(null)
                    : JobResult.succeeded(agentResult(PLAN_JSON));
        };

        coordinator(projects(List.of(ALM)), worker).execute(claim());

        assertEquals("CANCELLED", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals("CANCELLED", row("SELECT status FROM run WHERE task_id = ?", id).get("status"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'TASK_FAILED'").get("n"));
    }

    /** Pins plan ruling 7: recordBuildSession happens while the job is built, before the worker can fail its setup. */
    @Test
    void aFirstExecuteThatFailsInSetupStillKeepsTheGeneratedBuildSessionId() {
        long id = queue("Fix the login timeout");
        coordinator(projects(List.of(ALM)), remember(JobResult.succeeded(agentResult(PLAN_JSON)))).execute(claim());
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Approve(id, 1)));

        coordinator(projects(List.of(ALM)), remember(JobResult.failed(FailureReason.SETUP, "the task has no worktree", null)))
                .execute(claim());

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("FAILED", task.get("phase"));
        assertEquals("SETUP", task.get("failure_reason"));
        assertNotNull(task.get("build_session_id"),
                "the session id generated for this first EXECUTE survives even though the worker never got to run");
    }

    @Test
    void anExecutionJobCarriesTheLoopTheTestCommandAndAReviewPrompt() {
        Job job = approvedExecutionJob(loopProject("on"));

        assertTrue(job.project().loopOn());
        assertEquals("./mvnw -q test", job.project().test());
        assertTrue(job.reviewPrompt().contains("<plan>"), job.reviewPrompt());
        assertFalse(job.reviewPrompt().contains("<instruction>"), "the first execution has no instruction beyond the plan");
        assertTrue(job.reviewPrompt().strip().endsWith("The change to review:"), job.reviewPrompt());
    }

    @Test
    void followUpReviewPromptCarriesTheInstruction() {
        long id = queue("Fix the login timeout");
        Projects loop = projects(List.of(loopProject("on")));
        coordinator(loop, remember(JobResult.succeeded(agentResult(PLAN_JSON)))).execute(claim());
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Approve(id, 1)));
        Worker executes = (job, events, control) -> {
            events.agentStarted(null, null);
            return new JobResult(JobResult.Outcome.SUCCEEDED, agentResult(null), List.of(), "https://github.com/acme/alm/pull/9",
                    null, null);
        };
        coordinator(loop, executes).execute(claim());
        db.transaction(tx -> tasks.commands().run(tx, BOLD,
                new TaskCommand.FollowUp(id, "Also log the timeout", new Origin("telegram:100/followup"))));

        coordinator(loop, remember(JobResult.succeeded(agentResult(null)))).execute(claim());

        assertTrue(given.get().reviewPrompt().contains("Also log the timeout"), given.get().reviewPrompt());
    }

    @Test
    void loopOffSendsNoReviewPrompt() {
        Job job = approvedExecutionJob(loopProject("off"));

        assertNull(job.reviewPrompt());
        assertFalse(job.project().loopOn());
    }

    @Test
    void aLoopOffExecutionJobAddsNothingAnOlderWorkerWouldRejectToItsJson() {
        String json = Json.write(approvedExecutionJob(ALM));

        assertFalse(json.contains("\"loop\""), json);
        assertFalse(json.contains("\"test\""), json);
        assertFalse(json.contains("\"reviewPrompt\""), json);
    }

    @Test
    void aLoopOffProjectWithATestCommandStillSendsNoTestToItsWorker() {
        String json = Json.write(approvedExecutionJob(loopProject("off")));

        assertFalse(json.contains("\"test\""), json);
        assertFalse(json.contains("\"loop\""), json);
    }

    @Test
    void theBranchsExpectedHeadStartsAtItsBaseAndFollowsEachDeliveryDispatchMade() {
        long id = queue("Fix the login timeout");
        coordinator(projects(List.of(ALM)), (job, events, control) -> {
            given.set(job);
            events.worktreeCreated("/w/" + id, "base000");
            return JobResult.succeeded(agentResult(PLAN_JSON));
        }).execute(claim());
        assertNull(given.get().expectedHead(), "a PLAN job checks nothing");
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Approve(id, 1)));

        coordinator(projects(List.of(ALM)), executes(JobResult.failed(FailureReason.DELIVERY, "git push failed", agentResult(null))
                .withHead("commit1"))).execute(claim());
        assertEquals("base000", given.get().expectedHead(), "the first execution builds on the branch's start");
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Retry(id)));

        coordinator(projects(List.of(ALM)), remember(JobResult.delivered(null, List.of("README.md"),
                "https://github.com/acme/alm/pull/9").withHead("commit2"))).execute(claim());
        assertEquals(RunKind.DELIVER, given.get().kind());
        assertEquals("commit1", given.get().expectedHead(), "the failed push left Dispatch's own commit on the branch");
        db.transaction(tx -> tasks.commands().run(tx, BOLD,
                new TaskCommand.FollowUp(id, "Also log the timeout", new Origin("telegram:100/followup"))));

        coordinator(projects(List.of(ALM)), remember(JobResult.failed(FailureReason.SETUP, "the task has no worktree", null)))
                .execute(claim());
        assertEquals("commit2", given.get().expectedHead(), "a follow-up builds on the delivered commit");
        assertEquals("commit2", row("SELECT head_sha FROM task WHERE id = ?", id).get("head_sha"),
                "a run that never reached its delivery leaves the branch where it was");
    }

    @Test
    void aDeliveryFromAWorkerThatDoesNotReportTheHeadEndsTheGuardForThatTask() {
        long id = queue("Fix the login timeout");
        coordinator(projects(List.of(ALM)), (job, events, control) -> {
            events.worktreeCreated("/w/" + id, "base000");
            return JobResult.succeeded(agentResult(PLAN_JSON));
        }).execute(claim());
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Approve(id, 1)));

        // A worker from before the guard: its branch moved to a commit this machine never hears of.
        coordinator(projects(List.of(ALM)), executes(JobResult.delivered(agentResult(null), List.of("README.md"),
                "https://github.com/acme/alm/pull/9"))).execute(claim());
        db.transaction(tx -> tasks.commands().run(tx, BOLD,
                new TaskCommand.FollowUp(id, "Also log the timeout", new Origin("telegram:100/followup"))));
        coordinator(projects(List.of(ALM)), remember(JobResult.succeeded(agentResult(null)))).execute(claim());

        assertNull(given.get().expectedHead(), "an unknown head is not checked, rather than refusing every later run");
    }

    private static Config.Project loopProject(String loop) {
        return new Config.Project("alm", null, "git@github.com:acme/alm.git", "/home/bold/alm", "main", "claude-code", null,
                "high", List.of(".env"), null, new Config.PhaseSettings("opus", null), new Config.PhaseSettings(null, "low"),
                "./mvnw -q test", loop);
    }

    /** Plans and approves a task, then returns the EXECUTE job the worker got. */
    private Job approvedExecutionJob(Config.Project project) {
        long id = queue("Fix the login timeout");
        Projects configured = projects(List.of(project));
        coordinator(configured, remember(JobResult.succeeded(agentResult(PLAN_JSON)))).execute(claim());
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Approve(id, 1)));
        coordinator(configured, remember(JobResult.succeeded(agentResult(null)))).execute(claim());
        return given.get();
    }

    private Coordinator coordinator(Projects projects, Worker worker) {
        return coordinator(projects, worker, null);
    }

    private Coordinator coordinator(Projects projects, Worker worker, String branchPrefix) {
        return new Coordinator(db, projects, new RunTransitions(db, clock, () -> { }), activeRuns,
                project -> new Config.RunLimits(Duration.ofMinutes(30), new BigDecimal("2")),
                project -> new Config.RunLimits(Duration.ofMinutes(60), new BigDecimal("10")), worker, () -> { }, branchPrefix);
    }

    /** As {@link #remember}, for an execution whose agent started, which a follow-up requires. */
    private Worker executes(JobResult result) {
        return (job, events, control) -> {
            given.set(job);
            events.agentStarted(null, null);
            return result;
        };
    }

    private Worker remember(JobResult result) {
        return (job, events, control) -> {
            given.set(job);
            return result;
        };
    }

    private static Projects projects(List<Config.Project> configured) {
        return new Projects(configured, project -> Optional.empty());
    }

    private static AgentResult agentResult(String structuredOutput) {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "fake-session", structuredOutput, "done", new BigDecimal("0.01"), 2,
                List.of(), null, "claude-sonnet-5", null);
    }

    private long queue(String description) {
        return assertInstanceOf(CommandResult.Created.class, db.transactionReturning(tx -> tasks.commands().run(tx, BOLD,
                new TaskCommand.Give("alm", description, Priority.NORMAL, new Origin(BOLD.ref() + "/" + System.nanoTime()))))).taskId();
    }

    private ClaimedRun claim() {
        return db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
