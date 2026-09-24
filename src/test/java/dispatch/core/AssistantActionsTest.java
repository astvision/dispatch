package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
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

        assertEquals(AssistantActions.Outcome.DONE, run(ALI, id));
        Map<String, String> draft = SqlRows.single(dbFile, "SELECT project, description, origin_ref FROM draft");
        assertEquals("life", draft.get("project"));
        assertEquals("Дасгалын тэмдэглэл нэм\nӨдөр бүр", draft.get("description"));
        assertEquals(REPLY + "#a" + id, draft.get("origin_ref"));
        assertEquals("DRAFT_PROMPT", SqlRows.single(dbFile, "SELECT kind FROM outbox").get("kind"));
        assertEquals(AssistantActions.Outcome.USED, run(ALI, id), "a second tap does nothing");
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
        assertEquals(AssistantActions.Outcome.DONE, run(ALI, propose(ALI, checked)));
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

        assertEquals(AssistantActions.Outcome.STALE, run(ALI, id));
        assertEquals("REJECTED", phase(taskId));
    }

    @Test
    void anApprovalTappedInTimeQueuesTheExecution() {
        long taskId = planned(ALI, noQuestions());

        assertEquals(AssistantActions.Outcome.DONE, run(ALI, propose(ALI, check(ALI, Json.object().put("type", "approve").put("task", taskId)))));
        assertEquals("EXECUTING", phase(taskId));
    }

    @Test
    void onlyTheMembersOwnVisibleTasksCanBeActedOn() {
        long bolds = create(BOLD, "life", "Fix the login");
        long crm = create(BOLD, "crm", "Export");

        assertEquals("notYours", reason(check(ALI, Json.object().put("type", "cancel").put("task", bolds))));
        assertEquals("notFound", reason(check(ALI, Json.object().put("type", "cancel").put("task", crm))),
                "a task outside the member's projects does not leak");
        assertEquals("notFound", reason(check(ALI, Json.object().put("type", "cancel").put("task", 999))));
        assertEquals("phase", reason(check(BOLD, Json.object().put("type", "retry").put("task", bolds))), "it has not failed");
        assertEquals("phase", reason(check(BOLD, Json.object().put("type", "followUp").put("task", bolds).put("text", "also X"))));
        assertTrue(check(BOLD, Json.object().put("type", "cancel").put("task", bolds)).valid());
    }

    @Test
    void someoneElsesButtonDoesNothing() {
        long id = propose(ALI, check(ALI, Json.object().put("type", "draft").put("text", "Export")));

        assertEquals(AssistantActions.Outcome.USED, run(BOLD, id));
        assertEquals("0", SqlRows.single(dbFile, "SELECT count(*) AS n FROM draft").get("n"));
    }

    private AssistantActions.Checked check(Requester who, JsonNode action) {
        return db.transactionReturning(tx -> actions.check(tx, who, action));
    }

    private long propose(Requester who, AssistantActions.Checked checked) {
        assertTrue(checked.valid(), checked.payload().toString());
        return db.transactionReturning(tx -> Conversations.proposeAction(tx, who.ref(), checked.payload(), clock.instant()));
    }

    private AssistantActions.Outcome run(Requester who, long id) {
        return db.transactionReturning(tx -> actions.run(tx, who, id, REPLY, who.ref()));
    }

    private static String reason(AssistantActions.Checked checked) {
        assertFalse(checked.valid(), checked.payload().toString());
        return checked.payload().path("reason").asText();
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

    private long create(Requester who, String project, String title) {
        String origin = who.ref() + "/" + Math.abs(title.hashCode());
        db.transaction(tx -> tasks.create(tx, who, project, title, Priority.NORMAL, origin));
        return Long.parseLong(SqlRows.single(dbFile, "SELECT id FROM task WHERE origin_ref = ?", origin).get("id"));
    }
}
