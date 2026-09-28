package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.core.TaskAccess.Action;
import dispatch.core.Refusal;
import dispatch.core.TaskAccess.Sight;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.Plan;
import dispatch.domain.PlanQuestion;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.Task;
import dispatch.store.Database;
import dispatch.store.PlanAnswers;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Who may see and do what with a task (ADR 0027), as one table on a real SQLite file. The tests of the commands, the
 * channels and the pages prove only that they ask task access and relay its answer.
 */
class TaskAccessTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");
    /** An admin, and a member of the other group. */
    private static final Requester SARA = new Requester("telegram:300", "Sara");
    /** A member of the other group, and no admin. */
    private static final Requester TUYA = new Requester("telegram:500", "Tuya");
    /** An admin in no group. */
    private static final Requester ADMIN = new Requester("telegram:400", "Admin");
    /** An admin, and a member of the task's own group. */
    private static final Requester OYUN = new Requester("telegram:600", "Oyun");
    private static final Requester STRANGER = new Requester("telegram:999", "Eve");
    private static final Plan PLAN = new Plan("Make the auth timeout configurable", List.of(), List.of("Read auth.timeout"),
            List.of(), List.of());
    private static final Plan QUESTIONS = new Plan("Make the auth timeout configurable", List.of(), List.of("Read auth.timeout"),
            List.of(), List.of(new PlanQuestion("Which environments?", List.of("staging", "prod")),
                    new PlanQuestion("Keep the old default?", List.of("yes", "no"))));
    private static final AgentResult RESULT = new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", null, "Done.",
            new BigDecimal("0.10"), 3, List.of(), null, null, null);

    /** How a row's task came to be; returns its id. */
    interface Given {
        long task(TaskAccessTest test);
    }

    /** Who looks at a row's task. */
    interface Looking {
        TaskAccess.Viewer viewer(TaskAccess access);
    }

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TestClock clock;
    private Groups groups;
    private TaskService tasks;
    private RunTransitions transitions;
    private TaskAccess access;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-25T10:00:00Z"));
        groups = new Groups(new Config.Telegram(List.of(300L, 400L, 600L), List.of(
                new Config.Group("backend", -100L, List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali"),
                        new Config.Member(600, "Oyun")), List.of("alm", "crm")),
                new Config.Group("mobile", -300L, List.of(new Config.Member(300, "Sara"), new Config.Member(500, "Tuya")),
                        List.of("life")))));
        tasks = new TaskService(groups, new Projects(List.of(project("alm"), project("crm"), project("life")),
                project -> Optional.empty()), new ActiveRuns(), clock, () -> { }, () -> { });
        transitions = new RunTransitions(db, clock, () -> { });
        access = new TaskAccess(groups);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    static Stream<Arguments> actions() {
        return Stream.of(
                row("the requester approves a plan without questions", t -> t.awaiting(PLAN), BOLD, Action.APPROVE, null),
                row("the requester approves a plan that asks questions", t -> t.awaiting(QUESTIONS), BOLD, Action.APPROVE,
                        Refusal.OPEN_QUESTIONS),
                row("the requester approves while it is still planning", TaskAccessTest::planning, BOLD, Action.APPROVE,
                        Refusal.WRONG_PHASE),
                row("the requester approves again once it executes", TaskAccessTest::executing, BOLD, Action.APPROVE,
                        Refusal.WRONG_PHASE),
                row("a member of the group approves someone else's plan", t -> t.awaiting(PLAN), ALI, Action.APPROVE,
                        Refusal.NOT_REQUESTER),
                row("an admin of the task's group approves someone else's plan", t -> t.awaiting(PLAN), OYUN, Action.APPROVE,
                        Refusal.NOT_REQUESTER),
                row("a member of another group approves it", t -> t.awaiting(PLAN), TUYA, Action.APPROVE, Refusal.NOT_FOUND),
                row("an admin of another group approves it", t -> t.awaiting(PLAN), SARA, Action.APPROVE, Refusal.NOT_FOUND),
                row("an admin in no group approves it", t -> t.awaiting(PLAN), ADMIN, Action.APPROVE, Refusal.NOT_MEMBER),
                row("a stranger approves it", t -> t.awaiting(PLAN), STRANGER, Action.APPROVE, Refusal.NOT_MEMBER),
                row("the requester corrects a waiting plan", t -> t.awaiting(PLAN), BOLD, Action.CORRECT, null),
                row("the requester corrects once it executes", TaskAccessTest::executing, BOLD, Action.CORRECT,
                        Refusal.WRONG_PHASE),
                row("the requester rejects a plan that asks questions", t -> t.awaiting(QUESTIONS), BOLD, Action.REJECT, null),
                row("the requester answers a plan's open question", t -> t.awaiting(QUESTIONS), BOLD, Action.ANSWER, null),
                row("the requester answers a plan that asks nothing", t -> t.awaiting(PLAN), BOLD, Action.ANSWER,
                        Refusal.ALREADY_ANSWERED),
                row("the requester reprioritizes an active task", TaskAccessTest::planning, BOLD, Action.PRIORITY, null),
                row("the requester reprioritizes a finished task", TaskAccessTest::rejected, BOLD, Action.PRIORITY,
                        Refusal.WRONG_PHASE),
                row("a member of the group reprioritizes someone else's task", TaskAccessTest::planning, ALI, Action.PRIORITY,
                        Refusal.NOT_REQUESTER),
                row("the requester cancels an active task", TaskAccessTest::planning, BOLD, Action.CANCEL, null),
                row("the requester cancels a finished task", TaskAccessTest::rejected, BOLD, Action.CANCEL, Refusal.WRONG_PHASE),
                row("a member of the group cancels someone else's task", TaskAccessTest::planning, ALI, Action.CANCEL,
                        Refusal.NOT_REQUESTER),
                row("an admin of the task's group cancels someone else's task", TaskAccessTest::planning, OYUN, Action.CANCEL,
                        null),
                row("a member of another group cancels it", TaskAccessTest::planning, TUYA, Action.CANCEL, Refusal.NOT_FOUND),
                row("an admin of another group cancels it without seeing it", TaskAccessTest::planning, SARA, Action.CANCEL, null),
                row("an admin in no group cancels it", TaskAccessTest::planning, ADMIN, Action.CANCEL, null),
                row("an admin cancels a finished task", TaskAccessTest::rejected, SARA, Action.CANCEL, Refusal.WRONG_PHASE),
                row("a stranger cancels it", TaskAccessTest::planning, STRANGER, Action.CANCEL, Refusal.NOT_MEMBER),
                row("the requester retries a failed execution", TaskAccessTest::failedExecution, BOLD, Action.RETRY, null),
                row("the requester retries a failed plan", TaskAccessTest::failedPlan, BOLD, Action.RETRY, null),
                row("the requester retries a completed task", TaskAccessTest::completed, BOLD, Action.RETRY, Refusal.NOT_FAILED),
                row("the requester retries a rejected task", TaskAccessTest::rejected, BOLD, Action.RETRY, Refusal.NOT_FAILED),
                row("a member of the group retries someone else's failed task", TaskAccessTest::failedExecution, ALI, Action.RETRY,
                        Refusal.NOT_REQUESTER),
                row("an admin of another group retries it", TaskAccessTest::failedExecution, SARA, Action.RETRY,
                        Refusal.NOT_FOUND),
                row("the requester follows up a completed execution", TaskAccessTest::completed, BOLD, Action.FOLLOW_UP, null),
                row("the requester follows up a failed execution", TaskAccessTest::failedExecution, BOLD, Action.FOLLOW_UP, null),
                row("the requester follows up a plan that failed", TaskAccessTest::failedPlan, BOLD, Action.FOLLOW_UP,
                        Refusal.NOT_EXECUTED),
                row("the requester follows up an active task", TaskAccessTest::executing, BOLD, Action.FOLLOW_UP,
                        Refusal.WRONG_PHASE),
                row("the requester follows up a rejected task", TaskAccessTest::rejected, BOLD, Action.FOLLOW_UP,
                        Refusal.WRONG_PHASE),
                row("the requester follows up a cancelled task", TaskAccessTest::cancelled, BOLD, Action.FOLLOW_UP,
                        Refusal.WRONG_PHASE),
                row("the requester merges a completed task's pull request", TaskAccessTest::completed, BOLD, Action.MERGE, null),
                row("a member of the group merges someone else's", TaskAccessTest::completed, ALI, Action.MERGE,
                        Refusal.NOT_REQUESTER),
                row("the requester merges a task that delivered nothing", TaskAccessTest::completedWithoutChanges, BOLD, Action.MERGE,
                        Refusal.WRONG_PHASE),
                row("the requester merges a failed execution", TaskAccessTest::failedExecution, BOLD, Action.MERGE,
                        Refusal.WRONG_PHASE),
                row("the requester merges an active task", TaskAccessTest::executing, BOLD, Action.MERGE, Refusal.WRONG_PHASE),
                row("the requester merges a merged task", TaskAccessTest::merged, BOLD, Action.MERGE, Refusal.MERGED),
                row("a requester who left the group approves their own plan", TaskAccessTest::afterLeavingTheGroup, BOLD,
                        Action.APPROVE, null),
                row("a requester who left the group cancels their own task", TaskAccessTest::afterLeavingTheGroup, BOLD,
                        Action.CANCEL, null),
                row("the member now in that group approves it", TaskAccessTest::afterLeavingTheGroup, ALI, Action.APPROVE,
                        Refusal.NOT_REQUESTER),
                row("a task that does not exist", t -> 999L, BOLD, Action.CANCEL, Refusal.NOT_FOUND));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("actions")
    void action(String rule, Given given, Requester who, Action action, Refusal expected) {
        long taskId = given.task(this);

        assertEquals(Optional.ofNullable(expected), verdict(who, taskId).refusal(action), rule);
    }

    static Stream<Arguments> sights() {
        return Stream.of(
                Arguments.of("the requester sees their own task in full", (Looking) a -> a.member(BOLD.ref()),
                        (Given) TaskAccessTest::planning, Sight.FULL),
                Arguments.of("a member of the group sees its headline", (Looking) a -> a.member(ALI.ref()),
                        (Given) TaskAccessTest::planning, Sight.HEADLINE),
                Arguments.of("a member of another group does not see it", (Looking) a -> a.member(TUYA.ref()),
                        (Given) TaskAccessTest::planning, Sight.NONE),
                Arguments.of("its group chat sees its headline", (Looking) a -> a.chat("telegram:-100"),
                        (Given) TaskAccessTest::planning, Sight.HEADLINE),
                Arguments.of("another group's chat does not see it", (Looking) a -> a.chat("telegram:-300"),
                        (Given) TaskAccessTest::planning, Sight.NONE),
                Arguments.of("a requester who left the group still sees their own task in full", (Looking) a -> a.member(BOLD.ref()),
                        (Given) TaskAccessTest::afterLeavingTheGroup, Sight.FULL),
                Arguments.of("a requester in no group no longer sees their own task", (Looking) a -> a.member(BOLD.ref()),
                        (Given) TaskAccessTest::leftEveryGroup, Sight.NONE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sights")
    void sight(String rule, Looking looking, Given given, Sight expected) {
        long taskId = given.task(this);
        // After the row's setup, which may have changed the groups.
        TaskAccess.Viewer viewer = looking.viewer(access);
        Task task = db.transactionReturning(tx -> Tasks.find(tx, taskId)).orElseThrow();

        assertEquals(expected, viewer.sees(task), rule);
    }

    static Stream<Arguments> answers() {
        return Stream.of(
                Arguments.of("the first question, first", false, 1, 1, null),
                Arguments.of("the second question while the first is open", false, 1, 2, Refusal.OUT_OF_ORDER),
                Arguments.of("a question of a plan a newer one replaced", false, 7, 1, Refusal.STALE_PLAN),
                Arguments.of("a question the plan does not ask", false, 1, 3, Refusal.STALE_PLAN),
                Arguments.of("the first question again", true, 1, 1, Refusal.ALREADY_ANSWERED),
                Arguments.of("the second question once the first has its answer", true, 1, 2, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("answers")
    void answer(String rule, boolean firstAnswered, int planSeq, int index, Refusal expected) {
        long taskId = awaiting(QUESTIONS);
        if (firstAnswered) {
            db.transaction(tx -> PlanAnswers.record(tx, taskId, 1, 1, "prod", BOLD.ref(), null, clock.instant()));
        }

        assertEquals(Optional.ofNullable(expected), verdict(BOLD, taskId).answerRefusal(planSeq, index), rule);
    }

    @Test
    void aTapOnAReplacedPlanIsStaleAfterWhoAndThePhase() {
        long plain = awaiting(PLAN);
        long asking = awaiting(QUESTIONS);
        long moved = executing();

        assertEquals(Optional.of(Refusal.STALE_PLAN), verdict(BOLD, plain).refusal(Action.APPROVE, 2));
        assertEquals(Optional.of(Refusal.STALE_PLAN), verdict(BOLD, asking).refusal(Action.APPROVE, 2), "stale before open questions");
        assertEquals(Optional.of(Refusal.OPEN_QUESTIONS), verdict(BOLD, asking).refusal(Action.APPROVE, 1));
        assertEquals(Optional.empty(), verdict(BOLD, plain).refusal(Action.REJECT, 1));
        assertEquals(Optional.of(Refusal.WRONG_PHASE), verdict(BOLD, moved).refusal(Action.APPROVE, 1), "the phase before the plan");
        assertEquals(Optional.of(Refusal.NOT_REQUESTER), verdict(ALI, plain).refusal(Action.APPROVE, 2), "who, before anything");
    }

    @Test
    void aVerdictListsWhatTheMemberMayDoInOneOrder() {
        long taskId = awaiting(PLAN);

        assertEquals(List.of(Action.APPROVE, Action.CORRECT, Action.REJECT, Action.PRIORITY, Action.CANCEL),
                verdict(BOLD, taskId).allowed());
        assertEquals(List.of(), verdict(ALI, taskId).allowed(), "someone else's task is its requester's to act on");
        assertEquals(1, verdict(BOLD, taskId).planSeq());
        assertEquals("followUp", Action.FOLLOW_UP.json());
    }

    @Test
    void aPlanOffersApproveOnlyWhenItAsksNothing() {
        assertEquals(List.of(Action.APPROVE, Action.REJECT), TaskAccess.decisions(0));
        assertEquals(List.of(Action.REJECT), TaskAccess.decisions(2));
    }

    @Test
    void aPlanStoredBeforeQuestionsHadOptionsStillCountsItsQuestions() {
        long taskId = awaiting(PLAN);
        // G-1d: an older plan's questions are plain strings.
        db.transaction(tx -> tx.update("UPDATE task SET plan_json = ? WHERE id = ?",
                "{\"understanding\":\"u\",\"findings\":[],\"steps\":[\"s\"],\"risks\":[],\"questions\":[\"Which environments?\"]}",
                taskId));

        TaskAccess.Verdict verdict = verdict(BOLD, taskId);

        assertEquals(Optional.of(Refusal.OPEN_QUESTIONS), verdict.refusal(Action.APPROVE));
        assertEquals(1, verdict.currentQuestion());
    }

    private static Arguments row(String rule, Given given, Requester who, Action action, Refusal expected) {
        return Arguments.of(rule, given, who, action, expected);
    }

    private TaskAccess.Verdict verdict(Requester who, long taskId) {
        return db.transactionReturning(tx -> access.of(tx, who.ref(), taskId));
    }

    private long planning() {
        return create("alm");
    }

    private long awaiting(Plan plan) {
        return awaiting("alm", plan);
    }

    private long awaiting(String project, Plan plan) {
        long id = create(project);
        ClaimedRun run = claim();
        transitions.planSucceeded(id, run.seq(), plan, new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", plan.toJson(), null,
                new BigDecimal("0.10"), 3, List.of(), null, null, null));
        return id;
    }

    /** Approved; its execution run (seq 2) runs, and its agent started. */
    private long executing() {
        long id = awaiting(PLAN);
        db.transaction(tx -> tasks.approve(tx, BOLD, id, 1));
        ClaimedRun run = claim();
        transitions.agentStarted(id, run.seq(), null, null);
        return id;
    }

    private long failedExecution() {
        long id = executing();
        transitions.failed(id, 2, FailureReason.AGENT, "boom", RESULT);
        return id;
    }

    private long completed() {
        long id = executing();
        transitions.completed(id, 2, RESULT, List.of("README.md"), "https://github.com/acme/alm/pull/7");
        return id;
    }

    /** Its execution changed nothing, so there is no pull request. */
    private long completedWithoutChanges() {
        long id = executing();
        transitions.completed(id, 2, RESULT, List.of(), null);
        return id;
    }

    /** Its pull request merged from the result's button. */
    private long merged() {
        long id = completed();
        db.transaction(tx -> dispatch.store.Tasks.merged(tx, id, clock.instant()));
        return id;
    }

    /** Its first plan run failed, so no execution ever started. */
    private long failedPlan() {
        long id = create("alm");
        ClaimedRun run = claim();
        transitions.failed(id, run.seq(), FailureReason.AGENT, "boom", RESULT);
        return id;
    }

    private long rejected() {
        long id = awaiting(PLAN);
        db.transaction(tx -> tasks.reject(tx, BOLD, id, 1));
        return id;
    }

    private long cancelled() {
        long id = planning();
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Cancel(id)));
        return id;
    }

    /** Bold's crm plan waits for him after crm moved to a group he is not in; he is still in another group. */
    private long afterLeavingTheGroup() {
        long id = awaiting("crm", PLAN);
        groups.replace(new Config.Telegram(List.of(300L, 400L, 600L), List.of(
                new Config.Group("backend", -100L, List.of(new Config.Member(100, "Bold"), new Config.Member(600, "Oyun")),
                        List.of("alm")),
                new Config.Group("sales", -200L, List.of(new Config.Member(200, "Ali")), List.of("crm")),
                new Config.Group("mobile", -300L, List.of(new Config.Member(300, "Sara"), new Config.Member(500, "Tuya")),
                        List.of("life")))));
        return id;
    }

    /** Bold's task, after he is removed from every group; the other groups are unchanged. */
    private long leftEveryGroup() {
        long id = create("alm");
        groups.replace(new Config.Telegram(List.of(300L, 400L, 600L), List.of(
                new Config.Group("backend", -100L, List.of(new Config.Member(200, "Ali"), new Config.Member(600, "Oyun")),
                        List.of("alm", "crm")),
                new Config.Group("mobile", -300L, List.of(new Config.Member(300, "Sara"), new Config.Member(500, "Tuya")),
                        List.of("life")))));
        return id;
    }

    private long create(String project) {
        String origin = BOLD.ref() + "/" + System.nanoTime();
        db.transaction(tx -> tasks.create(tx, BOLD, project, "Fix the login timeout", Priority.NORMAL, origin));
        return Long.parseLong(SqlRows.single(dbFile, "SELECT id FROM task WHERE origin_ref = ?", origin).get("id"));
    }

    private ClaimedRun claim() {
        return db.transactionReturning(tx -> Runs.claimNext(tx, 10, clock.instant())).orElseThrow();
    }

    private static Config.Project project(String name) {
        return new Config.Project(name, null, "https://github.com/acme/" + name + ".git", null, "main", "claude-code", null, null,
                List.of(), null, null, null);
    }
}
