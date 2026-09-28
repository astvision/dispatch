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
            throw failed("gh pr create", result);
        }
        // gh prints the new pull request's URL as its last line.
        List<String> lines = result.stdout().strip().lines().toList();
        String url = lines.isEmpty() ? "" : lines.getLast().strip();
        if (!url.startsWith("https://")) {
            throw new WorkspaceException("gh pr create printed no pull request URL: " + result.stdout().strip());
        }
        return url;
    }

    /** The pull request's state on GitHub: OPEN, MERGED or CLOSED. Run in {@code dir}, outside any clone: the URL names its repository. */
    public String pullRequestState(Path dir, String url) {
        Git.Result result = Git.runProcess(List.of(command, "pr", "view", url, "--json", "state", "--jq", ".state"), dir, ghToken,
                timeout, "gh pr view");
        if (result.exitCode() != 0) {
            throw failed("gh pr view", result);
        }
        return result.stdout().strip();
    }

    /**
     * Marks the pull request ready for review, since a draft cannot be merged, then squash-merges it and deletes its branch
     * on GitHub. Run in {@code dir}, outside any clone, so gh never switches or deletes a checked-out branch. Throws with
     * GitHub's own words when it refuses, e.g. while a required check is still running.
     */
    public void squashMerge(Path dir, String url) {
        // Fails harmlessly on a pull request that is ready already; the merge's own answer is the one that matters.
        Git.runProcess(List.of(command, "pr", "ready", url), dir, ghToken, timeout, "gh pr ready");
        Git.Result merged = Git.runProcess(List.of(command, "pr", "merge", url, "--squash", "--delete-branch"), dir, ghToken, timeout,
                "gh pr merge");
        if (merged.exitCode() != 0) {
            throw failed("gh pr merge", merged);
        }
    }

    private static WorkspaceException failed(String what, Git.Result result) {
        String output = result.stderr().isBlank() ? result.stdout() : result.stderr();
        return new WorkspaceException(what + " failed (exit " + result.exitCode() + "): " + output.strip());
    }
}
