package dispatch.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The folder SQLite unpacks its library into on a Windows machine whose temporary folder it cannot load from: the user's
 * own, and nobody else's (Database.sqliteFolder). The checks are the same on every OS, so they are tested everywhere.
 */
class SqliteFolderTest {

    @TempDir
    Path dir;

    @Test
    void aMissingFolderIsCreatedForTheUserAlone() throws IOException {
        Path folder = dir.resolve("dispatch-0123456789abcdef");

        Database.ownFolder(folder);

        assertTrue(Files.isDirectory(folder));
        if (!OS.WINDOWS.isCurrentOs()) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(folder)));
        }
    }

    @Test
    void theUsersOwnFolderFromAnEarlierStartIsKept() throws IOException {
        Path folder = dir.resolve("dispatch-0123456789abcdef");
        Database.ownFolder(folder);
        Path unpacked = Files.writeString(folder.resolve("sqlite-3.53.4.0-a-sqlitejdbc.dll"), "x");

        Database.ownFolder(folder);

        assertTrue(Files.exists(unpacked));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "symlinks need elevated privileges on Windows")
    void aLinkInTheFoldersPlaceIsRefusedEvenToAFolderOfTheUsersOwn() throws IOException {
        // Someone else could make the link and later point it at a folder of theirs, between the check and the load.
        Path elsewhere = Files.createDirectory(dir.resolve("elsewhere"));
        Path folder = Files.createSymbolicLink(dir.resolve("dispatch-0123456789abcdef"), elsewhere);

        IOException refused = assertThrows(IOException.class, () -> Database.ownFolder(folder));

        assertTrue(refused.getMessage().contains("is a link, not a folder"), refused.getMessage());
    }

    @Test
    void theDriversFilesADayOldGoAndEverythingElseStays() throws IOException {
        Path folder = Files.createDirectory(dir.resolve("dispatch-0123456789abcdef"));
        Instant twoDaysAgo = Instant.now().minus(Duration.ofDays(2));
        Path oldLibrary = old(Files.writeString(folder.resolve("sqlite-3.53.4.0-0f1e-sqlitejdbc.dll"), "x"), twoDaysAgo);
        Path oldLock = old(Files.writeString(folder.resolve("sqlite-3.53.4.0-0f1e-sqlitejdbc.dll.lck"), ""), twoDaysAgo);
        Path fresh = Files.writeString(folder.resolve("sqlite-3.53.4.0-9a8b-sqlitejdbc.dll"), "x");
        Path notTheDrivers = old(Files.writeString(folder.resolve("notes.txt"), "x"), twoDaysAgo);

        Database.removeLeftovers(folder);

        assertFalse(Files.exists(oldLibrary));
        assertFalse(Files.exists(oldLock));
        assertTrue(Files.exists(fresh), "what a Dispatch started today may still be using");
        assertTrue(Files.exists(notTheDrivers));
    }

    private static Path old(Path file, Instant when) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(when));
        return file;
    }
}
