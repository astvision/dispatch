package dispatch.agent.claude;

import dispatch.Log;
import dispatch.ProcessTrees;
import dispatch.agent.AgentActivity;
import dispatch.agent.AgentResult;
import dispatch.agent.RunHandle;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/** A running {@code claude -p} process: stdout is copied line by line to the run log and fed to the parser. */
final class ClaudeRun implements RunHandle {

    private static final int STDERR_TAIL_BYTES = 2000;

    private final Process process;
    private final Path stderrLog;
    private final Duration cancelGrace;
    private final StreamParser parser;
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final Thread stdoutReader;
    private final Instant processStart;

    ClaudeRun(Process process, String permissionMode, String requestedModel, Path workdir, Path stdoutLog, Path stderrLog,
              Duration cancelGrace) {
        this.process = process;
        this.parser = new StreamParser(permissionMode, requestedModel, workdir);
        this.stderrLog = stderrLog;
        this.cancelGrace = cancelGrace;
        // Read now: the OS stops reporting it once the process has exited.
        this.processStart = process.toHandle().info().startInstant().orElse(null);
        this.stdoutReader = Thread.ofVirtual().name("agent-stdout-" + process.pid()).start(() -> copyStdout(stdoutLog));
    }

    @Override
    public ProcessHandle process() {
        return process.toHandle();
    }

    @Override
    public Instant processStart() {
        return processStart;
    }

    @Override
    public AgentResult await() throws InterruptedException {
        int exitCode = process.waitFor();
        stdoutReader.join();
        return parser.result(exitCode, stderrTail());
    }

    @Override
    public AgentActivity activity() {
        return parser.activity();
    }

    @Override
    public void cancel() {
        if (cancelRequested.compareAndSet(false, true)) {
            Log.info("agent.cancelling", "pid", process.pid(), "grace_seconds", cancelGrace.toSeconds());
            Thread.ofVirtual().name("agent-cancel-" + process.pid())
                    .start(() -> ProcessTrees.terminate(process.toHandle(), cancelGrace));
        }
    }

    private void copyStdout(Path stdoutLog) {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter log = Files.newBufferedWriter(stdoutLog, StandardCharsets.UTF_8)) {
            String line;
            boolean stopping = false;
            while ((line = lines.readLine()) != null) {
                parser.accept(line);
                if (!stopping && parser.wrongPermissionMode()) {
                    // Running on would only spend budget on a run whose result cannot be trusted.
                    stopping = true;
                    Log.warn("agent.wrong_permission_mode", "pid", process.pid());
                    cancel();
                }
                log.write(line);
                log.newLine();
                log.flush();
            }
        } catch (IOException e) {
            // The parser keeps what it saw; a broken log file must not hide the run's result.
            Log.error("agent.stdout_failed", e, "pid", process.pid(), "log", stdoutLog);
        }
    }

    private String stderrTail() {
        try (RandomAccessFile file = new RandomAccessFile(stderrLog.toFile(), "r")) {
            long start = Math.max(0, file.length() - STDERR_TAIL_BYTES);
            byte[] tail = new byte[(int) (file.length() - start)];
            file.seek(start);
            file.readFully(tail);
            return new String(tail, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "(stderr unavailable: " + e.getMessage() + ")";
        }
    }
}
