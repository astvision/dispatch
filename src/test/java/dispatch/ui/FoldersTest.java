package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.cli.CliException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FoldersTest {

    @TempDir
    Path home;

    @Test
    void listsOnlyVisibleFoldersSortedAndSaysWhichAreClones() throws IOException {
        Files.createDirectories(home.resolve("zeta"));
        Files.createDirectories(home.resolve("Alm/.git"));
        Files.createDirectories(home.resolve(".config"));
        Files.writeString(home.resolve("notes.txt"), "never listed");

        Folders.Listing listing = Folders.list(null, home);

        assertEquals(home.toString(), listing.path());
        assertEquals(home.getParent().toString(), listing.parent());
        assertEquals(List.of(new Folders.Entry("Alm", home.resolve("Alm").toString(), true),
                new Folders.Entry("zeta", home.resolve("zeta").toString(), false)), listing.folders());
        assertEquals(false, listing.truncated());
    }

    @Test
    void aGivenFolderIsListedAndAMissingOneIsExplained() throws IOException {
        Files.createDirectories(home.resolve("work/crm"));

        Folders.Listing work = Folders.list(home.resolve("work").toString(), home);
        CliException missing = assertThrows(CliException.class, () -> Folders.list(home.resolve("nope").toString(), home));

        assertEquals(List.of("crm"), work.folders().stream().map(Folders.Entry::name).toList());
        assertTrue(missing.getMessage().contains("is not a folder"), missing.getMessage());
    }

    @Test
    void aVeryLargeFolderIsCutShort() throws IOException {
        for (int i = 0; i < Folders.MAX_ENTRIES + 5; i++) {
            Files.createDirectories(home.resolve(String.format("d%04d", i)));
        }

        Folders.Listing listing = Folders.list(null, home);

        assertEquals(Folders.MAX_ENTRIES, listing.folders().size());
        assertTrue(listing.truncated());
        assertEquals("d0000", listing.folders().get(0).name());
        assertEquals("d0499", listing.folders().get(Folders.MAX_ENTRIES - 1).name());
        assertTrue(listing.folders().stream().noneMatch(e -> e.name().equals("d0500")),
                "Folders beyond MAX_ENTRIES should not be in the list");
    }
}
