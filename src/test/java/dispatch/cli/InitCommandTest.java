package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.OwnerOnly;
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
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitCommandTest {

    // Split, so secret scanners never see a token-shaped literal in this file.
    private static final String TOKEN = "123456789" + ":AAH-fake-token-for-tests-only-0123456789";
    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    @TempDir
    Path dir;

    private FakeTelegram telegram;
    private GitFixture repos;
    private Path config;
    private Locations locations;

    @BeforeEach
    void setUp() throws IOException {
        telegram = FakeTelegram.start();
        repos = GitFixture.create(dir, "alm");
        config = dir.resolve("config/dispatch/dispatch.yaml");
        locations = new Locations(config, dir.resolve("state/dispatch"));
    }

    @AfterEach
    void tearDown() {
        telegram.close();
    }

    @Test
    void guidedSetupWritesAPersonalConfigAndOwnerOnlySecrets() throws IOException {
        telegram.pushUpdate(start(100, "Bold"));
        ScriptedTerminal terminal = new ScriptedTerminal(
                TOKEN,                              // bot token
                "",                                 // is Bold you? yes
                JAVA,                               // the claude command
                repos.repo("alm").toString(),       // first project
                "", "", "opus", "high",             // name, base branch, model, effort
                "",                                 // no more projects
                "", "bold@example.com");            // commit author name and email

        int exit = init(terminal, false);

        assertEquals(0, exit, terminal.output());
        Map<String, String> environment = SecretsFile.environment(config, Map.of());
        Config written = ConfigLoader.load(config, environment);
        assertEquals(TOKEN, written.secrets().telegramBotToken());
        Config.Group group = written.telegram().groups().getFirst();
        assertNull(group.chatId(), "a personal bot has no group chat");
        assertEquals(List.of(new Config.Member(100, "Bold")), group.members());
        Config.Project project = written.projects().getFirst();
        assertEquals("alm", project.name());
        assertEquals(repos.repo("alm").toString(), project.path());
        assertEquals("main", project.baseBranch());
        assertEquals("opus", project.model());
        assertEquals("high", project.effort());
        assertEquals(JAVA, written.agents().get("claude-code").command());
        assertEquals(locations.stateDir().toAbsolutePath(), written.stateDir());
        assertEquals(new Config.Delivery("Dispatch (Bold)", "bold@example.com", "gh"), written.delivery());
        assertEquals(Optional.empty(), OwnerOnly.groupOrOthersAccess(SecretsFile.beside(config)));
        String output = terminal.output();
        assertTrue(output.contains("? (hidden) Bot token"), "the token is typed without being shown: " + output);
        assertFalse(output.contains(TOKEN), output);
        assertTrue(output.contains("dispatch check"), output);
    }

    @Test
    void someoneElsePressingStartIsNotTakenForYou() throws IOException {
        telegram.pushUpdate(start(666, "Stranger"));
        telegram.pushUpdate(start(100, "Bold"));
        ScriptedTerminal terminal = new ScriptedTerminal(concat(List.of(TOKEN, "n", "y"),
                List.of(JAVA, repos.repo("alm").toString(), "", "", "", "", "", "", "bold@example.com")));

        assertEquals(0, init(terminal, false), terminal.output());

        Config.Member member = ConfigLoader.load(config, SecretsFile.environment(config, Map.of())).telegram().groups().getFirst()
                .members().getFirst();
        assertEquals(new Config.Member(100, "Bold"), member);
        assertTrue(terminal.output().contains("Stranger (666)"), terminal.output());
    }

    @Test
    void refusedTokenIsAskedForAgain() {
        telegram.respond("getMe", 401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
        telegram.pushUpdate(start(100, "Bold"));
        String revoked = "987654321" + ":AAH-revoked-token-for-tests-only-012345";
        ScriptedTerminal terminal = new ScriptedTerminal(concat(List.of("my bot", revoked, TOKEN),
                List.of("", JAVA, repos.repo("alm").toString(), "", "", "", "", "", "", "bold@example.com")));

        assertEquals(0, init(terminal, false), terminal.output());
        assertTrue(terminal.output().contains("WARN that is not a bot token"), "checked before anything is sent: " + terminal.output());
        assertTrue(terminal.output().contains("Telegram refused that token"), terminal.output());
        assertFalse(terminal.output().contains(revoked), terminal.output());
    }

    @Test
    void existingConfigIsKeptUnlessForced() throws IOException {
        Files.createDirectories(config.getParent());
        Files.writeString(config, "team: mine\n");
        ScriptedTerminal terminal = new ScriptedTerminal();

        assertEquals(1, init(terminal, false));

        assertEquals("team: mine\n", Files.readString(config));
        assertTrue(terminal.output().contains("already exists") && terminal.output().contains("--force"), terminal.output());
    }

    private int init(ScriptedTerminal terminal, boolean force) {
        return new InitCommand(terminal, token -> new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5)),
                locations, Duration.ofSeconds(10)).run(new Cli.Init(config, force), Map.of("PATH", ""));
    }

    private static com.fasterxml.jackson.databind.JsonNode start(long userId, String firstName) {
        return dispatch.Json.read("""
                {"update_id":%d,"message":{"message_id":1,"from":{"id":%d,"is_bot":false,"first_name":"%s"},
                 "chat":{"id":%d,"type":"private"},"date":1789640000,"text":"/start",
                 "entities":[{"offset":0,"length":6,"type":"bot_command"}]}}""".formatted(userId, userId, firstName, userId));
    }

    private static String[] concat(List<String> first, List<String> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toArray(String[]::new);
    }
}
