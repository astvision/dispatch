package dispatch.agent.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dispatch.agent.AgentActivity;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fixtures were recorded from Claude Code 2.1.274: planning runs (plan mode, --json-schema) with the first prompt, whose
 * plan still had open questions, and with the current one, then the execution run that resumed the latter (auto mode).
 */
class StreamParserTest {

    /** Where the fixtures were recorded, after sanitizing. */
    private static final Path WORKTREE = Path.of("/var/lib/dispatch/demo/worktrees/1");

    @Test
    void successfulPlanRunYieldsPlanCostTurnsAndDenials() throws IOException {
        StreamParser parser = feed("plan", fixture("plan-with-questions.jsonl"));

        AgentResult result = parser.result(0, "");

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
        assertEquals("63d36fba-124d-4737-8020-d37d4998abca", result.sessionId());
        assertEquals(0, new BigDecimal("0.168185").compareTo(result.costUsd()));
        assertEquals(9, result.turns());
        JsonNode plan = new ObjectMapper().readTree(result.structuredOutput());
        assertTrue(plan.path("understanding").asText().startsWith("Staging орчинд"));
        assertEquals(5, plan.path("steps").size());
        assertEquals(2, result.denials().size());
        assertEquals("Bash: mkdir -p /home/dispatch-demo/.claude/plans", result.denials().get(1));
        assertNull(result.error());
    }

    @Test
    void successfulExecutionRunYieldsTheAgentsSummary() throws IOException {
        StreamParser parser = feed("auto", fixture("execute-success.jsonl"));

        AgentResult result = parser.result(0, "");

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
        assertEquals("5e2d6d8a-eeff-4603-a3e1-ee8a2d12d18a", result.sessionId());
        assertTrue(result.summary().contains("AUTH_TIMEOUT_SECONDS"), result.summary());
        assertNull(result.structuredOutput());
        assertEquals(0, new BigDecimal("0.160872").compareTo(result.costUsd()));
        assertEquals(7, result.turns());
        assertEquals(List.of(), result.denials());
        assertFalse(parser.wrongPermissionMode());
    }

    @Test
    void executionRunReportsItsStepsAndLatestActionWithPathsRelativeToTheWorktree() throws IOException {
        List<String> lines = fixture("execute-success.jsonl");
        StreamParser parser = new StreamParser("auto", null, WORKTREE);
        assertEquals(new AgentActivity(0, null), parser.activity());

        lines.subList(0, 6).forEach(parser::accept);
        assertEquals(new AgentActivity(2, "Edit: src/main/java/demo/AuthClient.java"), parser.activity());

        lines.subList(6, 13).forEach(parser::accept);
        AgentActivity heredoc = parser.activity();
        assertEquals(4, heredoc.steps());
        assertTrue(heredoc.lastAction().startsWith("Bash: cd /tmp && mkdir -p authclient-test/demo"), heredoc.lastAction());
        assertFalse(heredoc.lastAction().contains("\n"), "one line: " + heredoc.lastAction());
        assertTrue(heredoc.lastAction().length() <= 120, heredoc.lastAction());

        lines.subList(13, lines.size()).forEach(parser::accept);
        assertEquals(new AgentActivity(6, "Bash: git status && git diff"), parser.activity());
    }

    @Test
    void agentStartedInAnotherPermissionModeFailsEvenWithASuccessResult() throws IOException {
        StreamParser parser = feed("auto", List.of(fixture("plan-with-questions.jsonl").getFirst()));
        assertTrue(parser.wrongPermissionMode(), "detected as soon as the init event arrives");
        fixture("plan-with-questions.jsonl").stream().skip(1).forEach(parser::accept);

        AgentResult result = parser.result(0, "");

        assertEquals(AgentOutcome.FAILED, result.outcome());
        assertTrue(result.error().contains("permission mode 'plan' instead of 'auto'"), result.error());
        assertNull(result.structuredOutput());
        assertNull(result.summary());
    }

    @Test
    void initWithoutPermissionModeCountsAsWrongMode() {
        StreamParser parser = feed("auto", List.of("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-1\"}"));

        AgentResult result = parser.result(143, "");

        assertTrue(parser.wrongPermissionMode());
        assertEquals(AgentOutcome.FAILED, result.outcome());
        assertTrue(result.error().contains("permission mode 'null' instead of 'auto'"), result.error());
    }

    @Test
    void budgetExhaustionIsReportedAsBudgetExceeded() throws IOException {
        StreamParser parser = feed("plan", fixture("plan-budget-exceeded.jsonl"));

        AgentResult result = parser.result(1, "");

        assertEquals(AgentOutcome.BUDGET_EXCEEDED, result.outcome());
        assertEquals("Reached maximum budget ($0.005)", result.error());
        assertEquals(0, new BigDecimal("0.07122").compareTo(result.costUsd()));
    }

