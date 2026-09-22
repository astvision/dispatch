package dispatch.workspace;

import dispatch.Log;
import dispatch.OwnerOnly;
import dispatch.config.Config;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The state directory's working areas: Dispatch-owned clones under repos/, one git worktree per task under
 * worktrees/, and per-run agent logs under runs/.
 */
public final class Workspaces {

    public record PreparedWorktree(Path path, String baseSha) {
    }

    /**
     * What removing a task's worktree would lose.
     *
     * @param uncommitted changed and untracked paths, as git status lists them; git-ignored files (copyFiles) are not
     * @param pushed      every commit on the task's branch is on origin, or there is none
     */
    public record WorktreeState(List<String> uncommitted, boolean pushed) {

        public boolean disposable() {
            return uncommitted.isEmpty() && pushed;
        }
    }

    private final Path stateDir;
    private final Git git;
    private final Map<String, ReentrantLock> repoLocks = new ConcurrentHashMap<>();
    /** Why a project whose clone Dispatch is making cannot take tasks yet: cloning, or the clone failed. */
    private final Map<String, String> cloning = new ConcurrentHashMap<>();

    public Workspaces(Path stateDir, Git git) {
        this.stateDir = stateDir;
        this.git = git;
    }

    /**
     * Creates the state layout owner-only (task text, plans and agent transcripts live here). Returns a warning when the
     * state directory itself is open to other users; group access, as systemd's StateDirectoryMode=0750 gives, is fine.
     */
    public Optional<String> createDirectories() {
        try {
            for (String sub : List.of("repos", "worktrees", "runs", "splits", "attachments")) {
                OwnerOnly.createDirectories(stateDir.resolve(sub));
            }
            return OwnerOnly.othersAccess(stateDir).map(permissions -> "state directory " + stateDir
                    + " is accessible to other users (" + permissions + "); run: chmod o-rwx " + stateDir);
        } catch (IOException e) {
            throw new WorkspaceException("cannot create state directories under " + stateDir + ": " + e.getMessage(), e);
        }
    }

    /** The project's clone: its configured path, or {@code repos/<name>} under the state directory. */
    public Path repo(Config.Project project) {
        return project.path() != null ? Path.of(project.path()) : stateDir.resolve("repos").resolve(project.name());
    }

    public Path worktree(long taskId) {
        return stateDir.resolve("worktrees").resolve(Long.toString(taskId));
    }

    /** Where a run's raw agent output goes; the agent appends its own extensions. */
    /** Where splits run (ADR 0013): an empty directory of their own, which also keeps their logs. */
    public Path splitsDir() {
        return stateDir.resolve("splits");
    }

    /** A task's downloaded attachments: outside its worktree, so a delivery never commits them. */
    public Path attachmentsDir(long taskId) {
        return stateDir.resolve("attachments").resolve(Long.toString(taskId));
    }

    public Path runLogBase(long taskId, int seq) {
        return stateDir.resolve("runs").resolve(Long.toString(taskId)).resolve(Integer.toString(seq));
    }

    /** Empty when tasks can run. The clone is Dispatch's own ({@link #cloneMissing}), or the developer's (ADR 0014). */
    public Optional<String> unavailableReason(Config.Project project) {
        Path repo = repo(project);
        if (Files.exists(repo.resolve(".git"))) {
            return Optional.empty();
        }
        String status = cloning.get(project.name());
        if (status != null) {
            return Optional.of(status);
        }
        return Optional.of(project.repo() == null
                ? "no git clone at " + repo
                : "no clone at " + repo + " (git clone " + project.repo() + " " + repo + ")");
    }

    /** Whether Dispatch makes this project's clone: it has a repo URL, no path of the developer's own, and no clone yet. */
    public boolean needsClone(Config.Project project) {
        return project.repo() != null && project.path() == null && !Files.exists(repo(project).resolve(".git"));
    }

    /**
     * Clones the project into {@code repos/<name>}; the project cannot take tasks until it is done. Blocks, so callers run it
     * in the background. A failure leaves the project unavailable with git's error as the reason, until the next start.
     *
     * @param slowGit git with a timeout long enough for the repository's size
     */
    public void cloneMissing(Config.Project project, Git slowGit) {
        Path repo = repo(project);
        cloning.put(project.name(), "cloning " + project.repo() + " into " + repo);
        Log.info("project.cloning", "project", project.name(), "repo", project.repo());
        try {
            slowGit.run(repo.getParent(), "clone", "--quiet", project.repo(), repo.toString());
            cloning.remove(project.name());
            Log.info("project.cloned", "project", project.name(), "repo", repo);
        } catch (WorkspaceException e) {
            cloning.put(project.name(), "cloning " + project.repo() + " failed: " + e.getMessage());
            Log.error("project.clone_failed", null, "project", project.name(), "error", e.getMessage());
        }
    }

