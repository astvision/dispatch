package dispatch.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.domain.Phase;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.RunCause;
import dispatch.domain.RunKind;
import java.nio.file.Path;
import java.time.Instant;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Runs#latestSucceededPlanSeq} is {@code MAX(seq)} over a possibly-empty set: SQLite always returns one row for
 * that aggregate, with {@code seq = NULL} when nothing matched. It must read as "none", never throw (regression for W-3
 * fix round 1).
 */
class RunsTest {

    private static final Instant T0 = Instant.parse("2026-09-23T10:00:00Z");

    @TempDir
    Path dir;

    private Database db;

    @BeforeEach
    void open() {
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void isEmptyForATaskWithNoSucceededPlanRun() {
        long taskId = task();

        assertEquals(OptionalInt.empty(), db.transactionReturning(tx -> Runs.latestSucceededPlanSeq(tx, taskId)));
    }

    @Test
    void findsTheHighestSucceededPlanSeq() {
        long taskId = task();
        db.transaction(tx -> {
            Runs.insert(tx, new Runs.NewRun(taskId, 1, RunKind.PLAN, RunCause.TASK, "t", new Requester("telegram:1", "Bold")), T0);
            tx.update("UPDATE run SET status = 'SUCCEEDED' WHERE task_id = ? AND seq = 1", taskId);
        });

        assertEquals(OptionalInt.of(1), db.transactionReturning(tx -> Runs.latestSucceededPlanSeq(tx, taskId)));
    }

    private long task() {
        return db.transactionReturning(tx -> Tasks.insert(tx,
                new Tasks.NewTask("alm", "t", "t", new Requester("telegram:1", "Bold"), "telegram:-1/" + UUID.randomUUID(),
                        "telegram:-1", UUID.randomUUID(), "main", Priority.NORMAL),
                Phase.PLANNING, T0));
    }
}
