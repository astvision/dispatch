package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Redactor;
import dispatch.core.Signal;
import dispatch.domain.OutboxKind;
import dispatch.store.Database;
import dispatch.store.Outbox;
import dispatch.testing.FakeTelegram;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutboxSenderTest {

    private static final String SERVER_ERROR = "{\"ok\":false,\"error_code\":500,\"description\":\"Internal Server Error\"}";

    @TempDir
    Path dir;

    private FakeTelegram telegram;
    private Path dbFile;
    private Database db;
    private final TestClock clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
    private OutboxSender sender;

    @BeforeEach
    void setUp() throws Exception {
        telegram = FakeTelegram.start();
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        BotApi api = new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5));
        sender = new OutboxSender(db, api, new Renderer(Renderer.mongolian(), clock, FakeTelegram.BOT_USERNAME),
                Redactor.fromEnvironment(Map.of("ANTHROPIC_API_KEY", "sk-ant-test-value-for-instance")), new Signal(), clock,
                Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        telegram.close();
        db.close();
    }

    @Test
    void dueMessageIsSentAsReplyAndMarkedSent() throws Exception {
        long id = enqueue(OutboxKind.TASK_QUEUED, Json.object().put("taskId", 42).put("project", "autoland-management"));

        assertTrue(sender.deliverDue());

        JsonNode body = telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json();
        assertEquals(-100, body.get("chat_id").asLong());
        assertEquals(55, body.get("reply_parameters").get("message_id").asLong());
        assertTrue(body.get("text").asText().contains("#42"), body.toString());
        Map<String, String> row = row(id);
        assertEquals("SENT", row.get("status"));
        assertEquals("telegram:-100/1000", row.get("sent_ref"));
        assertEquals("1", row.get("attempts"));
        assertFalse(sender.deliverDue(), "nothing left to send");
    }

    @Test
    void secretsAreMaskedBeforeAnythingReachesTelegram() throws Exception {
        String githubToken = "gh" + "p_" + "Q7w8E9r0T1".repeat(4);
        String detail = "git fetch failed for https://x-access-token:" + githubToken + "@github.com/acme/app.git; key "
                + "sk-ant-test-value-for-instance";
        enqueue(OutboxKind.TASK_FAILED, Json.object().put("taskId", 7).put("reason", "SETUP").put("detail", detail));

        sender.deliverDue();

        String text = telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json().get("text").asText();
        assertFalse(text.contains(githubToken), text);
        assertFalse(text.contains("sk-ant-test-value-for-instance"), text);
        assertTrue(text.contains("https://[redacted]@github.com/acme/app.git"), text);
    }

    @Test
    void floodControlDelayFromTelegramIsHonoured() {
        telegram.respond("sendMessage", 429,
                "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 7\",\"parameters\":{\"retry_after\":7}}");
        long id = enqueue(OutboxKind.TASK_QUEUED, Json.object().put("taskId", 1).put("project", "alm"));

        sender.deliverDue();

        Map<String, String> row = row(id);
        assertEquals("PENDING", row.get("status"));
        assertEquals("1", row.get("attempts"));
        assertEquals("2026-09-17T10:00:07.000Z", row.get("next_attempt_at"));
        assertTrue(row.get("last_error").contains("429"), row.get("last_error"));
        assertFalse(sender.deliverDue(), "not due before the delay passes");
    }

    @Test
    void transientErrorsBackOffExponentially() {
        telegram.respond("sendMessage", 500, SERVER_ERROR);
        telegram.respond("sendMessage", 500, SERVER_ERROR);
        long id = enqueue(OutboxKind.TASK_QUEUED, Json.object().put("taskId", 1).put("project", "alm"));

        sender.deliverDue();
        assertEquals("2026-09-17T10:00:05.000Z", row(id).get("next_attempt_at"));
        clock.advance(Duration.ofSeconds(5));
        sender.deliverDue();

        assertEquals("2026-09-17T10:00:15.000Z", row(id).get("next_attempt_at"));
        assertEquals("2", row(id).get("attempts"));
    }

    @Test
    void permanentErrorFailsTheMessageAtOnce() {
        telegram.respond("sendMessage", 403, "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was kicked from the group chat\"}");
        long id = enqueue(OutboxKind.TASK_QUEUED, Json.object().put("taskId", 1).put("project", "alm"));

        sender.deliverDue();

        assertEquals("FAILED", row(id).get("status"));
        assertTrue(row(id).get("last_error").contains("bot was kicked"), row(id).get("last_error"));
    }

    @Test
    void privateMessageGoesToThePrivateChatWithoutAHint() throws Exception {
        long id = enqueuePrivate(OutboxKind.TASK_QUEUED, Json.object().put("taskId", 42).put("project", "alm"));

        sender.deliverDue();

        JsonNode body = telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json();
        assertEquals(100, body.get("chat_id").asLong());
        assertFalse(body.has("reply_parameters"));
        assertFalse(body.get("text").asText().contains("Start"), body.toString());
        assertEquals("telegram:100/1000", row(id).get("sent_ref"));
    }

    @Test
    void privateMessageTelegramRefusesFallsBackToTheGroupWithAStartHint() throws Exception {
        telegram.respond("sendMessage", 403,
                "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot can't initiate conversation with a user\"}");
        long id = enqueuePrivate(OutboxKind.TASK_QUEUED, Json.object().put("taskId", 42).put("project", "alm"));

        sender.deliverDue();

        Map<String, String> fellBack = row(id);
        assertEquals("PENDING", fellBack.get("status"));
        assertEquals("telegram:-100", fellBack.get("chat_ref"));
        assertEquals("telegram:-100/55", fellBack.get("reply_to_ref"));
        assertEquals("1", fellBack.get("fell_back"));
        assertTrue(fellBack.get("last_error").contains("can't initiate conversation"), fellBack.get("last_error"));

        assertTrue(sender.deliverDue(), "due again at once");

        assertEquals(100, telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json().get("chat_id").asLong());
        JsonNode inGroup = telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json();
        assertEquals(-100, inGroup.get("chat_id").asLong());
        assertEquals(55, inGroup.get("reply_parameters").get("message_id").asLong());
        assertTrue(inGroup.get("text").asText().contains("@" + FakeTelegram.BOT_USERNAME), inGroup.toString());
        assertEquals("SENT", row(id).get("status"));
        assertEquals("telegram:-100/1000", row(id).get("sent_ref"));
    }

    @Test
    void transientErrorOnAPrivateMessageIsRetriedThereFirst() {
        telegram.respond("sendMessage", 500, SERVER_ERROR);
        long id = enqueuePrivate(OutboxKind.TASK_QUEUED, Json.object().put("taskId", 1).put("project", "alm"));

        sender.deliverDue();

        assertEquals("telegram:100", row(id).get("chat_ref"));
        assertEquals("PENDING", row(id).get("status"));
        assertEquals("0", row(id).get("fell_back"));
    }

    @Test
    void messageStillFailingAfter24HoursIsGivenUp() {
        long id = enqueue(OutboxKind.TASK_QUEUED, Json.object().put("taskId", 1).put("project", "alm"));
        clock.advance(Duration.ofHours(25));
        telegram.respond("sendMessage", 500, SERVER_ERROR);

        sender.deliverDue();

        assertEquals("FAILED", row(id).get("status"));
        assertTrue(row(id).get("last_error").contains("24h"), row(id).get("last_error"));
    }

    @Test
    void longPlanIsUploadedAsDocument() throws Exception {
        ObjectNode payload = Json.object().put("taskId", 42).put("planSeq", 1).put("project", "autoland-management")
                .put("costUsd", "1.2").put("durationSeconds", 300);
        ObjectNode plan = payload.putObject("plan").put("understanding", "Big change");
        plan.putArray("findings");
        IntStream.rangeClosed(1, 150).forEach(i -> plan.withArray("steps").add("Step " + i + " with a reasonably long description"));
        plan.putArray("risks");
        plan.putArray("questions");
        long id = enqueue(OutboxKind.PLAN_READY, payload);

        sender.deliverDue();

        FakeTelegram.Request upload = telegram.awaitRequest("sendDocument", Duration.ofSeconds(1));
        assertTrue(upload.text().contains("filename=\"plan-42.md\""));
        assertEquals("SENT", row(id).get("status"));
    }

    private long enqueue(OutboxKind kind, ObjectNode payload) {
        return db.transactionReturning(tx -> Outbox.enqueue(tx, null, kind, "telegram:-100", "telegram:-100/55", payload, clock.instant()));
    }

    private long enqueuePrivate(OutboxKind kind, ObjectNode payload) {
        return db.transactionReturning(tx -> Outbox.enqueueWithFallback(tx, null, kind, "telegram:100", null, "telegram:-100",
                "telegram:-100/55", payload, clock.instant()));
    }

    private Map<String, String> row(long id) {
        return SqlRows.single(dbFile, "SELECT * FROM outbox WHERE id = ?", id);
    }
}
