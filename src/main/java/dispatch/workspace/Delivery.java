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

    /**
     * The delivery failed after its commit: the task's branch is now at {@code commitSha}, Dispatch's own commit, which a
     * DELIVER retry must find it at.
     */
    public static final class CommittedException extends WorkspaceException {

        private final String commitSha;

        CommittedException(WorkspaceException cause, String commitSha) {
            super(cause.getMessage(), cause);
            this.commitSha = commitSha;
        }

        public String commitSha() {
            return commitSha;
        }
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

    /** The commit the task's branch itself is at; the worktree's HEAD follows it only while it is checked out there. */
    public String branchHead(Path worktree, String branch) {
        return git.run(worktree, "rev-parse", "--verify", "refs/heads/" + branch);
    }

    /** The ref the worktree's HEAD names, e.g. {@code refs/heads/dispatch/7}; null when HEAD is detached. */
    public String checkedOutRef(Path worktree) {
        Git.Result result = git.execute(worktree, "symbolic-ref", "-q", "HEAD");
        return result.exitCode() == 0 ? result.stdout().strip() : null;
    }

    /** The change since {@code startSha}, new files included, for the verify loop's reviewer. */
    public String diff(Path worktree, String startSha) {
        // Marks new files as intended so git diff shows them; delivery adds everything anyway.
        git.run(worktree, "add", "--intent-to-add", "--all");
        return git.run(worktree, "diff", startSha);
    }

    /** @param existingPrUrl the task's pull request from an earlier delivery, null if none */
    public Result deliver(Path worktree, long taskId, String branch, String baseBranch, String startSha, Commit commit,
                          String existingPrUrl) {
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
        requireOpen(worktree, existingPrUrl);
        commit(worktree, commit);
        String commitSha = head(worktree);

        String prUrl;
        try {
            push(worktree, branch);
            prUrl = existingPrUrl != null
                    ? existingPrUrl
                    : gh.createDraftPullRequest(worktree, baseBranch, branch, commit.subject(), commit.body());
        } catch (WorkspaceException e) {
            throw new CommittedException(e, commitSha);
        }
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
    public Result redeliver(Path worktree, long taskId, String branch, String baseBranch, String baseSha, Commit commit,
                            String existingPrUrl) {
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
        String prUrl;
        try {
            if (!head.equals(pushed)) {
                requireOpen(worktree, existingPrUrl);
                push(worktree, branch);
            }
            prUrl = existingPrUrl != null
                    ? existingPrUrl
                    : gh.createDraftPullRequest(worktree, baseBranch, branch, commit.subject(), commit.body());
        } catch (WorkspaceException e) {
            throw new CommittedException(e, head);
        }
        List<String> files = git.run(worktree, "diff", "--name-only", baseSha, head).lines().filter(line -> !line.isBlank()).toList();
        Log.info("delivery.redone", "task", taskId, "commit", head, "files", files.size(), "pr", prUrl);
        return new Result(files, head, prUrl);
    }

    /**
     * More commits reach a task's pull request only while it is open. Pushed onto one merged or closed on GitHub, a follow-up
     * would be reported as delivered while it reaches nothing; the Merge button records its own merges, so this catches a
     * merge done on GitHub. Checked only when there is something to push; nothing is committed or pushed after it
     * fails, so the follow-up's changes stay in the worktree. A lookup GitHub cannot answer blocks nothing: this guards a
     * rare case, and follow-ups were delivered without it before.
     */
    private void requireOpen(Path worktree, String prUrl) {
        if (prUrl == null) {
            return;
        }
        String state;
        try {
            state = gh.pullRequestState(worktree, prUrl);
        } catch (WorkspaceException e) {
            Log.warn("delivery.pr_state_unknown", "pr", prUrl, "error", e.getMessage());
            return;
        }
        if (!state.equals("OPEN")) {
            throw new WorkspaceException("pull request " + prUrl + " is " + state.toLowerCase(java.util.Locale.ROOT)
                    + ", so this follow-up was not delivered; give it as a new task");
        }
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
