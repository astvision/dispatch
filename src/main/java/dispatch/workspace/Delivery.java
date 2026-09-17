package dispatch.workspace;

import dispatch.Log;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Hands an execution run's changes to the team (ADR 0007): one commit on dispatch/&lt;task&gt; by the instance's bot
 * identity, a push, and a draft pull request unless the task already has one.
 */
public final class Delivery {

    public record Commit(String subject, String body, List<String> trailers) {
    }

    /**
     * @param files     paths the commit changed; empty when the run changed nothing, and then nothing was committed or pushed
     * @param commitSha null when nothing was committed
     * @param prUrl     the task's pull request; null when nothing has been delivered yet
     */
    public record Result(List<String> files, String commitSha, String prUrl) {
    }

    private final Git git;
    private final Gh gh;
    private final String authorName;
    private final String authorEmail;

    public Delivery(Git git, Gh gh, String authorName, String authorEmail) {
        this.git = git;
        this.gh = gh;
        this.authorName = authorName;
        this.authorEmail = authorEmail;
    }

    /** The commit a run starts from; {@link #deliver} commits everything since then. */
    public String head(Path worktree) {
        return git.run(worktree, "rev-parse", "HEAD");
    }

    /** @param existingPrUrl the task's pull request from an earlier delivery, null if none */
    public Result deliver(Path worktree, long taskId, String baseBranch, String startSha, Commit commit, String existingPrUrl) {
        String branch = "dispatch/" + taskId;
        if (!head(worktree).equals(startSha)) {
            // The agent committed despite its rules. Without this, its work would be pushed as someone else's commits,
            // or reported as "no changes" because the working tree is clean.
            Log.warn("delivery.agent_commits_folded", "task", taskId, "start", startSha);
            git.run(worktree, "reset", "--soft", startSha);
        }
        git.run(worktree, "add", "--all");
        List<String> files = git.run(worktree, "diff", "--cached", "--name-only").lines().filter(line -> !line.isBlank()).toList();
        if (files.isEmpty()) {
            return new Result(List.of(), null, existingPrUrl);
        }

        // Hooks and signing belong to people's own setups; a delivery must behave the same on every run.
        List<String> args = new ArrayList<>(List.of("-c", "user.name=" + authorName, "-c", "user.email=" + authorEmail,
                "-c", "commit.gpgsign=false", "commit", "--quiet", "--no-verify", "-m", commit.subject()));
        if (!commit.body().isBlank()) {
            args.addAll(List.of("-m", commit.body()));
        }
        commit.trailers().forEach(trailer -> args.addAll(List.of("--trailer", trailer)));
        git.run(worktree, args.toArray(String[]::new));
        String commitSha = head(worktree);

        git.run(worktree, "push", "--quiet", "--no-verify", "origin", branch + ":refs/heads/" + branch);
        String prUrl = existingPrUrl != null
                ? existingPrUrl
                : gh.createDraftPullRequest(worktree, baseBranch, branch, commit.subject(), commit.body());
        Log.info("delivery.done", "task", taskId, "commit", commitSha, "files", files.size(), "pr", prUrl);
        return new Result(files, commitSha, prUrl);
    }
}
