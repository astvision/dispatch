package dispatch.worker;

import java.nio.file.Path;
import java.util.Map;

/**
 * The member's own settings, in {@code worker.yaml} beside {@code dispatch.yaml}. It holds only what is true on this
 * computer: everything about the team's projects — repo, base branch, agent, the files to copy — comes from the team
 * machine with each job.
 *
 * @param team              the team's URL, as {@code dispatch worker pair} was given it
 * @param name              what the member's /worker list calls this computer
 * @param maxConcurrentRuns how many of this member's runs this computer works on at once — trades this machine's own
 *                          resource use (parallel agent processes) against throughput; it does not bound how many
 *                          requests this computer sends the team machine at once, which {@link WorkerClient} caps on
 *                          its own regardless of this number
 * @param claudeCommand     the member's own Claude Code, which runs with the member's own login
 * @param ghCommand         the member's own GitHub CLI
 * @param stateDir          where this computer keeps its worktrees, run logs and attachments
 * @param projects          project name to what this computer knows about it; a project that is missing here cannot run
 * @param codexCommand      the member's own Codex CLI, for projects that run on codex; null when this computer has none
 * @param geminiCommand     the member's own Gemini CLI, likewise (ADR 0026)
 */
public record WorkerConfig(String team, String name, int maxConcurrentRuns, String claudeCommand, String ghCommand,
                           Path stateDir, Map<String, Project> projects, String codexCommand, String geminiCommand) {

    public WorkerConfig {
        projects = Map.copyOf(projects);
    }

    /** A computer with Claude Code only, as every worker was before other agents (ADR 0026). */
    public WorkerConfig(String team, String name, int maxConcurrentRuns, String claudeCommand, String ghCommand,
                        Path stateDir, Map<String, Project> projects) {
        this(team, name, maxConcurrentRuns, claudeCommand, ghCommand, stateDir, projects, null, null);
    }

    /** The agents this computer can run, by type, as the team's projects name them. */
    public Map<String, String> agentCommands() {
        Map<String, String> commands = new java.util.LinkedHashMap<>();
        commands.put("claude-code", claudeCommand);
        if (codexCommand != null) {
            commands.put("codex", codexCommand);
        }
        if (geminiCommand != null) {
            commands.put("gemini", geminiCommand);
        }
        return commands;
    }

    /**
     * @param path   an existing clone on this computer
     * @param model  overrides the team's model for this project here; null keeps the team's
     * @param effort likewise
     */
    public record Project(String path, String model, String effort) {
    }
}
