package dispatch.agent.sandbox;

import java.util.List;

/** Confines an agent process to what its run needs (spec: 2026-09-30-agent-sandbox-design). */
public interface Sandbox {

    /** "bubblewrap", or "none" when runs are not isolated; stored with each run. */
    String name();

    /** Why runs are not isolated on this machine; null when this sandbox isolates them. */
    String unavailableReason();

    /** The command line that runs {@code commandLine} inside the sandbox. */
    List<String> wrap(List<String> commandLine, SandboxPolicy policy);

    /** Whether this sandbox can mount a directory copy-on-write (spec: agent state guard); found once, at startup. */
    default boolean copyOnWrite() {
        return false;
    }
}
