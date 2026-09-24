package dispatch.store;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/** SQL for the answers to a plan's open questions (G-1d). */
public final class PlanAnswers {

    private PlanAnswers() {
    }

    /**
     * Stores the answer unless the question already has one.
     *
     * @param index      the question's 1-based position in the plan
     * @param messageRef the question message, redrawn with the answer
     * @return false when the question was already answered
     */
    public static boolean record(Tx tx, long taskId, int planSeq, int index, String answer, String answeredBy, String messageRef,
                                 Instant now) {
        return tx.update("""
                        INSERT INTO plan_answer (task_id, plan_seq, question_index, answer, answered_by, answered_at, message_ref)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (task_id, plan_seq, question_index) DO NOTHING""",
                taskId, planSeq, index, answer, answeredBy, now, messageRef) == 1;
    }

    /** The plan's answers by question index, in order. */
    public static Map<Integer, String> of(Tx tx, long taskId, int planSeq) {
        Map<Integer, String> answers = new TreeMap<>();
        tx.list("SELECT question_index, answer FROM plan_answer WHERE task_id = ? AND plan_seq = ?",
                        row -> Map.entry(row.intValue("question_index"), row.string("answer")), taskId, planSeq)
                .forEach(entry -> answers.put(entry.getKey(), entry.getValue()));
        return answers;
    }
}
