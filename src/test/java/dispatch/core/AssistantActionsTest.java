package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.Language;
import dispatch.Text;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.domain.ClaimedRun;
import dispatch.domain.Plan;
import dispatch.domain.PlanQuestion;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Conversations;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the assistant may propose, and that a tap runs it once, through the chat's own paths (A-1). */
class AssistantActionsTest {

    private static final Requester ALI = new Requester("telegram:200", "Ali");
    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final String REPLY = "telegram:200/77";

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TestClock clock;
    private TaskService tasks;
    private RunTransitions transitions;
    private AssistantActions actions;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-25T10:00:00Z"));
        Config.Project life = new Config.Project("life", "l", "https://github.com/acme/life.git", null, "main", "claude-code",
                null, null, List.of(), null, null, null);
        Config.Project crm = new Config.Project("crm", null, "https://github.com/acme/crm.git", null, "main", "claude-code",
                null, null, List.of(), null, null, null);
        Groups groups = new Groups(List.of(
                new Config.Group("home", -100L, List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("life")),
                new Config.Group("work", -300L, List.of(new Config.Member(100, "Bold")), List.of("crm"))));
        Projects projects = new Projects(List.of(life, crm), project -> Optional.empty());
        tasks = new TaskService(groups, projects, new ActiveRuns(), clock, () -> { }, () -> { });
        transitions = new RunTransitions(db, clock, () -> { });
        actions = new AssistantActions(tasks, groups, projects, clock, "Чи шийд");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aDraftNamesTheProjectByAliasAndATapOpensTheUsualPromptOnce() {
        AssistantActions.Checked checked = check(ALI, Json.object().put("type", "draft").put("project", "l")
                .put("text", "Дасгалын тэмдэглэл нэм\nӨдөр бүр"));

        assertTrue(checked.valid());
        assertEquals("life", checked.payload().path("project").asText());
        assertEquals("Дасгалын тэмдэглэл нэм", checked.payload().path("title").asText());
        long id = propose(ALI, checked);

        assertEquals(AssistantActions.Outcome.DONE, run(ALI, id).outcome());
        Map<String, String> draft = SqlRows.single(dbFile, "SELECT project, description, origin_ref FROM draft");
        assertEquals("life", draft.get("project"));
        assertEquals("Дасгалын тэмдэглэл нэм\nӨдөр бүр", draft.get("description"));
        assertEquals(REPLY + "#a" + id, draft.get("origin_ref"));
        assertEquals("DRAFT_PROMPT", SqlRows.single(dbFile, "SELECT kind FROM outbox").get("kind"));
        assertEquals(AssistantActions.Outcome.USED, run(ALI, id).outcome(), "a second tap does nothing");
        assertEquals("1", SqlRows.single(dbFile, "SELECT count(*) AS n FROM draft").get("n"));
    }

    @Test
    void aProjectTheMemberCannotUseIsLeftForThePromptToAsk() {
        AssistantActions.Checked checked = check(ALI, Json.object().put("type", "draft").put("project", "crm").put("text", "Export"));

        assertTrue(checked.valid());
        assertTrue(checked.payload().path("project").isNull());
    }

    @Test
    void anAnswerNamesTheOptionAndATapAnswersTheQuestion() {
        long taskId = planned(ALI, twoQuestions());

        AssistantActions.Checked checked = check(ALI, Json.object().put("type", "answer").put("task", taskId).put("question", 1)
                .put("option", 2));

        assertTrue(checked.valid(), checked.payload().toString());
        assertEquals("prod", checked.payload().path("answer").asText());
        assertEquals(AssistantActions.Outcome.DONE, run(ALI, propose(ALI, checked)).outcome());
        assertEquals("prod", SqlRows.single(dbFile, "SELECT answer FROM plan_answer WHERE task_id = ?", taskId).get("answer"));
    }

    @Test
    void onlyTheCurrentQuestionIsAnsweredAndNeverWithMoreTextThanTheReplyShows() {
        long taskId = planned(ALI, twoQuestions());

        assertEquals("order", reason(check(ALI, Json.object().put("type", "answer").put("task", taskId).put("question", 2).put("option", 1))),
                "the chat asks one question at a time");
        assertEquals("tooLong", reason(check(ALI, Json.object().put("type", "answer").put("task", taskId).put("question", 1)
                .put("text", "x".repeat(AssistantActions.SHOWN_TEXT + 1)))), "the member confirms only what they can read");
    }

    @Test
    void aTapThatFoundTheTaskMovedOnIsRecordedAsSuch() {
        long taskId = planned(ALI, noQuestions());
        long id = propose(ALI, check(ALI, Json.object().put("type", "approve").put("task", taskId)));
        db.transaction(tx -> tasks.reject(tx, ALI, taskId, 1));

        run(ALI, id);

        assertEquals("STALE", SqlRows.single(dbFile, "SELECT outcome FROM assistant_action WHERE id = ?", id).get("outcome"));
    }

