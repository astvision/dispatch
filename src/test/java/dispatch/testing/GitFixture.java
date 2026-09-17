package dispatch.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A bare "origin" with one commit on main, a "seed" clone to push further commits from, and a Dispatch-owned clone
 * at stateDir/repos/&lt;project&gt;.
 */
public final class GitFixture {

    public final Path origin;
    public final Path seed;
    public final Path stateDir;

    private GitFixture(Path origin, Path seed, Path stateDir) {
        this.origin = origin;
        this.seed = seed;
        this.stateDir = stateDir;
    }

    public static GitFixture create(Path dir, String project) throws IOException {
        GitFixture fixture = new GitFixture(dir.resolve("origin.git"), dir.resolve("seed"), dir.resolve("state"));
        Files.createDirectories(fixture.stateDir.resolve("repos"));
        sh(dir, "git", "init", "--quiet", "--bare", "-b", "main", fixture.origin.toString());
        sh(dir, "git", "clone", "--quiet", fixture.origin.toString(), fixture.seed.toString());
        // fake-* : what the fake claude and gh scripts record in their working directory must never be delivered.
        Files.writeString(fixture.seed.resolve(".gitignore"), ".env\nlocal/\nfake-*\n");
        Files.writeString(fixture.seed.resolve("README.md"), "v1\n");
        fixture.commitAndPush("initial");
        sh(dir, "git", "clone", "--quiet", fixture.origin.toString(), fixture.repo(project).toString());
        return fixture;
    }

    public Path repo(String project) {
        return stateDir.resolve("repos").resolve(project);
    }

    /** Commits everything in the seed clone, pushes it to origin and returns the new commit. */
    public String commitAndPush(String message) {
        sh(seed, "git", "add", "-A");
        sh(seed, "git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "--quiet", "-m", message);
        sh(seed, "git", "push", "--quiet", "origin", "main");
        return sh(seed, "git", "rev-parse", "HEAD");
    }

    public static String sh(Path workdir, String... command) {
        try {
            Process process = new ProcessBuilder(command).directory(workdir.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).strip();
            if (process.waitFor() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
