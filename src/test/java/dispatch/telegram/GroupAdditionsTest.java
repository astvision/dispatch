package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Language;
import dispatch.Text;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.GroupAdditions;
import dispatch.core.Groups;
import dispatch.core.Membership;
import dispatch.core.Projects;
import dispatch.core.RunTransitions;
import dispatch.core.TaskCommand;
import dispatch.core.TaskService;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.Plan;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.store.TelegramUsers;
import dispatch.testing.FakeTelegram;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.math.BigDecimal;
import java.net.http.HttpClient;
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

/**
 * An addition: someone replies in a linked group to the message a task came from, and the task's requester gets it
 * privately with one button that applies it the way the task can take it then.
 */
class GroupAdditionsTest {

    private static final long GROUP = -1001234567890L;
    private static final long ALI = 200;
    private static final long MANAGER = 999;

    @TempDir
    Path dir;

    private FakeTelegram telegram;
    private Path dbFile;
    private Database db;
    private final TestClock clock = new TestClock(Instant.parse("2026-09-28T03:04:00Z"));
    private final Renderer renderer = new Renderer(Renderer.mongolian(), clock, FakeTelegram.BOT_USERNAME);
    private RunTransitions transitions;
    private TaskService tasks;
    private UpdateHandler handler;
    private OutboxSender sender;

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
                List.of(new Config.Member(100, "Bold"), new Config.Member(ALI, "Ali")), List.of("autoland-management"))));
        tasks = new TaskService(groups, projects, new ActiveRuns(), clock, () -> { }, () -> { });
        transitions = new RunTransitions(db, clock, () -> { });
        BotApi api = new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5));
        Membership membership = new Membership(groups, (group, member) -> null, clock, () -> { });
        dispatch.Redactor redactor = dispatch.Redactor.patternsOnly();
        handler = new UpdateHandler(db, tasks, membership, groups, projects, api, renderer, redactor, FakeTelegram.BOT_USERNAME, clock,
                () -> { });
        sender = new OutboxSender(db, api, renderer, redactor, new dispatch.core.Signal(), clock, Duration.ofSeconds(1));
        db.transaction(tx -> TelegramUsers.record(tx, ALI, "ali_dev", clock.instant()));
    }

    @AfterEach
    void tearDown() {
        telegram.close();
        db.close();
    }

    @Test
    void theAskersReplyToTheMessageATaskCameFromIsOfferedToItsRequesterPrivately() {
        long taskId = taskFromTheManagersMention(90);

        handler.handle(UpdateHandlerTest.message(610, 91, MANAGER, "Nomin", GROUP, "supergroup",
                "Also show the full position in a tooltip", humanMessage(90, MANAGER, "Nomin")));
        deliverAll();

        List<JsonNode> toAli = sent("sendMessage").stream()
                .filter(message -> message.get("chat_id").asLong() == ALI && message.get("text").asText().contains("tooltip")).toList();
        assertEquals(1, toAli.size(), "offered once, privately, to the task's requester: " + toAli);
        JsonNode offer = toAli.getFirst();
        assertTrue(offer.get("text").asText().contains("#" + taskId), offer.toString());
        assertTrue(offer.get("text").asText().contains("Nomin"), "says who wrote it: " + offer);
        String button = offer.at("/reply_markup/inline_keyboard/0/0/callback_data").asText();
        assertEquals("ad:" + row("SELECT id FROM addition").get("id"), button, "one button that applies it");
        assertTrue(sent("setMessageReaction").stream().anyMatch(reaction -> reaction.get("message_id").asLong() == 91
                && reaction.at("/reaction/0/emoji").asText().equals("👀")), "the group sees the reply reached them");
    }

    @Test
    void aTapWhileTheTaskIsStillBeingWorkedOnSaysSoAndKeepsTheButton() throws Exception {
        long taskId = taskFromTheManagersMention(90);
        String additionId = offered(90, 91, "Also show the full position in a tooltip");

        handler.handle(UpdateHandlerTest.privateCallback(620, ALI, "Ali", "ad:" + additionId));

        assertEquals(renderer.text("callback.additionBusy"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        assertEquals("1", count("SELECT count(*) AS n FROM run WHERE task_id = ?", taskId), "nothing new runs while one does");
        assertEquals(null, row("SELECT used_at FROM addition WHERE id = ?", additionId).get("used_at"), "the button stays usable");
        assertTrue(sent("editMessageText").isEmpty() && sent("editMessageReplyMarkup").isEmpty(), "nor is its message redrawn");
    }

    @Test
    void aTapWhileItsPlanWaitsCorrectsThePlanOnceAndDropsTheButton() throws Exception {
        long taskId = taskFromTheManagersMention(90);
        planReady(taskId);
        String additionId = offered(90, 91, "Also show the full position in a tooltip");

        handler.handle(UpdateHandlerTest.privateCallback(630, ALI, "Ali", "ad:" + additionId));

        Map<String, String> correction = row("SELECT kind, cause, instruction FROM run WHERE task_id = ? AND seq = 2", taskId);
        assertEquals("PLAN", correction.get("kind"));
        assertEquals("CORRECTION", correction.get("cause"));
        assertEquals("Also show the full position in a tooltip\n\nХүсэлт: Nomin", correction.get("instruction"),
                "the plan is revised with the addition, saying who asked");
        assertEquals("PLANNING", row("SELECT phase FROM task WHERE id = ?", taskId).get("phase"));
        assertEquals(renderer.text("callback.additionDone"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        JsonNode redrawn = telegram.awaitRequest("editMessageReplyMarkup", Duration.ofSeconds(2)).json();
        assertEquals(88, redrawn.get("message_id").asLong(), "the offer itself");
        assertTrue(redrawn.at("/reply_markup/inline_keyboard").isEmpty(), "loses its button: " + redrawn);

        handler.handle(UpdateHandlerTest.privateCallback(631, ALI, "Ali", "ad:" + additionId));
        assertEquals("2", count("SELECT count(*) AS n FROM run WHERE task_id = ?", taskId), "a second tap changes nothing");
    }

    @Test
    void anAdditionDuringExecutionWaitsForItsResultThenFollowsItUp() throws Exception {
        // As a manager's reply often comes: moments after the plan was approved, while it is being carried out.
        long taskId = taskFromTheManagersMention(90);
        planReady(taskId);
        executing(taskId);
        String additionId = offered(90, 91, "Also show the full position in a tooltip");

        handler.handle(UpdateHandlerTest.privateCallback(640, ALI, "Ali", "ad:" + additionId));
        assertEquals(renderer.text("callback.additionBusy"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());

        transitions.completed(taskId, 2, new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", null, "Fixed the width", new BigDecimal("0.10"),
                5, List.of(), null, null, null), List.of("src/PositionColumn.tsx"), "https://github.com/acme/alm/pull/30");
        handler.handle(UpdateHandlerTest.privateCallback(641, ALI, "Ali", "ad:" + additionId));

        Map<String, String> followUp = row("SELECT kind, cause, instruction FROM run WHERE task_id = ? AND seq = 3", taskId);
        assertEquals("EXECUTE", followUp.get("kind"));
        assertEquals("FOLLOW_UP", followUp.get("cause"), "a new commit on the same pull request, no new plan");
        assertEquals("Also show the full position in a tooltip\n\nХүсэлт: Nomin", followUp.get("instruction"));
        assertEquals(renderer.text("callback.additionDone"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        assertEquals(88, telegram.awaitRequest("editMessageReplyMarkup", Duration.ofSeconds(2)).json().get("message_id").asLong());
    }

    @Test
    void anAdditionToAMergedTaskBecomesANewTask() throws Exception {
        long taskId = taskFromTheManagersMention(90);
        planReady(taskId);
        executing(taskId);
        transitions.completed(taskId, 2, new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", null, "Fixed the width", new BigDecimal("0.10"),
                5, List.of(), null, null, null), List.of("src/PositionColumn.tsx"), "https://github.com/acme/alm/pull/30");
        db.transaction(tx -> dispatch.store.Tasks.merged(tx, taskId, clock.instant()));
        String additionId = offered(90, 91, "Also show the full position in a tooltip");

        handler.handle(UpdateHandlerTest.privateCallback(655, ALI, "Ali", "ad:" + additionId));

        assertEquals(renderer.text("callback.additionDone"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        assertEquals("Also show the full position in a tooltip\n\nХүсэлт: Nomin\n\n↩️ #" + taskId + " https://github.com/acme/alm/pull/30",
                row("SELECT description FROM task WHERE id <> ?", taskId).get("description"), "its merged branch takes nothing more");
    }

    @Test
    void aTapTheTaskRefusesSaysWhyAndKeepsTheAdditionForLater() throws Exception {
        long taskId = taskFromTheManagersMention(90);
        db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        transitions.failed(taskId, 1, FailureReason.AGENT, "model overloaded", null);
        String additionId = offered(90, 91, "Also show the full position in a tooltip");
        long last = Long.parseLong(row("SELECT max(id) AS id FROM outbox").get("id"));
        GroupAdditions additions = new GroupAdditions(tasks, clock, () -> { }, renderer.text("group.requestedBy"));

        // Its planning failed, so nothing was ever carried out to follow up; after /retry the plan could take it.
        assertEquals(new GroupAdditions.Applied(GroupAdditions.Outcome.REFUSED, Optional.of(Text.of("refused.notExecuted", taskId))),
                db.transactionReturning(tx -> additions.apply(tx, new Requester("telegram:" + ALI, "Ali"), Long.parseLong(additionId),
                        "telegram:" + ALI + "/88")));
        assertEquals(Long.toString(last), row("SELECT max(id) AS id FROM outbox").get("id"),
                "the task writes nothing: the channel says why");

        handler.handle(UpdateHandlerTest.privateCallback(650, ALI, "Ali", "ad:" + additionId));

        assertEquals(renderer.text("callback.additionRefused"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        Map<String, String> refused = row("SELECT * FROM outbox WHERE id > ?", last);
        assertEquals("REFUSED", refused.get("kind"), "the channel's reply alone: the refused command wrote nothing");
        assertEquals("telegram:" + ALI + "/88", refused.get("reply_to_ref"), "the reason is said under the offer");
        assertEquals(Text.of("refused.notExecuted", taskId).render(Language.MN), Json.read(refused.get("payload")).get("text").asText());
        assertEquals(null, row("SELECT used_at FROM addition WHERE id = ?", additionId).get("used_at"), "and it can still be added");
        assertTrue(sent("editMessageReplyMarkup").isEmpty(), "so its button stays");
    }

    @Test
    void aTapOnACancelledTasksAdditionSaysItIsClosedAndDropsTheButton() throws Exception {
        long taskId = taskFromTheManagersMention(90);
        String additionId = offered(90, 91, "Also show the full position in a tooltip");
        db.transaction(tx -> tasks.commands().run(tx, new Requester("telegram:" + ALI, "Ali"), new TaskCommand.Cancel(taskId)));

        handler.handle(UpdateHandlerTest.privateCallback(660, ALI, "Ali", "ad:" + additionId));

        assertEquals(renderer.text("callback.additionClosed"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        assertEquals(88, telegram.awaitRequest("editMessageReplyMarkup", Duration.ofSeconds(2)).json().get("message_id").asLong(),
                "a closed task never takes it: the button goes");
        assertEquals("1", count("SELECT count(*) AS n FROM run WHERE task_id = ?", taskId));
    }

    @Test
    void anAdditionToADraftWaitsUntilItIsGivenAsATask() throws Exception {
        handler.handle(UpdateHandlerTest.people(690, 90, MANAGER, "Nomin", "@ali_dev make the position column a fixed width", null, "@ali_dev"));
        String draftId = row("SELECT id FROM draft").get("id");
        String additionId = offered(90, 91, "Also show the full position in a tooltip");
        assertEquals("telegram:" + ALI, row("SELECT member_ref FROM addition WHERE id = ?", additionId).get("member_ref"),
                "offered to whoever the draft is for");

        handler.handle(UpdateHandlerTest.privateCallback(691, ALI, "Ali", "ad:" + additionId));
        assertEquals(renderer.text("callback.additionDraft"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        assertEquals(null, row("SELECT used_at FROM addition WHERE id = ?", additionId).get("used_at"));

        handler.handle(UpdateHandlerTest.privateCallback(692, ALI, "Ali", "draft:" + draftId + ":prio:NORMAL"));
        telegram.drain("answerCallbackQuery");
        handler.handle(UpdateHandlerTest.privateCallback(693, ALI, "Ali", "ad:" + additionId));
        assertEquals(renderer.text("callback.additionBusy"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText(),
                "once given, the addition belongs to the task");
    }

    @Test
    void anAdditionToADiscardedDraftIsClosed() throws Exception {
        handler.handle(UpdateHandlerTest.people(695, 90, MANAGER, "Nomin", "@ali_dev make the position column a fixed width", null, "@ali_dev"));
        String draftId = row("SELECT id FROM draft").get("id");
        String additionId = offered(90, 91, "Also show the full position in a tooltip");
        handler.handle(UpdateHandlerTest.privateCallback(696, ALI, "Ali", "draft:" + draftId + ":discard:x"));
        telegram.drain("answerCallbackQuery");

        handler.handle(UpdateHandlerTest.privateCallback(697, ALI, "Ali", "ad:" + additionId));

        assertEquals(renderer.text("callback.additionClosed"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
    }

    @Test
    void aStrangersReplyIsLeftAlone() {
        taskFromTheManagersMention(90);

        handler.handle(UpdateHandlerTest.message(700, 91, 555, "Stranger", GROUP, "supergroup", "lol same here",
                humanMessage(90, MANAGER, "Nomin")));

        assertEquals("0", count("SELECT count(*) AS n FROM addition"), "neither who asked nor a member: chatter");
        assertEquals("0", count("SELECT count(*) AS n FROM outbox WHERE kind = 'ADDITION_OFFERED'"));
    }

    @Test
    void aMembersReplyIsOfferedToo() {
        taskFromTheManagersMention(90);

        handler.handle(UpdateHandlerTest.message(701, 91, 100, "Bold", GROUP, "supergroup", "The mobile list needs it too",
                humanMessage(90, MANAGER, "Nomin")));

        Map<String, String> addition = row("SELECT member_ref, author FROM addition");
        assertEquals("telegram:" + ALI, addition.get("member_ref"));
        assertEquals("Bold", addition.get("author"));
    }

    @Test
    void aMessageThatGaveTwoDevelopersTasksOffersTheReplyToBoth() {
        db.transaction(tx -> TelegramUsers.record(tx, 100, "bold_dev", clock.instant()));
        handler.handle(UpdateHandlerTest.people(710, 59, MANAGER, "Nomin", "@ali_dev @bold_dev split the release", null,
                "@ali_dev", "@bold_dev"));

        handler.handle(UpdateHandlerTest.message(711, 60, MANAGER, "Nomin", GROUP, "supergroup", "Web first, then mobile",
                humanMessage(59, MANAGER, "Nomin")));

        assertEquals("[telegram:" + GROUP + "/59#100=telegram:100, telegram:" + GROUP + "/59#200=telegram:200]",
                SqlRows.query(dbFile, "SELECT origin_ref, member_ref FROM addition ORDER BY origin_ref").stream()
                        .map(found -> found.get("origin_ref") + "=" + found.get("member_ref")).toList().toString(),
                "each developer's own draft gets it");
    }

    @Test
    void aTaskGivenInAForumTopicTakesRepliesThere() {
        JsonNode mention = UpdateHandlerTest.people(720, 90, MANAGER, "Nomin", "@ali_dev make the position column a fixed width", null,
                "@ali_dev");
        ((ObjectNode) mention.get("message")).put("message_thread_id", 7).put("is_topic_message", true);
        handler.handle(mention);
        JsonNode reply = UpdateHandlerTest.message(721, 91, MANAGER, "Nomin", GROUP, "supergroup", "Also a tooltip",
                humanMessage(90, MANAGER, "Nomin"));
        ((ObjectNode) reply.get("message")).put("message_thread_id", 7).put("is_topic_message", true);

        handler.handle(reply);

        assertEquals("telegram:" + GROUP + "/90@7", row("SELECT origin_ref FROM addition").get("origin_ref"));
    }

    @Test
    void aReplyThatMentionsADeveloperStillGivesThemANewTask() {
        taskFromTheManagersMention(90);

        handler.handle(UpdateHandlerTest.people(730, 91, MANAGER, "Nomin", "@ali_dev also the export button", humanMessage(90, MANAGER, "Nomin"),
                "@ali_dev"));

        assertEquals("0", count("SELECT count(*) AS n FROM addition"), "a mention is a task, as before");
        assertEquals("the original\n\nalso the export button\n\nХүсэлт: Nomin",
                row("SELECT description FROM draft WHERE origin_ref = ?", "telegram:" + GROUP + "/91").get("description"));
    }

    @Test
    void aReplyTooLongToReadInOneMessageIsOfferedCutAndCannotBeApplied() {
        taskFromTheManagersMention(90);

        handler.handle(UpdateHandlerTest.message(740, 91, MANAGER, "Nomin", GROUP, "supergroup", "x".repeat(3001),
                humanMessage(90, MANAGER, "Nomin")));

        assertEquals("0", count("SELECT count(*) AS n FROM addition"), "nothing to apply");
        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'ADDITION_OFFERED'").get("payload"));
        assertTrue(payload.path("tooLong").asBoolean(), payload.toString());
        assertEquals("x".repeat(3000) + "…", payload.get("text").asText(), "shown cut, so the message still fits");

        handler.handle(UpdateHandlerTest.message(741, 92, MANAGER, "Nomin", GROUP, "supergroup", "x".repeat(2999) + "😀 and more",
                humanMessage(90, MANAGER, "Nomin")));
        JsonNode emoji = Json.read(row("SELECT payload FROM outbox WHERE kind = 'ADDITION_OFFERED' ORDER BY id DESC LIMIT 1")
                .get("payload"));
        assertEquals("x".repeat(2999) + "…", emoji.get("text").asText(), "never half an emoji, which Telegram would refuse");
    }

    @Test
    void aReplyWithAPhotoSaysTheFileStayedInTheGroup() {
        taskFromTheManagersMention(90);
        JsonNode reply = UpdateHandlerTest.message(750, 91, MANAGER, "Nomin", GROUP, "supergroup", "", humanMessage(90, MANAGER, "Nomin"));
        ObjectNode message = (ObjectNode) reply.get("message");
        message.remove("text");
        message.put("caption", "Like this one").putArray("photo").add(Json.read("{\"file_id\":\"p\",\"width\":90,\"height\":60}"));

        handler.handle(reply);

        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'ADDITION_OFFERED'").get("payload"));
        assertEquals("Like this one", payload.get("text").asText());
        assertTrue(payload.path("files").asBoolean(), "text only is carried; the photo is named: " + payload);
    }

    @Test
    void whenTheRequestersPrivateChatRefusesTheGroupIsAskedToHaveThemStartTheBot() throws Exception {
        taskFromTheManagersMention(90);
        deliverAll();
        telegram.refuseChat(ALI, 403, "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot can't initiate conversation with a user\"}");
        telegram.drain("sendMessage");

        handler.handle(UpdateHandlerTest.message(611, 91, MANAGER, "Nomin", GROUP, "supergroup", "Also show the full position in a tooltip",
                humanMessage(90, MANAGER, "Nomin")));
        deliverAll();

        List<String> toGroup = sent("sendMessage").stream().filter(message -> message.get("chat_id").asLong() == GROUP)
                .map(message -> message.get("text").asText()).toList();
        assertEquals(List.of(renderer.render(dispatch.domain.OutboxKind.DRAFT_PROMPT, Json.object().put("requester", "Ali"), true).html()),
                toGroup, "the same hint a task given in the group gets, never an empty task number");
    }

    @Test
    void anAdditionToADraftThatWasSplitIsOfferedAgainForEachPart() throws Exception {
        handler.handle(UpdateHandlerTest.people(760, 90, MANAGER, "Nomin", "@ali_dev fix the list and the export", null, "@ali_dev"));
        long draftId = Long.parseLong(row("SELECT id FROM draft").get("id"));
        String additionId = offered(90, 91, "Both before Friday");
        Requester ali = new Requester("telegram:" + ALI, "Ali");
        db.transaction(tx -> {
            tasks.split(tx, ali, draftId, "telegram:" + ALI + "/5");
            tasks.splitProposed(tx, draftId, List.of("Fix the list", "Fix the export"));
            tasks.acceptSplit(tx, ali, draftId);
        });

        handler.handle(UpdateHandlerTest.privateCallback(761, ALI, "Ali", "ad:" + additionId));

        assertEquals(renderer.text("callback.additionSplit"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        assertEquals("[telegram:" + GROUP + "/90#1, telegram:" + GROUP + "/90#2]",
                SqlRows.query(dbFile, "SELECT origin_ref FROM addition WHERE used_at IS NULL ORDER BY origin_ref").stream()
                        .map(found -> found.get("origin_ref")).toList().toString(), "each part is offered it, to tap the right one");
    }

    @Test
    void theRequestersOwnReplyIsNotOfferedBackToThem() {
        taskFromTheManagersMention(90);

        handler.handle(UpdateHandlerTest.message(770, 91, ALI, "Ali", GROUP, "supergroup", "ok, on it", humanMessage(90, MANAGER, "Nomin")));

        assertEquals("0", count("SELECT count(*) AS n FROM addition"), "they can say more to their own plan or result privately");
        assertEquals("0", count("SELECT count(*) AS n FROM outbox WHERE kind = 'ADDITION_OFFERED'"));
    }

    @Test
    void aClosedTasksMessageTakesNoAdditions() {
        long taskId = taskFromTheManagersMention(90);
        db.transaction(tx -> tasks.commands().run(tx, new Requester("telegram:" + ALI, "Ali"), new TaskCommand.Cancel(taskId)));

        handler.handle(UpdateHandlerTest.message(780, 91, MANAGER, "Nomin", GROUP, "supergroup", "Also a tooltip", humanMessage(90, MANAGER, "Nomin")));

        assertEquals("0", count("SELECT count(*) AS n FROM outbox WHERE kind = 'ADDITION_OFFERED'"));
    }

    @Test
    void aPhotoWithNoTextIsNamedButHasNothingToApply() {
        taskFromTheManagersMention(90);
        JsonNode reply = UpdateHandlerTest.message(790, 91, MANAGER, "Nomin", GROUP, "supergroup", "", humanMessage(90, MANAGER, "Nomin"));
        ObjectNode message = (ObjectNode) reply.get("message");
        message.remove("text");
        message.putArray("photo").add(Json.read("{\"file_id\":\"p\",\"width\":90,\"height\":60}"));

        handler.handle(reply);

        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'ADDITION_OFFERED'").get("payload"));
        assertTrue(payload.path("files").asBoolean(), payload.toString());
        assertFalse(payload.has("additionId"), "no text, so no button: " + payload);
    }

    @Test
    void anotherMembersTapOnSomeonesAdditionChangesNothing() throws Exception {
        long taskId = taskFromTheManagersMention(90);
        planReady(taskId);
        String additionId = offered(90, 91, "Also show the full position in a tooltip");

        handler.handle(UpdateHandlerTest.privateCallback(800, 100, "Bold", "ad:" + additionId));

        assertEquals(renderer.text("callback.additionUsed"),
                telegram.awaitRequest("answerCallbackQuery", Duration.ofSeconds(2)).json().get("text").asText());
        assertEquals("1", count("SELECT count(*) AS n FROM run WHERE task_id = ?", taskId), "only the requester applies it");
        assertEquals(null, row("SELECT used_at FROM addition WHERE id = ?", additionId).get("used_at"));
    }

    @Test
    void aRequesterWhoChoseSilenceGetsTheOfferWithNoReactionInTheGroup() {
        taskFromTheManagersMention(90);
        deliverAll();
        telegram.drain("setMessageReaction");
        telegram.drain("sendMessage");
        db.transaction(tx -> dispatch.store.MemberPrefs.setGroupAck(tx, ALI, dispatch.domain.GroupAck.SILENT, clock.instant()));

        handler.handle(UpdateHandlerTest.message(810, 91, MANAGER, "Nomin", GROUP, "supergroup", "Also a tooltip", humanMessage(90, MANAGER, "Nomin")));
        deliverAll();

        assertEquals(1, sent("sendMessage").stream().filter(message -> message.get("chat_id").asLong() == ALI).count());
        assertTrue(sent("setMessageReaction").isEmpty(), "their choice of silence holds for additions too");
    }

    @Test
    void aReplyToTheMessageSomeoneGaveAsATaskByMentioningTheBotReachesItsRequester() {
        // Nomin writes; Bold, a member, gives it as his task by replying to it with a mention of the bot (G-1b).
        handler.handle(UpdateHandlerTest.message(820, 90, MANAGER, "Nomin", GROUP, "supergroup", "The positions column needs a width",
                null));
        handler.handle(UpdateHandlerTest.mention(821, 91, 100, "Bold", "@" + FakeTelegram.BOT_USERNAME + " please",
                humanMessage(90, MANAGER, "Nomin")));
        String draftId = row("SELECT id FROM draft WHERE origin_ref = ?", "telegram:" + GROUP + "/91").get("id");
        handler.handle(UpdateHandlerTest.privateCallback(822, 100, "Bold", "draft:" + draftId + ":prio:NORMAL"));

        handler.handle(UpdateHandlerTest.message(823, 92, MANAGER, "Nomin", GROUP, "supergroup", "Also a tooltip",
                humanMessage(90, MANAGER, "Nomin")));

        Map<String, String> addition = row("SELECT origin_ref, member_ref FROM addition");
        assertEquals("telegram:100", addition.get("member_ref"), "her reply reaches the task her message became");
        assertEquals("telegram:" + GROUP + "/91", addition.get("origin_ref"));
    }

    @Test
    void aReplyToTheMessageSomeoneGaveAsATaskByMentioningADeveloperReachesThem() {
        handler.handle(UpdateHandlerTest.message(830, 90, MANAGER, "Nomin", GROUP, "supergroup", "The positions column needs a width",
                null));
        handler.handle(UpdateHandlerTest.people(831, 91, 100, "Bold", "@ali_dev can you", humanMessage(90, MANAGER, "Nomin"), "@ali_dev"));

        handler.handle(UpdateHandlerTest.message(832, 92, MANAGER, "Nomin", GROUP, "supergroup", "Also a tooltip",
                humanMessage(90, MANAGER, "Nomin")));

        assertEquals("telegram:" + ALI, row("SELECT member_ref FROM addition").get("member_ref"), "offered while it is still Ali's draft");
    }

    @Test
    void thePartsOfADraftGivenInReplyToSomeonesMessageTakeTheirRepliesToo() {
        handler.handle(UpdateHandlerTest.message(840, 90, MANAGER, "Nomin", GROUP, "supergroup", "Fix the list and the export", null));
        handler.handle(UpdateHandlerTest.mention(841, 91, 100, "Bold", "@" + FakeTelegram.BOT_USERNAME + " please",
                humanMessage(90, MANAGER, "Nomin")));
        long draftId = Long.parseLong(row("SELECT id FROM draft").get("id"));
        Requester bold = new Requester("telegram:100", "Bold");
        db.transaction(tx -> {
            tasks.split(tx, bold, draftId, "telegram:100/5");
            tasks.splitProposed(tx, draftId, List.of("Fix the list", "Fix the export"));
            tasks.acceptSplit(tx, bold, draftId);
        });

        handler.handle(UpdateHandlerTest.message(842, 92, MANAGER, "Nomin", GROUP, "supergroup", "Both before Friday",
                humanMessage(90, MANAGER, "Nomin")));

        assertEquals("[telegram:" + GROUP + "/91#1, telegram:" + GROUP + "/91#2]",
                SqlRows.query(dbFile, "SELECT origin_ref FROM addition ORDER BY origin_ref").stream()
                        .map(found -> found.get("origin_ref")).toList().toString());
    }

    @Test
    void chatterInAForumTopicIsNoAdditionToATaskGivenThere() {
        // Telegram puts a topic's creation message under every message in the topic that replies to nothing: neither the
        // task nor the chatter after it answers anyone.
        String topic = topicCreated(7, MANAGER, "Nomin");
        JsonNode mention = UpdateHandlerTest.mention(850, 91, 100, "Bold", "@" + FakeTelegram.BOT_USERNAME + " fix the login", topic);
        ((ObjectNode) mention.get("message")).put("message_thread_id", 7).put("is_topic_message", true);
        handler.handle(mention);
        JsonNode chatter = UpdateHandlerTest.message(851, 92, ALI, "Ali", GROUP, "supergroup", "Lunch at noon?", topic);
        ((ObjectNode) chatter.get("message")).put("message_thread_id", 7).put("is_topic_message", true);

        handler.handle(chatter);

        assertEquals(null, row("SELECT source_ref FROM draft").get("source_ref"), "the topic is where it was given, not what it answers");
        assertEquals("0", count("SELECT count(*) AS n FROM addition"));
    }

    @Test
    void aSplitDraftsPartsAreOfferedItAgainButNoOtherDraftFromTheSameMessage() {
        db.transaction(tx -> TelegramUsers.record(tx, 100, "bold_dev", clock.instant()));
        // Nomin gives Bold a task; Bold, replying to her message, passes the export on to Ali (G-1c).
        handler.handle(UpdateHandlerTest.people(860, 90, MANAGER, "Nomin", "@bold_dev fix the list and the export", null, "@bold_dev"));
        handler.handle(UpdateHandlerTest.people(861, 91, 100, "Bold", "@ali_dev you take the export", humanMessage(90, MANAGER, "Nomin"),
                "@ali_dev"));
        handler.handle(UpdateHandlerTest.message(862, 92, MANAGER, "Nomin", GROUP, "supergroup", "Both before Friday",
                humanMessage(90, MANAGER, "Nomin")));
        assertEquals("1", count("SELECT count(*) AS n FROM addition WHERE member_ref = ?", "telegram:" + ALI), "Ali's draft answers her too");
        long boldsDraft = Long.parseLong(row("SELECT id FROM draft WHERE origin_ref = ?", "telegram:" + GROUP + "/90").get("id"));
        Requester bold = new Requester("telegram:100", "Bold");
        db.transaction(tx -> {
            tasks.split(tx, bold, boldsDraft, "telegram:100/5");
            tasks.splitProposed(tx, boldsDraft, List.of("Fix the list", "Fix the export"));
            tasks.acceptSplit(tx, bold, boldsDraft);
        });
        String boldsAddition = row("SELECT id FROM addition WHERE member_ref = ?", "telegram:100").get("id");

        handler.handle(UpdateHandlerTest.privateCallback(863, 100, "Bold", "ad:" + boldsAddition));

        assertEquals("[telegram:" + GROUP + "/90#1, telegram:" + GROUP + "/90#2]",
                SqlRows.query(dbFile, "SELECT origin_ref FROM addition WHERE member_ref = 'telegram:100' AND used_at IS NULL"
                        + " ORDER BY origin_ref").stream().map(found -> found.get("origin_ref")).toList().toString());
        assertEquals("1", count("SELECT count(*) AS n FROM addition WHERE member_ref = ?", "telegram:" + ALI), "Ali has hers already");
    }

    /** The approved plan's execution (run 2) has started, its agent running. */
    private void executing(long taskId) {
        db.transaction(tx -> tasks.approve(tx, new Requester("telegram:" + ALI, "Ali"), taskId, 1));
        ClaimedRun run = db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        assertEquals(2, run.seq());
        transitions.agentStarted(taskId, 2, null, null);
    }

    /** The task's first plan is ready and waits for approval. */
    private void planReady(long taskId) {
        ClaimedRun run = db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        assertEquals(taskId, run.taskId());
        Plan plan = new Plan("Fix the position column's width", List.of(), List.of("Set a fixed width"), List.of(), List.of());
        transitions.planSucceeded(taskId, run.seq(), plan, new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", plan.toJson(), null,
                new BigDecimal("0.10"), 3, List.of(), null, null, null));
    }

    /**
     * Nomin's reply {@code replyId} to her message {@code repliedId}, delivered; the id of the addition it offered. What was
     * sent to Telegram until then is forgotten, so a test sees only what a tap after it does.
     */
    private String offered(long repliedId, long replyId, String text) {
        handler.handle(UpdateHandlerTest.message(600 + replyId, replyId, MANAGER, "Nomin", GROUP, "supergroup", text,
                humanMessage(repliedId, MANAGER, "Nomin")));
        deliverAll();
        List.of("sendMessage", "answerCallbackQuery", "editMessageText", "editMessageReplyMarkup", "setMessageReaction")
                .forEach(telegram::drain);
        return row("SELECT id FROM addition WHERE text = ?", text).get("id");
    }

    /**
     * The task Ali got when Nomin, a manager who is no member, mentioned him in message {@code messageId}, as the draft
     * buttons create it: planning has started.
     */
    private long taskFromTheManagersMention(long messageId) {
        handler.handle(UpdateHandlerTest.people(600 + messageId, messageId, MANAGER, "Nomin",
                "@ali_dev make the position column a fixed width", null, "@ali_dev"));
        String draftId = row("SELECT id FROM draft WHERE origin_ref = ?", "telegram:" + GROUP + "/" + messageId).get("id");
        handler.handle(UpdateHandlerTest.privateCallback(700 + messageId, ALI, "Ali", "draft:" + draftId + ":prio:NORMAL"));
        return Long.parseLong(row("SELECT id FROM task WHERE origin_ref = ?", "telegram:" + GROUP + "/" + messageId).get("id"));
    }

    /** Someone's own message in the group, as a reply carries it. */
    private static String humanMessage(long messageId, long fromId, String firstName) {
        return """
                {"message_id":%d,"from":{"id":%d,"is_bot":false,"first_name":"%s"},"chat":{"id":%d,"type":"supergroup"},
                 "date":1789640000,"text":"the original"}""".formatted(messageId, fromId, firstName, GROUP);
    }

    /** The service message that opened forum topic {@code threadId}, as Telegram puts it under each message there. */
    private static String topicCreated(long threadId, long fromId, String firstName) {
        return """
                {"message_id":%d,"message_thread_id":%d,"from":{"id":%d,"is_bot":false,"first_name":"%s"},
                 "chat":{"id":%d,"type":"supergroup","is_forum":true},"date":1789640000,
                 "forum_topic_created":{"name":"Positions","icon_color":7322096}}""".formatted(threadId, threadId, fromId, firstName, GROUP);
    }

    private void deliverAll() {
        while (sender.deliverDue()) {
            // until nothing is due
        }
    }

    /** What Dispatch sent Telegram with {@code method} since the last look. */
    private List<JsonNode> sent(String method) {
        return telegram.drain(method).stream().map(FakeTelegram.Request::json).toList();
    }

    private String count(String sql, Object... params) {
        return row(sql, params).get("n");
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
