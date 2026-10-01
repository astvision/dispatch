package dispatch.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.AgentStartException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class RunGuardTest {

    @TempDir
    Path root;

    @Test
    void theCopyIsMadeBeforeTheRunAndDeletedAfterIt() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path original = Files.writeString(home.resolve(".claude.json"), "{\"mcpServers\":{}}");
        Path copy = root.resolve("state/runs/7/2.claude.json");

        RunGuard guard = start(policy(List.of(new SandboxPolicy.FileCopy(original, copy)), List.of()), home);
        assertEquals("{\"mcpServers\":{}}", Files.readString(copy));

        Files.writeString(copy, "{\"mcpServers\":{\"planted\":{}}}");
        guard.close();

        assertFalse(Files.exists(copy));
        assertEquals("{\"mcpServers\":{}}", Files.readString(original), "the run's writes never reach the original");
    }

    @Test
    void aWatchedPathThatAppearedIsMovedWithItsContentAndLogged() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path watched = home.resolve(".codex/memories");
        RunGuard guard = start(policy(List.of(), List.of(watched)), home);
        Files.createDirectories(watched);
        Files.writeString(watched.resolve("note.md"), "remember this");

        String logged = capturingLog(guard::close);

        assertFalse(Files.exists(watched));
        assertEquals("remember this", Files.readString(quarantine().resolve(".codex/memories/note.md")));
        assertTrue(logged.contains("level=WARN event=sandbox.quarantined path=" + watched), logged);
    }

    @Test
    void aWatchedPathThatNeverAppearedIsLeftAloneAndClosingTwiceDoesNothingMore() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        RunGuard guard = start(policy(List.of(), List.of(home.resolve(".codex/hooks.json"))), home);

        guard.close();
        Files.createDirectories(home.resolve(".codex"));
        Files.writeString(home.resolve(".codex/hooks.json"), "{}");
        guard.close();
        guard.sweep();

        assertTrue(Files.exists(home.resolve(".codex/hooks.json")), "the owner's file after the run is theirs");
        assertFalse(Files.exists(quarantine()));
    }

    /**
     * The process's exit closes the guard on one thread while await() closes it on another: whichever comes second must
     * not return before the sweep is done, or await()'s caller sees the owner's home not yet swept.
     */
    @Test
    void aSecondCloseWaitsForTheFirstToFinish() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        List<Path> watched = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            watched.add(home.resolve(".codex/memories-" + i));
        }
        RunGuard guard = start(policy(List.of(), watched), home);
        for (Path path : watched) {
            Files.createDirectories(path);
            Files.writeString(path.resolve("note.md"), "x");
        }

        Thread first = Thread.ofPlatform().start(() -> capturingLog(guard::close));
        while (watched.stream().allMatch(Files::exists) && first.isAlive()) {
            Thread.onSpinWait();
        }
        capturingLog(guard::close);

        assertTrue(watched.stream().noneMatch(Files::exists), "close() returned before the sweep was done");
        first.join();
    }

    /** A cancelled sandbox's tree may still be dying when its outer process exits: each sweep takes what is there by then. */
    @Test
    void aSweepRunsAgainUntilTheGuardClosesAndKeepsWhatWasPlantedTwice() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path watched = home.resolve(".codex/prompts");
        RunGuard guard = start(policy(List.of(), List.of(watched)), home);
        Files.writeString(Files.createDirectories(watched).resolve("a.md"), "first");
        capturingLog(guard::sweep);

        Files.writeString(Files.createDirectories(watched).resolve("a.md"), "second");
        capturingLog(guard::close);

        assertFalse(Files.exists(watched));
        assertEquals("first", Files.readString(quarantine().resolve(".codex/prompts/a.md")));
        assertEquals("second", Files.readString(quarantine().resolve(".codex/prompts.2/a.md")));
    }

    /** The copy holds the owner's MCP environment and account data: as private as the original. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theCopyIsOwnerOnly() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path original = Files.writeString(home.resolve(".claude.json"), "{}");
        Path copy = root.resolve("state/runs/7/2.claude.json");

        start(policy(List.of(new SandboxPolicy.FileCopy(original, copy)), List.of()), home);

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(copy)));
    }

    /** Across filesystems a non-empty dir cannot be moved; left in place, the next run would trust it as the owner's. */
    @Test
    void aPathThatCannotMoveToTheQuarantineIsRenamedInPlace() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path watched = home.resolve(".codex/prompts");
        Files.createDirectories(quarantine().getParent());
        Files.writeString(quarantine(), "a file where the quarantine dir would go");
        RunGuard guard = start(policy(List.of(), List.of(watched)), home);
        Files.createDirectories(watched);
        Files.writeString(watched.resolve("evil.md"), "run this");

        String logged = capturingLog(guard::close);

        assertFalse(Files.exists(watched), "out of the loader's path");
        assertEquals("run this", Files.readString(home.resolve(".codex/prompts.dispatch-quarantined-7-2/evil.md")));
        assertTrue(logged.contains("level=ERROR event=sandbox.quarantined_in_place path=" + watched), logged);
    }

    /** What a crash would leave to undo is on disk before the run starts, and gone once the guard closes. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theManifestRecordsTheRunUntilItsGuardCloses() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path watched = home.resolve(".codex/prompts");
        RunGuard guard = start(policy(List.of(), List.of(watched)), home);

        assertTrue(Files.readString(manifest()).contains(watched.toString()), Files.readString(manifest()));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(manifest())));
        guard.close();

        assertFalse(Files.exists(manifest()));
    }

    @Test
    void aGuardACrashLeftOpenIsClosedAtTheNextStart() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path original = Files.writeString(home.resolve(".claude.json"), "{}");
        Path copy = root.resolve("state/runs/7/2.claude.json");
        Path watched = home.resolve(".codex/prompts");
        start(policy(List.of(new SandboxPolicy.FileCopy(original, copy)), List.of(watched)), home);
        Files.createDirectories(watched);
        // Dispatch dies here: nothing closes the guard.

        String logged = capturingLog(() -> RunGuard.closeLeftovers(manifest().getParent()));

        assertFalse(Files.exists(watched));
        assertTrue(Files.isDirectory(quarantine().resolve(".codex/prompts")));
        assertFalse(Files.exists(copy));
        assertFalse(Files.exists(manifest()));
        assertTrue(logged.contains("level=WARN event=sandbox.guard_left"), logged);
    }

    /** The verify loop's test command has no agent state, and its guard is never closed: it must leave no manifest. */
    @Test
    void aRunWithNothingToUndoWritesNoManifest() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path made = home.resolve(".claude/projects/-w/memory");

        start(new SandboxPolicy(root.resolve("work"), null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(made)), home);

        assertTrue(Files.isDirectory(made), "its dirs are still made");
        assertFalse(Files.exists(manifest()));
    }

    @Test
    void noLeftoversDirIsNothingToClose() {
        String logged = capturingLog(() -> RunGuard.closeLeftovers(root.resolve("state/guards")));

        assertEquals("", logged);
    }

    @Test
    void aCopyThatCannotBeMadeFailsTheRunBeforeItStartsAndLeavesNothing() {
        Path home = root.resolve("home");
        Path missing = home.resolve(".claude.json");

        AgentStartException error = assertThrows(AgentStartException.class, () -> start(
                policy(List.of(new SandboxPolicy.FileCopy(missing, root.resolve("state/runs/7/2.claude.json"))), List.of()), home));

        assertTrue(error.getMessage().contains(missing.toString()), error.getMessage());
        assertFalse(Files.exists(manifest()), "a run that never started has nothing to undo");
    }

    private RunGuard start(SandboxPolicy policy, Path home) {
        return RunGuard.start(policy, home, quarantine(), root.resolve("state/runs/7/2"), manifest());
    }

    private Path quarantine() {
        return root.resolve("state/quarantine/7-2");
    }

    private Path manifest() {
        return root.resolve("state/guards/7-2.json");
    }

    private SandboxPolicy policy(List<SandboxPolicy.FileCopy> copies, List<Path> watched) {
        return new SandboxPolicy(root.resolve("work"), null, null, List.of(), List.of(), List.of(), List.of(), List.of(), copies,
                watched, List.of(), List.of());
    }

    private static String capturingLog(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setOut(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return logged.toString(StandardCharsets.UTF_8);
    }
}
