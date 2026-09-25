package dispatch.agent.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.Json;
import dispatch.agent.AgentActivity;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * usage-limit.jsonl was recorded from codex-cli 0.155.1 ({@code codex exec --json}); the success fixtures follow its
 * published event types (codex-rs/exec/src/exec_events.rs at rust-v0.155.1), since that account could not finish a run.
 */
class CodexParserTest {

    private static final Path WORKTREE = Path.of("/var/lib/dispatch/demo/worktrees/1");

    @Test
    void aPlanRunYieldsTheSchemaAnswerAsTheStructuredOutput() throws IOException {
        CodexParser parser = feed(true, "plan-success.jsonl");

        AgentResult result = parser.result(0, "");

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
        assertEquals("01a0d798-5aab-74d2-b4c8-a8c6ea63d8c1", result.sessionId(), "Codex's thread id, to resume it");
        assertEquals("add() subtracts instead of adding", Json.read(result.structuredOutput()).path("understanding").asText());
        assertEquals(List.of("Bash: touch notes.txt"), result.denials(), "a command the read-only sandbox declined");
        assertNull(result.costUsd(), "Codex reports tokens, not money");
        assertEquals("gpt-5-codex", result.model(), "the model it was asked for");
        assertNull(result.error());
    }

    @Test
    void anExecutionRunReportsItsSummaryAndEachToolOnce() throws IOException {
        CodexParser parser = feed(false, "execute-success.jsonl");

        AgentResult result = parser.result(0, "");
        AgentActivity activity = parser.activity();

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
        assertNull(result.structuredOutput(), "none was asked for");
        assertEquals("Fixed add() in calc.py to return a + b; add(2, 3) now prints 5.", result.summary());
        assertEquals(2, activity.steps(), "started and completed events of one item count once");
        assertEquals("Bash: python -c \"from calc import add; print(add(2, 3))\"", activity.lastAction());
    }

    @Test
    void aFileChangeIsShownRelativeToTheWorktree() {
        CodexParser parser = new CodexParser(false, null, WORKTREE);
        parser.accept("""
                {"type":"item.started","item":{"id":"item_0","type":"file_change","changes":[{"path":"/var/lib/dispatch/demo/worktrees/1/src/calc.py","kind":"update"},{"path":"/var/lib/dispatch/demo/worktrees/1/README.md","kind":"add"}],"status":"in_progress"}}""");

        assertEquals("Edit: src/calc.py (+1)", parser.activity().lastAction());
    }

    @Test
    void aTurnThatFailedReportsCodexsOwnReason() throws IOException {
        AgentResult result = feed(true, "usage-limit.jsonl").result(1, "");

        assertEquals(AgentOutcome.FAILED, result.outcome());
        assertTrue(result.error().startsWith("You’ve hit your usage limit."), result.error());
        assertEquals("01a0d798-5aab-74d2-b4c8-a8c6ea63d8c1", result.sessionId());
    }

    @Test
    void anAnswerThatIsNotJsonGivesNoPlan() {
        CodexParser parser = new CodexParser(true, null, WORKTREE);
        parser.accept("{\"type\":\"thread.started\",\"thread_id\":\"t-1\"}");
        parser.accept("{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"agent_message\",\"text\":\"Here is my plan: fix it.\"}}");
        parser.accept("{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":1,\"cached_input_tokens\":0,\"output_tokens\":1}}");

        AgentResult result = parser.result(0, "");

        assertEquals(AgentOutcome.SUCCEEDED, result.outcome());
        assertNull(result.structuredOutput(), "the plan step then fails with no plan, as for any agent");
        assertEquals("Here is my plan: fix it.", result.summary());
    }

    @Test
    void anExitWithoutAFinishedTurnFailsWithStderrAndSurvivesGarbage() {
        CodexParser parser = new CodexParser(false, null, WORKTREE);
        parser.accept("not json at all");
        parser.accept("{\"type\":\"thread.started\",\"thread_id\":\"t-2\"}");

        AgentResult result = parser.result(2, "error: unexpected argument '--frobnicate'");

        assertEquals(AgentOutcome.FAILED, result.outcome());
        assertEquals("codex exited with code 2 before finishing its turn: error: unexpected argument '--frobnicate'",
                result.error());
    }

    private static CodexParser feed(boolean structured, String fixture) throws IOException {
        CodexParser parser = new CodexParser(structured, "gpt-5-codex", WORKTREE);
        try (InputStream in = CodexParserTest.class.getResourceAsStream("/fixtures/codex/" + fixture)) {
            new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().forEach(parser::accept);
        }
        return parser;
    }
}
