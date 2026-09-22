package dispatch.ui;

import dispatch.cli.CliException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The folder browser of setup's project step, on the machine that runs Dispatch, since the browser may be on another
 * computer. It lists folder names only, never file contents (spec: Security); hidden folders are left out.
 */
final class Folders {

    static final int MAX_ENTRIES = 500;

    record Entry(String name, String path, boolean gitClone) {
    }

    /** @param parent null at a root */
    record Listing(String path, String parent, List<Entry> folders, boolean truncated) {
    }

    private Folders() {
    }

    /** @param requested null or blank for {@code home}; a leading "~" stands for {@code home} */
    static Listing list(String requested, Path home) {
        Path dir = folder(requested, home);
        if (!Files.isDirectory(dir)) {
            throw new CliException(dir + " is not a folder");
        }
        List<Entry> folders = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                folders.add(new Entry(name, child.toString(), Files.exists(child.resolve(".git"))));
            }
        } catch (AccessDeniedException e) {
            throw new CliException("cannot open " + dir + ": permission denied");
        } catch (java.nio.file.DirectoryIteratorException e) {
            throw new CliException("cannot open " + dir + ": " + e.getCause().getMessage());
        } catch (IOException e) {
            throw new CliException("cannot open " + dir + ": " + e.getMessage());
        }
        folders.sort(Comparator.comparing(entry -> entry.name().toLowerCase(Locale.ROOT)));
        boolean truncated = folders.size() > MAX_ENTRIES;
        List<Entry> result = truncated ? folders.subList(0, MAX_ENTRIES) : folders;
        Path parent = dir.getParent();
        return new Listing(dir.toString(), parent == null ? null : parent.toString(), List.copyOf(result), truncated);
    }

    private static Path folder(String requested, Path home) {
        if (requested == null || requested.isBlank()) {
            return home.toAbsolutePath().normalize();
        }
        String text = requested.strip();
        try {
            if (text.equals("~") || text.startsWith("~/") || text.startsWith("~\\")) {
                return Path.of(home + text.substring(1)).toAbsolutePath().normalize();
            }
            Path given = Path.of(text);
            // A relative path is the browser's home-relative listing (e.g. typed from a folder name shown there),
            // not the server process's own working directory, which the page never shows.
            return (given.isAbsolute() ? given : home.resolve(given)).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new CliException(text + " is not a folder");
        }
    }
}
