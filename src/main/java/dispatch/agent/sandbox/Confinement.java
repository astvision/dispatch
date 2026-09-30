package dispatch.agent.sandbox;

import dispatch.agent.RunRequest;
import dispatch.agent.SandboxUse;
import java.util.List;

/** This machine's sandbox and what each run may touch in it; one per process, shared by every agent. */
public record Confinement(Sandbox sandbox, SandboxPolicies policies) {

    public Confinement {
        java.util.Objects.requireNonNull(sandbox, "sandbox");
        if (sandbox.unavailableReason() == null) {
            java.util.Objects.requireNonNull(policies, "an isolating sandbox needs the policies of its runs");
        }
    }

    /** No sandbox, for a reason every run records; needs no policies. */
    public static Confinement none(String reason) {
        return new Confinement(new NoSandbox(reason), null);
    }

    public List<String> wrap(List<String> commandLine, RunRequest request, List<String> agentStateInHome) {
        if (sandbox.unavailableReason() != null) {
            return List.copyOf(commandLine);
        }
        return sandbox.wrap(commandLine, policies.forRun(request, agentStateInHome));
    }

    public SandboxUse use() {
        return new SandboxUse(sandbox.name(), sandbox.unavailableReason());
    }
}
