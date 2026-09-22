package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(List.of("Requested-by: Bold"), job.commitTrailers());
        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals("1", row("SELECT count(*) AS n FROM outbox WHERE kind = 'PLAN_READY'").get("n"));
    }

    @Test
    void whatTheWorkerReportsWhileItRunsIsRecordedAtOnce() {
        long id = queue("Fix the login timeout");
        Worker worker = (job, events, control) -> {
            events.worktreeCreated("/var/lib/dispatch/worktrees/" + id, "abc123");
            events.agentStarted(4242, Instant.parse("2026-09-17T10:00:01Z"));
            return JobResult.succeeded(agentResult(PLAN_JSON));
        };

        coordinator(projects(List.of(ALM)), worker).execute(claim());

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("/var/lib/dispatch/worktrees/" + id, task.get("worktree"));
        assertEquals("abc123", task.get("base_sha"));
        assertEquals("4242", row("SELECT pid FROM run WHERE task_id = ?", id).get("pid"));
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
            db.transaction(tx -> tasks.cancel(tx, BOLD, id, "telegram:100/9", "telegram:100"));
            return control.stopReason() == ActiveRuns.StopReason.CANCELLED
                    ? JobResult.cancelled(null)
                    : JobResult.succeeded(agentResult(PLAN_JSON));
        };

        coordinator(projects(List.of(ALM)), worker).execute(claim());

        assertEquals("CANCELLED", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals("CANCELLED", row("SELECT status FROM run WHERE task_id = ?", id).get("status"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'TASK_FAILED'").get("n"));
    }

    private Coordinator coordinator(Projects projects, Worker worker) {
        return new Coordinator(db, projects, new RunTransitions(db, clock, () -> { }), activeRuns,
                project -> new Config.RunLimits(Duration.ofMinutes(30), new BigDecimal("2")),
                project -> new Config.RunLimits(Duration.ofMinutes(60), new BigDecimal("10")), worker, () -> { });
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
        db.transaction(tx -> tasks.create(tx, BOLD, "alm", description, Priority.NORMAL, BOLD.ref() + "/" + System.nanoTime()));
        return Long.parseLong(row("SELECT max(id) AS id FROM task").get("id"));
    }

    private ClaimedRun claim() {
        return db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
