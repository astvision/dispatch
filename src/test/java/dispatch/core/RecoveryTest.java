package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.domain.ClaimedRun;
import dispatch.domain.Phase;
import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryTest {

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private final TestClock clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
    private RunTransitions transitions;
    private Recovery recovery;
    private Process orphan;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        transitions = new RunTransitions(db, clock, () -> { });
        recovery = new Recovery(db, transitions, Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        if (orphan != null) {
            orphan.destroyForcibly();
        }
        db.close();
    }

    @Test
    void orphanedAgentIsKilledAndItsRunFailsAsInterrupted() throws IOException, InterruptedException {
        ClaimedRun run = runningTask("alm");
        orphan = new ProcessBuilder("sleep", "300").start();
        transitions.recordProcess(run.taskId(), run.seq(), orphan.toHandle());

        recovery.run();

        assertTrue(orphan.waitFor(5, TimeUnit.SECONDS), "orphan must be killed");
        assertEquals("FAILED", row("SELECT status FROM run WHERE task_id = ?", run.taskId()).get("status"));
        assertEquals("INTERRUPTED", row("SELECT failure_reason FROM task WHERE id = ?", run.taskId()).get("failure_reason"));
        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'TASK_FAILED'").get("payload"));
        assertEquals("INTERRUPTED", payload.get("reason").asText());
    }

    @Test
    void reusedPidOfAnUnrelatedProcessIsLeftAlone() throws IOException {
        ClaimedRun run = runningTask("alm");
        orphan = new ProcessBuilder("sleep", "300").start();
        Instant realStart = orphan.toHandle().info().startInstant().orElseThrow();
        db.transaction(tx -> Runs.recordProcess(tx, run.taskId(), run.seq(), orphan.pid(), realStart.minusSeconds(60)));

        recovery.run();

        assertTrue(orphan.isAlive(), "a different process now owning the pid must not be killed");
        assertEquals("FAILED", row("SELECT status FROM run WHERE task_id = ?", run.taskId()).get("status"));
    }

    @Test
    void runOfATaskCancelledBeforeTheCrashEndsAsCancelled() {
        ClaimedRun run = runningTask("alm");
        db.transaction(tx -> Tasks.changePhase(tx, run.taskId(), Phase.PLANNING, Phase.CANCELLED, clock.instant()));

        recovery.run();

        assertEquals("CANCELLED", row("SELECT status FROM run WHERE task_id = ?", run.taskId()).get("status"));
        assertEquals("CANCELLED", row("SELECT phase FROM task WHERE id = ?", run.taskId()).get("phase"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox").get("n"));
    }

    @Test
    void queuedRunsAreLeftForTheScheduler() {
        long queued = task("crm");

        recovery.run();

        assertEquals("QUEUED", row("SELECT status FROM run WHERE task_id = ?", queued).get("status"));
        assertFalse(row("SELECT phase FROM task WHERE id = ?", queued).get("phase").equals("FAILED"));
    }

    private ClaimedRun runningTask(String project) {
        task(project);
        return db.transactionReturning(tx -> Runs.claimNext(tx, 10, clock.instant())).orElseThrow();
    }

    private long task(String project) {
        return db.transactionReturning(tx -> {
            long id = Tasks.insert(tx, new Tasks.NewTask(project, "t", "t", new Requester("telegram:1", "Bold"),
                    "telegram:-1/" + UUID.randomUUID(), "telegram:-1", UUID.randomUUID(), "main"), Phase.PLANNING, clock.instant());
            Runs.insert(tx, new Runs.NewRun(id, 1, RunKind.PLAN, "t", "telegram:1"), clock.instant());
            return id;
        });
    }

    private java.util.Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
