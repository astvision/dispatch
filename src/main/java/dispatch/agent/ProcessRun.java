package dispatch.agent;

import dispatch.Log;
import dispatch.ProcessTrees;
import dispatch.agent.sandbox.AgentState;
import dispatch.agent.sandbox.Confinement;
import dispatch.agent.sandbox.RunGuard;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A running agent CLI: its stdout is copied line by line to the run log and fed to the agent's parser, its stderr goes to a
 * file whose tail explains a failure, and cancelling terminates its whole process tree. The same for every agent.
 */
public final class ProcessRun implements RunHandle {

    /** Dispatch's own secrets; the agent process never needs them (ADR 0009). */
    private static final Set<String> WITHHELD_VARIABLES = Set.of("TELEGRAM_BOT_TOKEN", "GH_TOKEN", "DISPATCH_WORKER_KEY");
    private static final int STDERR_TAIL_BYTES = 2000;

    /** {@code base} without Dispatch's own secrets: the one place that decides what a child of an agent run may see. */
    public static Map<String, String> agentEnvironment(Map<String, String> base) {
        Map<String, String> copy = new java.util.HashMap<>(base);
        WITHHELD_VARIABLES.forEach(copy::remove);
        return copy;
    }

    private final Process process;
    private final Path stderrLog;
    private final Duration cancelGrace;
    private final OutputParser parser;
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    /** Counted down once a cancel has ended the whole process tree and closed the guard. */
    private final CountDownLatch terminated = new CountDownLatch(1);
    private final Thread stdoutReader;
    private final Instant processStart;
    private final SandboxUse sandbox;
    private final RunGuard guard;

    private ProcessRun(Process process, OutputParser parser, Path stdoutLog, Path stderrLog, Duration cancelGrace,
                       SandboxUse sandbox, RunGuard guard) {
        this.process = process;
        this.guard = guard;
        this.parser = parser;
        this.stderrLog = stderrLog;
        this.cancelGrace = cancelGrace;
        this.sandbox = sandbox;
        // Read now: the OS stops reporting it once the process has exited.
        this.processStart = process.toHandle().info().startInstant().orElse(null);
        this.stdoutReader = Thread.ofVirtual().name("agent-stdout-" + process.pid()).start(() -> copyStdout(stdoutLog));
    }

    /**
     * Starts {@code commandLine}, inside {@code confinement}'s sandbox, in the run's workdir with {@code environment} plus the run's own variables, minus Dispatch's
     * secrets, and writes {@code prompt} to its stdin. The run's raw output goes to {@code <logBase>.jsonl} and
     * {@code <logBase>.stderr}.
     *
     * @param agent            names the agent in the log, e.g. "codex"
     * @param state            what the agent keeps in the owner's home; the sandbox guards it (spec: agent state guard)
     */
    public static ProcessRun start(String agent, List<String> commandLine, RunRequest request, Map<String, String> environment,
                                   String prompt, OutputParser parser, Duration cancelGrace, Confinement confinement,
                                   AgentState state) {
        ProcessBuilder builder = new ProcessBuilder().directory(request.workdir().toFile());
        builder.environment().clear();
        Map<String, String> merged = new java.util.HashMap<>(environment);
        merged.putAll(request.environment());
        builder.environment().putAll(agentEnvironment(merged));
        Confinement.Confined confined = confinement.prepare(commandLine, request, state, builder.environment());
        builder.command(confined.commandLine());
        Path stdoutLog = Path.of(request.logBase() + ".jsonl");
        Path stderrLog = Path.of(request.logBase() + ".stderr");
        Process process;
        try {
            Files.createDirectories(stdoutLog.getParent());
            builder.redirectError(stderrLog.toFile());
            process = builder.start();
        } catch (IOException e) {
            confined.guard().close();
            throw new AgentStartException("cannot start " + confined.commandLine().getFirst() + ": " + e.getMessage(), e);
        }
        Log.info("agent.started", "agent", agent, "pid", process.pid(), "kind", request.kind(),
                "workdir", request.workdir(), "resume", request.resume(),
                "sandbox", confinement.sandbox().name());
        // Before the prompt: until it has read the prompt, the agent cannot have exited, so its start time is still known.
        ProcessRun run = new ProcessRun(process, parser, stdoutLog, stderrLog, cancelGrace, confinement.use(), confined.guard());
        // Whoever ends the process (the agent, a cancel, the watchdog), the guard runs once it has exited.
        process.onExit().thenRun(run::guardAfterExit);
        writePrompt(process, prompt);
        return run;
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
        // Before the result: a caller that has it sees the whole sandbox gone and the owner's home already swept.
        if (cancelRequested.get()) {
            terminated.await();
        } else {
            guard.close();
        }
        return parser.result(exitCode, stderrTail()).withSandbox(sandbox);
    }

    @Override
    public AgentActivity activity() {
        return parser.activity();
    }

    @Override
    public void cancel() {
        if (cancelRequested.compareAndSet(false, true)) {
            Log.info("agent.cancelling", "pid", process.pid(), "grace_seconds", cancelGrace.toSeconds());
            Thread.ofVirtual().name("agent-cancel-" + process.pid()).start(() -> {
                try {
                    ProcessTrees.terminate(process.toHandle(), cancelGrace);
                    // Only now: until the processes inside the sandbox are dead, they can still plant a loader.
                    guard.close();
                } finally {
                    terminated.countDown();
                }
            });
        }
    }

    /**
     * Once the process has exited. A cancelled sandbox's outer process exits first while the processes inside it are
     * still being terminated: sweep now, and close once the cancel has ended them all.
     */
    private void guardAfterExit() {
        try {
            if (cancelRequested.get()) {
                guard.sweep();
            } else {
                guard.close();
            }
        } catch (RuntimeException e) {
            Log.error("sandbox.guard_failed", e, "pid", process.pid());
        }
    }

    /** On its own thread: a large prompt must not block while the process has not started reading stdin yet. */
    private static void writePrompt(Process process, String prompt) {
        Thread.ofVirtual().name("agent-stdin-" + process.pid()).start(() -> {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // Usually the agent exited before reading; its exit code and stderr explain why.
                Log.warn("agent.stdin_failed", "pid", process.pid(), "error", e.getMessage());
            }
        });
    }

    private void copyStdout(Path stdoutLog) {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter log = Files.newBufferedWriter(stdoutLog, StandardCharsets.UTF_8)) {
            String line;
            boolean stopping = false;
            while ((line = lines.readLine()) != null) {
                parser.accept(line);
                String stopReason = stopping ? null : parser.stopReason();
                if (stopReason != null) {
                    // Running on would only spend budget on a run whose result cannot be trusted.
                    stopping = true;
                    Log.warn("agent." + stopReason, "pid", process.pid());
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
