package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
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
    void personalSetupWritesAPrivateBotsConfigAndOwnerOnlySecrets() throws IOException {
        telegram.pushUpdate(start(1, 100, "Bold"));
        ScriptedTerminal terminal = new ScriptedTerminal(
                "",                                 // who uses the bot: just me
                TOKEN,                              // bot token
                "y",                                // is Bold you? yes, typed: granting access never defaults
                JAVA,                               // the claude command
                repos.repo("alm").toString(),       // a project
                "", "", "Opus", "High",             // name, base branch, model, effort
                "",                                 // add another project? no
                "", "bold@example.com",             // commit author name and email
                "",                                 // write this setup? yes
                "");                                // keep it running in the background? yes

        int exit = init(terminal, false);

        assertEquals(0, exit, terminal.output());
        Config written = load();
        assertEquals(TOKEN, written.secrets().telegramBotToken());
        assertEquals(List.of(), written.telegram().admins(), "nobody can ask to join a personal bot");
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
        assertEquals(config, service.installed.configFile(), "the service runs this config");
        assertTrue(output.contains("Dispatch runs in the background"), output);
    }

    @Test
    void teamSetupAddsTeammatesWhoPressStartAndFindsTheGroupChat() throws IOException {
        telegram.pushUpdate(start(1, 100, "Bold"));
        telegram.pushUpdate(start(2, 222, "Ali"));
        telegram.pushUpdate(botAddedTo(3, -1001234567890L, "ACME backend"));
        ScriptedTerminal terminal = new ScriptedTerminal(
                "My team", TOKEN,
                "y",                                // is Bold you? yes
                "y", "y",                           // wait for a teammate: yes; add Ali: yes
                "n",                                // wait for another teammate? no
                "y",                                // a team group for announcements: yes
                "",                                 // team name: from the group's title
                JAVA, repos.repo("alm").toString(), "", "", "", "", "",
                "", "bold@example.com", "", "n");

        int exit = init(terminal, false);

        assertEquals(0, exit, terminal.output());
        Config written = load();
        assertEquals(List.of(100L), written.telegram().admins(), "whoever set it up approves who joins later");
        Config.Group group = written.telegram().groups().getFirst();
        assertEquals("acme-backend", group.name());
        assertEquals(-1001234567890L, group.chatId());
        assertEquals(List.of(new Config.Member(100, "Bold"), new Config.Member(222, "Ali")), group.members());
        assertEquals("acme-backend", written.team());
        assertNull(service.installed, "not wanted this time");
        assertTrue(terminal.output().contains("dispatch run"), terminal.output());
    }

    @Test
    void someoneElsePressingStartIsNotTakenForYou() throws IOException {
        telegram.pushUpdate(start(1, 666, "Stranger"));
        telegram.pushUpdate(start(2, 100, "Bold"));
        ScriptedTerminal terminal = new ScriptedTerminal("", TOKEN, "n", "y", JAVA, repos.repo("alm").toString(), "", "", "", "", "",
                "", "bold@example.com", "", "n");

        assertEquals(0, init(terminal, false), terminal.output());

        assertEquals(List.of(new Config.Member(100, "Bold")), load().telegram().groups().getFirst().members());
        assertTrue(terminal.output().contains("Stranger (666)"), terminal.output());
    }

    @Test
    void refusedTokenIsAskedForAgain() {
        telegram.respond("getMe", 401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
        telegram.pushUpdate(start(1, 100, "Bold"));
        String revoked = "987654321" + ":AAH-revoked-token-for-tests-only-012345";
        ScriptedTerminal terminal = new ScriptedTerminal("", "my bot", revoked, TOKEN, "y", JAVA, repos.repo("alm").toString(),
                "", "", "", "", "", "", "bold@example.com", "", "n");

        assertEquals(0, init(terminal, false), terminal.output());
        assertTrue(terminal.output().contains("WARN that is not a bot token"), "checked before anything is sent: " + terminal.output());
        assertTrue(terminal.output().contains("Telegram refused that token"), terminal.output());
        assertFalse(terminal.output().contains(revoked), terminal.output());
    }

    @Test
    void declinedSummaryWritesNothing() {
        telegram.pushUpdate(start(1, 100, "Bold"));
        ScriptedTerminal terminal = new ScriptedTerminal("", TOKEN, "y", JAVA, repos.repo("alm").toString(), "", "", "", "", "",
                "", "bold@example.com", "n");

        assertEquals(1, init(terminal, false));

        assertFalse(Files.exists(config));
        assertFalse(Files.exists(SecretsFile.beside(config)));
        assertTrue(terminal.output().contains("nothing was written"), terminal.output());
    }

    @Test
    void pressingEnterNeverGrantsAccess() {
        telegram.pushUpdate(start(1, 666, "Stranger"));
        ScriptedTerminal terminal = new ScriptedTerminal("", TOKEN, "");

        assertEquals(1, init(terminal, false));

        assertTrue(terminal.output().contains("Is Stranger (666) you?"), terminal.output());
        assertFalse(Files.exists(config));
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

    private final RecordingService service = new RecordingService();

    private int init(ScriptedTerminal terminal, boolean force) {
        Path jar;
        try {
            jar = Files.writeString(dir.resolve("dispatch.jar"), "stand-in");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return new InitCommand(terminal, token -> new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5)),
                locations, Duration.ofSeconds(10), new ServiceCommand(terminal, service, jar)).run(new Cli.Init(config, force), Map.of("PATH", ""));
    }

    /** A background service that only remembers what it was asked to run. */
    private static final class RecordingService implements Service {

        Service.Spec installed;

        @Override
        public String describe() {
            return "test service";
        }

        @Override
        public void install(Service.Spec spec) {
            installed = spec;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public Service.Status status() {
            return new Service.Status(installed != null, installed != null, "running", List.of());
        }

        @Override
        public void uninstall() {
            installed = null;
        }
    }

    private Config load() {
        return ConfigLoader.load(config, SecretsFile.environment(config, Map.of()));
    }

    private static JsonNode start(long updateId, long userId, String firstName) {
        return Json.read("""
                {"update_id":%d,"message":{"message_id":1,"from":{"id":%d,"is_bot":false,"first_name":"%s"},
                 "chat":{"id":%d,"type":"private"},"date":1789640000,"text":"/start",
                 "entities":[{"offset":0,"length":6,"type":"bot_command"}]}}""".formatted(updateId, userId, firstName, userId));
    }

    private static JsonNode botAddedTo(long updateId, long chatId, String title) {
        return Json.read("""
                {"update_id":%d,"my_chat_member":{"chat":{"id":%d,"title":"%s","type":"supergroup"},
                 "from":{"id":100,"is_bot":false,"first_name":"Bold"},"date":1789640000,
                 "old_chat_member":{"user":{"id":1,"is_bot":true,"first_name":"Dispatch"},"status":"left"},
                 "new_chat_member":{"user":{"id":1,"is_bot":true,"first_name":"Dispatch"},"status":"member"}}}"""
                .formatted(updateId, chatId, title));
    }
}
