package dispatch.config;

import dispatch.Log;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * A config file changed in place: an edited version replaces it only once it validates, atomically, so a mistake or a
 * crash leaves the file as it was.
 */
public final class ConfigFile {

    private ConfigFile() {
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
