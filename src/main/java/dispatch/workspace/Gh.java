package dispatch.workspace;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** The GitHub CLI, run like {@link Git}: it never prompts and sees the token only in its environment. */
public final class Gh {

    private final String command;
    private final String ghToken;
    private final Duration timeout;

    /** @param ghToken null to rely on the OS user's own gh login */
    public Gh(String command, String ghToken, Duration timeout) {
        this.command = command;
        this.ghToken = ghToken;
        this.timeout = timeout;
    }

    /** Opens a draft pull request from {@code head} into {@code base} for the worktree's repository; returns its URL. */
    public String createDraftPullRequest(Path worktree, String base, String head, String title, String body) {
        List<String> commandLine = List.of(command, "pr", "create", "--draft", "--base", base, "--head", head,
                "--title", title, "--body", body);
        Git.Result result = Git.runProcess(commandLine, worktree, ghToken, timeout, "gh pr create");
        if (result.exitCode() != 0) {
            String output = result.stderr().isBlank() ? result.stdout() : result.stderr();
            throw new WorkspaceException("gh pr create failed (exit " + result.exitCode() + "): " + output.strip());
        }
        // gh prints the new pull request's URL as its last line.
        List<String> lines = result.stdout().strip().lines().toList();
        String url = lines.isEmpty() ? "" : lines.getLast().strip();
        if (!url.startsWith("https://")) {
            throw new WorkspaceException("gh pr create printed no pull request URL: " + result.stdout().strip());
        }
        return url;
    }
}
