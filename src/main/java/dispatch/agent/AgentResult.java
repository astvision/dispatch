package dispatch.agent;

import java.math.BigDecimal;
import java.util.List;

/**
 * What an agent run produced once its process exited.
 *
 * @param sessionId        agent conversation id, null if the agent never reported one
 * @param structuredOutput raw JSON matching the requested schema, null if none was produced
 * @param summary          the agent's final message, null if it sent none
 * @param costUsd          reported spend, null if unknown
 * @param turns            reported conversation turns, null if unknown
 * @param denials          tool calls the agent was not allowed to make, as "Tool: input" summaries
 * @param error            human-readable failure detail, null on success
 * @param model            the model that answered, as the agent names it (several are joined with ", "); null if none did
 * @param requestedModel   the model the run asked for, only when an answer came from another one; null otherwise
 * @param sandbox          the sandbox the run ran in; null for results built without an agent process (tests, older workers)
 */
public record AgentResult(
        AgentOutcome outcome,
        int exitCode,
        String sessionId,
        String structuredOutput,
        String summary,
        BigDecimal costUsd,
        Integer turns,
        List<String> denials,
        String error,
        String model,
        String requestedModel,
        SandboxUse sandbox) {

    /** A result as each agent's parser builds it; {@link ProcessRun} adds the sandbox it ran in. */
    public AgentResult(AgentOutcome outcome, int exitCode, String sessionId, String structuredOutput, String summary,
                       BigDecimal costUsd, Integer turns, List<String> denials, String error, String model,
                       String requestedModel) {
        this(outcome, exitCode, sessionId, structuredOutput, summary, costUsd, turns, denials, error, model, requestedModel, null);
    }

    public AgentResult withSandbox(SandboxUse sandbox) {
        return new AgentResult(outcome, exitCode, sessionId, structuredOutput, summary, costUsd, turns, denials, error, model,
                requestedModel, sandbox);
    }
}
