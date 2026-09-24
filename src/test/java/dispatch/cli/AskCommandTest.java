package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.Groups;
import dispatch.core.Projects;
import dispatch.core.RunTransitions;
import dispatch.core.TaskService;
import dispatch.domain.ClaimedRun;
import dispatch.domain.Plan;
import dispatch.domain.PlanQuestion;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the assistant reads about a member's tasks, scoped by the bot and never by the model (A-1, ADR 0020). */
class AskCommandTest {

    private static final Requester ALI = new Requester("telegram:200", "Ali");
    private static final Requester BOLD = new Requester("telegram:100", "Bold");

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
        clock = new TestClock(Instant.parse("2026-09-25T10:00:00Z"));
        Config.Project alm = new Config.Project("alm", null, "https://github.com/acme/alm.git", null, "main", "claude-code",
                null, null, List.of(), null, null, null);
        Groups groups = new Groups(List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm"))));
        tasks = new TaskService(groups, new Projects(List.of(alm), project -> Optional.empty()), new ActiveRuns(), clock,
                () -> { }, () -> { });
        transitions = new RunTransitions(db, clock, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void tasksListsWhatIsActiveAndFinishedWithSomeoneElsesAsAHeadline() {
        long mine = planned(ALI, oneQuestion());
        long theirs = create(BOLD, "Add the export button");

        Answer answer = ask(ALI, new Cli.Ask(null));

        assertEquals(0, answer.exitCode());
        JsonNode awaiting = answer.json().path("active").path("awaitingApproval").get(0);
        assertEquals(mine, awaiting.path("taskId").asLong());
        assertEquals(1, awaiting.path("openQuestions").asInt(), "my own task says what it waits on");
        JsonNode queued = answer.json().path("active").path("queued").get(0);
        assertEquals(theirs, queued.path("taskId").asLong());
        assertFalse(queued.path("mine").asBoolean());
        assertTrue(answer.json().path("finished").isArray());
    }

    @Test
    void oneTaskShowsItsPlanQuestionsAndRunsToItsRequester() {
        long taskId = planned(ALI, oneQuestion());

        Answer answer = ask(ALI, new Cli.Ask(taskId));

        assertEquals(0, answer.exitCode());
        JsonNode plan = answer.json().path("plan");
        assertEquals(1, plan.path("planSeq").asInt());
        assertEquals("Which environments?", plan.path("questions").get(0).path("text").asText());
        assertEquals("[\"staging\",\"prod\"]", plan.path("questions").get(0).path("options").toString());
        assertEquals("PLAN", answer.json().path("runs").get(0).path("kind").asText());
    }

    @Test
    void someoneElsesTaskStopsAtTheHeadline() {
        long theirs = planned(BOLD, oneQuestion());

        Answer answer = ask(ALI, new Cli.Ask(theirs));

        assertEquals(0, answer.exitCode());
        assertTrue(answer.json().path("headline").asBoolean());
        assertFalse(answer.json().has("plan"), "a plan is its requester's alone");
        assertFalse(answer.json().has("runs"));
        assertTrue(answer.json().path("costUsd").isNull());
    }

    @Test
    void aTaskOutsideTheMembersProjectsIsNotFound() {
        long taskId = create(ALI, "Fix the login timeout");

        Answer answer = run(Map.of(AskCommand.MEMBER, ALI.ref(), AskCommand.PROJECTS, "other", AskCommand.DATABASE, dbFile.toString()),
                new Cli.Ask(taskId));

        assertEquals(1, answer.exitCode());
        assertEquals("not_found", answer.json().path("error").asText());
    }

    @Test
    void withoutTheBotsEnvironmentItRefusesToGuessWhoIsAsking() {
        Answer answer = run(Map.of(AskCommand.DATABASE, dbFile.toString()), new Cli.Ask(null));

        assertEquals(2, answer.exitCode());
        assertEquals("not_configured", answer.json().path("error").asText());
    }

    @Test
    void theCommandLineTakesNoOptionThatCouldWidenItsScope() {
        Locations defaults = new Locations(dir.resolve("dispatch.yaml"), dir);

        assertEquals(new Cli.Ask(null), Cli.parse(new String[] {"ask", "tasks"}, defaults));
        assertEquals(new Cli.Ask(12L), Cli.parse(new String[] {"ask", "task", "12"}, defaults));
        assertThrows(CliException.class, () -> Cli.parse(new String[] {"ask", "tasks", "--config", "x.yaml"}, defaults));
        assertThrows(CliException.class, () -> Cli.parse(new String[] {"ask", "task", "twelve"}, defaults));
        assertThrows(CliException.class, () -> Cli.parse(new String[] {"ask"}, defaults));
    }

    private record Answer(int exitCode, JsonNode json) {
    }

    private Answer ask(Requester who, Cli.Ask ask) {
        return run(Map.of(AskCommand.MEMBER, who.ref(), AskCommand.PROJECTS, "alm", AskCommand.DATABASE, dbFile.toString()), ask);
    }

    private static Answer run(Map<String, String> env, Cli.Ask ask) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = AskCommand.run(ask, env, new PrintStream(out, true, StandardCharsets.UTF_8));
        return new Answer(exitCode, Json.read(out.toString(StandardCharsets.UTF_8)));
    }

    private long planned(Requester who, Plan plan) {
        create(who, "Fix the login timeout " + System.nanoTime());
        ClaimedRun run = db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        transitions.planSucceeded(run.taskId(), run.seq(), plan,
                new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", plan.toJson(), null, new BigDecimal("0.1"), 3, List.of(), null, null, null));
        return run.taskId();
    }

    private static Plan oneQuestion() {
        return new Plan("Make the timeout configurable", List.of(), List.of("Read auth.timeout"), List.of(),
                List.of(new PlanQuestion("Which environments?", List.of("staging", "prod"))));
    }

    private long create(Requester who, String title) {
        String origin = who.ref() + "/" + Math.abs(title.hashCode());
        db.transaction(tx -> tasks.create(tx, who, "alm", title, Priority.NORMAL, origin));
        return Long.parseLong(SqlRows.single(dbFile, "SELECT id FROM task WHERE origin_ref = ?", origin).get("id"));
    }
}