    /** Fetches the base branch and adds worktrees/&lt;task&gt; on a new branch dispatch/&lt;task&gt; at origin/&lt;base&gt;. */
    public PreparedWorktree createWorktree(Config.Project project, long taskId) {
        Path repo = repo(project);
        Path worktree = worktree(taskId);
        if (Files.exists(worktree)) {
            throw new WorkspaceException("worktree " + worktree + " already exists; refusing to reuse it");
        }
        // Concurrent fetch/worktree add in one clone fail on git's own lock files (.git/config, refs).
        // ponytail: in-process lock per project, enough because one Dispatch process owns repos/ (ADR 0005).
        ReentrantLock lock = repoLocks.computeIfAbsent(project.name(), name -> new ReentrantLock());
        lock.lock();
        try {
            git.run(repo, "fetch", "origin", project.baseBranch());
            git.run(repo, "worktree", "add", "--quiet", "-b", "dispatch/" + taskId, worktree.toString(),
                    "origin/" + project.baseBranch());
            return new PreparedWorktree(worktree, git.run(worktree, "rev-parse", "HEAD"));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adds a removed worktree back on the task's branch, so a later run (a retry, a follow-up) continues where the task
     * stopped. The sweep only removes worktrees whose commits are pushed; a branch that is gone from the clone is fetched.
     */
    public Path recreateWorktree(Config.Project project, long taskId) {
        Path repo = repo(project);
        Path worktree = worktree(taskId);
        String branch = "dispatch/" + taskId;
        ReentrantLock lock = repoLocks.computeIfAbsent(project.name(), name -> new ReentrantLock());
        lock.lock();
        try {
            git.run(repo, "worktree", "prune");
            if (git.execute(repo, "rev-parse", "--verify", "--quiet", "refs/heads/" + branch).exitCode() != 0) {
                git.run(repo, "fetch", "origin", "refs/heads/" + branch + ":refs/heads/" + branch);
            }
            git.run(repo, "worktree", "add", "--quiet", worktree.toString(), branch);
            return worktree;
        } finally {
            lock.unlock();
        }
    }

    /** @param baseSha where the task's branch started: a branch still there has nothing to push */
    public WorktreeState state(Path worktree, long taskId, String baseSha) {
        List<String> uncommitted = git.run(worktree, "status", "--porcelain").lines().filter(line -> !line.isBlank()).toList();
        String head = git.run(worktree, "rev-parse", "HEAD");
        if (head.equals(baseSha)) {
            return new WorktreeState(uncommitted, true);
        }
        String listed = git.run(worktree, "ls-remote", "origin", "refs/heads/dispatch/" + taskId);
        String pushed = listed.isEmpty() ? null : listed.split("\\s+")[0];
        // Someone may have pushed on top of Dispatch's commits, e.g. from the pull request page.
        boolean onOrigin = head.equals(pushed)
                || (pushed != null && git.execute(worktree, "merge-base", "--is-ancestor", head, pushed).exitCode() == 0);
        return new WorktreeState(uncommitted, onOrigin);
    }

    /** Removes the task's worktree, changes and all; its branch stays in the clone for {@link #recreateWorktree}. */
    public void removeWorktree(Config.Project project, long taskId) {
        ReentrantLock lock = repoLocks.computeIfAbsent(project.name(), name -> new ReentrantLock());
        lock.lock();
        try {
            git.run(repo(project), "worktree", "remove", "--force", worktree(taskId).toString());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Copies the project's local-only files (e.g. .env) from its clone. Each must be ignored by git, otherwise a later
     * delivery commit would publish it.
     */
    public void copyFiles(Config.Project project, Path worktree) {
        Path repo = repo(project);
        for (String file : project.copyFiles()) {
            Path source = repo.resolve(file);
            if (!Files.isRegularFile(source)) {
                throw new WorkspaceException("copyFiles: " + file + " not found in " + repo);
            }
            Git.Result ignored = git.execute(worktree, "check-ignore", "--quiet", file);
            if (ignored.exitCode() == 1) {
                throw new WorkspaceException("copyFiles: " + file + " is not ignored by git in " + project.name()
                        + "; refusing to copy it where a commit could pick it up");
            }
            if (ignored.exitCode() != 0) {
                throw new WorkspaceException("git check-ignore " + file + " failed: " + ignored.stderr().strip());
            }
            Path target = worktree.resolve(file);
            try {
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException e) {
                throw new WorkspaceException("copyFiles: cannot copy " + file + ": " + e.getMessage(), e);
            }
        }
    }
}
