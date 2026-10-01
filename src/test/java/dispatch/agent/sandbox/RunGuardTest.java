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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunGuardTest {

    @TempDir
    Path root;

    @Test
    void theCopyIsMadeBeforeTheRunAndDeletedAfterIt() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path original = Files.writeString(home.resolve(".claude.json"), "{\"mcpServers\":{}}");
        Path copy = root.resolve("state/runs/7/2.claude.json");

        RunGuard guard = RunGuard.start(policy(List.of(new SandboxPolicy.FileCopy(original, copy)), List.of()), home,
                root.resolve("state/quarantine/7-2"), root.resolve("state/runs/7/2"));
        assertEquals("{\"mcpServers\":{}}", Files.readString(copy));

        Files.writeString(copy, "{\"mcpServers\":{\"planted\":{}}}");
        guard.end();

        assertFalse(Files.exists(copy));
        assertEquals("{\"mcpServers\":{}}", Files.readString(original), "the run's writes never reach the original");
    }

    @Test
    void aWatchedPathThatAppearedIsMovedWithItsContentAndLogged() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path watched = home.resolve(".codex/memories");
        Path quarantine = root.resolve("state/quarantine/7-2");
        RunGuard guard = RunGuard.start(policy(List.of(), List.of(watched)), home, quarantine, root.resolve("state/runs/7/2"));
        Files.createDirectories(watched);
        Files.writeString(watched.resolve("note.md"), "remember this");

        String logged = capturingLog(guard::end);

        assertFalse(Files.exists(watched));
        assertEquals("remember this", Files.readString(quarantine.resolve(".codex/memories/note.md")));
        assertTrue(logged.contains("level=WARN event=sandbox.quarantined path=" + watched), logged);
    }

    @Test
    void aWatchedPathThatNeverAppearedIsLeftAloneAndEndingTwiceDoesNothingMore() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path quarantine = root.resolve("state/quarantine/7-2");
        RunGuard guard = RunGuard.start(policy(List.of(), List.of(home.resolve(".codex/hooks.json"))), home, quarantine,
                root.resolve("state/runs/7/2"));

        guard.end();
        Files.createDirectories(home.resolve(".codex"));
        Files.writeString(home.resolve(".codex/hooks.json"), "{}");
        guard.end();

        assertTrue(Files.exists(home.resolve(".codex/hooks.json")), "the owner's file after the run is theirs");
        assertFalse(Files.exists(quarantine));
    }

    /**
     * The process's exit ends the guard on one thread while await() ends it on another: whichever comes second must
     * not return before the sweep is done, or await()'s caller sees the owner's home not yet swept.
     */
    @Test
    void aSecondEndWaitsForTheFirstToFinish() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        List<Path> watched = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            watched.add(home.resolve(".codex/memories-" + i));
        }
        RunGuard guard = RunGuard.start(policy(List.of(), watched), home, root.resolve("state/quarantine/7-2"),
                root.resolve("state/runs/7/2"));
        for (Path path : watched) {
            Files.createDirectories(path);
            Files.writeString(path.resolve("note.md"), "x");
        }

        Thread first = Thread.ofPlatform().start(() -> capturingLog(guard::end));
        while (watched.stream().allMatch(Files::exists) && first.isAlive()) {
            Thread.onSpinWait();
        }
        capturingLog(guard::end);

        assertTrue(watched.stream().noneMatch(Files::exists), "end() returned before the sweep was done");
        first.join();
    }

    @Test
    void aCopyThatCannotBeMadeFailsTheRunBeforeItStarts() {
        Path home = root.resolve("home");
        Path missing = home.resolve(".claude.json");

        AgentStartException error = assertThrows(AgentStartException.class, () -> RunGuard.start(
                policy(List.of(new SandboxPolicy.FileCopy(missing, root.resolve("state/runs/7/2.claude.json"))), List.of()),
                home, root.resolve("state/quarantine/7-2"), root.resolve("state/runs/7/2")));

        assertTrue(error.getMessage().contains(missing.toString()), error.getMessage());
    }

    private SandboxPolicy policy(List<SandboxPolicy.FileCopy> copies, List<Path> watched) {
        return new SandboxPolicy(root.resolve("work"), null, null, List.of(), List.of(), List.of(), List.of(), List.of(), copies,
                watched);
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