    @Test
    void aPlanWithOpenQuestionsIsNotProposedForApproval() {
        long taskId = planned(ALI, twoQuestions());

        AssistantActions.Checked checked = check(ALI, Json.object().put("type", "approve").put("task", taskId));

        assertFalse(checked.valid());
        assertEquals("openQuestions", checked.payload().path("reason").asText(), "answering makes the agent plan again first");
    }

    @Test
    void anApprovalProposedBeforeThePlanWasRejectedIsStaleWhenTapped() {
        long taskId = planned(ALI, noQuestions());
        long id = propose(ALI, check(ALI, Json.object().put("type", "approve").put("task", taskId)));
        db.transaction(tx -> tasks.reject(tx, ALI, taskId, 1));

        assertEquals(AssistantActions.Outcome.STALE, run(ALI, id).outcome());
        assertEquals("REJECTED", phase(taskId));
    }

    @Test
    void anApprovalTappedInTimeQueuesTheExecution() {
        long taskId = planned(ALI, noQuestions());

        assertEquals(AssistantActions.Outcome.DONE,
                run(ALI, propose(ALI, check(ALI, Json.object().put("type", "approve").put("task", taskId)))).outcome());
        assertEquals("EXECUTING", phase(taskId));
    }

    @Test
    void onlyTheMembersOwnVisibleTasksCanBeActedOn() {
        long bolds = create(BOLD, "life", "Fix the login");
        long crm = create(BOLD, "crm", "Export");

        assertEquals(Text.of("refused.cancelNotRequester", bolds, "Bold").render(Language.MN),
                words(check(ALI, Json.object().put("type", "cancel").put("task", bolds))));
        assertEquals(Text.of("refused.notFound", crm).render(Language.MN),
                words(check(ALI, Json.object().put("type", "cancel").put("task", crm))),
                "a task outside the member's projects does not leak");
        assertEquals(Text.of("refused.notFound", 999L).render(Language.MN),
                words(check(ALI, Json.object().put("type", "cancel").put("task", 999))));
        assertEquals(Text.of("refused.notFailed", bolds, Text.of("phase.planning")).render(Language.MN),
                words(check(BOLD, Json.object().put("type", "retry").put("task", bolds))), "it has not failed");
        assertEquals("phase", reason(check(BOLD, Json.object().put("type", "followUp").put("task", bolds).put("text", "also X"))));
        assertTrue(check(BOLD, Json.object().put("type", "cancel").put("task", bolds)).valid());
    }

    /**
     * A cancel or retry tap runs the task command itself (ADR 0031). What it refuses comes back with the refusal's words,
     * which the channel shows as the button's notice, and writes nothing.
     */
    @Test
    void aCancelOrRetryTapRunsTheCommandAndARefusedOneCarriesItsWordsAndWritesNothing() {
        long alis = create(ALI, "life", "Fix the login");
        long bolds = create(BOLD, "life", "Fix the export");
        long cancel = propose(ALI, check(ALI, Json.object().put("type", "cancel").put("task", alis)));
        long cancelAgain = stored(ALI, "cancel", alis);
        long retry = stored(ALI, "retry", alis);
        long notYours = stored(ALI, "cancel", bolds);

        assertEquals(new AssistantActions.Tapped(AssistantActions.Outcome.DONE, Optional.empty()), run(ALI, cancel));
        assertEquals("CANCELLED", phase(alis));
        String outbox = SqlRows.single(dbFile, "SELECT count(*) AS n FROM outbox").get("n");

        assertEquals(new AssistantActions.Tapped(AssistantActions.Outcome.STALE,
                Optional.of(Text.of("refused.wrongPhase", alis, Text.of("phase.cancelled")))), run(ALI, cancelAgain));
        assertEquals(new AssistantActions.Tapped(AssistantActions.Outcome.STALE,
                Optional.of(Text.of("refused.notFailed", alis, Text.of("phase.cancelled")))), run(ALI, retry));
        assertEquals(new AssistantActions.Tapped(AssistantActions.Outcome.NOT_ALLOWED,
                Optional.of(Text.of("refused.cancelNotRequester", bolds, "Bold"))), run(ALI, notYours));
        assertEquals(outbox, SqlRows.single(dbFile, "SELECT count(*) AS n FROM outbox").get("n"), "a refused tap writes no row");
        assertEquals("PLANNING", phase(bolds), "someone else's task is untouched");
    }

