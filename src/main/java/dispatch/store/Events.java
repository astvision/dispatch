package dispatch.store;

import dispatch.domain.Phase;
import java.time.Instant;

/** SQL for the append-only task_event audit trail. */
public final class Events {

    private Events() {
    }

    /**
     * @param runSeq the run that caused the change, null for member actions
     * @param from   null when the task is created
     */
    public static void record(Tx tx, long taskId, Integer runSeq, String actor, Phase from, Phase to, String reason, Instant now) {
        tx.update("""
                        INSERT INTO task_event (task_id, run_seq, at, actor, from_phase, to_phase, reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""",
                taskId, runSeq, now, actor, from, to, reason);
    }
}
