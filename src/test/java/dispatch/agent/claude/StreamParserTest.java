package dispatch.agent.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fixtures were recorded from Claude Code 2.1.274: planning (plan mode, --json-schema) and execution (auto mode, resumed). */
class StreamParserTest {

    @Test
    void successfulPlanRunYieldsPlanCostTurnsAndDenials() throws IOException {
        StreamParser parser = feed("plan", fixture("plan-success.jsonl"));

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
    void agentStartedInAnotherPermissionModeFailsEvenWithASuccessResult() throws IOException {
        StreamParser parser = feed("auto", List.of(fixture("plan-success.jsonl").getFirst()));
        assertTrue(parser.wrongPermissionMode(), "detected as soon as the init event arrives");
        fixture("plan-success.jsonl").stream().skip(1).forEach(parser::accept);

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
        StreamParser parser = feed("plan", List.of(fixture("plan-success.jsonl").getFirst()));

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
        StreamParser parser = feed("plan", fixture("plan-success.jsonl"));

        AgentResult result = parser.result(2, "segfault");

        assertEquals(AgentOutcome.FAILED, result.outcome());
        assertEquals("agent reported success but exited with code 2: segfault", result.error());
    }

    @Test
    void malformedLinesAreSkipped() throws IOException {
        List<String> lines = new java.util.ArrayList<>(fixture("plan-success.jsonl"));
        lines.add(3, "not json at all {");

        AgentResult result = feed("plan", lines).result(0, "");

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
    }

    private static StreamParser feed(String expectedPermissionMode, List<String> lines) {
        StreamParser parser = new StreamParser(expectedPermissionMode);
        lines.forEach(parser::accept);
        return parser;
    }

    private static List<String> fixture(String name) throws IOException {
        try (InputStream in = StreamParserTest.class.getResourceAsStream("/fixtures/claude/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }
}
