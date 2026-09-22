package dispatch.config;

import dispatch.Log;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * A config file changed in place: an edited version replaces it only once it validates, atomically, so a mistake or a
 * crash leaves the file as it was.
 */
public final class ConfigFile {

    /** One lock object per config file within this process; a {@link FileLock} alone would throw on an overlapping
     * lock from a second thread in the same JVM instead of waiting for it. */
    private static final Map<Path, Object> IN_PROCESS_LOCKS = new ConcurrentHashMap<>();

    private ConfigFile() {
    }

    /**
     * Reads {@code file}, lets {@code change} turn its text into the edited text, then validates and replaces it, all
     * while holding an exclusive lock on {@code file}'s own {@code .lock} file: a second edit, in this process or another
     * (e.g. the running service adding a member while this one saves a project), waits for this one to finish rather than
     * racing it.
     *
     * @param change turns the file's current text into the edited text; may throw {@link ConfigException} or a
     *               {@link dispatch.cli.CliException} to abort, leaving the file unchanged. Returning the text
     *               unchanged (e.g. a member already present) validates it but never rewrites the file: no new inode,
     *               no permissions reset.
     * @return the config the file now holds
     */
    public static Config edit(Path file, Map<String, String> environment, UnaryOperator<String> change) {
        Path absolute = file.toAbsolutePath().normalize();
        Object inProcessLock = IN_PROCESS_LOCKS.computeIfAbsent(absolute, ignored -> new Object());
        synchronized (inProcessLock) {
            Path lockFile = absolute.resolveSibling(absolute.getFileName() + ".lock");
            ensureLockFile(lockFile);
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                String current = Files.readString(absolute);
                String edited = change.apply(current);
                return edited.equals(current) ? parse(absolute, current, environment) : replace(absolute, edited, environment);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot edit " + absolute + ": " + e.getMessage(), e);
            }
        }
    }

    /** The lock file, owner-only where the filesystem supports it; a leftover one from an earlier run is reused as is. */
    private static void ensureLockFile(Path lockFile) {
        if (Files.exists(lockFile)) {
            return;
        }
        try {
            if (Files.getFileAttributeView(lockFile.getParent(), PosixFileAttributeView.class) != null) {
                Files.createFile(lockFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(lockFile);
            }
        } catch (FileAlreadyExistsException e) {
            // Another process created it first between the exists() check and here; either is fine to lock.
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + lockFile + ": " + e.getMessage(), e);
        }
    }

    /**
     * Validates {@code text} as the config at {@code shownAs} without writing it there; for reading the config a caller
     * already has as text (e.g. the very text an {@link #edit} is about to change), so it is never re-read from disk.
     *
     * @throws ConfigException when the text is invalid
     */
    public static Config parse(Path shownAs, String text, Map<String, String> environment) {
        Path draft = null;
        try {
            draft = Files.createTempFile(shownAs.toAbsolutePath().getParent(), ".dispatch-", ".yaml");
            Files.writeString(draft, text);
            try {
                return ConfigLoader.load(draft, environment);
            } catch (ConfigException e) {
                throw new ConfigException(e.getMessage().replace(draft.toString(), shownAs.toString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + shownAs + ": " + e.getMessage(), e);
        } finally {
            removeDraft(draft);
        }
    }

    /**
     * Validates {@code editedText} as the config at {@code file} and, when valid, puts it in the file's place.
     *
     * @return the config the file now holds
     * @throws ConfigException when the edited text is invalid; the file is unchanged
     */
    public static Config replace(Path file, String editedText, Map<String, String> environment) {
        Path draft = null;
        try {
            draft = Files.createTempFile(file.toAbsolutePath().getParent(), ".dispatch-", ".yaml");
            Files.writeString(draft, editedText);
            Config config;
            try {
                config = ConfigLoader.load(draft, environment);
            } catch (ConfigException e) {
                throw new ConfigException(e.getMessage().replace(draft.toString(), file.toString()));
            }
            try {
                Files.move(draft, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(draft, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return config;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file + ": " + e.getMessage(), e);
        } finally {
            removeDraft(draft);
        }
    }

    private static void removeDraft(Path draft) {
        if (draft == null) {
            return;
        }
        try {
            Files.deleteIfExists(draft);
        } catch (IOException e) {
            Log.warn("config.draft_not_removed", "draft", draft, "error", e.getMessage());
        }
    }
}
