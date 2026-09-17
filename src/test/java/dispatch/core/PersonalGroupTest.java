package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

/** A group without a chat, as on a developer's own bot (ADR 0014): tasks belong to the requester's private chat. */
class PersonalGroupTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Plan PLAN = new Plan("Make the auth timeout configurable", List.of(), List.of("Read it from config"),
            List.of(), List.of());

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TaskService tasks;
    private RunTransitions transitions;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        TestClock clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
        Config.Project alm = new Config.Project("alm", null, null, "/home/bold/work/alm", "main", "claude-code", "opus", "high", List.of(),
                null);
        Groups groups = new Groups(List.of(new Config.Group("bold", null, List.of(new Config.Member(100, "Bold")), List.of("alm"))));
        tasks = new TaskService(groups, new Projects(List.of(alm), project -> Optional.empty()), new ActiveRuns(), clock, () -> { },
                () -> { });
        transitions = new RunTransitions(db, clock, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void taskBelongsToTheRequestersPrivateChatAndIsNotAnnounced() {
        long id = create("5");

        assertEquals("telegram:100", row("SELECT chat_ref FROM task WHERE id = ?", id).get("chat_ref"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'TASK_QUEUED'").get("n"));
    }

    @Test
    void outcomesReachOnlyTheRequesterWithoutAGroupToFallBackTo() {
        long planned = create("6");
        ClaimedRun plan = claim();
        transitions.planSucceeded(planned, plan.seq(), PLAN, result());
        long failed = create("7");
        transitions.failed(failed, claim().seq(), FailureReason.TIMEOUT, "stopped after 15m", null);

        Map<String, String> planReady = row("SELECT * FROM outbox WHERE kind = 'PLAN_READY'");
        assertEquals("telegram:100", planReady.get("chat_ref"));
        assertEquals("telegram:100/6", planReady.get("reply_to_ref"));
        assertNull(planReady.get("fallback_chat_ref"), "there is no group to fall back to");
        assertEquals("telegram:100", row("SELECT chat_ref FROM outbox WHERE kind = 'TASK_FAILED'").get("chat_ref"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind LIKE '%_SHORT'").get("n"), "no one-line repeats");
    }

    @Test
    void rejectAndCancelAreConfirmedOnceInThePrivateChat() {
        long rejected = create("8");
        transitions.planSucceeded(rejected, claim().seq(), PLAN, result());
        long cancelled = create("9");

        db.transaction(tx -> tasks.reject(tx, BOLD, rejected, 1));
        db.transaction(tx -> tasks.cancel(tx, BOLD, cancelled, "telegram:100/20", "telegram:100"));

        Map<String, String> rejection = row("SELECT * FROM outbox WHERE kind = 'TASK_REJECTED'");
        assertEquals("telegram:100", rejection.get("chat_ref"));
        assertEquals("telegram:100/8", rejection.get("reply_to_ref"), "under the task's own message");
        List<Map<String, String>> cancellations = SqlRows.query(dbFile, "SELECT * FROM outbox WHERE kind = 'TASK_CANCELLED'");
        assertEquals(1, cancellations.size());
        assertEquals("telegram:100", cancellations.getFirst().get("chat_ref"));
    }

    private long create(String messageId) {
        String origin = BOLD.ref() + "/" + messageId;
        assertEquals(CreateResult.CREATED, db.transactionReturning(tx -> tasks.create(tx, BOLD, "alm", "Fix the login timeout",
                Priority.NORMAL, origin)));
        return Long.parseLong(row("SELECT id FROM task WHERE origin_ref = ?", origin).get("id"));
    }

    private ClaimedRun claim() {
        return db.transactionReturning(tx -> Runs.claimNext(tx, 10, Instant.parse("2026-09-17T10:00:00Z"))).orElseThrow();
    }

    private static AgentResult result() {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", PLAN.toJson(), null, new BigDecimal("0.1"), 5, List.of(), null);
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
