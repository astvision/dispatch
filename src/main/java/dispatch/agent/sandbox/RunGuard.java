package dispatch.agent.sandbox;

import dispatch.Log;
import dispatch.agent.AgentStartException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What a sandboxed run leaves in the owner's home (spec: agent state guard). Before the run, the throwaway copies the
 * sandbox mounts are made; once its process has exited, however it ended, they are deleted, and every watched loader
 * path that now exists is moved to the run's quarantine. Moved, not deleted: the owner may have created it meanwhile.
 */
public final class RunGuard {

    /** An unsandboxed run, or a command with no agent state: nothing to make, nothing to check. */
    public static final RunGuard NONE = new RunGuard(List.of(), List.of(), null, null, null);

    private final List<SandboxPolicy.FileCopy> copies;
    private final List<Path> watched;
    private final Path home;
    private final Path quarantine;
    private final Path logBase;
    private final AtomicBoolean ended = new AtomicBoolean();

    private RunGuard(List<SandboxPolicy.FileCopy> copies, List<Path> watched, Path home, Path quarantine, Path logBase) {
        this.copies = List.copyOf(copies);
        this.watched = List.copyOf(watched);
        this.home = home;
        this.quarantine = quarantine;
        this.logBase = logBase;
    }

    /** Makes the copies; a copy that cannot be made fails the run before its agent starts. */
    static RunGuard start(SandboxPolicy policy, Path home, Path quarantine, Path logBase) {
        for (SandboxPolicy.FileCopy copy : policy.copies()) {
            try {
                Files.createDirectories(copy.copy().getParent());
                Files.copy(copy.original(), copy.copy(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new AgentStartException("cannot copy " + copy.original() + " for the run: " + e, e);
            }
        }
        return new RunGuard(policy.copies(), policy.watched(), home, quarantine, logBase);
    }

    /** Once, after the process exited; later calls do nothing. */
    public void end() {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        for (SandboxPolicy.FileCopy copy : copies) {
            try {
                Files.deleteIfExists(copy.copy());
            } catch (IOException e) {
                Log.warn("sandbox.copy_left", "path", copy.copy(), "error", e.toString());
            }
        }
        for (Path path : watched) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path target = quarantine.resolve(home.relativize(path).toString());
            try {
                Files.createDirectories(target.getParent());
                Files.move(path, target);
                Log.warn("sandbox.quarantined", "path", path, "to", target, "run", logBase);
            } catch (IOException e) {
                Log.error("sandbox.quarantine_failed", e, "path", path, "error", e.toString());
            }
        }
    }
}
