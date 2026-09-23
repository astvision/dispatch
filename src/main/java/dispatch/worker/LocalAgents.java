package dispatch.worker;

import dispatch.Log;
import dispatch.OwnerOnly;
import dispatch.ProcessTrees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * The agent processes this computer started, one small file per run. The team machine cannot clean them up — the process
 * is here — so a worker that was killed mid-run kills what it left behind the next time it starts (ADR 0008).
 */
public final class LocalAgents {

    private final Path dir;

    public LocalAgents(Path stateDir) {
        this.dir = stateDir.resolve("agents");
    }

    public void record(long taskId, int seq, long pid, Instant processStart) {
        try {
            OwnerOnly.createDirectories(dir);
            Files.writeString(file(taskId, seq), pid + " " + processStart);
        } catch (IOException e) {
            // Losing the note only costs an orphan check after a crash; the run itself is fine.
            Log.warn("worker.agent_not_recorded", "task", taskId, "run", seq, "error", e.getMessage());
        }
    }

    public void forget(long taskId, int seq) {
        try {
            Files.deleteIfExists(file(taskId, seq));
        } catch (IOException e) {
            Log.warn("worker.agent_note_kept", "task", taskId, "run", seq, "error", e.getMessage());
        }
    }

    /** Must run before the loop takes its first job, while no agent of this process exists. */
    public void killOrphans(Duration grace) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var notes = Files.list(dir)) {
            notes.forEach(note -> {
                try {
                    String[] pidAndStart = Files.readString(note).strip().split(" ", 2);
                    ProcessTrees.findSame(Long.parseLong(pidAndStart[0]), Instant.parse(pidAndStart[1]))
                            .ifPresent(orphan -> {
                                Log.warn("worker.orphan_killed", "note", note.getFileName(), "pid", orphan.pid());
                                ProcessTrees.terminate(orphan, grace);
                            });
                    Files.deleteIfExists(note);
                } catch (IOException | RuntimeException e) {
                    Log.warn("worker.orphan_check_failed", "note", note.getFileName(), "error", e.getMessage());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(long taskId, int seq) {
        return dir.resolve(taskId + "." + seq);
    }
}
