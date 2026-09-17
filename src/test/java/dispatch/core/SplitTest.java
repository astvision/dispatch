package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dispatch.Json;
import dispatch.agent.Agent;
import dispatch.agent.AgentActivity;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.AgentStartException;
import dispatch.agent.RunHandle;
import dispatch.agent.RunRequest;
import dispatch.config.Config;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.store.Database;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Splitting one private message into several tasks on request (ADR 0013). */
class SplitTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester SARA = new Requester("telegram:300", "Sara");
    private static final String MESSAGE = "staging: login timeout-г 60 секунд болго, make help target нэм";
    private static final String PROMPT = "telegram:100/40";

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TestClock clock;
    private final List<Long> started = new CopyOnWriteArrayList<>();
    private final StubAgent agent = new StubAgent();
    private TaskService tasks;
    private Splitter splitter;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
        Projects projects = new Projects(List.of(project("autoland-management"), project("crm"), project("life")), project -> Optional.empty());
        Groups groups = new Groups(List.of(
                new Config.Group("backend", -100, List.of(new Config.Member(100, "Bold")), List.of("autoland-management", "crm")),
                new Config.Group("mobile", -300, List.of(new Config.Member(100, "Bold"), new Config.Member(300, "Sara")),
                        List.of("life"))));
        tasks = new TaskService(groups, projects, new ActiveRuns(), clock, () -> { }, () -> { }, false, started::add);
        splitter = new Splitter(db, tasks, agent, dir.resolve("splits"), clock, Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void scissorsAskTheAgentInTheBackgroundAndTheTopicsAreProposedOnTheSamePrompt() {
        long draftId = draft(BOLD, MESSAGE, "telegram:100/5");
        agent.result = topics("staging: login timeout-г 60 секунд болго", "staging: make help target нэм");

        assertEquals(DraftChoice.SPLITTING, db.transactionReturning(tx -> tasks.split(tx, BOLD, draftId, PROMPT)));
        assertEquals(List.of(draftId), started, "the agent starts once the press is committed");
        Map<String, String> splitting = row("SELECT * FROM draft WHERE id = ?", draftId);
        assertEquals("SPLITTING", splitting.get("split_state"));
        assertEquals(PROMPT, splitting.get("prompt_ref"));
        assertFalse(payload(draftId).get("splittable").asBoolean(), "no second press while it runs");

        splitter.split(draftId);

        RunRequest request = agent.requests.getFirst();
        assertEquals(RunKind.SPLIT, request.kind());
        assertEquals("haiku", request.model());
        assertNull(request.effort(), "a split is too small to need one");
        assertEquals(0, new BigDecimal("0.25").compareTo(request.budgetUsd()));
        assertEquals(dir.resolve("splits"), request.workdir());
        assertTrue(request.prompt().contains("<message>\n" + MESSAGE + "\n</message>"), request.prompt());
        assertEquals("PROPOSED", row("SELECT split_state FROM draft WHERE id = ?", draftId).get("split_state"));
        Map<String, String> redraw = row("SELECT * FROM outbox WHERE edit_ref IS NOT NULL");
        assertEquals("DRAFT_PROMPT", redraw.get("kind"));
        assertEquals("telegram:100", redraw.get("chat_ref"));
        assertEquals(PROMPT, redraw.get("edit_ref"));
        JsonNode proposal = Json.read(redraw.get("payload"));
        assertEquals("PROPOSED", proposal.get("split").asText());
        assertEquals("staging: make help target нэм", proposal.get("topics").get(1).asText());
    }

    @Test
    void messageWithOneTopicStaysAsItWasWithoutScissors() {
        long draftId = splitting(BOLD, "Fix the login timeout", "telegram:100/6");
        agent.result = topics("Fix the login timeout");

        splitter.split(draftId);

        JsonNode prompt = payload(draftId);
        assertEquals("ONE_TOPIC", prompt.get("split").asText());
        assertTrue(prompt.get("topics").isEmpty());
        assertFalse(prompt.get("splittable").asBoolean());
        assertEquals("OPEN", prompt.get("status").asText());
    }

    @Test
    void splittingGivesEachPartItsOwnDraftAndPromptUnderTheOriginalMessage() {
        long draftId = draft(BOLD, MESSAGE, "telegram:100/7");
        db.transaction(tx -> tasks.chooseProject(tx, BOLD, draftId, "crm"));
        proposed(draftId, "staging: fix login timeout", "staging: add make help");

        assertEquals(DraftChoice.SPLIT, db.transactionReturning(tx -> tasks.acceptSplit(tx, BOLD, draftId)));

        assertEquals("SPLIT", row("SELECT status FROM draft WHERE id = ?", draftId).get("status"));
        Map<String, String> second = row("SELECT * FROM draft WHERE parent_id = ? AND part = 2", draftId);
        assertEquals("telegram:100/7#2", second.get("origin_ref"), "unique per part, still naming the message");
        assertEquals("staging: add make help", second.get("description"));
        assertEquals("crm", second.get("project"), "the project chosen before splitting carries over");
        assertEquals("OPEN", second.get("status"));
        Map<String, String> prompt = row("SELECT * FROM outbox WHERE kind = 'DRAFT_PROMPT' AND reply_to_ref = 'telegram:100/7#2'");
        assertEquals("telegram:100", prompt.get("chat_ref"));
        JsonNode part = Json.read(prompt.get("payload"));
        assertEquals(2, part.get("part").asInt());
        assertEquals(2, part.get("parts").asInt());
        assertFalse(part.get("splittable").asBoolean(), "a part is not split again");
        JsonNode parent = payload(draftId);
        assertEquals("SPLIT", parent.get("status").asText());
        assertEquals(2, parent.get("topics").size());

        long partId = Long.parseLong(second.get("id"));
        assertEquals(DraftChoice.CREATED, db.transactionReturning(tx -> tasks.choosePriority(tx, BOLD, partId, Priority.URGENT)));
        Map<String, String> task = row("SELECT * FROM task");
        assertEquals("telegram:100/7#2", task.get("origin_ref"));
        assertEquals("staging: add make help", task.get("description"));
        assertEquals(DraftChoice.ALREADY_SPLIT, db.transactionReturning(tx -> tasks.acceptSplit(tx, BOLD, draftId)));
        assertEquals(DraftChoice.ALREADY_SPLIT, db.transactionReturning(tx -> tasks.choosePriority(tx, BOLD, draftId, Priority.LOW)));
        assertEquals("2", row("SELECT count(*) AS n FROM draft WHERE parent_id = ?", draftId).get("n"));
    }

    @Test
    void keepingTheMessageWholeAsksForProjectAndPriorityAgain() {
        long draftId = draft(BOLD, MESSAGE, "telegram:100/8");
        proposed(draftId, "staging: fix login timeout", "staging: add make help");

        assertEquals(DraftChoice.KEPT_WHOLE, db.transactionReturning(tx -> tasks.keepWhole(tx, BOLD, draftId)));

        JsonNode prompt = payload(draftId);
        assertEquals("KEPT", prompt.get("split").asText());
        assertFalse(prompt.get("splittable").asBoolean());
        assertEquals(DraftChoice.CANNOT_SPLIT, db.transactionReturning(tx -> tasks.acceptSplit(tx, BOLD, draftId)));
        assertEquals("0", row("SELECT count(*) AS n FROM draft WHERE parent_id IS NOT NULL").get("n"));
    }

    @Test
    void failedSplitIsShownOnThePromptAndCanBeTriedAgain() {
        long draftId = splitting(BOLD, MESSAGE, "telegram:100/9");
        agent.result = new AgentResult(AgentOutcome.BUDGET_EXCEEDED, 1, null, null, null, new BigDecimal("0.26"), 4, List.of(),
                "Reached maximum budget ($0.25)");

        splitter.split(draftId);

        JsonNode failed = Json.read(row("SELECT payload FROM outbox WHERE edit_ref = ?", PROMPT).get("payload"));
        assertEquals("FAILED", failed.get("split").asText());
        assertTrue(failed.get("splittable").asBoolean(), "✂️ is offered again");
        assertEquals(DraftChoice.SPLITTING, db.transactionReturning(tx -> tasks.split(tx, BOLD, draftId, PROMPT)));
    }

    @Test
    void answerThatIsNotAUsableTopicListFails() {
        String eleven = "[" + "\"topic\",".repeat(10) + "\"topic\"]";
        List<AgentResult> answers = List.of(
                new AgentResult(AgentOutcome.SUCCEEDED, 0, null, null, "Here are the topics", null, null, List.of(), null),
                new AgentResult(AgentOutcome.SUCCEEDED, 0, null, "{\"topics\":[\"fix login\",\"  \"]}", null, null, null, List.of(), null),
                new AgentResult(AgentOutcome.SUCCEEDED, 0, null, "{\"topics\":" + eleven + "}", null, null, null, List.of(), null),
                new AgentResult(AgentOutcome.SUCCEEDED, 0, null, "{\"topics\":[{\"title\":\"fix login\"}]}", null, null, null, List.of(), null));
        for (int i = 0; i < answers.size(); i++) {
            long draftId = splitting(BOLD, MESSAGE, "telegram:100/1" + i);
            agent.result = answers.get(i);

            splitter.split(draftId);

            assertEquals("FAILED", row("SELECT split_state FROM draft WHERE id = ?", draftId).get("split_state"), "answer " + i);
            assertNull(row("SELECT topics FROM draft WHERE id = ?", draftId).get("topics"));
        }
    }

    @Test
    void agentThatCannotStartOrOverrunsItsTimeFails() {
        long unstartable = splitting(BOLD, MESSAGE, "telegram:100/20");
        agent.startFailure = new AgentStartException("cannot start claude: No such file", null);
        splitter.split(unstartable);
        agent.startFailure = null;

        long slow = splitting(BOLD, MESSAGE, "telegram:100/21");
        agent.result = null;
        new Splitter(db, tasks, agent, dir.resolve("splits"), clock, Duration.ofMillis(100)).split(slow);

        assertEquals("FAILED", row("SELECT split_state FROM draft WHERE id = ?", unstartable).get("split_state"));
        assertEquals("FAILED", row("SELECT split_state FROM draft WHERE id = ?", slow).get("split_state"));
        assertTrue(agent.handles.getLast().cancelled.getCount() == 0, "the overrunning agent was stopped");
    }

    @Test
    void onlyTheWriterSplitsAnOpenWholeMessageOnceAtATime() {
        long bolds = draft(BOLD, MESSAGE, "telegram:100/30");
        long created = draft(SARA, "Add make help", "telegram:300/31");
        db.transaction(tx -> tasks.choosePriority(tx, SARA, created, Priority.NORMAL));

        assertEquals(DraftChoice.NOT_REQUESTER, db.transactionReturning(tx -> tasks.split(tx, SARA, bolds, "telegram:300/41")));
        assertEquals(DraftChoice.ALREADY_CREATED, db.transactionReturning(tx -> tasks.split(tx, SARA, created, "telegram:300/42")));
        assertEquals(DraftChoice.NOT_FOUND, db.transactionReturning(tx -> tasks.split(tx, BOLD, 999, PROMPT)));
        assertEquals(DraftChoice.SPLITTING, db.transactionReturning(tx -> tasks.split(tx, BOLD, bolds, PROMPT)));
        assertEquals(DraftChoice.CANNOT_SPLIT, db.transactionReturning(tx -> tasks.split(tx, BOLD, bolds, PROMPT)));
        assertEquals(List.of(bolds), started);
    }

    @Test
    void answerForADraftGivenMeanwhileIsDiscarded() {
        long draftId = splitting(SARA, "Add make help", "telegram:300/50");
        db.transaction(tx -> tasks.choosePriority(tx, SARA, draftId, Priority.NORMAL));
        agent.result = topics("Add make help", "Rename the report");

        splitter.split(draftId);

        assertEquals("CREATED", row("SELECT status FROM draft WHERE id = ?", draftId).get("status"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE edit_ref IS NOT NULL").get("n"), "the prompt shows the task");
    }

    @Test
    void splitCutOffByARestartIsReportedAsFailed() {
        long draftId = splitting(BOLD, MESSAGE, "telegram:100/60");
        int interrupted = db.transactionReturning(tx -> tasks.failInterruptedSplits(tx));

        assertEquals(1, interrupted);

        assertEquals("FAILED", row("SELECT split_state FROM draft WHERE id = ?", draftId).get("split_state"));
        assertEquals(PROMPT, row("SELECT edit_ref FROM outbox WHERE edit_ref IS NOT NULL").get("edit_ref"));
        int again = db.transactionReturning(tx -> tasks.failInterruptedSplits(tx));
        assertEquals(0, again);
    }

    @Test
    void stoppingDispatchStopsRunningSplitsWithoutRecordingThem() throws Exception {
        long draftId = splitting(BOLD, MESSAGE, "telegram:100/70");
        agent.result = null;
        Thread running = Thread.ofVirtual().start(() -> splitter.split(draftId));
        while (agent.handles.isEmpty()) {
            Thread.sleep(5);
        }

        splitter.stop();
        running.join(Duration.ofSeconds(5));

        assertFalse(running.isAlive());
        assertEquals("SPLITTING", row("SELECT split_state FROM draft WHERE id = ?", draftId).get("split_state"),
                "left for the next start to report");
    }

    private long draft(Requester who, String text, String originRef) {
        assertEquals(DraftResult.DRAFTED, db.transactionReturning(tx -> tasks.draft(tx, who, null, text, originRef)));
        return Long.parseLong(row("SELECT id FROM draft WHERE origin_ref = ?", originRef).get("id"));
    }

    private long splitting(Requester who, String text, String originRef) {
        long draftId = draft(who, text, originRef);
        assertEquals(DraftChoice.SPLITTING, db.transactionReturning(tx -> tasks.split(tx, who, draftId, PROMPT)));
        return draftId;
    }

    private void proposed(long draftId, String... topics) {
        assertEquals(DraftChoice.SPLITTING, db.transactionReturning(tx -> tasks.split(tx, BOLD, draftId, PROMPT)));
        db.transaction(tx -> tasks.splitProposed(tx, draftId, List.of(topics)));
    }

    private JsonNode payload(long draftId) {
        return db.transactionReturning(tx -> tasks.draftPayload(tx, draftId)).orElseThrow();
    }

    private static AgentResult topics(String... topics) {
        ArrayNode list = Json.MAPPER.createArrayNode();
        for (String topic : topics) {
            list.add(topic);
        }
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "split-session", Json.object().set("topics", list).toString(), null,
                new BigDecimal("0.015"), 2, List.of(), null);
    }

    private static Config.Project project(String name) {
        return new Config.Project(name, null, "https://github.com/acme/" + name + ".git", null, "main", "claude-code", null, null, List.of(), null);
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }

    /** Answers every run with {@code result}; with none, the run lasts until it is cancelled. */
    private static final class StubAgent implements Agent {

        final List<RunRequest> requests = new CopyOnWriteArrayList<>();
        final List<StubHandle> handles = new CopyOnWriteArrayList<>();
        volatile AgentResult result;
        volatile AgentStartException startFailure;

        @Override
        public RunHandle start(RunRequest request) {
            requests.add(request);
            if (startFailure != null) {
                throw startFailure;
            }
            StubHandle handle = new StubHandle(result);
            handles.add(handle);
            return handle;
        }
    }

    private static final class StubHandle implements RunHandle {

        private final AgentResult result;
        final CountDownLatch cancelled = new CountDownLatch(1);

        StubHandle(AgentResult result) {
            this.result = result;
        }

        @Override
        public ProcessHandle process() {
            return ProcessHandle.current();
        }

        @Override
        public AgentResult await() throws InterruptedException {
            if (result != null) {
                return result;
            }
            cancelled.await();
            return new AgentResult(AgentOutcome.FAILED, 143, null, null, null, null, null, List.of(), "agent exited with code 143");
        }

        @Override
        public void cancel() {
            cancelled.countDown();
        }

        @Override
        public AgentActivity activity() {
            return new AgentActivity(0, null);
        }
    }
}
