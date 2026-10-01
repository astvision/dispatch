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

    /** The command line inside the sandbox, and what to make and check around the process (spec: agent state guard). */
    public record Confined(List<String> commandLine, RunGuard guard) {
    }

    /** @param environment the agent process's environment, whose XDG_RUNTIME_DIR the sandbox hides */
    public Confined prepare(List<String> commandLine, RunRequest request, AgentState state, java.util.Map<String, String> environment) {
        if (sandbox.unavailableReason() != null) {
            return new Confined(List.copyOf(commandLine), RunGuard.NONE);
        }
        SandboxPolicy policy = policies.forRun(request, state, environment, sandbox.copyOnWrite());
        RunGuard guard = RunGuard.start(policy, policies.home(), policies.quarantineFor(request.logBase()), request.logBase());
        return new Confined(sandbox.wrap(commandLine, policy), guard);
    }

    /** For a command with no agent state of its own, such as the verify loop's tests: nothing to guard. */
    public List<String> wrap(List<String> commandLine, RunRequest request, java.util.Map<String, String> environment) {
        return prepare(commandLine, request, AgentState.NONE, environment).commandLine();
    }

    public SandboxUse use() {
        return new SandboxUse(sandbox.name(), sandbox.unavailableReason());
    }
}
