package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.Groups;
import dispatch.core.Membership;
import dispatch.core.Projects;
import dispatch.core.RunTransitions;
import dispatch.core.TaskService;
import dispatch.domain.ClaimedRun;
import dispatch.domain.Plan;
import dispatch.domain.PlanQuestion;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
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

/** Answering a plan's open questions with buttons (G-1d): one question message at a time, then one correction. */
class PlanQuestionsTest {

    private static final long GROUP = -1001234567890L;
    private static final long BOLD = 100;
    private static final String YOU_DECIDE = "Та хамгийн боломжит шийдлийг сонгож, таамаглал болгон тэмдэглэ.";

    @TempDir
    Path dir;

    private FakeTelegram telegram;
    private Path dbFile;
    private Database db;
    private final TestClock clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
    private final Renderer renderer = new Renderer(Renderer.mongolian(), clock, FakeTelegram.BOT_USERNAME);
    private RunTransitions transitions;
    private TaskService tasks;
    private UpdateHandler handler;
    private OutboxSender sender;

    @BeforeEach
    void setUp() throws Exception {
        telegram = FakeTelegram.start();
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        Config.Project alm = new Config.Project("autoland-management", "alm", "https://github.com/acme/alm.git", null, "main",
                "claude-code", null, null, List.of(), null, null, null);
        Projects projects = new Projects(List.of(alm), project -> Optional.empty());
        Groups groups = new Groups(List.of(new Config.Group("backend", GROUP,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("autoland-management"))));
        tasks = new TaskService(groups, projects, new ActiveRuns(), clock, () -> { }, () -> { });
        transitions = new RunTransitions(db, clock, () -> { });
        BotApi api = new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5));
        Membership membership = new Membership(groups, (group, member) -> null, clock, () -> { });
        dispatch.Redactor redactor = dispatch.Redactor.patternsOnly();
        handler = new UpdateHandler(db, tasks, membership, groups, projects, api, renderer, redactor, FakeTelegram.BOT_USERNAME, clock,
                () -> { });
        sender = new OutboxSender(db, api, renderer, redactor, new dispatch.core.Signal(), clock, Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        telegram.close();
        db.close();
    }

    @Test
    void aPlanWithTwoOpenQuestionsSendsOnlyTheFirstToTheRequester() {
        long taskId = taskWithTwoQuestions();

        assertEquals("1", count("SELECT count(*) AS n FROM outbox WHERE kind = 'PLAN_QUESTION'"));
        Map<String, String> question = row("SELECT * FROM outbox WHERE kind = 'PLAN_QUESTION'");
        assertEquals("telegram:100", question.get("chat_ref"), "privately, never to the group");
        JsonNode payload = Json.read(question.get("payload"));
        assertEquals(taskId, payload.get("taskId").asLong());
        assertEquals(1, payload.get("planSeq").asInt());
        assertEquals(1, payload.get("index").asInt());
        assertEquals(2, payload.get("total").asInt());
        assertEquals("Which environments?", payload.get("text").asText());
        assertEquals("[\"staging\",\"prod\"]", payload.get("options").toString());
        assertTrue(Long.parseLong(question.get("id")) > Long.parseLong(row("SELECT id FROM outbox WHERE kind = 'PLAN_READY'").get("id")),
                "after the plan itself");
    }

    @Test
    void anOptionTapAnswersEditsTheQuestionAndSendsTheNext() throws Exception {
        long taskId = taskWithTwoQuestions();
        long question1 = deliveredQuestion(1);

        handler.handle(PlanQuestionsTest.tap(600, BOLD, question1, "q:" + taskId + ":1:1:1"));

        assertEquals(renderer.text("callback.answered"), answerText());
        Map<String, String> answer = row("SELECT * FROM plan_answer WHERE task_id = ?", taskId);
        assertEquals("1", answer.get("plan_seq"));
        assertEquals("1", answer.get("question_index"));
        assertEquals("prod", answer.get("answer"));
        assertEquals("telegram:100", answer.get("answered_by"));
        assertEquals("telegram:100/" + question1, answer.get("message_ref"));
        Map<String, String> edit = row("SELECT * FROM outbox WHERE edit_ref IS NOT NULL");
        assertEquals("PLAN_QUESTION", edit.get("kind"));
        assertEquals("telegram:100/" + question1, edit.get("edit_ref"));
        assertEquals("prod", Json.read(edit.get("payload")).get("answer").asText());
        Map<String, String> next = row("SELECT * FROM outbox WHERE kind = 'PLAN_QUESTION' AND edit_ref IS NULL AND status = 'PENDING'");
        assertEquals(2, Json.read(next.get("payload")).get("index").asInt());
        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
    }

    @Test
    void theAnsweredQuestionIsRedrawnWithTheAnswerAndNoButtons() throws Exception {
        long taskId = taskWithTwoQuestions();
        long question1 = deliveredQuestion(1);
        handler.handle(tap(601, BOLD, question1, "q:" + taskId + ":1:1:0"));
        telegram.drain("answerCallbackQuery");

        deliverAll();

        JsonNode edit = telegram.awaitRequest("editMessageText", Duration.ofSeconds(2)).json();
        assertEquals(question1, edit.get("message_id").asLong());
        assertEquals("✅ <b>#" + taskId + "</b> · асуулт 1/2\nWhich environments?\n→ <i>staging</i>", edit.get("text").asText());
        assertEquals(0, edit.path("reply_markup").path("inline_keyboard").size());
    }

    @Test
    void theLastAnswerQueuesOneCorrectionWithAllAnswers() throws Exception {
        long taskId = taskWithTwoQuestions();
        handler.handle(tap(602, BOLD, deliveredQuestion(1), "q:" + taskId + ":1:1:0"));
        handler.handle(tap(603, BOLD, deliveredQuestion(2), "q:" + taskId + ":1:2:d"));

        assertEquals("PLANNING", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        assertEquals("2", count("SELECT count(*) AS n FROM run WHERE task_id = " + taskId));
        Map<String, String> run = row("SELECT * FROM run WHERE task_id = ? AND seq = 2", taskId);
        assertEquals("PLAN", run.get("kind"));
        assertEquals("CORRECTION", run.get("cause"));
        assertEquals("Bold", run.get("requested_by_name"));
        assertEquals("Асуултын хариулт:\n1. Which environments? → staging\n2. Keep the old default? → " + YOU_DECIDE,
                run.get("instruction"));
        assertEquals("1", count("SELECT count(*) AS n FROM outbox WHERE kind = 'CORRECTION_QUEUED'"));
        assertEquals("telegram:100", row("SELECT chat_ref FROM outbox WHERE kind = 'CORRECTION_QUEUED'").get("chat_ref"));
    }

    @Test
    void writeYourOwnSendsAForceReplyPromptAndTheReplyToItIsTheAnswer() throws Exception {
        long taskId = taskWithTwoQuestions();
        long question1 = deliveredQuestion(1);

        handler.handle(tap(604, BOLD, question1, "q:" + taskId + ":1:1:w"));
        telegram.drain("sendMessage");
        deliverAll();

        JsonNode prompt = telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json();
        assertEquals("✍️ <b>#" + taskId + "</b> · асуулт 1: хариултаа бичнэ үү.", prompt.get("text").asText());
        assertTrue(prompt.path("reply_markup").path("force_reply").asBoolean(), prompt.toString());
        assertEquals("Хариулт", prompt.path("reply_markup").path("input_field_placeholder").asText());
        assertEquals("0", count("SELECT count(*) AS n FROM plan_answer"), "nothing answered yet");
        long promptId = messageId(row("SELECT sent_ref FROM outbox WHERE kind = 'PLAN_ANSWER_PROMPT'").get("sent_ref"));

        handler.handle(UpdateHandlerTest.message(605, 70, BOLD, "Bold", BOLD, "private", "Only staging, prod next week", botReply(promptId)));

        Map<String, String> answer = row("SELECT * FROM plan_answer WHERE task_id = ?", taskId);
        assertEquals("Only staging, prod next week", answer.get("answer"));
        assertEquals("telegram:100/" + question1, answer.get("message_ref"), "the question, not the prompt, is redrawn");
        assertEquals("telegram:100/" + question1, row("SELECT edit_ref FROM outbox WHERE edit_ref IS NOT NULL").get("edit_ref"));
    }

    @Test
    void aSecondWriteTapWhileThePromptIsOpenPointsAtIt() throws Exception {
        long taskId = taskWithTwoQuestions();
        long question1 = deliveredQuestion(1);

        handler.handle(tap(626, BOLD, question1, "q:" + taskId + ":1:1:w"));
        telegram.drain("answerCallbackQuery");
        deliverAll();
        handler.handle(tap(627, BOLD, question1, "q:" + taskId + ":1:1:w"));

        assertEquals("Хариултаа доорх мессежид бичнэ үү.", answerText());
        assertEquals("1", count("SELECT count(*) AS n FROM outbox WHERE kind = 'PLAN_ANSWER_PROMPT'"));
    }

    @Test
    void aReplyToTheQuestionMessageItselfIsTheAnswer() {
        long taskId = taskWithTwoQuestions();
        long question1 = deliveredQuestion(1);

        handler.handle(UpdateHandlerTest.message(606, 71, BOLD, "Bold", BOLD, "private", "Both", botReply(question1)));

        assertEquals("Both", row("SELECT answer FROM plan_answer WHERE task_id = ?", taskId).get("answer"));
        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"), "not a correction");
    }

    @Test
    void youDecideRecordsTheAssumptionAnswer() throws Exception {
        long taskId = taskWithTwoQuestions();

        handler.handle(tap(607, BOLD, deliveredQuestion(1), "q:" + taskId + ":1:1:d"));

        assertEquals(YOU_DECIDE, row("SELECT answer FROM plan_answer WHERE task_id = ?", taskId).get("answer"));
    }

    @Test
    void anotherMembersTapIsRefusedAndChangesNothing() throws Exception {
        long taskId = taskWithTwoQuestions();

        handler.handle(UpdateHandlerTest.callback(608, 200, "Ali", GROUP, deliveredQuestion(1), "q:" + taskId + ":1:1:0"));

        assertEquals(renderer.text("callback.notRequester"), answerText());
        assertEquals("0", count("SELECT count(*) AS n FROM plan_answer"));
    }

    @Test
    void aTapOnAnOlderPlanOrAnAnsweredQuestionIsStale() throws Exception {
        long taskId = taskWithTwoQuestions();
        long question1 = deliveredQuestion(1);

        handler.handle(tap(609, BOLD, question1, "q:" + taskId + ":9:1:0"));
        assertEquals(renderer.text("callback.stale"), answerText());
        assertEquals("0", count("SELECT count(*) AS n FROM plan_answer"));

        handler.handle(tap(610, BOLD, question1, "q:" + taskId + ":1:1:0"));
        telegram.drain("answerCallbackQuery");
        handler.handle(tap(611, BOLD, question1, "q:" + taskId + ":1:1:1"));

        assertEquals("Энэ асуултад аль хэдийн хариулсан.", answerText(), "answered, while the plan is still current");
        assertEquals("staging", row("SELECT answer FROM plan_answer WHERE task_id = ?", taskId).get("answer"));
        assertEquals("2", count("SELECT count(*) AS n FROM outbox WHERE kind = 'PLAN_QUESTION' AND edit_ref IS NULL"),
                "the second question was sent once");
    }

    @Test
    void aReplyToAnAnsweredQuestionSaysSoAndOneToAnOutdatedPlanSaysThat() throws Exception {
        long taskId = taskWithTwoQuestions();
        long question1 = deliveredQuestion(1);
        handler.handle(tap(620, BOLD, question1, "q:" + taskId + ":1:1:0"));
        long question2 = deliveredQuestion(2);
        telegram.drain("sendMessage");

        handler.handle(UpdateHandlerTest.message(621, 73, BOLD, "Bold", BOLD, "private", "prod after all", botReply(question1)));
        deliverAll();

        assertEquals("Энэ асуултад аль хэдийн хариулсан.", lastSentText());
        assertEquals("staging", row("SELECT answer FROM plan_answer WHERE task_id = ?", taskId).get("answer"));

        long planMessage = messageId(row("SELECT sent_ref FROM outbox WHERE kind = 'PLAN_READY'").get("sent_ref"));
        handler.handle(UpdateHandlerTest.message(622, 74, BOLD, "Bold", BOLD, "private", "Only staging", botReply(planMessage)));
        handler.handle(UpdateHandlerTest.message(623, 75, BOLD, "Bold", BOLD, "private", "yes", botReply(question2)));
        deliverAll();

        assertEquals("#" + taskId + ": энэ төлөвлөгөө хуучирсан байна. Хамгийн сүүлийн төлөвлөгөөнд хариу бичнэ үү.", lastSentText());
    }

    @Test
    void aStickerReplyToAQuestionIsAskedForText() throws Exception {
        long taskId = taskWithTwoQuestions();
        long question1 = deliveredQuestion(1);
        telegram.drain("sendMessage");
        JsonNode sticker = UpdateHandlerTest.message(624, 76, BOLD, "Bold", BOLD, "private", "", botReply(question1));
        com.fasterxml.jackson.databind.node.ObjectNode body = (com.fasterxml.jackson.databind.node.ObjectNode) sticker.get("message");
        body.remove("text");
        body.putObject("sticker").put("file_id", "s1");

        handler.handle(sticker);
        deliverAll();

        assertEquals("Хариултаа текстээр бичнэ үү.", lastSentText());
        assertEquals("0", count("SELECT count(*) AS n FROM plan_answer WHERE task_id = " + taskId));
    }

    @Test
    void aReplyFromSomeoneTheTasksNoLongerAllowIsRefusedInWords() throws Exception {
        taskWithTwoQuestions();
        long question1 = deliveredQuestion(1);
        telegram.drain("sendMessage");
        // The handler still sees Bold as a member, but the task service's groups no longer have him, e.g. mid config change.
        Groups withoutBold = new Groups(List.of(new Config.Group("backend", GROUP, List.of(new Config.Member(200, "Ali")),
                List.of("autoland-management"))));
        Projects projects = new Projects(List.of(), project -> Optional.empty());
        UpdateHandler refusing = new UpdateHandler(db, new TaskService(withoutBold, projects, new ActiveRuns(), clock, () -> { }, () -> { }),
                new Membership(withoutBold, (group, member) -> null, clock, () -> { }),
                new Groups(List.of(new Config.Group("backend", GROUP, List.of(new Config.Member(100, "Bold")), List.of("autoland-management")))),
                projects, new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5)), renderer,
                dispatch.Redactor.patternsOnly(), FakeTelegram.BOT_USERNAME, clock, () -> { });

        refusing.handle(UpdateHandlerTest.message(625, 77, BOLD, "Bold", BOLD, "private", "prod", botReply(question1)));
        deliverAll();

        assertEquals(renderer.text("callback.notAllowed"), lastSentText());
        assertEquals("0", count("SELECT count(*) AS n FROM plan_answer"));
    }

    @Test
    void aTypedReplyToThePlanStaysAFreeCorrectionAndMakesTheRemainingQuestionsStale() throws Exception {
        long taskId = taskWithTwoQuestions();
        handler.handle(tap(612, BOLD, deliveredQuestion(1), "q:" + taskId + ":1:1:0"));
        long question2 = deliveredQuestion(2);
        long planMessage = messageId(row("SELECT sent_ref FROM outbox WHERE kind = 'PLAN_READY'").get("sent_ref"));
        telegram.drain("answerCallbackQuery");

        handler.handle(UpdateHandlerTest.message(613, 72, BOLD, "Bold", BOLD, "private", "Forget it, only fix staging", botReply(planMessage)));
        handler.handle(tap(614, BOLD, question2, "q:" + taskId + ":1:2:0"));

        assertEquals("Forget it, only fix staging", row("SELECT instruction FROM run WHERE task_id = ? AND seq = 2", taskId).get("instruction"));
        assertEquals(renderer.text("callback.stale"), answerText());
        assertEquals("1", count("SELECT count(*) AS n FROM plan_answer"));
        assertEquals("2", count("SELECT count(*) AS n FROM run WHERE task_id = " + taskId), "no second correction");
    }

    @Test
    void theQuestionMessageHasOneButtonPerOptionThenWriteAndDecide() throws Exception {
        long taskId = taskWithTwoQuestions();
        deliveredQuestion(1);

        List<FakeTelegram.Request> sent = telegram.drain("sendMessage");
        JsonNode question = sent.getLast().json();
        assertEquals("❓ <b>#" + taskId + "</b> · асуулт 1/2\nWhich environments?", question.get("text").asText());
        JsonNode rows = question.path("reply_markup").path("inline_keyboard");
        assertEquals("staging", rows.get(0).get(0).get("text").asText());
        assertEquals("q:" + taskId + ":1:1:0", rows.get(0).get(0).get("callback_data").asText());
        assertEquals("q:" + taskId + ":1:1:1", rows.get(1).get(0).get("callback_data").asText());
        assertEquals("✍️ Өөрөөр хариулах", rows.get(2).get(0).get("text").asText());
        assertEquals("q:" + taskId + ":1:1:w", rows.get(2).get(0).get("callback_data").asText());
        assertEquals("🤷 Та шийд", rows.get(2).get(1).get("text").asText());
        assertEquals("q:" + taskId + ":1:1:d", rows.get(2).get(1).get("callback_data").asText());
        assertFalse(question.has("reply_markup") && question.path("reply_markup").has("force_reply"));
    }

    /** A task for Bold whose first plan asks two questions, the first with two options. */
    private long taskWithTwoQuestions() {
        String origin = "telegram:100/" + System.nanoTime();
        db.transaction(tx -> tasks.create(tx, new Requester("telegram:100", "Bold"), "alm", "Fix the login timeout", Priority.NORMAL,
                origin));
        ClaimedRun run = db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        Plan plan = new Plan("Make the timeout configurable", List.of(), List.of("Read auth.timeout"), List.of(),
                List.of(new PlanQuestion("Which environments?", List.of("staging", "prod")),
                        new PlanQuestion("Keep the old default?", List.of("yes", "no"))));
        transitions.planSucceeded(run.taskId(), run.seq(), plan,
                new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", plan.toJson(), null, new BigDecimal("0.1"), 3, List.of(), null, null, null));
        return run.taskId();
    }

    /** Delivers everything due and returns the message id Telegram gave question {@code index}. */
    private long deliveredQuestion(int index) {
        deliverAll();
        return db.transactionReturning(tx -> tx.list("SELECT sent_ref, payload FROM outbox WHERE kind = 'PLAN_QUESTION' AND sent_ref IS NOT NULL",
                        r -> new String[] {r.string("sent_ref"), r.string("payload")}))
                .stream().filter(sent -> Json.read(sent[1]).get("index").asInt() == index)
                .map(sent -> messageId(sent[0])).findFirst().orElseThrow();
    }

    private void deliverAll() {
        while (sender.deliverDue()) {
            // until nothing is due
        }
    }

    private String answerText() throws InterruptedException {
        return telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText();
    }

    private String lastSentText() {
        return telegram.drain("sendMessage").getLast().json().get("text").asText();
    }

    private static long messageId(String ref) {
        return Refs.messageId(ref);
    }

    /** A button pressed in Bold's private chat under the bot's message {@code messageId}. */
    static JsonNode tap(long updateId, long fromId, long messageId, String data) {
        return UpdateHandlerTest.callback(updateId, fromId, "Bold", fromId, messageId, data);
    }

    private static String botReply(long messageId) {
        return """
                {"message_id":%d,"from":{"id":1,"is_bot":true,"first_name":"Dispatch"},"chat":{"id":100,"type":"private"},
                 "date":1789640000,"text":"question"}""".formatted(messageId);
    }

    private String count(String sql) {
        return row(sql).get("n");
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
