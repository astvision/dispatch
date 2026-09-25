package dispatch.agent.codex;

import dispatch.Log;
import dispatch.OwnerOnly;
import dispatch.agent.Agent;
import dispatch.agent.AgentActivity;
import dispatch.agent.AgentResult;
import dispatch.agent.AgentStartException;
import dispatch.agent.ProcessRun;
import dispatch.agent.RunHandle;
import dispatch.agent.RunRequest;
import dispatch.agent.Schemas;
import dispatch.domain.RunKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Runs OpenAI's Codex CLI headless: {@code codex exec --json} with the prompt on stdin (ADR 0026). A plan runs in Codex's
 * read-only sandbox and answers through --output-schema; an execution runs without a sandbox, as the user, with the same
 * access Claude Code's auto mode has (ADR 0009). Codex names its own threads, so the thread each Dispatch session started
 * is remembered in a small file and resumed by that name. The user's config.toml is not loaded: a run behaves the same on
 * every computer, as Claude Code's does with project settings only.
 */
public final class CodexAgent implements Agent {

    private static final String PLAN_SCHEMA = Schemas.withoutLimits(Schemas.PLAN);
    /** What a thread id may look like before it is passed back to Codex as an argument. */
    private static final Pattern THREAD_ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private final String command;
    private final Map<String, String> environment;
    private final Duration cancelGrace;
    private final Path sessionsDir;

    /**
     * @param environment the base environment for agent processes, normally {@code System.getenv()}
     * @param sessionsDir where the thread each Dispatch session started is remembered, one file per session
     */
    public CodexAgent(String command, Map<String, String> environment, Duration cancelGrace, Path sessionsDir) {
        this.command = command;
        this.environment = Map.copyOf(environment);
        this.cancelGrace = cancelGrace;
        this.sessionsDir = sessionsDir;
    }

    @Override
    public RunHandle start(RunRequest request) {
        boolean plan = switch (request.kind()) {
            case PLAN -> true;
            case EXECUTE -> false;
            // Splitting and the assistant need Claude Code's structured output and tools (ADR 0013, A-1).
            case SPLIT, ASSISTANT -> throw new IllegalArgumentException(request.kind() + " runs on claude-code, not codex");
            case DELIVER -> throw new IllegalArgumentException("DELIVER runs do not start an agent");
        };
        List<String> args = new ArrayList<>(List.of(command, "exec"));
        String thread = null;
        if (request.resume()) {
            thread = recordedThread(request.sessionId());
            args.add("resume");
        }
        args.addAll(List.of("--json", "--ignore-user-config",
                "-c", "approval_policy=\"never\"",
                // exec resume has no --sandbox; a -c override works for both.
                "-c", "sandbox_mode=\"" + (plan ? "read-only" : "danger-full-access") + "\""));
        if (request.model() != null) {
            args.addAll(List.of("-m", request.model()));
        }
        if (request.effort() != null) {
            args.addAll(List.of("-c", "model_reasoning_effort=\"" + request.effort() + "\""));
        }
        if (plan) {
            args.addAll(List.of("--output-schema", writeSchema(request.logBase()).toString()));
        }
        if (thread != null) {
            args.add(thread);
        }
        args.add("-");
        ProcessRun run = ProcessRun.start("codex", args, request, environment, request.prompt(),
                new CodexParser(plan, request.model(), request.workdir()), cancelGrace);
        return request.resume() ? run : new RememberingRun(run, request.sessionId());
    }

    private String recordedThread(UUID session) {
        try {
            String thread = Files.readString(sessionFile(session)).strip();
            if (THREAD_ID.matcher(thread).matches()) {
                return thread;
            }
        } catch (IOException e) {
            // Falls through to the explanation below.
        }
        throw new AgentStartException("no Codex thread is recorded here for session " + session
                + "; it started on another computer or its record was removed", null);
    }

    private void remember(UUID session, String thread) {
        if (thread == null || !THREAD_ID.matcher(thread).matches()) {
            Log.warn("agent.codex_thread_unknown", "session", session);
            return;
        }
        try {
            OwnerOnly.createDirectories(sessionsDir);
            Files.writeString(sessionFile(session), thread);
        } catch (IOException e) {
            // The run itself is fine; only a later correction or follow-up of it will fail to resume.
            Log.error("agent.codex_thread_not_recorded", e, "session", session, "thread", thread);
        }
    }

    private Path sessionFile(UUID session) {
        return sessionsDir.resolve(session.toString());
    }

    /** Beside the run's logs, so it is there to read when a run's plan is looked into later. */
    private static Path writeSchema(Path logBase) {
        Path schema = Path.of(logBase + ".schema.json");
        try {
            Files.createDirectories(schema.getParent());
            Files.writeString(schema, PLAN_SCHEMA);
        } catch (IOException e) {
            throw new AgentStartException("cannot write the plan schema to " + schema + ": " + e.getMessage(), e);
        }
        return schema;
    }

    /** A first run of a session: once it ends, the thread Codex started for it is remembered for the runs that resume it. */
    private final class RememberingRun implements RunHandle {

        private final RunHandle run;
        private final UUID session;

        RememberingRun(RunHandle run, UUID session) {
            this.run = run;
            this.session = session;
        }

        @Override
        public ProcessHandle process() {
            return run.process();
        }

        @Override
        public Instant processStart() {
            return run.processStart();
        }

        @Override
        public AgentResult await() throws InterruptedException {
            AgentResult result = run.await();
            remember(session, result.sessionId());
            return result;
        }

        @Override
        public void cancel() {
            run.cancel();
        }

        @Override
        public AgentActivity activity() {
            return run.activity();
        }
    }
}
