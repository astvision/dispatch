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
                new Config.Telegram(List.of(new Config.Group("backend", GROUP,
                        List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("autoland-management")))),
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
    void taskGivenPrivatelyBecomesAPrivatePlanThatTheRequesterRejects() throws Exception {
        app = start();
        JsonNode groupMenu = telegram.awaitRequest("setMyCommands", WAIT).json();
        assertEquals(GROUP, groupMenu.get("scope").get("chat_id").asLong());
        assertFalse(groupMenu.get("commands").toString().contains("\"task\""), groupMenu.toString());
        JsonNode privateMenu = telegram.awaitRequest("setMyCommands", WAIT).json();
        assertEquals("all_private_chats", privateMenu.get("scope").get("type").asText());
        assertTrue(privateMenu.get("commands").toString().contains("\"task\""), privateMenu.toString());

        giveTask(1, "Staging дээр нэвтрэх үед timeout болж байна", "NORMAL");

        JsonNode announced = awaitMessageContaining("#1");
        assertEquals(GROUP, announced.get("chat_id").asLong(), "the group hears who gave which task");
        assertTrue(announced.get("text").asText().contains("Bold"), announced.toString());
        JsonNode plan = awaitMessageContaining("The user reports that login");
        assertEquals(100, plan.get("chat_id").asLong(), "the plan goes to the requester privately");
        assertEquals("reject:1:1", plan.get("reply_markup").get("inline_keyboard").get(0).get(1).get("callback_data").asText());

        telegram.pushUpdate(privateCallback(20, 100, "Bold", "reject:1:1", awaitSentMessageId("PLAN_READY")));

        assertEquals(messages.getString("callback.rejected"), awaitCallbackAnswer());
        JsonNode rejected = telegram.awaitRequest("sendMessage", WAIT).json();
        assertEquals(GROUP, rejected.get("chat_id").asLong(), "the group hears the outcome");
        assertTrue(rejected.get("text").asText().contains("Bold"), rejected.toString());
        assertEquals("REJECTED", SqlRows.single(repos.stateDir.resolve("dispatch.db"), "SELECT phase FROM task WHERE id = 1").get("phase"));
        assertTrue(fatalErrors.isEmpty(), fatalErrors.toString());
    }

    @Test
    void correctedAndApprovedPlanEndsAsADraftPullRequestReportedPrivatelyAndInTheGroup() throws Exception {
        app = start();
        giveTask(1, "Fix the login timeout on staging", "URGENT");
        JsonNode firstPlan = awaitMessageContaining("The user reports that login");
        assertEquals(100, firstPlan.get("chat_id").asLong());
        assertEquals("approve:1:1", firstPlan.get("reply_markup").get("inline_keyboard").get(0).get(0).get("callback_data").asText());

        // A member can only reply once the plan exists; Dispatch knows its message id once Telegram has answered.
        telegram.pushUpdate(privateReply(20, 21, 100, "Bold", "Also cover the mobile login", awaitSentMessageId("PLAN_READY")));
        assertEquals(100, awaitMessageContaining("✏️").get("chat_id").asLong());
        JsonNode revisedPlan = telegram.awaitRequest("sendMessage", WAIT).json();
        assertEquals("approve:1:2", revisedPlan.get("reply_markup").get("inline_keyboard").get(0).get(0).get("callback_data").asText());

        telegram.pushUpdate(privateCallback(21, 100, "Bold", "approve:1:2", 0));

        assertEquals(messages.getString("callback.approved"), awaitCallbackAnswer());
        JsonNode result = awaitMessageContaining(FakeGh.PR_URL);
        assertEquals(100, result.get("chat_id").asLong());
        assertTrue(result.get("text").asText().contains("AUTH_TIMEOUT_SECONDS"), "the full result with the summary: " + result);
        JsonNode inGroup = awaitMessageContaining(FakeGh.PR_URL);
        assertEquals(GROUP, inGroup.get("chat_id").asLong());
        assertFalse(inGroup.get("text").asText().contains("AUTH_TIMEOUT_SECONDS"), "one line in the group: " + inGroup);
        Path db = repos.stateDir.resolve("dispatch.db");
        assertEquals("COMPLETED", SqlRows.single(db, "SELECT phase FROM task WHERE id = 1").get("phase"));
        assertEquals("URGENT", SqlRows.single(db, "SELECT priority FROM task WHERE id = 1").get("priority"));
        assertEquals(FakeGh.PR_URL, SqlRows.single(db, "SELECT pr_url FROM task WHERE id = 1").get("pr_url"));
        assertEquals("dispatch #1: Fix the login timeout on staging", GitFixture.sh(dir, "git", "--git-dir", repos.origin.toString(),
                "log", "-1", "--format=%s", "refs/heads/dispatch/1"));
        assertTrue(fatalErrors.isEmpty(), fatalErrors.toString());
    }

    @Test
    void restartMarksTheInterruptedRunFailedAndStillTellsTheGroup() throws Exception {
        app = start();
        giveTask(1, "SCENARIO:sleep", "NORMAL");
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

    private long awaitSentMessageId(String kind) throws InterruptedException {
        Path db = repos.stateDir.resolve("dispatch.db");
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            List<Map<String, String>> rows = SqlRows.query(db, "SELECT sent_ref FROM outbox WHERE kind = ? ORDER BY id LIMIT 1", kind);
            String sentRef = rows.isEmpty() ? null : rows.getFirst().get("sent_ref");
            if (sentRef != null) {
                return Long.parseLong(sentRef.substring(sentRef.indexOf('/') + 1));
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no sent " + kind + " message");
    }

    /** Bold writes the task privately and presses a priority button on the prompt (one project, so nothing else is asked). */
    private void giveTask(long updateId, String text, String priority) throws InterruptedException {
        telegram.pushUpdate(Json.read("""
                {"update_id":%d,"message":{"message_id":%d,"from":{"id":100,"is_bot":false,"first_name":"Bold"},
                 "chat":{"id":100,"type":"private"},"date":1789640000,"text":%s}}"""
                .formatted(updateId, updateId, Json.MAPPER.valueToTree(text))));
        long prompt = awaitSentMessageId("DRAFT_PROMPT");
        telegram.pushUpdate(privateCallback(updateId + 1, 100, "Bold", "draft:1:prio:" + priority, prompt));
        assertEquals(messages.getString("callback.taskCreated"), awaitCallbackAnswer());
    }

    private String awaitCallbackAnswer() throws InterruptedException {
        return telegram.awaitRequest("answerCallbackQuery", WAIT).json().get("text").asText();
    }

    /** A button pressed on message {@code messageId} in the presser's private chat with the bot. */
    private static JsonNode privateCallback(long updateId, long fromId, String name, String data, long messageId) {
        return Json.read("""
                {"update_id":%d,"callback_query":{"id":"cb-%d","from":{"id":%d,"is_bot":false,"first_name":"%s"},
                 "message":{"message_id":%d,"chat":{"id":%d,"type":"private"},"date":1789640000,"text":"x"},
                 "chat_instance":"1","data":"%s"}}""".formatted(updateId, updateId, fromId, name, messageId, fromId, data));
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
