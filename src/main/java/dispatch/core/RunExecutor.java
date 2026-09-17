package dispatch.core;

import dispatch.Log;
import dispatch.agent.Agent;
import dispatch.agent.AgentResult;
import dispatch.agent.AgentStartException;
import dispatch.agent.RunHandle;
import dispatch.agent.RunRequest;
import dispatch.config.Config;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.InvalidPlanException;
import dispatch.domain.Plan;
import dispatch.domain.RunKind;
import dispatch.domain.Task;
import dispatch.store.Database;
import dispatch.store.Tasks;
import dispatch.workspace.WorkspaceException;
import dispatch.workspace.Workspaces;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Carries one claimed run to its end on the calling thread: worktree, agent process, timeout watchdog, and exactly one
 * outcome transition. Whatever goes wrong, the run never stays RUNNING.
 */
public final class RunExecutor {

    private final Database db;
    private final Projects projects;
    private final Workspaces workspaces;
    private final Map<String, Agent> agents;
    private final RunTransitions transitions;
    private final ActiveRuns activeRuns;
    private final Function<Config.Project, Config.RunLimits> planLimits;
    private final Runnable wakeScheduler;

    public RunExecutor(Database db, Projects projects, Workspaces workspaces, Map<String, Agent> agents,
                       RunTransitions transitions, ActiveRuns activeRuns,
                       Function<Config.Project, Config.RunLimits> planLimits, Runnable wakeScheduler) {
        this.db = db;
        this.projects = projects;
        this.workspaces = workspaces;
        this.agents = Map.copyOf(agents);
        this.transitions = transitions;
        this.activeRuns = activeRuns;
        this.planLimits = planLimits;
        this.wakeScheduler = wakeScheduler;
    }

    public void execute(ClaimedRun claimed) {
        ActiveRuns.ActiveRun active = activeRuns.register(claimed.taskId(), claimed.seq());
        try {
            if (claimed.kind() == RunKind.PLAN) {
                plan(claimed, active);
            } else {
                transitions.failed(claimed.taskId(), claimed.seq(), FailureReason.INTERNAL,
                        claimed.kind() + " runs are not supported until M2", null);
            }
        } catch (RuntimeException e) {
            Log.error("run.crashed", e, "task", claimed.taskId(), "run", claimed.seq());
            failAfterCrash(claimed, e);
        } finally {
            activeRuns.unregister(active);
            wakeScheduler.run();
        }
    }

    private void plan(ClaimedRun claimed, ActiveRuns.ActiveRun active) {
        long taskId = claimed.taskId();
        int seq = claimed.seq();
        Task task = db.transactionReturning(tx -> Tasks.find(tx, taskId))
                .orElseThrow(() -> new IllegalStateException("claimed run for missing task " + taskId));
        Optional<Config.Project> configured = projects.byName(task.project());
        if (configured.isEmpty()) {
            transitions.failed(taskId, seq, FailureReason.SETUP, "project " + task.project() + " is no longer configured", null);
            return;
        }
        Config.Project project = configured.get();
        if (active.stopReason() != null) {
            finish(taskId, seq, active.stopReason(), null, null);
            return;
        }

        Workspaces.PreparedWorktree worktree;
        try {
            // No copyFiles here: planning needs no local secrets (.env), and whatever the agent can read may end up
            // quoted in a plan posted to the group. They are copied only when an execution run starts.
            worktree = workspaces.createWorktree(project, taskId);
            transitions.recordWorktree(taskId, worktree.path(), worktree.baseSha());
        } catch (WorkspaceException e) {
            transitions.failed(taskId, seq, FailureReason.SETUP, e.getMessage(), null);
            return;
        }
        if (active.stopReason() != null) {
            finish(taskId, seq, active.stopReason(), null, null);
            return;
        }

        Config.RunLimits limits = planLimits.apply(project);
        RunRequest request = new RunRequest(RunKind.PLAN, worktree.path(), Prompts.plan(task), task.sessionId(), seq > 1,
                List.of(), limits.budgetUsd(), project.model(), workspaces.runLogBase(taskId, seq));
        RunHandle handle;
        try {
            handle = agents.get(project.agent()).start(request);
        } catch (AgentStartException e) {
            transitions.failed(taskId, seq, FailureReason.AGENT, e.getMessage(), null);
            return;
        }
        transitions.recordProcess(taskId, seq, handle.process());
        active.attach(handle);

        Thread watchdog = Thread.ofVirtual().name("run-timeout-" + taskId + "." + seq).start(() -> {
            try {
                Thread.sleep(limits.timeout());
                active.stop(ActiveRuns.StopReason.TIMEOUT);
            } catch (InterruptedException e) {
                // The run ended before the timeout; nothing to stop.
            }
        });
        AgentResult result;
        try {
            result = handle.await();
        } catch (InterruptedException e) {
            handle.cancel();
            Thread.currentThread().interrupt();
            transitions.failed(taskId, seq, FailureReason.INTERRUPTED, "run thread was interrupted", null);
            return;
        } finally {
            watchdog.interrupt();
        }
        finish(taskId, seq, active.stopReason(), result, limits.timeout());
    }

    /** @param result null when the run was stopped before its agent started */
    private void finish(long taskId, int seq, ActiveRuns.StopReason stopReason, AgentResult result, Duration timeout) {
        if (stopReason != null) {
            switch (stopReason) {
                case CANCELLED -> transitions.cancelled(taskId, seq, result);
                case TIMEOUT -> transitions.failed(taskId, seq, FailureReason.TIMEOUT, "stopped after " + format(timeout), result);
                case INTERRUPTED -> transitions.failed(taskId, seq, FailureReason.INTERRUPTED,
                        "Dispatch stopped while the run was active", result);
            }
            return;
        }
        switch (result.outcome()) {
            case SUCCEEDED -> finishPlan(taskId, seq, result);
            case BUDGET_EXCEEDED -> transitions.failed(taskId, seq, FailureReason.BUDGET, result.error(), result);
            case FAILED -> transitions.failed(taskId, seq, FailureReason.AGENT, result.error(), result);
        }
    }

    private void finishPlan(long taskId, int seq, AgentResult result) {
        if (result.structuredOutput() == null) {
            transitions.failed(taskId, seq, FailureReason.AGENT, "agent finished without returning a plan", result);
            return;
        }
        try {
            transitions.planSucceeded(taskId, seq, Plan.parse(result.structuredOutput()), result);
        } catch (InvalidPlanException e) {
            transitions.failed(taskId, seq, FailureReason.AGENT, "plan did not match the schema: " + e.getMessage(), result);
        }
    }

    private void failAfterCrash(ClaimedRun claimed, RuntimeException cause) {
        try {
            transitions.failed(claimed.taskId(), claimed.seq(), FailureReason.INTERNAL,
                    "Dispatch error: " + cause.getMessage(), null);
        } catch (RuntimeException e) {
            // Storage itself is failing; the scheduler hits the same error and stops the process.
            Log.error("run.crash_not_recorded", e, "task", claimed.taskId(), "run", claimed.seq());
        }
    }

    private static String format(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds > 0 && seconds % 3600 == 0) {
            return seconds / 3600 + "h";
        }
        if (seconds > 0 && seconds % 60 == 0) {
            return seconds / 60 + "m";
        }
        return seconds > 0 ? seconds + "s" : duration.toMillis() + "ms";
    }
}
