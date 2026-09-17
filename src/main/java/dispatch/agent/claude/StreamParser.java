package dispatch.agent.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads Claude Code's print-mode stream-json output. Only the init event (session id, permission mode) and the final
 * result event decide a run's outcome; every other event type is ignored here.
 */
final class StreamParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_DENIAL_LENGTH = 200;

    private final String expectedPermissionMode;
    private boolean initSeen;
    private String permissionMode;
    private String sessionId;
    private JsonNode result;

    StreamParser(String expectedPermissionMode) {
        this.expectedPermissionMode = expectedPermissionMode;
    }

    void accept(String line) {
        if (line.isBlank()) {
            return;
        }
        JsonNode event;
        try {
            event = JSON.readTree(line);
        } catch (JsonProcessingException e) {
            // The raw line stays in the run log; one garbled line must not cost the run its result.
            return;
        }
        String type = event.path("type").asText();
        if (type.equals("system") && event.path("subtype").asText().equals("init")) {
            initSeen = true;
            sessionId = event.path("session_id").asText(null);
            permissionMode = event.path("permissionMode").asText(null);
        } else if (type.equals("result")) {
            result = event;
        }
    }

    /**
     * True once the init event shows the agent running in another permission mode than requested, or in none. Claude
     * Code does not refuse a mode the model lacks: without auto mode it starts in default mode, where every edit is
     * denied and the run still ends as a success.
     */
    boolean wrongPermissionMode() {
        return initSeen && !expectedPermissionMode.equals(permissionMode);
    }

    AgentResult result(int exitCode, String stderrTail) {
        String wrongMode = wrongPermissionMode()
                ? "Claude Code started in permission mode '" + permissionMode + "' instead of '" + expectedPermissionMode
                        + "' and was stopped; not every model supports every mode (Haiku has no auto mode)"
                : null;
        if (result == null) {
            String error = wrongMode != null ? wrongMode : "agent exited with code " + exitCode + " without a result" + detail(stderrTail);
            return new AgentResult(AgentOutcome.FAILED, exitCode, sessionId, null, null, null, null, List.of(), error);
        }
        String session = result.hasNonNull("session_id") ? result.get("session_id").asText() : sessionId;
        BigDecimal cost = result.hasNonNull("total_cost_usd")
                ? result.get("total_cost_usd").decimalValue().setScale(6, RoundingMode.HALF_UP)
                : null;
        Integer turns = result.hasNonNull("num_turns") ? result.get("num_turns").asInt() : null;
        List<String> denials = denials(result.path("permission_denials"));
        if (wrongMode != null) {
            // Whatever it produced was produced under the wrong rules; keep only the accounting.
            return new AgentResult(AgentOutcome.FAILED, exitCode, session, null, null, cost, turns, denials, wrongMode);
        }
        String structured = result.hasNonNull("structured_output") ? result.get("structured_output").toString() : null;
        String summary = result.hasNonNull("result") ? result.get("result").asText() : null;

        String subtype = result.path("subtype").asText();
        AgentOutcome outcome;
        String error;
        if (subtype.equals("error_max_budget_usd")) {
            outcome = AgentOutcome.BUDGET_EXCEEDED;
            error = errors(result);
        } else if (subtype.equals("success") && !result.path("is_error").asBoolean()) {
            outcome = exitCode == 0 ? AgentOutcome.SUCCEEDED : AgentOutcome.FAILED;
            error = exitCode == 0 ? null : "agent reported success but exited with code " + exitCode + detail(stderrTail);
        } else {
            outcome = AgentOutcome.FAILED;
            error = subtype + ": " + errors(result);
        }
        return new AgentResult(outcome, exitCode, session, structured, summary, cost, turns, denials, error);
    }

    private static String errors(JsonNode result) {
        List<String> messages = new ArrayList<>();
        result.path("errors").forEach(message -> messages.add(message.asText()));
        if (!messages.isEmpty()) {
            return String.join("; ", messages);
        }
        String text = result.path("result").asText("");
        return text.isBlank() ? "no error detail reported" : text;
    }

    private static List<String> denials(JsonNode denials) {
        List<String> summaries = new ArrayList<>();
        for (JsonNode denial : denials) {
            JsonNode input = denial.path("tool_input");
            String shown = input.hasNonNull("command") ? input.get("command").asText() : input.toString();
            summaries.add(truncate(denial.path("tool_name").asText("?") + ": " + shown));
        }
        return List.copyOf(summaries);
    }

    private static String truncate(String text) {
        return text.length() <= MAX_DENIAL_LENGTH ? text : text.substring(0, MAX_DENIAL_LENGTH - 1) + "…";
    }

    private static String detail(String stderrTail) {
        return stderrTail == null || stderrTail.isBlank() ? "" : ": " + stderrTail.strip();
    }
}
