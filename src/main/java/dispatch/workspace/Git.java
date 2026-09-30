package dispatch.workspace;

import dispatch.ProcessTrees;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Runs git without ever waiting on a prompt; with a GitHub token, credentials come from the environment. */
public final class Git {

    /** Reads GH_TOKEN at call time so the token never appears in a command line (visible to `ps`). */
    private static final String CREDENTIAL_HELPER =
            "credential.helper=!f() { echo username=x-access-token; echo \"password=$GH_TOKEN\"; }; f";
    /**
     * Agents write to the clones this git runs in; hooks (post-commit runs even with --no-verify) and an fsmonitor
     * planted there would otherwise run here, outside the sandbox, with GH_TOKEN in the environment.
     */
    private static final List<String> IGNORE_PLANTED_CODE = List.of("-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false");
    private static final ExecutorService OUTPUT_READERS = Executors.newVirtualThreadPerTaskExecutor();

    private final String command;
    private final String ghToken;
    private final Duration timeout;

    /** @param ghToken null to rely on the OS user's own git credentials */
    public Git(String command, String ghToken, Duration timeout) {
        this.command = command;
        this.ghToken = ghToken;
        this.timeout = timeout;
    }

    /** The same git for a slower command, such as cloning a large repository. */
    public Git withTimeout(Duration slowTimeout) {
        return new Git(command, ghToken, slowTimeout);
    }

    public record Result(int exitCode, String stdout, String stderr) {
    }

    /** Runs git and returns trimmed stdout; a non-zero exit fails with git's own error output. */
    public String run(Path dir, String... args) {
        Result result = execute(dir, args);
        if (result.exitCode() != 0) {
            String output = result.stderr().isBlank() ? result.stdout() : result.stderr();
            throw new WorkspaceException(describe(args) + " failed (exit " + result.exitCode() + "): " + output.strip());
        }
        return result.stdout().strip();
    }

    /** Runs git and returns whatever happened, for commands whose exit code is an answer (e.g. check-ignore). */
    public Result execute(Path dir, String... args) {
        refuseRedirected(dir);
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        commandLine.addAll(IGNORE_PLANTED_CODE);
        if (ghToken != null) {
            commandLine.addAll(List.of("-c", "credential.helper=", "-c", CREDENTIAL_HELPER));
        }
        commandLine.addAll(Arrays.asList(args));
        return runProcess(commandLine, dir, ghToken, timeout, describe(args));
    }

    /**
     * Runs any command the way git and gh are run: nothing may prompt, the token travels only in the environment, and a hang
     * is killed. Throws {@link WorkspaceException} when the command cannot start or times out.
     */
    public static Result runProcess(List<String> commandLine, Path dir, String ghToken, Duration timeout, String description) {
        ProcessBuilder builder = new ProcessBuilder(commandLine).directory(dir.toFile());
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        if (ghToken != null) {
            builder.environment().put("GH_TOKEN", ghToken);
        }
        Process process;
        try {
            process = builder.start();
            process.getOutputStream().close();
        } catch (IOException e) {
            throw new WorkspaceException("cannot start " + description + ": " + e.getMessage(), e);
        }
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()), OUTPUT_READERS);
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()), OUTPUT_READERS);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                ProcessTrees.terminate(process.toHandle(), Duration.ofSeconds(2));
                throw new WorkspaceException(description + " timed out after " + timeout.toSeconds() + "s");
            }
            return new Result(process.exitValue(), stdout.join(), stderr.join());
        } catch (InterruptedException e) {
            ProcessTrees.terminate(process.toHandle(), Duration.ofSeconds(2));
            Thread.currentThread().interrupt();
            throw new WorkspaceException(description + " was interrupted", e);
        }
    }

    /**
     * Agents write to the clone's git dir. A {@code commondir} file there, or a linked worktree's admin {@code commondir}
     * naming anything but its own clone, would make this git read a config the agent wrote (remote URL, credential
     * helper, core.sshCommand) with GH_TOKEN in the environment. A dir without .git (a clone's parent) has nothing to check.
     */
    static void refuseRedirected(Path dir) {
        Path dotGit = dir.resolve(".git");
        if (Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            refuseCommondir(dotGit);
            return;
        }
        if (!Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        String line = readFile(dotGit);
        if (!line.startsWith("gitdir: ")) {
            throw new WorkspaceException("refusing to run git: " + dotGit + " does not name a git dir");
        }
        Path gitDir = dir.resolve(line.substring("gitdir: ".length())).normalize();
        Path parent = gitDir.getParent();
        boolean linkedWorktree = parent != null && parent.getParent() != null && parent.getFileName().toString().equals("worktrees");
        if (!linkedWorktree) {
            // A submodule or --separate-git-dir: the git dir is its own common dir.
            refuseCommondir(gitDir);
            return;
        }
        Path adminCommondir = gitDir.resolve("commondir");
        Path commonDir = realPath(parent.getParent());
        if (commonDir == null || !Files.isRegularFile(adminCommondir, LinkOption.NOFOLLOW_LINKS)
                || !commonDir.equals(realPath(gitDir.resolve(readFile(adminCommondir))))) {
            throw new WorkspaceException("refusing to run git: " + adminCommondir + " redirects the repository");
        }
        refuseCommondir(parent.getParent());
    }

    private static void refuseCommondir(Path commonDir) {
        Path commondir = commonDir.resolve("commondir");
        if (Files.exists(commondir, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("refusing to run git: " + commondir + " redirects the repository");
        }
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            throw new WorkspaceException("refusing to run git: cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** Resolved so symlinks cannot fake the comparison; null when the path does not exist. */
    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    private static String describe(String... args) {
        return "git " + String.join(" ", args);
    }

    private static String read(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
