package dispatch.agent.sandbox;

import java.util.List;

/** A machine that cannot sandbox: runs go ahead as before, and the reason is shown wherever that matters. */
public record NoSandbox(String reason) implements Sandbox {

    @Override
    public String name() {
        return "none";
    }

    @Override
    public String unavailableReason() {
        return reason;
    }

    @Override
    public List<String> wrap(List<String> commandLine, SandboxPolicy policy) {
        return List.copyOf(commandLine);
    }
}
