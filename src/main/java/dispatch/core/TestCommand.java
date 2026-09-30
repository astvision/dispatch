package dispatch.core;

import dispatch.Log;
import dispatch.ProcessTrees;
import dispatch.agent.ProcessRun;
import dispatch.agent.RunRequest;
import dispatch.agent.sandbox.Confinement;
import dispatch.domain.RunKind;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The project's test command, in the worktree and inside this machine's sandbox like an agent (ADR 0032). The output
 * goes to a file, never a pipe: a child left running (a Gradle daemon) would keep a pipe open and hold the step.
 */
public final class TestCommand implements TestRunner {

    public static final int TAIL_BYTES = 4096;
    private static final Duration POLL = Duration.ofMillis(200);
    private static final Duration GRACE = Duration.ofSeconds(5);

    private final Confinement confinement;
    private final Map<String, String> environment;

    public TestCommand(Confinement confinement) {
        this(confinement, System.getenv());
    }

    /** @param environment what the command may see; Dispatch's own secrets are dropped, as for an agent (ADR 0009) */
    public TestCommand(Confinement confinement, Map<String, String> environment) {
        this.confinement = confinement;
        this.environment = ProcessRun.agentEnvironment(environment);
    }

    @Override
    public TestRun run(String commandLine, Path dir, Path log, Duration timeout, BooleanSupplier stopRequested) {
        List<String> shell = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")
                ? List.of("cmd", "/c", commandLine)
                : List.of("sh", "-c", commandLine);
        // Sandboxed like the execution itself: writable worktree and caches, nothing of the agent's own state.
        RunRequest asRun = new RunRequest(RunKind.EXECUTE, dir, "", null, false, List.of(), null, null, null,
                Path.of(log.toString().replaceFirst("\\.log$", "")));
        Process process;
        try {
            Files.createDirectories(log.getParent());
            ProcessBuilder builder = new ProcessBuilder(confinement.wrap(shell, asRun, List.of(), environment))
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            builder.environment().clear();
            builder.environment().putAll(environment);
            process = builder.start();
        } catch (IOException | RuntimeException e) {
            return new TestRun(-1, false, false, "cannot run the test command: " + e);
        }
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // the command reads no input, so a failed close changes nothing
        }
        Log.info("verify.test_started", "pid", process.pid(), "dir", dir);
        Instant deadline = Instant.now().plus(timeout);
        try {
            while (!process.waitFor(POLL.toMillis(), TimeUnit.MILLISECONDS)) {
                if (stopRequested.getAsBoolean()) {
                    ProcessTrees.terminate(process.toHandle(), GRACE);
                    return new TestRun(-1, false, true, tail(log));
                }
                if (Instant.now().isAfter(deadline)) {
                    ProcessTrees.terminate(process.toHandle(), GRACE);
                    return new TestRun(-1, true, false, tail(log) + "\n(stopped after " + timeout.toMinutes() + " min)");
                }
            }
        } catch (InterruptedException e) {
            ProcessTrees.terminate(process.toHandle(), GRACE);
            Thread.currentThread().interrupt();
            return new TestRun(-1, false, true, tail(log));
        }
        return new TestRun(process.exitValue(), false, false, tail(log));
    }

    /** The last {@link #TAIL_BYTES} bytes of the log, read from its end so a huge log is never loaded. */
    static String tail(Path log) {
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long start = Math.max(0, file.length() - TAIL_BYTES);
            byte[] bytes = new byte[(int) (file.length() - start)];
            file.seek(start);
            file.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(output unavailable: " + e.getMessage() + ")";
        }
    }
}