    @Test
    void anAdminOfTheTasksGroupProposesCancellingATeammatesTaskWithItsTitle() {
        Requester sara = new Requester("telegram:300", "Sara");
        Config.Project life = new Config.Project("life", "l", "https://github.com/acme/life.git", null, "main", "claude-code",
                null, null, List.of(), null, null, null);
        Groups adminGroups = new Groups(new Config.Telegram(List.of(300L), List.of(
                new Config.Group("home", -100L, List.of(new Config.Member(100, "Bold"), new Config.Member(300, "Sara")),
                        List.of("life")))));
        Projects adminProjects = new Projects(List.of(life), project -> Optional.empty());
        TaskService adminTasks = new TaskService(adminGroups, adminProjects, new ActiveRuns(), clock, () -> { }, () -> { });
        AssistantActions adminActions = new AssistantActions(adminTasks, adminGroups, adminProjects, clock, "Чи шийд");
        long taskId = create(BOLD, "life", "Fix the login");

        AssistantActions.Checked checked = db.transactionReturning(tx ->
                adminActions.check(tx, sara, Json.object().put("type", "cancel").put("task", taskId)));

        assertTrue(checked.valid(), checked.payload().toString());
        assertEquals("Fix the login", checked.payload().path("title").asText());
    }

    @Test
    void followUpsOnMergedTasksProposedInOneReplyEachBecomeATaskOfTheirOwn() {
        long first = merged(BOLD);
        long second = merged(BOLD);
        long a = propose(BOLD, check(BOLD, Json.object().put("type", "followUp").put("task", first).put("text", "Also log it")));
        long b = propose(BOLD, check(BOLD, Json.object().put("type", "followUp").put("task", second).put("text", "Also cover mobile")));

        assertEquals(AssistantActions.Outcome.DONE, run(BOLD, a).outcome());
        assertEquals(AssistantActions.Outcome.DONE, run(BOLD, b).outcome());

        assertEquals("[Also log it, Also cover mobile]", SqlRows.query(dbFile,
                        "SELECT title FROM task WHERE id NOT IN (?, ?) ORDER BY id", first, second).stream().map(row -> row.get("title")).toList()
                .toString(), "both taps made their task, though both name the same reply");
    }

    @Test
    void someoneElsesButtonDoesNothing() {
        long id = propose(ALI, check(ALI, Json.object().put("type", "draft").put("text", "Export")));

        assertEquals(AssistantActions.Outcome.USED, run(BOLD, id).outcome());
        assertEquals("0", SqlRows.single(dbFile, "SELECT count(*) AS n FROM draft").get("n"));
    }

    private AssistantActions.Checked check(Requester who, JsonNode action) {
        return db.transactionReturning(tx -> actions.check(tx, who, action));
    }

    private long propose(Requester who, AssistantActions.Checked checked) {
        assertTrue(checked.valid(), checked.payload().toString());
        return db.transactionReturning(tx -> Conversations.proposeAction(tx, who.ref(), checked.payload(), clock.instant()));
    }

    private AssistantActions.Tapped run(Requester who, long id) {
        return db.transactionReturning(tx -> actions.run(tx, who, id, REPLY, who.ref()));
    }

    /** A proposal stored as it stands, as one checked before its task moved on would be, whatever a check would say now. */
    private long stored(Requester who, String type, long taskId) {
        return db.transactionReturning(tx ->
                Conversations.proposeAction(tx, who.ref(), Json.object().put("type", type).put("taskId", taskId), clock.instant()));
    }

    private static String reason(AssistantActions.Checked checked) {
        assertFalse(checked.valid(), checked.payload().toString());
        return checked.payload().path("reason").asText();
    }

    /** A proposal a task command refuses: its note is the refusal's own words, as every channel shows them (ADR 0031). */
    private static String words(AssistantActions.Checked checked) {
        assertFalse(checked.valid(), checked.payload().toString());
        return checked.payload().path("words").asText();
    }

    private long planned(Requester who, Plan plan) {
        create(who, "life", "Fix the login timeout " + System.nanoTime());
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

    /** A task of {@code who}'s, planned, carried out, delivered and merged from its result's button. */
    private long merged(Requester who) {
        long id = planned(who, noQuestions());
        db.transaction(tx -> tasks.approve(tx, who, id, 1));
        db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        transitions.agentStarted(id, 2, null, null);
        transitions.completed(id, 2, new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", null, "Done", new BigDecimal("0.1"), 3, List.of(), null,
                null, null), List.of("Makefile"), "https://github.com/acme/life/pull/" + id);
        db.transaction(tx -> dispatch.store.Tasks.merged(tx, id, clock.instant()));
        return id;
    }

    private long create(Requester who, String project, String title) {
        String origin = who.ref() + "/" + Math.abs(title.hashCode());
        db.transaction(tx -> tasks.create(tx, who, project, title, Priority.NORMAL, origin));
        return Long.parseLong(SqlRows.single(dbFile, "SELECT id FROM task WHERE origin_ref = ?", origin).get("id"));
    }
}
