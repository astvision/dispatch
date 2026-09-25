package dispatch.agent.codex;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads {@code codex exec --json}: thread.started gives the thread to resume, tool items feed the live activity, the last
 * agent message is the answer, and turn.completed or turn.failed decide the outcome. Codex reports tokens, not money, so
 * a run's cost stays unknown.
 */
final class CodexParser implements OutputParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ACTION_LENGTH = 120;
    private static final int MAX_DENIAL_LENGTH = 200;
    private static final Set<String> TOOL_ITEMS = Set.of("command_execution", "file_change", "mcp_tool_call", "web_search");
    /** Codex runs each command through a login shell: "/bin/bash -lc 'cat calc.py'". */
    private static final Pattern SHELL_WRAPPER = Pattern.compile("^(?:\\S*/)?(?:ba|z)?sh -lc ");

    private final boolean structured;
    private final String requestedModel;
    private final Path workdir;
    private final Set<String> toolItems = new HashSet<>();
    private final List<String> denials = new ArrayList<>();
    private String threadId;
    private String lastMessage;
    private boolean turnCompleted;
    private String turnFailure;
    private String lastError;
    /** Written by the stdout reader, read by /status; replaced whole, so readers never see a torn value. */
    private volatile AgentActivity activity = new AgentActivity(0, null);

    /**
     * @param structured     the run asked for --output-schema: its last message is the JSON answer
     * @param requestedModel the run's -m, reported as the model that answered (Codex does not name it); null for its default
     * @param workdir        file paths inside it are shown relative in the activity
     */
    CodexParser(boolean structured, String requestedModel, Path workdir) {
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
            case "thread.started" -> threadId = event.path("thread_id").asText(null);
            case "item.started", "item.updated", "item.completed" -> item(event.path("item"), event.path("type").asText());
            case "turn.completed" -> turnCompleted = true;
            case "turn.failed" -> turnFailure = event.path("error").path("message").asText("turn failed");
            case "error" -> lastError = event.path("message").asText(null);
            default -> {
            }
        }
    }

    private void item(JsonNode item, String eventType) {
        String type = item.path("type").asText();
        // An item is started, maybe updated, then completed: it is one step, counted when first seen.
        if (TOOL_ITEMS.contains(type) && toolItems.add(item.path("id").asText())) {
            activity = new AgentActivity(activity.steps() + 1, describe(item));
        }
        if (!eventType.equals("item.completed")) {
            return;
        }
        switch (type) {
            case "agent_message" -> lastMessage = item.path("text").asText(null);
            case "error" -> lastError = item.path("message").asText(null);
            case "command_execution" -> {
                if (item.path("status").asText().equals("declined")) {
                    denials.add(truncate("Bash: " + command(item), MAX_DENIAL_LENGTH));
                }
            }
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
        if (turnFailure == null && turnCompleted && exitCode == 0) {
            return new AgentResult(AgentOutcome.SUCCEEDED, exitCode, threadId, structured ? jsonObject(lastMessage) : null,
                    lastMessage, null, null, List.copyOf(denials), null, requestedModel, null);
        }
        String error;
        if (turnFailure != null) {
            error = turnFailure;
        } else if (lastError != null) {
            error = lastError;
        } else if (turnCompleted) {
            error = "codex finished its turn but exited with code " + exitCode + detail(stderrTail);
        } else {
            error = "codex exited with code " + exitCode + " before finishing its turn" + detail(stderrTail);
        }
        return new AgentResult(AgentOutcome.FAILED, exitCode, threadId, null, lastMessage, null, null, List.copyOf(denials),
                error, requestedModel, null);
    }

    /** The answer as compact JSON when it is one object, as --output-schema asks; null makes the plan step fail as "no plan". */
    private static String jsonObject(String text) {
        if (text == null) {
            return null;
        }
        try {
            JsonNode answer = JSON.readTree(text);
            return answer != null && answer.isObject() ? answer.toString() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** "Tool: detail" on one line, named as Claude's are, so /status reads the same whatever the agent. */
    private String describe(JsonNode item) {
        String detail = switch (item.path("type").asText()) {
            case "command_execution" -> "Bash: " + command(item);
            case "file_change" -> fileChange(item.path("changes"));
            case "mcp_tool_call" -> "MCP: " + item.path("server").asText("?") + "." + item.path("tool").asText("?");
            case "web_search" -> "Search: " + item.path("query").asText("");
            default -> item.path("type").asText("?");
        };
        return truncate(detail.replaceAll("\\s+", " ").strip(), MAX_ACTION_LENGTH);
    }

    private String fileChange(JsonNode changes) {
        if (changes.isEmpty()) {
            return "Edit";
        }
        JsonNode first = changes.get(0);
        String verb = switch (first.path("kind").asText()) {
            case "add" -> "Write";
            case "delete" -> "Delete";
            default -> "Edit";
        };
        String more = changes.size() > 1 ? " (+" + (changes.size() - 1) + ")" : "";
        return verb + ": " + relative(first.path("path").asText()) + more;
    }

    /** The command as the agent wrote it, without the login shell Codex wraps it in. */
    private static String command(JsonNode item) {
        String command = SHELL_WRAPPER.matcher(item.path("command").asText("")).replaceFirst("");
        boolean quoted = command.length() >= 2 && (command.startsWith("'") && command.endsWith("'")
                || command.startsWith("\"") && command.endsWith("\""));
        return quoted ? command.substring(1, command.length() - 1) : command;
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
