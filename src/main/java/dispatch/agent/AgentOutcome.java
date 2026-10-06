package dispatch.agent;

/** How an agent process ended, as reported by the agent itself. */
public enum AgentOutcome {
    SUCCEEDED,
    FAILED,
    BUDGET_EXCEEDED,
    /** Claude's usage limit cut the run short: it did not finish, and nothing of it is an error (spec: usage limit). */
    LIMITED
}
