package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.Members;
import dispatch.core.Projects;
import dispatch.core.RunTransitions;
import dispatch.core.TaskService;
import dispatch.domain.ClaimedRun;
import dispatch.domain.Plan;
import dispatch.store.Database;
import dispatch.store.Outbox;
import dispatch.store.Runs;
import dispatch.testing.FakeTelegram;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateHandlerTest {

    private static final long GROUP = -1001234567890L;

    @TempDir
    Path dir;

    private FakeTelegram telegram;
    private Path dbFile;
    private Database db;
    private final TestClock clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
    private final Renderer renderer = new Renderer(Renderer.mongolian(), clock);
    private RunTransitions transitions;
    private UpdateHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        telegram = FakeTelegram.start();
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        Config.Project alm = new Config.Project("autoland-management", "alm", "https://github.com/acme/alm.git", "main",
                "claude-code", null, List.of(), null);
        Projects projects = new Projects(List.of(alm), project -> Optional.empty());
        Members members = new Members(List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")));
        TaskService tasks = new TaskService(members, projects, new ActiveRuns(), clock, () -> { }, () -> { });
        transitions = new RunTransitions(db, clock, () -> { });
        BotApi api = new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5));
        handler = new UpdateHandler(db, tasks, projects, api, renderer, GROUP, FakeTelegram.BOT_USERNAME, clock, () -> { });
    }

    @AfterEach
    void tearDown() {
        telegram.close();
        db.close();
    }

    @Test
    void taskCommandFromMemberCreatesTaskAndStoresTheNextOffset() {
        handler.handle(message(500, 10, 100, "Bold", GROUP, "supergroup", "/task alm Fix the login timeout\nIt happens on staging", null));

        Map<String, String> task = row("SELECT * FROM task");
        assertEquals("autoland-management", task.get("project"));
        assertEquals("Fix the login timeout\nIt happens on staging", task.get("description"));
        assertEquals("telegram:" + GROUP + "/10", task.get("origin_ref"));
        assertEquals("telegram:" + GROUP, task.get("chat_ref"));
        assertEquals("telegram:100", task.get("requester_ref"));
        assertEquals("Bold", task.get("requester_name"));
        assertEquals("501", row("SELECT value FROM kv WHERE key = 'telegram.offset'").get("value"));
        assertEquals(501, handler.nextOffset());
    }

    @Test
    void taskCommandSentAsReplyTurnsTheRepliedMessageIntoTheTask() {
        String replied = """
                {"message_id":9,"from":{"id":300,"is_bot":false,"first_name":"QA"},"chat":{"id":%d,"type":"supergroup"},
                 "date":1789640000,"text":"Login fails after 30s on staging"}""".formatted(GROUP);

        handler.handle(message(501, 11, 100, "Bold", GROUP, "supergroup", "/task@" + FakeTelegram.BOT_USERNAME + " alm also check mobile",
                replied));

        assertEquals("Login fails after 30s on staging\n\nalso check mobile", row("SELECT description FROM task").get("description"));
    }

    @Test
    void commandForAnotherBotIsIgnoredButStillConsumed() {
        handler.handle(message(502, 12, 100, "Bold", GROUP, "supergroup", "/task@other_bot alm Fix it", null));

        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox").get("n"));
        assertEquals(503, handler.nextOffset());
    }

    @Test
    void strangerInTheGroupIsToldNo() {
        handler.handle(message(503, 13, 999, "Sara", GROUP, "supergroup", "/task alm Drop the tables", null));

        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"));
        assertEquals("NOT_ALLOWED", row("SELECT kind FROM outbox").get("kind"));
    }

    @Test
    void botLeavesAnyOtherGroup() throws Exception {
        handler.handle(message(504, 14, 100, "Bold", -555L, "supergroup", "/task alm Fix it", null));

        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"));
        assertEquals(-555L, telegram.awaitRequest("leaveChat", Duration.ofSeconds(2)).json().get("chat_id").asLong());
    }

    @Test
    void privateChatIsIgnored() throws Exception {
        handler.handle(message(505, 15, 100, "Bold", 100L, "private", "/task alm Fix it", null));

        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox").get("n"));
        Thread.sleep(100);
        assertTrue(telegram.drain("leaveChat").isEmpty());
    }

    @Test
    void rejectButtonRejectsThePlanAndAnswersTheCallback() throws Exception {
        long taskId = taskAwaitingApproval();

        handler.handle(callback(510, 200, "Ali", "reject:" + taskId + ":1"));

        assertEquals("REJECTED", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        JsonNode answer = telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json();
        assertEquals("cb-510", answer.get("callback_query_id").asText());
        assertEquals(renderer.text("callback.rejected"), answer.get("text").asText());
    }

    @Test
    void buttonOfAnOlderPlanIsAnsweredAsStale() throws Exception {
        long taskId = taskAwaitingApproval();

        handler.handle(callback(511, 200, "Ali", "reject:" + taskId + ":9"));

        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        assertEquals(renderer.text("callback.stale"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
    }

    @Test
    void approveButtonApprovesThePlanAndAnswersTheCallback() throws Exception {
        long taskId = taskAwaitingApproval(List.of());

        handler.handle(callback(512, 200, "Ali", "approve:" + taskId + ":1"));

        assertEquals("EXECUTING", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        assertEquals("Ali", row("SELECT requested_by_name FROM run WHERE task_id = ? AND seq = 2", taskId).get("requested_by_name"));
        assertEquals(renderer.text("callback.approved"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
    }

    @Test
    void approveButtonOnAPlanWithOpenQuestionsSaysToAnswerThemFirst() throws Exception {
        long taskId = taskAwaitingApproval(List.of("Which environments?"));

        handler.handle(callback(513, 200, "Ali", "approve:" + taskId + ":1"));

        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        assertEquals(renderer.text("callback.openQuestions"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
    }

    @Test
    void replyToThePlanMessageIsACorrection() {
        long taskId = taskAwaitingApproval(List.of());
        planMessageSentAs(1000);

        handler.handle(message(540, 41, 200, "Ali", GROUP, "supergroup", "Also cover the mobile login", botMessage(1000)));

        assertEquals("PLANNING", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        Map<String, String> run = row("SELECT * FROM run WHERE task_id = ? AND seq = 2", taskId);
        assertEquals("PLAN", run.get("kind"));
        assertEquals("Also cover the mobile login", run.get("instruction"));
        assertEquals("telegram:" + GROUP + "/41", row("SELECT reply_to_ref FROM outbox WHERE kind = 'CORRECTION_QUEUED'").get("reply_to_ref"));
    }

    @Test
    void replyThatMerelyStartsLikeACommandIsStillACorrection() {
        long taskId = taskAwaitingApproval(List.of());
        planMessageSentAs(1000);

        handler.handle(message(541, 42, 200, "Ali", GROUP, "supergroup", "/api/login fails the same way", botMessage(1000)));

        assertEquals("/api/login fails the same way", row("SELECT instruction FROM run WHERE task_id = ? AND seq = 2", taskId).get("instruction"));
    }

    @Test
    void repliesToOtherBotMessagesAndCommandsForOtherBotsAreNotCorrections() {
        long taskId = taskAwaitingApproval(List.of());
        planMessageSentAs(1000);
        long messagesBefore = Long.parseLong(row("SELECT count(*) AS n FROM outbox").get("n"));
        String ackRef = "telegram:" + GROUP + "/999";
        db.transaction(tx -> tx.update("UPDATE outbox SET status = 'SENT', sent_ref = ? WHERE kind = 'TASK_QUEUED'", ackRef));

        handler.handle(message(542, 43, 200, "Ali", GROUP, "supergroup", "thanks", botMessage(999)));
        handler.handle(message(543, 44, 200, "Ali", GROUP, "supergroup", "/start@other_bot", botMessage(1000)));
        handler.handle(message(544, 45, 200, "Ali", GROUP, "supergroup", "just chatting", null));

        assertEquals("1", row("SELECT count(*) AS n FROM run WHERE task_id = ?", taskId).get("n"));
        assertEquals(messagesBefore, Long.parseLong(row("SELECT count(*) AS n FROM outbox").get("n")));
        assertEquals(545, handler.nextOffset());
    }

    @Test
    void cancelCommandCancelsTheTask() {
        handler.handle(message(520, 20, 100, "Bold", GROUP, "supergroup", "/task alm Fix it", null));

        handler.handle(message(521, 21, 200, "Ali", GROUP, "supergroup", "/cancel 1", null));

        assertEquals("CANCELLED", row("SELECT phase FROM task WHERE id = 1").get("phase"));
    }

    @Test
    void helpListsTheProjects() {
        handler.handle(message(530, 30, 999, "Sara", GROUP, "supergroup", "/help", null));

        Map<String, String> reply = row("SELECT * FROM outbox");
        assertEquals("HELP", reply.get("kind"));
        JsonNode payload = Json.read(reply.get("payload"));
        assertEquals("alm", payload.get("projects").get(0).get("alias").asText());
        assertEquals(FakeTelegram.BOT_USERNAME, payload.get("bot").asText());
        assertTrue(renderer.render(dispatch.domain.OutboxKind.HELP, payload).html().contains("/task@" + FakeTelegram.BOT_USERNAME));
    }

    private long taskAwaitingApproval() {
        return taskAwaitingApproval(List.of());
    }

    private long taskAwaitingApproval(List<String> questions) {
        handler.handle(message(509, 19, 100, "Bold", GROUP, "supergroup", "/task alm Fix the login timeout", null));
        ClaimedRun run = db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        Plan plan = new Plan("Make the timeout configurable", List.of(), List.of("Read auth.timeout"), List.of(), questions);
        transitions.planSucceeded(run.taskId(), run.seq(), plan,
                new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", plan.toJson(), null, new BigDecimal("0.1"), 3, List.of(), null));
        return run.taskId();
    }

    /** As the outbox sender records it once Telegram accepted the plan message. */
    private void planMessageSentAs(long messageId) {
        long outboxId = Long.parseLong(row("SELECT id FROM outbox WHERE kind = 'PLAN_READY'").get("id"));
        db.transaction(tx -> Outbox.markSent(tx, outboxId, 1, "telegram:" + GROUP + "/" + messageId, clock.instant()));
    }

    private static String botMessage(long messageId) {
        return """
                {"message_id":%d,"from":{"id":1,"is_bot":true,"first_name":"Dispatch"},"chat":{"id":%d,"type":"supergroup"},
                 "date":1789640000,"text":"plan"}""".formatted(messageId, GROUP);
    }

    /** Shaped like a real Bot API update; the command entity covers the leading /command[@bot] token. */
    static JsonNode message(long updateId, long messageId, long fromId, String firstName, long chatId, String chatType,
                            String text, String replyToJson) {
        int commandLength = text.startsWith("/") ? text.split("\\s", 2)[0].length() : 0;
        String entities = commandLength > 0 ? ",\"entities\":[{\"offset\":0,\"length\":" + commandLength + ",\"type\":\"bot_command\"}]" : "";
        String reply = replyToJson == null ? "" : ",\"reply_to_message\":" + replyToJson;
        return Json.read("""
                {"update_id":%d,"message":{"message_id":%d,
                 "from":{"id":%d,"is_bot":false,"first_name":"%s","username":"%s_dev","language_code":"mn"},
                 "chat":{"id":%d,"title":"Team","type":"%s"},"date":1789640000,"text":%s%s%s}}"""
                .formatted(updateId, messageId, fromId, firstName, firstName.toLowerCase(), chatId, chatType,
                        Json.MAPPER.valueToTree(text), entities, reply));
    }

    static JsonNode callback(long updateId, long fromId, String firstName, String data) {
        return Json.read("""
                {"update_id":%d,"callback_query":{"id":"cb-%d",
                 "from":{"id":%d,"is_bot":false,"first_name":"%s"},
                 "message":{"message_id":77,"from":{"id":1,"is_bot":true,"first_name":"Dispatch"},
                            "chat":{"id":%d,"title":"Team","type":"supergroup"},"date":1789640000,"text":"plan"},
                 "chat_instance":"123","data":"%s"}}"""
                .formatted(updateId, updateId, fromId, firstName, GROUP, data));
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
