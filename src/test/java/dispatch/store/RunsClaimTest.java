package dispatch.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.domain.ClaimedRun;
import dispatch.domain.Phase;
import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.testing.SqlRows;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunsClaimTest {

    private static final Instant T0 = Instant.parse("2026-09-17T10:00:00Z");

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;

    @BeforeEach
    void open() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void oldestQueuedRunIsClaimedFirstAndMarkedRunning() {
        long newer = queue("alm", RunKind.PLAN, T0.plusSeconds(5));
        long older = queue("alm", RunKind.PLAN, T0);

        ClaimedRun claimed = claim(10, T0.plusSeconds(60)).orElseThrow();

        assertEquals(new ClaimedRun(older, 1, RunKind.PLAN), claimed);
        assertEquals("RUNNING", SqlRows.single(dbFile, "SELECT status FROM run WHERE task_id = ?", older).get("status"));
        assertEquals("2026-09-17T10:01:00.000Z", SqlRows.single(dbFile, "SELECT started_at FROM run WHERE task_id = ?", older).get("started_at"));
        assertEquals("2026-09-17T10:01:00.000Z", SqlRows.single(dbFile, "SELECT started_at FROM task WHERE id = ?", older).get("started_at"));
        assertEquals("QUEUED", SqlRows.single(dbFile, "SELECT status FROM run WHERE task_id = ?", newer).get("status"));
    }

    @Test
    void nothingIsClaimedOnceTheCapIsReached() {
        queue("alm", RunKind.PLAN, T0);
        queue("crm", RunKind.PLAN, T0.plusSeconds(1));
        claim(1, T0).orElseThrow();

        assertEquals(Optional.empty(), claim(1, T0));
        assertTrue(claim(2, T0).isPresent());
    }

    @Test
    void executionWaitsForItsProjectsRunningExecutionWhilePlansAndOtherProjectsProceed() {
        long runningExecution = queue("alm", RunKind.EXECUTE, T0);
        claim(10, T0).orElseThrow();
        long blockedExecution = queue("alm", RunKind.EXECUTE, T0.plusSeconds(1));
        long samePlan = queue("alm", RunKind.PLAN, T0.plusSeconds(2));
        long otherProject = queue("crm", RunKind.EXECUTE, T0.plusSeconds(3));

        assertEquals(samePlan, claim(10, T0).orElseThrow().taskId());
        assertEquals(otherProject, claim(10, T0).orElseThrow().taskId());
        assertEquals(Optional.empty(), claim(10, T0));

        db.transaction(tx -> tx.update("UPDATE run SET status = 'SUCCEEDED' WHERE task_id = ?", runningExecution));
        assertEquals(blockedExecution, claim(10, T0).orElseThrow().taskId());
    }

    private Optional<ClaimedRun> claim(int cap, Instant now) {
        return db.transactionReturning(tx -> Runs.claimNext(tx, cap, now));
    }

    private long queue(String project, RunKind kind, Instant queuedAt) {
        return db.transactionReturning(tx -> {
            long id = Tasks.insert(tx, new Tasks.NewTask(project, "t", "t", new Requester("telegram:1", "Bold"),
                    "telegram:-1/" + UUID.randomUUID(), "telegram:-1", UUID.randomUUID(), "main"), Phase.PLANNING, queuedAt);
            Runs.insert(tx, new Runs.NewRun(id, 1, kind, "t", "telegram:1"), queuedAt);
            return id;
        });
    }
}
