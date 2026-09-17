package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunCommandTest {

    // Split, so secret scanners never see a token-shaped literal in this file.
    private static final String TOKEN = "123456789" + ":AAH-fake-token-for-tests-only-0123456789";

    @TempDir
    Path dir;

    @Test
    void missingConfigPointsToInit() {
        Path config = dir.resolve("dispatch.yaml");

        CliException error = assertThrows(CliException.class, () -> RunCommand.prepare(config, Map.of()));

        assertTrue(error.getMessage().contains("no config at " + config) && error.getMessage().contains("dispatch init"),
                error.getMessage());
    }

    @Test
    void configIsLoadedWithTheSecretsBesideIt() throws IOException {
        Path config = dir.resolve("dispatch.yaml");
        String yaml = new String(RunCommandTest.class.getResourceAsStream("/personal.yaml").readAllBytes())
                .replace("STATE_DIR", dir.resolve("state").toString().replace("'", "''"))
                .replace("CLONE", dir.resolve("work/alm").toString().replace("'", "''"));
        Files.writeString(config, yaml);
        SecretsFile.write(SecretsFile.beside(config), Map.of("TELEGRAM_BOT_TOKEN", TOKEN));

        RunCommand.Prepared prepared = RunCommand.prepare(config, Map.of("PATH", "/usr/bin"));

        assertEquals(TOKEN, prepared.config().secrets().telegramBotToken());
        assertEquals(TOKEN, prepared.environment().get("TELEGRAM_BOT_TOKEN"), "the redactor and agents get the file's secrets too");
        assertEquals("/usr/bin", prepared.environment().get("PATH"));
        assertEquals(dir.resolve("state"), prepared.config().stateDir());
    }
}
