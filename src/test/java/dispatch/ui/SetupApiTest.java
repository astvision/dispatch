package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        String writeBody = """
                {"claude":"%s","authorName":"Dispatch (Bold)","authorEmail":"bold@example.com",
                 "projects":[{"folder":"%s","name":"alm","baseBranch":"main","model":"opus","effort":"high"}]}"""
                .formatted(json(JAVA), json(repos.repo("alm").toString()));
        JsonNode written = call("/api/setup/write", writeBody);

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
        byte[] before = Files.readAllBytes(config);
        CliException rewrite = assertThrows(CliException.class, () -> call("/api/setup/write", writeBody),
                "a second write, even with the same valid answers, never replaces the config");
        assertTrue(rewrite.getMessage().contains("already exists"), rewrite.getMessage());
        assertArrayEquals(before, Files.readAllBytes(config), "the config file's bytes are unchanged");
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
    void aTeammateSkippedAfterYouAreConfirmedIsNotOfferedAgainAtOnce() throws Exception {
        call("/api/setup/team", "{\"team\":true}");
        call("/api/setup/token", "{\"token\":\"" + TOKEN + "\"}");
        telegram.pushUpdate(start(1, 100, "Bold"));
        call("/api/setup/people/next", "{}");
        call("/api/setup/people/answer", "{\"id\":100,\"accept\":true}"); // you're confirmed

        telegram.pushUpdate(start(2, 222, "Ali"));
        JsonNode found = call("/api/setup/people/next", "{}");
        call("/api/setup/people/answer", "{\"id\":222,\"accept\":false}"); // skip Ali, after you were already in

        JsonNode again = call("/api/setup/people/next", "{}");

        assertEquals(222, found.path("candidate").path("id").asLong());
        assertTrue(again.path("candidate").isNull(), "Ali, declined after confirmation, is not offered again at once: " + again);
    }

    @Test
    void aPersonalBotRefusesASecondMemberAndClearsTheCandidate() throws Exception {
        call("/api/setup/team", "{\"team\":false}");
        call("/api/setup/token", "{\"token\":\"" + TOKEN + "\"}");
        telegram.pushUpdate(start(1, 100, "Bold"));
        call("/api/setup/people/next", "{}");
        call("/api/setup/people/answer", "{\"id\":100,\"accept\":true}");
        telegram.pushUpdate(start(2, 200, "Ali"));
        call("/api/setup/people/next", "{}");

        CliException second = assertThrows(CliException.class, () -> call("/api/setup/people/answer", "{\"id\":200,\"accept\":true}"));

        assertTrue(second.getMessage().contains("one member"), second.getMessage());
        JsonNode state = call("/api/setup/state", "{}");
        assertEquals(1, state.path("members").size(), "Ali was never added");
        assertTrue(state.path("candidate").isNull(), "the declined candidate is cleared so the page moves on");
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
    void aTokenChangeDuringALongPollNeverLeaksTheOldBotsCandidate() throws Exception {
        SetupApi longPoll = new SetupApi(config, new Locations(config, dir.resolve("state")),
                token -> new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5)), service,
                Files.writeString(dir.resolve("dispatch-longpoll-person.jar"), "stand-in"), Map.of(), Duration.ofSeconds(2));
        call(longPoll, "/api/setup/token", "{\"token\":\"" + TOKEN + "\"}");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> stalePerson = executor.submit(() -> call(longPoll, "/api/setup/people/next", "{}"));
            // Waiting for the actual getUpdates request (not a sleep) proves the OLD Setup.Updates is genuinely
            // polling before the token swap below, so the guard this test exercises is not a coincidence of timing.
            telegram.awaitRequest("getUpdates", Duration.ofSeconds(2));
            call(longPoll, "/api/setup/token", "{\"token\":\"" + TOKEN + "\"}"); // swaps bot/updates mid-poll
            telegram.pushUpdate(start(1, 100, "Bold")); // only the OLD Updates instance is still around to read this

            JsonNode stalePersonResult = stalePerson.get(5, TimeUnit.SECONDS);

            assertTrue(stalePersonResult.path("candidate").isNull(), stalePersonResult.toString());
            assertTrue(call(longPoll, "/api/setup/state", "{}").path("candidate").isNull());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aTokenChangeDuringALongPollNeverLeaksTheOldBotsGroup() throws Exception {
        SetupApi longPoll = new SetupApi(config, new Locations(config, dir.resolve("state")),
                token -> new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5)), service,
                Files.writeString(dir.resolve("dispatch-longpoll-group.jar"), "stand-in"), Map.of(), Duration.ofSeconds(2));
        call(longPoll, "/api/setup/team", "{\"team\":true}");
        call(longPoll, "/api/setup/token", "{\"token\":\"" + TOKEN + "\"}");
        telegram.pushUpdate(start(1, 100, "Bold"));
        call(longPoll, "/api/setup/people/next", "{}");
        call(longPoll, "/api/setup/people/answer", "{\"id\":100,\"accept\":true}");
        // The confirmation above made its own getUpdates request(s); drain them so the awaitRequest below only ever
        // sees the fresh one nextGroup makes, not a leftover from an earlier call on the shared FakeTelegram queue.
        telegram.drain("getUpdates");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> staleGroup = executor.submit(() -> call(longPoll, "/api/setup/group/next", "{}"));
            telegram.awaitRequest("getUpdates", Duration.ofSeconds(2));
            call(longPoll, "/api/setup/token", "{\"token\":\"" + TOKEN + "\"}"); // swaps bot/updates mid-poll
            telegram.pushUpdate(botAddedTo(2, -1001234567890L, "ACME backend")); // only the OLD instance reads this

            JsonNode staleGroupResult = staleGroup.get(5, TimeUnit.SECONDS);

            assertTrue(staleGroupResult.path("group").isNull(), staleGroupResult.toString());
            assertTrue(call(longPoll, "/api/setup/state", "{}").path("group").isNull());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void writeWaitsForAnInFlightLongPollRatherThanRacingItsUpdates() throws Exception {
        call("/api/setup/token", "{\"token\":\"" + TOKEN + "\"}");
        telegram.pushUpdate(start(1, 100, "Bold"));
        call("/api/setup/people/next", "{}");
        call("/api/setup/people/answer", "{\"id\":100,\"accept\":true}");
        String writeBody = "{\"claude\":\"" + json(JAVA) + "\",\"authorName\":\"a\",\"authorEmail\":\"a@example.com\","
                + "\"projects\":[{\"folder\":\"" + json(repos.repo("alm").toString()) + "\",\"name\":\"alm\",\"baseBranch\":\"main\"}]}";

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // No update is pushed: this polls for the whole 1s poll duration set up in setUp().
            Future<JsonNode> emptyPoll = executor.submit(() -> call("/api/setup/people/next", "{}"));
            Thread.sleep(200); // let the poll start and take the "reading" lock
            long started = System.nanoTime();
            JsonNode written = call("/api/setup/write", writeBody);
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertTrue(elapsedMillis >= 700, "write must wait for the in-flight poll's \"reading\" lock, not race its "
                    + "non-thread-safe Setup.Updates; took only " + elapsedMillis + "ms");
            assertEquals(config.toString(), written.path("configFile").asText());
            assertTrue(emptyPoll.get(5, TimeUnit.SECONDS).path("candidate").isNull());
        } finally {
            executor.shutdownNow();
        }
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
        return call(setup, path, body);
    }

    private static JsonNode call(SetupApi api, String path, String body) throws Exception {
        Object answer = api.routes().get(path).apply(Json.MAPPER.readTree(body));
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

    private static JsonNode botAddedTo(long updateId, long chatId, String title) {
        return Json.read("""
                {"update_id":%d,"my_chat_member":{"chat":{"id":%d,"title":"%s","type":"supergroup"},
                 "from":{"id":100,"is_bot":false,"first_name":"Bold"},"date":1789640000,
                 "old_chat_member":{"user":{"id":1,"is_bot":true,"first_name":"Dispatch"},"status":"left"},
                 "new_chat_member":{"user":{"id":1,"is_bot":true,"first_name":"Dispatch"},"status":"member"}}}"""
                .formatted(updateId, chatId, title));
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
