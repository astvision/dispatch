package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.cli.CliException;
import dispatch.cli.Locations;
import dispatch.cli.SecretsFile;
import dispatch.cli.Service;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetupApiTest {

    // Split, so secret scanners never see a token-shaped literal in this file.
    private static final String TOKEN = "123456789" + ":AAH-fake-token-for-tests-only-0123456789";
    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    @TempDir
    Path dir;

    private FakeTelegram telegram;
    private GitFixture repos;
    private Path config;
    private StubService service;
    private SetupApi setup;

    @BeforeEach
    void setUp() throws IOException {
        telegram = FakeTelegram.start();
        repos = GitFixture.create(dir, "alm");
        config = dir.resolve("config/dispatch.yaml");
        service = new StubService();
        setup = new SetupApi(config, new Locations(config, dir.resolve("state")),
                token -> new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5)), service,
                Files.writeString(dir.resolve("dispatch.jar"), "stand-in"), Map.of(), Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        telegram.close();
    }

    @Test
    void aWholePersonalSetupWritesAConfigThatLoads() throws Exception {
        call("/api/setup/team", "{\"team\":false}");
        JsonNode bot = call("/api/setup/token", "{\"token\":\"" + TOKEN + "\"}");
        telegram.pushUpdate(start(1, 100, "Bold"));
        JsonNode found = call("/api/setup/people/next", "{}");
        JsonNode afterYes = call("/api/setup/people/answer", "{\"id\":100,\"accept\":true}");
        JsonNode claude = call("/api/setup/claude", "{\"command\":\"" + json(JAVA) + "\"}");
        JsonNode project = call("/api/setup/project", "{\"folder\":\"" + json(repos.repo("alm").toString()) + "\"}");
        JsonNode written = call("/api/setup/write", """
                {"claude":"%s","authorName":"Dispatch (Bold)","authorEmail":"bold@example.com",
                 "projects":[{"folder":"%s","name":"alm","baseBranch":"main","model":"opus","effort":"high"}]}"""
                .formatted(json(JAVA), json(repos.repo("alm").toString())));

        assertEquals(FakeTelegram.BOT_USERNAME, bot.path("username").asText());
        assertEquals(100, found.path("candidate").path("id").asLong());
        assertEquals(100, telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json().path("chat_id").asLong(),
                "the bot answers whoever wrote");
        assertEquals("Bold", afterYes.path("members").get(0).path("name").asText());
        assertFalse(claude.path("version").asText().isBlank());
        assertEquals("alm", project.path("name").asText());
        assertEquals("main", project.path("baseBranch").asText());
        assertEquals(config.toString(), written.path("configFile").asText());
        Config loaded = ConfigLoader.load(config, SecretsFile.environment(config, Map.of()));
        assertEquals(TOKEN, loaded.secrets().telegramBotToken());
        assertEquals(List.of(new Config.Member(100, "Bold")), loaded.telegram().groups().getFirst().members());
        assertEquals("opus", loaded.projects().getFirst().model());
        assertEquals("high", loaded.projects().getFirst().effort());
        assertThrows(CliException.class, () -> call("/api/setup/write", "{}"), "a second write never replaces the config");
    }

    @Test
    void theBotTokenNeverReachesThePage() throws Exception {
        call("/api/setup/token", "{\"token\":\"" + TOKEN + "\"}");

        assertFalse(Json.write(call("/api/setup/state", "{}")).contains(TOKEN));
    }

    @Test
    void aPersonFoundStaysPendingUntilAnsweredAndADeclinedOneCanStillBeATeammate() throws Exception {
        call("/api/setup/team", "{\"team\":true}");
        call("/api/setup/token", "{\"token\":\"" + TOKEN + "\"}");
        telegram.pushUpdate(start(1, 222, "Ali"));
        telegram.pushUpdate(start(2, 100, "Bold"));

        JsonNode first = call("/api/setup/people/next", "{}");
        JsonNode again = call("/api/setup/people/next", "{}");
        call("/api/setup/people/answer", "{\"id\":222,\"accept\":false}");
        JsonNode second = call("/api/setup/people/next", "{}");
        call("/api/setup/people/answer", "{\"id\":100,\"accept\":true}");
        JsonNode teammate = call("/api/setup/people/next", "{}");

        assertEquals(222, first.path("candidate").path("id").asLong());
        assertEquals(222, again.path("candidate").path("id").asLong(), "a request the page abandoned loses nobody");
        assertEquals(100, second.path("candidate").path("id").asLong());
        assertEquals(222, teammate.path("candidate").path("id").asLong(), "Ali, not you, may still join the team");
    }

    @Test
    void nobodyWritingAnswersNullAndAnotherReaderIsAConflict() throws Exception {
        call("/api/setup/token", "{\"token\":\"" + TOKEN + "\"}");

        JsonNode nobody = call("/api/setup/people/next", "{}");
        telegram.respond("getUpdates", 409,
                "{\"ok\":false,\"error_code\":409,\"description\":\"Conflict: terminated by other getUpdates request\"}");
        ApiException conflict = assertThrows(ApiException.class, () -> call("/api/setup/people/next", "{}"));

        assertTrue(nobody.path("candidate").isNull(), nobody.toString());
        assertEquals(409, conflict.status());
        assertEquals("conflict", conflict.code());
    }

    @Test
    void writingChecksWhatThePageSent() throws Exception {
        call("/api/setup/token", "{\"token\":\"" + TOKEN + "\"}");
        telegram.pushUpdate(start(1, 100, "Bold"));
        call("/api/setup/people/next", "{}");
        call("/api/setup/people/answer", "{\"id\":100,\"accept\":true}");
        String clone = json(repos.repo("alm").toString());

        CliException model = assertThrows(CliException.class, () -> call("/api/setup/write", """
                {"claude":"claude","authorName":"a","authorEmail":"a@example.com",
                 "projects":[{"folder":"%s","name":"alm","baseBranch":"main","model":"gpt"}]}""".formatted(clone)));
        CliException none = assertThrows(CliException.class, () -> call("/api/setup/write",
                "{\"claude\":\"claude\",\"authorName\":\"a\",\"authorEmail\":\"a@example.com\",\"projects\":[]}"));
        CliException author = assertThrows(CliException.class, () -> call("/api/setup/write", """
                {"claude":"claude","authorEmail":"a@example.com",
                 "projects":[{"folder":"%s","name":"alm","baseBranch":"main"}]}""".formatted(clone)));

        assertTrue(model.getMessage().contains("model"), model.getMessage());
        assertTrue(none.getMessage().contains("project"), none.getMessage());
        assertTrue(author.getMessage().contains("authorName"), author.getMessage());
        assertFalse(Files.exists(config));
    }

    @Test
    void theServiceIsInstalledForTheWrittenConfig() throws Exception {
        aWholePersonalSetupWritesAConfigThatLoads();

        JsonNode status = call("/api/service/install", "{}");

        assertEquals(config.toAbsolutePath(), service.installed.configFile());
        assertTrue(status.path("installed").asBoolean(), status.toString());
    }

    private JsonNode call(String path, String body) throws Exception {
        Object answer = setup.routes().get(path).apply(Json.MAPPER.readTree(body));
        return Json.MAPPER.readTree(Json.write(answer));
    }

    private static String json(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static JsonNode start(long updateId, long userId, String firstName) {
        return Json.read("""
                {"update_id":%d,"message":{"message_id":1,"from":{"id":%d,"is_bot":false,"first_name":"%s"},
                 "chat":{"id":%d,"type":"private"},"date":1789640000,"text":"/start"}}""".formatted(updateId, userId, firstName, userId));
    }

    /** A background service that only remembers what it was asked to run. */
    private static final class StubService implements Service {

        Service.Spec installed;

        @Override
        public String describe() {
            return "stub service";
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
            return new Service.Status(installed != null, installed != null, installed != null ? "running" : "not installed", List.of());
        }

        @Override
        public void uninstall() {
            installed = null;
        }
    }
}
