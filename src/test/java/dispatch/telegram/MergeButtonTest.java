package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.Groups;
import dispatch.core.Membership;
import dispatch.core.Merges;
import dispatch.core.Projects;
import dispatch.core.RunTransitions;
import dispatch.core.TaskService;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Outbox;
import dispatch.store.Runs;
import dispatch.testing.FakeTelegram;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import dispatch.workspace.WorkspaceException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Merge button under a delivered task's result: the requester merges its pull request from Telegram. */
class MergeButtonTest {

    private static final long GROUP = -1001234567890L;
    private static final long BOLD = 100;
    private static final String PR = "https://github.com/acme/alm/pull/7";

    @TempDir
    Path dir;

    private FakeTelegram telegram;
    private Path dbFile;
    private Database db;
    private final TestClock clock = new TestClock(Instant.parse("2026-09-28T04:00:00Z"));
    private final Renderer renderer = new Renderer(Renderer.mongolian(), clock, FakeTelegram.BOT_USERNAME);
    private RunTransitions transitions;
    private TaskService tasks;
    private UpdateHandler handler;

    /** What GitHub says of the pull request, and does when asked to merge it. */
    private String state = "OPEN";
    private String refusal;
    private final List<String> merged = new ArrayList<>();
    /** Merges start in the background; a test runs them when it chooses. */
    private final List<Runnable> started = new ArrayList<>();

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
                List.of(new Config.Member(BOLD, "Bold"), new Config.Member(200, "Ali")), List.of("autoland-management"))));
        tasks = new TaskService(groups, projects, new ActiveRuns(), clock, () -> { }, () -> { });
        transitions = new RunTransitions(db, clock, () -> { }, true);
        BotApi api = new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5));
        Merges.PullRequests pullRequests = new Merges.PullRequests() {
            @Override
            public String state(String url) {
                return state;
            }

            @Override
            public void squashMerge(String url) {
                if (refusal != null) {
                    throw new WorkspaceException(refusal);
                }
                merged.add(url);
            }
        };
        Merges merges = new Merges(db, groups, pullRequests, clock, () -> { }, started::add);
        handler = new UpdateHandler(db, tasks, new Membership(groups, (group, member) -> null, clock, () -> { }), groups, projects, api,
                renderer, dispatch.Redactor.patternsOnly(), FakeTelegram.BOT_USERNAME, clock, () -> { }, null, null, null, null, null,
                null, merges);
    }

    @AfterEach
    void tearDown() {
        telegram.close();
        db.close();
    }

    @Test
    void aTapMergesThePullRequestAndItsResultLosesTheButton() throws Exception {
        long taskId = delivered();
        assertTrue(Json.read(row("SELECT payload FROM outbox WHERE kind = 'TASK_COMPLETED'").get("payload")).path("merge").asBoolean(),
                "this bot offers Merge under the result");

        handler.handle(UpdateHandlerTest.privateCallback(900, BOLD, "Bold", "merge:" + taskId));
        assertEquals(renderer.text("callback.mergeStarted"), answer());
        runMerges();

        assertEquals(List.of(PR), merged, "squash-merged on GitHub");
        assertTrue(row("SELECT merged_at FROM task WHERE id = ?", taskId).get("merged_at") != null);
        Map<String, String> said = row("SELECT chat_ref, reply_to_ref FROM outbox WHERE kind = 'TASK_MERGED'");
        assertEquals("telegram:" + BOLD, said.get("chat_ref"));
        assertEquals("telegram:" + BOLD + "/88", said.get("reply_to_ref"), "under the result that offered it");
        JsonNode redrawn = Json.read(row("SELECT payload FROM outbox WHERE kind = 'TASK_COMPLETED' AND edit_ref = ?",
                "telegram:" + BOLD + "/88").get("payload"));
        assertTrue(redrawn.path("merged").asBoolean(), "the result is redrawn as merged, without its button: " + redrawn);
    }

    @Test
    void whenGitHubRefusesItsReasonIsSaidAndTheButtonStays() throws Exception {
        long taskId = delivered();
        refusal = "GraphQL: Required status check \"build\" is expected. (mergePullRequest)";

        handler.handle(UpdateHandlerTest.privateCallback(910, BOLD, "Bold", "merge:" + taskId));
        runMerges();

        JsonNode said = Json.read(row("SELECT payload FROM outbox WHERE kind = 'MERGE_REFUSED'").get("payload"));
        assertTrue(said.get("error").asText().contains("Required status check"), said.toString());
        assertEquals(null, row("SELECT merged_at FROM task WHERE id = ?", taskId).get("merged_at"));
        assertEquals("0", count("SELECT count(*) AS n FROM outbox WHERE edit_ref IS NOT NULL"), "the result keeps its button");
    }

    @Test
    void onlyTheRequesterMerges() throws Exception {
        long taskId = delivered();

        handler.handle(UpdateHandlerTest.privateCallback(920, 200, "Ali", "merge:" + taskId));

        assertEquals(renderer.text("callback.notAllowed"), answer());
        assertTrue(started.isEmpty(), "nothing is merged");
    }

    @Test
    void aSecondTapWhileMergingWaitsAndOneAfterSaysItIsMerged() throws Exception {
        long taskId = delivered();

        handler.handle(UpdateHandlerTest.privateCallback(930, BOLD, "Bold", "merge:" + taskId));
        answer();
        handler.handle(UpdateHandlerTest.privateCallback(931, BOLD, "Bold", "merge:" + taskId));
        assertEquals(renderer.text("callback.mergeRunning"), answer());
        runMerges();
        handler.handle(UpdateHandlerTest.privateCallback(932, BOLD, "Bold", "merge:" + taskId));

        assertEquals(renderer.text("callback.mergeAlready"), answer());
        assertEquals(List.of(PR), merged, "merged once");
    }

    @Test
    void aPullRequestAlreadyMergedOnGitHubIsRecordedWithoutMergingAgain() throws Exception {
        long taskId = delivered();
        state = "MERGED";

        handler.handle(UpdateHandlerTest.privateCallback(940, BOLD, "Bold", "merge:" + taskId));
        runMerges();

        assertTrue(merged.isEmpty());
        assertTrue(row("SELECT merged_at FROM task WHERE id = ?", taskId).get("merged_at") != null, "it is merged, whoever did it");
        assertEquals("1", count("SELECT count(*) AS n FROM outbox WHERE kind = 'TASK_MERGED'"));
    }

    @Test
    void aClosedPullRequestIsNotMerged() throws Exception {
        long taskId = delivered();
        state = "CLOSED";

        handler.handle(UpdateHandlerTest.privateCallback(950, BOLD, "Bold", "merge:" + taskId));
        runMerges();

        assertTrue(merged.isEmpty());
        assertTrue(Json.read(row("SELECT payload FROM outbox WHERE kind = 'MERGE_REFUSED'").get("payload")).path("closed").asBoolean());
        assertEquals(null, row("SELECT merged_at FROM task WHERE id = ?", taskId).get("merged_at"));
    }

    @Test
    void aReplyToAMergedTasksResultBecomesANewTask() throws Exception {
        long taskId = delivered();
        handler.handle(UpdateHandlerTest.privateCallback(960, BOLD, "Bold", "merge:" + taskId));
        runMerges();

        handler.handle(UpdateHandlerTest.message(961, 961, BOLD, "Bold", BOLD, "private", "Also cover the mobile login", """
                {"message_id":88,"from":{"id":1,"is_bot":true,"first_name":"Dispatch"},"chat":{"id":%d,"type":"private"},
                 "date":1789640000,"text":"done"}""".formatted(BOLD)));

        Map<String, String> next = row("SELECT phase, project, description FROM task WHERE id <> ?", taskId);
        assertEquals("PLANNING", next.get("phase"), "planned afresh, from the base its merge moved");
        assertEquals("autoland-management", next.get("project"));
        assertEquals("Also cover the mobile login\n\n↩️ #" + taskId + " " + PR, next.get("description"));
        assertEquals("2", count("SELECT count(*) AS n FROM run WHERE task_id = ?", taskId), "no follow-up run on the merged branch");
    }

    /** A task Bold gave, planned, approved and delivered as {@link #PR}; its result was sent as message 88. */
    private long delivered() {
        Requester bold = new Requester("telegram:" + BOLD, "Bold");
        db.transaction(tx -> tasks.create(tx, bold, "alm", "Fix the login timeout", Priority.NORMAL, "telegram:" + BOLD + "/10"));
        long taskId = Long.parseLong(row("SELECT id FROM task").get("id"));
        db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        Plan plan = new Plan("Make the timeout configurable", List.of(), List.of("Read auth.timeout"), List.of(), List.of());
        transitions.planSucceeded(taskId, 1, plan, result(plan.toJson()));
        db.transaction(tx -> tasks.approve(tx, bold, taskId, 1));
        db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        transitions.agentStarted(taskId, 2, null, null);
        transitions.completed(taskId, 2, result(null), List.of("src/Auth.java"), PR);
        long resultId = Long.parseLong(row("SELECT id FROM outbox WHERE kind = 'TASK_COMPLETED'").get("id"));
        db.transaction(tx -> Outbox.markSent(tx, resultId, 1, "telegram:" + BOLD + "/88", clock.instant()));
        return taskId;
    }

    private static AgentResult result(String structuredOutput) {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", structuredOutput, "Done", new BigDecimal("0.10"), 3, List.of(), null, null,
                null);
    }

    private void runMerges() {
        List<Runnable> due = new ArrayList<>(started);
        started.clear();
        due.forEach(Runnable::run);
    }

    /** The text of the next callback answer. */
    private String answer() throws InterruptedException {
        return telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText();
    }

    private String count(String sql, Object... params) {
        return row(sql, params).get("n");
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
