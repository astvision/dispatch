package dispatch.agent.sandbox;

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

        List<String> wrapped = confinement.wrap(List.of("claude", "-p"), request(root), List.of(".claude"));

        assertEquals("bwrap", wrapped.getFirst());
        assertEquals(List.of("--", "claude", "-p"), wrapped.subList(wrapped.size() - 3, wrapped.size()));
        assertEquals(new SandboxUse("bubblewrap", null), confinement.use());
    }

    @Test
    void noSandboxLeavesTheCommandAloneAndNeedsNoPolicies() {
        Confinement confinement = Confinement.none("bubblewrap (bwrap) is not installed");

        assertEquals(List.of("claude", "-p"), confinement.wrap(List.of("claude", "-p"), request(root), List.of(".claude")));
        assertEquals(new SandboxUse("none", "bubblewrap (bwrap) is not installed"), confinement.use());
    }

    private static RunRequest request(Path workdir) {
        return new RunRequest(RunKind.PLAN, workdir, "prompt", UUID.randomUUID(), false, List.of(), null, null, null,
                workdir.resolve("run"));
    }
}
