package dispatch;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/** Stops whole process trees: agents and git spawn children that must not outlive them. */
public final class ProcessTrees {

    private ProcessTrees() {
    }

    /** SIGTERM to the process and its descendants, up to {@code grace} to exit, then SIGKILL for the rest. Blocks. */
    public static void terminate(ProcessHandle root, Duration grace) {
        // Snapshot first: once a parent dies its children are re-parented and no longer reachable as descendants.
        List<ProcessHandle> tree = snapshot(root);
        tree.forEach(ProcessHandle::destroy);
        waitForExit(tree, grace);
        Set<ProcessHandle> remaining = new LinkedHashSet<>();
        for (ProcessHandle process : tree) {
            if (process.isAlive()) {
                remaining.addAll(snapshot(process));
            }
        }
        remaining.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    /** The process with this pid, but only if it is the same process that was recorded (pids get reused). */
    public static Optional<ProcessHandle> findSame(long pid, Instant recordedStart) {
        if (recordedStart == null) {
            return Optional.empty();
        }
        return ProcessHandle.of(pid).filter(process -> process.info().startInstant()
                .map(start -> start.truncatedTo(ChronoUnit.MILLIS).equals(recordedStart.truncatedTo(ChronoUnit.MILLIS)))
                .orElse(false));
    }

    private static List<ProcessHandle> snapshot(ProcessHandle root) {
        return Stream.concat(Stream.of(root), root.descendants()).toList();
    }

    private static void waitForExit(List<ProcessHandle> processes, Duration grace) {
        Instant deadline = Instant.now().plus(grace);
        for (ProcessHandle process : processes) {
            long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
            if (remainingMillis <= 0) {
                return;
            }
            try {
                process.onExit().get(remainingMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                return;
            } catch (ExecutionException e) {
                // onExit never completes exceptionally for OS processes; treat it as "still running".
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
