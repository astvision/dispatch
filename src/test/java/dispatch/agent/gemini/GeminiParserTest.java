package dispatch.agent.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The fixtures follow Gemini CLI's stream-json event types (packages/core/src/output/types.ts, 0.61); no Gemini account was
 * available to record a run.
 */
class GeminiParserTest {

    private static final Path WORKTREE = Path.of("/var/lib/dispatch/demo/worktrees/1");

    @Test
    void aPlanRunYieldsTheJsonAnswerEvenInsideAFence() throws IOException {
        GeminiParser parser = feed(true, "gemini-2.5-pro", "plan-success.jsonl");

        AgentResult result = parser.result(0, "");

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
        assertEquals("0b9d2c1e-7a53-4b5e-9d1a-2f6a8e4c3b21", result.sessionId());
        assertEquals("add() subtracts instead of adding", Json.read(result.structuredOutput()).path("understanding").asText());
        assertEquals("gemini-2.5-pro", result.model(), "as its init event names it");
        assertNull(result.requestedModel(), "it is the model asked for");
        assertNull(result.costUsd(), "Gemini CLI reports tokens, not money");
        assertEquals(2, parser.activity().steps());
        assertEquals("Bash: git log -1 --oneline", parser.activity().lastAction());
    }

    @Test
    void aFileToolIsShownRelativeToTheWorktree() {
        GeminiParser parser = new GeminiParser(false, null, WORKTREE);
        parser.accept("""
                {"type":"tool_use","timestamp":"t","tool_name":"replace","tool_id":"replace-1","parameters":{"file_path":"/var/lib/dispatch/demo/worktrees/1/src/calc.py","old_string":"a - b","new_string":"a + b"}}""");

        assertEquals("Edit: src/calc.py", parser.activity().lastAction());
    }

    @Test
    void anotherModelAnsweringIsReported() throws IOException {
        AgentResult result = feed(true, "gemini-3-pro", "plan-success.jsonl").result(0, "");

        assertEquals("gemini-2.5-pro", result.model());
        assertEquals("gemini-3-pro", result.requestedModel(), "shown on the plan, as for Claude");
    }

    @Test
    void aFailedResultIsReportedWithGeminisReason() throws IOException {
        AgentResult result = feed(false, null, "quota-error.jsonl").result(1, "");

        assertEquals(AgentOutcome.FAILED, result.outcome());
        assertEquals("Quota exceeded for gemini-2.5-pro; try again later", result.error());
        assertEquals("5d7e8f90-1a2b-4c3d-8e9f-0a1b2c3d4e5f", result.sessionId());
    }

    @Test
    void anExitWithoutAResultSaysWhatTheExitCodeMeans() {
        GeminiParser parser = new GeminiParser(false, null, WORKTREE);
        parser.accept("{\"type\":\"init\",\"timestamp\":\"t\",\"session_id\":\"s\",\"model\":\"gemini-2.5-pro\"}");

        assertEquals("gemini stopped: its session reached the turn limit (exit 53)", parser.result(53, "").error());
        assertEquals("gemini exited with code 42 without a result: Unknown argument: --frobnicate",
                parser.result(42, "Unknown argument: --frobnicate").error());
    }

    @Test
    void anExecutionSummaryIsTheTextAfterTheLastTool() {
        GeminiParser parser = new GeminiParser(false, null, WORKTREE);
        parser.accept("{\"type\":\"message\",\"timestamp\":\"t\",\"role\":\"assistant\",\"content\":\"Let me look.\",\"delta\":true}");
        parser.accept("{\"type\":\"tool_use\",\"timestamp\":\"t\",\"tool_name\":\"write_file\",\"tool_id\":\"w-1\",\"parameters\":{\"file_path\":\"/var/lib/dispatch/demo/worktrees/1/calc.py\",\"content\":\"x\"}}");
        parser.accept("{\"type\":\"message\",\"timestamp\":\"t\",\"role\":\"assistant\",\"content\":\"Fixed add().\",\"delta\":true}");
        parser.accept("{\"type\":\"result\",\"timestamp\":\"t\",\"status\":\"success\"}");

        AgentResult result = parser.result(0, "");

        assertEquals("Fixed add().", result.summary());
        assertNull(result.structuredOutput());
        assertEquals("Write: calc.py", parser.activity().lastAction());
    }

    private static GeminiParser feed(boolean structured, String requestedModel, String fixture) throws IOException {
        GeminiParser parser = new GeminiParser(structured, requestedModel, WORKTREE);
        try (InputStream in = GeminiParserTest.class.getResourceAsStream("/fixtures/gemini/" + fixture)) {
            new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().forEach(parser::accept);
        }
        return parser;
    }
}
