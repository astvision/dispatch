package dispatch.agent;

/** An AI coding tool Dispatch can drive; each implementation wraps one CLI. */
public interface Agent {

    /** Starts the agent process; throws {@link AgentStartException} if it cannot even start. */
    RunHandle start(RunRequest request);
}
