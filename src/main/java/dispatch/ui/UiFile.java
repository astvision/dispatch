package dispatch.ui;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dispatch.Json;
import dispatch.Log;
import dispatch.OwnerOnly;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * {@code <stateDir>/ui.json}: where a running {@code dispatch ui} listens and the key its {@code POST /link} answers, so
 * the bot on this computer can hand an admin a fresh login link from the Mini App (ADR 0018, amended). Owner-only and
 * moved into place whole, as {@link DeskFile} is.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UiFile(int port, String key, String version) {

    static final String FILE = "ui.json";

    public static Path in(Path stateDir) {
        return stateDir.resolve(FILE);
    }

    public void write(Path stateDir) throws IOException {
        OwnerOnly.createDirectories(stateDir);
        Path draft = stateDir.resolve(FILE + ".new");
        Files.deleteIfExists(draft);
        OwnerOnly.createFile(draft);
        Files.writeString(draft, Json.write(this));
        Files.move(draft, in(stateDir), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Empty when there is none, or it cannot be read: either way there is no web UI to ask. */
    public static Optional<UiFile> read(Path stateDir) {
        try {
            return Optional.of(Json.MAPPER.readValue(Files.readString(in(stateDir)), UiFile.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Only when it is still this server's: a second {@code dispatch ui} may have written its own since. */
    public static void deleteIfOwn(Path stateDir, int port) {
        if (read(stateDir).filter(file -> file.port() == port).isEmpty()) {
            return;
        }
        try {
            Files.deleteIfExists(in(stateDir));
        } catch (IOException e) {
            Log.warn("ui.file_not_deleted", "file", in(stateDir), "error", e.getMessage());
        }
    }
}
