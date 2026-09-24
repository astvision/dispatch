package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.config.ConfigLoader;
import dispatch.config.GroupWriter;
import dispatch.core.ActiveRuns;
import dispatch.core.GroupLinks;
import dispatch.core.Groups;
import dispatch.core.Membership;
import dispatch.core.Projects;
import dispatch.core.RunTransitions;
import dispatch.core.TaskService;
import dispatch.domain.ClaimedRun;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.store.Database;
import dispatch.store.Outbox;
import dispatch.store.Runs;
import dispatch.testing.FakeTelegram;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateHandlerTest {

    private static final long GROUP = -1001234567890L;
    private static final long MOBILE_GROUP = -1009876543210L;
    private static final String BOT = FakeTelegram.BOT_USERNAME;
    private static final dispatch.domain.Requester BOLD = new dispatch.domain.Requester("telegram:100", "Bold");

    @TempDir
    Path dir;

    private FakeTelegram telegram;
    private Path dbFile;
    private Database db;
    private final TestClock clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
    private final Renderer renderer = new Renderer(Renderer.mongolian(), clock, FakeTelegram.BOT_USERNAME);
    private RunTransitions transitions;
    private TaskService tasks;
    private final List<Long> splitsStarted = new java.util.concurrent.CopyOnWriteArrayList<>();
    private UpdateHandler handler;
    private Membership membership;
    private Groups groups;
    private Projects projects;
    private BotApi api;
    private dispatch.Redactor redactor;
    private Groups personalGroups;
    private TaskService personalTasks;
    private GroupLinks personalLinks;
    private boolean linkFails;

    @BeforeEach
    void setUp() throws Exception {
        telegram = FakeTelegram.start();
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        Config.Project alm = new Config.Project("autoland-management", "alm", "https://github.com/acme/alm.git", null, "main",
                "claude-code", null, null, List.of(), null, null, null);
        Config.Project life = new Config.Project("life", null, "https://github.com/acme/life.git", null, "master",
                "claude-code", null, null, List.of(), null, null, null);
        projects = new Projects(List.of(alm, life), project -> Optional.empty());
        groups = new Groups(List.of(
                new Config.Group("backend", GROUP, List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")),
                        List.of("autoland-management")),
                new Config.Group("mobile", MOBILE_GROUP, List.of(new Config.Member(100, "Bold"), new Config.Member(300, "Sara")),
                        List.of("life"))));
        tasks = new TaskService(groups, projects, new ActiveRuns(), clock, () -> { }, () -> { }, false, splitsStarted::add, false);
        transitions = new RunTransitions(db, clock, () -> { });
        api = new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5));
        membership = new Membership(groups, UpdateHandlerTest::noJoins, clock, () -> { });
        redactor = dispatch.Redactor.patternsOnly();
        handler = new UpdateHandler(db, tasks, membership, groups, projects, api,
                renderer, redactor, FakeTelegram.BOT_USERNAME, clock, () -> { });
    }

    private UpdateHandler handlerWithWorkers(dispatch.worker.WorkerKeys keys) {
        return handlerWithWorkers(keys, groups);
    }

    private UpdateHandler handlerWithWorkers(dispatch.worker.WorkerKeys keys, Groups groupsOverride) {
        Membership membershipOverride = new Membership(groupsOverride, UpdateHandlerTest::noJoins, clock, () -> { });
        return new UpdateHandler(db, tasks, membershipOverride, groupsOverride, projects, api, renderer, redactor, BOT, clock,
                () -> { }, keys, "https://team.example.com", null, null);
    }

    @AfterEach
    void tearDown() {
        telegram.close();
        db.close();
    }

    @Test
    void privateMessageFromAMemberStartsADraftAndStoresTheNextOffset() {
        handler.handle(message(500, 10, 100, "Bold", 100L, "private", "Fix the login timeout\nIt happens on staging", null));

        Map<String, String> draft = row("SELECT * FROM draft");
        assertEquals("Fix the login timeout\nIt happens on staging", draft.get("description"));
        assertEquals("telegram:100/10", draft.get("origin_ref"));
        assertEquals("telegram:100", draft.get("requester_ref"));
        assertEquals("Bold", draft.get("requester_name"));
        assertEquals("DRAFT_PROMPT", row("SELECT kind FROM outbox").get("kind"));
        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"));
        assertEquals("501", row("SELECT value FROM kv WHERE key = 'telegram.offset'").get("value"));
        assertEquals(501, handler.nextOffset());
    }

    @Test
    void photosAndDocumentsGoWithTheDraftToItsTaskUnderSafeNames() throws Exception {
        JsonNode update = message(509, 19, 100, "Bold", 100L, "private", "", null);
        com.fasterxml.jackson.databind.node.ObjectNode sent = (com.fasterxml.jackson.databind.node.ObjectNode) update.get("message");
        sent.remove("text");
        sent.put("caption", "Login fails, see the screenshot");
        sent.putArray("photo").add(Json.read("{\"file_id\":\"small\",\"width\":90,\"height\":60,\"file_size\":900}"))
                .add(Json.read("{\"file_id\":\"large\",\"width\":1280,\"height\":853,\"file_size\":90000}"));
        handler.handle(update);
        JsonNode withDocument = message(510, 20, 100, "Bold", 100L, "private", "", null);
        ((com.fasterxml.jackson.databind.node.ObjectNode) withDocument.get("message")).remove("text");
        ((com.fasterxml.jackson.databind.node.ObjectNode) withDocument.get("message")).put("caption", "And the log")
                .set("document", Json.read("{\"file_id\":\"doc\",\"file_name\":\"../../.ssh/id_rsa\",\"file_size\":25000000}"));
        handler.handle(withDocument);

        Map<String, String> photo = row("SELECT * FROM attachment WHERE draft_id = 1");
        assertEquals("large", photo.get("file_ref"), "the largest size");
        assertEquals("1-photo.jpg", photo.get("name"));
        assertEquals("1-_.._.ssh_id_rsa", row("SELECT name FROM attachment WHERE draft_id = 2").get("name"), "never a path out of its directory");
        JsonNode prompt = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = 'telegram:100/20'").get("payload"));
        assertEquals("1-_.._.ssh_id_rsa", prompt.get("skippedFiles").get(0).asText(), "over 20 MB, and the prompt says so");

        handler.handle(privateCallback(511, 100, "Bold", "draft:1:p:autoland-management"));
        handler.handle(privateCallback(512, 100, "Bold", "draft:1:prio:NORMAL"));

        assertEquals(row("SELECT id FROM task").get("id"), row("SELECT task_id FROM attachment WHERE draft_id = 1").get("task_id"));
    }

    @Test
    void privateTaskCommandNamesTheProjectAndTakesTheRepliedMessageAsText() {
        String forwarded = """
                {"message_id":9,"from":{"id":100,"is_bot":false,"first_name":"Bold"},"chat":{"id":100,"type":"private"},
                 "date":1789640000,"text":"Login fails after 30s on staging"}""";

        handler.handle(message(501, 11, 100, "Bold", 100L, "private", "/task alm also check mobile", forwarded));

        Map<String, String> draft = row("SELECT * FROM draft");
        assertEquals("autoland-management", draft.get("project"));
        assertEquals("Login fails after 30s on staging\n\nalso check mobile", draft.get("description"));
    }

    @Test
    void draftButtonsChooseTheProjectThenThePriorityAndUpdateThePromptInPlace() throws Exception {
        handler.handle(message(502, 12, 100, "Bold", 100L, "private", "Fix the login timeout", null));
        long draftId = Long.parseLong(row("SELECT id FROM draft").get("id"));

        handler.handle(privateCallback(503, 100, "Bold", "draft:" + draftId + ":p:autoland-management"));

        assertEquals(renderer.text("callback.projectChosen"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        JsonNode chosen = telegram.awaitRequest("editMessageText", Duration.ofSeconds(2)).json();
        assertEquals(88, chosen.get("message_id").asLong());
        assertTrue(chosen.get("reply_markup").toString().contains("✓ alm"), chosen.toString());

        handler.handle(privateCallback(504, 100, "Bold", "draft:" + draftId + ":prio:URGENT"));

        Map<String, String> task = row("SELECT * FROM task");
        assertEquals("autoland-management", task.get("project"));
        assertEquals("URGENT", task.get("priority"));
        assertEquals(renderer.text("callback.taskCreated"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        JsonNode created = telegram.awaitRequest("editMessageText", Duration.ofSeconds(2)).json();
        assertTrue(created.get("text").asText().contains("#" + task.get("id")), created.toString());
        assertEquals(0, created.get("reply_markup").get("inline_keyboard").size(), "no buttons once the task exists");
    }

    @Test
    void scissorsStartTheSplitAndRedrawThePromptTheyWerePressedOn() throws Exception {
        handler.handle(message(507, 14, 100, "Bold", 100L, "private", "Fix the login timeout, add make help", null));
        long draftId = Long.parseLong(row("SELECT id FROM draft").get("id"));

        handler.handle(privateCallback(508, 100, "Bold", "draft:" + draftId + ":split:ask"));

        assertEquals(List.of(draftId), splitsStarted);
        assertEquals("telegram:100/88", row("SELECT prompt_ref FROM draft").get("prompt_ref"));
        assertEquals(renderer.text("callback.splitting"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        JsonNode splitting = telegram.awaitRequest("editMessageText", Duration.ofSeconds(2)).json();
        assertEquals(88, splitting.get("message_id").asLong());
        assertTrue(splitting.get("text").asText().contains(renderer.text("draft.splitting")), splitting.toString());
        assertFalse(splitting.get("reply_markup").toString().contains(":split:"), splitting.toString());
    }

    @Test
    void proposalButtonsSplitTheMessageOrKeepItWhole() throws Exception {
        handler.handle(message(509, 15, 100, "Bold", 100L, "private", "Fix the login timeout, add make help", null));
        handler.handle(message(510, 16, 100, "Bold", 100L, "private", "Rename the report, drop old logs", null));
        long first = Long.parseLong(row("SELECT id FROM draft WHERE origin_ref = 'telegram:100/15'").get("id"));
        long second = Long.parseLong(row("SELECT id FROM draft WHERE origin_ref = 'telegram:100/16'").get("id"));
        handler.handle(privateCallback(511, 100, "Bold", "draft:" + first + ":split:ask"));
        handler.handle(privateCallback(512, 100, "Bold", "draft:" + second + ":split:ask"));
        db.transaction(tx -> {
            tasks.splitProposed(tx, first, List.of("Fix the login timeout", "Add make help"));
            tasks.splitProposed(tx, second, List.of("Rename the report", "Drop old logs"));
        });
        telegram.drain("editMessageText");
        telegram.drain("answerCallbackQuery");

        handler.handle(privateCallback(513, 100, "Bold", "draft:" + first + ":split:yes"));
        handler.handle(privateCallback(514, 100, "Bold", "draft:" + second + ":split:no"));
        handler.handle(privateCallback(515, 100, "Bold", "draft:" + second + ":split:maybe"));

        assertEquals("2", row("SELECT count(*) AS n FROM draft WHERE parent_id = ?", first).get("n"));
        assertEquals(renderer.text("callback.split"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        JsonNode split = telegram.awaitRequest("editMessageText", Duration.ofSeconds(2)).json();
        assertTrue(split.get("text").asText().contains("2. Add make help"), split.toString());
        assertEquals(0, split.get("reply_markup").get("inline_keyboard").size());
        assertEquals(renderer.text("callback.keptWhole"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        JsonNode kept = telegram.awaitRequest("editMessageText", Duration.ofSeconds(2)).json();
        assertTrue(kept.get("reply_markup").toString().contains("draft:" + second + ":prio:URGENT"), kept.toString());
        assertFalse(kept.get("reply_markup").toString().contains(":split:"), kept.toString());
        assertEquals(renderer.text("callback.unknown"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
    }

    @Test
    void strangerAsksToJoinAndAnAdminApprovesThemFromTheChat() throws Exception {
        Config.Project alm = new Config.Project("autoland-management", "alm", "https://github.com/acme/alm.git", null, "main",
                "claude-code", null, null, List.of(), null, null, null);
        Projects projects = new Projects(List.of(alm), project -> Optional.empty());
        Groups joinable = new Groups(new Config.Telegram(List.of(100L),
                List.of(new Config.Group("backend", GROUP, List.of(new Config.Member(100, "Bold")), List.of("autoland-management")))));
        dispatch.config.MemberWriter writer = (group, member) -> new Config.Telegram(List.of(100L),
                List.of(new Config.Group("backend", GROUP, List.of(new Config.Member(100, "Bold"), member), List.of("autoland-management"))));
        BotApi api = new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5));
        UpdateHandler joinHandler = new UpdateHandler(db, new TaskService(joinable, projects, new ActiveRuns(), clock, () -> { }, () -> { }),
                new Membership(joinable, writer, clock, () -> { }), joinable, projects, api, renderer, dispatch.Redactor.patternsOnly(),
                FakeTelegram.BOT_USERNAME, clock, () -> { });

        joinHandler.handle(message(620, 40, 555, "Ali", 555L, "private", "Fix the login timeout", null));

        assertEquals("OPEN", row("SELECT status FROM join_request").get("status"));
        assertEquals("0", row("SELECT count(*) AS n FROM draft").get("n"), "no task before they are a member");
        long requestId = Long.parseLong(row("SELECT id FROM join_request").get("id"));

        joinHandler.handle(privateCallback(621, 300, "Sara", "join:" + requestId + ":backend"));
        joinHandler.handle(privateCallback(622, 100, "Bold", "join:" + requestId + ":backend"));

        assertEquals(renderer.text("callback.notAdmin"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        assertEquals(renderer.text("callback.joinApproved"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        JsonNode redrawn = telegram.awaitRequest("editMessageText", Duration.ofSeconds(2)).json();
        assertTrue(redrawn.get("text").asText().contains("Ali") && redrawn.get("text").asText().contains("backend"), redrawn.toString());
        assertEquals(0, redrawn.get("reply_markup").get("inline_keyboard").size());
        assertTrue(joinable.isMember("telegram:555"));

        joinHandler.handle(message(623, 41, 555, "Ali", 555L, "private", "Fix the login timeout", null));

        assertEquals("1", row("SELECT count(*) AS n FROM draft").get("n"), "their messages are tasks now");
    }

    /** A display name Telegram accepts must not fail a join: displayName() cleans it before it ever reaches the config. */
    @Test
    void strangerWhoseNameHasAControlCharacterIsStillApprovedAndTheConfigThenLoads() throws Exception {
        Path configFile = dir.resolve("dispatch.yaml");
        Files.writeString(configFile, """
                team: acme
                stateDir: '%s'

                telegram:
                  admins: [100]
                  groups:
                    - name: backend
                      members:
                        - id: 100
                          name: 'Bold'
                      projects:
                        - alm

                delivery:
                  authorName: 'Dispatch (acme)'
                  authorEmail: 'dispatch@example.com'

                scheduler:
                  maxConcurrentRuns: 1

                limits:
                  plan:
                    timeout: 15m
                    budgetUsd: 2
                  execute:
                    timeout: 60m
                    budgetUsd: 10

                agents:
                  claude-code:
                    command: 'claude'

                projects:
                  - name: alm
                    path: '%s'
                    baseBranch: main
                    agent: claude-code
                """.formatted(dir.resolve("state").toString().replace("'", "''"), dir.resolve("work/alm").toString().replace("'", "''")));
        Map<String, String> env = Map.of("TELEGRAM_BOT_TOKEN", "123456789" + ":AAH-fake-token-for-tests-only-0123456789");
        Config loaded = ConfigLoader.load(configFile, env);
        Projects projects = new Projects(loaded.projects(), project -> Optional.empty());
        Groups joinable = new Groups(loaded.telegram());
        dispatch.config.MemberWriter writer = dispatch.config.MemberWriter.file(configFile, env);
        BotApi api = new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5));
        UpdateHandler joinHandler = new UpdateHandler(db, new TaskService(joinable, projects, new ActiveRuns(), clock, () -> { }, () -> { }),
                new Membership(joinable, writer, clock, () -> { }), joinable, projects, api, renderer, dispatch.Redactor.patternsOnly(),
                FakeTelegram.BOT_USERNAME, clock, () -> { });

        // "\u0007" here is a literal backslash-u-0007, a JSON escape for a Bell character once parsed, not a raw control byte.
        joinHandler.handle(message(620, 40, 555, "Bold Bat\\u0007", 555L, "private", "hi", null));
        assertEquals("OPEN", row("SELECT status FROM join_request").get("status"));
        long requestId = Long.parseLong(row("SELECT id FROM join_request").get("id"));

        joinHandler.handle(privateCallback(622, 100, "Bold", "join:" + requestId + ":backend"));

        assertEquals(renderer.text("callback.joinApproved"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        assertTrue(joinable.isMember("telegram:555"));

        Config.Member added = ConfigLoader.load(configFile, env).telegram().groups().getFirst().members().stream()
                .filter(member -> member.id() == 555).findFirst().orElseThrow();
        assertEquals("Bold Bat", added.name());
    }

    @Test
    void withoutAdminsAStrangersPrivateMessageIsOnlyLogged() {
        handler.handle(message(630, 42, 555, "Ali", 555L, "private", "hello", null));

        assertEquals("0", row("SELECT count(*) AS n FROM join_request").get("n"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox").get("n"));
    }

    private static Config.Telegram noJoins(String group, Config.Member member) {
        throw new AssertionError("no one joins in this test");
    }

    @Test
    void adminOutsideEveryGroupCancelsPrivatelyInsteadOfBeingAskedToJoin() {
        Config.Project alm = new Config.Project("autoland-management", "alm", "https://github.com/acme/alm.git", null, "main",
                "claude-code", null, null, List.of(), null, null, null);
        Projects projects = new Projects(List.of(alm), project -> Optional.empty());
        Groups adminGroups = new Groups(new Config.Telegram(List.of(999L),
                List.of(new Config.Group("backend", GROUP, List.of(new Config.Member(100, "Bold")), List.of("autoland-management")))));
        TaskService adminTasks = new TaskService(adminGroups, projects, new ActiveRuns(), clock, () -> { }, () -> { });
        BotApi api = new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5));
        UpdateHandler adminHandler = new UpdateHandler(db, adminTasks, new Membership(adminGroups, UpdateHandlerTest::noJoins, clock, () -> { }),
                adminGroups, projects, api, renderer, dispatch.Redactor.patternsOnly(), FakeTelegram.BOT_USERNAME, clock, () -> { });
        String origin = "telegram:100/" + System.nanoTime();
        db.transaction(tx -> adminTasks.create(tx, new dispatch.domain.Requester("telegram:100", "Bold"), "alm", "Fix it",
                dispatch.domain.Priority.NORMAL, origin));
        long id = Long.parseLong(row("SELECT id FROM task WHERE origin_ref = ?", origin).get("id"));

        adminHandler.handle(message(700, 70, 999, "Root", 999L, "private", "/cancel " + id, null));

        assertEquals("CANCELLED", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals("0", row("SELECT count(*) AS n FROM join_request").get("n"), "the admin's own cancel never becomes a join request");

        // A non-admin non-member sending the same command still only gets to ask for access, as before this fix.
        adminHandler.handle(message(701, 71, 777, "Eve", 777L, "private", "/cancel " + id, null));

        assertEquals("1", row("SELECT count(*) AS n FROM join_request").get("n"), "a non-admin stranger's /cancel still routes to the join flow");
    }

    @Test
    void draftButtonOfSomeoneElsesMessageIsRefused() throws Exception {
        handler.handle(message(505, 13, 100, "Bold", 100L, "private", "Fix the login timeout", null));
        long draftId = Long.parseLong(row("SELECT id FROM draft").get("id"));

        handler.handle(privateCallback(506, 300, "Sara", "draft:" + draftId + ":p:life"));

        assertEquals(renderer.text("callback.notRequester"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
    }

    @Test
    void commandForAnotherBotIsIgnoredButStillConsumed() {
        handler.handle(message(502, 12, 100, "Bold", GROUP, "supergroup", "/task@other_bot alm Fix it", null));

        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox").get("n"));
        assertEquals(503, handler.nextOffset());
    }

    @Test
    void cancelAndRetryInAGroupArePointedToThePrivateChat() {
        handler.handle(message(507, 17, 100, "Bold", GROUP, "supergroup", "/cancel@" + BOT + " 3", null));
        handler.handle(message(508, 18, 100, "Bold", GROUP, "supergroup", "/retry 1", null));

        assertEquals("0", row("SELECT count(*) AS n FROM draft").get("n"));
        Map<String, String> cancel = row("SELECT * FROM outbox WHERE reply_to_ref = ?", "telegram:" + GROUP + "/17");
        assertEquals("PRIVATE_ONLY", cancel.get("kind"));
        assertEquals(FakeTelegram.BOT_USERNAME, Json.read(cancel.get("payload")).get("bot").asText());
        assertEquals("PRIVATE_ONLY", row("SELECT kind FROM outbox WHERE reply_to_ref = ?", "telegram:" + GROUP + "/18").get("kind"));
    }

    @Test
    void memberMentioningTheBotInTheGroupGetsTheDraftPromptPrivatelyAndTheGroupIsTold() throws Exception {
        handler.handle(mention(520, 30, 100, "Bold", "@" + BOT + " Fix the login timeout", null));

        Map<String, String> draft = row("SELECT * FROM draft");
        assertEquals("Fix the login timeout", draft.get("description"));
        assertEquals("telegram:100", draft.get("requester_ref"));
        assertEquals("telegram:" + GROUP + "/30", draft.get("origin_ref"));
        assertEquals("autoland-management", draft.get("project"), "the group's only project is already chosen");
        Map<String, String> prompt = row("SELECT * FROM outbox");
        assertEquals("DRAFT_PROMPT", prompt.get("kind"));
        assertEquals("telegram:100", prompt.get("chat_ref"));
        assertEquals(null, prompt.get("reply_to_ref"), "never a reply target from another chat");

        OutboxSender sender = sender();
        assertTrue(sender.deliverDue());
        assertTrue(sender.deliverDue());

        JsonNode privately = telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json();
        assertEquals(100, privately.get("chat_id").asLong());
        assertFalse(privately.has("reply_parameters"));
        JsonNode inGroup = telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json();
        assertEquals(GROUP, inGroup.get("chat_id").asLong());
        assertEquals(30, inGroup.get("reply_parameters").get("message_id").asLong());
        assertEquals("✉️ <b>Bold</b>: хувийн чатад илгээлээ.", inGroup.get("text").asText());
        assertFalse(sender.deliverDue(), "nothing else is said");
    }

    @Test
    void taskCommandToTheBotInTheGroupStartsTheSameDraft() {
        handler.handle(message(521, 31, 200, "Ali", GROUP, "supergroup", "/task@" + BOT + " Fix it", null));

        Map<String, String> draft = row("SELECT * FROM draft");
        assertEquals("Fix it", draft.get("description"));
        assertEquals("telegram:200", draft.get("requester_ref"));
        assertEquals("autoland-management", draft.get("project"));
        assertEquals("DRAFT_PROMPT", row("SELECT kind FROM outbox").get("kind"));
    }

    @Test
    void mentionReplyingToAMessageTakesThatMessageAsTheTask() {
        String replied = """
                {"message_id":29,"from":{"id":300,"is_bot":false,"first_name":"Sara"},"chat":{"id":%d,"type":"supergroup"},
                 "date":1789640000,"text":"Login fails after 30s on staging"}""".formatted(GROUP);

        handler.handle(mention(522, 32, 100, "Bold", "@" + BOT, replied));
        handler.handle(mention(523, 33, 200, "Ali", "@" + BOT + " also check mobile", replied));

        assertEquals("Login fails after 30s on staging", row("SELECT description FROM draft WHERE id = 1").get("description"));
        assertEquals("Login fails after 30s on staging\n\nalso check mobile",
                row("SELECT description FROM draft WHERE id = 2").get("description"), "as private /task orders it");
    }

    @Test
    void mentionReplyingToAPhotoTakesThePhotoAlong() {
        String replied = """
                {"message_id":29,"from":{"id":300,"is_bot":false,"first_name":"Sara"},"chat":{"id":%d,"type":"supergroup"},
                 "date":1789640000,"caption":"Login screen is blank",
                 "photo":[{"file_id":"shot","width":1280,"height":853,"file_size":90000}]}""".formatted(GROUP);

        handler.handle(mention(528, 38, 100, "Bold", "@" + BOT, replied));

        assertEquals("Login screen is blank", row("SELECT description FROM draft").get("description"));
        assertEquals("shot", row("SELECT file_ref FROM attachment WHERE draft_id = 1").get("file_ref"));
    }

    @Test
    void mentionReplyingToTheBotsOwnMessageIsNotATask() {
        String botLine = """
                {"message_id":29,"from":{"id":1,"is_bot":true,"first_name":"Dispatch"},"chat":{"id":%d,"type":"supergroup"},
                 "date":1789640000,"text":"✉️ Ali: хувийн чатад илгээлээ."}""".formatted(GROUP);

        handler.handle(mention(529, 39, 100, "Bold", "@" + BOT, botLine));

        assertEquals("0", row("SELECT count(*) AS n FROM draft").get("n"));
        assertEquals("TASK_USAGE", row("SELECT kind FROM outbox").get("kind"));
    }

    @Test
    void taskCommandInAGroupWithSeveralProjectsNamesOneOfThem() {
        Config.Project crm = new Config.Project("crm-backend", "crm", "https://github.com/acme/crm.git", null, "main",
                "claude-code", null, null, List.of(), null, null, null);
        Config.Project alm = projects.all().getFirst();
        Projects two = new Projects(List.of(alm, crm), project -> Optional.empty());
        Groups shared = new Groups(List.of(new Config.Group("backend", GROUP, List.of(new Config.Member(100, "Bold")),
                List.of("autoland-management", "crm-backend"))));
        TaskService sharedTasks = new TaskService(shared, two, new ActiveRuns(), clock, () -> { }, () -> { });
        UpdateHandler sharedHandler = new UpdateHandler(db, sharedTasks, new Membership(shared, UpdateHandlerTest::noJoins, clock, () -> { }),
                shared, two, api, renderer, redactor, BOT, clock, () -> { });

        sharedHandler.handle(message(530, 40, 100, "Bold", GROUP, "supergroup", "/task@" + BOT + " crm Fix it", null));
        sharedHandler.handle(message(531, 41, 100, "Bold", GROUP, "supergroup", "/task@" + BOT + " nope Fix it", null));

        assertEquals("crm-backend", row("SELECT project FROM draft WHERE id = 1").get("project"));
        assertEquals("Fix it", row("SELECT description FROM draft WHERE id = 1").get("description"));
        Map<String, String> unnamed = row("SELECT * FROM draft WHERE id = 2");
        assertEquals(null, unnamed.get("project"), "two projects and none named: the prompt asks");
        assertEquals("nope Fix it", unnamed.get("description"), "an unknown word stays in the text");
    }

    @Test
    void mentionWithNothingToDoIsAnsweredWithUsageInTheGroup() {
        handler.handle(mention(523, 33, 100, "Bold", "@" + BOT, null));
        handler.handle(message(524, 34, 100, "Bold", GROUP, "supergroup", "@someone_else fix it", null));

        assertEquals("0", row("SELECT count(*) AS n FROM draft").get("n"));
        Map<String, String> usage = row("SELECT * FROM outbox");
        assertEquals("TASK_USAGE", usage.get("kind"));
        assertEquals("telegram:" + GROUP, usage.get("chat_ref"));
        assertEquals("telegram:" + GROUP + "/33", usage.get("reply_to_ref"));
        assertEquals("1", row("SELECT count(*) AS n FROM outbox").get("n"), "a message not for the bot is left alone");
    }

    @Test
    void someoneOutsideTheGroupMentioningTheBotIsNotAllowed() {
        handler.handle(mention(525, 35, 300, "Sara", "@" + BOT + " Drop the tables", null));

        assertEquals("0", row("SELECT count(*) AS n FROM draft").get("n"));
        Map<String, String> refused = row("SELECT * FROM outbox");
        assertEquals("NOT_ALLOWED", refused.get("kind"));
        assertEquals("telegram:" + GROUP, refused.get("chat_ref"));
        assertEquals("telegram:" + GROUP + "/35", refused.get("reply_to_ref"));
    }

    @Test
    void theSameMentionHandledTwiceMakesOneDraft() {
        JsonNode update = mention(526, 36, 100, "Bold", "@" + BOT + " Fix the login timeout", null);

        handler.handle(update);
        handler.handle(update);

        assertEquals("1", row("SELECT count(*) AS n FROM draft").get("n"));
        assertEquals("1", row("SELECT count(*) AS n FROM outbox").get("n"));

        db.transaction(tx -> tasks.expireDrafts(tx, clock.instant().plusSeconds(1)));

        Map<String, String> expired = row("SELECT * FROM outbox WHERE kind = 'DRAFT_EXPIRED'");
        assertEquals("telegram:100", expired.get("chat_ref"));
        assertEquals(null, expired.get("reply_to_ref"), "the group message is in another chat");
    }

    @Test
    void whenThePrivateChatRefusesThePromptTheGroupIsAskedToPressStartInstead() throws Exception {
        telegram.refuseChat(100, 403, "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot can't initiate conversation with a user\"}");
        handler.handle(mention(527, 37, 100, "Bold", "Please @" + BOT + " fix the login timeout", null));

        OutboxSender sender = sender();
        assertTrue(sender.deliverDue());
        assertTrue(sender.deliverDue());

        assertEquals(100, telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json().get("chat_id").asLong());
        JsonNode inGroup = telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json();
        assertEquals(GROUP, inGroup.get("chat_id").asLong());
        assertEquals(37, inGroup.get("reply_parameters").get("message_id").asLong());
        assertEquals("⚠️ <b>Bold</b>: эхлээд @" + BOT + "-г нээж Start дарна уу.", inGroup.get("text").asText());
        assertFalse(sender.deliverDue(), "no ✉️ line after the refusal");
        assertEquals("Please fix the login timeout", row("SELECT description FROM draft").get("description"));
    }

    @Test
    void aMemberMentionedByUsernameInTheGroupGetsTheTaskPrivatelyAndTheGroupIsTold() throws Exception {
        db.transaction(tx -> dispatch.store.TelegramUsers.record(tx, 200, "ali_dev", clock.instant()));

        handler.handle(people(540, 50, 100, "Bold", "@Ali_Dev please fix the login timeout", null, "@Ali_Dev"));

        Map<String, String> draft = row("SELECT * FROM draft");
        assertEquals("telegram:200", draft.get("requester_ref"), "the developer is the requester");
        assertEquals("Ali", draft.get("requester_name"));
        assertEquals("telegram:200", draft.get("chat_ref"));
        assertEquals("telegram:" + GROUP + "/50", draft.get("origin_ref"));
        assertEquals("autoland-management", draft.get("project"));
        assertEquals("please fix the login timeout\n\nХүсэлт: Bold", draft.get("description"));

        OutboxSender sender = sender();
        assertTrue(sender.deliverDue());
        assertTrue(sender.deliverDue());
        assertEquals(200, telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json().get("chat_id").asLong());
        JsonNode inGroup = telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json();
        assertEquals(GROUP, inGroup.get("chat_id").asLong());
        assertEquals(50, inGroup.get("reply_parameters").get("message_id").asLong());
        assertEquals("✉️ <b>Ali</b>: хувийн чатад илгээлээ.", inGroup.get("text").asText());
    }

    @Test
    void aTextMentionNeedsNoRecordedUsername() {
        JsonNode update = message(541, 51, 100, "Bold", GROUP, "supergroup", "Ali check the mobile login", null);
        ((com.fasterxml.jackson.databind.node.ObjectNode) update.get("message")).putArray("entities").addObject()
                .put("offset", 0).put("length", 3).put("type", "text_mention")
                .putObject("user").put("id", 200).put("is_bot", false).put("first_name", "Ali");

        handler.handle(update);

        Map<String, String> draft = row("SELECT * FROM draft");
        assertEquals("telegram:200", draft.get("requester_ref"));
        assertEquals("check the mobile login\n\nХүсэлт: Bold", draft.get("description"));
    }

    @Test
    void anUnrecordedUsernameIsAnsweredInTheGroupAndGivesNoTask() throws Exception {
        handler.handle(people(542, 52, 100, "Bold", "@nobody fix it", null, "@nobody"));

        assertEquals("0", row("SELECT count(*) AS n FROM draft").get("n"));
        Map<String, String> line = row("SELECT * FROM outbox");
        assertEquals("UNKNOWN_USERNAME", line.get("kind"));
        assertEquals("telegram:" + GROUP, line.get("chat_ref"));
        assertEquals("telegram:" + GROUP + "/52", line.get("reply_to_ref"));

        assertTrue(sender().deliverDue());
        assertEquals("@nobody-г танихгүй байна — тэр ботод нэг удаа бичих хэрэгтэй.",
                telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json().get("text").asText());
    }

    @Test
    void aMentionedPersonOutsideTheChatsProjectGroupsGetsNothing() {
        // Sara is a member, but of the mobile group only.
        db.transaction(tx -> dispatch.store.TelegramUsers.record(tx, 300, "sara_dev", clock.instant()));

        handler.handle(people(543, 53, 100, "Bold", "@sara_dev fix it", null, "@sara_dev"));

        assertEquals("0", row("SELECT count(*) AS n FROM draft").get("n"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox").get("n"));
    }

    @Test
    void anAuthorOutsideTheChatsProjectGroupsIsIgnoredSilently() {
        db.transaction(tx -> dispatch.store.TelegramUsers.record(tx, 200, "ali_dev", clock.instant()));

        handler.handle(people(544, 54, 300, "Sara", "@ali_dev fix it", null, "@ali_dev"));
        handler.handle(people(545, 55, 999, "Stranger", "@ali_dev fix it", null, "@ali_dev"));

        assertEquals("0", row("SELECT count(*) AS n FROM draft").get("n"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox").get("n"), "no NOT_ALLOWED for ordinary chat");
    }

    @Test
    void mentioningTheBotTooWinsOverMentioningAMember() {
        db.transaction(tx -> dispatch.store.TelegramUsers.record(tx, 200, "ali_dev", clock.instant()));

        handler.handle(people(546, 56, 100, "Bold", "@" + BOT + " @ali_dev fix it", null, "@" + BOT, "@ali_dev"));

        Map<String, String> draft = row("SELECT * FROM draft");
        assertEquals("telegram:100", draft.get("requester_ref"), "G-1b: a task for the author");
        assertEquals("@ali_dev fix it", draft.get("description"));
    }

    @Test
    void aDeveloperMentioningThemselvesGetsNoRequesterLine() {
        db.transaction(tx -> dispatch.store.TelegramUsers.record(tx, 200, "ali_dev", clock.instant()));

        handler.handle(people(547, 57, 200, "Ali", "@ali_dev remember the cache", null, "@ali_dev"));

        assertEquals("remember the cache", row("SELECT description FROM draft").get("description"));
    }

    @Test
    void aMentionReplyingToAMessageTakesItAsThePrivateTaskDoes() {
        db.transaction(tx -> dispatch.store.TelegramUsers.record(tx, 200, "ali_dev", clock.instant()));
        String replied = """
                {"message_id":29,"from":{"id":300,"is_bot":false,"first_name":"Sara"},"chat":{"id":%d,"type":"supergroup"},
                 "date":1789640000,"caption":"Login screen is blank",
                 "photo":[{"file_id":"shot","width":1280,"height":853,"file_size":90000}]}""".formatted(GROUP);

        handler.handle(people(548, 58, 100, "Bold", "@ali_dev this one", replied, "@ali_dev"));

        assertEquals("Login screen is blank\n\nthis one\n\nХүсэлт: Bold", row("SELECT description FROM draft").get("description"));
        assertEquals("shot", row("SELECT file_ref FROM attachment WHERE draft_id = 1").get("file_ref"));
    }

    @Test
    void severalMentionedDevelopersEachGetOneDraftEvenIfHandledTwice() {
        db.transaction(tx -> dispatch.store.TelegramUsers.record(tx, 200, "ali_dev", clock.instant()));
        db.transaction(tx -> dispatch.store.TelegramUsers.record(tx, 100, "bold_dev", clock.instant()));
        JsonNode update = people(549, 59, 100, "Bold", "@ali_dev @bold_dev split the release", null, "@ali_dev", "@bold_dev");

        handler.handle(update);
        handler.handle(update);

        assertEquals("2", row("SELECT count(*) AS n FROM draft").get("n"));
        Map<String, String> ali = row("SELECT * FROM draft WHERE requester_ref = 'telegram:200'");
        assertEquals("telegram:" + GROUP + "/59#200", ali.get("origin_ref"));
        assertEquals("split the release\n\nХүсэлт: Bold", ali.get("description"));
        Map<String, String> bold = row("SELECT * FROM draft WHERE requester_ref = 'telegram:100'");
        assertEquals("telegram:" + GROUP + "/59#100", bold.get("origin_ref"));
        assertEquals("split the release", bold.get("description"));
    }

    @Test
    void aMembersMessagesRecordTheirUsername() {
        handler.handle(message(550, 60, 200, "Ali", 200L, "private", "hello", null));
        handler.handle(message(551, 61, 100, "Bold", GROUP, "supergroup", "morning", null));
        handler.handle(message(552, 62, 999, "Stranger", GROUP, "supergroup", "hi", null));

        assertEquals("200", row("SELECT user_id FROM telegram_user WHERE username = 'ali_dev'").get("user_id"));
        assertEquals("100", row("SELECT user_id FROM telegram_user WHERE username = 'bold_dev'").get("user_id"));
        assertEquals("2", row("SELECT count(*) AS n FROM telegram_user").get("n"), "a non-member is not recorded");
    }

    /** A message in the linked group GROUP with a "mention" entity for each of {@code mentioned}, found in {@code text}. */
    static JsonNode people(long updateId, long messageId, long fromId, String firstName, String text, String replyToJson,
                           String... mentioned) {
        JsonNode update = message(updateId, messageId, fromId, firstName, GROUP, "supergroup", text, replyToJson);
        com.fasterxml.jackson.databind.node.ArrayNode entities =
                ((com.fasterxml.jackson.databind.node.ObjectNode) update.get("message")).putArray("entities");
        for (String name : mentioned) {
            entities.addObject().put("offset", text.indexOf(name)).put("length", name.length()).put("type", "mention");
        }
        return update;
    }

    private OutboxSender sender() {
        return new OutboxSender(db, api, renderer, redactor, new dispatch.core.Signal(), clock, Duration.ofSeconds(1));
    }

    /** A message in the linked group GROUP that mentions the bot by its username. */
    static JsonNode mention(long updateId, long messageId, long fromId, String firstName, String text, String replyToJson) {
        JsonNode update = message(updateId, messageId, fromId, firstName, GROUP, "supergroup", text, replyToJson);
        ((com.fasterxml.jackson.databind.node.ObjectNode) update.get("message")).putArray("entities").addObject()
                .put("offset", text.indexOf("@" + BOT)).put("length", BOT.length() + 1).put("type", "mention");
        return update;
    }

    @Test
    void botLeavesAnyOtherGroup() throws Exception {
        handler.handle(message(504, 14, 100, "Bold", -555L, "supergroup", "/task alm Fix it", null));

        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"));
        assertEquals(-555L, telegram.awaitRequest("leaveChat", Duration.ofSeconds(2)).json().get("chat_id").asLong());
    }

    @Test
    void nonMemberInAPrivateChatIsIgnored() throws Exception {
        handler.handle(message(505, 15, 999, "Sara", 999L, "private", "/status", null));

        assertEquals("0", row("SELECT count(*) AS n FROM outbox").get("n"));
        assertEquals(506, handler.nextOffset());
        Thread.sleep(100);
        assertTrue(telegram.drain("leaveChat").isEmpty());
    }

    @Test
    void memberGetsStatusHistoryAndHelpInTheirPrivateChat() {
        handler.handle(message(560, 60, 100, "Bold", 100L, "private", "/status", null));
        handler.handle(message(561, 61, 100, "Bold", 100L, "private", "/history 7", null));
        handler.handle(message(562, 62, 100, "Bold", 100L, "private", "/start", null));

        Map<String, String> status = row("SELECT * FROM outbox WHERE reply_to_ref = 'telegram:100/60'");
        assertEquals("STATUS", status.get("kind"));
        assertEquals("telegram:100", status.get("chat_ref"));
        assertEquals("TASK_NOT_FOUND", row("SELECT kind FROM outbox WHERE reply_to_ref = 'telegram:100/61'").get("kind"));
        Map<String, String> help = row("SELECT * FROM outbox WHERE reply_to_ref = 'telegram:100/62'");
        assertEquals("HELP", help.get("kind"));
        assertTrue(Json.read(help.get("payload")).get("privateChat").asBoolean());
    }

    @Test
    void manageAnswersAMemberWithAButtonAndSaysSoWhenTheMiniAppIsOff() {
        UpdateHandler withMiniApp = new UpdateHandler(db, tasks, membership, groups, projects, api, renderer, redactor, BOT, clock,
                () -> { }, null, null, "https://dispatch.example.com", null);

        withMiniApp.handle(message(570, 70, 100, "Bold", 100L, "private", "/manage", null));
        handler.handle(message(571, 71, 100, "Bold", 100L, "private", "/manage", null));
        withMiniApp.handle(message(572, 72, 100, "Bold", GROUP, "supergroup", "/manage", null));

        Map<String, String> answered = row("SELECT * FROM outbox WHERE reply_to_ref = 'telegram:100/70'");
        assertEquals("MANAGE", answered.get("kind"));
        assertEquals("https://dispatch.example.com", Json.read(answered.get("payload")).get("url").asText());
        Map<String, String> off = row("SELECT * FROM outbox WHERE reply_to_ref = 'telegram:100/71'");
        assertEquals("MANAGE", off.get("kind"));
        assertTrue(Json.read(off.get("payload")).path("url").isNull(), "no miniApp block, so there is no button to give");
        assertEquals("PRIVATE_ONLY", row("SELECT kind FROM outbox WHERE reply_to_ref = ?", "telegram:" + GROUP + "/72").get("kind"));
    }

    /** Without "manage" in COMMANDS this would be swallowed as a correction of the topic's plan. */
    @Test
    void manageInsideATaskTopicIsACommandAndNotACorrection() {
        UpdateHandler withMiniApp = new UpdateHandler(db, tasks, membership, groups, projects, api, renderer, redactor, BOT, clock,
                () -> { }, null, null, "https://dispatch.example.com", null);
        long taskId = taskAwaitingApproval(List.of());
        db.transaction(tx -> tx.update("UPDATE task SET topic_ref = '56' WHERE id = ?", taskId));

        withMiniApp.handle(topicMessage(573, 73, 100, "Bold", 56, "/manage"));

        assertEquals("MANAGE", row("SELECT kind FROM outbox WHERE reply_to_ref = 'telegram:100/73@56'").get("kind"));
    }

    @Test
    void memberCanCancelFromTheirPrivateChat() {
        task("Fix it");

        handler.handle(message(565, 65, 100, "Bold", 100L, "private", "/cancel 1", null));

        assertEquals("CANCELLED", row("SELECT phase FROM task WHERE id = 1").get("phase"));
    }

    @Test
    void retryIsTakenPrivatelyAndPointedThereFromAGroup() {
        long id = task("Fix it");
        ClaimedRun run = db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        transitions.failed(run.taskId(), run.seq(), dispatch.domain.FailureReason.TIMEOUT, "stopped after 30m", null);

        handler.handle(message(566, 66, 100, "Bold", GROUP, "supergroup", "/retry " + id, null));
        handler.handle(message(567, 67, 100, "Bold", 100L, "private", "/retry " + id, null));

        assertEquals("PRIVATE_ONLY", row("SELECT kind FROM outbox WHERE reply_to_ref = ?", "telegram:" + GROUP + "/66").get("kind"));
        assertEquals("RETRY_QUEUED", row("SELECT kind FROM outbox WHERE reply_to_ref = 'telegram:100/67'").get("kind"));
        assertEquals("PLANNING", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
    }

    @Test
    void planButtonsWorkInTheRequestersPrivateChat() throws Exception {
        long taskId = taskAwaitingApproval(List.of());

        handler.handle(privateCallback(566, 100, "Bold", "approve:" + taskId + ":1"));

        assertEquals("EXECUTING", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        assertEquals(renderer.text("callback.approved"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
    }

    @Test
    void replyToThePlanInThePrivateChatIsACorrection() {
        long taskId = taskAwaitingApproval(List.of());
        long outboxId = Long.parseLong(row("SELECT id FROM outbox WHERE kind = 'PLAN_READY'").get("id"));
        db.transaction(tx -> Outbox.markSent(tx, outboxId, 1, "telegram:100/2000", clock.instant()));

        handler.handle(message(567, 67, 100, "Bold", 100L, "private", "Also cover the mobile login", """
                {"message_id":2000,"from":{"id":1,"is_bot":true,"first_name":"Dispatch"},"chat":{"id":100,"type":"private"},
                 "date":1789640000,"text":"plan"}"""));

        assertEquals("Also cover the mobile login", row("SELECT instruction FROM run WHERE task_id = ? AND seq = 2", taskId).get("instruction"));
        assertEquals("telegram:100", row("SELECT chat_ref FROM outbox WHERE kind = 'CORRECTION_QUEUED'").get("chat_ref"));
    }

    @Test
    void rejectButtonRejectsThePlanAndAnswersTheCallback() throws Exception {
        long taskId = taskAwaitingApproval();

        handler.handle(callback(510, 100, "Bold", "reject:" + taskId + ":1"));

        assertEquals("REJECTED", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        JsonNode answer = telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json();
        assertEquals("cb-510", answer.get("callback_query_id").asText());
        assertEquals(renderer.text("callback.rejected"), answer.get("text").asText());
    }

    @Test
    void buttonOfAnOlderPlanIsAnsweredAsStale() throws Exception {
        long taskId = taskAwaitingApproval();

        handler.handle(callback(511, 100, "Bold", "reject:" + taskId + ":9"));

        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        assertEquals(renderer.text("callback.stale"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
    }

    @Test
    void approveButtonApprovesThePlanAndAnswersTheCallback() throws Exception {
        long taskId = taskAwaitingApproval(List.of());

        handler.handle(callback(512, 100, "Bold", "approve:" + taskId + ":1"));

        assertEquals("EXECUTING", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        assertEquals("Bold", row("SELECT requested_by_name FROM run WHERE task_id = ? AND seq = 2", taskId).get("requested_by_name"));
        assertEquals(renderer.text("callback.approved"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
    }

    @Test
    void buttonPressedByAnotherMemberIsAnsweredThatOnlyTheRequesterDecides() throws Exception {
        long taskId = taskAwaitingApproval(List.of());

        handler.handle(callback(514, 200, "Ali", "approve:" + taskId + ":1"));

        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        assertEquals(renderer.text("callback.notRequester"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
    }

    @Test
    void approveButtonOnAPlanWithOpenQuestionsSaysToAnswerThemFirst() throws Exception {
        long taskId = taskAwaitingApproval(List.of("Which environments?"));

        handler.handle(callback(513, 100, "Bold", "approve:" + taskId + ":1"));

        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        assertEquals(renderer.text("callback.openQuestions"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
    }

    @Test
    void replyToThePlanMessageIsACorrection() {
        long taskId = taskAwaitingApproval(List.of());
        planMessageSentAs(1000);

        handler.handle(message(540, 41, 100, "Bold", GROUP, "supergroup", "Also cover the mobile login", botMessage(1000)));

        assertEquals("PLANNING", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        Map<String, String> run = row("SELECT * FROM run WHERE task_id = ? AND seq = 2", taskId);
        assertEquals("PLAN", run.get("kind"));
        assertEquals("Also cover the mobile login", run.get("instruction"));
        assertEquals("telegram:" + GROUP + "/41", row("SELECT reply_to_ref FROM outbox WHERE kind = 'CORRECTION_QUEUED'").get("reply_to_ref"));
    }

    @Test
    void replyToATasksResultIsAFollowUpAndInItsTopicToo() {
        long taskId = taskAwaitingApproval(List.of());
        db.transaction(tx -> tasks.approve(tx, new dispatch.domain.Requester("telegram:100", "Bold"), taskId, 1));
        ClaimedRun run = db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        db.transaction(tx -> Runs.recordAgentStarted(tx, run.taskId(), run.seq(), clock.instant(), 1L, clock.instant()));
        transitions.completed(run.taskId(), run.seq(), new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", null, "Done", null, 3, List.of(),
                null, null, null), List.of("README.md"), "https://github.com/acme/alm/pull/1");
        long outboxId = Long.parseLong(row("SELECT id FROM outbox WHERE kind = 'TASK_COMPLETED_SHORT'").get("id"));
        db.transaction(tx -> Outbox.markSent(tx, outboxId, 1, "telegram:" + GROUP + "/1100", clock.instant()));

        handler.handle(message(545, 45, 100, "Bold", GROUP, "supergroup", "Also log the value", botMessage(1100)));

        assertEquals("EXECUTING", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        Map<String, String> followUp = row("SELECT * FROM run WHERE task_id = ? AND seq = 3", taskId);
        assertEquals("FOLLOW_UP", followUp.get("cause"));
        assertEquals("Also log the value", followUp.get("instruction"));
        assertEquals("telegram:" + GROUP + "/45", row("SELECT reply_to_ref FROM outbox WHERE kind = 'FOLLOW_UP_QUEUED'").get("reply_to_ref"));

        db.transaction(tx -> tx.update("UPDATE task SET topic_ref = '55', phase = 'COMPLETED' WHERE id = ?", taskId));
        handler.handle(topicMessage(546, 105, 100, "Bold", 55, "and the tablet too"));

        assertEquals("and the tablet too", row("SELECT instruction FROM run WHERE task_id = ? AND seq = 4", taskId).get("instruction"));
    }

    @Test
    void groupMemberReplyingToAnotherMembersOutcomeIsRefusedAsNotTheRequester() {
        long taskId = taskAwaitingApproval(List.of());
        db.transaction(tx -> tasks.approve(tx, new dispatch.domain.Requester("telegram:100", "Bold"), taskId, 1));
        ClaimedRun run = db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        db.transaction(tx -> Runs.recordAgentStarted(tx, run.taskId(), run.seq(), clock.instant(), 1L, clock.instant()));
        transitions.completed(run.taskId(), run.seq(), new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", null, "Done", null, 3, List.of(),
                null, null, null), List.of("README.md"), "https://github.com/acme/alm/pull/1");
        long outboxId = Long.parseLong(row("SELECT id FROM outbox WHERE kind = 'TASK_COMPLETED_SHORT'").get("id"));
        db.transaction(tx -> Outbox.markSent(tx, outboxId, 1, "telegram:" + GROUP + "/1100", clock.instant()));
        String runsBefore = row("SELECT count(*) AS n FROM run WHERE task_id = ?", taskId).get("n");

        // Ali is in the same group as Bold, the requester, but did not give this task.
        handler.handle(message(550, 50, 200, "Ali", GROUP, "supergroup", "Nice work, also log the value", botMessage(1100)));

        Map<String, String> refused = row("SELECT * FROM outbox WHERE reply_to_ref = ?", "telegram:" + GROUP + "/50");
        assertEquals("FOLLOW_UP_REFUSED", refused.get("kind"));
        assertEquals("requester", Json.read(refused.get("payload")).get("reason").asText());
        assertEquals(runsBefore, row("SELECT count(*) AS n FROM run WHERE task_id = ?", taskId).get("n"), "no new run for a refused follow-up");
    }

    @Test
    void replyThatMerelyStartsLikeACommandIsStillACorrection() {
        long taskId = taskAwaitingApproval(List.of());
        planMessageSentAs(1000);

        handler.handle(message(541, 42, 100, "Bold", GROUP, "supergroup", "/api/login fails the same way", botMessage(1000)));

        assertEquals("/api/login fails the same way", row("SELECT instruction FROM run WHERE task_id = ? AND seq = 2", taskId).get("instruction"));
    }

    @Test
    void repliesToOtherBotMessagesAndCommandsForOtherBotsAreNotCorrections() {
        long taskId = taskAwaitingApproval(List.of());
        planMessageSentAs(1000);
        long messagesBefore = Long.parseLong(row("SELECT count(*) AS n FROM outbox").get("n"));
        String ackRef = "telegram:" + GROUP + "/999";
        db.transaction(tx -> tx.update("UPDATE outbox SET status = 'SENT', sent_ref = ? WHERE kind = 'TASK_QUEUED'", ackRef));

        handler.handle(message(542, 43, 200, "Ali", GROUP, "supergroup", "thanks", botMessage(999)));
        handler.handle(message(543, 44, 200, "Ali", GROUP, "supergroup", "/start@other_bot", botMessage(1000)));
        handler.handle(message(544, 45, 200, "Ali", GROUP, "supergroup", "just chatting", null));

        assertEquals("1", row("SELECT count(*) AS n FROM run WHERE task_id = ?", taskId).get("n"));
        assertEquals(messagesBefore, Long.parseLong(row("SELECT count(*) AS n FROM outbox").get("n")));
        assertEquals(545, handler.nextOffset());
    }

    @Test
    void statusAndHistoryAnswerAnyoneInTheGroup() {
        handler.handle(message(550, 50, 999, "Sara", GROUP, "supergroup", "/status", null));
        handler.handle(message(551, 51, 999, "Sara", GROUP, "supergroup", "/history", null));
        handler.handle(message(552, 52, 999, "Sara", GROUP, "supergroup", "/history@" + FakeTelegram.BOT_USERNAME + " 7", null));

        assertEquals("STATUS", row("SELECT kind FROM outbox WHERE reply_to_ref = ?", "telegram:" + GROUP + "/50").get("kind"));
        assertEquals("HISTORY", row("SELECT kind FROM outbox WHERE reply_to_ref = ?", "telegram:" + GROUP + "/51").get("kind"));
        assertEquals("TASK_NOT_FOUND", row("SELECT kind FROM outbox WHERE reply_to_ref = ?", "telegram:" + GROUP + "/52").get("kind"));
    }

    @Test
    void buttonDataFromAnotherPersonsPrivateChatIsRefused() throws Exception {
        long taskId = taskAwaitingApproval(List.of());
        JsonNode forged = Json.read(privateCallback(568, 100, "Bold", "approve:" + taskId + ":1").toString()
                .replace("\"from\":{\"id\":100", "\"from\":{\"id\":200"));

        handler.handle(forged);

        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        assertEquals(renderer.text("callback.unknown"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
    }

    @Test
    void eachConfiguredGroupIsServedAndSeesOnlyItsOwnProjects() throws Exception {
        long backendTask = taskAwaitingApproval();

        handler.handle(message(570, 70, 300, "Sara", MOBILE_GROUP, "supergroup", "/status", null));
        handler.handle(message(571, 71, 999, "Stranger", GROUP, "supergroup", "/status", null));

        JsonNode mobile = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = ?", "telegram:" + MOBILE_GROUP + "/70").get("payload"));
        assertEquals(0, mobile.get("awaitingApproval").size(), "backend's task is not shown in the mobile group");
        JsonNode backend = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = ?", "telegram:" + GROUP + "/71").get("payload"));
        assertEquals(backendTask, backend.get("awaitingApproval").get(0).get("taskId").asLong());
        Thread.sleep(100);
        assertTrue(telegram.drain("leaveChat").isEmpty(), "configured groups are never left");
    }

    @Test
    void privateCommandsCoverEveryGroupOfTheMemberAndNoOthers() {
        long backendTask = taskAwaitingApproval();

        handler.handle(message(572, 72, 100, "Bold", 100L, "private", "/status", null));
        handler.handle(message(573, 73, 300, "Sara", 300L, "private", "/status", null));
        handler.handle(message(574, 74, 300, "Sara", 300L, "private", "/help", null));

        JsonNode bold = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = 'telegram:100/72'").get("payload"));
        assertEquals(backendTask, bold.get("awaitingApproval").get(0).get("taskId").asLong());
        JsonNode sara = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = 'telegram:300/73'").get("payload"));
        assertEquals(0, sara.get("awaitingApproval").size());
        JsonNode help = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = 'telegram:300/74'").get("payload"));
        assertEquals(1, help.get("projects").size());
        assertEquals("life", help.get("projects").get(0).get("name").asText());
    }

    @Test
    void priorityButtonChangesThePriorityAndUpdatesTheStatusMessageInPlace() throws Exception {
        task("Fix the login timeout");

        handler.handle(privateCallback(581, 100, "Bold", "prio:1:URGENT"));

        assertEquals("URGENT", row("SELECT priority FROM task WHERE id = 1").get("priority"));
        assertEquals(renderer.text("callback.priorityChanged"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        JsonNode edit = telegram.awaitRequest("editMessageText", Duration.ofSeconds(2)).json();
        assertEquals(100, edit.get("chat_id").asLong());
        assertEquals(88, edit.get("message_id").asLong(), "the status message the button belongs to");
        assertTrue(edit.get("text").asText().contains("🔴"), edit.toString());
        assertEquals("prio:1:URGENT", edit.get("reply_markup").get("inline_keyboard").get(0).get(0).get("callback_data").asText());
    }

    @Test
    void priorityButtonOfSomeoneElsesTaskIsRefused() throws Exception {
        task("Fix the login timeout");

        handler.handle(privateCallback(583, 200, "Ali", "prio:1:LOW"));

        assertEquals("NORMAL", row("SELECT priority FROM task WHERE id = 1").get("priority"));
        assertEquals(renderer.text("callback.notRequester"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
    }

    @Test
    void statsArePostedAndTheirButtonsRedrawTheSameMessage() throws Exception {
        handler.handle(message(590, 90, 100, "Bold", 100L, "private", "/stats", null));
        handler.handle(message(591, 91, 300, "Sara", MOBILE_GROUP, "supergroup", "/stats", null));

        assertEquals("me", Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = 'telegram:100/90'").get("payload"))
                .get("view").asText());
        assertEquals("group:mobile", Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = ?", "telegram:" + MOBILE_GROUP + "/91")
                .get("payload")).get("view").asText());

        handler.handle(privateCallback(592, 100, "Bold", "stats:week:group:backend"));

        telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2));
        JsonNode edit = telegram.awaitRequest("editMessageText", Duration.ofSeconds(2)).json();
        assertEquals(88, edit.get("message_id").asLong());
        assertTrue(edit.get("reply_markup").toString().contains("✓ backend"), edit.toString());
    }

    @Test
    void statsButtonForAGroupTheViewerIsNotInIsRefused() throws Exception {
        handler.handle(privateCallback(593, 300, "Sara", "stats:all:group:backend"));

        assertEquals(renderer.text("callback.unknown"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        Thread.sleep(100);
        assertTrue(telegram.drain("editMessageText").isEmpty());
    }

    @Test
    void anythingWrittenInsideATasksTopicIsACorrectionOfItsPlan() {
        long taskId = taskAwaitingApproval(List.of());
        db.transaction(tx -> tx.update("UPDATE task SET topic_ref = '55' WHERE id = ?", taskId));

        handler.handle(topicMessage(600, 101, 100, "Bold", 55, "Also cover the mobile login"));

        assertEquals("Also cover the mobile login", row("SELECT instruction FROM run WHERE task_id = ? AND seq = 2", taskId).get("instruction"));
        assertEquals("telegram:100/101@55", row("SELECT reply_to_ref FROM outbox WHERE kind = 'CORRECTION_QUEUED'").get("reply_to_ref"));
        assertEquals("0", row("SELECT count(*) AS n FROM draft").get("n"), "not a new task");
    }

    @Test
    void messageInTheTopicOfATaskThatIsNoLongerWaitingIsAnsweredWithItsPhase() {
        long taskId = taskAwaitingApproval(List.of());
        db.transaction(tx -> tx.update("UPDATE task SET topic_ref = '55', phase = 'EXECUTING' WHERE id = ?", taskId));

        handler.handle(topicMessage(601, 102, 100, "Bold", 55, "and the tablet too"));

        Map<String, String> refused = row("SELECT * FROM outbox WHERE kind = 'CORRECTION_REFUSED'");
        assertEquals("phase", Json.read(refused.get("payload")).get("reason").asText());
        assertEquals("0", row("SELECT count(*) AS n FROM draft").get("n"));
    }

    @Test
    void commandsInsideATopicAreAnsweredThereAndOtherTopicsStartTasks() {
        long taskId = taskAwaitingApproval(List.of());
        db.transaction(tx -> tx.update("UPDATE task SET topic_ref = '55' WHERE id = ?", taskId));

        handler.handle(topicMessage(602, 103, 100, "Bold", 55, "/status"));
        handler.handle(topicMessage(603, 104, 100, "Bold", 77, "Rename the report"));

        assertEquals("telegram:100/103@55", row("SELECT reply_to_ref FROM outbox WHERE kind = 'STATUS'").get("reply_to_ref"));
        assertEquals("telegram:100/104@77", row("SELECT origin_ref FROM draft").get("origin_ref"), "a topic of no task is like General");
    }

    @Test
    void projectsListsWhatTheChatCanUse() {
        handler.handle(message(531, 31, 999, "Sara", GROUP, "supergroup", "/projects", null));
        handler.handle(message(532, 32, 100, "Bold", 100L, "private", "/projects", null));

        JsonNode group = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = ?", "telegram:" + GROUP + "/31").get("payload"));
        assertEquals(1, group.get("projects").size(), "a group sees only its own projects");
        assertEquals("main", group.get("projects").get(0).get("baseBranch").asText());
        JsonNode mine = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = 'telegram:100/32'").get("payload"));
        assertEquals(2, mine.get("projects").size(), "a member sees the projects of all their groups");
    }

    @Test
    void helpListsTheProjects() {
        handler.handle(message(530, 30, 999, "Sara", GROUP, "supergroup", "/help", null));

        Map<String, String> reply = row("SELECT * FROM outbox");
        assertEquals("HELP", reply.get("kind"));
        JsonNode payload = Json.read(reply.get("payload"));
        assertEquals("alm", payload.get("projects").get(0).get("alias").asText());
        assertEquals(FakeTelegram.BOT_USERNAME, payload.get("bot").asText());
        assertTrue(renderer.render(dispatch.domain.OutboxKind.HELP, payload).html().contains("@" + FakeTelegram.BOT_USERNAME));
    }

    @Test
    void workerGivesTheMemberAPairingCodeAndListsTheirComputers() {
        dispatch.worker.WorkerKeys keys = new dispatch.worker.WorkerKeys(db, clock);
        long existing = keys.pair(keys.newCode(BOLD), "ann-laptop").orElseThrow().workerId();
        handlerWithWorkers(keys).handle(privateCommand(1, 100, "Bold", "/worker"));

        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'WORKER_PAIRING'").get("payload"));
        assertEquals(8, payload.get("code").asText().length());
        assertEquals("https://team.example.com", payload.get("url").asText());
        assertEquals(existing, payload.get("workers").get(0).get("id").asLong());
        assertEquals("ann-laptop", payload.get("workers").get(0).get("name").asText());
    }

    @Test
    void workerRevokeTakesOneComputerAwayAndOnlyItsOwnerOrAnAdminMay() {
        dispatch.worker.WorkerKeys keys = new dispatch.worker.WorkerKeys(db, clock);
        dispatch.worker.WorkerKeys.NewKey bold = keys.pair(keys.newCode(BOLD), "ann-laptop").orElseThrow();
        UpdateHandler handler = handlerWithWorkers(keys);

        handler.handle(privateCommand(1, 200, "Ali", "/worker revoke " + bold.workerId()));
        assertTrue(keys.authenticate(bold.key()).isPresent(), "another member cannot revoke it");
        assertEquals("false", Json.read(row("SELECT payload FROM outbox WHERE kind = 'WORKER_REVOKED'").get("payload"))
                .get("found").asText());

        handler.handle(privateCommand(2, 100, "Bold", "/worker revoke " + bold.workerId()));
        assertTrue(keys.authenticate(bold.key()).isEmpty());
    }

    @Test
    void workerIsRefusedToSomeoneWhoIsNotAMember() {
        handlerWithWorkers(new dispatch.worker.WorkerKeys(db, clock)).handle(privateCommand(1, 999, "Stranger", "/worker"));

        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'WORKER_PAIRING'").get("n"));
        assertEquals("1", row("SELECT count(*) AS n FROM outbox WHERE kind = 'NOT_ALLOWED'").get("n"));
        assertEquals("0", row("SELECT count(*) AS n FROM pairing_code").get("n"), "no code is even made");
    }

    @Test
    void workerInAPersonalDispatchSaysThereIsNothingToPair() {
        handler.handle(privateCommand(1, 100, "Bold", "/worker"));

        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'WORKER_PAIRING'").get("payload"));
        assertTrue(payload.get("personal").asBoolean(), payload.toString());
        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"), "and it is not taken for a task");
    }

    @Test
    void anAdminRevokesAnotherMembersWorker() {
        Groups adminGroups = new Groups(new Config.Telegram(List.of(100L), groups.all()));
        dispatch.worker.WorkerKeys keys = new dispatch.worker.WorkerKeys(db, clock);
        dispatch.worker.WorkerKeys.NewKey ali =
                keys.pair(keys.newCode(new dispatch.domain.Requester("telegram:200", "Ali")), "ali-laptop").orElseThrow();

        handlerWithWorkers(keys, adminGroups).handle(privateCommand(1, 100, "Bold", "/worker revoke " + ali.workerId()));

        assertEquals("true", Json.read(row("SELECT payload FROM outbox WHERE kind = 'WORKER_REVOKED'").get("payload"))
                .get("found").asText());
        assertTrue(keys.authenticate(ali.key()).isEmpty(), "an admin may revoke anyone's computer");
    }

    @Test
    void workerInAGroupChatIsPrivateOnlyAndMakesNoCode() {
        handlerWithWorkers(new dispatch.worker.WorkerKeys(db, clock))
                .handle(message(1, 1, 100, "Bold", GROUP, "supergroup", "/worker", null));

        assertEquals("PRIVATE_ONLY", row("SELECT kind FROM outbox").get("kind"));
        assertEquals("0", row("SELECT count(*) AS n FROM pairing_code").get("n"));
    }

    @Test
    void workerRevokeWithoutAValidNumberAnswersWithUsage() {
        handlerWithWorkers(new dispatch.worker.WorkerKeys(db, clock)).handle(privateCommand(1, 100, "Bold", "/worker revoke abc"));

        assertEquals("WORKER_USAGE", row("SELECT kind FROM outbox").get("kind"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'WORKER_REVOKED'").get("n"));
    }

    @Test
    void workerFromANonMemberJoinsWhenAdminsAreConfiguredInsteadOfBeingRefused() {
        Groups adminGroups = new Groups(new Config.Telegram(List.of(999L), groups.all()));

        handlerWithWorkers(new dispatch.worker.WorkerKeys(db, clock), adminGroups).handle(privateCommand(1, 777, "Eve", "/worker"));

        assertEquals("1", row("SELECT count(*) AS n FROM join_request").get("n"), "the join flow (ADR 0015) takes it, not a refusal");
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'NOT_ALLOWED'").get("n"));
    }

    private static final long NEW_GROUP = -4883391545L;

    @Test
    void theOwnerAddingTheBotIsAskedWhichProjectAndTheBotStays() throws Exception {
        UpdateHandler handler = personalHandler();

        handler.handle(myChatMember(700, 100, -4883391545L, "note", "member"));

        JsonNode prompt = telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json();
        assertEquals(100, prompt.get("chat_id").asLong(), "privately, to whoever added it");
        assertTrue(prompt.get("text").asText().contains("note"));
        assertEquals("link:-4883391545:0", prompt.at("/reply_markup/inline_keyboard/0/0/callback_data").asText());
        Thread.sleep(100);
        assertTrue(telegram.drain("leaveChat").isEmpty());
    }

    @Test
    void someoneElseAddingTheBotMakesItLeave() throws Exception {
        personalHandler().handle(myChatMember(701, 999, -4883391545L, "note", "member"));
        assertEquals(-4883391545L, telegram.awaitRequest("leaveChat", Duration.ofSeconds(2)).json().get("chat_id").asLong());
    }

    @Test
    void theOwnersCommandInAGroupTheBotIsAlreadyInAlsoAsks() throws Exception {
        personalHandler().handle(message(702, 30, 100, "Bold", -4883391545L, "group", "/status@" + BOT, null));
        assertEquals(100, telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json().get("chat_id").asLong());
    }

    @Test
    void tappingTheProjectLinksItAndTheNextTaskIsAnnouncedThereWithoutARestart() throws Exception {
        UpdateHandler handler = personalHandler();
        handler.handle(myChatMember(703, 100, -4883391545L, "note", "member"));
        long promptId = telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json().path("message_id").asLong(1);

        handler.handle(callback(704, 100, "Bold", 100L, promptId, "link:-4883391545:0"));

        assertTrue(personalGroups.isGroupChat("telegram:-4883391545"), "the running groups, not only the file");
        assertEquals("GROUP_LINKED", row("SELECT kind FROM outbox WHERE chat_ref = 'telegram:-4883391545'").get("kind"));
        db.transaction(tx -> personalTasks.create(tx, BOLD, "life", "Fix it", Priority.NORMAL, "telegram:100/77"));
        assertEquals("1", row("SELECT count(*) AS n FROM outbox WHERE kind = 'TASK_QUEUED' AND chat_ref = 'telegram:-4883391545'").get("n"));
    }

    @Test
    void aStaleOrForeignButtonWritesNothing() {
        UpdateHandler handler = personalHandler();
        handler.handle(callback(705, 100, "Bold", 100L, 1, "link:-4883391545:0"));   // no prompt open
        handler.handle(myChatMember(706, 100, -4883391545L, "note", "member"));
        handler.handle(callback(707, 999, "Eve", 999L, 1, "link:-4883391545:0"));    // not the owner

        assertFalse(personalGroups.isGroupChat("telegram:-4883391545"));
    }

    @Test
    void aLongProjectNameStillFitsInTheButton() throws Exception {
        UpdateHandler handler = personalHandlerWithProject("x".repeat(60));
        handler.handle(myChatMember(708, 100, -4883391545L, "note", "member"));
        String data = telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json()
                .at("/reply_markup/inline_keyboard/0/0/callback_data").asText();
        assertTrue(data.getBytes(StandardCharsets.UTF_8).length <= 64, data);
    }

    @Test
    void aRefusedPromptMakesTheBotLeave() throws Exception {
        telegram.failNext("sendMessage", 403, "Forbidden: bot can't initiate conversation with a user");
        personalHandler().handle(myChatMember(709, 100, -4883391545L, "note", "member"));
        assertEquals(-4883391545L, telegram.awaitRequest("leaveChat", Duration.ofSeconds(2)).json().get("chat_id").asLong());
    }

    @Test
    void aLinkedGroupThatBecomesASupergroupKeepsItsLink() {
        UpdateHandler handler = personalHandler();
        handler.handle(myChatMember(710, 100, -4883391545L, "note", "member"));
        handler.handle(callback(711, 100, "Bold", 100L, 1, "link:-4883391545:0"));

        handler.handle(migration(712, -4883391545L, -1004883391545L));

        assertTrue(personalGroups.isGroupChat("telegram:-1004883391545"));
    }

    /** Once the prompt was refused and the bot left, adding it again (after pressing Start) must ask again. */
    @Test
    void afterARefusedPromptAddingTheBotAgainAsksAgain() throws Exception {
        UpdateHandler handler = personalHandler();
        telegram.failNext("sendMessage", 403, "Forbidden: bot can't initiate conversation with a user");
        handler.handle(myChatMember(720, 100, NEW_GROUP, "note", "member"));
        telegram.awaitRequest("leaveChat", Duration.ofSeconds(2));
        telegram.drain("sendMessage");

        handler.handle(myChatMember(721, 100, NEW_GROUP, "note", "member"));

        assertEquals(100, telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json().get("chat_id").asLong());
    }

    /** Telegram also sends a "new members" service message from the owner; it neither asks twice nor makes the bot leave. */
    @Test
    void theOwnersServiceMessageAboutAddingTheBotAsksOnceAndStays() throws Exception {
        UpdateHandler handler = personalHandler();
        JsonNode added = message(722, 32, 100, "Bold", NEW_GROUP, "group", "", null);
        ((com.fasterxml.jackson.databind.node.ObjectNode) added.get("message")).remove("text");
        ((com.fasterxml.jackson.databind.node.ObjectNode) added.get("message")).putArray("new_chat_members")
                .addObject().put("id", 1).put("is_bot", true).put("first_name", "Dispatch");

        handler.handle(added);
        handler.handle(myChatMember(723, 100, NEW_GROUP, "note", "member"));

        telegram.awaitRequest("sendMessage", Duration.ofSeconds(2));
        Thread.sleep(100);
        assertTrue(telegram.drain("sendMessage").isEmpty(), "one prompt per chat");
        assertTrue(telegram.drain("leaveChat").isEmpty());
    }

    /** Spec, Errors: the bot removed from the group before the button was pressed still links; only the greeting may fail. */
    @Test
    void theBotRemovedBeforeTheTapStillLinks() throws Exception {
        UpdateHandler handler = personalHandler();
        handler.handle(myChatMember(726, 100, NEW_GROUP, "note", "member"));
        handler.handle(myChatMember(727, 100, NEW_GROUP, "note", "left"));

        handler.handle(callback(728, 100, "Bold", 100L, 1, "link:" + NEW_GROUP + ":0"));

        assertEquals(renderer.text("callback.groupLinked"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        assertTrue(personalGroups.isGroupChat("telegram:" + NEW_GROUP), "the running groups, from what the writer returned");
        assertEquals("GROUP_LINKED", row("SELECT kind FROM outbox WHERE chat_ref = ?", "telegram:" + NEW_GROUP).get("kind"),
                "the greeting is an outbox message: its delivery failing is the sender's to log, not the link's");
    }

    @Test
    void declineRedrawsThePromptAndLeaves() throws Exception {
        UpdateHandler handler = personalHandler();
        handler.handle(myChatMember(724, 100, NEW_GROUP, "note", "member"));

        handler.handle(callback(725, 100, "Bold", 100L, 55, "link:" + NEW_GROUP + ":-"));

        assertEquals(renderer.text("callback.groupDeclined"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        JsonNode redrawn = telegram.awaitRequest("editMessageText", Duration.ofSeconds(2)).json();
        assertEquals(55, redrawn.get("message_id").asLong());
        assertEquals(0, redrawn.get("reply_markup").get("inline_keyboard").size());
        assertEquals(NEW_GROUP, telegram.awaitRequest("leaveChat", Duration.ofSeconds(2)).json().get("chat_id").asLong());
        assertFalse(personalGroups.isGroupChat("telegram:" + NEW_GROUP));
    }

    /** Ruling R6: the project's group follows it to the new chat, so the bot has nothing left to do in the old one. */
    @Test
    void relinkingAProjectToAnotherChatLeavesTheOldChat() throws Exception {
        UpdateHandler handler = personalHandler();
        handler.handle(myChatMember(730, 100, NEW_GROUP, "note", "member"));
        handler.handle(callback(731, 100, "Bold", 100L, 1, "link:" + NEW_GROUP + ":0"));
        long otherGroup = -4883391546L;
        handler.handle(myChatMember(732, 100, otherGroup, "life", "member"));

        handler.handle(callback(733, 100, "Bold", 100L, 2, "link:" + otherGroup + ":0"));

        assertTrue(personalGroups.isGroupChat("telegram:" + otherGroup));
        assertFalse(personalGroups.isGroupChat("telegram:" + NEW_GROUP));
        assertEquals(NEW_GROUP, telegram.awaitRequest("leaveChat", Duration.ofSeconds(2)).json().get("chat_id").asLong());
        Thread.sleep(100);
        assertTrue(telegram.drain("leaveChat").isEmpty(), "only the old chat");
    }

    /** Ruling R7: the dedupe folds the near-simultaneous add events; an ignored prompt must not block the chat forever. */
    @Test
    void anUnansweredPromptIsAskedAgainOnlyOnceItIsAMinuteOld() throws Exception {
        UpdateHandler handler = personalHandler();
        handler.handle(myChatMember(734, 100, NEW_GROUP, "note", "member"));
        telegram.awaitRequest("sendMessage", Duration.ofSeconds(2));

        handler.handle(myChatMember(735, 100, NEW_GROUP, "note", "member"));
        Thread.sleep(100);
        assertTrue(telegram.drain("sendMessage").isEmpty(), "a second add within 60 s sends no second prompt");

        clock.advance(Duration.ofSeconds(61));
        handler.handle(myChatMember(736, 100, NEW_GROUP, "note", "member"));
        assertEquals(100, telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json().get("chat_id").asLong());
    }

    @Test
    void aFailedConfigWriteIsReportedAndTheQuestionStaysOpen() throws Exception {
        UpdateHandler handler = personalHandler();
        handler.handle(myChatMember(737, 100, NEW_GROUP, "note", "member"));
        linkFails = true;

        handler.handle(callback(738, 100, "Bold", 100L, 1, "link:" + NEW_GROUP + ":0"));

        assertEquals(renderer.text("callback.groupLinkFailed"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        assertFalse(personalGroups.isGroupChat("telegram:" + NEW_GROUP));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'GROUP_LINKED'").get("n"));
        assertTrue(db.transactionReturning(tx -> personalLinks.prompt(tx, NEW_GROUP)).isPresent(), "the prompt stays open");
    }

    private UpdateHandler personalHandler() {
        return personalHandlerWithProject("life");
    }

    /** A personal Dispatch: Bold alone, one chatless group owning {@code project}, and an in-memory config writer. */
    private UpdateHandler personalHandlerWithProject(String project) {
        Config.Project only = new Config.Project(project, null, "https://github.com/acme/life.git", null, "master",
                "claude-code", null, null, List.of(), null, null, null);
        Projects personalProjects = new Projects(List.of(only), candidate -> Optional.empty());
        Config.Telegram[] config = {new Config.Telegram(List.of(),
                List.of(new Config.Group("bold", null, List.of(new Config.Member(100, "Bold")), List.of(project))))};
        personalGroups = new Groups(config[0]);
        personalTasks = new TaskService(personalGroups, personalProjects, new ActiveRuns(), clock, () -> { }, () -> { });
        // Only the in-place rule: the one chatless group takes the chat.
        GroupWriter fakeWriter = new GroupWriter() {
            @Override
            public Config.Telegram link(long chatId, String title, String linked) {
                if (linkFails) {
                    throw new dispatch.config.ConfigException("config file is read-only");
                }
                return config[0] = withChat(config[0], group -> group.projects().contains(linked), chatId);
            }

            @Override
            public Config.Telegram unlink(String groupName) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Config.Telegram migrate(long oldChatId, long newChatId) {
                return config[0] = withChat(config[0], group -> Long.valueOf(oldChatId).equals(group.chatId()), newChatId);
            }
        };
        personalLinks = new GroupLinks(personalGroups, fakeWriter, clock, () -> { });
        return new UpdateHandler(db, personalTasks, new Membership(personalGroups, UpdateHandlerTest::noJoins, clock, () -> { }),
                personalGroups, personalProjects, api, renderer, redactor, BOT, clock, () -> { }, null, null, null, personalLinks);
    }

    private static Config.Telegram withChat(Config.Telegram telegram, java.util.function.Predicate<Config.Group> which, long chatId) {
        return new Config.Telegram(telegram.admins(), telegram.groups().stream()
                .map(group -> which.test(group) ? new Config.Group(group.name(), chatId, group.members(), group.projects()) : group)
                .toList());
    }

    private long taskAwaitingApproval() {
        return taskAwaitingApproval(List.of());
    }

    /** A task Bold gave for autoland-management, as the draft buttons would have created it. */
    private long task(String text) {
        String origin = "telegram:100/" + System.nanoTime();
        db.transaction(tx -> tasks.create(tx, new dispatch.domain.Requester("telegram:100", "Bold"), "alm", text,
                dispatch.domain.Priority.NORMAL, origin));
        return Long.parseLong(row("SELECT id FROM task WHERE origin_ref = ?", origin).get("id"));
    }

    private long taskAwaitingApproval(List<String> questions) {
        task("Fix the login timeout");
        ClaimedRun run = db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        Plan plan = new Plan("Make the timeout configurable", List.of(), List.of("Read auth.timeout"), List.of(), questions);
        transitions.planSucceeded(run.taskId(), run.seq(), plan,
                new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", plan.toJson(), null, new BigDecimal("0.1"), 3, List.of(), null, null, null));
        return run.taskId();
    }

    /** As the outbox sender records it once Telegram accepted the plan message. */
    private void planMessageSentAs(long messageId) {
        long outboxId = Long.parseLong(row("SELECT id FROM outbox WHERE kind = 'PLAN_READY'").get("id"));
        db.transaction(tx -> Outbox.markSent(tx, outboxId, 1, "telegram:" + GROUP + "/" + messageId, clock.instant()));
    }

    private static String botMessage(long messageId) {
        return """
                {"message_id":%d,"from":{"id":1,"is_bot":true,"first_name":"Dispatch"},"chat":{"id":%d,"type":"supergroup"},
                 "date":1789640000,"text":"plan"}""".formatted(messageId, GROUP);
    }

    /** Shaped like a real Bot API update; the command entity covers the leading /command[@bot] token. */
    static JsonNode message(long updateId, long messageId, long fromId, String firstName, long chatId, String chatType,
                            String text, String replyToJson) {
        int commandLength = text.startsWith("/") ? text.split("\\s", 2)[0].length() : 0;
        String entities = commandLength > 0 ? ",\"entities\":[{\"offset\":0,\"length\":" + commandLength + ",\"type\":\"bot_command\"}]" : "";
        String reply = replyToJson == null ? "" : ",\"reply_to_message\":" + replyToJson;
        return Json.read("""
                {"update_id":%d,"message":{"message_id":%d,
                 "from":{"id":%d,"is_bot":false,"first_name":"%s","username":"%s_dev","language_code":"mn"},
                 "chat":{"id":%d,"title":"Team","type":"%s"},"date":1789640000,"text":%s%s%s}}"""
                .formatted(updateId, messageId, fromId, firstName, firstName.toLowerCase(), chatId, chatType,
                        Json.MAPPER.valueToTree(text), entities, reply));
    }

    /** A command in the sender's own private chat with the bot. */
    static JsonNode privateCommand(long updateId, long fromId, String firstName, String text) {
        return message(updateId, updateId, fromId, firstName, fromId, "private", text, null);
    }

    /** A message written inside a topic of the sender's private chat with the bot. */
    static JsonNode topicMessage(long updateId, long messageId, long fromId, String firstName, long threadId, String text) {
        JsonNode message = message(updateId, messageId, fromId, firstName, fromId, "private", text, null);
        ((com.fasterxml.jackson.databind.node.ObjectNode) message.get("message")).put("message_thread_id", threadId).put("is_topic_message", true);
        return message;
    }

    /** A button pressed in the presser's own private chat with the bot. */
    static JsonNode privateCallback(long updateId, long fromId, String firstName, String data) {
        return Json.read("""
                {"update_id":%d,"callback_query":{"id":"cb-%d",
                 "from":{"id":%d,"is_bot":false,"first_name":"%s"},
                 "message":{"message_id":88,"from":{"id":1,"is_bot":true,"first_name":"Dispatch"},
                            "chat":{"id":%d,"type":"private"},"date":1789640000,"text":"plan"},
                 "chat_instance":"456","data":"%s"}}"""
                .formatted(updateId, updateId, fromId, firstName, fromId, data));
    }

    static JsonNode callback(long updateId, long fromId, String firstName, String data) {
        return Json.read("""
                {"update_id":%d,"callback_query":{"id":"cb-%d",
                 "from":{"id":%d,"is_bot":false,"first_name":"%s"},
                 "message":{"message_id":77,"from":{"id":1,"is_bot":true,"first_name":"Dispatch"},
                            "chat":{"id":%d,"title":"Team","type":"supergroup"},"date":1789640000,"text":"plan"},
                 "chat_instance":"123","data":"%s"}}"""
                .formatted(updateId, updateId, fromId, firstName, GROUP, data));
    }

    /** A button pressed under a message in {@code chatId}: the presser's private chat when it is their id, else a group. */
    static JsonNode callback(long updateId, long fromId, String firstName, long chatId, long messageId, String data) {
        return Json.read("""
                {"update_id":%d,"callback_query":{"id":"cb-%d",
                 "from":{"id":%d,"is_bot":false,"first_name":"%s"},
                 "message":{"message_id":%d,"from":{"id":1,"is_bot":true,"first_name":"Dispatch"},
                            "chat":{"id":%d,"type":"%s"},"date":1789640000,"text":"prompt"},
                 "chat_instance":"789","data":"%s"}}"""
                .formatted(updateId, updateId, fromId, firstName, messageId, chatId, chatId == fromId ? "private" : "supergroup", data));
    }

    /** The bot's own membership in {@code chatId} changed to {@code status}, by {@code fromId}. */
    static JsonNode myChatMember(long updateId, long fromId, long chatId, String title, String status) {
        return Json.read("""
                {"update_id":%d,"my_chat_member":{"chat":{"id":%d,"title":%s,"type":"group"},
                 "from":{"id":%d,"is_bot":false,"first_name":"Adder"},"date":1789640000,
                 "old_chat_member":{"user":{"id":1,"is_bot":true,"first_name":"Dispatch"},"status":"left"},
                 "new_chat_member":{"user":{"id":1,"is_bot":true,"first_name":"Dispatch"},"status":"%s"}}}"""
                .formatted(updateId, chatId, Json.MAPPER.valueToTree(title), fromId, status));
    }

    /** The service message Telegram posts in a group that became the supergroup {@code newChatId}. */
    static JsonNode migration(long updateId, long oldChatId, long newChatId) {
        JsonNode update = message(updateId, updateId, 100, "Bold", oldChatId, "group", "", null);
        com.fasterxml.jackson.databind.node.ObjectNode message = (com.fasterxml.jackson.databind.node.ObjectNode) update.get("message");
        message.remove("text");
        message.put("migrate_to_chat_id", newChatId);
        return update;
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
