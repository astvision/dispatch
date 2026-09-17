package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.Groups;
import dispatch.core.Projects;
import dispatch.core.TaskService;
import dispatch.store.Database;
import dispatch.store.Kv;
import dispatch.testing.FakeTelegram;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PollerTest {

    private static final long GROUP = -1001234567890L;

    @TempDir
    Path dir;

    private FakeTelegram telegram;
    private Path dbFile;
    private Database db;
    private Poller poller;
    private Thread thread;

    @BeforeEach
    void setUp() throws Exception {
        telegram = FakeTelegram.start();
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        TestClock clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
        Config.Project alm = new Config.Project("autoland-management", "alm", "https://github.com/acme/alm.git", null, "main",
                "claude-code", null, null, List.of(), null);
        Projects projects = new Projects(List.of(alm), project -> Optional.empty());
        Groups groups = new Groups(List.of(new Config.Group("backend", GROUP, List.of(new Config.Member(100, "Bold")),
                List.of("autoland-management"))));
        TaskService tasks = new TaskService(groups, projects, new ActiveRuns(), clock, () -> { }, () -> { });
        BotApi api = new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5));
        UpdateHandler handler = new UpdateHandler(db, tasks, new dispatch.core.Membership(groups, (group, member) -> {
            throw new AssertionError("no one joins in this test");
        }, clock, () -> { }), groups, projects, api,
                new Renderer(Renderer.mongolian(), clock, FakeTelegram.BOT_USERNAME), dispatch.Redactor.patternsOnly(), FakeTelegram.BOT_USERNAME,
                clock, () -> { });
        poller = new Poller(api, handler, 0, Duration.ofMillis(50), Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (thread != null) {
            poller.stop();
            thread.interrupt();
            thread.join(Duration.ofSeconds(5));
        }
        telegram.close();
        db.close();
    }

    @Test
    void resumesFromTheStoredOffsetAndSkipsAnUpdateThatCannotBeHandled() throws Exception {
        db.transaction(tx -> Kv.put(tx, "telegram.offset", "40"));
        JsonNode malformed = Json.read("""
                {"update_id":40,"message":{"message_id":1,"from":{"id":100,"is_bot":false,"first_name":"Bold"},
                 "chat":{"id":%d,"type":"supergroup"},"date":1789640000,"text":"/t",
                 "entities":[{"offset":0,"length":99,"type":"bot_command"}]}}""".formatted(GROUP));
        telegram.pushUpdate(malformed);
        telegram.pushUpdate(UpdateHandlerTest.message(41, 2, 100, "Bold", 100L, "private", "Fix it", null));

        thread = Thread.ofVirtual().start(poller);

        assertEquals(40, telegram.awaitRequest("getUpdates", Duration.ofSeconds(5)).json().get("offset").asLong());
        assertEquals(42, telegram.awaitRequest("getUpdates", Duration.ofSeconds(5)).json().get("offset").asLong());
        assertEquals("1", SqlRows.single(dbFile, "SELECT count(*) AS n FROM draft").get("n"));
        assertEquals("42", SqlRows.single(dbFile, "SELECT value FROM kv WHERE key = 'telegram.offset'").get("value"));
    }

    @Test
    void updateThatFailsIsLoggedWithoutTheChatContent() throws Exception {
        JsonNode malformed = Json.read("""
                {"update_id":40,"message":{"message_id":1,"from":{"id":100,"is_bot":false,"first_name":"Bold"},
                 "chat":{"id":%d,"type":"supergroup"},"date":1789640000,"text":"/t private chat words",
                 "entities":[{"offset":0,"length":99,"type":"bot_command"}]}}""".formatted(GROUP));
        telegram.pushUpdate(malformed);
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            thread = Thread.ofVirtual().start(poller);
            telegram.awaitRequest("getUpdates", Duration.ofSeconds(5));
            assertEquals(41, telegram.awaitRequest("getUpdates", Duration.ofSeconds(5)).json().get("offset").asLong());
        } finally {
            poller.stop();
            thread.interrupt();
            thread.join(Duration.ofSeconds(5));
            System.setOut(original);
        }

        String output = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(output.contains("event=telegram.update_failed") && output.contains("update_id=40"), output);
        assertFalse(output.contains("private chat words"), output);
    }

    @Test
    void unreachableTelegramIsRetriedUntilItRecovers() throws Exception {
        telegram.respond("getUpdates", 502, "<html>Bad Gateway</html>");
        telegram.respond("getUpdates", 502, "<html>Bad Gateway</html>");
        telegram.pushUpdate(UpdateHandlerTest.message(7, 3, 100, "Bold", 100L, "private", "Fix it", null));

        thread = Thread.ofVirtual().start(poller);

        for (int call = 0; call < 3; call++) {
            telegram.awaitRequest("getUpdates", Duration.ofSeconds(5));
        }
        telegram.awaitRequest("getUpdates", Duration.ofSeconds(5));
        assertEquals("1", SqlRows.single(dbFile, "SELECT count(*) AS n FROM draft").get("n"));
    }

    @Test
    void stopEndsThePollingLoop() throws Exception {
        thread = Thread.ofVirtual().start(poller);
        telegram.awaitRequest("getUpdates", Duration.ofSeconds(5));

        poller.stop();
        thread.interrupt();
        thread.join(Duration.ofSeconds(5));

        assertFalse(thread.isAlive());
    }
}
