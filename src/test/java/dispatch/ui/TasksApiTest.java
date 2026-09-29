package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.Text;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.CommandResult;
import dispatch.core.Groups;
import dispatch.core.Origin;
import dispatch.core.Projects;
import dispatch.core.RunTransitions;
import dispatch.core.TaskCommand;
import dispatch.core.TaskService;
import dispatch.domain.ClaimedRun;
import dispatch.domain.Plan;
import dispatch.domain.PlanQuestion;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import dispatch.ui.UiServer.Caller;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a member and an admin see and may do on the Mini App's task pages (spec: Task pages, ADR 0020). */
class TasksApiTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");
    private static final Caller BOLD_CALLER = new Caller("telegram:100", "Bold", true);
    private static final Caller ALI_CALLER = new Caller("telegram:200", "Ali", false);
    private static final Caller STRANGER = new Caller("telegram:999", "Eve", false);

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TestClock clock;
    private TaskService tasks;
    private TasksApi api;
    private RunTransitions transitions;
    private Groups groups;
    /** Why alm cannot take tasks now; null while it can. */
    private final AtomicReference<String> unavailable = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-23T10:00:00Z"));
        Config.Project alm = new Config.Project("alm", null, "https://github.com/acme/alm.git", null, "main", "claude-code",
                null, null, List.of(), null, null, null);
        // Bold is the admin. TaskService decides what someone may do from this config, never from the Caller it is
        // handed, so the admin has to be an admin here for the cancel rules to hold.
        groups = new Groups(new Config.Telegram(List.of(100L), List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm")))));
        tasks = new TaskService(groups, new Projects(List.of(alm), project -> Optional.ofNullable(unavailable.get())), new ActiveRuns(),
                clock, () -> { }, () -> { });
        api = new TasksApi(db, tasks, groups);
        transitions = new RunTransitions(db, clock, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void myTasksHoldsOnlyMyOwnTasks() {
        long mine = create(BOLD, "Fix the login timeout");
        long theirs = create(ALI, "Add the export button");

        JsonNode listed = api.list(ALI_CALLER, Json.object());

        assertEquals(List.of(theirs), taskIds(listed), "a teammate's task is not on my page at all, not even as a headline");
        assertTrue(mine > 0);
    }

    /** The history's ten are counted among my own tasks, not among the group's before mine are picked out. */
    @Test
    void myFinishedTasksAreNotCrowdedOutByTeammatesNewerOnes() {
        long alisOld = create(ALI, "Add the export button");
        db.transaction(tx -> tasks.commands().run(tx, ALI, new TaskCommand.Cancel(alisOld)));
        for (int i = 0; i < 10; i++) {
            long boldsNew = create(BOLD, "Fix the login timeout " + i);
            db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Cancel(boldsNew)));
        }

        JsonNode listed = api.list(ALI_CALLER, Json.object());

        assertEquals(List.of(alisOld), taskIds(listed));
    }

    @Test
    void anAdminSeesEveryTaskOfTheirGroupsWithSomeoneElsesAsAHeadline() {
        long boldsOwn = create(BOLD, "Fix the login timeout");
        long alis = create(ALI, "Add the export button");

        JsonNode listed = api.list(BOLD_CALLER, Json.object().put("scope", "group"));

        assertEquals(List.of(boldsOwn, alis).stream().sorted().toList(), taskIds(listed).stream().sorted().toList(),
                "both tasks of the group");
        assertEquals("Ali", item(listed, alis).path("requester").asText());
        assertFalse(item(listed, alis).path("costUsd").isNumber(), "someone else's cost stays hidden (ADR 0020)");
    }

    @Test
    void aMemberWhoIsNoAdminMayNotAskForTheGroupsTasks() {
        create(ALI, "Add the export button");

        ApiException refused = assertThrows(ApiException.class, () -> api.list(ALI_CALLER, Json.object().put("scope", "group")));

        assertEquals(403, refused.status());
        assertEquals("not_admin", refused.code());
    }

    @Test
    void someoneElsesTimelineStopsAtTheHeadline() {
        long alis = create(ALI, "Add the export button");

        JsonNode own = api.timeline(ALI_CALLER, Json.object().put("taskId", alis));
        JsonNode asAdmin = api.timeline(BOLD_CALLER, Json.object().put("taskId", alis));

        assertFalse(own.path("headline").asBoolean(false), "my own task shows its runs");
        assertTrue(own.has("runs"));
        assertTrue(asAdmin.path("headline").asBoolean(false), "someone else's stops at the headline (ADR 0020)");
        assertFalse(asAdmin.has("runs"));
        assertTrue(asAdmin.path("costUsd").isNull());
    }

    @Test
    void aTaskInNoGroupOfTheCallerIsNotFoundRatherThanRefused() {
        long alis = create(ALI, "Add the export button");

        ApiException missing = assertThrows(ApiException.class,
                () -> api.timeline(STRANGER, Json.object().put("taskId", alis)));

        assertEquals(404, missing.status(), "its existence must not leak");
    }

    @Test
    void theRequesterCancelsTheirOwnTaskAndAnAdminCancelsAnybodys() {
        long alis = create(ALI, "Add the export button");
        long boldsOwn = create(BOLD, "Fix the login timeout");

        assertEquals("CANCELLED", api.cancel(ALI_CALLER, Json.object().put("taskId", alis)).path("result").asText());
        assertEquals("CANCELLED", api.cancel(BOLD_CALLER, Json.object().put("taskId", boldsOwn)).path("result").asText());
    }

    /**
     * An admin may stop anybody's task (ADR 0020). The group and the requester hear of it as news; the admin acted on a
     * page, and the page alone answers them (ADR 0031).
     */
    @Test
    void anAdminsCancelOfSomeoneElsesTaskIsNewsForTheGroupAndTheRequesterOnly() {
        long alis = create(ALI, "Add the export button");

        assertEquals("CANCELLED", api.cancel(BOLD_CALLER, Json.object().put("taskId", alis)).path("result").asText());

        assertEquals(List.of("telegram:-100", ALI.ref()), SqlRows.query(dbFile,
                "SELECT chat_ref FROM outbox WHERE kind = 'TASK_CANCELLED' ORDER BY id").stream().map(row -> row.get("chat_ref")).toList());
    }

    /**
     * The page that was tapped answers a refusal: the status its kind calls for, its code, and the command's own words,
     * which the page shows in its language (ADR 0031). None of it may also reach the member's private chat.
     */
    @Test
    void aRefusedCancelOrRetryIsAnsweredWithItsWordsAndNeverInTheChat() {
        long alis = create(ALI, "Add the export button");
        long boldsOwn = create(BOLD, "Fix the login timeout");
        long ended = create(ALI, "Rename the report");
        api.cancel(ALI_CALLER, Json.object().put("taskId", ended));
        String outboxBefore = SqlRows.single(dbFile, "SELECT count(*) AS n FROM outbox").get("n");

        List<ApiException> refusals = List.of(
                assertThrows(ApiException.class, () -> api.cancel(STRANGER, Json.object().put("taskId", alis))),
                assertThrows(ApiException.class, () -> api.cancel(ALI_CALLER, Json.object().put("taskId", 9999))),
                assertThrows(ApiException.class, () -> api.cancel(ALI_CALLER, Json.object().put("taskId", boldsOwn))),
                assertThrows(ApiException.class, () -> api.cancel(ALI_CALLER, Json.object().put("taskId", ended))),
                assertThrows(ApiException.class, () -> api.retry(BOLD_CALLER, Json.object().put("taskId", alis))),
                assertThrows(ApiException.class, () -> api.retry(ALI_CALLER, Json.object().put("taskId", alis))));
        String outboxAfter = SqlRows.single(dbFile, "SELECT count(*) AS n FROM outbox").get("n");

        assertAll(
                () -> assertEquals(List.of("403 not_a_member", "404 not_found", "403 not_yours", "409 wrong_state",
                        "403 not_yours", "409 wrong_state"), refusals.stream().map(refused -> refused.status() + " " + refused.code())
                        .toList()),
                () -> assertEquals(List.of(Text.of("refused.notMember"), Text.of("refused.notFound", 9999L),
                        Text.of("refused.cancelNotRequester", boldsOwn, "Bold"),
                        Text.of("refused.wrongPhase", ended, Text.of("phase.cancelled")), Text.of("refused.notRequester", alis, "Ali"),
                        Text.of("refused.notFailed", alis, Text.of("phase.planning"))), refusals.stream().map(ApiException::text).toList()),
                () -> assertEquals("no task #9999 here", refusals.get(1).getMessage(), "the log and the terminal read English"),
                () -> assertEquals(outboxBefore, outboxAfter, "a refusal the Mini App shows must not also be sent to the chat"));
    }

    /** A refusal as its status and code, e.g. "403 not_yours". */
    private static String refused(org.junit.jupiter.api.function.Executable call) {
        ApiException refused = assertThrows(ApiException.class, call);
        return refused.status() + " " + refused.code();
    }

    @Test
    void theRequesterReadsTheirPlanWithItsQuestionsAndNobodyElseDoes() {
        long taskId = planned(ALI, twoQuestions());

        JsonNode detail = api.detail(ALI_CALLER, Json.object().put("taskId", taskId));

        assertEquals("AWAITING_APPROVAL", detail.path("phase").asText());
        JsonNode plan = detail.path("plan");
        assertEquals(1, plan.path("planSeq").asInt());
        assertEquals("Make the timeout configurable", plan.path("understanding").asText());
        assertEquals("Read auth.timeout", plan.path("steps").get(0).asText());
        assertEquals("[\"staging\",\"prod\"]", plan.path("questions").get(0).path("options").toString());
        assertTrue(plan.path("questions").get(0).path("answer").isNull(), "not answered yet");
        JsonNode listed = item(api.list(ALI_CALLER, Json.object()), taskId);
        assertEquals(2, listed.path("openQuestions").asInt(), "the home screen says what the task waits on");
        assertEquals("Which environments?", listed.path("question").asText());
        assertFalse(item(api.list(BOLD_CALLER, Json.object().put("scope", "group")), taskId).has("question"),
                "someone else's task stays a headline (ADR 0020)");
        for (String route : List.of("detail", "answer", "approve", "reject")) {
            ApiException refused = assertThrows(ApiException.class, () -> call(route, BOLD_CALLER,
                    Json.object().put("taskId", taskId).put("planSeq", 1).put("index", 1).put("option", 0)));
            assertEquals(403, refused.status(), route + ": even an admin decides nobody else's plan");
            assertEquals("not_yours", refused.code(), route);
        }
        assertEquals(404, assertThrows(ApiException.class, () -> api.detail(STRANGER, Json.object().put("taskId", taskId))).status());
    }

    @Test
    void answeringTheLastQuestionQueuesTheAnswersAsOneCorrection() {
        long taskId = planned(ALI, twoQuestions());

        JsonNode first = api.answer(ALI_CALLER, Json.object().put("taskId", taskId).put("planSeq", 1).put("index", 1).put("option", 1));
        assertEquals("prod", first.path("plan").path("questions").get(0).path("answer").asText());
        assertEquals("AWAITING_APPROVAL", first.path("phase").asText(), "one question is still open");

        JsonNode last = api.answer(ALI_CALLER, Json.object().put("taskId", taskId).put("planSeq", 1).put("index", 2).put("decide", true));

        assertEquals("PLANNING", last.path("phase").asText(), "the agent plans again with the answers");
        String correction = SqlRows.single(dbFile, "SELECT instruction FROM run WHERE task_id = ? AND cause = 'CORRECTION'", taskId)
                .get("instruction");
        assertTrue(correction.contains("Which environments? → prod"), correction);
        assertTrue(correction.contains("Keep the old default? → Та хамгийн боломжит"), "the chat's own 'you decide' words: " + correction);
    }

    /** The Mini App can answer before the outbox has sent the question: it is then sent once, and redrawn (ADR 0031). */
    @Test
    void aWrittenAnswerBeforeTheQuestionIsSentSendsNoSecondQuestion() {
        long taskId = planned(ALI, new Plan("Make the timeout configurable", List.of(), List.of("Read auth.timeout"), List.of(),
                List.of(new PlanQuestion("Which environments?", List.of("staging", "prod")))));
        String question = SqlRows.single(dbFile, "SELECT id FROM outbox WHERE kind = 'PLAN_QUESTION' AND status = 'PENDING'").get("id");

        api.answer(ALI_CALLER, Json.object().put("taskId", taskId).put("planSeq", 1).put("index", 1).put("text", "Only staging"));

        assertEquals(List.of(question), SqlRows.query(dbFile, "SELECT id FROM outbox WHERE kind = 'PLAN_QUESTION' AND edit_of IS NULL")
                .stream().map(row -> row.get("id")).toList(), "the question goes out once");
        assertEquals(List.of(question),
                SqlRows.query(dbFile, "SELECT edit_of FROM outbox WHERE kind = 'PLAN_QUESTION' AND edit_of IS NOT NULL").stream()
                        .map(row -> row.get("edit_of")).toList(), "and is redrawn with its answer once it is");
    }

    /** A refused answer is logged as any command is (ADR 0031): the page's refusal must not take the log line with it. */
    @Test
    void aRefusedAnswerIsLoggedLikeAnyCommand() {
        long taskId = planned(ALI, twoQuestions());
        PrintStream out = System.out;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        ApiException refused;
        try {
            System.setOut(new PrintStream(logged, true, StandardCharsets.UTF_8));
            refused = assertThrows(ApiException.class, () -> api.answer(ALI_CALLER,
                    Json.object().put("taskId", taskId).put("planSeq", 1).put("index", 2).put("text", "yes")));
        } finally {
            System.setOut(out);
        }

        assertEquals("409 out_of_order", refused.status() + " " + refused.code());
        String lines = logged.toString(StandardCharsets.UTF_8);
        assertTrue(lines.contains("event=task.command command=Answer task=" + taskId
                + " actor=telegram:200 result=\"REFUSED OUT_OF_ORDER\""), lines);
    }

    @Test
    void questionsAreAnsweredInOrderAndAnOlderPlanIsStale() {
        long taskId = planned(ALI, twoQuestions());

        ApiException outOfOrder = assertThrows(ApiException.class, () -> api.answer(ALI_CALLER,
                Json.object().put("taskId", taskId).put("planSeq", 1).put("index", 2).put("text", "yes")));
        ApiException stale = assertThrows(ApiException.class, () -> api.answer(ALI_CALLER,
                Json.object().put("taskId", taskId).put("planSeq", 7).put("index", 1).put("text", "prod")));
        ApiException staleApproval = assertThrows(ApiException.class, () -> api.approve(ALI_CALLER,
                Json.object().put("taskId", taskId).put("planSeq", 7)));

        assertEquals(409, outOfOrder.status());
        assertEquals("out_of_order", outOfOrder.code());
        assertEquals("stale", stale.code());
        assertEquals("stale", staleApproval.code());
        assertEquals(Text.of("refused.stalePlan", taskId), staleApproval.text(), "the words the chat shows too");
    }

    @Test
    void aPlanWithOpenQuestionsCannotBeApproved() {
        long taskId = planned(ALI, twoQuestions());
        String outboxBefore = SqlRows.single(dbFile, "SELECT count(*) AS n FROM outbox").get("n");

        ApiException refused = assertThrows(ApiException.class, () -> api.approve(ALI_CALLER,
                Json.object().put("taskId", taskId).put("planSeq", 1)));

        assertEquals(409, refused.status());
        assertEquals("open_questions", refused.code());
        assertEquals(Text.of("refused.openQuestions", taskId), refused.text());
        assertEquals("AWAITING_APPROVAL", phase(taskId));
        assertEquals(outboxBefore, SqlRows.single(dbFile, "SELECT count(*) AS n FROM outbox").get("n"), "the page alone answers it");
    }

    @Test
    void theRequesterApprovesOrRejectsAPlanWithoutQuestions() {
        long approved = planned(ALI, noQuestions());
        long rejected = planned(ALI, noQuestions());

        assertEquals("APPROVED", api.approve(ALI_CALLER, Json.object().put("taskId", approved).put("planSeq", 1)).path("result").asText());
        assertEquals("REJECTED", api.reject(ALI_CALLER, Json.object().put("taskId", rejected).put("planSeq", 1)).path("result").asText());

        assertEquals("EXECUTING", phase(approved));
        assertEquals("REJECTED", phase(rejected));
        assertEquals("wrong_state", assertThrows(ApiException.class, () -> api.approve(ALI_CALLER,
                Json.object().put("taskId", approved).put("planSeq", 1))).code(), "a second tap changes nothing");
    }

    @Test
    void theGroupViewOffersAnAdminOnlyTheCancelOfSomeoneElsesTask() {
        long alis = create(ALI, "Add the export button");
        long boldsOwn = create(BOLD, "Fix the login timeout");

        JsonNode listed = api.list(BOLD_CALLER, Json.object().put("scope", "group"));

        assertEquals("[\"cancel\"]", item(listed, alis).path("actions").toString(), "an admin stops it, never decides it");
        assertEquals("[\"priority\",\"cancel\"]", item(listed, boldsOwn).path("actions").toString());
    }

    @Test
    void theDetailSaysWhatTheRequesterMayDoAndWhichQuestionIsAsked() {
        long taskId = planned(ALI, twoQuestions());

        JsonNode detail = api.detail(ALI_CALLER, Json.object().put("taskId", taskId));
        JsonNode answered = api.answer(ALI_CALLER,
                Json.object().put("taskId", taskId).put("planSeq", 1).put("index", 1).put("option", 0));

        assertEquals("[\"correct\",\"answer\",\"reject\",\"priority\",\"cancel\"]", detail.path("actions").toString());
        assertEquals(1, detail.path("plan").path("current").asInt());
        assertEquals(2, answered.path("plan").path("current").asInt(), "the next question is the one to answer");
    }

    /** The branch shown is the one the worker makes: a named instance's own prefix, not "dispatch" (M). */
    @Test
    void aTimelineShowsTheBranchWithTheInstancesOwnPrefix() {
        TaskService team = new TaskService(groups, new Projects(List.of(), project -> Optional.empty()), new ActiveRuns(), clock,
                () -> { }, () -> { }, false, draftId -> { }, false, "dispatch/team");
        long mine = create(BOLD, "Fix the login timeout");

        JsonNode timeline = new TasksApi(db, team, groups).timeline(BOLD_CALLER, Json.object().put("taskId", mine));

        assertEquals("dispatch/team/" + mine, timeline.path("branch").asText());
    }

    @Test
    void theDesksOwnerSeesEveryTaskInFullAndTellsTheirOwnApart() {
        long theirs = planned(ALI, noQuestions());
        long mine = create(BOLD, "Fix the login timeout");
        TasksApi desk = new TasksApi(db, tasks, groups, true);

        JsonNode listed = desk.list(BOLD_CALLER, Json.object().put("scope", "group"));
        JsonNode timeline = desk.timeline(BOLD_CALLER, Json.object().put("taskId", theirs));
        JsonNode detail = desk.detail(BOLD_CALLER, Json.object().put("taskId", theirs));

        assertFalse(item(listed, theirs).path("mine").asBoolean(), "Ali's task is not Bold's: " + listed);
        assertTrue(item(listed, mine).path("mine").asBoolean());
        assertFalse(timeline.path("headline").asBoolean(false), "the owner reads a member's task in full: " + timeline);
        assertEquals("0.1", timeline.path("costUsd").asText());
        assertEquals("dispatch/" + theirs, timeline.path("branch").asText());
        assertEquals("[\"cancel\"]", detail.path("actions").toString(), "seen in full, still decided by Ali alone");
        assertEquals(0, detail.path("plan").path("current").asInt());
    }

    /** ADR 0020, 0027: an admin cancels any task, and the desk offers it to an admin who is in no group too. */
    @Test
    void theDeskOffersAnAdminInNoGroupTheCancelOfEveryTask() {
        groups.replace(new Config.Telegram(List.of(100L, 400L), List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm")))));
        long alis = create(ALI, "Add the export button");
        Caller admin = new Caller("telegram:400", "Admin", true);
        TasksApi desk = new TasksApi(db, tasks, groups, true);

        JsonNode listed = desk.list(admin, Json.object().put("scope", "group"));
        JsonNode detail = desk.detail(admin, Json.object().put("taskId", alis));
        desk.cancel(admin, Json.object().put("taskId", alis));

        assertEquals("[\"cancel\"]", item(listed, alis).path("actions").toString(), listed.toString());
        assertEquals("[\"cancel\"]", detail.path("actions").toString());
        assertEquals(dispatch.domain.Phase.CANCELLED, db.transactionReturning(tx -> Tasks.find(tx, alis)).orElseThrow().phase());
    }

    @Test
    void aMemberStillSeesATeammatesTaskAsItsHeadline() {
        long theirs = planned(ALI, noQuestions());

        JsonNode timeline = api.timeline(BOLD_CALLER, Json.object().put("taskId", theirs));
        JsonNode listed = api.list(BOLD_CALLER, Json.object().put("scope", "group"));

        assertTrue(timeline.path("headline").asBoolean(), "ADR 0020 holds for members: " + timeline);
        assertFalse(item(listed, theirs).path("mine").asBoolean());
    }

    @Test
    void aPlanIsCorrectedByTextOnlyByItsRequesterAndOnlyTheLatestOne() {
        long taskId = planned(ALI, noQuestions());
        TasksApi desk = new TasksApi(db, tasks, groups, true);

        ApiException notYours = assertThrows(ApiException.class, () -> desk.correct(BOLD_CALLER,
                Json.object().put("taskId", taskId).put("planSeq", 1).put("text", "use 60 s")));
        ApiException stale = assertThrows(ApiException.class, () -> desk.correct(ALI_CALLER,
                Json.object().put("taskId", taskId).put("planSeq", 7).put("text", "use 60 s")));
        ApiException empty = assertThrows(ApiException.class, () -> desk.correct(ALI_CALLER,
                Json.object().put("taskId", taskId).put("planSeq", 1).put("text", "  ")));
        JsonNode corrected = desk.correct(ALI_CALLER, Json.object().put("taskId", taskId).put("planSeq", 1).put("text", "use 60 s"));

        assertEquals("not_yours", notYours.code());
        assertEquals("stale", stale.code());
        assertEquals("invalid", empty.code());
        assertEquals("CORRECTED", corrected.path("result").asText());
        assertEquals("PLANNING", phase(taskId));
    }

    @Test
    void aFinishedTaskTakesAFollowUpFromItsRequesterOnly() {
        long waiting = planned(ALI, noQuestions());
        long finished = finished(ALI);
        String outboxBefore = SqlRows.single(dbFile, "SELECT count(*) AS n FROM outbox").get("n");

        List<String> answers = List.of(
                refused(() -> api.followUp(BOLD_CALLER, Json.object().put("taskId", finished).put("text", "add a test"))),
                refused(() -> api.followUp(ALI_CALLER, Json.object().put("taskId", waiting).put("text", "add a test"))),
                refused(() -> api.followUp(ALI_CALLER, Json.object().put("taskId", finished).put("text", "  "))));
        String outboxAfter = SqlRows.single(dbFile, "SELECT count(*) AS n FROM outbox").get("n");
        JsonNode followed = api.followUp(ALI_CALLER, Json.object().put("taskId", finished).put("text", "add a test"));

        assertEquals(List.of("403 not_yours", "409 wrong_state", "400 invalid"), answers);
        assertEquals(outboxBefore, outboxAfter, "a refusal the Mini App shows must not also be sent to the chat");
        assertEquals("QUEUED", followed.path("result").asText());
        assertEquals("EXECUTING", phase(finished));
    }

    /** A merged task's follow-up is a new task, refused exactly as giving one is (ADR 0031): on the page, never in the chat. */
    @Test
    void aMergedTasksFollowUpWhileItsProjectIsUnavailableIsRefusedOnThePage() {
        long merged = finished(ALI);
        db.transaction(tx -> Tasks.merged(tx, merged, clock.instant()));
        unavailable.set("repos/alm is being cloned");
        String outboxBefore = SqlRows.single(dbFile, "SELECT count(*) AS n FROM outbox").get("n");

        ApiException refused = assertThrows(ApiException.class,
                () -> api.followUp(ALI_CALLER, Json.object().put("taskId", merged).put("text", "add a test")));

        assertEquals("409 project_unavailable", refused.status() + " " + refused.code());
        assertEquals(Text.of("refused.projectUnavailable", "alm", "repos/alm is being cloned"), refused.text());
        assertEquals(outboxBefore, SqlRows.single(dbFile, "SELECT count(*) AS n FROM outbox").get("n"),
                "a refusal the Mini App shows must not also be sent to the chat");
    }

    private long finished(Requester who) {
        long taskId = planned(who, noQuestions());
        db.transaction(tx -> tasks.commands().run(tx, who, new TaskCommand.Approve(taskId, 1)));
        ClaimedRun run = db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        transitions.agentStarted(run.taskId(), run.seq(), null, null);
        transitions.completed(run.taskId(), run.seq(), new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", null, null,
                new BigDecimal("0.1"), 3, List.of(), null, null, null), List.of("a.txt"), "https://github.com/acme/alm/pull/1");
        return taskId;
    }

    private Object call(String route, Caller caller, com.fasterxml.jackson.databind.node.ObjectNode body) {
        return switch (route) {
            case "detail" -> api.detail(caller, body);
            case "answer" -> api.answer(caller, body);
            case "approve" -> api.approve(caller, body);
            default -> api.reject(caller, body);
        };
    }

    private long planned(Requester who, Plan plan) {
        create(who, "Fix the login timeout " + System.nanoTime());
        ClaimedRun run = db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        transitions.planSucceeded(run.taskId(), run.seq(), plan,
                new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", plan.toJson(), null, new BigDecimal("0.1"), 3, List.of(), null, null, null));
        return run.taskId();
    }

    private static Plan twoQuestions() {
        return new Plan("Make the timeout configurable", List.of(), List.of("Read auth.timeout"), List.of(),
                List.of(new PlanQuestion("Which environments?", List.of("staging", "prod")),
                        new PlanQuestion("Keep the old default?", List.of("yes", "no"))));
    }

    private static Plan noQuestions() {
        return new Plan("Make the timeout configurable", List.of(), List.of("Read auth.timeout"), List.of(), List.of());
    }

    private String phase(long taskId) {
        return SqlRows.single(dbFile, "SELECT phase FROM task WHERE id = ?", taskId).get("phase");
    }

    private long create(Requester who, String title) {
        Origin origin = new Origin(who.ref() + "/" + Math.abs(title.hashCode()));
        return assertInstanceOf(CommandResult.Created.class, db.transactionReturning(tx -> tasks.commands().run(tx, who,
                new TaskCommand.Give("alm", title, Priority.NORMAL, origin)))).taskId();
    }

    private static List<Long> taskIds(JsonNode listed) {
        return StreamSupport.stream(listed.path("tasks").spliterator(), false).map(task -> task.path("taskId").asLong()).toList();
    }

    private static JsonNode item(JsonNode listed, long taskId) {
        return StreamSupport.stream(listed.path("tasks").spliterator(), false)
                .filter(task -> task.path("taskId").asLong() == taskId).findFirst().orElseThrow();
    }

    @Test
    void theMiniAppGivesNoTaskAndCountsNoSpend() {
        Set<String> routes = new TasksApi(db, tasks, groups).routes().keySet();

        assertFalse(routes.contains("/api/tasks/new"));
        assertFalse(routes.contains("/api/tasks/spend"));
    }
}
