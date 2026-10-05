package dispatch.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.testing.FakeGh;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** What Dispatch asks the GitHub CLI when merging and when watching checks: the command lines, as the fake gh received them. */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake gh CLI is a POSIX shell script")
class GhTest {

    private static final String PR = "https://github.com/acme/alm/pull/7";

    @TempDir
    Path dir;

    private Gh gh;

    @BeforeEach
    void setUp() throws IOException {
        gh = new Gh(FakeGh.install(dir.resolve("bin")).toString(), null, Duration.ofSeconds(30));
    }

    @Test
    void aPullRequestsStateIsWhatGitHubSays() throws IOException {
        Files.writeString(dir.resolve("fake-gh.state"), "MERGED\n");

        assertEquals("MERGED", gh.pullRequestState(dir, PR));
        assertEquals(List.of("pr", "view", PR, "--json", "state", "--jq", ".state", "--"), calls());
    }

    @Test
    void aSquashMergeMarksItReadyThenMergesItAndDeletesItsBranch() throws IOException {
        gh.squashMerge(dir, PR);

        assertEquals(List.of("pr", "ready", PR, "--", "pr", "merge", PR, "--squash", "--delete-branch", "--"), calls());
    }

    @Test
    void aRefusedMergeFailsWithGitHubsOwnWords() {
        WorkspaceException refused = assertThrows(WorkspaceException.class, () -> gh.squashMerge(dir, PR + "#GH:fail"));

        assertTrue(refused.getMessage().contains("GraphQL: Resource not accessible"), refused.getMessage());
    }

    @Test
    void aPullRequestIsItsStateAndItsHeadCommit() throws IOException {
        Files.writeString(dir.resolve("fake-gh.pr"), "{\"headRefOid\":\"def456\",\"state\":\"MERGED\"}\n");

        assertEquals(new Gh.PullRequest("MERGED", "def456"), gh.pullRequest(dir, PR));
        assertEquals(List.of("pr", "view", PR, "--json", "state,headRefOid", "--"), calls());
    }

    @Test
    void checksAreReadFromWhatGhPrintsWhateverItsExitCode() throws IOException {
        Files.writeString(dir.resolve("fake-gh.checks"), """
                [{"bucket":"pending","link":"https://github.com/acme/alm/actions/runs/11/job/21","name":"test (ubuntu-latest)"},
                 {"bucket":"fail","link":"https://github.com/acme/alm/actions/runs/11/job/22","name":"ui"}]
                """);
        Files.writeString(dir.resolve("fake-gh.checks.exit"), "8\n");

        assertEquals(List.of(new Gh.Check("test (ubuntu-latest)", "pending", "https://github.com/acme/alm/actions/runs/11/job/21"),
                new Gh.Check("ui", "fail", "https://github.com/acme/alm/actions/runs/11/job/22")), gh.checks(dir, PR));
        assertEquals(List.of("pr", "checks", PR, "--json", "name,bucket,link", "--"), calls());
    }

    @Test
    void aPullRequestWithoutChecksHasNone() {
        assertEquals(List.of(), gh.checks(dir, PR));
    }

    @Test
    void checksThatAreNotJsonFail() throws IOException {
        Files.writeString(dir.resolve("fake-gh.checks"), "<html>API rate limit exceeded</html>\n");

        WorkspaceException failed = assertThrows(WorkspaceException.class, () -> gh.checks(dir, PR));

        assertTrue(failed.getMessage().contains("rate limit"), failed.getMessage());
    }

    @Test
    void aRefusedChecksCallFailsWithGitHubsOwnWords() {
        WorkspaceException refused = assertThrows(WorkspaceException.class, () -> gh.checks(dir, PR + "#GH:fail"));

        assertTrue(refused.getMessage().contains("GraphQL: Resource not accessible"), refused.getMessage());
    }

    @Test
    void aFailedLogIsTheTailOfItsActionsRun() throws IOException {
        Files.writeString(dir.resolve("fake-gh.log"),
                IntStream.rangeClosed(1, 250).mapToObj(n -> "line " + n).collect(Collectors.joining("\n")) + "\n");

        String log = gh.failedLog(dir, "https://github.com/acme/alm/actions/runs/36443099774/job/108998886647");

        assertEquals(200, log.lines().count());
        assertTrue(log.startsWith("line 51\n") && log.endsWith("line 250"), log);
        assertEquals(List.of("run", "view", "36443099774", "--repo", "acme/alm", "--log-failed", "--"), calls());
    }

    @Test
    void aCheckThatIsNotAnActionsRunHasNoLogAndAsksNothing() {
        assertEquals("", gh.failedLog(dir, "https://vercel.com/acme/alm/deployments/9"));
        assertFalse(Files.exists(dir.resolve("fake-gh.calls")));
    }

    @Test
    void aLogGhWillNotGiveIsEmpty() {
        assertEquals("", gh.failedLog(dir, "https://github.com/acme/alm/actions/runs/1/job/2"));
    }

    /** Every call the fake gh received in {@link #dir}, each ended by "--". */
    private List<String> calls() throws IOException {
        return Files.readAllLines(dir.resolve("fake-gh.calls"));
    }
}
