package dispatch.agent;

/**
 * The sandbox a run ran in, as the machine that ran it reports it.
 *
 * @param name              "bubblewrap", or "none"
 * @param unsandboxedReason why the run was not isolated; null when it was
 */
public record SandboxUse(String name, String unsandboxedReason) {
}
