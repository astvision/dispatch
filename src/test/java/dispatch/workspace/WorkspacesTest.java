package dispatch.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.config.Config;
import dispatch.testing.GitFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacesTest {

    @TempDir
    Path dir;

    private GitFixture repos;
    private Path stateDir;
    private Git git;
    private Workspaces workspaces;

    @BeforeEach
    void createRepositories() throws IOException {
        repos = GitFixture.create(dir, "alm");
        stateDir = repos.stateDir;
        git = new Git("git", null, Duration.ofSeconds(30));
        workspaces = new Workspaces(stateDir, git);
    }

    @Test
    void worktreeIsCreatedOnTheTaskBranchFromTheFreshlyFetchedBase() throws IOException {
        Files.writeString(repos.seed.resolve("README.md"), "v2\n");
        String newBase = repos.commitAndPush("pushed after Dispatch cloned");

        Workspaces.PreparedWorktree worktree = workspaces.createWorktree(project(List.of()), 42);

        assertEquals(stateDir.resolve("worktrees/42"), worktree.path());
        assertEquals(newBase, worktree.baseSha());
        assertEquals("dispatch/42", GitFixture.sh(worktree.path(), "git", "rev-parse", "--abbrev-ref", "HEAD"));
        assertEquals("v2\n", Files.readString(worktree.path().resolve("README.md")));
    }

    @Test
    void ignoredFilesAreCopiedIntoTheWorktree() throws IOException {
        Path repo = stateDir.resolve("repos/alm");
        Files.writeString(repo.resolve(".env"), "DB_PASSWORD=local\n");
        Files.createDirectories(repo.resolve("local"));
        Files.writeString(repo.resolve("local/app.yml"), "port: 8081\n");
        Workspaces.PreparedWorktree worktree = workspaces.createWorktree(project(List.of(".env", "local/app.yml")), 7);

        workspaces.copyFiles(project(List.of(".env", "local/app.yml")), worktree.path());

        assertEquals("DB_PASSWORD=local\n", Files.readString(worktree.path().resolve(".env")));
        assertEquals("port: 8081\n", Files.readString(worktree.path().resolve("local/app.yml")));
    }

    @Test
    void fileGitWouldCommitIsNeverCopied() throws IOException {
        Files.writeString(stateDir.resolve("repos/alm/notes.txt"), "secret notes\n");
        Workspaces.PreparedWorktree worktree = workspaces.createWorktree(project(List.of("notes.txt")), 8);

        WorkspaceException error = assertThrows(WorkspaceException.class,
                () -> workspaces.copyFiles(project(List.of("notes.txt")), worktree.path()));

        assertTrue(error.getMessage().contains("notes.txt") && error.getMessage().contains("not ignored"), error.getMessage());
        assertFalse(Files.exists(worktree.path().resolve("notes.txt")));
    }

    @Test
    void missingCopyFileIsReported() {
        Workspaces.PreparedWorktree worktree = workspaces.createWorktree(project(List.of(".env")), 9);

        WorkspaceException error = assertThrows(WorkspaceException.class,
                () -> workspaces.copyFiles(project(List.of(".env")), worktree.path()));

        assertTrue(error.getMessage().contains(".env") && error.getMessage().contains("not found"), error.getMessage());
    }

    @Test
    void parallelTasksOnOneProjectAllGetTheirWorktrees() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<Workspaces.PreparedWorktree>> results = new java.util.ArrayList<>();
            for (long taskId = 100; taskId < 108; taskId++) {
                long id = taskId;
                results.add(pool.submit(() -> workspaces.createWorktree(project(List.of()), id)));
            }
            for (java.util.concurrent.Future<Workspaces.PreparedWorktree> result : results) {
                assertTrue(Files.isDirectory(result.get(60, java.util.concurrent.TimeUnit.SECONDS).path()));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void existingWorktreeIsNeverReused() {
        workspaces.createWorktree(project(List.of()), 10);

        WorkspaceException error = assertThrows(WorkspaceException.class, () -> workspaces.createWorktree(project(List.of()), 10));

        assertTrue(error.getMessage().contains("already exists"), error.getMessage());
    }

    @Test
    void fetchFailureCarriesGitsOwnError() throws IOException {
        GitFixture.sh(stateDir.resolve("repos/alm"), "git", "remote", "set-url", "origin", dir.resolve("missing.git").toString());

        WorkspaceException error = assertThrows(WorkspaceException.class, () -> workspaces.createWorktree(project(List.of()), 11));

        assertTrue(error.getMessage().contains("git fetch origin main"), error.getMessage());
        assertTrue(error.getMessage().contains("does not appear to be a git repository"), error.getMessage());
    }

    @Test
    void projectWithoutCloneIsUnavailable() {
        Config.Project crm = new Config.Project("crm", null, "https://github.com/acme/crm.git", "main", "claude-code", null, List.of(), null);

        Optional<String> reason = workspaces.unavailableReason(crm);

        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains(stateDir.resolve("repos/crm").toString()), reason.get());
        assertEquals(Optional.empty(), workspaces.unavailableReason(project(List.of())));
    }

    @Test
    void tokenReachesGitThroughTheEnvironmentNotTheCommandLine() {
        Git withToken = new Git("git", "github_pat_SECRET", Duration.ofSeconds(30));

        String helpers = withToken.run(stateDir.resolve("repos/alm"), "config", "--get-all", "credential.helper");

        assertTrue(helpers.contains("x-access-token") && helpers.contains("$GH_TOKEN"), helpers);
        assertFalse(helpers.contains("github_pat_SECRET"), helpers);
    }

    @Test
    void hangingGitIsKilledAfterTheTimeout() throws IOException {
        Path slowGit = dir.resolve("slow-git");
        Files.writeString(slowGit, "#!/bin/sh\nsleep 30\n");
        Files.setPosixFilePermissions(slowGit, PosixFilePermissions.fromString("rwx------"));
        Git slow = new Git(slowGit.toString(), null, Duration.ofMillis(300));

        WorkspaceException error = assertThrows(WorkspaceException.class, () -> slow.run(dir, "fetch", "origin", "main"));

        assertTrue(error.getMessage().contains("timed out"), error.getMessage());
    }

    private Config.Project project(List<String> copyFiles) {
        return new Config.Project("alm", null, repos.origin.toString(), "main", "claude-code", null, copyFiles, null);
    }

}
