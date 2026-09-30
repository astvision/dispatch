package dispatch.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dispatch's git runs outside the sandbox with GH_TOKEN in a clone an agent wrote to; config or hooks planted there
 * must not run.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the planted hook and fsmonitor are POSIX shell scripts")
class GitTest {

    @TempDir
    Path dir;

    private Git git;
    private Path repo;
    private Path ran;

    @BeforeEach
    void plantHookAndFsmonitor() throws IOException {
        git = new Git("git", null, Duration.ofSeconds(30));
        repo = Files.createDirectories(dir.resolve("repo"));
        ran = dir.resolve("ran");
        git.run(repo, "init", "-q", "-b", "main");
        Path script = dir.resolve("planted.sh");
        Files.writeString(script, "#!/bin/sh\necho \"$0 $*\" >> '" + ran + "'\n");
        script.toFile().setExecutable(true);
        Files.copy(script, repo.resolve(".git/hooks/post-commit"));
        repo.resolve(".git/hooks/post-commit").toFile().setExecutable(true);
        git.run(repo, "config", "core.fsmonitor", script.toString());
    }

    @Test
    void aPlantedFsmonitorDoesNotRun() {
        git.run(repo, "status", "--porcelain");

        assertFalse(Files.exists(ran), "fsmonitor ran");
    }

    @Test
    void aPlantedPostCommitHookDoesNotRunThoughNoVerifyCannotStopIt() {
        git.run(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "--no-verify", "-m", "x");

        assertFalse(Files.exists(ran), "post-commit hook ran");
    }

    @Test
    void aCloneWhoseGitDirNamesAnotherCommonDirIsRefused() throws IOException {
        // An agent writes <common>/commondir naming a git dir with its own config (remote, credential helper, sshCommand).
        Path commondir = repo.resolve(".git/commondir");
        Files.writeString(commondir, agentsGitDir() + "\n");

        WorkspaceException refused = assertThrows(WorkspaceException.class, () -> git.run(repo, "status", "--porcelain"));

        assertTrue(refused.getMessage().contains(commondir.toString()), refused.getMessage());
    }

    @Test
    void aWorktreeWhoseAdminCommondirPointsElsewhereIsRefused() throws IOException {
        // A sibling run rewrote this worktree's admin commondir.
        Path worktree = addWorktree();
        Path commondir = repo.resolve(".git/worktrees/w/commondir");
        Files.writeString(commondir, agentsGitDir() + "\n");

        WorkspaceException refused = assertThrows(WorkspaceException.class, () -> git.run(worktree, "status", "--porcelain"));

        assertTrue(refused.getMessage().contains(commondir.toString()), refused.getMessage());
    }

    @Test
    void aWorktreeWithoutAnAdminCommondirIsRefused() throws IOException {
        Path worktree = addWorktree();
        Files.delete(repo.resolve(".git/worktrees/w/commondir"));

        assertThrows(WorkspaceException.class, () -> git.run(worktree, "status", "--porcelain"));
    }

    @Test
    void aNormalCloneAndANormalWorktreeRun() throws IOException {
        Path worktree = addWorktree();

        assertEquals("w", git.run(worktree, "rev-parse", "--abbrev-ref", "HEAD"));
        assertEquals("main", git.run(repo, "rev-parse", "--abbrev-ref", "HEAD"));
    }

    @Test
    void aCommondirPlantedAfterTheCheckIsIgnored() throws IOException {
        // Another task's agent can plant it between the check and git's start; git must still read the clone's own config.
        git.run(repo, "remote", "add", "origin", "https://good.example/x");
        Git racing = racingGit(repo.resolve(".git/commondir"), agentsGitDir());

        assertEquals("https://good.example/x", racing.run(repo, "config", "remote.origin.url"));
    }

    @Test
    void anAdminCommondirRewrittenAfterTheCheckIsIgnored() throws IOException {
        git.run(repo, "remote", "add", "origin", "https://good.example/x");
        Path worktree = addWorktree();
        Git racing = racingGit(repo.resolve(".git/worktrees/w/commondir"), agentsGitDir());

        assertEquals("https://good.example/x", racing.run(worktree, "config", "remote.origin.url"));
    }

    /** A git that plants {@code redirect} into {@code commondir} after Dispatch's check, just before git starts. */
    private Git racingGit(Path commondir, Path redirect) throws IOException {
        Path script = dir.resolve("racing-git.sh");
        Files.writeString(script, "#!/bin/sh\necho '" + redirect + "' > '" + commondir + "'\nexec git \"$@\"\n");
        script.toFile().setExecutable(true);
        return new Git(script.toString(), null, Duration.ofSeconds(30));
    }

    private Path addWorktree() {
        git.run(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "--no-verify", "-m", "x");
        Path worktree = dir.resolve("w");
        git.run(repo, "worktree", "add", "-q", "-b", "w", worktree.toString());
        return worktree;
    }

    private Path agentsGitDir() throws IOException {
        Path agents = Files.createDirectories(dir.resolve("agents"));
        git.run(agents, "init", "-q", "-b", "main");
        git.run(agents, "remote", "add", "origin", "https://evil.example/x");
        return agents.resolve(".git");
    }
}
