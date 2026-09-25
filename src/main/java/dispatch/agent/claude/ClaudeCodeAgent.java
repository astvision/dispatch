package dispatch.agent.claude;

import dispatch.agent.Agent;
import dispatch.agent.ProcessRun;
import dispatch.agent.RunHandle;
import dispatch.agent.RunRequest;
import dispatch.agent.Schemas;
import dispatch.domain.RunKind;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs Claude Code headless: {@code claude -p} with stream-json output, the prompt on stdin, and no permission prompts
 * (anything that would ask is denied). Only project/local Claude settings and no MCP servers are loaded, so a run
 * behaves the same on a laptop and on a team server.
 */
public final class ClaudeCodeAgent implements Agent {

    private static final String PLAN_SCHEMA = Schemas.PLAN;
    private static final String SPLIT_SCHEMA = Schemas.SPLIT;
    private static final String ASSISTANT_SCHEMA = Schemas.ASSISTANT;
    /** The one command the assistant may run: its view of the member's tasks (A-1). */
    private static final String ASSISTANT_BASH = "Bash(dispatch ask *)";
    /** Replaces Claude Code's coding prompt, which a split does not need: it would multiply the split's cost (ADR 0013). */
    private static final String SPLIT_SYSTEM_PROMPT =
            "You split a developer's chat message into independent development tasks. Answer only through the structured output.";

    private final String command;
    private final Map<String, String> environment;
    private final Duration cancelGrace;

    /** @param environment the base environment for agent processes, normally {@code System.getenv()} */
    public ClaudeCodeAgent(String command, Map<String, String> environment, Duration cancelGrace) {
        this.command = command;
        this.environment = Map.copyOf(environment);
        this.cancelGrace = cancelGrace;
    }

    @Override
    public RunHandle start(RunRequest request) {
        String permissionMode = permissionMode(request.kind());
        return ProcessRun.start("claude-code", commandLine(request, permissionMode), request, environment, request.prompt(),
                new StreamParser(permissionMode, request.model(), request.workdir()), cancelGrace);
    }

    private static String permissionMode(RunKind kind) {
        return switch (kind) {
            case PLAN, SPLIT -> "plan";
            case EXECUTE -> "auto";
            // Anything not allowed up front is refused without asking: here, every Bash command but dispatch ask.
            case ASSISTANT -> "dontAsk";
            case DELIVER -> throw new IllegalArgumentException("DELIVER runs do not start an agent");
        };
    }

    private List<String> commandLine(RunRequest request, String permissionMode) {
        List<String> args = new ArrayList<>(List.of(command, "-p",
                "--output-format", "stream-json", "--verbose",
                "--permission-mode", permissionMode,
                "--permission-prompts", "none",
                // The assistant's home is Dispatch's own directory; nothing local to a person's checkout applies there.
                "--setting-sources", request.kind() == RunKind.ASSISTANT ? "project" : "project,local",
                "--strict-mcp-config"));
        if (request.budgetUsd() != null) {
            args.addAll(List.of("--max-budget-usd", request.budgetUsd().toPlainString()));
        }
        if (request.kind() == RunKind.SPLIT) {
            // Nothing will continue a split: no transcript on disk, no skills listed in its prompt.
            args.addAll(List.of("--no-session-persistence", "--disable-slash-commands"));
        } else if (request.resume()) {
            args.addAll(List.of("--resume", request.sessionId().toString()));
        } else {
            args.addAll(List.of("--session-id", request.sessionId().toString()));
        }
        if (request.model() != null) {
            args.addAll(List.of("--model", request.model()));
        }
        if (request.effort() != null) {
            args.addAll(List.of("--effort", request.effort()));
        }
        for (Path dir : request.readOnlyDirs()) {
            args.addAll(List.of("--add-dir", dir.toString()));
        }
        switch (request.kind()) {
            // Read-only investigation: no subagents or schedulers, just reading files and read-only shell commands.
            case PLAN -> args.addAll(List.of("--tools", "Read,Bash", "--json-schema", PLAN_SCHEMA));
            // Delivery is Dispatch's job (ADR 0007); the deny rules are a guardrail, not a boundary (ADR 0009).
            // --disallowedTools takes every following argument that is not a flag, so it stays last.
            case EXECUTE -> args.addAll(List.of("--tools", "Read,Edit,Write,Bash",
                    "--disallowedTools", "Bash(git commit *)", "Bash(git push *)", "Bash(gh *)"));
            case SPLIT -> args.addAll(List.of("--tools", "", "--json-schema", SPLIT_SCHEMA, "--system-prompt", SPLIT_SYSTEM_PROMPT));
            // Reads code, asks for tasks and loads its taskmanager skill (A-1), nothing else; --allowedTools takes every following argument too, so it is last.
            case ASSISTANT -> args.addAll(List.of("--tools", "Read,Grep,Glob,Bash,Skill", "--json-schema", ASSISTANT_SCHEMA,
                    "--allowedTools", ASSISTANT_BASH));
            case DELIVER -> throw new IllegalArgumentException("DELIVER runs do not start an agent");
        }
        return args;
    }
}
