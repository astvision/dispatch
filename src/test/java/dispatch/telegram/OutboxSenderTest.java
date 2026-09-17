package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
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
        sender = new OutboxSender(db, api, new Renderer(Renderer.mongolian(), clock), new Signal(), clock, Duration.ofSeconds(1));
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

    private Map<String, String> row(long id) {
        return SqlRows.single(dbFile, "SELECT * FROM outbox WHERE id = ?", id);
    }
}
