package dispatch.agent.gemini;

import dispatch.agent.Agent;
import dispatch.agent.ProcessRun;
import dispatch.agent.RunHandle;
import dispatch.agent.RunRequest;
import dispatch.agent.Schemas;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs Google's Gemini CLI headless: {@code gemini -p} with stream-json output and the prompt on stdin (ADR 0026). A plan
 * runs in the default approval mode, where headless Gemini CLI denies every edit and shell command, and answers in JSON the
 * prompt asks for, since the CLI has no schema flag. An execution approves everything (yolo), with the same access Claude
 * Code's auto mode has (ADR 0009). Dispatch names each session itself, as for Claude Code. --skip-trust keeps a folder
 * Gemini does not trust from quietly turning yolo back into the default mode, where no edit would happen.
 */
public final class GeminiAgent implements Agent {

    /** Appended to the prompt on stdin (Gemini CLI puts -p after it): the plan's shape, as other agents get it by flag. */
    private static final String PLAN_ANSWER = "Answer with the plan only: one JSON object, with no other text, that matches "
            + "this JSON Schema: " + Schemas.PLAN;
    private static final String EXECUTE_ANSWER = "Do what the message above asks.";

    private final String command;
    private final Map<String, String> environment;
    private final Duration cancelGrace;

    /** @param environment the base environment for agent processes, normally {@code System.getenv()} */
    public GeminiAgent(String command, Map<String, String> environment, Duration cancelGrace) {
        this.command = command;
        this.environment = Map.copyOf(environment);
        this.cancelGrace = cancelGrace;
    }

    @Override
    public RunHandle start(RunRequest request) {
        boolean plan = switch (request.kind()) {
            case PLAN -> true;
            case EXECUTE -> false;
            // Splitting and the assistant need Claude Code's structured output and tools (ADR 0013, A-1).
            case SPLIT, ASSISTANT -> throw new IllegalArgumentException(request.kind() + " runs on claude-code, not gemini");
            case DELIVER -> throw new IllegalArgumentException("DELIVER runs do not start an agent");
        };
        List<String> args = new ArrayList<>(List.of(command, "--output-format", "stream-json", "--skip-trust",
                "--approval-mode", plan ? "default" : "yolo"));
        // Sessions are kept per project directory, so a resumed run must start in the same worktree, as it does.
        args.addAll(List.of(request.resume() ? "--resume" : "--session-id", request.sessionId().toString()));
        if (request.model() != null) {
            args.addAll(List.of("-m", request.model()));
        }
        if (!request.readOnlyDirs().isEmpty()) {
            // Gemini CLI's file tools reach only the workspace; attachments live outside the worktree.
            args.addAll(List.of("--include-directories",
                    String.join(",", request.readOnlyDirs().stream().map(Path::toString).toList())));
        }
        args.addAll(List.of("-p", plan ? PLAN_ANSWER : EXECUTE_ANSWER));
        return ProcessRun.start("gemini", args, request, environment, request.prompt(),
                new GeminiParser(plan, request.model(), request.workdir()), cancelGrace);
    }
}
