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
 * @param maxConcurrentRuns how many of this member's runs this computer works on at once
 * @param claudeCommand     the member's own Claude Code, which runs with the member's own login
 * @param ghCommand         the member's own GitHub CLI
 * @param stateDir          where this computer keeps its worktrees, run logs and attachments
 * @param projects          project name to what this computer knows about it; a project that is missing here cannot run
 */
public record WorkerConfig(String team, String name, int maxConcurrentRuns, String claudeCommand, String ghCommand,
                           Path stateDir, Map<String, Project> projects) {

    public WorkerConfig {
        projects = Map.copyOf(projects);
    }

    /**
     * @param path   an existing clone on this computer
     * @param model  overrides the team's model for this project here; null keeps the team's
     * @param effort likewise
     */
    public record Project(String path, String model, String effort) {
    }
}
