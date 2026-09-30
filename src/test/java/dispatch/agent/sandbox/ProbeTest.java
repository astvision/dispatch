package dispatch.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

@DisabledOnOs(value = OS.WINDOWS, disabledReason = "uses POSIX true")
class ProbeTest {

    @Test
    void trueCommandSucceeds() {
        Probe.Trial trial = Probe.system(System.getenv()).trial(List.of("true"));

        assertEquals(0, trial.exitCode());
    }

    @Test
    void nonexistentCommandFailsWithNonNullOutput() {
        Probe.Trial trial = Probe.system(System.getenv()).trial(List.of("/no/such/command-xyz"));

        assertEquals(-1, trial.exitCode());
        assertNotNull(trial.output());
        assertTrue(trial.output().contains("IOException"), "output should mention IOException but was: " + trial.output());
    }

    @Test
    void findsShCommandOnPath() {
        Probe probe = Probe.system(Map.of("PATH", "/usr/bin:/bin"));
        Optional<java.nio.file.Path> sh = probe.find("sh");

        assertTrue(sh.isPresent(), "sh should be found in /usr/bin or /bin");
    }
}
