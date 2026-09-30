package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.sandbox.Confinement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the commands are POSIX shell")
class TestCommandTest {

    @TempDir
    Path dir;
    private final TestCommand tests = new TestCommand(Confinement.none("test"));

    @Test
    void aPassingCommandPasses() throws Exception {
        TestRunner.TestRun run = tests.run("echo all good", dir, dir.resolve("t.log"), Duration.ofSeconds(10), () -> false);

        assertTrue(run.passed());
        assertEquals("all good", run.tail().strip());
        assertEquals("all good", Files.readString(dir.resolve("t.log")).strip());
    }

    @Test
    void aFailingCommandKeepsItsExitCodeAndOutput() {
        TestRunner.TestRun run = tests.run("echo boom >&2; exit 3", dir, dir.resolve("t.log"), Duration.ofSeconds(10), () -> false);

        assertFalse(run.passed());
        assertEquals(3, run.exitCode());
        assertTrue(run.tail().contains("boom"), run.tail());
    }

    @Test
    void hugeOutputKeepsOnlyTheTail() {
        TestRunner.TestRun run = tests.run("yes line | head -n 200000; echo LAST", dir, dir.resolve("t.log"),
                Duration.ofSeconds(30), () -> false);

        assertTrue(run.passed());
        assertTrue(run.tail().length() <= TestCommand.TAIL_BYTES, "tail " + run.tail().length());
        assertTrue(run.tail().strip().endsWith("LAST"));
    }

    @Test
    void aTimeoutEndsTheCommandAndItsChildren() {
        Instant start = Instant.now();

        TestRunner.TestRun run = tests.run("sleep 300 & sleep 300", dir, dir.resolve("t.log"), Duration.ofSeconds(1), () -> false);

        assertTrue(run.timedOut());
        assertFalse(run.passed());
        assertTrue(Duration.between(start, Instant.now()).toSeconds() < 20);
    }

    @Test
    void aStopRequestEndsTheTestTree() {
        AtomicBoolean stop = new AtomicBoolean();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            stop.set(true);
        });
        Instant start = Instant.now();

        TestRunner.TestRun run = tests.run("sleep 300", dir, dir.resolve("t.log"), Duration.ofMinutes(5), stop::get);

        assertTrue(run.stopped());
        assertTrue(Duration.between(start, Instant.now()).toSeconds() < 15);
    }

    @Test
    void aLeftoverChildDoesNotHangTheStep() {
        Instant start = Instant.now();

        // The shell exits at once; its background child keeps running (like a Gradle daemon) and must not hold the step.
        TestRunner.TestRun run = tests.run("(sleep 30 &) ; echo done", dir, dir.resolve("t.log"), Duration.ofMinutes(1), () -> false);

        assertTrue(run.passed());
        assertTrue(Duration.between(start, Instant.now()).toSeconds() < 10);
    }

    @Test
    void aCommandThatCannotStartFailsWithTheReason() {
        TestRunner.TestRun run = tests.run("echo x", dir.resolve("missing-dir"), dir.resolve("t.log"), Duration.ofSeconds(5), () -> false);

        assertFalse(run.passed());
        assertTrue(run.tail().contains("cannot run"), run.tail());
    }
}
