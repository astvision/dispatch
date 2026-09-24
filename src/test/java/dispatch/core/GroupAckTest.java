package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.GroupAck;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.MemberPrefs;
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
 * A group-origin task's reaction on the message that started it, instead of the old ✉️ line, by the requester's own
 * choice (G-1e). The 👀 state itself (the delivered draft prompt) lives in OutboxSender, exercised in
 * OutboxSenderTest and UpdateHandlerTest; this covers what TaskService and RunTransitions enqueue on their own.
 */
class GroupAckTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold Dev");
    private static final String GROUP = "telegram:-100";
    private static final Plan PLAN = new Plan("Make the timeout configurable", List.of(), List.of("Edit it"), List.of(), List.of());

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TestClock clock;
    private TaskService tasks;
    private RunTransitions transitions;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
        Config.Project alm = new Config.Project("autoland-management", "alm", "https://github.com/acme/alm.git", null, "main",
                "claude-code", null, null, List.of(), null, null, null);
        Projects projects = new Projects(List.of(alm), project -> Optional.empty());
        Groups groups = new Groups(List.of(new Config.Group("backend", -100L, List.of(new Config.Member(100, "Bold Dev")),
                List.of("autoland-management"))));
        tasks = new TaskService(groups, projects, new ActiveRuns(), clock, () -> { }, () -> { });
        transitions = new RunTransitions(db, clock, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void creatingAGroupOriginTaskReactsWithTheWorkingIconByDefaultAndSendsNoLine() {
        long id = createFromMention("30");

        Map<String, String> reaction = row("SELECT * FROM outbox WHERE kind = 'GROUP_REACTION' AND task_id = ?", id);
        assertEquals(GROUP, reaction.get("chat_ref"));
        assertEquals(GROUP + "/30", reaction.get("reply_to_ref"));
        assertEquals("✍", Json.read(reaction.get("payload")).get("emoji").asText());
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'GROUP_WORKING'").get("n"));
    }

    @Test
    void reactionAndLineAlsoPostsTheWorkingLineOnCreation() {
        db.transaction(tx -> MemberPrefs.setGroupAck(tx, 100, GroupAck.REACTION_AND_LINE, clock.instant()));

        long id = createFromMention("32");

        assertEquals("✍", Json.read(row("SELECT payload FROM outbox WHERE kind = 'GROUP_REACTION' AND task_id = ?", id).get("payload"))
                .get("emoji").asText());
        Map<String, String> line = row("SELECT * FROM outbox WHERE kind = 'GROUP_WORKING' AND task_id = ?", id);
        assertEquals(GROUP, line.get("chat_ref"));
        assertEquals(GROUP + "/32", line.get("reply_to_ref"));
        assertEquals("Bold", Json.read(line.get("payload")).get("requester").asText(), "the first word of the configured name");
    }

    @Test
    void silentGetsNoReactionAndNoLine() {
        db.transaction(tx -> MemberPrefs.setGroupAck(tx, 100, GroupAck.SILENT, clock.instant()));

        long id = createFromMention("33");

        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE task_id = ? AND kind IN ('GROUP_REACTION', 'GROUP_WORKING')", id)
                .get("n"));
    }

    @Test
    void completingReactsWithThumbsUp() {
        long id = executingGroupOriginTask("34");

        transitions.completed(id, 2, executionResult(), List.of("a.txt"), "https://github.com/acme/alm/pull/1");

        assertEquals("👍", lastReactionEmoji(id));
    }

    @Test
    void failingReactsWithThumbsDown() {
        long id = executingGroupOriginTask("35");

        transitions.failed(id, 2, FailureReason.AGENT, "boom", executionResult());

        assertEquals("👎", lastReactionEmoji(id));
    }

    @Test
    void rejectingReactsWithThumbsDown() {
        long id = awaitingApprovalGroupOriginTask("36");

        db.transaction(tx -> tasks.reject(tx, BOLD, id, 1));

        assertEquals("👎", lastReactionEmoji(id));
    }

    @Test
    void cancellingReactsWithThumbsDown() {
        long id = createFromMention("37");

        db.transaction(tx -> tasks.cancel(tx, BOLD, id, GROUP + "/37", GROUP));

        assertEquals("👎", lastReactionEmoji(id));
    }

    @Test
    void aTaskGivenDirectlyRatherThanByMentioningTheBotGetsNoReaction() {
        String origin = BOLD.ref() + "/40";
        CreateResult result = db.transactionReturning(tx ->
                tasks.create(tx, BOLD, "autoland-management", "Fix login timeout", Priority.NORMAL, origin));
        assertEquals(CreateResult.CREATED, result);
        long id = Long.parseLong(row("SELECT id FROM task WHERE origin_ref = ?", origin).get("id"));

        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE task_id = ? AND kind = 'GROUP_REACTION'", id).get("n"));
    }

    private long createFromMention(String messageId) {
        String origin = GROUP + "/" + messageId;
        DraftResult drafted = db.transactionReturning(tx ->
                tasks.draft(tx, BOLD, "autoland-management", "Fix login timeout", origin, List.of(),
                        new TaskService.GroupOrigin(GROUP, "Bold")));
        assertEquals(DraftResult.DRAFTED, drafted);
        long draftId = Long.parseLong(row("SELECT id FROM draft WHERE origin_ref = ?", origin).get("id"));
        assertEquals(DraftChoice.CREATED, db.transactionReturning(tx -> tasks.choosePriority(tx, BOLD, draftId, Priority.NORMAL)));
        return Long.parseLong(row("SELECT id FROM task WHERE origin_ref = ?", origin).get("id"));
    }

    private long awaitingApprovalGroupOriginTask(String messageId) {
        long id = createFromMention(messageId);
        ClaimedRun run = db.transactionReturning(tx -> Runs.claimNext(tx, 10, clock.instant())).orElseThrow();
        transitions.planSucceeded(id, run.seq(), PLAN, planResult());
        return id;
    }

    private long executingGroupOriginTask(String messageId) {
        long id = awaitingApprovalGroupOriginTask(messageId);
        db.transaction(tx -> tasks.approve(tx, BOLD, id, 1));
        ClaimedRun run = db.transactionReturning(tx -> Runs.claimNext(tx, 10, clock.instant())).orElseThrow();
        assertEquals(2, run.seq());
        return id;
    }

    private static AgentResult planResult() {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", PLAN.toJson(), null, new BigDecimal("0.10"), 5, List.of(), null, null,
                null);
    }

    private static AgentResult executionResult() {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", null, "Done", new BigDecimal("0.10"), 5, List.of(), null, null, null);
    }

    private String lastReactionEmoji(long taskId) {
        return Json.read(SqlRows.query(dbFile, "SELECT payload FROM outbox WHERE kind = 'GROUP_REACTION' AND task_id = ? ORDER BY id DESC LIMIT 1",
                taskId).getFirst().get("payload")).get("emoji").asText();
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
