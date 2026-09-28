package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.Language;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
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
    private static final Plan PLAN = new Plan("Make the auth timeout configurable", List.of("AuthClient.java:14 hard-codes 30s"),
            List.of("Read auth.timeout"), List.of(), List.of());

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TestClock clock;
    private TaskService tasks;
    private TaskCommands commands;
    private RunTransitions transitions;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-29T10:00:00Z"));
        Config.Project alm = new Config.Project("autoland-management", "alm", "https://github.com/acme/alm.git", null, "main",
                "claude-code", null, null, List.of(), null, null, null);
        Groups groups = new Groups(new Config.Telegram(List.of(400L), List.of(
                new Config.Group("backend", -100L, List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")),
                        List.of("autoland-management")))));
        tasks = new TaskService(groups, new Projects(List.of(alm), project -> Optional.empty()), new ActiveRuns(), clock,
                () -> { }, () -> { });
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
    void aRetryQueuesTheFailedStepAgainAndTellsTheRequester() {
        long id = failedExecution("5");

        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Retry(id)));

        Map<String, String> retried = row("SELECT kind, cause, status FROM run WHERE task_id = ? ORDER BY seq DESC LIMIT 1", id);
        assertEquals(Map.of("kind", "EXECUTE", "cause", "RETRY", "status", "QUEUED"), retried);
        assertEquals(List.of(BOLD.ref()), chatsOf("RETRY_QUEUED"));
    }

    @Test
    void aRefusedCommandWritesNothingAndSaysWhyInBothLanguages() {
        long open = given(BOLD, "5");
        long failed = failedExecution("6");
        record Case(Requester who, TaskCommand command, Refusal reason) {
        }
        List<Case> cases = List.of(
                new Case(STRANGER, new TaskCommand.Cancel(open), Refusal.NOT_MEMBER),
                new Case(BOLD, new TaskCommand.Cancel(404), Refusal.NOT_FOUND),
                new Case(ALI, new TaskCommand.Cancel(open), Refusal.NOT_REQUESTER),
                new Case(ALI, new TaskCommand.Retry(failed), Refusal.NOT_REQUESTER),
                new Case(BOLD, new TaskCommand.Retry(open), Refusal.NOT_FAILED),
                new Case(ADMIN, new TaskCommand.Retry(failed), Refusal.NOT_MEMBER));
        for (Case refused : cases) {
            Map<String, String> before = counts();
            CommandResult result = run(refused.who(), refused.command());
            CommandResult.Refused refusal = assertInstanceOf(CommandResult.Refused.class, result, refused.toString());
            assertEquals(refused.reason(), refusal.reason(), refused.toString());
            assertEquals(before, counts(), refused + " wrote something");
            for (Language language : Language.values()) {
                String words = refusal.words().render(language);
                assertFalse(words.isBlank(), refused + " in " + language);
                assertTrue(words.length() <= 200, refused + " in " + language + " is too long for a button's notice: " + words);
            }
        }
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

    private long given(Requester who, String messageId) {
        String origin = who.ref() + "/" + messageId;
        assertEquals(CreateResult.CREATED,
                db.transactionReturning(tx -> tasks.create(tx, who, "alm", "Fix login timeout", Priority.NORMAL, origin)));
        return Long.parseLong(row("SELECT id FROM task WHERE origin_ref = ?", origin).get("id"));
    }

    /** A task of BOLD's whose execution failed; claims its own runs even when an earlier task's run is queued ahead. */
    private long failedExecution(String messageId) {
        long id = given(BOLD, messageId);
        ClaimedRun plan = claimFor(id);
        transitions.planSucceeded(id, plan.seq(), PLAN, planResult());
        assertEquals(ApproveResult.APPROVED, db.transactionReturning(tx -> tasks.approve(tx, BOLD, id, plan.seq())));
        ClaimedRun execution = claimFor(id);
        transitions.failed(id, execution.seq(), FailureReason.AGENT, "boom", executionResult());
        return id;
    }

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
}
