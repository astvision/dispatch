package dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.config.Config;
import dispatch.telegram.BotApi;
import dispatch.telegram.Renderer;
import dispatch.testing.FakeClaude;
import dispatch.testing.FakeTelegram;
import dispatch.testing.GitFixture;
import dispatch.testing.SqlRows;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whole instance: fake Telegram in, fake claude + real git + SQLite underneath, fake Telegram out. */
class AppTest {

    private static final long GROUP = -1001234567890L;
    private static final Duration WAIT = Duration.ofSeconds(20);

    @TempDir
    Path dir;

    private final ResourceBundle messages = Renderer.mongolian();
    private final List<Throwable> fatalErrors = new CopyOnWriteArrayList<>();
    private FakeTelegram telegram;
    private GitFixture repos;
    private Config config;
    private App app;

    @BeforeEach
    void setUp() throws IOException {
        telegram = FakeTelegram.start();
        repos = GitFixture.create(dir, "autoland-management");
        Path claude = FakeClaude.install(Files.createDirectories(dir.resolve("bin")));
        config = new Config("backend", repos.stateDir,
                new Config.Telegram(GROUP, List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali"))),
                new Config.Scheduler(2),
                new Config.Limits(new Config.RunLimits(Duration.ofSeconds(60), new BigDecimal("2"))),
                Map.of("claude-code", new Config.Agent(claude.toString())),
                List.of(new Config.Project("autoland-management", "alm", repos.origin.toString(), "main", "claude-code", null,
                        List.of(), null)),
                new Config.Secrets(FakeTelegram.TOKEN, null));
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
        telegram.close();
    }

    @Test
    void taskCommandBecomesAPostedPlanThatAMemberRejects() throws Exception {
        app = start();
        JsonNode menu = telegram.awaitRequest("setMyCommands", WAIT).json();
        assertEquals(GROUP, menu.get("scope").get("chat_id").asLong());
        assertTrue(menu.get("commands").toString().contains("\"task\""), menu.toString());

        telegram.pushUpdate(command(1, 10, 100, "Bold", "/task@" + FakeTelegram.BOT_USERNAME + " alm Staging дээр нэвтрэх үед timeout болж байна"));

        JsonNode queued = telegram.awaitRequest("sendMessage", WAIT).json();
        assertTrue(queued.get("text").asText().contains("#1"), queued.toString());
        assertEquals(10, queued.get("reply_parameters").get("message_id").asLong());
        JsonNode plan = telegram.awaitRequest("sendMessage", WAIT).json();
        assertTrue(plan.get("text").asText().contains("Staging орчинд"), plan.toString());
        assertEquals("reject:1:1", plan.get("reply_markup").get("inline_keyboard").get(0).get(0).get("callback_data").asText());

        telegram.pushUpdate(Json.read("""
                {"update_id":2,"callback_query":{"id":"cb-2","from":{"id":200,"is_bot":false,"first_name":"Ali"},
                 "message":{"message_id":1001,"chat":{"id":%d,"type":"supergroup"},"date":1789640000,"text":"plan"},
                 "chat_instance":"1","data":"reject:1:1"}}""".formatted(GROUP)));

        assertEquals(messages.getString("callback.rejected"),
                telegram.awaitRequest("answerCallbackQuery", WAIT).json().get("text").asText());
        assertTrue(telegram.awaitRequest("sendMessage", WAIT).json().get("text").asText().contains("Ali"));
        assertEquals("REJECTED", SqlRows.single(repos.stateDir.resolve("dispatch.db"), "SELECT phase FROM task WHERE id = 1").get("phase"));
        assertTrue(fatalErrors.isEmpty(), fatalErrors.toString());
    }

    @Test
    void restartMarksTheInterruptedRunFailedAndStillTellsTheGroup() throws Exception {
        app = start();
        telegram.pushUpdate(command(1, 10, 100, "Bold", "/task alm SCENARIO:sleep"));
        telegram.awaitRequest("sendMessage", WAIT);
        awaitFile(repos.stateDir.resolve("worktrees/1/fake-claude.child"));

        app.stop();
        app = start();

        String failure = messages.getString("failure.INTERRUPTED");
        assertTrue(awaitMessageContaining(failure).contains("#1"));
        Path db = repos.stateDir.resolve("dispatch.db");
        assertEquals("FAILED", SqlRows.single(db, "SELECT phase FROM task WHERE id = 1").get("phase"));
        assertEquals("0", SqlRows.single(db, "SELECT count(*) AS n FROM run WHERE status = 'RUNNING'").get("n"));
        assertTrue(fatalErrors.isEmpty(), fatalErrors.toString());
    }

    private App start() {
        BotApi api = new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(60));
        return App.start(config, api, FakeClaude.environment(), Clock.systemUTC(), fatalErrors::add);
    }

    private String awaitMessageContaining(String fragment) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            String text = telegram.awaitRequest("sendMessage", Duration.between(Instant.now(), deadline)).json().get("text").asText();
            if (text.contains(fragment)) {
                return text;
            }
        }
        throw new AssertionError("no message containing " + fragment);
    }

    private static JsonNode command(long updateId, long messageId, long fromId, String name, String text) {
        int length = text.split("\\s", 2)[0].length();
        return Json.read("""
                {"update_id":%d,"message":{"message_id":%d,"from":{"id":%d,"is_bot":false,"first_name":"%s"},
                 "chat":{"id":%d,"title":"Team","type":"supergroup"},"date":1789640000,"text":%s,
                 "entities":[{"offset":0,"length":%d,"type":"bot_command"}]}}"""
                .formatted(updateId, messageId, fromId, name, GROUP, Json.MAPPER.valueToTree(text), length));
    }

    private static void awaitFile(Path file) throws Exception {
        Instant deadline = Instant.now().plus(WAIT);
        while (!Files.exists(file) || Files.readString(file).isBlank()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("timed out waiting for " + file);
            }
            Thread.sleep(20);
        }
    }
}
