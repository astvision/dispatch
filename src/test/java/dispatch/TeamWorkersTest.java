package dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.agent.Agent;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.telegram.BotApi;
import dispatch.testing.FakeClaude;
import dispatch.testing.FakeGh;
import dispatch.testing.FakeTelegram;
import dispatch.testing.GitFixture;
import dispatch.testing.SqlRows;
import dispatch.worker.WorkerClient;
import dispatch.worker.WorkerConfig;
import dispatch.worker.WorkerLoop;
import dispatch.workspace.Delivery;
import dispatch.workspace.Gh;
import dispatch.workspace.Git;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A team of two, each with their own computer: the bot, the queue and the store on the team machine, the agents, clones
 * and environments on the members' own machines, and only headlines between them (spec: Testing, end to end).
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake claude and gh CLIs are POSIX shell scripts")
class TeamWorkersTest {

    private static final long GROUP = -1001234567890L;
    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path dir;

    private final List<Throwable> fatalErrors = new CopyOnWriteArrayList<>();
    private final List<WorkerLoop> loops = new ArrayList<>();
    private final List<Thread> loopThreads = new ArrayList<>();
    /** sendMessage calls seen by {@link #awaitMessageTo} that matched neither its chat nor fragment yet. */
    private final List<JsonNode> pendingSendMessages = new ArrayList<>();
    /** Each worker's own key, by computer name, so a test can prove another computer's environment never carries it. */
    private final Map<String, String> workerKeys = new HashMap<>();
    /** update_id is monotonic on real Telegram; one shared counter keeps every pushed update that way here too. */
    private long nextUpdateId = 1;
    private FakeTelegram telegram;
    private GitFixture repos;
    private Path claude;
    private Path gh;
    private App app;

