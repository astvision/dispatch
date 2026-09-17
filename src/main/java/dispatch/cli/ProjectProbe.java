package dispatch.cli;

import dispatch.config.ConfigLoader;
import dispatch.workspace.Git;
import dispatch.workspace.WorkspaceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * What a clone says about itself: where it is, its origin, and the branch tasks should start from.
 *
 * @param originUrl null when there is no origin, or when its URL holds credentials, which never go into the config
 */
record ProjectProbe(Path folder, String defaultName, String originUrl, boolean originHadCredentials, String defaultBranch) {

    /** @param given as typed; "~" stands for the home directory on every OS */
    static ProjectProbe of(Path given, Git git) {
        Path folder = expandHome(given).toAbsolutePath().normalize();
        if (!Files.exists(folder.resolve(".git"))) {
            throw new CliException(folder + " is not a git clone");
        }
        Optional<String> origin = output(git, folder, "remote", "get-url", "origin");
        boolean credentials = origin.map(ConfigLoader::hasCredentials).orElse(false);
        return new ProjectProbe(folder, name(folder), credentials ? null : origin.orElse(null), credentials,
                defaultBranch(git, folder).orElse(null));
    }

    /** origin's default branch as last fetched, else as origin says now, else the branch checked out. */
    private static Optional<String> defaultBranch(Git git, Path folder) {
        Optional<String> fetched = output(git, folder, "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD")
                .map(ref -> ref.replaceFirst("^origin/", ""));
        if (fetched.isPresent()) {
            return fetched;
        }
        Optional<String> remote = output(git, folder, "ls-remote", "--symref", "origin", "HEAD")
                .flatMap(listing -> listing.lines().filter(line -> line.startsWith("ref: refs/heads/")).findFirst())
                .map(line -> line.substring("ref: refs/heads/".length()).split("\\s")[0]);
        if (remote.isPresent()) {
            return remote;
        }
        return output(git, folder, "rev-parse", "--abbrev-ref", "HEAD").filter(branch -> !branch.equals("HEAD"));
    }

    private static Optional<String> output(Git git, Path folder, String... args) {
        try {
            Git.Result result = git.execute(folder, args);
            return result.exitCode() == 0 && !result.stdout().isBlank() ? Optional.of(result.stdout().strip()) : Optional.empty();
        } catch (WorkspaceException e) {
            throw new CliException("cannot run git in " + folder + " (" + e.getMessage() + "); is git installed and on PATH?");
        }
    }

    /** The folder's name, reduced to the characters a project name may have. */
    private static String name(Path folder) {
        String name = folder.getFileName() == null ? "" : folder.getFileName().toString().replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return name.isEmpty() ? "project" : name;
    }

    static Path expandHome(Path given) {
        String text = given.toString();
        if (text.equals("~") || text.startsWith("~/") || text.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home") + text.substring(1));
        }
        return given;
    }
}
