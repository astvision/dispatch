package dispatch.core;

import dispatch.Log;
import dispatch.ProcessTrees;
import dispatch.domain.FailureReason;
import dispatch.domain.Phase;
import dispatch.domain.Run;
import dispatch.domain.RunStatus;
import dispatch.domain.Task;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import java.time.Duration;
import java.util.List;

/**
 * Startup cleanup after a crash (ADR 0008): runs still marked RUNNING belong to a previous process. Their agents are
 * killed if still alive, and the runs fail as interrupted so members can decide to retry. Queued runs are untouched.
 */
public final class Recovery {

    private final Database db;
    private final RunTransitions transitions;
    private final Duration killGrace;

    public Recovery(Database db, RunTransitions transitions, Duration killGrace) {
        this.db = db;
        this.transitions = transitions;
        this.killGrace = killGrace;
    }

    /** Must run before the scheduler starts, while no run of this process can be active. */
    public void run() {
        List<Run> interrupted = db.transactionReturning(tx -> Runs.withStatus(tx, RunStatus.RUNNING));
        for (Run run : interrupted) {
            if (run.pid() != null) {
                ProcessTrees.findSame(run.pid(), run.pidStart()).ifPresent(orphan -> {
                    Log.warn("recovery.orphan_killed", "task", run.taskId(), "run", run.seq(), "pid", orphan.pid());
                    ProcessTrees.terminate(orphan, killGrace);
                });
            }
            Phase phase = db.transactionReturning(tx -> Tasks.find(tx, run.taskId())).map(Task::phase).orElse(null);
            if (phase == Phase.CANCELLED) {
                transitions.cancelled(run.taskId(), run.seq(), null);
            } else {
                transitions.failed(run.taskId(), run.seq(), FailureReason.INTERRUPTED,
                        "Dispatch restarted while this run was active", null);
            }
        }
        Log.info("recovery.done", "interrupted_runs", interrupted.size());
    }
}
