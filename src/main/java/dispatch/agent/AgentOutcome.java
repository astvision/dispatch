package dispatch.agent;

/** How an agent process ended, as reported by the agent itself. */
public enum AgentOutcome {
    SUCCEEDED,
    FAILED,
    BUDGET_EXCEEDED
}
