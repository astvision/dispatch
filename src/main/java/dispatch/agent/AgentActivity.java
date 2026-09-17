package dispatch.agent;

/**
 * What a running agent has done so far.
 *
 * @param steps      tool calls made
 * @param lastAction the latest one as "Tool: detail" on one line; null before the first
 */
public record AgentActivity(int steps, String lastAction) {
}
