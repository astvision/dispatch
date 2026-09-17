package dispatch.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.config.Config;
import dispatch.testing.FakeGh;
import dispatch.testing.GitFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Delivery against real git: a bare origin, a Dispatch clone with a task worktree, and the fake gh script. */
class DeliveryTest {

    private static final Delivery.Commit COMMIT = new Delivery.Commit("dispatch #42: Fix the login timeout",
            "Made the auth timeout configurable.", List.of("Requested-by: Bold", "Approved-by: Ali"));

    @TempDir
    Path dir;

    private GitFixture repos;
    private Git git;
    private Path gh;
    private Path worktree;
    private String start;

    @BeforeEach
    void createWorktree() throws IOException {
        repos = GitFixture.create(dir, "alm");
        git = new Git("git", null, Duration.ofSeconds(30));
        gh = FakeGh.install(dir.resolve("bin"));
        Config.Project alm = new Config.Project("alm", null, repos.origin.toString(), "main", "claude-code", null, List.of(), null);
        worktree = new Workspaces(repos.stateDir, git).createWorktree(alm, 42).path();
        start = delivery(null).head(worktree);
    }

    @Test
    void changesBecomeOneBotCommitOnThePushedTaskBranchWithADraftPullRequest() throws IOException {
        Files.writeString(worktree.resolve("README.md"), "v2\n");
        Files.createDirectories(worktree.resolve("src"));
        Files.writeString(worktree.resolve("src/Timeout.java"), "class Timeout {}\n");

        Delivery.Result result = delivery(null).deliver(worktree, 42, "main", start, COMMIT, null);

        assertEquals(List.of("README.md", "src/Timeout.java"), result.files());
        assertEquals(result.commitSha(), origin("rev-parse", "refs/heads/dispatch/42"));
        assertEquals(start, origin("rev-parse", "refs/heads/dispatch/42^"), "exactly one commit on top of the run's start");
        assertEquals("Dispatch (backend) <dispatch-backend@example.com>|Dispatch (backend)|dispatch #42: Fix the login timeout",
                origin("log", "-1", "--format=%an <%ae>|%cn|%s", "refs/heads/dispatch/42"));
        String body = origin("log", "-1", "--format=%b", "refs/heads/dispatch/42");
        assertTrue(body.startsWith("Made the auth timeout configurable."), body);
        assertTrue(body.endsWith("Requested-by: Bold\nApproved-by: Ali"), body);

        assertEquals(FakeGh.PR_URL, result.prUrl());
        List<String> ghArgs = Files.readAllLines(worktree.resolve("fake-gh.args"));
        assertEquals(List.of("pr", "create", "--draft", "--base", "main", "--head", "dispatch/42",
                "--title", "dispatch #42: Fix the login timeout", "--body", "Made the auth timeout configurable."), ghArgs);
    }

    @Test
    void trailersStayTheirOwnParagraphWhenTheSummaryEndsLikeATrailer() throws IOException {
        Files.writeString(worktree.resolve("README.md"), "v2\n");
        Delivery.Commit commit = new Delivery.Commit("dispatch #42: Add make help",
                "Added the help target.\n\nSummary: listed every target with its description.", List.of("Requested-by: Bold", "Approved-by: Ali"));

        delivery(null).deliver(worktree, 42, "main", start, commit, null);

        assertEquals("Requested-by: Bold\nApproved-by: Ali",
                origin("log", "-1", "--format=%(trailers:only,unfold)", "refs/heads/dispatch/42").strip());
        String body = origin("log", "-1", "--format=%b", "refs/heads/dispatch/42");
        assertTrue(body.contains("Summary: listed every target with its description.\n\nRequested-by: Bold"), body);
    }

    @Test
    void runWithoutChangesDeliversNothing() {
        Delivery.Result result = delivery(null).deliver(worktree, 42, "main", start, COMMIT, null);

        assertEquals(List.of(), result.files());
        assertNull(result.commitSha());
        assertNull(result.prUrl());
        assertEquals("", origin("branch", "--list", "dispatch/42"));
        assertFalse(Files.exists(worktree.resolve("fake-gh.args")), "no pull request without changes");
    }

    @Test
    void commitsTheAgentMadeAnywayAreFoldedIntoTheOneDeliveryCommit() throws IOException {
        Files.writeString(worktree.resolve("README.md"), "v2\n");
        GitFixture.sh(worktree, "git", "-c", "user.name=Agent", "-c", "user.email=agent@example.com", "commit", "--quiet", "-am", "agent");

        Delivery.Result result = delivery(null).deliver(worktree, 42, "main", start, COMMIT, null);

        assertEquals(List.of("README.md"), result.files());
        assertEquals(start, origin("rev-parse", "refs/heads/dispatch/42^"));
        assertEquals("Dispatch (backend)", origin("log", "-1", "--format=%an", "refs/heads/dispatch/42"));
    }

    @Test
    void laterDeliveryPushesToTheExistingPullRequest() throws IOException {
        Files.writeString(worktree.resolve("README.md"), "v2\n");

        Delivery.Result result = delivery(null).deliver(worktree, 42, "main", start, COMMIT, "https://github.com/acme/alm/pull/3");

        assertEquals("https://github.com/acme/alm/pull/3", result.prUrl());
        assertEquals(result.commitSha(), origin("rev-parse", "refs/heads/dispatch/42"));
        assertFalse(Files.exists(worktree.resolve("fake-gh.args")));
    }

    @Test
    void pushFailureCarriesGitsError() throws IOException {
        Files.writeString(worktree.resolve("README.md"), "v2\n");
        GitFixture.sh(worktree, "git", "remote", "set-url", "origin", dir.resolve("missing.git").toString());

        WorkspaceException error = assertThrows(WorkspaceException.class,
                () -> delivery(null).deliver(worktree, 42, "main", start, COMMIT, null));

        assertTrue(error.getMessage().contains("git push"), error.getMessage());
        assertTrue(error.getMessage().contains("does not appear to be a git repository"), error.getMessage());
    }

    @Test
    void pullRequestFailureCarriesGhsError() throws IOException {
        Files.writeString(worktree.resolve("README.md"), "v2\n");
        Delivery.Commit failing = new Delivery.Commit("dispatch #42: GH:fail", "body", List.of());

        WorkspaceException error = assertThrows(WorkspaceException.class,
                () -> delivery(null).deliver(worktree, 42, "main", start, failing, null));

        assertTrue(error.getMessage().contains("gh pr create failed (exit 1)"), error.getMessage());
        assertTrue(error.getMessage().contains("Resource not accessible"), error.getMessage());
    }

    @Test
    void tokenReachesGhThroughTheEnvironmentNotTheCommandLine() throws IOException {
        Files.writeString(worktree.resolve("README.md"), "v2\n");

        delivery("github_pat_SECRET").deliver(worktree, 42, "main", start, COMMIT, null);

        assertTrue(Files.readAllLines(worktree.resolve("fake-gh.env")).contains("GH_TOKEN=github_pat_SECRET"));
        assertFalse(Files.readString(worktree.resolve("fake-gh.args")).contains("github_pat_SECRET"));
    }

    private Delivery delivery(String ghToken) {
        // Pushing to a local bare origin needs no token; only gh sees it here.
        return new Delivery(git, new Gh(gh.toString(), ghToken, Duration.ofSeconds(30)), "Dispatch (backend)",
                "dispatch-backend@example.com");
    }

    private String origin(String... args) {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "--git-dir";
        command[2] = repos.origin.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        return GitFixture.sh(dir, command);
    }
}
