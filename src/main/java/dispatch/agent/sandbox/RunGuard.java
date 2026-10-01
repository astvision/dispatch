package dispatch.agent.sandbox;

import dispatch.Json;
import dispatch.Log;
import dispatch.OwnerOnly;
import dispatch.agent.AgentStartException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * What a sandboxed run leaves in the owner's home (spec: agent state guard). Before the run, the dirs and throwaway
 * copies the sandbox mounts are made, owner-only, and recorded in a manifest. After the process exits, every watched
 * loader path that now exists is moved to the run's quarantine: moved, not deleted, since the owner may have created it
 * meanwhile. Once the whole sandbox is gone the guard closes: a last sweep, then the copies and the manifest go. A
 * manifest a crash left behind is closed at the next start.
 */
public final class RunGuard {

    /** An unsandboxed run, or a command with no agent state: nothing to make, nothing to check. */
    public static final RunGuard NONE = new RunGuard(List.of(), List.of(), null, null, null, null);

    /** What a guard must still undo, on disk from before its run starts until it closes. */
    record Manifest(String home, String quarantine, String logBase, List<String> copies, List<String> watched) {
    }

    private final List<Path> copies;
    private final List<Path> watched;
    private final Path home;
    private final Path quarantine;
    private final Path logBase;
    private final Path manifest;
    private boolean closed;

    private RunGuard(List<Path> copies, List<Path> watched, Path home, Path quarantine, Path logBase, Path manifest) {
        this.copies = List.copyOf(copies);
        this.watched = List.copyOf(watched);
        this.home = home;
        this.quarantine = quarantine;
        this.logBase = logBase;
        this.manifest = manifest;
    }

    /**
     * Makes the run's dirs, records the run in {@code manifest}, then makes its copies; anything that cannot be made
     * fails the run before its agent starts, and leaves nothing to undo.
     */
    static RunGuard start(SandboxPolicy policy, Path home, Path quarantine, Path logBase, Path manifest) {
        // Kept after the run, so made before anything that must be undone.
        for (Path dir : policy.created()) {
            try {
                OwnerOnly.createDirectories(dir);
            } catch (IOException e) {
                throw new AgentStartException("cannot create " + dir + " for the run: " + e, e);
            }
        }
        // Nothing to undo, no manifest: a guard nobody closes, as for the verify loop's tests, leaves nothing behind.
        if (policy.copies().isEmpty() && policy.watched().isEmpty()) {
            return NONE;
        }
        List<Path> copyPaths = policy.copies().stream().map(SandboxPolicy.FileCopy::copy).toList();
        RunGuard guard = new RunGuard(copyPaths, policy.watched(), home, quarantine, logBase, manifest);
        // Before the copies: a crash while they are made still leaves a record of what to undo.
        try {
            guard.writeManifest();
        } catch (IOException e) {
            throw new AgentStartException("cannot record the run's sandbox in " + manifest + ": " + e, e);
        }
        for (SandboxPolicy.FileCopy copy : policy.copies()) {
            try {
                // Owner-only like the original: ~/.claude.json holds the owner's MCP environment and account.
                OwnerOnly.createDirectories(copy.copy().getParent());
                Files.deleteIfExists(copy.copy());
                OwnerOnly.createFile(copy.copy());
                Files.write(copy.copy(), Files.readAllBytes(copy.original()));
            } catch (IOException e) {
                guard.close();
                throw new AgentStartException("cannot copy " + copy.original() + " for the run: " + e, e);
            }
        }
        return guard;
    }

    /**
     * Moves every watched path that exists now to the quarantine. Again on each call until the guard closes: a cancelled
     * sandbox's outer process exits while the processes inside it may still be dying.
     */
    public synchronized void sweep() {
        if (closed) {
            return;
        }
        for (Path path : watched) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                quarantine(path);
            }
        }
    }

    /**
     * Once the whole sandbox is gone: a last sweep, then the copies and the manifest go. Later calls do nothing, and
     * wait for the first to finish (synchronized): await()'s caller must not see the owner's home half-swept.
     */
    public synchronized void close() {
        if (closed) {
            return;
        }
        sweep();
        closed = true;
        for (Path copy : copies) {
            try {
                Files.deleteIfExists(copy);
            } catch (IOException e) {
                Log.warn("sandbox.copy_left", "path", copy, "error", e.toString());
            }
        }
        if (manifest == null) {
            return;
        }
        try {
            Files.deleteIfExists(manifest);
        } catch (IOException e) {
            Log.warn("sandbox.manifest_left", "path", manifest, "error", e.toString());
        }
    }

    /** At startup, once the orphan kill has ended what a crashed Dispatch left running: closes the guards it left open. */
    public static void closeLeftovers(Path guardsDir) {
        if (!Files.isDirectory(guardsDir)) {
            return;
        }
        List<Path> manifests;
        try (Stream<Path> files = Files.list(guardsDir)) {
            manifests = files.filter(file -> file.getFileName().toString().endsWith(".json")).toList();
        } catch (IOException e) {
            Log.error("sandbox.guards_unreadable", e, "dir", guardsDir);
            return;
        }
        for (Path file : manifests) {
            Manifest left;
            try {
                left = Json.MAPPER.readValue(file.toFile(), Manifest.class);
            } catch (IOException e) {
                // Kept: what it names is unknown, and the owner may want to look.
                Log.error("sandbox.guard_unreadable", e, "manifest", file);
                continue;
            }
            Log.warn("sandbox.guard_left", "run", left.logBase(), "manifest", file);
            new RunGuard(left.copies().stream().map(Path::of).toList(), left.watched().stream().map(Path::of).toList(),
                    Path.of(left.home()), Path.of(left.quarantine()), Path.of(left.logBase()), file).close();
        }
    }

    private void writeManifest() throws IOException {
        Manifest record = new Manifest(home.toString(), quarantine.toString(), logBase.toString(),
                copies.stream().map(Path::toString).toList(), watched.stream().map(Path::toString).toList());
        OwnerOnly.createDirectories(manifest.getParent());
        Files.deleteIfExists(manifest);
        OwnerOnly.createFile(manifest);
        Files.writeString(manifest, Json.MAPPER.writeValueAsString(record));
    }

    private void quarantine(Path path) {
        Path target = unused(quarantine.resolve(home.relativize(path).toString()));
        IOException failure;
        try {
            OwnerOnly.createDirectories(target.getParent());
            Files.move(path, target);
            Log.warn("sandbox.quarantined", "path", path, "to", target, "run", logBase);
            return;
        } catch (IOException e) {
            failure = e;
        }
        // A non-empty dir cannot move to another filesystem. Renamed beside itself, to a name no agent loads, it is out
        // of reach all the same, and the next run does not take it for the owner's.
        Path aside = unused(path.resolveSibling(path.getFileName() + ".dispatch-quarantined-" + quarantine.getFileName()));
        try {
            Files.move(path, aside);
            Log.error("sandbox.quarantined_in_place", failure, "path", path, "to", aside, "run", logBase);
        } catch (IOException e) {
            Log.error("sandbox.quarantine_failed", e, "path", path, "error", e.toString(), "run", logBase);
        }
    }

    /** {@code path}, or the first of {@code path.2}, {@code path.3}, ... that does not exist: what was planted again is kept too. */
    private static Path unused(Path path) {
        Path candidate = path;
        for (int n = 2; Files.exists(candidate, LinkOption.NOFOLLOW_LINKS); n++) {
            candidate = path.resolveSibling(path.getFileName() + "." + n);
        }
        return candidate;
    }
}
