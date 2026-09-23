package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.testing.GitFixture;
import dispatch.testing.TestClock;
import dispatch.workspace.Git;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The member's own machine tidying up: nothing on the team machine can reach these worktrees (ADR 0021). */
class WorkerSweeperTest {

    private static final Config.Project ALM = new Config.Project("alm", null, null, null, "main", "claude-code", null,
            null, List.of(), null, null, null);

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock(Instant.parse("2026-09-23T10:00:00Z"));
    private GitFixture repos;
    private Workspaces workspaces;
    private ActiveRuns activeRuns;

    @BeforeEach
    void setUp() throws IOException {
        repos = GitFixture.create(dir, "alm");
        workspaces = new Workspaces(repos.stateDir, new Git("git", null, Duration.ofSeconds(30)));
        workspaces.createDirectories();
        activeRuns = new ActiveRuns();
    }

    @Test
    void anIdleCleanWorktreeIsRemovedAndABusyOrDirtyOneIsKept() throws Exception {
        Path idle = workspaces.createWorktree(project(), 1).path();
        Path dirty = workspaces.createWorktree(project(), 2).path();
        Path running = workspaces.createWorktree(project(), 3).path();
        Files.writeString(dirty.resolve("notes.txt"), "work in progress\n");
        activeRuns.register(3, 1);
        for (Path worktree : List.of(idle, dirty, running)) {
            Files.setLastModifiedTime(worktree, FileTime.from(clock.instant().minus(Duration.ofDays(30))));
        }

        int removed = sweeper().sweep();

        assertEquals(1, removed);
        assertFalse(Files.exists(idle), "an idle, clean worktree goes");
        assertTrue(Files.exists(dirty), "an uncommitted change is never discarded here");
        assertTrue(Files.exists(running), "a run is working in it right now");
    }

    @Test
    void aWorktreeWithAnUnpushedCommitIsKept() throws Exception {
        Path unpushed = workspaces.createWorktree(project(), 5).path();
        // Empty on purpose: the working tree stays clean, so this isolates the pushed check from the uncommitted
        // one — a worktree straight off origin/main with nothing but a local commit no one has pushed.
        GitFixture.sh(unpushed, "git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit",
                "--quiet", "--allow-empty", "-m", "local only, never pushed");
        Files.setLastModifiedTime(unpushed, FileTime.from(clock.instant().minus(Duration.ofDays(30))));

        assertEquals(0, sweeper().sweep());
        assertTrue(Files.exists(unpushed), "a commit that never reached origin is not discardable here");
    }

    @Test
    void aWorktreeThatWasUsedRecentlyIsKept() throws Exception {
        Path recent = workspaces.createWorktree(project(), 4).path();
        Files.setLastModifiedTime(recent, FileTime.from(clock.instant().minus(Duration.ofDays(30))));
        // A run's own log directory is what says "something happened here", and it lives outside the worktree.
        Files.createDirectories(repos.stateDir.resolve("runs").resolve("4"));

        assertEquals(0, sweeper().sweep());
        assertTrue(Files.exists(recent));
    }

    private WorkerSweeper sweeper() {
        return new WorkerSweeper(repos.stateDir, workspaces, activeRuns, clock, Duration.ofDays(7), Duration.ofHours(1));
    }

    private Config.Project project() {
        return ALM;
    }
}
