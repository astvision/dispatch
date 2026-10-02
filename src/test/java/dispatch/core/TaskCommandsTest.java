package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.Language;
import dispatch.Text;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.Plan;
import dispatch.domain.PlanQuestion;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The task commands at their interface (ADR 0031): what each does, the news it writes, and what it refuses in which words.
 * The rules of who may do what are TaskAccessTest's (ADR 0027); these cases prove the commands ask it and relay it.
 */
class TaskCommandsTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");
    private static final Requester STRANGER = new Requester("telegram:999", "Nobody");
    /** An admin in no group: may cancel any task (ADR 0020), and do nothing else. */
    private static final Requester ADMIN = new Requester("telegram:400", "Tuya");
    private static final String CHAT = "telegram:-100";
    private static final Config.Project ALM = new Config.Project("autoland-management", "alm", "https://github.com/acme/alm.git", null,
            "main", "claude-code", null, null, List.of(), null, null, null);
    /** Never cloned: a task given there is refused as unavailable. */
    private static final Config.Project CRM = new Config.Project("crm", null, "https://github.com/acme/crm.git", null, "develop",
            "claude-code", null, null, List.of(), null, null, null);
    private static final Plan ANSWER = new Plan("Where is the timeout set?", List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), Plan.Result.ANSWER, "In `AuthClient.java:14`: **30 s**.");
    private static final Plan PLAN = new Plan("Make the auth timeout configurable", List.of("AuthClient.java:14 hard-codes 30s"),
            List.of("Read auth.timeout"), List.of(), List.of());
    private static final Plan PLAN_WITH_TWO_QUESTIONS = new Plan("Make the auth timeout configurable", List.of(),
            List.of("Read auth.timeout"), List.of(), List.of(
                    new PlanQuestion("Which environments?", List.of("staging", "prod")),
                    new PlanQuestion("Keep the old default?", List.of("yes", "no"))));

    /** The tasks the table's commands act on: one in each state a refusal needs. */
    private record Situation(long open, long failed, long awaiting, long answered, long planFailed, long completed) {
    }

    /** A command refused in one of the situation's tasks, and why. */
    private record Case(Requester who, Function<Situation, TaskCommand> command, Refusal reason) {
    }

    /** Each refusal a command gives, in one situation at least: the table both refusal tests read. */
    private static final List<Case> REFUSALS = List.of(
            new Case(STRANGER, on -> new TaskCommand.Cancel(on.open()), Refusal.NOT_MEMBER),
            new Case(ADMIN, on -> new TaskCommand.Retry(on.failed()), Refusal.NOT_MEMBER),
            new Case(BOLD, on -> new TaskCommand.Cancel(404), Refusal.NOT_FOUND),
            new Case(ALI, on -> new TaskCommand.Cancel(on.open()), Refusal.NOT_REQUESTER),
            new Case(ALI, on -> new TaskCommand.Retry(on.failed()), Refusal.NOT_REQUESTER),
            new Case(ALI, on -> new TaskCommand.Reprioritize(on.open(), Priority.URGENT), Refusal.NOT_REQUESTER),
            new Case(BOLD, on -> new TaskCommand.FollowUp(on.open(), "Also log it", Origin.page()), Refusal.WRONG_PHASE),
            new Case(BOLD, on -> new TaskCommand.Reprioritize(on.completed(), Priority.URGENT), Refusal.WRONG_PHASE),
            new Case(BOLD, on -> new TaskCommand.Retry(on.open()), Refusal.NOT_FAILED),
            new Case(BOLD, on -> new TaskCommand.Correct(on.awaiting(), OptionalInt.of(9), "Keep the default"), Refusal.STALE_PLAN),
            new Case(BOLD, on -> new TaskCommand.Reject(on.awaiting(), 9), Refusal.STALE_PLAN),
            new Case(BOLD, on -> new TaskCommand.Approve(on.awaiting(), 9), Refusal.STALE_PLAN),
            new Case(BOLD, on -> new TaskCommand.Answer(on.awaiting(), 1, 3, new TaskCommand.Choice.Option(0)), Refusal.STALE_PLAN),
            new Case(BOLD, on -> new TaskCommand.Approve(on.awaiting(), 1), Refusal.OPEN_QUESTIONS),
            new Case(BOLD, on -> new TaskCommand.Answer(on.awaiting(), 1, 2, new TaskCommand.Choice.Option(0)), Refusal.OUT_OF_ORDER),
            new Case(BOLD, on -> new TaskCommand.Answer(on.answered(), 1, 1, new TaskCommand.Choice.Option(1)), Refusal.ALREADY_ANSWERED),
            new Case(BOLD, on -> new TaskCommand.FollowUp(on.planFailed(), "Also log it", Origin.page()), Refusal.NOT_EXECUTED),
            new Case(BOLD, on -> new TaskCommand.Correct(on.awaiting(), OptionalInt.of(1), " "), Refusal.EMPTY),
            new Case(BOLD, on -> new TaskCommand.FollowUp(on.completed(), " ", Origin.page()), Refusal.EMPTY),
            new Case(BOLD, on -> new TaskCommand.Answer(on.awaiting(), 1, 1, new TaskCommand.Choice.Written(" ")), Refusal.EMPTY),
            new Case(BOLD, on -> new TaskCommand.Give("alm", " ", Priority.NORMAL, Origin.page()), Refusal.EMPTY),
            new Case(BOLD, on -> new TaskCommand.Give("nope", "Fix it", Priority.NORMAL, Origin.page()), Refusal.UNKNOWN_PROJECT),
            new Case(BOLD, on -> new TaskCommand.Give("crm", "Fix it", Priority.NORMAL, Origin.page()), Refusal.PROJECT_UNAVAILABLE));

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TestClock clock;
    private Groups groups;
    private TaskService tasks;
    private TaskCommands commands;
    private RunTransitions transitions;
    /** Why alm cannot take tasks now; null while it can. */
    private final AtomicReference<String> unavailable = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-29T10:00:00Z"));
        groups = new Groups(new Config.Telegram(List.of(400L), List.of(
                new Config.Group("backend", -100L, List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")),
                        List.of("autoland-management", "crm")))));
        tasks = new TaskService(groups, new Projects(List.of(ALM, CRM),
                project -> project == CRM ? Optional.of("repos/crm is not cloned") : Optional.ofNullable(unavailable.get())),
                new ActiveRuns(), clock, () -> { }, () -> { });
        commands = tasks.commands();
        transitions = new RunTransitions(db, clock, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void theRequesterCancelsTheirTaskAndTheGroupAndTheirTopicBothHearIt() {
        long id = given(BOLD, "5");

        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Cancel(id)));

        assertEquals("CANCELLED", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals("CANCELLED", row("SELECT status FROM run WHERE task_id = ?", id).get("status"));
        assertEquals(List.of(CHAT, BOLD.ref()), chatsOf("TASK_CANCELLED"), "the group's line, then the requester's own");
    }

    @Test
    void anAdminsCancelReachesTheRequesterAndLeavesTheAdminsReplyToTheChannel() {
        long id = given(BOLD, "5");

        assertEquals(new CommandResult.Done(id, false), run(ADMIN, new TaskCommand.Cancel(id)));

        assertEquals(List.of(CHAT, BOLD.ref()), chatsOf("TASK_CANCELLED"));
    }

    @Test
    void aPersonalBotsTaskIsCancelledInOneLineSinceItsChatIsTheRequesters() {
        // The same member and project on a personal bot: a group without a chat (ADR 0014).
        Groups personal = new Groups(List.of(new Config.Group("bold", null, List.of(new Config.Member(100, "Bold")),
                List.of("autoland-management"))));
        tasks = new TaskService(personal, new Projects(List.of(ALM), project -> Optional.empty()), new ActiveRuns(), clock,
                () -> { }, () -> { });
        commands = tasks.commands();
        long id = given(BOLD, "5");

        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Cancel(id)));

        assertEquals(List.of(BOLD.ref()), chatsOf("TASK_CANCELLED"), "one line: the group's line and the requester's are the same chat");
    }

    @Test
    void aRetryQueuesTheFailedStepAgainAndTellsTheRequester() {
        long id = failedExecution("5");

        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Retry(id)));

        Map<String, String> retried = row("SELECT kind, cause, status FROM run WHERE task_id = ? ORDER BY seq DESC LIMIT 1", id);
        assertEquals(Map.of("kind", "EXECUTE", "cause", "RETRY", "status", "QUEUED"), retried);
        assertEquals(List.of(BOLD.ref()), chatsOf("RETRY_QUEUED"));
    }

    @Test
    void anApprovalQueuesTheExecutionAndTellsTheRequester() {
        long id = awaitingApproval("5", PLAN);
        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Approve(id, 1)));
        assertEquals("EXECUTING", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals(List.of(BOLD.ref()), chatsOf("EXECUTION_QUEUED"));
    }

    @Test
    void aRejectionEndsTheTaskInItsChat() {
        long id = awaitingApproval("5", PLAN);
        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Reject(id, 1)));
        assertEquals(List.of(CHAT), chatsOf("TASK_REJECTED"));
    }

    @Test
    void theSamePriorityAgainChangesNothing() {
        long id = given(BOLD, "5");
        Map<String, String> before = counts();
        assertEquals(new CommandResult.Unchanged(id), run(BOLD, new TaskCommand.Reprioritize(id, Priority.NORMAL)));
        assertEquals(before, counts(), "unchanged writes nothing");
        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Reprioritize(id, Priority.URGENT)));
        assertEquals("URGENT", row("SELECT priority FROM task WHERE id = ?", id).get("priority"));
    }

    @Test
    void aCorrectionPlansAgainAndTellsTheRequester() {
        long id = awaitingApproval("5", PLAN);

        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Correct(id, OptionalInt.of(1), " Keep the default ")));

        Map<String, String> planning = row("SELECT kind, cause, instruction FROM run WHERE task_id = ? ORDER BY seq DESC LIMIT 1", id);
        assertEquals(Map.of("kind", "PLAN", "cause", "CORRECTION", "instruction", "Keep the default"), planning);
        assertEquals(List.of(BOLD.ref()), chatsOf("CORRECTION_QUEUED"));
    }

    @Test
    void aCorrectionWithoutAPlanNumberCorrectsTheOneThatWaits() {
        long id = awaitingApproval("5", PLAN);
        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Correct(id, OptionalInt.empty(), "more")));
    }

    @Test
    void aFollowUpContinuesAFinishedTask() {
        long id = completed("5");

        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.FollowUp(id, "Also log it", new Origin("telegram:100/9"))));

        assertEquals("EXECUTING", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals(List.of(BOLD.ref()), chatsOf("FOLLOW_UP_QUEUED"));
    }

    @Test
    void aMergedTasksFollowUpBecomesANewTaskGivenFromItsOrigin() {
        long id = merged("5");

        CommandResult.Created created = assertInstanceOf(CommandResult.Created.class,
                run(BOLD, new TaskCommand.FollowUp(id, "Also log it", new Origin("telegram:100/9"))));

        Map<String, String> task = row("SELECT origin_ref, description FROM task WHERE id = ?", created.taskId());
        assertEquals("telegram:100/9", task.get("origin_ref"));
        assertTrue(task.get("description").startsWith("Also log it\n\n↩️ #" + id + " "), task.get("description"));
        assertEquals(List.of(BOLD.ref()), chatsOf("FOLLOW_UP_NEW_TASK"));
        assertEquals(created, run(BOLD, new TaskCommand.FollowUp(id, "Also log it", new Origin("telegram:100/9"))),
                "the same origin gives the same task, and writes nothing more");
        assertEquals(List.of(BOLD.ref()), chatsOf("FOLLOW_UP_NEW_TASK"));
    }

    @Test
    void aMergedTasksFollowUpFromAPageSaysWhereItWentAndNotThatAPageGaveIt() {
        long id = merged("5");

        assertInstanceOf(CommandResult.Created.class, run(BOLD, new TaskCommand.FollowUp(id, "Also log it", Origin.page())));

        assertEquals(List.of(BOLD.ref()), chatsOf("FOLLOW_UP_NEW_TASK"));
        assertEquals(List.of(), chatsOf("TASK_GIVEN_ON_DESK"), "one line in the old task's topic says it all");
    }

    @Test
    void aMergedTasksFollowUpIsRefusedAsGivingATaskIsWhenItsProjectIsUnavailable() {
        long id = merged("5");
        unavailable.set("repos/alm is being cloned");
        Map<String, String> before = counts();

        CommandResult.Refused refused = assertInstanceOf(CommandResult.Refused.class,
                run(BOLD, new TaskCommand.FollowUp(id, "Also log it", Origin.page())));

        assertEquals(Refusal.PROJECT_UNAVAILABLE, refused.reason());
        assertTrue(refused.words().render(Language.EN).contains("repos/alm is being cloned"), refused.words().render(Language.EN));
        assertEquals(before, counts());
    }

    @Test
    void aMergedTasksFollowUpIsRefusedAsGivingATaskIsWhenItsRequesterLeftTheProjectsGroup() {
        long id = merged("5");
        // Still a member, so his own task is his to follow up; its project is no longer one of his.
        groups.replace(new Config.Telegram(List.of(400L), List.of(
                new Config.Group("backend", -100L, List.of(new Config.Member(200, "Ali")), List.of("autoland-management", "crm")),
                new Config.Group("mobile", -300L, List.of(new Config.Member(100, "Bold")), List.of()))));
        Map<String, String> before = counts();

        CommandResult.Refused refused = assertInstanceOf(CommandResult.Refused.class,
                run(BOLD, new TaskCommand.FollowUp(id, "Also log it", Origin.page())));

        assertEquals(Refusal.UNKNOWN_PROJECT, refused.reason());
        assertEquals(before, counts());
    }

    @Test
    void answersGoInOrderAndTheLastSendsThemAllAsOneCorrection() {
        long id = awaitingApproval("5", PLAN_WITH_TWO_QUESTIONS);

        assertEquals(Refusal.OUT_OF_ORDER, refusalOf(run(BOLD, new TaskCommand.Answer(id, 1, 2, new TaskCommand.Choice.Option(0)))));
        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Answer(id, 1, 1, new TaskCommand.Choice.Option(1))));
        assertEquals(Refusal.ALREADY_ANSWERED, refusalOf(run(BOLD, new TaskCommand.Answer(id, 1, 1, new TaskCommand.Choice.Written("x")))));
        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Answer(id, 1, 2, new TaskCommand.Choice.YouDecide())));

        String correction = row("SELECT instruction FROM run WHERE task_id = ? ORDER BY seq DESC LIMIT 1", id).get("instruction");
        assertTrue(correction.contains("1. Which environments? → prod"), correction);
        assertTrue(correction.contains("2. Keep the old default? → " + Text.of("answer.youDecide").render(Language.MN)), correction);
    }

    @Test
    void anAnswerRedrawsTheQuestionByItsOwnRowEvenBeforeItIsSent() {
        long id = awaitingApproval("5", PLAN_WITH_TWO_QUESTIONS);
        String question = row("SELECT id FROM outbox WHERE kind = 'PLAN_QUESTION' AND task_id = ?", id).get("id");

        run(BOLD, new TaskCommand.Answer(id, 1, 1, new TaskCommand.Choice.Written("staging")));

        Map<String, String> redraw = row("SELECT edit_of, edit_ref FROM outbox WHERE kind = 'PLAN_QUESTION' AND edit_of IS NOT NULL");
        assertEquals(question, redraw.get("edit_of"), "names the question's row, which Telegram has not got yet");
        assertEquals(null, redraw.get("edit_ref"));
    }

    @Test
    void emptyWordsAreRefusedOnlyOnceWhoAndWhenAllowIt() {
        long id = awaitingApproval("5", PLAN_WITH_TWO_QUESTIONS);
        assertEquals(Refusal.EMPTY, refusalOf(run(BOLD, new TaskCommand.Correct(id, OptionalInt.of(1), "  "))));
        assertEquals(Refusal.EMPTY, refusalOf(run(BOLD, new TaskCommand.Answer(id, 1, 1, new TaskCommand.Choice.Option(7)))));
        assertEquals(Refusal.NOT_MEMBER, refusalOf(run(STRANGER, new TaskCommand.Correct(id, OptionalInt.of(1), "  "))),
                "a stranger never learns their words were blank");
        assertEquals(Refusal.STALE_PLAN, refusalOf(run(BOLD, new TaskCommand.Correct(id, OptionalInt.of(9), ""))));
    }

    @Test
    void givingATaskIsRefusedInOrderAndGivenOncePerOrigin() {
        assertEquals(Refusal.NOT_MEMBER, refusalOf(run(STRANGER, new TaskCommand.Give("alm", "Fix it", Priority.NORMAL, Origin.page()))));
        assertEquals(Refusal.UNKNOWN_PROJECT, refusalOf(run(BOLD, new TaskCommand.Give("nope", "Fix it", Priority.NORMAL, Origin.page()))));
        assertEquals(Refusal.EMPTY, refusalOf(run(BOLD, new TaskCommand.Give("alm", " ", Priority.NORMAL, Origin.page()))));
        // Who comes before the project, and the project before the words.
        assertEquals(Refusal.NOT_MEMBER, refusalOf(run(STRANGER, new TaskCommand.Give("nope", " ", Priority.NORMAL, Origin.page()))));
        assertEquals(Refusal.UNKNOWN_PROJECT, refusalOf(run(BOLD, new TaskCommand.Give("nope", " ", Priority.NORMAL, Origin.page()))));
        assertEquals(Refusal.PROJECT_UNAVAILABLE, refusalOf(run(BOLD, new TaskCommand.Give("crm", " ", Priority.NORMAL, Origin.page()))));
        Origin origin = Origin.page();
        CommandResult.Created created = assertInstanceOf(CommandResult.Created.class,
                run(BOLD, new TaskCommand.Give("alm", "Fix it", Priority.URGENT, origin)));
        Map<String, String> once = counts();
        assertEquals(created, run(BOLD, new TaskCommand.Give("alm", "Fix it", Priority.URGENT, origin)));
        assertEquals(once, counts(), "the same origin gives the same task, and writes nothing more");
        assertEquals(List.of(BOLD.ref()), chatsOf("TASK_GIVEN_ON_DESK"), "a task from a page tells the chat it was given there");
    }

    /** A page has no message in the chat that gave the task: the requester's chat hears where it came from (D-2b). */
    @Test
    void aTaskGivenOnAPageComesFromThePageAndSaysSoInTheRequestersChat() {
        Origin page = Origin.page();

        long id = assertInstanceOf(CommandResult.Created.class,
                run(BOLD, new TaskCommand.Give("alm", "  Fix the login timeout  ", Priority.URGENT, page))).taskId();

        assertEquals(Map.of("origin_ref", page.ref(), "description", "Fix the login timeout", "priority", "URGENT"),
                row("SELECT origin_ref, description, priority FROM task WHERE id = ?", id));
        assertEquals("PLAN", row("SELECT kind FROM run WHERE task_id = ?", id).get("kind"));
        Map<String, String> line = row("SELECT chat_ref, reply_to_ref, payload FROM outbox WHERE kind = 'TASK_GIVEN_ON_DESK'");
        assertEquals(BOLD.ref(), line.get("chat_ref"));
        assertNull(line.get("reply_to_ref"), "no message of the requester's gave it");
        assertTrue(line.get("payload").contains("\"title\":\"Fix the login timeout\""), line.toString());
    }

    /** Another group's project is no more the giver's than one that does not exist; one that cannot take tasks says why. */
    @Test
    void aTaskIsGivenOnlyOnTheGiversOwnProjectsAndOnlyWhenTheyCanTakeIt() {
        unavailable.set("repos/alm is being cloned");
        CommandResult.Refused cloning = assertInstanceOf(CommandResult.Refused.class,
                run(BOLD, new TaskCommand.Give("alm", "Fix it", Priority.NORMAL, Origin.page())));
        assertEquals(Refusal.PROJECT_UNAVAILABLE, cloning.reason());
        assertTrue(cloning.words().render(Language.EN).contains("repos/alm is being cloned"), cloning.words().render(Language.EN));
        unavailable.set(null);
        // Bold is still a member, but alm is only the group's he left.
        groups.replace(new Config.Telegram(List.of(400L), List.of(
                new Config.Group("backend", -100L, List.of(new Config.Member(200, "Ali")), List.of("autoland-management", "crm")),
                new Config.Group("mobile", -300L, List.of(new Config.Member(100, "Bold")), List.of()))));

        assertEquals(Refusal.UNKNOWN_PROJECT, refusalOf(run(BOLD, new TaskCommand.Give("alm", "Fix it", Priority.NORMAL, Origin.page()))));
        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox").get("n"), "a refusal is the page's to show, never a line in the chat");
    }

    @Test
    void aRefusedCommandWritesNothingAndSaysWhyInBothLanguages() {
        long open = given(BOLD, "5");
        long failed = failedExecution("6");
        long awaiting = awaitingApproval("7", PLAN_WITH_TWO_QUESTIONS);
        long answered = awaitingApproval("8", PLAN_WITH_TWO_QUESTIONS);
        run(BOLD, new TaskCommand.Answer(answered, 1, 1, new TaskCommand.Choice.Option(0)));
        long planFailed = given(BOLD, "9");
        transitions.failed(planFailed, claimFor(planFailed).seq(), FailureReason.AGENT, "boom", planResult());
        Situation on = new Situation(open, failed, awaiting, answered, planFailed, completed("10"));
        for (Case refused : REFUSALS) {
            TaskCommand command = refused.command().apply(on);
            String what = refused.who().name() + ": " + command;
            Map<String, String> before = counts();
            CommandResult result = run(refused.who(), command);
            CommandResult.Refused refusal = assertInstanceOf(CommandResult.Refused.class, result, what);
            assertEquals(refused.reason(), refusal.reason(), what);
            assertEquals(before, counts(), what + " wrote something");
            for (Language language : Language.values()) {
                String words = refusal.words().render(language);
                assertFalse(words.isBlank(), what + " in " + language);
                assertTrue(words.length() <= 200, what + " in " + language + " is too long for a button's notice: " + words);
            }
        }
    }

    @Test
    void everyRefusalHasACaseInTheTable() {
        // REFUSALS names each refusal a command gives; one missing here is one no test words. MERGED is Merges' refusal and
        // no command's.
        assertEquals(EnumSet.complementOf(EnumSet.of(Refusal.MERGED)), refusalsCovered());
    }

    @Test
    void aRefusalNamesTheTaskAndWhatStandsInTheWay() {
        long id = given(BOLD, "5");
        run(BOLD, new TaskCommand.Cancel(id));

        CommandResult.Refused again = assertInstanceOf(CommandResult.Refused.class, run(BOLD, new TaskCommand.Cancel(id)));
        assertEquals(Refusal.WRONG_PHASE, again.reason());
        assertTrue(again.words().render(Language.EN).contains("#" + id), again.words().render(Language.EN));
        assertTrue(again.words().render(Language.EN).contains("cancelled"), again.words().render(Language.EN));

        long other = given(BOLD, "6");
        CommandResult.Refused notYours = assertInstanceOf(CommandResult.Refused.class, run(ALI, new TaskCommand.Cancel(other)));
        assertTrue(notYours.words().render(Language.MN).contains("Bold"), "names who gave it (ADR 0020 shows the headline)");
    }

    @Test
    void checkRefusesAsRunWouldAndWritesNothing() {
        long id = given(BOLD, "5");
        Map<String, String> before = counts();

        Optional<CommandResult.Refused> checked = db.transactionReturning(tx -> commands.check(tx, ALI, new TaskCommand.Cancel(id)));
        assertEquals(Optional.of(run(ALI, new TaskCommand.Cancel(id))), checked);
        assertEquals(Optional.empty(), db.transactionReturning(tx -> commands.check(tx, BOLD, new TaskCommand.Cancel(id))));
        assertEquals(before, counts());
    }

    private CommandResult run(Requester who, TaskCommand command) {
        return db.transactionReturning(tx -> commands.run(tx, who, command));
    }

    private static Refusal refusalOf(CommandResult result) {
        return assertInstanceOf(CommandResult.Refused.class, result).reason();
    }

    private static Set<Refusal> refusalsCovered() {
        return REFUSALS.stream().map(Case::reason).collect(Collectors.toCollection(() -> EnumSet.noneOf(Refusal.class)));
    }

    private long given(Requester who, String messageId) {
        return assertInstanceOf(CommandResult.Created.class, run(who,
                new TaskCommand.Give("alm", "Fix login timeout", Priority.NORMAL, new Origin(who.ref() + "/" + messageId)))).taskId();
    }

    /** A task of BOLD's whose plan (run 1) waits for approval. */
    private long awaitingApproval(String messageId, Plan plan) {
        long id = given(BOLD, messageId);
        transitions.planSucceeded(id, claimFor(id).seq(), plan, planResult());
        return id;
    }

    /** A task of BOLD's whose approved plan's execution runs, its agent started. */
    private ClaimedRun executing(String messageId) {
        long id = awaitingApproval(messageId, PLAN);
        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Approve(id, 1)));
        ClaimedRun execution = claimFor(id);
        transitions.agentStarted(id, execution.seq(), null, null);
        return execution;
    }

    private long failedExecution(String messageId) {
        ClaimedRun execution = executing(messageId);
        transitions.failed(execution.taskId(), execution.seq(), FailureReason.AGENT, "boom", executionResult());
        return execution.taskId();
    }

    /** Its execution delivered a pull request, so it takes a follow-up (ADR 0006). */
    private long completed(String messageId) {
        ClaimedRun execution = executing(messageId);
        transitions.completed(execution.taskId(), execution.seq(), executionResult(), List.of("src/AuthClient.java"),
                "https://github.com/acme/alm/pull/7");
        return execution.taskId();
    }

    /** Completed, and its pull request merged: its branch is gone. */
    private long merged(String messageId) {
        long id = completed(messageId);
        db.transaction(tx -> Tasks.merged(tx, id, clock.instant()));
        return id;
    }

    /** Claims the task's next run, even when an earlier task's run is queued ahead of it. */
    private ClaimedRun claimFor(long taskId) {
        ClaimedRun claimed = db.transactionReturning(tx -> Runs.claimNext(tx, 10, clock.instant())).orElseThrow();
        while (claimed.taskId() != taskId) {
            claimed = db.transactionReturning(tx -> Runs.claimNext(tx, 10, clock.instant())).orElseThrow();
        }
        return claimed;
    }

    private static AgentResult planResult() {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", PLAN.toJson(), null, new BigDecimal("0.10"), 9, List.of(),
                null, null, null);
    }

    private static AgentResult executionResult() {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", null, "Made the auth timeout configurable.",
                new BigDecimal("0.42"), 12, List.of(), null, null, null);
    }

    private List<String> chatsOf(String kind) {
        return SqlRows.query(dbFile, "SELECT chat_ref FROM outbox WHERE kind = ? ORDER BY id", kind).stream()
                .map(found -> found.get("chat_ref")).toList();
    }

    /** What a refused command must leave as it was. */
    private Map<String, String> counts() {
        return row("""
                SELECT (SELECT count(*) FROM task) AS tasks, (SELECT count(*) FROM run) AS runs,
                       (SELECT count(*) FROM task_event) AS events, (SELECT count(*) FROM plan_answer) AS answers,
                       (SELECT count(*) FROM outbox) AS outbox""");
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }

    @Test
    void anAnsweredTaskCompletesWithoutApprovalAndOnlyItsRequesterGetsTheAnswer() {
        long id = given(BOLD, "5");
        transitions.planSucceeded(id, claimFor(id).seq(), ANSWER, planResult());

        Map<String, String> task = row("SELECT phase, completed_at, pr_url FROM task WHERE id = ?", id);
        assertEquals("COMPLETED", task.get("phase"));
        assertNotNull(task.get("completed_at"));
        assertNull(task.get("pr_url"));
        assertEquals("1", row("SELECT count(*) AS n FROM run WHERE task_id = ?", id).get("n"), "no execution run");
        assertEquals(List.of(BOLD.ref()), chatsOf("ANSWER_READY"));
        assertEquals(List.of(), chatsOf("PLAN_READY"));
        assertEquals("answered", row("SELECT reason FROM task_event WHERE task_id = ? ORDER BY id DESC LIMIT 1", id).get("reason"));
    }
}
