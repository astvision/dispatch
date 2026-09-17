package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.domain.OutboxKind;
import dispatch.testing.TestClock;
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
        assertEquals(List.of(new Renderer.Button(messages.getString("button.approve"), "approve:42:1"),
                new Renderer.Button(messages.getString("button.reject"), "reject:42:1")), rendered.buttons());
    }

    @Test
    void openQuestionsAreListedWithTheNeedToAnswerThem() {
        Renderer.Rendered rendered = renderer.render(OutboxKind.PLAN_READY,
                planPayload(List.of(), List.of("Which environment reads auth.timeout?")));

        assertTrue(rendered.html().contains(messages.getString("plan.questions")), rendered.html());
        assertTrue(rendered.html().contains("1. Which environment reads auth.timeout?"), rendered.html());
        assertTrue(rendered.html().contains(messages.getString("plan.questionsHint")), rendered.html());
        assertEquals(List.of(new Renderer.Button(messages.getString("button.reject"), "reject:42:1")), rendered.buttons(),
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
        assertEquals("approve:42:1", rendered.buttons().getFirst().data());
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
    void refusedCorrectionSaysWhy() {
        String stale = renderer.render(OutboxKind.CORRECTION_REFUSED, Json.object().put("taskId", 42).put("reason", "stale")).html();
        String busy = renderer.render(OutboxKind.CORRECTION_REFUSED,
                Json.object().put("taskId", 42).put("reason", "phase").put("phase", "EXECUTING")).html();

        assertEquals(new java.text.MessageFormat(messages.getString("task.correctionStale")).format(new Object[] {"42"}), stale);
        assertTrue(busy.contains(messages.getString("phase.EXECUTING")), busy);
    }

    @Test
    void statusShowsRunningWorkWithTheAgentsLatestActionThenQueuedAndAwaiting() {
        String html = renderer.render(OutboxKind.STATUS, statusPayload()).html();

        assertTrue(html.contains(messages.getString("status.running")), html);
        assertTrue(html.contains("<b>#3</b> life · " + messages.getString("kind.EXECUTE") + " · 4 мин · 5 алхам"), html);
        assertTrue(html.contains("Fix the &lt;login&gt; timeout"), html);
        assertTrue(html.contains("└ Bash: ./gradlew test"), html);
        assertTrue(html.contains(messages.getString("status.queued")), html);
        assertTrue(html.contains("<b>#4</b> life · " + messages.getString("kind.PLAN")), html);
        assertTrue(html.contains(messages.getString("status.awaiting")), html);
        assertTrue(html.contains("<b>#2</b> life · Bold · 1 цаг"), html);
        assertTrue(html.indexOf("#3") < html.indexOf("#4") && html.indexOf("#4") < html.indexOf("#2"), html);
    }

    @Test
    void statusOfARunStillPreparingShowsNoActivityLine() {
        ObjectNode payload = Json.object();
        payload.putArray("running").addObject().put("taskId", 3).put("project", "life").put("title", "Fix it").put("kind", "PLAN")
                .put("startedAt", "2026-09-17T10:29:50Z");
        payload.putArray("queued");
        payload.putArray("awaitingApproval");

        String html = renderer.render(OutboxKind.STATUS, payload).html();

        assertTrue(html.contains("<b>#3</b> life · " + messages.getString("kind.PLAN") + " · " + messages.getString("age.justNow")), html);
        assertFalse(html.contains("└"), html);
    }

    @Test
    void statusWithNothingGoingOnSaysSo() {
        ObjectNode payload = Json.object();
        payload.putArray("running");
        payload.putArray("queued");
        payload.putArray("awaitingApproval");

        assertEquals(messages.getString("status.empty"), renderer.render(OutboxKind.STATUS, payload).html());
    }

    @Test
    void historyShowsEachOutcomeWithPullRequestFailureCostAndAge() {
        String html = renderer.render(OutboxKind.HISTORY, historyPayload()).html();

        assertTrue(html.contains("✅ <b>#2</b> life · $0.42 · 3 цаг"), html);
        assertTrue(html.contains("https://github.com/acme/life/pull/1"), html);
        assertTrue(html.contains("❌ <b>#5</b> life"), html);
        assertTrue(html.contains(messages.getString("failure.DELIVERY")), html);
        assertTrue(html.contains("🚫 <b>#4</b>"), html);
        assertTrue(html.contains("/history"), "points to the per-task timeline: " + html);
    }

    @Test
    void emptyHistorySaysSo() {
        ObjectNode payload = Json.object();
        payload.putArray("tasks");

        assertEquals(messages.getString("history.empty"), renderer.render(OutboxKind.HISTORY, payload).html());
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
    void timelineOfAFailedRunNamesTheReason() {
        ObjectNode payload = timelinePayload().put("phase", "FAILED").put("failureReason", "AGENT").putNull("prUrl");
        ((ObjectNode) payload.get("runs").get(2)).put("status", "FAILED").put("failureReason", "AGENT");

        String html = renderer.render(OutboxKind.TASK_TIMELINE, payload).html();

        assertTrue(html.contains("❌ " + messages.getString("failure.AGENT")), html);
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
    void messageThatFellBackToTheGroupAsksTheRequesterToPressStart() {
        String html = renderer.render(OutboxKind.TASK_QUEUED, samplePayload(OutboxKind.TASK_QUEUED), true).html();

        assertTrue(html.contains("@dispatch_backend_bot"), html);
        assertTrue(html.contains("Start"), html);
    }

    @Test
    void longestCompletionStillFitsWithTheStartHint() {
        ObjectNode payload = completedPayload("https://github.com/acme/alm/pull/7", 3,
                java.util.Collections.nCopies(9, "Bash: " + "<&>".repeat(80)));
        payload.put("summary", "<&>".repeat(3000)).put("project", "p".repeat(100));

        String html = renderer.render(OutboxKind.TASK_COMPLETED, payload, true).html();

        assertTrue(html.length() <= 4096, "message limit, got " + html.length());
        assertTrue(html.contains("Start"), "the hint survives");
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

    private static ObjectNode statusPayload() {
        ObjectNode payload = Json.object();
        payload.putArray("running").addObject().put("taskId", 3).put("project", "life").put("title", "Fix the <login> timeout")
                .put("kind", "EXECUTE").put("startedAt", "2026-09-17T10:26:00Z").put("steps", 5).put("lastAction", "Bash: ./gradlew test");
        payload.putArray("queued").addObject().put("taskId", 4).put("project", "life").put("title", "Rename the report")
                .put("kind", "PLAN").put("queuedAt", "2026-09-17T10:29:30Z");
        payload.putArray("awaitingApproval").addObject().put("taskId", 2).put("project", "life").put("title", "Add make help")
                .put("requester", "Bold").put("since", "2026-09-17T09:30:00Z");
        return payload;
    }

    private static ObjectNode historyPayload() {
        ObjectNode payload = Json.object();
        com.fasterxml.jackson.databind.node.ArrayNode tasks = payload.putArray("tasks");
        tasks.addObject().put("taskId", 2).put("project", "life").put("title", "Add make help").put("phase", "COMPLETED")
                .put("prUrl", "https://github.com/acme/life/pull/1").putNull("failureReason").put("costUsd", "0.42")
                .put("completedAt", "2026-09-17T07:30:00Z");
        tasks.addObject().put("taskId", 5).put("project", "life").put("title", "Fix login").put("phase", "FAILED")
                .putNull("prUrl").put("failureReason", "DELIVERY").put("costUsd", "0.31").put("completedAt", "2026-09-17T06:00:00Z");
        tasks.addObject().put("taskId", 4).put("project", "life").put("title", "Old idea").put("phase", "REJECTED")
                .putNull("prUrl").putNull("failureReason").putNull("costUsd").put("completedAt", "2026-09-16T10:30:00Z");
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

    private static ObjectNode completedPayload(String prUrl, int filesChanged, List<String> denials) {
        ObjectNode payload = Json.object().put("taskId", 42).put("project", "autoland-management").put("prUrl", prUrl)
                .put("filesChanged", filesChanged).put("summary", "Made the timeout <configurable>.")
                .put("costUsd", "0.42").put("durationSeconds", 250);
        denials.forEach(payload.putArray("denials")::add);
        return payload;
    }

    private static ObjectNode samplePayload(OutboxKind kind) {
        return switch (kind) {
            case TASK_QUEUED -> Json.object().put("taskId", 1).put("project", "autoland-management");
            case PLAN_READY -> planPayload(List.of("Do it"), List.of());
            case TASK_FAILED -> Json.object().put("taskId", 1).put("reason", "AGENT").put("detail", "boom");
            case TASK_REJECTED, TASK_CANCELLED, EXECUTION_QUEUED, CORRECTION_QUEUED -> Json.object().put("taskId", 1).put("by", "Ali");
            case CORRECTION_REFUSED -> Json.object().put("taskId", 1).put("reason", "phase").put("phase", "PLANNING");
            case TASK_COMPLETED -> completedPayload("https://github.com/acme/alm/pull/7", 1, List.of("Bash: gh pr list"));
            case STATUS -> statusPayload();
            case HISTORY -> historyPayload();
            case TASK_TIMELINE -> timelinePayload();
            case TASK_NOT_FOUND -> Json.object().put("taskId", 99);
            case CANCEL_REFUSED -> Json.object().put("taskId", 1).put("phase", "REJECTED");
            case NOT_ALLOWED -> Json.object().put("name", "Sara");
            case UNKNOWN_PROJECT -> {
                ObjectNode payload = Json.object().put("given", "billing");
                payload.putArray("projects").addObject().put("name", "autoland-management").put("alias", "alm");
                yield payload;
            }
            case PROJECT_UNAVAILABLE -> Json.object().put("project", "crm").put("reason", "no clone");
            case TASK_USAGE -> Json.object();
            case HELP -> {
                ObjectNode payload = Json.object().put("bot", "dispatch_backend_bot");
                payload.putArray("projects").addObject().put("name", "crm").putNull("alias");
                yield payload;
            }
        };
    }
}
