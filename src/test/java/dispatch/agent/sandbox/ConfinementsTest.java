package dispatch.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.RunRequest;
import dispatch.domain.RunKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfinementsTest {

    @TempDir
    Path root;

    @Test
    void aRunHidesItsOwnAndEverySiblingInstancesConfigStateAndClones() throws Exception {
        Path configDir = Files.createDirectory(root.resolve("config"));
        Path state = Files.createDirectory(root.resolve("state"));
        Path clone = Files.createDirectory(root.resolve("clone"));
        Path siblingState = Files.createDirectory(root.resolve("sibling-state"));
        Path siblingClone = Files.createDirectory(root.resolve("sibling-clone"));
        Path workdir = Files.createDirectory(root.resolve("work"));

        Confinement confinement = Confinements.of(new Bubblewrap("bwrap"), state, configDir.resolve("dispatch.yaml"),
                List.of(clone), List.of(siblingState, siblingClone));

        List<Path> hidden = confinement.policies().forRun(new RunRequest(RunKind.PLAN, workdir, "prompt", UUID.randomUUID(),
                false, List.of(), null, null, null, workdir.resolve("run")), List.of()).hidden();
        for (Path expected : List.of(configDir, state, clone, siblingState, siblingClone)) {
            assertTrue(hidden.contains(expected.toAbsolutePath()), expected + " in " + hidden);
        }
    }
}
