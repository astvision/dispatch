package dispatch.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.domain.Phase;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.Task;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task's computer pin: {@link Tasks#workerOf} reads a nullable column and must read as "none", never throw
 * (regression for W-3 Task 8 fix round 1), and {@link Tasks#clearWorkerPin} releases it once revoked, but only from
 * unfinished work (W-3 Task 8 fix round 1).
 */
class TasksTest {

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
    void workerOfIsEmptyForAnUnassignedTaskAndDoesNotThrow() {
        long taskId = task();

        assertEquals(Optional.empty(), db.transactionReturning(tx -> Tasks.workerOf(tx, taskId)));
    }

    @Test
    void recordWorkerIsThenReturnedByWorkerOf() {
        long taskId = task();
        // task.worker_id references worker(id): a real worker row, not an arbitrary number.
        long workerId = db.transactionReturning(tx -> Workers.insert(tx, "telegram:1", "laptop", "a".repeat(64), T0));

        db.transaction(tx -> Tasks.recordWorker(tx, taskId, workerId, T0));

        assertEquals(Optional.of(workerId), db.transactionReturning(tx -> Tasks.workerOf(tx, taskId)));
    }

    @Test
    void clearWorkerPinRemovesItFromAnActiveTaskButLeavesAFinishedOneAlone() {
        long active = task();
        long finished = task();
        long workerId = db.transactionReturning(tx -> Workers.insert(tx, "telegram:1", "laptop", "a".repeat(64), T0));
        db.transaction(tx -> {
            Tasks.recordWorker(tx, active, workerId, T0);
            Tasks.recordWorker(tx, finished, workerId, T0);
            Tasks.changePhase(tx, finished, Phase.PLANNING, Phase.COMPLETED, T0);
        });

        db.transaction(tx -> Tasks.clearWorkerPin(tx, workerId, T0.plusSeconds(1)));

        assertEquals(Optional.empty(), db.transactionReturning(tx -> Tasks.workerOf(tx, active)), "unpinned: free for another computer");
        assertEquals(Optional.of(workerId), db.transactionReturning(tx -> Tasks.workerOf(tx, finished)), "finished work keeps its record");
    }

    @Test
    void aWorktreeOnAMembersComputerIsNeverThisMachinesToSweep() {
        long here = task();
        long there = task();
        long workerId = db.transactionReturning(tx -> Workers.insert(tx, "telegram:1", "laptop", "a".repeat(64), T0));
        db.transaction(tx -> {
            Tasks.recordWorktree(tx, here, "/var/lib/dispatch/worktrees/" + here, "abc", T0);
            Tasks.recordWorktree(tx, there, "C:\\Users\\Ann\\dispatch\\worktrees\\" + there, "abc", T0);
            Tasks.recordWorker(tx, there, workerId, T0);
            Tasks.changePhase(tx, here, Phase.PLANNING, Phase.COMPLETED, T0);
            Tasks.changePhase(tx, there, Phase.PLANNING, Phase.COMPLETED, T0);
        });

        List<Task> idle = db.transactionReturning(tx -> Tasks.finishedIdleWithWorktree(tx, T0.plusSeconds(60)));

        assertEquals(List.of(here), idle.stream().map(Task::id).toList(), "a member's computer sweeps its own (WorkerSweeper)");
    }

    private long task() {
        return db.transactionReturning(tx -> Tasks.insert(tx,
                new Tasks.NewTask("alm", "t", "t", new Requester("telegram:1", "Bold"), "telegram:-1/" + UUID.randomUUID(),
                        "telegram:-1", UUID.randomUUID(), "main", Priority.NORMAL),
                Phase.PLANNING, T0));
    }
}
