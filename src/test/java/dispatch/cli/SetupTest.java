package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.config.Config;
import dispatch.config.ConfigLoader;
import dispatch.telegram.BotApi;
import dispatch.testing.FakeTelegram;
import dispatch.testing.GitFixture;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetupTest {

    // Split, so secret scanners never see a token-shaped literal in this file.
    private static final String TOKEN = "123456789" + ":AAH-fake-token-for-tests-only-0123456789";

    @TempDir
    Path dir;

    private FakeTelegram telegram;
    private Path config;

    @BeforeEach
    void setUp() throws IOException {
        telegram = FakeTelegram.start();
        config = dir.resolve("config/dispatch.yaml");
    }

    @AfterEach
    void tearDown() {
        telegram.close();
    }

    @Test
    void aWrittenSetupLoadsWithItsTokenInTheSecretsFile() throws IOException {
        GitFixture repos = GitFixture.create(dir, "alm");
        Setup.Answers answers = new Setup.Answers("bold", false, List.of(new Config.Member(100, "Bold")), null, "claude",
                List.of(new ProjectAddCommand.Project("alm", null, repos.repo("alm"), null, "main", "claude-code", "opus", null)),
                "Dispatch (Bold)", "bold@example.com");

        Setup.write(config, Setup.render(answers, dir.resolve("state")), TOKEN);

        Config written = ConfigLoader.load(config, SecretsFile.environment(config, Map.of()));
        assertEquals(TOKEN, written.secrets().telegramBotToken());
        assertEquals(List.of(new Config.Member(100, "Bold")), written.telegram().groups().getFirst().members());
        assertEquals("opus", written.projects().getFirst().model());
        assertFalse(Files.readString(config).contains(TOKEN), "the token goes only into the secrets file");
    }

    @Test
    void anInvalidSetupWritesNothingAtAll() throws IOException {
        CliException e = assertThrows(CliException.class, () -> Setup.write(config, "team: [\n", TOKEN));

        assertTrue(e.getMessage().contains(config.toString()), "names the config, not a temporary file: " + e.getMessage());
        assertFalse(Files.exists(config));
        assertFalse(Files.exists(SecretsFile.beside(config)));
        try (Stream<Path> left = Files.list(config.getParent())) {
            assertEquals(List.of(), left.toList(), "no draft is left behind");
        }
    }

    @Test
    void aTokenTelegramRefusesIsExplainedWithoutShowingIt() {
        telegram.respond("getMe", 401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");

        CliException refused = assertThrows(CliException.class, () -> Setup.bot(TOKEN, this::bot));
        CliException malformed = assertThrows(CliException.class, () -> Setup.bot("not a token", this::bot));

        assertTrue(refused.getMessage().startsWith("Telegram refused that token"), refused.getMessage());
        assertFalse(refused.getMessage().contains(TOKEN));
        assertTrue(malformed.getMessage().startsWith("that is not a bot token"), malformed.getMessage());
    }

    @Test
    void theBotNeverShowsItsTokenWhenPrinted() {
        Setup.Bot bot = Setup.bot(TOKEN, this::bot);

        assertEquals(FakeTelegram.BOT_USERNAME, bot.username());
        assertFalse(bot.toString().contains(TOKEN), bot.toString());
    }

    @Test
    void anotherReaderOfTheBotIsAConflict() {
        telegram.respond("getUpdates", 409,
                "{\"ok\":false,\"error_code\":409,\"description\":\"Conflict: terminated by other getUpdates request\"}");
        Setup.Updates updates = new Setup.Updates(bot(TOKEN));

        Setup.ConflictException e = assertThrows(Setup.ConflictException.class,
                () -> updates.next(Setup::isPrivateMessage, Duration.ofSeconds(2)));

        assertTrue(e.getMessage().contains("stop it first"), e.getMessage());
    }

    private BotApi bot(String token) {
        return new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5));
    }
}
