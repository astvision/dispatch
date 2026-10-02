package dispatch.agent.claude;

import dispatch.agent.RunRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What one Claude Code run loads beyond its working directory: Dispatch's skills plugin (ADR 0034), what the machine's
 * owner lists (ADR 0036) and the plan's picks (ADR 0037). Made once per run, on the machine that runs it; the command line
 * and the sandbox only read it.
 *
 * @param pluginDirs  every plugin the run loads, in the order they are named: the job's own, the owner's and the picks,
 *                    then the plugin of links to the owner's skills
 * @param skillDirs   the owner's skill directories those links point to, which the run must be able to read
 * @param mcpConfig   the one inline MCP configuration, null when the run starts no server
 * @param environment the variables its servers' secrets travel in
 */
record Loadout(List<Path> pluginDirs, List<Path> skillDirs, String mcpConfig, Map<String, String> environment) {

    Loadout {
        pluginDirs = List.copyOf(pluginDirs);
        skillDirs = List.copyOf(skillDirs);
        environment = Map.copyOf(environment);
    }

    /** @param ownerSkillsPlugin the plugin of links to the owner's skills, null when they list none */
    static Loadout of(RunRequest request, OwnerPlugins.Resolved owner, Path ownerSkillsPlugin) {
        List<Path> dirs = new ArrayList<>(request.pluginDirs());
        dirs.addAll(owner.pluginDirs());
        if (ownerSkillsPlugin != null) {
            dirs.add(ownerSkillsPlugin);
        }
        return new Loadout(dirs, owner.skills(), owner.mcpConfig(), owner.environment());
    }

    /** A plugin's skill cannot be invoked without the Skill tool (probed on Claude Code 2.1.286). */
    boolean skillTool() {
        return !pluginDirs.isEmpty();
    }
}
