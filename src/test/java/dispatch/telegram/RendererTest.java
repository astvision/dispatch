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
    private final Renderer renderer = new Renderer(messages, clock);

    @Test
    void planIsRenderedWithEscapedContentNumberedStepsCostDurationAndRejectButton() {
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
        assertEquals(List.of(new Renderer.Button(messages.getString("button.reject"), "reject:42:1")), rendered.buttons());
    }

    @Test
    void openQuestionsAreListedWithTheNeedToAnswerThem() {
        Renderer.Rendered rendered = renderer.render(OutboxKind.PLAN_READY,
                planPayload(List.of(), List.of("Which environment reads auth.timeout?")));

        assertTrue(rendered.html().contains(messages.getString("plan.questions")), rendered.html());
        assertTrue(rendered.html().contains("1. Which environment reads auth.timeout?"), rendered.html());
        assertTrue(rendered.html().contains(messages.getString("plan.questionsHint")), rendered.html());
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
        assertEquals("reject:42:1", rendered.buttons().getFirst().data());
    }

    @Test
    void failureNamesTheReasonAndKeepsHugeDetailWithinTheLimit() {
        ObjectNode payload = Json.object().put("taskId", 42).put("reason", "TIMEOUT").put("detail", "x".repeat(6000));

        Renderer.Rendered rendered = renderer.render(OutboxKind.TASK_FAILED, payload);

        assertTrue(rendered.html().contains(messages.getString("failure.TIMEOUT")), rendered.html());
        assertTrue(rendered.html().length() <= 4096, "message limit");
    }

    @Test
    void taskListShowsPhaseAndAge() {
        ObjectNode payload = Json.object();
        payload.putArray("tasks").addObject().put("id", 7).put("title", "Fix <login>").put("project", "autoland-management")
                .put("phase", "AWAITING_APPROVAL").put("createdAt", "2026-09-17T10:18:00Z");

        String html = renderer.render(OutboxKind.TASK_LIST, payload).html();

        assertTrue(html.contains("#7"), html);
        assertTrue(html.contains("Fix &lt;login&gt;"), html);
        assertTrue(html.contains(messages.getString("phase.AWAITING_APPROVAL")), html);
        assertTrue(html.contains("12 мин"), html);
    }

    @Test
    void emptyTaskListSaysSo() {
        ObjectNode payload = Json.object();
        payload.putArray("tasks");

        assertEquals(messages.getString("tasks.empty"), renderer.render(OutboxKind.TASK_LIST, payload).html());
    }

    @Test
    void everyKindRendersWithinTelegramLimitsWithoutPlaceholders() {
        for (OutboxKind kind : OutboxKind.values()) {
            Renderer.Rendered rendered = renderer.render(kind, samplePayload(kind));

            assertFalse(rendered.html().isBlank(), kind.name());
            assertTrue(rendered.html().length() <= 4096, kind.name());
            assertFalse(rendered.html().matches("(?s).*\\{\\d}.*"), kind + " left a placeholder: " + rendered.html());
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

    private static ObjectNode samplePayload(OutboxKind kind) {
        return switch (kind) {
            case TASK_QUEUED -> Json.object().put("taskId", 1).put("project", "autoland-management");
            case PLAN_READY -> planPayload(List.of("Do it"), List.of());
            case TASK_FAILED -> Json.object().put("taskId", 1).put("reason", "AGENT").put("detail", "boom");
            case TASK_REJECTED, TASK_CANCELLED -> Json.object().put("taskId", 1).put("by", "Ali");
            case TASK_LIST -> {
                ObjectNode payload = Json.object();
                payload.putArray("tasks");
                yield payload;
            }
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
