package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.OwnerOnly;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class SecretsFileTest {

    // Split, so secret scanners never see a token-shaped literal in this file.
    private static final String TOKEN = "123456789" + ":AAH-fake-token-for-tests-only-0123456789";

    @TempDir
    Path dir;

    @Test
    void secretsLiveBesideTheConfigUnderItsName() {
        assertEquals(dir.resolve("backend.env"), SecretsFile.beside(dir.resolve("backend.yaml")));
        assertEquals(dir.resolve("dispatch.env"), SecretsFile.beside(dir.resolve("dispatch.yml")));
    }

    @Test
    void writtenSecretsReadBackAndOnlyTheOwnerCanReadThem() throws IOException {
        Path file = dir.resolve("dispatch.env");

        SecretsFile.write(file, Map.of("TELEGRAM_BOT_TOKEN", TOKEN));

        assertEquals(Map.of("TELEGRAM_BOT_TOKEN", TOKEN), SecretsFile.read(file));
        assertEquals(java.util.Optional.empty(), OwnerOnly.groupOrOthersAccess(file));
    }

    @Test
    void readsEnvironmentFileSyntaxWithCommentsBlankLinesAndQuotes() throws IOException {
        Path file = dir.resolve("dispatch.env");
        Files.writeString(file, "# secrets\n\nTELEGRAM_BOT_TOKEN=\"" + TOKEN + "\"\r\nGH_TOKEN='gh-secret'\nANTHROPIC_API_KEY=\n");

        assertEquals(Map.of("TELEGRAM_BOT_TOKEN", TOKEN, "GH_TOKEN", "gh-secret"), SecretsFile.read(file));
    }

    @Test
    void theEnvironmentWinsOverTheFileAndAMissingFileIsFine() throws IOException {
        Path config = dir.resolve("dispatch.yaml");
        assertEquals(Map.of("HOME", "/home/bold"), SecretsFile.environment(config, Map.of("HOME", "/home/bold")));

        SecretsFile.write(SecretsFile.beside(config), Map.of("TELEGRAM_BOT_TOKEN", TOKEN, "GH_TOKEN", "from-file"));
        Map<String, String> environment = SecretsFile.environment(config, Map.of("GH_TOKEN", "from-environment"));

        assertEquals(TOKEN, environment.get("TELEGRAM_BOT_TOKEN"));
        assertEquals("from-environment", environment.get("GH_TOKEN"));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permissions")
    void secretsReadableByOthersAreRefusedWithTheFix() throws IOException {
        Path config = dir.resolve("dispatch.yaml");
        Path secrets = SecretsFile.beside(config);
        Files.writeString(secrets, "TELEGRAM_BOT_TOKEN=" + TOKEN + "\n");
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString("rw-r--r--"));

        CliException error = assertThrows(CliException.class, () -> SecretsFile.environment(config, Map.of()));

        assertTrue(error.getMessage().contains("chmod 600 " + secrets), error.getMessage());
        assertFalse(error.getMessage().contains(TOKEN), "never echoes a secret");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permissions")
    void unreadableSecretsAreSkippedWhenTheServiceManagerAlreadyPassedTheToken() throws IOException {
        Path config = dir.resolve("backend.yaml");
        Path secrets = SecretsFile.beside(config);
        Files.writeString(secrets, "TELEGRAM_BOT_TOKEN=" + TOKEN + "\n");
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString("---------"));

        assertEquals(TOKEN, SecretsFile.environment(config, Map.of("TELEGRAM_BOT_TOKEN", TOKEN)).get("TELEGRAM_BOT_TOKEN"),
                "systemd reads /etc/dispatch/<team>.env as root and passes its values in");
        assertThrows(CliException.class, () -> SecretsFile.environment(config, Map.of()));
    }
}
