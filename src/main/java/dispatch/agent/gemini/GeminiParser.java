package dispatch.agent.gemini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dispatch.agent.AgentActivity;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.OutputParser;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code gemini --output-format stream-json}: init names the session and model, tool_use events feed the live
 * activity, the assistant's text after its last tool call is the answer, and the result event decides the outcome.
 * Gemini CLI reports tokens, not money, so a run's cost stays unknown.
 */
final class GeminiParser implements OutputParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ACTION_LENGTH = 120;
    /** Gemini CLI's tool names, as the activity names Claude's, so /status reads the same whatever the agent. */
    private static final Map<String, String> TOOL_NAMES = Map.of(
            "run_shell_command", "Bash", "read_file", "Read", "read_many_files", "Read", "write_file", "Write",
            "replace", "Edit", "glob", "Glob", "search_file_content", "Grep", "grep_search", "Grep", "list_directory", "LS");

    private final boolean structured;
    private final String requestedModel;
    private final Path workdir;
    private final StringBuilder answer = new StringBuilder();
    private String sessionId;
    private String model;
    private JsonNode result;
    private String lastError;
    /** Written by the stdout reader, read by /status; replaced whole, so readers never see a torn value. */
    private volatile AgentActivity activity = new AgentActivity(0, null);

    /**
     * @param structured     the run asked for a JSON answer: it is taken from the final text
     * @param requestedModel the run's -m, compared with the model that answers; null for Gemini CLI's default
     * @param workdir        file paths inside it are shown relative in the activity
     */
    GeminiParser(boolean structured, String requestedModel, Path workdir) {
        this.structured = structured;
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
        switch (event.path("type").asText()) {
            case "init" -> {
                sessionId = event.path("session_id").asText(null);
                model = event.path("model").asText(null);
            }
            case "message" -> {
                if (event.path("role").asText().equals("assistant")) {
                    answer.append(event.path("content").asText(""));
                }
            }
            case "tool_use" -> {
                // Whatever it said before a tool call was thinking aloud; the answer is what follows the last one.
                answer.setLength(0);
                activity = new AgentActivity(activity.steps() + 1, describe(event));
            }
            case "error" -> {
                if (event.path("severity").asText().equals("error")) {
                    lastError = event.path("message").asText(null);
                }
            }
            case "result" -> result = event;
            default -> {
            }
        }
    }

    @Override
    public AgentActivity activity() {
        return activity;
    }

    @Override
    public AgentResult result(int exitCode, String stderrTail) {
        String text = answer.isEmpty() ? null : answer.toString().strip();
        String unexpectedModel = requestedModel != null && model != null && !model.equals(requestedModel) ? requestedModel : null;
        if (result != null && result.path("status").asText().equals("success") && exitCode == 0) {
            return new AgentResult(AgentOutcome.SUCCEEDED, exitCode, sessionId, structured ? jsonObject(text) : null, text,
                    null, null, List.of(), null, model, unexpectedModel);
        }
        String error;
        if (result != null && result.path("error").hasNonNull("message")) {
            error = result.path("error").path("message").asText();
        } else if (lastError != null) {
            error = lastError;
        } else if (exitCode == 53) {
            // Gemini CLI's documented exit codes: 53 is model.maxSessionTurns reached.
            error = "gemini stopped: its session reached the turn limit (exit 53)";
        } else {
            error = "gemini exited with code " + exitCode + (result == null ? " without a result" : "") + detail(stderrTail);
        }
        return new AgentResult(AgentOutcome.FAILED, exitCode, sessionId, null, text, null, null, List.of(), error, model,
                unexpectedModel);
    }

    /**
     * The one JSON object in the answer, which the model may wrap in a ```json fence or a sentence; null makes the plan step
     * fail as "no plan", as for any agent.
     */
    static String jsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            return null;
        }
        try {
            JsonNode answer = JSON.readTree(text.substring(start, end + 1));
            return answer != null && answer.isObject() ? answer.toString() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** "Tool: detail" on one line: the command for the shell, a worktree-relative path for file tools, else the raw input. */
    private String describe(JsonNode toolUse) {
        String tool = toolUse.path("tool_name").asText("?");
        JsonNode parameters = toolUse.path("parameters");
        String detail;
        if (parameters.hasNonNull("command")) {
            detail = parameters.get("command").asText();
        } else if (path(parameters) != null) {
            detail = relative(path(parameters));
        } else if (parameters.hasNonNull("pattern")) {
            detail = parameters.get("pattern").asText();
        } else {
            detail = parameters.toString();
        }
        String shown = TOOL_NAMES.getOrDefault(tool, tool) + ": " + detail.replaceAll("\\s+", " ").strip();
        return shown.length() <= MAX_ACTION_LENGTH ? shown : shown.substring(0, MAX_ACTION_LENGTH - 1) + "…";
    }

    /** Gemini CLI's file tools have named their path differently across versions. */
    private static String path(JsonNode parameters) {
        for (String name : List.of("file_path", "absolute_path", "dir_path", "path")) {
            if (parameters.hasNonNull(name)) {
                return parameters.get(name).asText();
            }
        }
        return null;
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

    private static String detail(String stderrTail) {
        return stderrTail == null || stderrTail.isBlank() ? "" : ": " + stderrTail.strip();
    }
}
