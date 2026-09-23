package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.domain.OutboxKind;
import dispatch.testing.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class RendererTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-09-17T10:30:00Z"));
    private final ResourceBundle messages = Renderer.mongolian();
    private final Renderer renderer = new Renderer(messages, clock, "dispatch_backend_bot");

    @Test
    void planIsRenderedWithEscapedContentNumberedStepsCostDurationAndApproveAndRejectButtons() {
        ObjectNode payload = planPayload(List.of("Read <auth.timeout> & default to 60s", "Add AuthClientTimeoutTest"), List.of());

        Renderer.Rendered rendered = renderer.render(OutboxKind.PLAN_READY, payload);

        assertNull(rendered.document());
        String html = rendered.html();
        assertTrue(html.contains("#42"), html);
        assertTrue(html.contains("autoland-management"), html);
        assertTrue(html.contains("Login times out after 30s &amp; users retry"), html);
        assertTrue(html.contains("1. Read &lt;auth.timeout&gt; &amp; default to 60s"), html);
        assertTrue(html.contains("2. Add AuthClientTimeoutTest"), html);
        assertTrue(html.contains("$0.17"), html);
        assertTrue(html.contains("1 мин 50 сек"), html);
        assertFalse(html.contains(messages.getString("plan.questions")), html);
        assertTrue(html.contains(messages.getString("plan.replyHint")), html);
        assertEquals(List.of(List.of(new Renderer.Button(messages.getString("button.approve"), "approve:42:1"),
                new Renderer.Button(messages.getString("button.reject"), "reject:42:1"))), rendered.keyboard());
    }

    @Test
    void openQuestionsAreListedWithTheNeedToAnswerThem() {
        Renderer.Rendered rendered = renderer.render(OutboxKind.PLAN_READY,
                planPayload(List.of(), List.of("Which environment reads auth.timeout?")));

        assertTrue(rendered.html().contains(messages.getString("plan.questions")), rendered.html());
        assertTrue(rendered.html().contains("1. Which environment reads auth.timeout?"), rendered.html());
        assertTrue(rendered.html().contains(messages.getString("plan.questionsHint")), rendered.html());
        assertEquals(List.of(List.of(new Renderer.Button(messages.getString("button.reject"), "reject:42:1"))), rendered.keyboard(),
                "no Approve while questions are open; answers come as replies");
    }

    @Test
    void planTooLongForAMessageIsSentAsMarkdownDocumentWithTheSameButtons() {
        List<String> steps = java.util.stream.IntStream.rangeClosed(1, 150)
                .mapToObj(i -> "Step " + i + " touches a file with a fairly long description").toList();

        Renderer.Rendered rendered = renderer.render(OutboxKind.PLAN_READY, planPayload(steps, List.of()));

        assertNotNull(rendered.document());
        assertEquals("plan-42.md", rendered.document().fileName());
        assertTrue(rendered.document().markdown().contains("150. Step 150 touches"), rendered.document().markdown());
        assertTrue(rendered.html().length() <= 1024, "caption limit");
        assertEquals("approve:42:1", rendered.keyboard().getFirst().getFirst().data());
    }

    @Test
    void thePairingMessageShowsTheCodeTheCommandAndTheMembersComputers() {
        ObjectNode payload = Json.object().put("personal", false).put("code", "ABCD2345").put("minutes", 10)
                .put("url", "https://team.example.com");
        ArrayNode workers = payload.putArray("workers");
        workers.addObject().put("id", 3).put("name", "ann-laptop").put("lastSeenAt", clock.instant().minus(Duration.ofMinutes(2)).toString());
        workers.addObject().put("id", 4).put("name", "desktop").putNull("lastSeenAt");

        String html = renderer.render(OutboxKind.WORKER_PAIRING, payload).html();

        assertTrue(html.contains("ABCD2345"), html);
        assertTrue(html.contains("dispatch worker pair https://team.example.com ABCD2345"), html);
        assertTrue(html.contains("#3"), html);
        assertTrue(html.contains("ann-laptop"), html);
        assertTrue(html.contains("#4"), html);
    }

    @Test
    void aTaskWaitingForItsRequestersComputerSaysSo() {
        String html = renderer.render(OutboxKind.WORKER_WAITING, Json.object().put("taskId", 7)).html();

        assertTrue(html.contains("#7"), html);
        assertEquals(html, renderer.render(OutboxKind.WORKER_WAITING, Json.object().put("taskId", 7)).html());
    }

    @Test
    void failureNamesTheReasonAndKeepsHugeDetailWithinTheLimit() {
        ObjectNode payload = Json.object().put("taskId", 42).put("reason", "TIMEOUT").put("detail", "x".repeat(6000));

        Renderer.Rendered rendered = renderer.render(OutboxKind.TASK_FAILED, payload);

        assertTrue(rendered.html().contains(messages.getString("failure.TIMEOUT")), rendered.html());
        assertTrue(rendered.html().length() <= 4096, "message limit");
    }

    @Test
    void failureDetailFullOfMarkupStaysWithinTheLimitOnceEscaped() {
        ObjectNode payload = Json.object().put("taskId", 42).put("reason", "AGENT").put("detail", "<&>".repeat(3000));

        String html = renderer.render(OutboxKind.TASK_FAILED, payload).html();

        assertTrue(html.length() <= 4096, "message limit, got " + html.length());
        assertFalse(html.matches("(?s).*&[a-z]*….*"), "an entity must not be cut in half");
    }

    @Test
    void completedTaskLinksThePullRequestWithSummaryDenialsCostAndDuration() {
        String html = renderer.render(OutboxKind.TASK_COMPLETED,
                completedPayload("https://github.com/acme/alm/pull/7", 2, List.of("Bash: git push origin dispatch/42"))).html();

        assertTrue(html.contains("#42"), html);
        assertTrue(html.contains("autoland-management"), html);
        assertTrue(html.contains("https://github.com/acme/alm/pull/7"), html);
        assertTrue(html.contains("Made the timeout &lt;configurable&gt;"), html);
        assertTrue(html.contains("• Bash: git push origin dispatch/42"), html);
        assertTrue(html.contains("$0.42"), html);
        assertTrue(html.contains("4 мин"), html);
    }

    @Test
    void footersNameTheModelThatAnsweredAndWarnWhenItIsNotTheConfiguredOne() {
        ObjectNode plan = planPayload(List.of("Add AuthClientTimeoutTest"), List.of()).put("model", "claude-sonnet-5").put("requestedModel", "haiku");
        ObjectNode completed = completedPayload("https://github.com/acme/alm/pull/7", 2, List.of()).put("model", "claude-haiku-4-5-20251001");

        String planHtml = renderer.render(OutboxKind.PLAN_READY, plan).html();
        String completedHtml = renderer.render(OutboxKind.TASK_COMPLETED, completed).html();

        assertTrue(planHtml.contains("sonnet-5 · Зардал $0.17"), planHtml);
        assertTrue(planHtml.contains(java.text.MessageFormat.format(messages.getString("run.modelDiffers"), "haiku", "sonnet-5")), planHtml);
        assertTrue(completedHtml.contains("haiku-4-5 · 2 файл"), "the date in a model id is left out: " + completedHtml);
        assertFalse(completedHtml.contains("⚠️"), completedHtml);
        assertFalse(renderer.render(OutboxKind.PLAN_READY, planPayload(List.of(), List.of())).html().contains(" · Зардал"),
                "a plan from before models were recorded has the footer it had");
    }

    @Test
    void completedTaskWithoutChangesSaysNoPullRequestWasOpened() {
        String html = renderer.render(OutboxKind.TASK_COMPLETED, completedPayload(null, 0, List.of())).html();

        assertTrue(html.contains(messages.getString("task.completedNoChanges")), html);
        assertFalse(html.contains("github.com"), html);
    }

    @Test
    void hugeSummaryFullOfMarkupStaysWithinTheMessageLimit() {
        ObjectNode payload = completedPayload("https://github.com/acme/alm/pull/7", 3, List.of("Bash: " + "x".repeat(300)));
        payload.put("summary", "<&>".repeat(3000));

        String html = renderer.render(OutboxKind.TASK_COMPLETED, payload).html();

        assertTrue(html.length() <= 4096, "message limit, got " + html.length());
        assertTrue(html.contains("https://github.com/acme/alm/pull/7"), "the link survives a long summary");
    }

    @Test
    void groupHearsWhoGaveWhichTaskForWhichProjectAndHowUrgent() {
        String html = renderer.render(OutboxKind.TASK_QUEUED, Json.object().put("taskId", 3).put("project", "life")
                .put("requester", "Bold <dev>").put("priority", "URGENT").put("title", "Fix <login>")).html();

        assertTrue(html.contains("#3") && html.contains("life") && html.contains("Bold &lt;dev&gt;"), html);
        assertTrue(html.contains("🔴") && html.contains("Fix &lt;login&gt;"), html);
    }

    @Test
    void draftPromptAsksForTheProjectInRowsOfThreeThenThePriority() {
        Renderer.Rendered rendered = renderer.render(OutboxKind.DRAFT_PROMPT,
                draftPayload(List.of("alm", "crm", "life", "billing"), null, "OPEN", null));

        assertTrue(rendered.html().contains("Fix the &lt;login&gt; timeout"), rendered.html());
        assertTrue(rendered.html().contains(messages.getString("draft.chooseProject")), rendered.html());
        List<List<Renderer.Button>> keyboard = rendered.keyboard();
        assertEquals(3, keyboard.size());
        assertEquals(List.of(new Renderer.Button("alm", "draft:5:p:alm"), new Renderer.Button("crm", "draft:5:p:crm"),
                new Renderer.Button("life", "draft:5:p:life")), keyboard.get(0));
        assertEquals(List.of(new Renderer.Button("billing", "draft:5:p:billing")), keyboard.get(1));
        assertEquals("draft:5:prio:URGENT", keyboard.get(2).get(0).data());
        assertEquals("draft:5:prio:LOW", keyboard.get(2).get(2).data());
    }

    @Test
    void draftPromptMarksTheChosenProjectAndWithOneProjectAsksOnlyForPriority() {
        Renderer.Rendered chosen = renderer.render(OutboxKind.DRAFT_PROMPT, draftPayload(List.of("alm", "crm"), "crm", "OPEN", null));
        Renderer.Rendered single = renderer.render(OutboxKind.DRAFT_PROMPT, draftPayload(List.of("life"), "life", "OPEN", null));

        assertEquals("✓ crm", chosen.keyboard().getFirst().get(1).text());
        assertTrue(chosen.html().contains("crm"), chosen.html());
        assertEquals(1, single.keyboard().size(), "priority only");
        assertTrue(single.html().contains("life"), single.html());
    }

    @Test
    void wholeMessageOffersScissorsBelowThePriorities() {
        Renderer.Rendered rendered = renderer.render(OutboxKind.DRAFT_PROMPT,
                draftPayload(List.of("alm", "crm"), null, "OPEN", null).put("splittable", true));

        assertEquals(List.of(new Renderer.Button(messages.getString("button.split"), "draft:5:split:ask")), rendered.keyboard().getLast());
        assertEquals("draft:5:prio:URGENT", rendered.keyboard().get(1).getFirst().data());
    }

    @Test
    void whileSplittingThePromptSaysSoAndStillAsksForProjectAndPriority() {
        Renderer.Rendered rendered = renderer.render(OutboxKind.DRAFT_PROMPT,
                draftPayload(List.of("alm", "crm"), "crm", "OPEN", null).put("split", "SPLITTING"));

        assertTrue(rendered.html().contains(messages.getString("draft.splitting")), rendered.html());
        assertEquals("draft:5:prio:LOW", rendered.keyboard().getLast().getLast().data(), "no ✂️ while it runs");
    }

    @Test
    void proposedSplitListsThePartsAndAsksToSplitOrKeepWhole() {
        ObjectNode payload = draftPayload(List.of("alm", "crm"), null, "OPEN", null).put("split", "PROPOSED");
        payload.putArray("topics").add("staging: fix the <login> timeout").add("staging: add make help");

        Renderer.Rendered rendered = renderer.render(OutboxKind.DRAFT_PROMPT, payload);

        assertTrue(rendered.html().contains("1. staging: fix the &lt;login&gt; timeout\n2. staging: add make help"), rendered.html());
        assertEquals(List.of(List.of(new Renderer.Button("✂️ 2 даалгавар болгох", "draft:5:split:yes"),
                new Renderer.Button(messages.getString("button.keepWhole"), "draft:5:split:no"))), rendered.keyboard());
    }

    @Test
    void splitOutcomesAreNotedOnThePrompt() {
        String oneTopic = renderer.render(OutboxKind.DRAFT_PROMPT,
                draftPayload(List.of("alm"), "alm", "OPEN", null).put("split", "ONE_TOPIC")).html();
        Renderer.Rendered failed = renderer.render(OutboxKind.DRAFT_PROMPT,
                draftPayload(List.of("alm"), "alm", "OPEN", null).put("split", "FAILED").put("splittable", true));
        ObjectNode split = draftPayload(List.of("alm"), "alm", "SPLIT", null);
        split.putArray("topics").add("Fix the timeout").add("Add make help");
        Renderer.Rendered splitInto = renderer.render(OutboxKind.DRAFT_PROMPT, split);

        assertTrue(oneTopic.contains(messages.getString("draft.oneTopic")), oneTopic);
        assertTrue(failed.html().contains(messages.getString("draft.splitFailed")), failed.html());
        assertEquals("draft:5:split:ask", failed.keyboard().getLast().getFirst().data(), "try again");
        assertTrue(splitInto.html().contains("2. Add make help"), splitInto.html());
        assertTrue(splitInto.keyboard().isEmpty());
    }

    @Test
    void partPromptSaysWhichPartItIs() {
        String html = renderer.render(OutboxKind.DRAFT_PROMPT,
                draftPayload(List.of("alm"), "alm", "OPEN", null).put("part", 2).put("parts", 3)).html();

        assertTrue(html.contains("2/3"), html);
    }

    @Test
    void expiredNoteNamesTheMessageItIsAbout() {
        String html = renderer.render(OutboxKind.DRAFT_EXPIRED, Json.object().put("draftId", 5).put("title", "Add <make> help")).html();

        assertTrue(html.startsWith(messages.getString("draft.expired")) && html.contains("Add &lt;make&gt; help"), html);
    }

    @Test
    void joinRequestOffersAButtonPerGroupAndDeny() {
        Renderer.Rendered rendered = renderer.render(OutboxKind.JOIN_REQUEST, joinPayload("OPEN"));

        assertTrue(rendered.html().contains("Ali &lt;x&gt;") && rendered.html().contains("@ali_dev") && rendered.html().contains("555"),
                rendered.html());
        assertEquals(List.of(List.of(new Renderer.Button("✅ backend", "join:7:backend"), new Renderer.Button("✅ mobile", "join:7:mobile")),
                List.of(new Renderer.Button(messages.getString("button.joinDeny"), "join:7:-"))), rendered.keyboard());
    }

    @Test
    void decidedJoinRequestSaysWhoDecidedWithoutButtons() {
        Renderer.Rendered approved = renderer.render(OutboxKind.JOIN_REQUEST, joinPayload("APPROVED").put("group", "backend"));
        Renderer.Rendered denied = renderer.render(OutboxKind.JOIN_REQUEST, joinPayload("DENIED"));

        assertTrue(approved.html().contains("backend") && approved.html().contains("Nomin"), approved.html());
        assertTrue(denied.html().contains("Nomin"), denied.html());
        assertTrue(approved.keyboard().isEmpty() && denied.keyboard().isEmpty());
    }

    @Test
    void createdDraftShowsTheTaskWithoutButtons() {
        Renderer.Rendered rendered = renderer.render(OutboxKind.DRAFT_PROMPT, draftPayload(List.of("alm", "crm"), "crm", "CREATED", 7L)
                .put("priority", "URGENT"));

        assertTrue(rendered.html().contains("#7") && rendered.html().contains("🔴") && rendered.html().contains("crm"), rendered.html());
        assertTrue(rendered.keyboard().isEmpty());
    }

    @Test
    void groupGetsOneLineOutcomes() {
        String done = renderer.render(OutboxKind.TASK_COMPLETED_SHORT, Json.object().put("taskId", 3).put("project", "life")
                .put("prUrl", "https://github.com/acme/life/pull/4").put("filesChanged", 2)).html();
        String nothing = renderer.render(OutboxKind.TASK_COMPLETED_SHORT, Json.object().put("taskId", 3).put("project", "life")
                .putNull("prUrl").put("filesChanged", 0)).html();
        String failed = renderer.render(OutboxKind.TASK_FAILED_SHORT, Json.object().put("taskId", 3).put("reason", "TIMEOUT")).html();

        assertTrue(done.contains("#3") && done.contains("https://github.com/acme/life/pull/4"), done);
        assertTrue(nothing.contains(messages.getString("task.completedNoChanges")), nothing);
        assertTrue(failed.contains("#3") && failed.contains(messages.getString("failure.TIMEOUT")), failed);
        assertFalse(done.contains("\n\n"), "one short message: " + done);
    }

    @Test
    void refusedCorrectionSaysWhy() {
        String stale = renderer.render(OutboxKind.CORRECTION_REFUSED, Json.object().put("taskId", 42).put("reason", "stale")).html();
        String busy = renderer.render(OutboxKind.CORRECTION_REFUSED,
                Json.object().put("taskId", 42).put("reason", "phase").put("phase", "EXECUTING")).html();

        String notRequester = renderer.render(OutboxKind.CORRECTION_REFUSED,
                Json.object().put("taskId", 42).put("reason", "requester").put("requester", "Bold")).html();

        assertEquals(new java.text.MessageFormat(messages.getString("task.correctionStale")).format(new Object[] {"42"}), stale);
        assertTrue(busy.contains(messages.getString("phase.EXECUTING")), busy);
        assertTrue(notRequester.contains("#42") && notRequester.contains("Bold"), notRequester);
    }

    @Test
    void cancelRetryAndFollowUpRefuseNonRequestersToo() {
        String cancel = renderer.render(OutboxKind.CANCEL_REFUSED,
                Json.object().put("taskId", 7).put("reason", "requester").put("requester", "Bold")).html();
        String retry = renderer.render(OutboxKind.RETRY_REFUSED,
                Json.object().put("taskId", 7).put("reason", "requester").put("requester", "Bold")).html();
        String followUp = renderer.render(OutboxKind.FOLLOW_UP_REFUSED,
                Json.object().put("taskId", 7).put("reason", "requester").put("requester", "Bold")).html();

        assertTrue(cancel.contains("#7") && cancel.contains("Bold"), cancel);
        assertTrue(retry.contains("#7") && retry.contains("Bold"), retry);
        assertTrue(followUp.contains("#7") && followUp.contains("Bold"), followUp);
    }

    @Test
    void statusShowsRunningWorkWithTheAgentsLatestActionThenQueuedAndAwaiting() {
        String html = renderer.render(OutboxKind.STATUS, statusPayload()).html();

        assertTrue(html.contains(messages.getString("status.running")), html);
        assertTrue(html.contains("🟡 <b>#3</b> life · " + messages.getString("kind.EXECUTE") + " · 4 мин · 5 алхам"), html);
        assertTrue(html.contains("Fix the &lt;login&gt; timeout"), html);
        assertTrue(html.contains("└ Bash: ./gradlew test"), html);
        assertTrue(html.contains(messages.getString("status.queued")), html);
        assertTrue(html.contains("🔴 <b>#4</b> life · " + messages.getString("kind.PLAN")), html);
        assertTrue(html.contains(messages.getString("status.awaiting")), html);
        assertTrue(html.contains("🟢 <b>#2</b> life · Bold · 1 цаг"), html);
        assertTrue(html.indexOf("#3") < html.indexOf("#4") && html.indexOf("#4") < html.indexOf("#2"), html);
    }

    @Test
    void privateStatusHasARowOfPriorityButtonsPerOwnTaskWithTheCurrentOneMarked() {
        ObjectNode payload = statusPayload();
        payload.putArray("mine").addObject().put("taskId", 4).put("priority", "URGENT");

        Renderer.Rendered rendered = renderer.render(OutboxKind.STATUS, payload);

        assertEquals(List.of(List.of(new Renderer.Button("#4 ✓🔴", "prio:4:URGENT"), new Renderer.Button("#4 🟡", "prio:4:NORMAL"),
                new Renderer.Button("#4 🟢", "prio:4:LOW"))), rendered.keyboard());
    }

    @Test
    void statusOfARunStillPreparingShowsNoActivityLine() {
        ObjectNode payload = Json.object();
        payload.putArray("running").addObject().put("taskId", 3).put("project", "life").put("title", "Fix it").put("kind", "PLAN")
                .put("priority", "NORMAL").put("startedAt", "2026-09-17T10:29:50Z");
        payload.putArray("queued");
        payload.putArray("awaitingApproval");
        payload.putArray("mine");

        String html = renderer.render(OutboxKind.STATUS, payload).html();

        assertTrue(html.contains("<b>#3</b> life · " + messages.getString("kind.PLAN") + " · " + messages.getString("age.justNow")), html);
        assertTrue(rendered(payload).keyboard().isEmpty());
        assertFalse(html.contains("└"), html);
    }

    @Test
    void aQueuedRunWaitingForItsComputerSaysSoOnItsStatusLine() {
        ObjectNode payload = Json.object();
        payload.putArray("running");
        payload.putArray("awaitingApproval");
        payload.putArray("mine");
        payload.putArray("queued").addObject().put("taskId", 7).put("project", "alm")
                .put("title", "Fix the login timeout").put("kind", "PLAN").put("priority", "NORMAL")
                .put("queuedAt", Instant.now().toString()).put("waitingForWorker", true);

        String html = renderer.render(OutboxKind.STATUS, payload).html();

        assertTrue(html.contains(messages.getString("status.waitingForWorker")), html);
    }

    @Test
    void statusWithNothingGoingOnSaysSo() {
        ObjectNode payload = Json.object();
        payload.putArray("running");
        payload.putArray("queued");
        payload.putArray("awaitingApproval");
        payload.putArray("mine");

        assertEquals(messages.getString("status.empty"), renderer.render(OutboxKind.STATUS, payload).html());
    }

    @Test
    void historyShowsEachOutcomeWithPullRequestFailureCostAndAge() {
        String html = renderer.render(OutboxKind.HISTORY, historyPayload()).html();

        assertTrue(html.contains("✅ 🟡 <b>#2</b> life · Bold · 09-17 07:00 · $0.42"), html);
        assertTrue(html.contains("https://github.com/acme/life/pull/1"), html);
        assertTrue(html.contains("❌ <b>#5</b> life · Ali &lt;qa&gt; · 09-17 05:40"), html);
        assertTrue(html.contains(messages.getString("failure.DELIVERY")), html);
        assertTrue(html.contains("🚫 <b>#4</b>"), html);
        assertTrue(html.contains("/history"), "points to the per-task timeline: " + html);
    }

    @Test
    void historyTaskWithoutCostShowsNoCostSeparator() {
        String html = renderer.render(OutboxKind.HISTORY, historyPayload()).html();

        int date = html.indexOf("09-16 10:00");
        assertTrue(date >= 0, html);
        assertEquals("", html.substring(date + "09-16 10:00".length(), html.indexOf('\n', date)),
                "no cost shown for a task with costUsd null: " + html);
    }

    @Test
    void emptyHistorySaysSo() {
        ObjectNode payload = Json.object();
        payload.putArray("tasks");

        assertEquals(messages.getString("history.empty"), renderer.render(OutboxKind.HISTORY, payload).html());
    }

    @Test
    void statsShowTheSummaryWithButtonsForEveryViewAndPeriod() {
        Renderer.Rendered rendered = renderer.render(OutboxKind.STATS, statsPayload("me"));

        String html = rendered.html();
        assertTrue(html.contains(messages.getString("stats.view.me")) && html.contains(messages.getString("stats.period.month")), html);
        assertTrue(html.contains("12") && html.contains("✅ 9") && html.contains("❌ 1") && html.contains("🚫 2"), html);
        assertTrue(html.contains("$4.10") && html.contains("$0.34"), html);
        assertTrue(html.contains("14 мин"), html);
        assertTrue(html.contains("75%"), html);
        List<List<Renderer.Button>> keyboard = rendered.keyboard();
        assertEquals(List.of(new Renderer.Button("✓ " + messages.getString("stats.view.me"), "stats:month:me"),
                new Renderer.Button("backend", "stats:month:group:backend"), new Renderer.Button("mobile", "stats:month:group:mobile")),
                keyboard.get(0));
        assertEquals(new Renderer.Button(messages.getString("stats.view.people"), "stats:month:people"), keyboard.get(1).get(0));
        assertEquals(List.of(new Renderer.Button(messages.getString("stats.period.week"), "stats:week:me"),
                new Renderer.Button("✓ " + messages.getString("stats.period.month"), "stats:month:me"),
                new Renderer.Button(messages.getString("stats.period.all"), "stats:all:me")), keyboard.get(2));
    }

    @Test
    void statsWithoutCostShowNoCostFigures() {
        ObjectNode payload = statsPayload("group:backend");
        ((ObjectNode) payload.get("summary")).putNull("costUsd").putNull("averageCostUsd");
        payload.putArray("people").addObject().put("name", "Ali").put("tasks", 2).put("completed", 1).putNull("costUsd");

        String html = renderer.render(OutboxKind.STATS, payload).html();

        assertFalse(html.contains("$"), html);
        assertTrue(html.contains("Ali") && html.contains("2 даалгавар"), html);
    }

    @Test
    void groupStatsInAGroupChatOfferNoPersonalView() {
        ObjectNode payload = statsPayload("group:backend").put("canViewMe", false);
        payload.putArray("groups").removeAll().add("backend");

        Renderer.Rendered rendered = renderer.render(OutboxKind.STATS, payload);

        assertTrue(rendered.html().contains("backend"), rendered.html());
        assertEquals("✓ backend", rendered.keyboard().get(0).get(0).text());
        assertTrue(rendered.keyboard().stream().flatMap(List::stream).noneMatch(button -> button.data().endsWith(":me")));
    }

    @Test
    void peopleStatsListEachPersonAndEmptyStatsSaySo() {
        ObjectNode people = statsPayload("people");
        people.putArray("people").addObject().put("name", "Sara <qa>").put("tasks", 2).put("completed", 1).put("costUsd", "0.10");
        ObjectNode empty = statsPayload("me");
        ((ObjectNode) empty.get("summary")).put("tasks", 0);

        String peopleHtml = renderer.render(OutboxKind.STATS, people).html();
        String emptyHtml = renderer.render(OutboxKind.STATS, empty).html();

        assertTrue(peopleHtml.contains("Sara &lt;qa&gt;") && peopleHtml.contains("$0.10"), peopleHtml);
        assertTrue(emptyHtml.contains(messages.getString("stats.empty")), emptyHtml);
    }

    @Test
    void timelineShowsEachRunWithTimeDurationAndCostThenTheOutcome() {
        String html = renderer.render(OutboxKind.TASK_TIMELINE, timelinePayload()).html();

        assertTrue(html.contains("<b>#2</b> life · Bold"), html);
        assertTrue(html.contains("2026-09-17 10:00"), html);
        assertTrue(html.contains("10:00 📋"), html);
        assertTrue(html.contains("1 мин 30 сек · $0.16"), html);
        assertTrue(html.contains("10:05 ✏️") && html.contains("Also describe the &lt;logs&gt; target"), html);
        assertTrue(html.contains("10:07 ▶️") && html.contains("1 мин 2 сек · $0.26"), html);
        assertTrue(html.contains("✅") && html.contains("https://github.com/acme/life/pull/1"), html);
        assertTrue(html.contains("$0.52"), html);
    }

    @Test
    void timelineOfSomeoneElsesTaskShowsOnlyTheHeadlineWithNoRunsOrTotal() {
        ObjectNode payload = timelinePayload().put("headline", true).putNull("costUsd");

        String html = renderer.render(OutboxKind.TASK_TIMELINE, payload).html();

        assertTrue(html.contains("<b>#2</b> life · Bold"), html);
        assertTrue(html.contains("✅") && html.contains("https://github.com/acme/life/pull/1"), html);
        assertFalse(html.contains("10:00 📋"), "someone else's runs are not shown: " + html);
        String totalPrefix = messages.getString("timeline.total").substring(0, messages.getString("timeline.total").indexOf('{'));
        assertFalse(html.contains(totalPrefix), "someone else's cost is not shown: " + html);
    }

    @Test
    void timelineOfAFailedRunNamesTheReason() {
        ObjectNode payload = timelinePayload().put("phase", "FAILED").put("failureReason", "AGENT").putNull("prUrl");
        ((ObjectNode) payload.get("runs").get(2)).put("status", "FAILED").put("failureReason", "AGENT");

        String html = renderer.render(OutboxKind.TASK_TIMELINE, payload).html();

        assertTrue(html.contains("❌ " + messages.getString("failure.AGENT")), html);
    }

    @Test
    void privateHelpExplainsGivingATaskAndListsThePrivateCommands() {
        ObjectNode payload = Json.object().put("bot", "dispatch_backend_bot").put("privateChat", true);
        payload.putArray("projects").addObject().put("name", "life").putNull("alias");

        String html = renderer.render(OutboxKind.HELP, payload).html();

        assertTrue(html.contains("/status") && html.contains("/history") && html.contains("/cancel"), html);
        assertTrue(html.contains("<code>/task"), html);
    }

    @Test
    void everyKindRendersWithinTelegramLimitsWithoutPlaceholders() {
        for (OutboxKind kind : OutboxKind.values()) {
            for (boolean fellBack : new boolean[] {false, true}) {
                Renderer.Rendered rendered = renderer.render(kind, samplePayload(kind), fellBack);

                assertFalse(rendered.html().isBlank(), kind.name());
                assertTrue(rendered.html().length() <= 4096, kind.name());
                assertFalse(rendered.html().matches("(?s).*\\{\\d}.*"), kind + " left a placeholder: " + rendered.html());
            }
        }
    }

    @Test
    void aPrivateMessageThatFellBackToTheGroupShowsNoneOfItsContent() {
        ObjectNode plan = Json.object().put("taskId", 42).put("planSeq", 1).put("project", "alm")
                .put("costUsd", "0.10").put("durationSeconds", 5);
        plan.putObject("plan").put("understanding", "Rotate the signing key in secrets.env").putArray("steps").add("Edit secrets.env");
        ObjectNode completed = Json.object().put("taskId", 43).put("project", "alm")
                .put("prUrl", "https://github.com/acme/alm/pull/9").put("filesChanged", 1)
                .put("summary", "Changed the password check");

        for (var message : List.of(renderer.render(OutboxKind.PLAN_READY, plan, true),
                renderer.render(OutboxKind.TASK_COMPLETED, completed, true))) {
            assertFalse(message.html().contains("secrets.env"), message.html());
            assertFalse(message.html().contains("password"), message.html());
            assertFalse(message.html().contains("pull/9"), message.html());
            assertTrue(message.keyboard().isEmpty(), "no Approve button in the group");
            assertNull(message.document());
            assertTrue(message.html().contains("dispatch_backend_bot"), message.html());
        }
    }

    private static ObjectNode planPayload(List<String> steps, List<String> questions) {
        ObjectNode payload = Json.object().put("taskId", 42).put("planSeq", 1).put("project", "autoland-management")
                .put("costUsd", "0.168185").put("durationSeconds", 110);
        ObjectNode plan = payload.putObject("plan").put("understanding", "Login times out after 30s & users retry");
        plan.putArray("findings").add("AuthClient.java:14 hard-codes 30s");
        steps.forEach(plan.putArray("steps")::add);
        plan.putArray("risks").add("Slower error page");
        questions.forEach(plan.putArray("questions")::add);
        return payload;
    }

    private static ObjectNode draftPayload(List<String> projects, String project, String status, Long taskId) {
        ObjectNode payload = Json.object().put("draftId", 5).put("title", "Fix the <login> timeout").put("project", project)
                .put("status", status).put("taskId", taskId).putNull("priority");
        projects.forEach(name -> payload.withArray("projects").addObject().put("name", name).putNull("alias"));
        return payload;
    }

    private static ObjectNode joinPayload(String status) {
        ObjectNode payload = Json.object().put("requestId", 7).put("name", "Ali <x>").put("username", "ali_dev").put("userId", 555)
                .put("status", status).put("decidedBy", status.equals("OPEN") ? null : "Nomin");
        payload.putArray("groups").add("backend").add("mobile");
        return payload;
    }

    private static ObjectNode statsPayload(String view) {
        ObjectNode payload = Json.object().put("view", view).put("period", "month").put("canViewMe", true);
        payload.putArray("groups").add("backend").add("mobile");
        payload.putObject("summary").put("tasks", 12).put("completed", 9).put("failed", 1).put("rejected", 2).put("cancelled", 0)
                .put("active", 0).put("pullRequests", 9).put("costUsd", "4.10").put("averageCostUsd", "0.34")
                .put("medianMinutesToPr", 14).put("approvedWithoutCorrectionPercent", 75);
        payload.putArray("people");
        return payload;
    }

    private Renderer.Rendered rendered(ObjectNode statusPayload) {
        return renderer.render(OutboxKind.STATUS, statusPayload);
    }

    private static ObjectNode statusPayload() {
        ObjectNode payload = Json.object();
        payload.putArray("running").addObject().put("taskId", 3).put("project", "life").put("title", "Fix the <login> timeout")
                .put("kind", "EXECUTE").put("priority", "NORMAL").put("startedAt", "2026-09-17T10:26:00Z").put("steps", 5)
                .put("lastAction", "Bash: ./gradlew test");
        payload.putArray("queued").addObject().put("taskId", 4).put("project", "life").put("title", "Rename the report")
                .put("kind", "PLAN").put("priority", "URGENT").put("queuedAt", "2026-09-17T10:29:30Z");
        payload.putArray("awaitingApproval").addObject().put("taskId", 2).put("project", "life").put("title", "Add make help")
                .put("priority", "LOW").put("requester", "Bold").put("since", "2026-09-17T09:30:00Z");
        payload.putArray("mine");
        return payload;
    }

    private static ObjectNode historyPayload() {
        ObjectNode payload = Json.object();
        com.fasterxml.jackson.databind.node.ArrayNode tasks = payload.putArray("tasks");
        tasks.addObject().put("taskId", 2).put("project", "life").put("title", "Add make help").put("phase", "COMPLETED").put("priority", "NORMAL")
                .put("prUrl", "https://github.com/acme/life/pull/1").putNull("failureReason").put("costUsd", "0.42")
                .put("requester", "Bold").put("createdAt", "2026-09-17T07:00:00Z").put("completedAt", "2026-09-17T07:30:00Z");
        tasks.addObject().put("taskId", 5).put("project", "life").put("title", "Fix login").put("phase", "FAILED")
                .putNull("prUrl").put("failureReason", "DELIVERY").put("costUsd", "0.31").put("requester", "Ali <qa>")
                .put("createdAt", "2026-09-17T05:40:00Z").put("completedAt", "2026-09-17T06:00:00Z");
        tasks.addObject().put("taskId", 4).put("project", "life").put("title", "Old idea").put("phase", "REJECTED")
                .putNull("prUrl").putNull("failureReason").putNull("costUsd").put("requester", "Bold")
                .put("createdAt", "2026-09-16T10:00:00Z").put("completedAt", "2026-09-16T10:30:00Z");
        return payload;
    }

    private static ObjectNode timelinePayload() {
        ObjectNode payload = Json.object().put("taskId", 2).put("project", "life").put("title", "Add make help").put("requester", "Bold")
                .put("phase", "COMPLETED").put("prUrl", "https://github.com/acme/life/pull/1").putNull("failureReason")
                .put("createdAt", "2026-09-17T10:00:00Z").put("completedAt", "2026-09-17T10:08:02Z").put("costUsd", "0.52");
        com.fasterxml.jackson.databind.node.ArrayNode runs = payload.putArray("runs");
        runs.addObject().put("seq", 1).put("kind", "PLAN").put("status", "SUCCEEDED").put("requestedBy", "Bold").putNull("instruction")
                .put("queuedAt", "2026-09-17T10:00:00Z").put("startedAt", "2026-09-17T10:00:00Z").put("finishedAt", "2026-09-17T10:01:30Z")
                .put("costUsd", "0.16").putNull("failureReason");
        runs.addObject().put("seq", 2).put("kind", "PLAN").put("status", "SUCCEEDED").put("requestedBy", "Bold")
                .put("instruction", "Also describe the <logs> target").put("queuedAt", "2026-09-17T10:05:00Z")
                .put("startedAt", "2026-09-17T10:05:00Z").put("finishedAt", "2026-09-17T10:05:50Z").put("costUsd", "0.10").putNull("failureReason");
        runs.addObject().put("seq", 3).put("kind", "EXECUTE").put("status", "SUCCEEDED").put("requestedBy", "Bold").putNull("instruction")
                .put("queuedAt", "2026-09-17T10:07:00Z").put("startedAt", "2026-09-17T10:07:00Z").put("finishedAt", "2026-09-17T10:08:02Z")
                .put("costUsd", "0.26").putNull("failureReason");
        return payload;
    }

    @Test
    void timelineShowsARetryWithWhoAskedAndWhichStepRanAgain() {
        ObjectNode payload = timelinePayload();
        payload.withArray("runs").addObject().put("seq", 4).put("kind", "DELIVER").put("cause", "RETRY").put("status", "SUCCEEDED")
                .put("requestedBy", "Ali").putNull("instruction").put("queuedAt", "2026-09-17T10:09:00Z")
                .put("startedAt", "2026-09-17T10:09:00Z").put("finishedAt", "2026-09-17T10:09:05Z").putNull("costUsd").putNull("failureReason");

        String html = renderer.render(OutboxKind.TASK_TIMELINE, payload).html();

        assertTrue(html.contains("🔁 Дахин оролдлого (Ali): хүргэлт"), html);
    }

    private static ObjectNode completedPayload(String prUrl, int filesChanged, List<String> denials) {
        ObjectNode payload = Json.object().put("taskId", 42).put("project", "autoland-management").put("prUrl", prUrl)
                .put("filesChanged", filesChanged).put("summary", "Made the timeout <configurable>.")
                .put("costUsd", "0.42").put("durationSeconds", 250);
        denials.forEach(payload.putArray("denials")::add);
        return payload;
    }

    private static ObjectNode samplePayload(OutboxKind kind) {
        return switch (kind) {
            case TASK_QUEUED -> Json.object().put("taskId", 1).put("project", "autoland-management").put("requester", "Bold")
                    .put("priority", "URGENT").put("title", "Fix the login timeout");
            case TOPIC_CREATE -> Json.object().put("taskId", 7).put("project", "life").put("title", "Fix it");
            case DRAFT_PROMPT -> draftPayload(List.of("alm", "crm", "life", "billing"), null, "OPEN", null);
            case DRAFT_EXPIRED -> Json.object();
            case JOIN_REQUEST -> joinPayload("OPEN");
            case JOIN_REQUESTED, JOIN_DENIED -> Json.object();
            case MANAGE -> Json.object().put("url", "https://dispatch.example.com");
            case JOIN_APPROVED -> Json.object().put("group", "backend");
            case PRIVATE_ONLY -> Json.object().put("bot", "dispatch_backend_bot");
            case NO_PROJECTS -> Json.object();
            case TASK_COMPLETED_SHORT -> Json.object().put("taskId", 1).put("project", "life")
                    .put("prUrl", "https://github.com/acme/alm/pull/7").put("filesChanged", 2);
            case TASK_FAILED_SHORT -> Json.object().put("taskId", 1).put("reason", "DELIVERY");
            case PLAN_READY -> planPayload(List.of("Do it"), List.of());
            case TASK_FAILED -> Json.object().put("taskId", 1).put("reason", "AGENT").put("detail", "boom");
            case TASK_REJECTED, TASK_CANCELLED, EXECUTION_QUEUED, CORRECTION_QUEUED -> Json.object().put("taskId", 1).put("by", "Ali");
            case CORRECTION_REFUSED -> Json.object().put("taskId", 1).put("reason", "phase").put("phase", "PLANNING");
            case TASK_COMPLETED -> completedPayload("https://github.com/acme/alm/pull/7", 1, List.of("Bash: gh pr list"));
            case STATUS -> statusPayload();
            case HISTORY -> historyPayload();
            case STATS -> statsPayload("me");
            case TASK_TIMELINE -> timelinePayload();
            case TASK_NOT_FOUND -> Json.object().put("taskId", 99);
            case CANCEL_REFUSED -> Json.object().put("taskId", 1).put("phase", "REJECTED");
            case RETRY_QUEUED -> Json.object().put("taskId", 1).put("by", "Ali").put("kind", "DELIVER");
            case RETRY_REFUSED -> Json.object().put("taskId", 1).put("phase", "COMPLETED");
            case FOLLOW_UP_QUEUED -> Json.object().put("taskId", 1).put("by", "Ali");
            case FOLLOW_UP_REFUSED -> Json.object().put("taskId", 1).put("reason", "notExecuted").put("phase", "FAILED");
            case NOT_ALLOWED -> Json.object().put("name", "Sara");
            case UNKNOWN_PROJECT -> {
                ObjectNode payload = Json.object().put("given", "billing");
                payload.putArray("projects").addObject().put("name", "autoland-management").put("alias", "alm");
                yield payload;
            }
            case PROJECT_UNAVAILABLE -> Json.object().put("project", "crm").put("reason", "no clone");
            case TASK_USAGE -> Json.object();
            case PROJECTS -> {
                ObjectNode payload = Json.object();
                payload.putArray("projects").addObject().put("name", "crm").putNull("alias").put("baseBranch", "main")
                        .put("unavailable", "cloning https://github.com/acme/crm.git");
                yield payload;
            }
            case HELP -> {
                ObjectNode payload = Json.object().put("bot", "dispatch_backend_bot");
                payload.putArray("projects").addObject().put("name", "crm").putNull("alias");
                yield payload;
            }
            case WORKER_PAIRING -> {
                ObjectNode payload = Json.object().put("personal", false).put("code", "ABCD2345").put("minutes", 10)
                        .put("url", "https://team.example.com");
                payload.putArray("workers").addObject().put("id", 3).put("name", "ann-laptop").putNull("lastSeenAt");
                yield payload;
            }
            case WORKER_REVOKED -> Json.object().put("workerId", 3).put("found", true);
            case WORKER_USAGE -> Json.object();
            case WORKER_WAITING -> Json.object().put("taskId", 7);
        };
    }
}
