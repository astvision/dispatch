package dispatch.agent.sandbox;

import dispatch.agent.AgentStartException;
import dispatch.agent.RunRequest;
import dispatch.domain.RunKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** What each run may touch on this machine (spec: 2026-09-30-agent-sandbox-design, "The policy"). */
public final class SandboxPolicies {

    /** Writable so builds fetch new dependencies as they do outside the sandbox; a poisoned cache is the accepted cost. */
    private static final List<String> CACHES = List.of(".m2", ".gradle", ".npm", ".cache");
    private static final List<String> SECRETS = List.of(".ssh", ".config/gh", ".gnupg");
    private static final String GITDIR_PREFIX = "gitdir: ";

    private final Path home;
    private final Path stateDir;
    private final List<Path> dispatchPrivate;
    private final List<Path> clones;

    /**
     * @param home            the user's home, which the agent's state and caches are relative to
     * @param stateDir        this instance's or worker's state dir; its state file is the assistant's to read
     * @param dispatchPrivate Dispatch's config dir, state dir and every configured project's clone
     * @param clones          the configured clones whose .git a worktree may point at, besides {@code <stateDir>/repos/*}
     */
    public SandboxPolicies(Path home, Path stateDir, List<Path> dispatchPrivate, List<Path> clones) {
        this.home = home;
        this.stateDir = stateDir;
        this.dispatchPrivate = List.copyOf(dispatchPrivate);
        this.clones = List.copyOf(clones);
    }

    /** No configured clones: only worktrees of Dispatch's own {@code <stateDir>/repos/*} get their git dir. */
    public SandboxPolicies(Path home, Path stateDir, List<Path> dispatchPrivate) {
        this(home, stateDir, dispatchPrivate, List.of());
    }

    /** @param agentStateInHome the agent's own files, relative to home, e.g. ".claude" and ".claude.json" */
    public SandboxPolicy forRun(RunRequest request, List<String> agentStateInHome) {
        Path workdir = request.workdir().toAbsolutePath().normalize();
        List<Path> writable = new ArrayList<>();
        agentStateInHome.forEach(entry -> writable.add(home.resolve(entry)));
        CACHES.forEach(cache -> writable.add(home.resolve(cache)));
        List<Path> readOnly = new ArrayList<>(request.readOnlyDirs());
        if (request.kind() == RunKind.ASSISTANT) {
            // dispatch ask reads the state file, and SQLite writes -shm even to read a WAL database (AssistantHome).
            Path database = stateDir.resolve("dispatch.db");
            writable.addAll(List.of(database, stateDir.resolve("dispatch.db-wal"), stateDir.resolve("dispatch.db-shm")));
            readOnly.add(stateDir.resolve("assistant-bin"));
        }
        // bwrap cannot create a mount point on the read-only root: a directory that does not exist has nothing to hide.
        List<Path> hidden = Stream.concat(SECRETS.stream().map(home::resolve), dispatchPrivate.stream())
                .filter(Files::isDirectory)
                .toList();
        Path gitCommonDir = gitCommonDir(workdir);
        if (gitCommonDir != null) {
            // Mounted after the workdir, so the agent cannot point the next run's mounts somewhere else.
            readOnly.add(workdir.resolve(".git"));
        }
        return new SandboxPolicy(workdir, gitCommonDir, readOnly, writable, hidden);
    }

    /**
     * A worktree's .git is a file naming {@code <common>/worktrees/<name>}; git needs the common dir to read objects
     * and update the index. Null when the workdir is not a worktree (a split, the assistant) or holds its own .git.
     * The file lives in the agent-writable workdir, so the common dir it names must be a configured clone's .git or
     * {@code <stateDir>/repos/<name>/.git}: anything else would be mounted read-write over the hidden directories.
     */
    Path gitCommonDir(Path workdir) {
        Path dotGit = workdir.resolve(".git");
        if (!Files.isRegularFile(dotGit)) {
            return null;
        }
        String line;
        try {
            line = Files.readString(dotGit).strip();
        } catch (IOException e) {
            throw new AgentStartException("cannot read " + dotGit + ": " + e.getMessage(), e);
        }
        if (!line.startsWith(GITDIR_PREFIX)) {
            throw new AgentStartException(dotGit + " does not name a git dir: " + line, null);
        }
        Path gitDir = workdir.resolve(line.substring(GITDIR_PREFIX.length())).normalize();
        Path parent = gitDir.getParent();
        boolean linkedWorktree = parent != null && parent.getFileName() != null
                && parent.getFileName().toString().equals("worktrees");
        return allowedGitDir(dotGit, linkedWorktree ? parent.getParent() : gitDir);
    }

    /** The real path of {@code commonDir}, so a symlink swapped in after this check cannot redirect the mount. */
    private Path allowedGitDir(Path dotGit, Path commonDir) {
        Path real = realPath(commonDir);
        boolean stateRepo = real != null && real.getFileName() != null && real.getFileName().toString().equals(".git")
                && real.getParent() != null && real.getParent().getParent() != null
                && real.getParent().getParent().equals(realPath(stateDir.resolve("repos")));
        boolean configuredClone = real != null && clones.stream().anyMatch(clone -> real.equals(realPath(clone.resolve(".git"))));
        if (!stateRepo && !configuredClone) {
            throw new AgentStartException(dotGit + " names " + commonDir
                    + ", which is not the .git of a configured clone or of " + stateDir.resolve("repos") + "/*; refused", null);
        }
        return real;
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }
}