    @Test
    void processThatExitsWithoutResultFailsWithExitCodeAndStderr() throws IOException {
        StreamParser parser = feed("plan", List.of(fixture("plan-with-questions.jsonl").getFirst()));

        AgentResult result = parser.result(143, "Terminated");

        assertEquals(AgentOutcome.FAILED, result.outcome());
        assertEquals("63d36fba-124d-4737-8020-d37d4998abca", result.sessionId());
        assertEquals("agent exited with code 143 without a result: Terminated", result.error());
        assertNull(result.structuredOutput());
    }

    @Test
    void errorResultFailsWithSubtypeAndErrors() {
        StreamParser parser = feed("plan", List.of("""
                {"type":"result","subtype":"error_during_execution","is_error":true,"errors":["API Error: 500","retry exhausted"],\
                "session_id":"s-1","total_cost_usd":0.01,"num_turns":1,"permission_denials":[]}"""));

        AgentResult result = parser.result(1, "");

        assertEquals(AgentOutcome.FAILED, result.outcome());
        assertEquals("error_during_execution: API Error: 500; retry exhausted", result.error());
        assertEquals("s-1", result.sessionId());
    }

    @Test
    void successResultWithNonZeroExitFails() throws IOException {
        StreamParser parser = feed("plan", fixture("plan-with-questions.jsonl"));

        AgentResult result = parser.result(2, "segfault");

        assertEquals(AgentOutcome.FAILED, result.outcome());
        assertEquals("agent reported success but exited with code 2: segfault", result.error());
    }

    @Test
    void malformedLinesAreSkipped() throws IOException {
        List<String> lines = new java.util.ArrayList<>(fixture("plan-with-questions.jsonl"));
        lines.add(3, "not json at all {");

        AgentResult result = feed("plan", lines).result(0, "");

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
    }

    @Test
    void theModelThatAnsweredIsComparedWithTheOneAskedFor() throws IOException {
        // Recorded with --model haiku: Claude Code started with Haiku, and every answer came from Sonnet 5.
        List<String> lines = fixture("plan-success.jsonl");

        AgentResult haiku = feed("plan", "haiku", lines).result(0, "");

        assertEquals("claude-sonnet-5", haiku.model());
        assertEquals("haiku", haiku.requestedModel(), "answered by another model than the one asked for");
        assertNull(feed("plan", "sonnet", lines).result(0, "").requestedModel(), "an alias names a family");
        assertNull(feed("plan", "sonnet[1m]", lines).result(0, "").requestedModel(), "the same family with a longer context");
        assertNull(feed("plan", "claude-sonnet-5", lines).result(0, "").requestedModel());
        assertEquals("claude-sonnet-4-5", feed("plan", "claude-sonnet-4-5", lines).result(0, "").requestedModel());
        assertNull(feed("plan", null, lines).result(0, "").requestedModel(), "nothing asked for: Claude Code's default");
    }

    @Test
    void everyModelThatAnsweredTheRunIsNamedButNotASubagents() {
        List<String> lines = List.of(
                "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-1\",\"permissionMode\":\"auto\"}",
                "{\"type\":\"assistant\",\"parent_tool_use_id\":null,\"message\":{\"model\":\"claude-opus-5\",\"content\":[]}}",
                "{\"type\":\"assistant\",\"parent_tool_use_id\":\"toolu_1\",\"message\":{\"model\":\"claude-haiku-4-5-20251001\",\"content\":[]}}",
                "{\"type\":\"assistant\",\"parent_tool_use_id\":null,\"message\":{\"model\":\"claude-sonnet-5\",\"content\":[]}}",
                "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"session_id\":\"s-1\",\"total_cost_usd\":0.01,\"num_turns\":2,\"permission_denials\":[]}");

        AgentResult result = feed("auto", "opus", lines).result(0, "");

        assertEquals("claude-opus-5, claude-sonnet-5", result.model());
        assertEquals("opus", result.requestedModel(), "Sonnet 5 answered too");
    }

    private static StreamParser feed(String expectedPermissionMode, List<String> lines) {
        return feed(expectedPermissionMode, null, lines);
    }

    private static StreamParser feed(String expectedPermissionMode, String requestedModel, List<String> lines) {
        StreamParser parser = new StreamParser(expectedPermissionMode, requestedModel, WORKTREE);
        lines.forEach(parser::accept);
        return parser;
    }

    private static List<String> fixture(String name) throws IOException {
        try (InputStream in = StreamParserTest.class.getResourceAsStream("/fixtures/claude/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }
}
