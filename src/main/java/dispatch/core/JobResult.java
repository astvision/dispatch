package dispatch.core;

import dispatch.agent.AgentResult;
import dispatch.domain.FailureReason;
import java.util.List;

/**
 * How one job ended. The Coordinator turns it into exactly one transition, so it carries everything
 * {@link RunTransitions} needs. Plain JSON types only — W-3 sends this back over HTTP.
 *
 * @param agent         the agent's own result, null when no agent reported (a setup failure, a delivery run)
 * @param files         paths the delivery commit changed; empty unless the job delivered
 * @param prUrl         the task's pull request after the delivery, null when nothing has been delivered
 * @param failureReason null unless the outcome is FAILED
 * @param failureDetail null unless the outcome is FAILED
 */
public record JobResult(
        Outcome outcome,
        AgentResult agent,
        List<String> files,
        String prUrl,
        FailureReason failureReason,
        String failureDetail) {

    public enum Outcome {
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    public JobResult {
        files = files == null ? List.of() : List.copyOf(files);
    }

    /** An agent that finished its work; a planning run's plan is in {@code agent.structuredOutput()}. */
    public static JobResult succeeded(AgentResult agent) {
        return new JobResult(Outcome.SUCCEEDED, agent, List.of(), null, null, null);
    }

    /** @param agent null for a delivery run, which has no agent */
    public static JobResult delivered(AgentResult agent, List<String> files, String prUrl) {
        return new JobResult(Outcome.SUCCEEDED, agent, files, prUrl, null, null);
    }

    /** @param agent null when the run failed before its agent reported */
    public static JobResult failed(FailureReason reason, String detail, AgentResult agent) {
        return new JobResult(Outcome.FAILED, agent, List.of(), null, reason, detail);
    }

    /** @param agent null when the run was stopped before its agent reported */
    public static JobResult cancelled(AgentResult agent) {
        return new JobResult(Outcome.CANCELLED, agent, List.of(), null, null, null);
    }
}
