package dispatch.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Runs a project's test command for the verify loop; a fake stands in for it in VerifyLoop's tests. */
public interface TestRunner {

    /**
     * @param log           where the whole output goes
     * @param stopRequested polled while the command runs; true ends the command's whole process tree
     * @param started       called once, right after the command's process starts, so it can be recorded for orphan kill
     */
    TestRun run(String commandLine, Path dir, Path log, Duration timeout, BooleanSupplier stopRequested,
                Consumer<ProcessHandle> started);

    /** @param tail the output's last {@link TestCommand#TAIL_BYTES} bytes, or why the command could not run */
    record TestRun(int exitCode, boolean timedOut, boolean stopped, String tail) {

        public boolean passed() {
            return exitCode == 0 && !timedOut && !stopped;
        }
    }
}
