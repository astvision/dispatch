package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.Agent;
import dispatch.agent.AgentActivity;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A member's conversation with the bot's assistant: sessions, escalation, proposals and failure (A-1). */
class AssistantTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final String CHAT = "telegram:100";

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TestClock clock;
    private TaskService tasks;
    private StubAgent agent;
    private Assistant assistant;
    private final List<String> typed = new CopyOnWriteArrayList<>();
    private int messages;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-25T10:00:00Z"));
        Config.Project life = new Config.Project("life", "l", "https://github.com/acme/life.git", null, "main", "claude-code",
                null, null, List.of(), null, null, null);
        Groups groups = new Groups(List.of(new Config.Group("home", -100L, List.of(new Config.Member(100, "Bold")), List.of("life"))));
        Projects projects = new Projects(List.of(life), project -> Optional.empty());
        tasks = new TaskService(groups, projects, new ActiveRuns(), clock, () -> { }, () -> { });
        agent = new StubAgent();
        AssistantHome home = new AssistantHome(dir.resolve("assistant"), dbFile, "/usr/bin/java", "/opt/dispatch.jar", "/usr/bin:/bin");
        home.install();
        assistant = new Assistant(db, tasks, new AssistantActions(tasks, groups, projects, clock, "Чи шийд"), groups, projects, agent,
                home, project -> Optional.of(dir.resolve("clones").resolve(project)), clock, Duration.ofSeconds(5), typed::add, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aMessageIsAnsweredUnderItFromTheMembersOwnReadOnlySession() throws Exception {
        agent.answers.add(reply("Одоо #1 ажиллаж байна."));
        create("Fix the login timeout");

        say("юу хийгдэж байна?");

        RunRequest request = agent.requests.getFirst();
        assertEquals(RunKind.ASSISTANT, request.kind());
        assertEquals("haiku", request.model());
        assertFalse(request.resume(), "the first message starts the session");
        assertEquals(dir.resolve("assistant"), request.workdir());
        assertEquals(List.of(dir.resolve("clones/life")), request.readOnlyDirs(), "the member's project clones, to read");
        assertTrue(request.prompt().contains("юу хийгдэж байна?"), request.prompt());
        assertTrue(request.prompt().contains("Fix the login timeout"), "a snapshot of their tasks comes first: " + request.prompt());
        assertTrue(request.environment().get("PATH").startsWith(dir.resolve("assistant/bin/telegram-100").toString()));
        String command = Files.readString(dir.resolve("assistant/bin/telegram-100/dispatch"));
        assertTrue(command.contains("export DISPATCH_ASK_MEMBER='telegram:100'"), "the member's own dispatch ask: " + command);
        assertTrue(Files.readString(dir.resolve("assistant/CLAUDE.md")).contains("You propose, the owner decides"));
        Map<String, String> sent = row("SELECT kind, chat_ref, reply_to_ref, payload FROM outbox WHERE kind = 'ASSISTANT_REPLY'");
        assertEquals(CHAT, sent.get("chat_ref"));
        assertEquals(CHAT + "/1", sent.get("reply_to_ref"));
        assertEquals("Одоо #1 ажиллаж байна.", Json.read(sent.get("payload")).path("reply").asText());
        assertEquals("haiku", row("SELECT model FROM assistant_turn").get("model"));
        assertFalse(typed.isEmpty(), "the chat shows the bot typing while it works");
    }

    @Test
    void theNextMessageResumesTheSessionUntilNewOrTwelveHoursOfSilence() {
        for (int i = 0; i < 4; i++) {
            agent.answers.add(reply("ок"));
        }

        say("сайн уу");
        say("#1 юу болсон бэ?");
        clock.advance(Duration.ofHours(13));
        say("дахиад");
        db.transaction(tx -> assistant.reset(tx, BOLD.ref()));
        say("шинэ сэдэв");

        List<RunRequest> requests = agent.requests;
        assertTrue(requests.get(1).resume());
        assertEquals(requests.getFirst().sessionId(), requests.get(1).sessionId());
        assertFalse(requests.get(2).resume(), "after 12 hours of silence a fresh session");
        assertNotEquals(requests.get(1).sessionId(), requests.get(2).sessionId());
        assertFalse(requests.get(3).resume(), "after /new a fresh session");
        assertNotEquals(requests.get(2).sessionId(), requests.get(3).sessionId());
    }

    @Test
    void anEscalatedTurnIsAnsweredAgainByTheStrongerModelInTheSameSession() {
        agent.answers.add(Json.object().put("reply", "Энэ кодыг сайн уншъя.").put("escalate", true).set("actions", Json.MAPPER.createArrayNode()));
        agent.answers.add(reply("auth.timeout нь config/auth.yaml-д байна."));

        say("login timeout хаана тохируулагддаг вэ?");

        assertEquals(List.of("haiku", "sonnet"), agent.requests.stream().map(RunRequest::model).toList());
        assertTrue(agent.requests.get(1).resume());
        assertEquals(agent.requests.getFirst().sessionId(), agent.requests.get(1).sessionId());
        assertEquals("auth.timeout нь config/auth.yaml-д байна.", replyText());
        assertEquals("2", row("SELECT count(*) AS n FROM assistant_turn").get("n"), "both runs' costs are counted");
    }

    @Test
    void askingToThinkItThroughGoesStraightToTheStrongerModel() {
        agent.answers.add(reply("За."));

        say("Энэ алдааг сайн бодоорой");

        assertEquals(List.of("sonnet"), agent.requests.stream().map(RunRequest::model).toList());
    }

    @Test
    void validProposalsBecomeButtonsAndTheRestNotes() {
        agent.answers.add(Json.object().put("reply", "Санал болгож байна.").set("actions", Json.MAPPER.createArrayNode()
                .add(Json.object().put("type", "draft").put("project", "l").put("text", "Дасгалын тэмдэглэл нэм"))
                .add(Json.object().put("type", "approve").put("task", 99))));

        say("life-д дасгалын тэмдэглэл нэм");

        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'ASSISTANT_REPLY'").get("payload"));
        JsonNode draft = payload.path("actions").get(0);
        assertEquals("life", draft.path("project").asText());
        assertEquals(Long.parseLong(row("SELECT id FROM assistant_action").get("id")), draft.path("id").asLong());
        assertEquals("notFound", payload.path("notes").get(0).path("reason").asText());
        assertEquals("0", row("SELECT count(*) AS n FROM draft").get("n"), "nothing happens before the tap");
    }

    @Test
    void aFailedTurnSaysSoAndOffersTheMessageAsADraft() {
        agent.answers.add(Json.object().put("reply", "no actions list"));
        agent.answers.add(reply("ок"));

        say("life-д дасгалын тэмдэглэл нэм");
        say("дахиад");

        assertTrue(Json.read(row("SELECT payload FROM outbox WHERE kind = 'ASSISTANT_REPLY' ORDER BY id LIMIT 1").get("payload"))
                .path("failed").asBoolean());
        Map<String, String> draft = row("SELECT description, origin_ref FROM draft");
        assertEquals("life-д дасгалын тэмдэглэл нэм", draft.get("description"), "nothing is lost");
        assertEquals(CHAT + "/1", draft.get("origin_ref"));
        assertFalse(agent.requests.get(1).resume(), "a failed turn's session is not resumed");
    }

    @Test
    void aTurnThatTakesTooLongIsStoppedAndCountsAsFailed() {
        assistant = new Assistant(db, tasks, null, new Groups(List.of(new Config.Group("home", -100L,
                List.of(new Config.Member(100, "Bold")), List.of("life")))), new Projects(List.of(), project -> Optional.empty()), agent,
                new AssistantHome(dir.resolve("assistant"), dbFile, "java", "cp", "/bin"), project -> Optional.empty(), clock,
                Duration.ofMillis(200), typed::add, () -> { });
        agent.hang = true;

        say("юу байна?");

        assertTrue(agent.handles.getFirst().cancelled.getCount() == 0, "the agent was stopped");
        assertTrue(Json.read(row("SELECT payload FROM outbox WHERE kind = 'ASSISTANT_REPLY'").get("payload")).path("failed").asBoolean());
    }

    @Test
    void oneMembersMessagesAreAnsweredOneAtATimeInOrder() throws Exception {
        agent.gate = new CountDownLatch(1);
        agent.answers.add(reply("нэг"));
        agent.answers.add(reply("хоёр"));

        Thread first = Thread.ofVirtual().start(() -> assistant.answer(BOLD, "нэг", CHAT + "/1", CHAT));
        assertTrue(agent.started.poll(5, TimeUnit.SECONDS) != null);
        Thread second = Thread.ofVirtual().start(() -> assistant.answer(BOLD, "хоёр", CHAT + "/2", CHAT));

        assertEquals(null, agent.started.poll(300, TimeUnit.MILLISECONDS), "the second waits while the first runs");
        agent.gate.countDown();
        first.join();
        second.join();
        assertEquals(List.of("нэг", "хоёр"), SqlRows.query(dbFile, "SELECT payload FROM outbox WHERE kind = 'ASSISTANT_REPLY' ORDER BY id")
                .stream().map(sent -> Json.read(sent.get("payload")).path("reply").asText()).toList());
        assertTrue(agent.requests.get(1).resume(), "and resumes the session the first one saved");
    }

    private void say(String text) {
        messages++;
        assistant.answer(BOLD, text, CHAT + "/" + messages, CHAT);
    }

    private String replyText() {
        return Json.read(row("SELECT payload FROM outbox WHERE kind = 'ASSISTANT_REPLY'").get("payload")).path("reply").asText();
    }

    private static JsonNode reply(String text) {
        return Json.object().put("reply", text).set("actions", Json.MAPPER.createArrayNode());
    }

    private void create(String title) {
        db.transaction(tx -> tasks.create(tx, BOLD, "life", title, Priority.NORMAL, CHAT + "/900"));
    }

    private Map<String, String> row(String sql) {
        return SqlRows.single(dbFile, sql);
    }

    /** Answers each run with the next scripted structured output; can hold a run open, or never finish one. */
    private static final class StubAgent implements Agent {

        final List<RunRequest> requests = new CopyOnWriteArrayList<>();
        final List<StubHandle> handles = new CopyOnWriteArrayList<>();
        final BlockingQueue<JsonNode> answers = new LinkedBlockingQueue<>();
        final BlockingQueue<RunRequest> started = new LinkedBlockingQueue<>();
        volatile CountDownLatch gate;
        volatile boolean hang;

        @Override
        public RunHandle start(RunRequest request) {
            requests.add(request);
            started.add(request);
            StubHandle handle = new StubHandle(hang ? null : answers.poll(), gate);
            handles.add(handle);
            return handle;
        }
    }

    private static final class StubHandle implements RunHandle {

        private final JsonNode answer;
        private final CountDownLatch gate;
        final CountDownLatch cancelled = new CountDownLatch(1);

        StubHandle(JsonNode answer, CountDownLatch gate) {
            this.answer = answer;
            this.gate = gate;
        }

        @Override
        public ProcessHandle process() {
            return ProcessHandle.current();
        }

        @Override
        public AgentResult await() throws InterruptedException {
            if (answer == null) {
                cancelled.await();
                return new AgentResult(AgentOutcome.FAILED, 143, null, null, null, null, null, List.of(), "agent exited with code 143", null, null);
            }
            if (gate != null) {
                gate.await();
            }
            return new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", answer.toString(), null, new BigDecimal("0.004"), 1, List.of(), null,
                    null, null);
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
