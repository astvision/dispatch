package dispatch.agent;

import java.math.BigDecimal;
import java.util.List;

/**
 * What an agent run produced once its process exited.
 *
 * @param sessionId        agent conversation id, null if the agent never reported one
 * @param structuredOutput raw JSON matching the requested schema, null if none was produced
 * @param costUsd          reported spend, null if unknown
 * @param turns            reported conversation turns, null if unknown
 * @param denials          tool calls the agent was not allowed to make, as "Tool: input" summaries
 * @param error            human-readable failure detail, null on success
 */
public record AgentResult(
        AgentOutcome outcome,
        int exitCode,
        String sessionId,
        String structuredOutput,
        BigDecimal costUsd,
        Integer turns,
        List<String> denials,
        String error) {
}
