package dispatch.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dispatch's git runs outside the sandbox with GH_TOKEN in a clone an agent wrote to; config or hooks planted there
 * must not run.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the planted hook and fsmonitor are POSIX shell scripts")
class GitTest {

    @TempDir
    Path dir;

    private Git git;
    private Path repo;
    private Path ran;

    @BeforeEach
    void plantHookAndFsmonitor() throws IOException {
        git = new Git("git", null, Duration.ofSeconds(30));
        repo = Files.createDirectories(dir.resolve("repo"));
        ran = dir.resolve("ran");
        git.run(repo, "init", "-q", "-b", "main");
        Path script = dir.resolve("planted.sh");
        Files.writeString(script, "#!/bin/sh\necho \"$0 $*\" >> '" + ran + "'\n");
        script.toFile().setExecutable(true);
        Files.copy(script, repo.resolve(".git/hooks/post-commit"));
        repo.resolve(".git/hooks/post-commit").toFile().setExecutable(true);
        git.run(repo, "config", "core.fsmonitor", script.toString());
    }

    @Test
    void aPlantedFsmonitorDoesNotRun() {
        git.run(repo, "status", "--porcelain");

        assertFalse(Files.exists(ran), "fsmonitor ran");
    }

    @Test
    void aPlantedPostCommitHookDoesNotRunThoughNoVerifyCannotStopIt() {
        git.run(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "--no-verify", "-m", "x");

        assertFalse(Files.exists(ran), "post-commit hook ran");
    }
}
