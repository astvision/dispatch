package dispatch.core;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * @param verification  what the verify loop found before the delivery; null when the loop did not run
 */
public record JobResult(
        Outcome outcome,
        AgentResult agent,
        List<String> files,
        String prUrl,
        FailureReason failureReason,
        String failureDetail,
        @JsonInclude(JsonInclude.Include.NON_NULL) Verification verification) {

    public enum Outcome {
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    public JobResult {
        files = files == null ? List.of() : List.copyOf(files);
    }

    /** Without the verify loop: no verification. */
    public JobResult(Outcome outcome, AgentResult agent, List<String> files, String prUrl, FailureReason failureReason,
                     String failureDetail) {
        this(outcome, agent, files, prUrl, failureReason, failureDetail, null);
    }

    /** An agent that finished its work; a planning run's plan is in {@code agent.structuredOutput()}. */
    public static JobResult succeeded(AgentResult agent) {
        return new JobResult(Outcome.SUCCEEDED, agent, List.of(), null, null, null);
    }

    /** @param agent null for a delivery run, which has no agent */
    public static JobResult delivered(AgentResult agent, List<String> files, String prUrl) {
        return delivered(agent, files, prUrl, null);
    }

    /** @param verification null when the verify loop did not run */
    public static JobResult delivered(AgentResult agent, List<String> files, String prUrl, Verification verification) {
        return new JobResult(Outcome.SUCCEEDED, agent, files, prUrl, null, null, verification);
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
