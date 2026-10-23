package dispatch.core;

import dispatch.domain.FailureReason;
import dispatch.domain.Run;
import dispatch.domain.RunCause;
import dispatch.domain.RunKind;
import java.util.function.Supplier;

/**
 * The run that repeats a step which did not finish. A member's /retry of a failed task (ADR 0008) and a usage limit's
 * re-queue (ADR 0040) both ask here, so the two cannot drift apart.
 */
record Repeat(RunKind kind, RunCause cause, String instruction) {

    /** @param output the step's summary, read only when just its delivery is repeated */
    static Repeat of(Run step, Supplier<String> output) {
        if (step.kind() == RunKind.PLAN || step.kind() == RunKind.REPORT) {
            return new Repeat(step.kind(), RunCause.RETRY, step.instruction());
        }
        if (step.kind() == RunKind.DELIVER || step.failureReason() == FailureReason.DELIVERY) {
            // The agent's work is done and waits in the worktree; only its summary is needed, as the commit message.
            return new Repeat(RunKind.DELIVER, RunCause.RETRY, step.kind() == RunKind.DELIVER ? step.instruction() : output.get());
        }
        // A repeated CI fix is still one: its instruction is a failed log, which only the fix prompt quotes as output to read,
        // and its delivery keeps the watch's fix count.
        return new Repeat(RunKind.EXECUTE, step.cause() == RunCause.CI_FIX ? RunCause.CI_FIX : RunCause.RETRY, step.instruction());
    }
}
