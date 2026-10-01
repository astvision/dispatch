package dispatch.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Text files shipped in the jar under one resource root and listed in its {@code files.txt}, written out at startup: the
 * assistant's home (A-1) and the skills plugin (spec: agent skills).
 */
public final class BundledFiles {

    private static final String FILE_LIST = "files.txt";

    private BundledFiles() {
    }

    /** The files under {@code root}, relative to it, as its {@code files.txt} lists them. */
    public static List<String> list(String root) {
        return read(root + "/" + FILE_LIST).lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    /** Writes every listed file under {@code dir}, over whatever is there. */
    public static void copy(String root, Path dir) {
        for (String file : list(root)) {
            write(dir.resolve(file), read(root + "/" + file));
        }
    }

    /** @param name an absolute resource name, e.g. {@code /assistant/CLAUDE.md} */
    public static String read(String name) {
        try (InputStream in = BundledFiles.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("resource missing: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
