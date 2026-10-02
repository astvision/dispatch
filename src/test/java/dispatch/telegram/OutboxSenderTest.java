package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    void aReplyTargetOutsideTelegramIsSentAsAPlainMessage() throws Exception {
        // A task given on the desktop (D-2b) comes from "desk:…": there is no Telegram message to reply to.
        long id = db.transactionReturning(tx -> Outbox.enqueue(tx, null, OutboxKind.TASK_QUEUED, "telegram:100", "desk:5f0c9a2e",
                Json.object().put("taskId", 42).put("project", "alm").put("requester", "Bold").put("priority", "NORMAL")
                        .put("title", "Fix the login timeout"), clock.instant()));

        assertTrue(sender.deliverDue());

        JsonNode body = telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json();
        assertEquals(100, body.get("chat_id").asLong());
        assertFalse(body.has("reply_parameters"), body.toString());
        assertEquals("SENT", row(id).get("status"));
    }

    @Test
    void editRedrawsItsMessageInPlaceAndLeavesRepliesPointingAtTheOriginal() throws Exception {
        long id = enqueueEdit(Json.object().put("draftId", 7).put("title", "Fix login timeout").put("status", "CREATED")
                .put("project", "life").put("taskId", 9).put("priority", "URGENT"));

        assertTrue(sender.deliverDue());

        JsonNode body = telegram.awaitRequest("editMessageText", Duration.ofSeconds(1)).json();
        assertEquals(100, body.get("chat_id").asLong());
        assertEquals(77, body.get("message_id").asLong());
        assertTrue(body.get("text").asText().contains("#9"), body.toString());
        assertTrue(telegram.drain("sendMessage").isEmpty(), "no new message");
        Map<String, String> row = row(id);
        assertEquals("SENT", row.get("status"));
        assertNull(row.get("sent_ref"), "the edited message keeps its own outbox row");
    }

    @Test
    void editWhoseTextIsAlreadyShownCountsAsDone() {
        // What Telegram answers when a retried edit had already been applied before its response was lost.
        telegram.respond("editMessageText", 400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message is not "
                + "modified: specified new message content and reply markup are exactly the same as a current content and reply "
                + "markup of the message\"}");
        long id = enqueueEdit(Json.object().put("draftId", 7).put("title", "Fix login timeout").put("status", "EXPIRED"));

        sender.deliverDue();

        assertEquals("SENT", row(id).get("status"));
    }

    @Test
    void aRedrawWaitsForTheMessageItRedrawsAndThenEditsIt() throws Exception {
        // An answer given before its question reached the chat (ADR 0031), whose first send then fails.
        telegram.respond("sendMessage", 500, SERVER_ERROR);
        long question = enqueueQuestion();
        long redraw = enqueueRedrawOf(question);

        sender.deliverDue();
        sender.deliverDue();

        assertEquals("PENDING", row(redraw).get("status"), "waits for its question");
        assertEquals("0", row(redraw).get("attempts"), "waiting is no attempt");
        clock.advance(Duration.ofSeconds(5));
        sender.deliverDue();
        sender.deliverDue();

        assertEquals(List.of("sendMessage", "sendMessage", "editMessageText"), telegram.calls(),
                "the failed send, the question once, then its redraw: never a second question");
        assertEquals(1000, telegram.awaitRequest("editMessageText", Duration.ofSeconds(1)).json().get("message_id").asLong(),
                "the message the question was sent as");
        assertEquals("SENT", row(question).get("status"));
        assertEquals("SENT", row(redraw).get("status"));
    }

    @Test
    void aRedrawOfAMessageThatWasNeverSentIsDropped() {
        long question = enqueueQuestion();
        long redraw = enqueueRedrawOf(question);
        db.transaction(tx -> Outbox.markFailed(tx, question, 1, "Forbidden: bot was blocked by the user"));

        String log = logged(() -> assertTrue(sender.deliverDue()));

        assertEquals("FAILED", row(redraw).get("status"));
        assertTrue(telegram.calls().isEmpty(), "nothing to redraw: " + telegram.calls());
        assertTrue(log.contains("level=WARN event=outbox.redraw_dropped id=" + redraw + " kind=PLAN_QUESTION original=" + question
                + " error=\"the message it redraws was never sent\""), log);
    }

    @Test
    void aRedrawWhoseMessageIsGoneIsDroppedAndTheOutboxGoesOn() throws Exception {
        long question = enqueueQuestion();
        long redraw = enqueueRedrawOf(question);
        long next = enqueue(OutboxKind.TASK_QUEUED, Json.object().put("taskId", 42).put("project", "alm"));
        Database.prepareSqlite();
        // Nothing in the store deletes a row another one redraws: only a connection without foreign keys can.
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + dbFile); Statement statement = raw.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate("DELETE FROM outbox WHERE id = " + question);
        }

        String log = logged(() -> assertTrue(sender.deliverDue()));
        assertTrue(sender.deliverDue());

        Map<String, String> dropped = row(redraw);
        assertEquals("FAILED", dropped.get("status"));
        assertEquals("the message it redraws is gone", dropped.get("last_error"));
        assertEquals(List.of("sendMessage"), telegram.calls(), "the message after it still went out");
        assertEquals("SENT", row(next).get("status"));
        assertTrue(log.contains("level=WARN event=outbox.redraw_dropped id=" + redraw + " kind=PLAN_QUESTION original=" + question
                + " error=\"the message it redraws is gone\""), log);
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
    void privateMessageTelegramRefusesFallsBackToTheGroupAsANoticeWithoutItsContent() throws Exception {
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
    void taskTopicIsCreatedWithItsPriorityColourThenTheTasksPrivateMessagesGoIntoIt() throws Exception {
        long taskId = task("URGENT");
        enqueueFor(taskId, OutboxKind.TOPIC_CREATE, "telegram:100", null, Json.object().put("taskId", taskId));
        enqueueFor(taskId, OutboxKind.PLAN_READY, "telegram:100", "telegram:100/5", planPayload(taskId));

        sender.deliverDue();
        sender.deliverDue();

        JsonNode created = telegram.awaitRequest("createForumTopic", Duration.ofSeconds(1)).json();
        assertEquals(100, created.get("chat_id").asLong());
        assertEquals("#" + taskId + " · life · Fix the login timeout", created.get("name").asText());
        assertEquals(0xFB6F5F, created.get("icon_color").asInt());
        assertEquals("500", SqlRows.single(dbFile, "SELECT topic_ref FROM task WHERE id = ?", taskId).get("topic_ref"));
        JsonNode plan = telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json();
        assertEquals(500, plan.get("message_thread_id").asLong());
        assertFalse(plan.has("reply_parameters"), "the task's own message is outside its topic");
    }

    @Test
    void messageForATopicThatIsGoneGoesToGeneralInsteadOfTheGroup() throws Exception {
        // What Telegram answers for a topic the requester deleted, and for older topics after Bot API 10.0 (tdlib/telegram-bot-api#847).
        telegram.respond("sendMessage", 400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message thread not found\"}");
        long taskId = task("NORMAL");
        db.transaction(tx -> tx.update("UPDATE task SET topic_ref = '500' WHERE id = ?", taskId));
        dispatch.domain.Task task = db.transactionReturning(tx -> dispatch.store.Tasks.find(tx, taskId)).orElseThrow();
        long id = db.transactionReturning(tx -> Outbox.enqueueForRequester(tx, task, OutboxKind.PLAN_READY, planPayload(taskId), clock.instant()));

        sender.deliverDue();

        assertEquals(500, telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json().get("message_thread_id").asLong());
        assertNull(SqlRows.single(dbFile, "SELECT topic_ref FROM task WHERE id = ?", taskId).get("topic_ref"), "the topic is forgotten");
        assertEquals("telegram:100", row(id).get("chat_ref"), "no fallback to the group");
        assertTrue(sender.deliverDue(), "due again at once");
        JsonNode inGeneral = telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json();
        assertEquals(100, inGeneral.get("chat_id").asLong());
        assertFalse(inGeneral.has("message_thread_id"), inGeneral.toString());
        assertEquals(5, inGeneral.get("reply_parameters").get("message_id").asLong(), "under the message that gave the task");
        assertEquals("SENT", row(id).get("status"));
    }

    @Test
    void topicIsRenamedByTheOutcomeSentToTheTasksOwnChatOnly() throws Exception {
        long personal = task("NORMAL");
        db.transaction(tx -> tx.update("UPDATE task SET origin_ref = 'telegram:100/4' WHERE id = ?", personal));
        long inGroup = task("NORMAL");
        db.transaction(tx -> {
            tx.update("UPDATE task SET topic_ref = '500', phase = 'COMPLETED', chat_ref = 'telegram:100' WHERE id = ?", personal);
            tx.update("UPDATE task SET topic_ref = '501', phase = 'COMPLETED' WHERE id = ?", inGroup);
        });
        ObjectNode result = Json.object().put("project", "life").put("prUrl", "https://github.com/acme/life/pull/1").put("filesChanged", 1)
                .put("summary", "Done").put("costUsd", "0.1").put("durationSeconds", 60);
        enqueueFor(personal, OutboxKind.TASK_COMPLETED, "telegram:100", "telegram:100/5", result.deepCopy().put("taskId", personal));
        enqueueFor(inGroup, OutboxKind.TASK_COMPLETED, "telegram:100", "telegram:100/5", result.deepCopy().put("taskId", inGroup));

        sender.deliverDue();
        sender.deliverDue();

        JsonNode renamed = telegram.awaitRequest("editForumTopic", Duration.ofSeconds(1)).json();
        assertEquals(500, renamed.get("message_thread_id").asLong(), "a personal task has no group line, so its result renames it");
        assertTrue(telegram.drain("editForumTopic").isEmpty(), "a group task's topic is renamed by its group line instead");
    }

    @Test
    void aPersonalTasksAnswerRenamesItsTopicAsFinished() throws Exception {
        long personal = task("NORMAL");
        db.transaction(tx -> tx.update("""
                UPDATE task SET origin_ref = 'telegram:100/4', topic_ref = '500', phase = 'COMPLETED', chat_ref = 'telegram:100'
                WHERE id = ?""", personal));
        enqueueFor(personal, OutboxKind.ANSWER_READY, "telegram:100", "telegram:100/5", Json.object().put("taskId", personal)
                .put("project", "life").put("answer", "Because.").put("costUsd", "0.1").put("durationSeconds", 60));

        sender.deliverDue();

        JsonNode renamed = telegram.awaitRequest("editForumTopic", Duration.ofSeconds(1)).json();
        assertEquals(500, renamed.get("message_thread_id").asLong(), "a personal task has no group line: its answer renames it");
    }


    @Test
    void finishedTasksTopicIsRenamedWithItsOutcome() throws Exception {
        long taskId = task("NORMAL");
        db.transaction(tx -> tx.update("UPDATE task SET topic_ref = '500', phase = 'COMPLETED' WHERE id = ?", taskId));
        enqueueFor(taskId, OutboxKind.TASK_COMPLETED_SHORT, "telegram:-100", null,
                Json.object().put("taskId", taskId).put("project", "life").put("prUrl", "https://github.com/acme/life/pull/1").put("filesChanged", 1));

        sender.deliverDue();

        assertEquals(-100, telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json().get("chat_id").asLong());
        JsonNode renamed = telegram.awaitRequest("editForumTopic", Duration.ofSeconds(1)).json();
        assertEquals(100, renamed.get("chat_id").asLong());
        assertEquals(500, renamed.get("message_thread_id").asLong());
        assertEquals("✅ #" + taskId + " · life · Fix the login timeout", renamed.get("name").asText());
    }

    @Test
    void replyToAMessageWrittenInATopicStaysInThatTopic() throws Exception {
        db.transactionReturning(tx -> Outbox.enqueue(tx, null, OutboxKind.TASK_USAGE, "telegram:100", "telegram:100/77@55", Json.object(),
                clock.instant()));

        sender.deliverDue();

        JsonNode body = telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json();
        assertEquals(55, body.get("message_thread_id").asLong());
        assertEquals(77, body.get("reply_parameters").get("message_id").asLong());
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

    @Test
    void reactionIsSentAndMarkedSentWithoutASentRef() throws Exception {
        long id = enqueueReaction(null, "✍", null);

        assertTrue(sender.deliverDue());

        JsonNode body = telegram.awaitRequest("setMessageReaction", Duration.ofSeconds(1)).json();
        assertEquals(-100, body.get("chat_id").asLong());
        assertEquals(55, body.get("message_id").asLong());
        assertEquals("✍", body.get("reaction").get(0).get("emoji").asText());
        assertEquals("emoji", body.get("reaction").get(0).get("type").asText());
        assertFalse(body.get("is_big").asBoolean());
        Map<String, String> row = row(id);
        assertEquals("SENT", row.get("status"));
        assertNull(row.get("sent_ref"), "a reaction is not a message of its own");
    }

    @Test
    void reactionRefusedOnThePromptStateFallsBackToTheEnvelopeLineOnce() throws Exception {
        telegram.respond("setMessageReaction", 400,
                "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: REACTION_INVALID\"}");
        long id = enqueueReaction(null, "👀", "Bold");

        sender.deliverDue();

        assertEquals("SENT", row(id).get("status"), "resolved once, not retried forever");
        assertTrue(sender.deliverDue(), "the fallback line is due at once");
        JsonNode fallback = telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json();
        assertEquals(-100, fallback.get("chat_id").asLong());
        assertEquals(55, fallback.get("reply_parameters").get("message_id").asLong());
        assertEquals("✉️ <b>Bold</b>: хувийн чатад илгээлээ.", fallback.get("text").asText());
    }

    @Test
    void reactionRefusedOnALaterStateIsOnlyLoggedNeverTheEnvelopeLine() {
        telegram.respond("setMessageReaction", 400,
                "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: REACTION_INVALID\"}");
        long id = enqueueReaction(null, "👍", null);

        sender.deliverDue();

        assertEquals("FAILED", row(id).get("status"));
        assertTrue(telegram.drain("sendMessage").isEmpty(), "no ✉️ line for a state past the prompt");
    }

    @Test
    void transientReactionErrorIsRetriedNotFallenBack() {
        telegram.respond("setMessageReaction", 500, SERVER_ERROR);
        long id = enqueueReaction(null, "✍", null);

        sender.deliverDue();

        assertEquals("PENDING", row(id).get("status"));
        assertTrue(telegram.drain("sendMessage").isEmpty());
    }

    private long enqueueReaction(Long taskId, String emoji, String requester) {
        ObjectNode payload = Json.object().put("emoji", emoji);
        if (requester != null) {
            payload.put("requester", requester);
        }
        return db.transactionReturning(tx -> Outbox.enqueue(tx, taskId, OutboxKind.GROUP_REACTION, "telegram:-100", "telegram:-100/55",
                payload, clock.instant()));
    }

    private long enqueue(OutboxKind kind, ObjectNode payload) {
        return db.transactionReturning(tx -> Outbox.enqueue(tx, null, kind, "telegram:-100", "telegram:-100/55", payload, clock.instant()));
    }

    private long enqueueEdit(ObjectNode draftPayload) {
        return db.transactionReturning(tx -> Outbox.enqueueEdit(tx, null, OutboxKind.DRAFT_PROMPT, "telegram:100", "telegram:100/77",
                draftPayload, clock.instant()));
    }

    private long enqueueQuestion() {
        return db.transactionReturning(tx -> Outbox.enqueue(tx, null, OutboxKind.PLAN_QUESTION, "telegram:100", null, questionPayload(),
                clock.instant()));
    }

    private long enqueueRedrawOf(long question) {
        return db.transactionReturning(tx -> Outbox.enqueueEditOf(tx, null, OutboxKind.PLAN_QUESTION, "telegram:100", question,
                questionPayload().put("answer", "prod"), clock.instant()));
    }

    private static ObjectNode questionPayload() {
        ObjectNode payload = Json.object().put("taskId", 42).put("planSeq", 1).put("index", 1).put("total", 2)
                .put("text", "Which environments?");
        payload.putArray("options").add("staging").add("prod");
        return payload;
    }

    private long enqueuePrivate(OutboxKind kind, ObjectNode payload) {
        return db.transactionReturning(tx -> Outbox.enqueueWithFallback(tx, null, kind, "telegram:100", null, "telegram:-100",
                "telegram:-100/55", payload, clock.instant()));
    }

    private long task(String priority) {
        return db.transactionReturning(tx -> tx.insert("""
                INSERT INTO task (project, title, description, phase, priority, requester_ref, requester_name, origin_ref, chat_ref,
                                  session_id, base_branch, created_at, updated_at)
                VALUES ('life', 'Fix the login timeout', 'Fix the login timeout', 'PLANNING', ?, 'telegram:100', 'Bold', 'telegram:100/5',
                        'telegram:-100', '63d36fba-124d-4737-8020-d37d4998abca', 'main', '2026-09-17T10:00:00.000Z', '2026-09-17T10:00:00.000Z')""",
                priority));
    }

    private long enqueueFor(long taskId, OutboxKind kind, String chatRef, String replyToRef, ObjectNode payload) {
        return db.transactionReturning(tx -> Outbox.enqueue(tx, taskId, kind, chatRef, replyToRef, payload, clock.instant()));
    }

    private static ObjectNode planPayload(long taskId) {
        ObjectNode payload = Json.object().put("taskId", taskId).put("planSeq", 1).put("project", "life").put("costUsd", "0.1")
                .put("durationSeconds", 10);
        ObjectNode plan = payload.putObject("plan").put("understanding", "Make it configurable");
        plan.putArray("findings");
        plan.putArray("steps").add("Change it");
        plan.putArray("risks");
        plan.putArray("questions");
        return payload;
    }

    private Map<String, String> row(long id) {
        return SqlRows.single(dbFile, "SELECT * FROM outbox WHERE id = ?", id);
    }

    /** The lines {@code work} logs: Log writes them to stdout. */
    private static String logged(Runnable work) {
        PrintStream out = System.out;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(logged, true, StandardCharsets.UTF_8));
            work.run();
        } finally {
            System.setOut(out);
        }
        return logged.toString(StandardCharsets.UTF_8);
    }

    /** Masked as one text, the URL pattern ran from the step's port to the risk's @ and took the field between them away. */
    @Test
    void aPlanWhoseTextLooksLikeAUrlCredentialAcrossTwoFieldsIsStillSent() throws Exception {
        long taskId = task("NORMAL");
        ObjectNode payload = planPayload(taskId);
        ((ObjectNode) payload.get("plan")).putArray("steps").add("Open http://localhost:8080");
        ((ObjectNode) payload.get("plan")).putArray("risks").add("ops@team.mn must approve");
        long id = enqueueFor(taskId, OutboxKind.PLAN_READY, "telegram:100", "telegram:100/5", payload);

        sender.deliverDue();

        assertEquals("SENT", row(id).get("status"));
        assertTrue(telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json().get("text").asText()
                .contains("Make it configurable"));
    }
}
