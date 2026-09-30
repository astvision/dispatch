package dispatch.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.domain.Phase;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.RunCause;
import dispatch.domain.RunKind;
import dispatch.domain.RunStep;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunStepsTest {

    private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");

    @TempDir
    Path dir;

    private Database db;
    private long taskId;

    @BeforeEach
    void open() {
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
        taskId = db.transactionReturning(tx -> {
            long id = Tasks.insert(tx, new Tasks.NewTask("alm", "t", "t", new Requester("telegram:1", "Bold"),
                    "telegram:-1/" + UUID.randomUUID(), "telegram:-1", UUID.randomUUID(), "main", Priority.NORMAL), Phase.EXECUTING, T0);
            Runs.insert(tx, new Runs.NewRun(id, 2, RunKind.EXECUTE, RunCause.TASK, "t", new Requester("telegram:1", "Bold")), T0);
            return id;
        });
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void aStepIsReadBackRunningThenEndedAndOnlyItsFirstEndCounts() {
        db.transaction(tx -> RunSteps.started(tx, taskId, 2, 1, RunStep.Kind.TEST, 1, T0));
        assertEquals(List.of(new RunStep(1, RunStep.Kind.TEST, 1, T0, null, null, null)),
                db.transactionReturning(tx -> RunSteps.of(tx, taskId, 2)));

        db.transaction(tx -> {
            RunSteps.ended(tx, taskId, 2, 1, RunStep.Outcome.FAILED, "{\"tail\":\"1 failed\"}", T0.plusSeconds(45));
            RunSteps.ended(tx, taskId, 2, 1, RunStep.Outcome.STOPPED, null, T0.plusSeconds(50));
        });

        assertEquals(List.of(new RunStep(1, RunStep.Kind.TEST, 1, T0, T0.plusSeconds(45), RunStep.Outcome.FAILED, "{\"tail\":\"1 failed\"}")),
                db.transactionReturning(tx -> RunSteps.of(tx, taskId, 2)));
    }

    @Test
    void aWorkersReportReplacesTheRunsSteps() {
        db.transaction(tx -> RunSteps.started(tx, taskId, 2, 1, RunStep.Kind.IMPLEMENT, 1, T0));
        List<RunStep> reported = List.of(
                new RunStep(1, RunStep.Kind.IMPLEMENT, 1, T0, T0.plusSeconds(240), RunStep.Outcome.DONE, null),
                new RunStep(2, RunStep.Kind.TEST, 1, T0.plusSeconds(240), null, null, null));

        db.transaction(tx -> RunSteps.replace(tx, taskId, 2, reported));

        assertEquals(reported, db.transactionReturning(tx -> RunSteps.of(tx, taskId, 2)));
    }
}
