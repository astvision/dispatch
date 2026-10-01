package dispatch.cli;

import dispatch.config.Config;
import dispatch.core.Teleport;
import dispatch.domain.Task;
import dispatch.store.Database;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * `dispatch teleport N`: continues task #N's agent conversation in this terminal, in its worktree (RM-6). Claude Code keeps
 * a session's transcript by the directory it ran in, so resuming there finds it. The task keeps its state: a later
 * correction or follow-up resumes the same session, with whatever was done here.
 */
public final class TeleportCommand {

    /** Where and what to run: the task's worktree, and Claude Code resuming its session. */
    public record Target(Path workdir, List<String> command) {
    }

    private TeleportCommand() {
    }

    /** Runs Claude in the terminal and answers with its exit code. */
    public static int run(Cli.Teleport teleport, Map<String, String> env) {
        // Through RunCommand, so the token in dispatch.env counts as it does for `dispatch run`.
        Config config = RunCommand.prepare(teleport.configFile(), env).config();
        Target target;
        try (Database db = Database.open(config.stateDir().resolve("dispatch.db"))) {
            String agent = config.projects().stream().filter(project -> project.name().equals(projectOf(db, teleport.taskId())))
                    .map(Config.Project::agent).findFirst().orElse("claude-code");
            Config.Agent claude = config.agents().get("claude-code");
            String command = claude == null || claude.command() == null ? "claude" : claude.command();
            target = db.transactionReturning(tx -> target(tx, teleport.taskId(), teleport.plan(), agent, command));
        }
        System.out.println("#" + teleport.taskId() + ": " + String.join(" ", target.command()) + "  (in " + target.workdir() + ")");
        try {
            return new ProcessBuilder(target.command()).directory(target.workdir().toFile()).inheritIO().start().waitFor();
        } catch (IOException e) {
            throw new CliException("cannot start " + target.command().getFirst() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    private static String projectOf(Database db, long taskId) {
        return db.transactionReturning(tx -> Tasks.find(tx, taskId).map(Task::project).orElse(null));
    }

    /**
     * What `dispatch teleport` opens, or why it cannot, in English for the terminal.
     *
     * @param plan    the planning session even when the task has a build session
     * @param agent   the task's project's agent type
     * @param claude  the Claude Code command this instance runs
     */
    static Target target(Tx tx, long taskId, boolean plan, String agent, String claude) {
        Teleport teleport = Teleport.of(tx, taskId, plan, agent);
        if (teleport.refusal() != null) {
            throw new CliException(switch (teleport.refusal()) {
                case NO_TASK -> "no task #" + taskId + " in this instance";
                case RUNNING -> "#" + taskId + " is running; teleport once it finishes, or /cancel it first";
                case NOT_CLAUDE -> "#" + taskId + " runs on " + agent + "; teleport opens Claude Code sessions only for now";
                case ON_WORKER -> "#" + taskId + " ran on " + teleport.worker() + "; its session is on that computer, so teleport there: "
                        + teleport.shellLine();
                case NO_WORKTREE -> "#" + taskId + "'s worktree is gone; a follow-up or retry makes it again";
            });
        }
        return new Target(teleport.workdir(), teleport.command(claude));
    }
}
