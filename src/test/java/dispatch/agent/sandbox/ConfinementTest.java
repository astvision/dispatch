package dispatch.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.agent.RunRequest;
import dispatch.agent.SandboxUse;
import dispatch.domain.RunKind;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfinementTest {

    @TempDir
    Path root;

    @Test
    void anIsolatingSandboxWrapsTheCommandWithTheRunsPolicy() {
        Confinement confinement = new Confinement(new Bubblewrap("bwrap"), new SandboxPolicies(root, root.resolve("state"), List.of()));

        List<String> wrapped = confinement.prepare(List.of("claude", "-p"), request(root), AgentState.NONE, java.util.Map.of()).commandLine();

        assertEquals("bwrap", wrapped.getFirst());
        assertEquals(List.of("--", "/bin/sh", "-c", Bubblewrap.END_WITH_COMMAND, "sh", "claude", "-p"),
                wrapped.subList(wrapped.size() - 7, wrapped.size()));
        assertEquals(new SandboxUse("bubblewrap", null), confinement.use());
    }

    @Test
    void noSandboxLeavesTheCommandAloneAndNeedsNoPolicies() {
        Confinement confinement = Confinement.none("bubblewrap (bwrap) is not installed");

        assertEquals(List.of("claude", "-p"), confinement.prepare(List.of("claude", "-p"), request(root), AgentState.NONE, java.util.Map.of()).commandLine());
        assertEquals(new SandboxUse("none", "bubblewrap (bwrap) is not installed"), confinement.use());
    }

    private static RunRequest request(Path workdir) {
        return new RunRequest(RunKind.PLAN, workdir, "prompt", UUID.randomUUID(), false, List.of(), null, null, null,
                workdir.resolve("run"));
    }

    @Test
    void anIsolatingSandboxWithoutPoliciesIsRefusedAtConstruction() {
        assertThrows(NullPointerException.class, () -> new Confinement(new Bubblewrap("bwrap"), null));
        Confinement.none("x");
    }

    @Test
    void theAgentEnvironmentsRuntimeDirIsHidden() throws Exception {
        Path runtime = java.nio.file.Files.createDirectory(root.resolve("run-user"));
        Confinement confinement = new Confinement(new Bubblewrap("bwrap"), new SandboxPolicies(root, root.resolve("state"), List.of()));

        List<String> wrapped = confinement.wrap(List.of("claude"), request(root),
                java.util.Map.of("XDG_RUNTIME_DIR", runtime.toString()));

        int at = wrapped.indexOf(runtime.toString());
        assertEquals("--tmpfs", wrapped.get(at - 1), wrapped.toString());
    }
}
