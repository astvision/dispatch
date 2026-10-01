package dispatch.agent.sandbox;

import java.util.List;

/**
 * What an agent keeps in the owner's home, relative to it (spec: agent state guard).
 *
 * @param dir         its own directory, e.g. ".claude"; null for a command with none
 * @param copyOnWrite whether the sandbox mounts {@code dir} copy-on-write where it can
 * @param persisted   under a copy-on-write {@code dir}: bound back read-write, so they survive the run
 * @param runCopies   files given a throwaway copy per run, e.g. ".claude.json"
 * @param loaders     what a later session of the agent loads as code or instructions: read-only when present, moved
 *                    to the quarantine after the run when it was absent before; unused while {@code dir} is copy-on-write
 */
public record AgentState(String dir, boolean copyOnWrite, List<String> persisted, List<String> runCopies, List<String> loaders) {

    /** A command with no agent state of its own, such as the verify loop's test command. */
    public static final AgentState NONE = new AgentState(null, false, List.of(), List.of(), List.of());

    public AgentState {
        persisted = List.copyOf(persisted);
        runCopies = List.copyOf(runCopies);
        loaders = List.copyOf(loaders);
    }
}
