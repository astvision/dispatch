package dispatch.ui;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dispatch.Json;
import dispatch.Log;
import dispatch.OwnerOnly;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

/**
 * {@code <stateDir>/desk.json}: where the running bot's desk port listens and the token it answers (D-2). Owner-only, and
 * written whole under another name before it is moved into place, so {@code dispatch ui} never reads half of it.
 * Unknown fields are ignored: a newer bot may add one, and an older {@code dispatch ui} must still find it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeskFile(int port, String token, String version, String name) {

    static final String FILE = "desk.json";
    private static final SecureRandom RANDOM = new SecureRandom();

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

    /** Empty when there is none, or it cannot be read: either way there is no bot to ask. */
    public static Optional<DeskFile> read(Path stateDir) {
        try {
            return Optional.of(Json.MAPPER.readValue(Files.readString(in(stateDir)), DeskFile.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** At a clean stop; a crash leaves the file, which the next start replaces. */
    public static void delete(Path stateDir) {
        try {
            Files.deleteIfExists(in(stateDir));
        } catch (IOException e) {
            Log.warn("desk.file_not_deleted", "file", in(stateDir), "error", e.getMessage());
        }
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
