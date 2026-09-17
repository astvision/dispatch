package dispatch.agent.claude;

import dispatch.Json;
import dispatch.Log;
import dispatch.agent.Agent;
import dispatch.agent.AgentStartException;
import dispatch.agent.RunHandle;
import dispatch.agent.RunRequest;
import dispatch.domain.RunKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs Claude Code headless: {@code claude -p} with stream-json output, the prompt on stdin, and no permission prompts
 * (anything that would ask is denied). Only project/local Claude settings and no MCP servers are loaded, so a run
 * behaves the same on a laptop and on a team server.
 */
public final class ClaudeCodeAgent implements Agent {

    /** Dispatch's own secrets; the agent process never needs them (ADR 0009). */
    private static final Set<String> WITHHELD_VARIABLES = Set.of("TELEGRAM_BOT_TOKEN", "GH_TOKEN");
    /** Compacted to one line (and so validated as JSON) when the class loads, not on the first run. */
    private static final String PLAN_SCHEMA = Json.read(resource("/plan-schema.json")).toString();
    private static final String SPLIT_SCHEMA = Json.read(resource("/split-schema.json")).toString();
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
        ProcessBuilder builder = new ProcessBuilder(commandLine(request, permissionMode)).directory(request.workdir().toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        WITHHELD_VARIABLES.forEach(builder.environment()::remove);
        Path stdoutLog = Path.of(request.logBase() + ".jsonl");
        Path stderrLog = Path.of(request.logBase() + ".stderr");
        Process process;
        try {
            Files.createDirectories(stdoutLog.getParent());
            builder.redirectError(stderrLog.toFile());
            process = builder.start();
        } catch (IOException e) {
            throw new AgentStartException("cannot start " + command + ": " + e.getMessage(), e);
        }
        Log.info("agent.started", "agent", "claude-code", "pid", process.pid(), "kind", request.kind(),
                "workdir", request.workdir(), "resume", request.resume());
        writePrompt(process, request.prompt());
        return new ClaudeRun(process, permissionMode, request.workdir(), stdoutLog, stderrLog, cancelGrace);
    }

    private static String permissionMode(RunKind kind) {
        return switch (kind) {
            case PLAN, SPLIT -> "plan";
            case EXECUTE -> "auto";
            case DELIVER -> throw new IllegalArgumentException("DELIVER runs do not start an agent");
        };
    }

    private List<String> commandLine(RunRequest request, String permissionMode) {
        List<String> args = new ArrayList<>(List.of(command, "-p",
                "--output-format", "stream-json", "--verbose",
                "--permission-mode", permissionMode,
                "--permission-prompts", "none",
                "--setting-sources", "project,local",
                "--strict-mcp-config",
                "--max-budget-usd", request.budgetUsd().toPlainString()));
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
            case DELIVER -> throw new IllegalArgumentException("DELIVER runs do not start an agent");
        }
        return args;
    }

    /** On its own thread: a large prompt must not block while the process has not started reading stdin yet. */
    private static void writePrompt(Process process, String prompt) {
        Thread.ofVirtual().name("agent-stdin-" + process.pid()).start(() -> {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // Usually the agent exited before reading; its exit code and stderr explain why.
                Log.warn("agent.stdin_failed", "pid", process.pid(), "error", e.getMessage());
            }
        });
    }

    private static String resource(String name) {
        try (InputStream in = ClaudeCodeAgent.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("resource missing: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