    @BeforeEach
    void setUp() throws IOException {
        telegram = FakeTelegram.start();
        repos = GitFixture.create(dir, "alm");
        claude = FakeClaude.install(Files.createDirectories(dir.resolve("bin")));
        gh = FakeGh.install(dir.resolve("bin"));
        app = App.start(teamConfig(), (group, member) -> {
            throw new AssertionError("no one joins in this test");
        }, new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(60)), FakeClaude.environment(),
                Clock.systemUTC(), fatalErrors::add);
    }

    @AfterEach
    void tearDown() {
        loops.forEach(WorkerLoop::stop);
        loopThreads.forEach(thread -> thread.interrupt());
        if (app != null) {
            app.stop();
        }
        telegram.close();
    }

    @Test
    void eachMembersTaskRunsOnTheirOwnComputerAndTheOtherSeesOnlyTheHeadline() throws Exception {
        startWorker(100, "Bold", "bold-laptop", "BOLD-TOKEN");
        startWorker(200, "Ali", "ali-laptop", "ALI-TOKEN");

        // Bold's execution (not his plan: SCENARIO:exec-busy only changes fake-claude's EXECUTE-mode behaviour, see
        // fake-claude.sh) hangs with one real tool call reported, so it is still genuinely RUNNING, with real activity
        // recorded, when Ali's own /status below checks what does and does not cross to her.
        giveTask(100, "Bold", "SCENARIO:exec-busy Fix the login timeout on staging");
        giveTask(200, "Ali", "Fix the signup timeout on staging");

        JsonNode boldsPlan = awaitMessageTo(100, "The user reports that login");
        JsonNode alisPlan = awaitMessageTo(200, "The user reports that login");
        assertTrue(boldsPlan.get("text").asText().contains("#1"), boldsPlan.toString());
        assertTrue(alisPlan.get("text").asText().contains("#2"), alisPlan.toString());

        assertTrue(Files.exists(worktree("bold-laptop", 1)), "Bold's task ran in Bold's own state directory");
        assertTrue(Files.exists(worktree("ali-laptop", 2)), "Ali's task ran in Ali's own state directory");
        assertFalse(Files.exists(worktree("bold-laptop", 2)));
        assertFalse(Files.exists(worktree("ali-laptop", 1)));
        assertFalse(Files.exists(repos.stateDir.resolve("worktrees")),
                "the team machine runs no agent and makes no worktree");

        assertTrue(Files.readString(worktree("bold-laptop", 1).resolve("fake-claude.env")).contains("BOLD-TOKEN"),
                "the agent ran with Bold's own environment");
        assertTrue(Files.readString(worktree("ali-laptop", 2).resolve("fake-claude.env")).contains("ALI-TOKEN"));
        assertFalse(Files.readString(worktree("ali-laptop", 2).resolve("fake-claude.env")).contains("BOLD-TOKEN"),
                "neither token ever reaches the other member's computer");
        assertFalse(Files.readString(worktree("ali-laptop", 2).resolve("fake-claude.env")).contains(workerKeys.get("bold-laptop")),
                "not even the worker key that got Bold's own computer its clone");

        Path db = repos.stateDir.resolve("dispatch.db");
        assertEquals("0", SqlRows.single(db, "SELECT count(*) AS n FROM run WHERE pid IS NOT NULL").get("n"),
                "a remote run records no process on the team machine");
        assertEquals("2", SqlRows.single(db, "SELECT count(DISTINCT worker_id) AS n FROM task").get("n"),
                "each task belongs to its requester's computer");
        List<Map<String, String>> planReadyPerChat = SqlRows.query(db,
                "SELECT chat_ref, count(*) AS n FROM outbox WHERE kind = 'PLAN_READY' GROUP BY chat_ref");
        assertEquals(2, planReadyPerChat.size(), "one PLAN_READY group per member's own chat: " + planReadyPerChat);
        planReadyPerChat.forEach(row -> assertEquals("1", row.get("n"), "a plan delivered to more than one chat: " + row));

        long boldsPlanMessageId = awaitSentMessageId("PLAN_READY", 100);
        telegram.pushUpdate(privateCallback(nextUpdateId(), 100, "Bold", "approve:1:1", boldsPlanMessageId));
        // Bold's own /status, polled until it shows the └ marker, proves his execution is genuinely RUNNING with real
        // recorded activity by the time Ali's own /status is checked below — not merely queued or already finished,
        // which is what let this same check pass vacuously before (the running array was empty either way).
        JsonNode boldsOwnStatus = awaitOwnStatusShowingActivity(100, "Bold");
        assertTrue(boldsOwnStatus.get("text").asText().contains("Bash:"), boldsOwnStatus.toString());

        telegram.pushUpdate(privateCommand(nextUpdateId(), 200, "Ali", "/status"));
        JsonNode status = awaitMessageTo(200, "#1");
        String statusText = status.get("text").asText();
        assertTrue(statusText.contains("#1"), "Ali sees Bold's task number: " + statusText);
        assertFalse(statusText.contains("└"), "Ali must not see Bold's agent's own last action: " + statusText);
        assertFalse(statusText.contains("$"), "Ali must not see a cost figure for a task she does not own: " + statusText);
        assertTrue(fatalErrors.isEmpty(), fatalErrors.toString());
    }

    @Test
    void aTaskGivenWhileTheComputerIsOffSaysSoAndStartsWhenItConnects() throws Exception {
        giveTask(100, "Bold", "Fix the login timeout on staging");

        Path db = repos.stateDir.resolve("dispatch.db");
        awaitRow(db, "SELECT count(*) AS n FROM outbox WHERE kind = 'WORKER_WAITING' AND task_id = 1", "1");
        assertEquals("QUEUED", SqlRows.single(db, "SELECT status FROM run WHERE task_id = 1").get("status"),
                "nothing claims a run whose computer is not there");

        startWorker(100, "Bold", "bold-laptop", "BOLD-TOKEN");

        awaitMessageTo(100, "The user reports that login");
        assertTrue(Files.exists(worktree("bold-laptop", 1)));
        assertTrue(fatalErrors.isEmpty(), fatalErrors.toString());
    }

    private long nextUpdateId() {
        return nextUpdateId++;
    }

    private void startWorker(long memberId, String memberName, String name, String token) throws Exception {
        telegram.pushUpdate(privateCommand(nextUpdateId(), memberId, memberName, "/worker"));
        String code = codeFrom(awaitMessageTo(memberId, "dispatch worker pair"));
        URI team = URI.create("http://127.0.0.1:" + app.workerPort());
        WorkerClient.Paired paired = WorkerClient.pair(HttpClient.newHttpClient(), team, code, name);
        workerKeys.put(name, paired.key());
        WorkerClient client = new WorkerClient(HttpClient.newHttpClient(), team, paired.key());
        WorkerClient.Setup setup = client.setup();

        Path stateDir = Files.createDirectories(dir.resolve("workers").resolve(name));
        Map<String, String> environment = new HashMap<>(FakeClaude.environment());
        environment.put("MEMBER_TOKEN", token);
        Git git = new Git("git", null, Duration.ofSeconds(30));
        Workspaces workspaces = new Workspaces(stateDir, git);
        workspaces.createDirectories();
        Files.createDirectories(stateDir.resolve("repos"));
        GitFixture.sh(dir, "git", "clone", "--quiet", repos.origin.toString(),
                stateDir.resolve("repos").resolve("alm").toString());
        WorkerConfig config = new WorkerConfig(team.toString(), name, 1, claude.toString(), gh.toString(), stateDir,
                Map.of("alm", new WorkerConfig.Project(stateDir.resolve("repos").resolve("alm").toString(), null, null)));
        Map<String, Agent> agents = Map.of("claude-code",
                new ClaudeCodeAgent(claude.toString(), environment, Duration.ofSeconds(10)));
        Delivery delivery = new Delivery(git, new Gh(gh.toString(), null, Duration.ofSeconds(30)), setup.authorName(),
                setup.authorEmail());
        WorkerLoop loop = new WorkerLoop(config, client, agents, workspaces, delivery, Redactor.patternsOnly(),
                new ActiveRuns());
        loops.add(loop);
        loopThreads.add(Thread.ofVirtual().name("worker-" + name).start(loop));
    }

    private Path worktree(String worker, long taskId) {
        return dir.resolve("workers").resolve(worker).resolve("worktrees").resolve(String.valueOf(taskId));
    }

    /** Waits for a single-column query to answer {@code expected}; the store is written by other threads. */
    private static void awaitRow(Path db, String sql, String expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            if (expected.equals(SqlRows.single(db, sql).values().iterator().next())) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(sql + " never answered " + expected);
    }

    private static String codeFrom(JsonNode message) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("dispatch worker pair \\S+ ([A-Z2-9]{8})").matcher(message.get("text").asText());
        if (!matcher.find()) {
            throw new AssertionError("no pairing code in " + message.get("text").asText());
        }
        return matcher.group(1);
    }

    private void giveTask(long memberId, String name, String text) throws InterruptedException {
        telegram.pushUpdate(privateText(nextUpdateId(), memberId, name, text));
        long prompt = awaitSentMessageId("DRAFT_PROMPT", memberId);
        telegram.pushUpdate(privateCallback(nextUpdateId(), memberId, name, "draft:" + draftId(memberId) + ":prio:NORMAL",
                prompt));
    }

    /**
     * Retries {@code /status} in {@code memberId}'s own chat until its running block shows the └ marker: this
     * member's own execution has genuinely reached RUNNING with real recorded activity, not merely claimed or queued.
     */
    private JsonNode awaitOwnStatusShowingActivity(long memberId, String name) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (true) {
            telegram.pushUpdate(privateCommand(nextUpdateId(), memberId, name, "/status"));
            JsonNode status = awaitMessageTo(memberId, "#1");
            if (status.get("text").asText().contains("└")) {
                return status;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("never saw " + name + "'s own agent activity in /status: " + status.get("text").asText());
            }
            Thread.sleep(300);
        }
    }

    /**
     * The next sendMessage call to {@code chatId} whose text contains {@code fragment}. Two members' runs race each
     * other, so their messages can arrive in either order: a call that does not match this member's chat is kept, not
     * dropped, so a later call for that other member still finds it.
     */
    private JsonNode awaitMessageTo(long chatId, String fragment) throws InterruptedException {
        java.util.Iterator<JsonNode> buffered = pendingSendMessages.iterator();
        while (buffered.hasNext()) {
            JsonNode message = buffered.next();
            if (matchesMessage(message, chatId, fragment)) {
                buffered.remove();
                return message;
            }
        }
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            JsonNode message = telegram.awaitRequest("sendMessage", Duration.between(Instant.now(), deadline)).json();
            if (matchesMessage(message, chatId, fragment)) {
                return message;
            }
            pendingSendMessages.add(message);
        }
        throw new AssertionError("no message to " + chatId + " containing " + fragment);
    }

    private static boolean matchesMessage(JsonNode message, long chatId, String fragment) {
        return message.get("chat_id").asLong() == chatId && message.get("text").asText().contains(fragment);
    }

    /** The id of the message an outbox row of {@code kind} sent to {@code chatId}'s private chat. */
    private long awaitSentMessageId(String kind, long chatId) throws InterruptedException {
        Path db = repos.stateDir.resolve("dispatch.db");
        String chatRef = "telegram:" + chatId;
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            List<Map<String, String>> rows = SqlRows.query(db,
                    "SELECT sent_ref FROM outbox WHERE kind = ? AND chat_ref = ? ORDER BY id DESC LIMIT 1", kind, chatRef);
            String sentRef = rows.isEmpty() ? null : rows.getFirst().get("sent_ref");
            if (sentRef != null) {
                return Long.parseLong(sentRef.substring(sentRef.indexOf('/') + 1));
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no sent " + kind + " message to " + chatId);
    }

    /** The most recent draft opened from {@code memberId}'s own private chat. */
    private long draftId(long memberId) {
        return Long.parseLong(SqlRows.single(repos.stateDir.resolve("dispatch.db"),
                "SELECT id FROM draft WHERE chat_ref = ? ORDER BY id DESC LIMIT 1", "telegram:" + memberId).get("id"));
    }

    /** A private message from a named member; its message id is the update id. */
    private static JsonNode privateText(long updateId, long fromId, String name, String text) {
        return Json.read("""
                {"update_id":%d,"message":{"message_id":%d,"from":{"id":%d,"is_bot":false,"first_name":"%s"},
                 "chat":{"id":%d,"type":"private"},"date":1789640000,"text":%s}}"""
                .formatted(updateId, updateId, fromId, name, fromId, Json.MAPPER.valueToTree(text)));
    }

    /** A command in the sender's own private chat with the bot. */
    private static JsonNode privateCommand(long updateId, long fromId, String name, String text) {
        int commandLength = text.split("\\s", 2)[0].length();
        return Json.read("""
                {"update_id":%d,"message":{"message_id":%d,"from":{"id":%d,"is_bot":false,"first_name":"%s"},
                 "chat":{"id":%d,"type":"private"},"date":1789640000,"text":%s,
                 "entities":[{"offset":0,"length":%d,"type":"bot_command"}]}}"""
                .formatted(updateId, updateId, fromId, name, fromId, Json.MAPPER.valueToTree(text), commandLength));
    }

    /** A button pressed on message {@code messageId} in the presser's private chat with the bot. */
    private static JsonNode privateCallback(long updateId, long fromId, String name, String data, long messageId) {
        return Json.read("""
                {"update_id":%d,"callback_query":{"id":"cb-%d","from":{"id":%d,"is_bot":false,"first_name":"%s"},
                 "message":{"message_id":%d,"chat":{"id":%d,"type":"private"},"date":1789640000,"text":"x"},
                 "chat_instance":"1","data":"%s"}}""".formatted(updateId, updateId, fromId, name, messageId, fromId, data));
    }

    private Config teamConfig() {
        return new Config("backend", repos.stateDir,
                new Config.Telegram(List.of(), List.of(new Config.Group("backend", GROUP,
                        List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm")))),
                new Config.Scheduler(2), Config.Worktrees.DEFAULT,
                new Config.Limits(new Config.RunLimits(Duration.ofSeconds(60), new BigDecimal("2")),
                        new Config.RunLimits(Duration.ofSeconds(60), new BigDecimal("10"))),
                Map.of("claude-code", new Config.Agent(claude.toString())),
                List.of(new Config.Project("alm", null, repos.origin.toString(), null, "main", "claude-code", null, null,
                        List.of(), null, null, null)),
                new Config.Delivery("Dispatch (backend)", "dispatch-backend@example.com", gh.toString()),
                new Config.Workers("http://127.0.0.1:0", 0), new Config.Secrets(FakeTelegram.TOKEN, null));
    }
}
