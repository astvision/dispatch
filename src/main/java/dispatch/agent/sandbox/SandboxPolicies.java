package dispatch.agent.sandbox;

import dispatch.agent.AgentStartException;
import dispatch.agent.RunRequest;
import dispatch.domain.RunKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** What each run may touch on this machine (spec: 2026-09-30-agent-sandbox-design, "The policy"). */
public final class SandboxPolicies {

    /** Writable so builds fetch new dependencies as they do outside the sandbox; a poisoned cache is the accepted cost. */
    private static final List<String> CACHES = List.of(".m2", ".gradle", ".npm", ".cache");
    /**
     * Inside the writable dirs, but run as code by a later agent or build outside any sandbox: hooks, notify commands,
     * MCP servers, init scripts, JVM args. ~/.claude.json stays writable (Claude Code writes it), so its mcpServers are not.
     */
    private static final List<String> CODE_IN_WRITABLE = List.of(".claude/settings.json", ".claude/settings.local.json",
            ".codex/config.toml", ".gemini/settings.json", ".gradle/init.d", ".gradle/gradle.properties", ".m2/settings.xml");
    private static final List<String> SECRETS = List.of(".ssh", ".config/gh", ".gnupg");
    private static final String GITDIR_PREFIX = "gitdir: ";
    private static final Set<RunKind> IN_WORKTREE = Set.of(RunKind.PLAN, RunKind.EXECUTE, RunKind.REVIEW);
    private static final List<String> GIT_CONTROL = List.of("config", "hooks", "info", "config.worktree");

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

    /** As {@link #forRun(RunRequest, List, Map)} for an environment without XDG_RUNTIME_DIR. */
    public SandboxPolicy forRun(RunRequest request, List<String> agentStateInHome) {
        return forRun(request, agentStateInHome, Map.of());
    }

    /**
     * @param agentStateInHome the agent's own files, relative to home, e.g. ".claude" and ".claude.json"
     * @param environment      the agent's environment; its XDG_RUNTIME_DIR is hidden
     */
    public SandboxPolicy forRun(RunRequest request, List<String> agentStateInHome, Map<String, String> environment) {
        Path workdir = request.workdir().toAbsolutePath().normalize();
        List<Path> writable = new ArrayList<>();
        agentStateInHome.forEach(entry -> writable.add(home.resolve(entry)));
        CACHES.forEach(cache -> writable.add(home.resolve(cache)));
        List<Path> readOnly = new ArrayList<>(request.readOnlyDirs());
        // Codex reads its --output-schema from beside the run's log, under the hidden state dir. Only that file: the log
        // dir holds other runs' logs, and for the assistant every member's conversations. A regular file outside the
        // workdir only, so an agent-planted symlink cannot mount anything it names.
        Path schema = Path.of(request.logBase().toAbsolutePath().normalize() + ".schema.json");
        if (!schema.startsWith(workdir) && Files.isRegularFile(schema, LinkOption.NOFOLLOW_LINKS)) {
            readOnly.add(schema);
        }
        CODE_IN_WRITABLE.forEach(entry -> readOnly.add(home.resolve(entry)));
        if (request.kind() == RunKind.ASSISTANT) {
            // dispatch ask reads the state file, and SQLite writes -shm even to read a WAL database (AssistantHome).
            Path database = stateDir.resolve("dispatch.db");
            writable.addAll(List.of(database, stateDir.resolve("dispatch.db-wal"), stateDir.resolve("dispatch.db-shm")));
            readOnly.add(stateDir.resolve("assistant-bin"));
        }
        // bwrap cannot create a mount point on the read-only root: a directory that does not exist has nothing to hide.
        List<Path> hidden = Stream.of(SECRETS.stream().map(home::resolve), dispatchPrivate.stream(), runtimeDir(environment))
                .flatMap(paths -> paths)
                .filter(Files::isDirectory)
                .toList();
        // Only a task's worktree has a git dir. A split's or the assistant's workdir is agent-writable and no worktree:
        // a .git an earlier run planted there would otherwise mount any configured clone read-write.
        GitLink git = IN_WORKTREE.contains(request.kind()) ? gitLink(workdir) : null;
        if (git == null) {
            return new SandboxPolicy(workdir, null, null, readOnly, writable, hidden);
        }
        // Mounted after the workdir and the git dir: the agent cannot point the next run's mounts somewhere else, nor
        // plant config (core.fsmonitor, filters, remotes) or hooks that Dispatch's own git runs outside the sandbox.
        readOnly.add(workdir.resolve(".git"));
        GIT_CONTROL.forEach(name -> readOnly.add(git.commonDir().resolve(name)));
        // Other worktrees' admin dirs: a rewritten commondir there would redirect Dispatch's git in that worktree.
        readOnly.add(git.commonDir().resolve("worktrees"));
        return new SandboxPolicy(workdir, git.commonDir(), git.worktreeAdmin(), readOnly, writable, hidden);
    }

    /**
     * The owner's sockets live here: systemd --user (systemd-run escapes any sandbox), ssh-agent, gpg-agent, D-Bus and
     * the Secret Service. /run/user/&lt;uid&gt; when XDG_RUNTIME_DIR is unset; nothing when the uid cannot be read.
     */
    private Stream<Path> runtimeDir(Map<String, String> environment) {
        String fromEnvironment = environment.get("XDG_RUNTIME_DIR");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return Stream.of(Path.of(fromEnvironment).toAbsolutePath());
        }
        try {
            return Stream.of(Path.of("/run/user", String.valueOf(Files.getAttribute(home, "unix:uid"))));
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return Stream.empty();
        }
    }

    /**
     * A worktree's .git is a file naming {@code <common>/worktrees/<name>}; git needs the common dir to read objects
     * and update the index. Null when the workdir is not a worktree or holds its own .git.
     * The file lives in the agent-writable workdir, so the common dir it names must be a configured clone's .git or
     * {@code <stateDir>/repos/<name>/.git}: anything else would be mounted read-write over the hidden directories.
     */
    Path gitCommonDir(Path workdir) {
        GitLink git = gitLink(workdir);
        return git == null ? null : git.commonDir();
    }

    /** @param worktreeAdmin {@code <commonDir>/worktrees/<name>} for a linked worktree, else null */
    private record GitLink(Path commonDir, Path worktreeAdmin) {
    }

    private GitLink gitLink(Path workdir) {
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
        if (!linkedWorktree) {
            return new GitLink(allowedGitDir(dotGit, gitDir), null);
        }
        Path commonDir = allowedGitDir(dotGit, parent.getParent());
        return new GitLink(commonDir, commonDir.resolve("worktrees").resolve(gitDir.getFileName()));
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
