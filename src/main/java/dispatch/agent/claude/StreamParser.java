package dispatch.agent.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dispatch.Log;
import dispatch.agent.AgentActivity;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.DataAccess;
import dispatch.agent.UsageLimit;
import dispatch.agent.OutputParser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
    private final Path logBase;
    /** The level the run was given the project's data at; null when it has none. */
    private final DataAccess.Level dataLevel;
    private final Set<String> models = new LinkedHashSet<>();
    private boolean initSeen;
    private String permissionMode;
    private String sessionId;
    private JsonNode result;
    /** True while Claude's last word on its usage limit was a rejection that no extra usage covers. */
    private boolean limited;
    /** That rejection's reset and window, when it named them; null when it did not (Dispatch never guesses a reset). */
    private UsageLimit limit;
    /** The data server's tools beyond what its level allows, as the init event lists them (spec: data reports). */
    private final Set<String> unexpectedDataTools = new TreeSet<>();
    /** The data server's status when the init event shows it anything but connected, "missing" when it lists none. */
    private String dataServerDown;
    /** True once the run itself called one of the data server's tools. */
    private boolean dataTouched;
    /** Written by the stdout reader, read by /status; replaced whole, so readers never see a torn value. */
    private volatile AgentActivity activity = new AgentActivity(0, null);

    /**
     * @param requestedModel the run's --model, compared with the model that answers; null for Claude Code's default
     * @param workdir        file paths inside it are shown relative in the activity
     * @param logBase        the run's log base, which names its task, run and step in the log
     */
    StreamParser(String expectedPermissionMode, String requestedModel, Path workdir, Path logBase) {
        this(expectedPermissionMode, requestedModel, workdir, logBase, null, false);
    }

    /**
     * @param dataLevel the level the run was given the project's data at, whose tools are checked; null for none
     * @param resumed   whether the run continues a session, whose earlier runs may have read the data already
     */
    StreamParser(String expectedPermissionMode, String requestedModel, Path workdir, Path logBase, DataAccess.Level dataLevel,
                 boolean resumed) {
        this.dataLevel = dataLevel;
        // A correction resumes the plan's session: what an earlier run read is still in its context.
        this.dataTouched = dataLevel != null && resumed;
        this.expectedPermissionMode = expectedPermissionMode;
        this.requestedModel = requestedModel;
        this.workdir = workdir;
        this.logBase = logBase;
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
            JsonNode pluginErrors = event.path("plugin_errors");
            if (pluginErrors.isArray() && !pluginErrors.isEmpty()) {
                // The run goes on without the skills its prompt names (spec: agent skills).
                Log.warn("agent.plugin_errors", "run", logBase, "errors", pluginErrors.toString());
            }
            for (JsonNode server : event.path("mcp_servers")) {
                if (server.path("status").asText().equals("failed")) {
                    // The run goes on without it (spec: owner plugins).
                    Log.warn("agent.mcp_failed", "server", server.path("name").asText(), "run", logBase);
                }
            }
            if (dataLevel != null) {
                checkDataServer(event);
            }
        } else if (type.equals("assistant")) {
            // A subagent's messages name the tool call that started it; only the run's own model is reported.
            boolean topLevel = !event.hasNonNull("parent_tool_use_id");
            if (topLevel && event.path("message").hasNonNull("model")) {
                models.add(event.path("message").get("model").asText());
            }
            for (JsonNode block : event.path("message").path("content")) {
                if (block.path("type").asText().equals("tool_use")) {
                    activity = new AgentActivity(activity.steps() + 1, describe(block));
                    if (topLevel && block.path("name").asText().startsWith(DataAccess.TOOL_PREFIX)) {
                        dataTouched = true;
                    }
                    if (topLevel && block.path("name").asText().equals("Skill")) {
                        Log.info("agent.skill", "skill", block.path("input").path("skill").asText(), "run", logBase);
                    }
                }
            }
        } else if (type.equals("rate_limit_event")) {
            JsonNode info = event.path("rate_limit_info");
            limited = info.path("status").asText().equals("rejected") && !info.path("isUsingOverage").asBoolean();
            limit = limited && info.hasNonNull("resetsAt")
                    ? new UsageLimit(info.get("resetsAt").asLong(), info.path("rateLimitType").asText("unknown"))
                    : null;
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

    /**
     * Dispatch enforces a data level rather than asking for it in the prompt: a server that offers more than its level allows
     * (a newer server, a changed disabled list) stops the run before it can call it.
     */
    private void checkDataServer(JsonNode init) {
        for (JsonNode tool : init.path("tools")) {
            String name = tool.asText();
            if (!name.startsWith(DataAccess.TOOL_PREFIX)) {
                continue;
            }
            String bare = name.substring(DataAccess.TOOL_PREFIX.length());
            if (!dataLevel.allowedTools().contains(bare)) {
                unexpectedDataTools.add(bare);
            }
        }
        // Absent counts as down: a report run must not succeed on no data at all.
        dataServerDown = "missing";
        for (JsonNode server : init.path("mcp_servers")) {
            if (server.path("name").asText().equals(DataAccess.SERVER)) {
                String status = server.path("status").asText();
                dataServerDown = status.equals("connected") ? null : status;
            }
        }
    }

    @Override
    public String stopReason() {
        if (wrongPermissionMode()) {
            return "wrong_permission_mode";
        }
        if (!unexpectedDataTools.isEmpty()) {
            return "data_tools_unexpected";
        }
        // Without its data the run can only guess; whatever it spends from here is wasted.
        return dataServerDown != null ? "data_unreachable" : null;
    }

    /** Why the init event alone makes the run's result unusable on the data's account; null when it does not. */
    private String dataRefusal() {
        if (!unexpectedDataTools.isEmpty()) {
            return "the project's data server offered tools its " + dataLevel + " level does not allow, so the run was stopped: "
                    + String.join(", ", unexpectedDataTools);
        }
        if (dataServerDown != null) {
            return "could not reach the project's data: its server's status was '" + dataServerDown + "'";
        }
        return null;
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
        String refusal = wrongMode != null ? wrongMode : dataRefusal();
        if (result == null) {
            String error = refusal != null ? refusal : "agent exited with code " + exitCode + " without a result" + detail(stderrTail);
            return new AgentResult(unfinished(), exitCode, sessionId, null, null, null, null, List.of(), error, model, unexpectedModel)
                    .withLimit(limited ? limit : null);
        }
        String session = result.hasNonNull("session_id") ? result.get("session_id").asText() : sessionId;
        BigDecimal cost = result.hasNonNull("total_cost_usd")
                ? result.get("total_cost_usd").decimalValue().setScale(6, RoundingMode.HALF_UP)
                : null;
        Integer turns = result.hasNonNull("num_turns") ? result.get("num_turns").asInt() : null;
        List<String> denials = denials(result.path("permission_denials"));
        if (refusal == null && dataTouched && result.path("structured_output").path("result").asText().equals("answer")) {
            // An answer goes to the chat as it is, and what the run read may be in it: only an approved report may show data.
            refusal = "the plan read the project's data, so it may not answer directly";
        }
        if (refusal != null) {
            // Whatever it produced was produced under the wrong rules; keep only the accounting.
            return new AgentResult(AgentOutcome.FAILED, exitCode, session, null, null, cost, turns, denials, refusal, model,
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
            outcome = unfinished();
            // A result that is_error under subtype "success" (a usage limit, say) carries Claude's own sentence; the subtype adds nothing.
            error = subtype.equals("success") ? errors(result) : subtype + ": " + errors(result);
        }
        return new AgentResult(outcome, exitCode, session, structured, summary, cost, turns, denials, error, model, unexpectedModel)
                .withLimit(outcome == AgentOutcome.LIMITED ? limit : null);
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

    /** How a run that did not finish ended: cut short by the usage limit when Claude's last word on it was a rejection. */
    private AgentOutcome unfinished() {
        return limited ? AgentOutcome.LIMITED : AgentOutcome.FAILED;
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
