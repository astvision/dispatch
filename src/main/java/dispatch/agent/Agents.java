package dispatch.agent;

import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.agent.claude.OwnerPlugins;
import dispatch.agent.codex.CodexAgent;
import dispatch.agent.gemini.GeminiAgent;
import dispatch.agent.sandbox.Confinement;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** The agents a machine can run, by the type the config names them with (ADR 0026). */
public final class Agents {

    /** How long a cancelled agent gets between SIGTERM and SIGKILL. */
    private static final Duration CANCEL_GRACE = Duration.ofSeconds(10);

    private Agents() {
    }

    /**
     * @param commands    agent type (claude-code, codex, gemini) to the command that runs it
     * @param environment the base environment for agent processes, normally {@code System.getenv()}
     * @param stateDir    this machine's state; Codex remembers the threads it started under it
     */
    public static Map<String, Agent> create(Map<String, String> commands, Map<String, String> environment, Path stateDir) {
        return create(commands, environment, stateDir, Confinement.none("no sandbox configured"));
    }

    /** @param confinement this machine's sandbox, shared by every agent it runs */
    public static Map<String, Agent> create(Map<String, String> commands, Map<String, String> environment, Path stateDir,
                                            Confinement confinement) {
        return create(commands, environment, stateDir, confinement, OwnerPlugins.NONE);
    }

    /**
     * @param confinement  this machine's sandbox, shared by every agent it runs
     * @param ownerPlugins what this machine's owner lists for Claude Code runs (spec: owner plugins)
     */
    public static Map<String, Agent> create(Map<String, String> commands, Map<String, String> environment, Path stateDir,
                                            Confinement confinement, OwnerPlugins ownerPlugins) {
        Map<String, Agent> agents = new LinkedHashMap<>();
        commands.forEach((type, command) -> agents.put(type, switch (type) {
            case "claude-code" -> new ClaudeCodeAgent(command, environment, CANCEL_GRACE, confinement, ownerPlugins);
            case "codex" -> new CodexAgent(command, environment, CANCEL_GRACE, stateDir.resolve("agent-sessions").resolve("codex"), confinement);
            case "gemini" -> new GeminiAgent(command, environment, CANCEL_GRACE, confinement);
            default -> throw new IllegalArgumentException("unsupported agent type: " + type);
        }));
        return Map.copyOf(agents);
    }
}
