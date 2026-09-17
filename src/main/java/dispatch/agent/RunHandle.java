package dispatch.agent;

import java.time.Instant;

/** A started agent process. */
public interface RunHandle {

    /** The agent's OS process; its pid and start time identify orphans after a crash. */
    ProcessHandle process();

    /** When the agent's process started, or null if the OS does not say. */
    default Instant processStart() {
        return process().info().startInstant().orElse(null);
    }

    /** Blocks until the process has exited and its output is fully read. */
    AgentResult await() throws InterruptedException;

    /** Starts terminating the process tree (SIGTERM, grace period, SIGKILL) without waiting for it. */
    void cancel();

    /** A snapshot of the agent's progress; safe to call from any thread while the run is active. */
    AgentActivity activity();
}
