package dispatch.agent.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dispatch.agent.AgentActivity;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.OutputParser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads Claude Code's print-mode stream-json output. The init event (session id, permission mode) and the final result
 * event decide a run's outcome; tool calls in assistant events feed the live activity. Must never throw: it runs on the
 * thread that drains the agent's stdout.
 */
final class StreamParser implements OutputParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_DENIAL_LENGTH = 200;
    private static final int MAX_ACTION_LENGTH = 120;

    private final String expectedPermissionMode;
    private final String requestedModel;
    private final Path workdir;
    private final Set<String> models = new LinkedHashSet<>();
    private boolean initSeen;
    private String permissionMode;
    private String sessionId;
    private JsonNode result;
    /** Written by the stdout reader, read by /status; replaced whole, so readers never see a torn value. */
    private volatile AgentActivity activity = new AgentActivity(0, null);

    /**
     * @param requestedModel the run's --model, compared with the model that answers; null for Claude Code's default
     * @param workdir        file paths inside it are shown relative in the activity
     */
    StreamParser(String expectedPermissionMode, String requestedModel, Path workdir) {
        this.expectedPermissionMode = expectedPermissionMode;
        this.requestedModel = requestedModel;
        this.workdir = workdir;
    }

    @Override
    public void accept(String line) {
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
        } else if (type.equals("assistant")) {
            // A subagent's messages name the tool call that started it; only the run's own model is reported.
            if (!event.hasNonNull("parent_tool_use_id") && event.path("message").hasNonNull("model")) {
                models.add(event.path("message").get("model").asText());
            }
            for (JsonNode block : event.path("message").path("content")) {
                if (block.path("type").asText().equals("tool_use")) {
                    activity = new AgentActivity(activity.steps() + 1, describe(block));
                }
            }
        } else if (type.equals("result")) {
            result = event;
        }
    }

    @Override
    public AgentActivity activity() {
        return activity;
    }

    /**
     * True once the init event shows the agent running in another permission mode than requested, or in none. Claude
     * Code does not refuse a mode the model lacks: without auto mode it starts in default mode, where every edit is
     * denied and the run still ends as a success.
     */
    boolean wrongPermissionMode() {
        return initSeen && !expectedPermissionMode.equals(permissionMode);
    }

    @Override
    public String stopReason() {
        return wrongPermissionMode() ? "wrong_permission_mode" : null;
    }

    @Override
    public AgentResult result(int exitCode, String stderrTail) {
        String model = models.isEmpty() ? null : String.join(", ", models);
        // Claude Code does not always use the model it was given: runs started with Haiku were answered by Sonnet 5.
        String unexpectedModel = requestedModel != null && models.stream().anyMatch(answered -> !isRequested(requestedModel, answered))
                ? requestedModel
                : null;
        String wrongMode = wrongPermissionMode()
                ? "Claude Code started in permission mode '" + permissionMode + "' instead of '" + expectedPermissionMode
                        + "' and was stopped; not every model supports every mode (Haiku has no auto mode)"
                : null;
        if (result == null) {
            String error = wrongMode != null ? wrongMode : "agent exited with code " + exitCode + " without a result" + detail(stderrTail);
            return new AgentResult(AgentOutcome.FAILED, exitCode, sessionId, null, null, null, null, List.of(), error, model, unexpectedModel);
        }
        String session = result.hasNonNull("session_id") ? result.get("session_id").asText() : sessionId;
        BigDecimal cost = result.hasNonNull("total_cost_usd")
                ? result.get("total_cost_usd").decimalValue().setScale(6, RoundingMode.HALF_UP)
                : null;
        Integer turns = result.hasNonNull("num_turns") ? result.get("num_turns").asInt() : null;
        List<String> denials = denials(result.path("permission_denials"));
        if (wrongMode != null) {
            // Whatever it produced was produced under the wrong rules; keep only the accounting.
            return new AgentResult(AgentOutcome.FAILED, exitCode, session, null, null, cost, turns, denials, wrongMode, model,
                    unexpectedModel);
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
        return new AgentResult(outcome, exitCode, session, structured, summary, cost, turns, denials, error, model, unexpectedModel);
    }

    /**
     * Whether an answer came from the model asked for. An alias such as "sonnet" names a family, "sonnet[1m]" the same family
     * with a longer context, and an id such as "claude-sonnet-5" one model. "opusplan" and "default" resolve per mode or per
     * account, so there is nothing to compare them with.
     */
    static boolean isRequested(String requested, String answered) {
        String wanted = requested.replaceFirst("\\[[^]]*]$", "");
        if (wanted.equals("opusplan") || wanted.equals("default")) {
            return true;
        }
        if (wanted.startsWith("claude-")) {
            return answered.matches(Pattern.quote(wanted) + "(-\\d{8})?");
        }
        return answered.contains("claude-" + wanted + "-");
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
            summaries.add(truncate(denial.path("tool_name").asText("?") + ": " + shown, MAX_DENIAL_LENGTH));
        }
        return List.copyOf(summaries);
    }

    /** "Tool: detail" on one line: the command for Bash, a worktree-relative path for file tools, else the raw input. */
    private String describe(JsonNode toolUse) {
        JsonNode input = toolUse.path("input");
        String detail;
        if (input.hasNonNull("command")) {
            detail = input.get("command").asText();
        } else if (input.hasNonNull("file_path")) {
            detail = relative(input.get("file_path").asText());
        } else if (input.hasNonNull("pattern")) {
            detail = input.get("pattern").asText();
        } else {
            detail = input.toString();
        }
        return truncate(toolUse.path("name").asText("?") + ": " + detail.replaceAll("\\s+", " ").strip(), MAX_ACTION_LENGTH);
    }

    /** Shown with '/' on every OS, so /status reads the same from a Windows machine. */
    private String relative(String file) {
        try {
            Path path = Path.of(file);
            if (!path.startsWith(workdir)) {
                return file;
            }
            List<String> names = new ArrayList<>();
            workdir.relativize(path).forEach(name -> names.add(name.toString()));
            return String.join("/", names);
        } catch (InvalidPathException e) {
            return file;
        }
    }

    private static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }

    private static String detail(String stderrTail) {
        return stderrTail == null || stderrTail.isBlank() ? "" : ": " + stderrTail.strip();
    }
}
