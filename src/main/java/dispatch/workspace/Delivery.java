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
        List<String> files = stageAll(worktree);
        if (files.isEmpty()) {
            return new Result(List.of(), null, existingPrUrl);
        }
        commit(worktree, commit);
        String commitSha = head(worktree);

        push(worktree, branch);
        String prUrl = existingPrUrl != null
                ? existingPrUrl
                : gh.createDraftPullRequest(worktree, baseBranch, branch, commit.subject(), commit.body());
        Log.info("delivery.done", "task", taskId, "commit", commitSha, "files", files.size(), "pr", prUrl);
        return new Result(files, commitSha, prUrl);
    }

    /**
     * Finishes a delivery that failed part-way, without the agent (ADR 0008): whatever the pushed branch lacks becomes one
     * commit, which is pushed, and the pull request is opened if it never was. Each step that already happened is skipped:
     * a commit that was made but not pushed is folded into the new one, so the run still becomes one commit.
     *
     * @param baseSha where the task's branch started, for a branch that was never pushed
     * @return the files the task's branch changes; empty when there is nothing to deliver at all
     */
    public Result redeliver(Path worktree, long taskId, String baseBranch, String baseSha, Commit commit, String existingPrUrl) {
        String branch = "dispatch/" + taskId;
        String pushed = pushedHead(worktree, branch);
        String start = pushed != null ? pushed : baseSha;
        if (!head(worktree).equals(start)) {
            git.run(worktree, "reset", "--soft", start);
        }
        if (!stageAll(worktree).isEmpty()) {
            commit(worktree, commit);
        }
        String head = head(worktree);
        if (head.equals(baseSha)) {
            return new Result(List.of(), null, existingPrUrl);
        }
        if (!head.equals(pushed)) {
            push(worktree, branch);
        }
        String prUrl = existingPrUrl != null
                ? existingPrUrl
                : gh.createDraftPullRequest(worktree, baseBranch, branch, commit.subject(), commit.body());
        List<String> files = git.run(worktree, "diff", "--name-only", baseSha, head).lines().filter(line -> !line.isBlank()).toList();
        Log.info("delivery.redone", "task", taskId, "commit", head, "files", files.size(), "pr", prUrl);
        return new Result(files, head, prUrl);
    }

    /** The branch's commit on origin, null if it was never pushed. */
    private String pushedHead(Path worktree, String branch) {
        String listed = git.run(worktree, "ls-remote", "origin", "refs/heads/" + branch);
        return listed.isEmpty() ? null : listed.split("\\s+")[0];
    }

    /** Stages everything and returns the staged paths. */
    private List<String> stageAll(Path worktree) {
        git.run(worktree, "add", "--all");
        return git.run(worktree, "diff", "--cached", "--name-only").lines().filter(line -> !line.isBlank()).toList();
    }

    private void commit(Path worktree, Commit commit) {
        // Hooks and signing belong to people's own setups; a delivery must behave the same on every run.
        List<String> args = new ArrayList<>(List.of("-c", "user.name=" + authorName, "-c", "user.email=" + authorEmail,
                "-c", "commit.gpgsign=false", "commit", "--quiet", "--no-verify", "-m", commit.subject()));
        if (!commit.body().isBlank()) {
            args.addAll(List.of("-m", commit.body()));
        }
        if (!commit.trailers().isEmpty()) {
            // Their own paragraph: --trailer appends to a last body paragraph that merely looks like trailers
            // ("Summary: ..."), which turned a summary line into a trailer in a recorded delivery.
            args.addAll(List.of("-m", String.join("\n", commit.trailers())));
        }
        git.run(worktree, args.toArray(String[]::new));
    }

    private void push(Path worktree, String branch) {
        git.run(worktree, "push", "--quiet", "--no-verify", "origin", branch + ":refs/heads/" + branch);
    }
}
