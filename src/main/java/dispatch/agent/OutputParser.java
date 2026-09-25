package dispatch.agent;

/**
 * Reads one agent CLI's JSONL stdout, line by line, into its live activity and final result. Implementations must never
 * throw: they run on the thread that drains the agent's stdout.
 */
public interface OutputParser {

    void accept(String line);

    /** A snapshot of the agent's progress; safe to call from any thread while the run is active. */
    AgentActivity activity();

    /**
     * Non-null once the output shows the run cannot be trusted, so running on would only spend money: the run is cancelled
     * and this names why, as the suffix of the {@code agent.} log event (e.g. "wrong_permission_mode").
     */
    default String stopReason() {
        return null;
    }

    /** What the run produced, once its process exited and its stdout was read to the end. */
    AgentResult result(int exitCode, String stderrTail);
}
