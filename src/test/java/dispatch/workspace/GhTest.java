package dispatch.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.testing.FakeGh;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** What Dispatch asks the GitHub CLI when merging: the command lines, as the fake gh received them. */
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

    /** Every call the fake gh received in {@link #dir}, each ended by "--". */
    private List<String> calls() throws IOException {
        return Files.readAllLines(dir.resolve("fake-gh.calls"));
    }
}
