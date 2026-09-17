package dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.config.Config;
import dispatch.telegram.BotApi;
import dispatch.telegram.Renderer;
import dispatch.testing.FakeClaude;
import dispatch.testing.FakeGh;
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
        Path gh = FakeGh.install(dir.resolve("bin"));
        config = new Config("backend", repos.stateDir,
                new Config.Telegram(GROUP, List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali"))),
                new Config.Scheduler(2),
                new Config.Limits(new Config.RunLimits(Duration.ofSeconds(60), new BigDecimal("2")),
                        new Config.RunLimits(Duration.ofSeconds(60), new BigDecimal("10"))),
                Map.of("claude-code", new Config.Agent(claude.toString())),
                List.of(new Config.Project("autoland-management", "alm", repos.origin.toString(), "main", "claude-code", null,
                        List.of(), null)),
                new Config.Delivery("Dispatch (backend)", "dispatch-backend@example.com", gh.toString()),
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
    void taskCommandBecomesAPrivatePlanThatTheRequesterRejects() throws Exception {
        app = start();
        JsonNode menu = telegram.awaitRequest("setMyCommands", WAIT).json();
        assertEquals(GROUP, menu.get("scope").get("chat_id").asLong());
        assertTrue(menu.get("commands").toString().contains("\"task\""), menu.toString());
        JsonNode privateMenu = telegram.awaitRequest("setMyCommands", WAIT).json();
        assertEquals("all_private_chats", privateMenu.get("scope").get("type").asText());
        assertFalse(privateMenu.get("commands").toString().contains("\"task\""), privateMenu.toString());

        telegram.pushUpdate(command(1, 10, 100, "Bold", "/task@" + FakeTelegram.BOT_USERNAME + " alm Staging дээр нэвтрэх үед timeout болж байна"));

        JsonNode queued = telegram.awaitRequest("sendMessage", WAIT).json();
        assertEquals(GROUP, queued.get("chat_id").asLong());
        assertTrue(queued.get("text").asText().contains("#1") && queued.get("text").asText().contains("Bold"), queued.toString());
        assertEquals(10, queued.get("reply_parameters").get("message_id").asLong());
        JsonNode plan = telegram.awaitRequest("sendMessage", WAIT).json();
        assertEquals(100, plan.get("chat_id").asLong(), "the plan goes to the requester privately");
        assertTrue(plan.get("text").asText().contains("The user reports that login"), plan.toString());
        assertEquals("reject:1:1", plan.get("reply_markup").get("inline_keyboard").get(0).get(1).get("callback_data").asText());

        telegram.pushUpdate(privateCallback(2, 100, "Bold", "reject:1:1"));

        assertEquals(messages.getString("callback.rejected"),
                telegram.awaitRequest("answerCallbackQuery", WAIT).json().get("text").asText());
        JsonNode rejected = telegram.awaitRequest("sendMessage", WAIT).json();
        assertEquals(GROUP, rejected.get("chat_id").asLong(), "the group hears the outcome");
        assertTrue(rejected.get("text").asText().contains("Bold"), rejected.toString());
        assertEquals("REJECTED", SqlRows.single(repos.stateDir.resolve("dispatch.db"), "SELECT phase FROM task WHERE id = 1").get("phase"));
        assertTrue(fatalErrors.isEmpty(), fatalErrors.toString());
    }

    @Test
    void correctedAndApprovedPlanEndsAsADraftPullRequestReportedPrivatelyAndInTheGroup() throws Exception {
        app = start();
        telegram.pushUpdate(command(1, 10, 100, "Bold", "/task@" + FakeTelegram.BOT_USERNAME + " alm Fix the login timeout on staging"));
        assertEquals(GROUP, telegram.awaitRequest("sendMessage", WAIT).json().get("chat_id").asLong());
        JsonNode firstPlan = telegram.awaitRequest("sendMessage", WAIT).json();
        assertEquals(100, firstPlan.get("chat_id").asLong());
        assertEquals("approve:1:1", firstPlan.get("reply_markup").get("inline_keyboard").get(0).get(0).get("callback_data").asText());

        // A member can only reply once the plan exists; Dispatch knows its message id once Telegram has answered.
        telegram.pushUpdate(privateReply(2, 11, 100, "Bold", "Also cover the mobile login", awaitSentMessageId("PLAN_READY")));
        assertEquals(100, awaitMessageContaining("✏️").get("chat_id").asLong());
        JsonNode revisedPlan = telegram.awaitRequest("sendMessage", WAIT).json();
        assertEquals("approve:1:2", revisedPlan.get("reply_markup").get("inline_keyboard").get(0).get(0).get("callback_data").asText());

        telegram.pushUpdate(privateCallback(3, 100, "Bold", "approve:1:2"));

        assertEquals(messages.getString("callback.approved"),
                telegram.awaitRequest("answerCallbackQuery", WAIT).json().get("text").asText());
        JsonNode result = awaitMessageContaining(FakeGh.PR_URL);
        assertEquals(100, result.get("chat_id").asLong());
        assertTrue(result.get("text").asText().contains("AUTH_TIMEOUT_SECONDS"), "the full result with the summary: " + result);
        JsonNode inGroup = awaitMessageContaining(FakeGh.PR_URL);
        assertEquals(GROUP, inGroup.get("chat_id").asLong());
        assertFalse(inGroup.get("text").asText().contains("AUTH_TIMEOUT_SECONDS"), "one line in the group: " + inGroup);
        Path db = repos.stateDir.resolve("dispatch.db");
        assertEquals("COMPLETED", SqlRows.single(db, "SELECT phase FROM task WHERE id = 1").get("phase"));
        assertEquals(FakeGh.PR_URL, SqlRows.single(db, "SELECT pr_url FROM task WHERE id = 1").get("pr_url"));
        assertEquals("dispatch #1: Fix the login timeout on staging", GitFixture.sh(dir, "git", "--git-dir", repos.origin.toString(),
                "log", "-1", "--format=%s", "refs/heads/dispatch/1"));
        assertTrue(fatalErrors.isEmpty(), fatalErrors.toString());
    }

    @Test
    void requesterWhoNeverStartedTheBotGetsThePlanInTheGroupWithAStartHint() throws Exception {
        telegram.refuseChat(100, 403, "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot can't initiate conversation with a user\"}");
        app = start();

        telegram.pushUpdate(command(1, 10, 100, "Bold", "/task@" + FakeTelegram.BOT_USERNAME + " alm Fix the login timeout"));

        JsonNode plan = awaitMessageContaining("The user reports that login");
        assertEquals(100, plan.get("chat_id").asLong(), "tried privately first");
        JsonNode fellBack = awaitMessageContaining("The user reports that login");
        assertEquals(GROUP, fellBack.get("chat_id").asLong());
        assertEquals(10, fellBack.get("reply_parameters").get("message_id").asLong());
        assertTrue(fellBack.get("text").asText().contains("@" + FakeTelegram.BOT_USERNAME), fellBack.toString());
        assertEquals("approve:1:1", fellBack.get("reply_markup").get("inline_keyboard").get(0).get(0).get("callback_data").asText());
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
        assertTrue(awaitMessageContaining(failure).get("text").asText().contains("#1"));
        Path db = repos.stateDir.resolve("dispatch.db");
        assertEquals("FAILED", SqlRows.single(db, "SELECT phase FROM task WHERE id = 1").get("phase"));
        assertEquals("0", SqlRows.single(db, "SELECT count(*) AS n FROM run WHERE status = 'RUNNING'").get("n"));
        assertTrue(fatalErrors.isEmpty(), fatalErrors.toString());
    }

    private App start() {
        BotApi api = new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(60));
        return App.start(config, api, FakeClaude.environment(), Clock.systemUTC(), fatalErrors::add);
    }

    /** The next sendMessage call whose text contains {@code fragment}; earlier calls are skipped. */
    private JsonNode awaitMessageContaining(String fragment) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            JsonNode message = telegram.awaitRequest("sendMessage", Duration.between(Instant.now(), deadline)).json();
            if (message.get("text").asText().contains(fragment)) {
                return message;
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

    private long awaitSentMessageId(String kind) throws InterruptedException {
        Path db = repos.stateDir.resolve("dispatch.db");
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            String sentRef = SqlRows.single(db, "SELECT sent_ref FROM outbox WHERE kind = ? ORDER BY id LIMIT 1", kind).get("sent_ref");
            if (sentRef != null) {
                return Long.parseLong(sentRef.substring(sentRef.indexOf('/') + 1));
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no sent " + kind + " message");
    }

    /** A button pressed on a plan in the presser's private chat with the bot. */
    private static JsonNode privateCallback(long updateId, long fromId, String name, String data) {
        return Json.read("""
                {"update_id":%d,"callback_query":{"id":"cb-%d","from":{"id":%d,"is_bot":false,"first_name":"%s"},
                 "message":{"message_id":1001,"chat":{"id":%d,"type":"private"},"date":1789640000,"text":"plan"},
                 "chat_instance":"1","data":"%s"}}""".formatted(updateId, updateId, fromId, name, fromId, data));
    }

    /** A reply in the sender's private chat with the bot. */
    private static JsonNode privateReply(long updateId, long messageId, long fromId, String name, String text, long repliedMessageId) {
        return Json.read("""
                {"update_id":%d,"message":{"message_id":%d,"from":{"id":%d,"is_bot":false,"first_name":"%s"},
                 "chat":{"id":%d,"type":"private"},"date":1789640000,"text":%s,
                 "reply_to_message":{"message_id":%d,"from":{"id":1,"is_bot":true,"first_name":"Dispatch"},
                                     "chat":{"id":%d,"type":"private"},"date":1789640000,"text":"plan"}}}"""
                .formatted(updateId, messageId, fromId, name, fromId, Json.MAPPER.valueToTree(text), repliedMessageId, fromId));
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
