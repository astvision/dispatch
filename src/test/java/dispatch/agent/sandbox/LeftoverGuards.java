package dispatch.agent.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** A run guard a crash left open, for the tests of what closes it when Dispatch starts again. */
public final class LeftoverGuards {

    private LeftoverGuards() {
    }

    /** Starts run 7-2's guard, with a throwaway copy of {@code home/.claude.json}, and never closes it; returns the copy. */
    public static Path leave(Path stateDir, Path home) throws IOException {
        Path original = Files.writeString(Files.createDirectories(home).resolve(".claude.json"), "{}");
        Path logBase = stateDir.resolve("runs/7/2");
        Path copy = Path.of(logBase + ".claude.json");
        SandboxPolicy policy = new SandboxPolicy(home, null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new SandboxPolicy.FileCopy(original, copy)), List.of(), List.of(), List.of());
        RunGuard.start(policy, home, stateDir, logBase);
        return copy;
    }
}
