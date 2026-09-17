package dispatch.workspace;

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

    private final Path stateDir;
    private final Git git;
    private final Map<String, ReentrantLock> repoLocks = new ConcurrentHashMap<>();

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
            for (String sub : List.of("repos", "worktrees", "runs", "splits")) {
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

    public Path runLogBase(long taskId, int seq) {
        return stateDir.resolve("runs").resolve(Long.toString(taskId)).resolve(Integer.toString(seq));
    }

    /** Empty when tasks can run. The clone is made by hand, or is the developer's own (ADR 0014). */
    public Optional<String> unavailableReason(Config.Project project) {
        Path repo = repo(project);
        if (Files.exists(repo.resolve(".git"))) {
            return Optional.empty();
        }
        return Optional.of(project.repo() == null
                ? "no git clone at " + repo
                : "no clone at " + repo + " (git clone " + project.repo() + " " + repo + ")");
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
