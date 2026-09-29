package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.OwnerOnly;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** desk.json: where the running bot's desk port listens, for dispatch ui to find (D-2). */
class DeskFileTest {

    @TempDir
    Path state;

    @Test
    void aWrittenFileIsReadBackAndOnlyItsOwnerCanReadIt() throws IOException {
        DeskFile written = new DeskFile(41234, DeskFile.newToken(), "0.3.0", "acme");

        written.write(state);

        assertEquals(Optional.of(written), DeskFile.read(state));
        assertEquals(64, written.token().length());
        assertEquals(Optional.empty(), OwnerOnly.othersAccess(DeskFile.in(state)));
    }

    @Test
    void aMissingOrBrokenFileIsNoBot() throws IOException {
        assertEquals(Optional.empty(), DeskFile.read(state));
        Files.writeString(DeskFile.in(state), "{\"port\":");
        assertEquals(Optional.empty(), DeskFile.read(state));
    }

    @Test
    void aFieldANewerBotAddedIsIgnored() throws IOException {
        Files.writeString(DeskFile.in(state),
                "{\"port\":41234,\"token\":\"t\",\"version\":\"9.9.9\",\"name\":\"acme\",\"added\":1}");

        assertEquals(Optional.of(new DeskFile(41234, "t", "9.9.9", "acme")), DeskFile.read(state));
    }

    @Test
    void deletingIsQuietWhenThereIsNothingToDelete() throws IOException {
        new DeskFile(1, DeskFile.newToken(), "0.3.0", "acme").write(state);
        DeskFile.delete(state);
        DeskFile.delete(state);
        assertEquals(Optional.empty(), DeskFile.read(state));
    }
}
