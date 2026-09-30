package dispatch.store;

import dispatch.domain.RunStep;
import java.time.Instant;
import java.util.List;

/** The steps of each run, written as they start and end (RM-1). */
public final class RunSteps {

    private RunSteps() {
    }

    public static void started(Tx tx, long taskId, int seq, int n, RunStep.Kind kind, int round, Instant at) {
        tx.update("INSERT INTO run_step (task_id, seq, n, kind, round, started_at) VALUES (?, ?, ?, ?, ?, ?)",
                taskId, seq, n, kind.name(), round, at);
    }

    /** A step that already ended keeps how it ended: only the first end counts. */
    public static void ended(Tx tx, long taskId, int seq, int n, RunStep.Outcome outcome, String detail, Instant at) {
        tx.update("UPDATE run_step SET ended_at = ?, outcome = ?, detail = ? WHERE task_id = ? AND seq = ? AND n = ? AND ended_at IS NULL",
                at, outcome.name(), detail, taskId, seq, n);
    }

    /** A team worker reports the run's steps whole: they replace what the store held for the run. */
    public static void replace(Tx tx, long taskId, int seq, List<RunStep> steps) {
        tx.update("DELETE FROM run_step WHERE task_id = ? AND seq = ?", taskId, seq);
        for (RunStep step : steps) {
            tx.update("""
                    INSERT INTO run_step (task_id, seq, n, kind, round, started_at, ended_at, outcome, detail)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    taskId, seq, step.n(), step.kind().name(), step.round(), step.startedAt(), step.endedAt(),
                    step.outcome() == null ? null : step.outcome().name(), step.detail());
        }
    }

    public static List<RunStep> of(Tx tx, long taskId, int seq) {
        return tx.list("SELECT n, kind, round, started_at, ended_at, outcome, detail FROM run_step WHERE task_id = ? AND seq = ? ORDER BY n",
                row -> new RunStep(row.intValue("n"), row.enumOrNull("kind", RunStep.Kind.class), row.intValue("round"),
                        row.instant("started_at"), row.instant("ended_at"), row.enumOrNull("outcome", RunStep.Outcome.class),
                        row.string("detail")),
                taskId, seq);
    }
}
