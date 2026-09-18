package dispatch.core;

import dispatch.Log;
import dispatch.Redactor;
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
import dispatch.domain.Run;
import dispatch.domain.RunCause;
import dispatch.domain.RunKind;
import dispatch.domain.Task;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.workspace.Delivery;
import dispatch.workspace.WorkspaceException;
import dispatch.workspace.Workspaces;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Carries one claimed run to its end on the calling thread: worktree, agent process, timeout watchdog, delivery of an
 * execution run's changes, and exactly one outcome transition. Whatever goes wrong, the run never stays RUNNING.
 */
public final class RunExecutor {

    private final Database db;
    private final Projects projects;
    private final Workspaces workspaces;
    private final Delivery delivery;
    private final Map<String, Agent> agents;
    private final RunTransitions transitions;
    private final ActiveRuns activeRuns;
    private final Function<Config.Project, Config.RunLimits> planLimits;
    private final Function<Config.Project, Config.RunLimits> executeLimits;
    private final Redactor redactor;
    private final Runnable wakeScheduler;

    /** @param redactor masks secrets in the agent's summary before it becomes a commit message and pull request */
    public RunExecutor(Database db, Projects projects, Workspaces workspaces, Delivery delivery, Map<String, Agent> agents,
                       RunTransitions transitions, ActiveRuns activeRuns, Function<Config.Project, Config.RunLimits> planLimits,
                       Function<Config.Project, Config.RunLimits> executeLimits, Redactor redactor, Runnable wakeScheduler) {
        this.db = db;
        this.projects = projects;
        this.workspaces = workspaces;
        this.delivery = delivery;
        this.agents = Map.copyOf(agents);
        this.transitions = transitions;
        this.activeRuns = activeRuns;
        this.planLimits = planLimits;
        this.executeLimits = executeLimits;
        this.redactor = redactor;
        this.wakeScheduler = wakeScheduler;
    }

    public void execute(ClaimedRun claimed) {
        ActiveRuns.ActiveRun active = activeRuns.register(claimed.taskId(), claimed.seq());
        try {
            switch (claimed.kind()) {
                case PLAN -> plan(active);
                case EXECUTE -> implement(active);
                case DELIVER -> deliverAgain(active);
                case SPLIT -> throw new IllegalStateException("a split is never a task's run");
            }
        } catch (RuntimeException e) {
            Log.error("run.crashed", e, "task", claimed.taskId(), "run", claimed.seq());
            failAfterCrash(claimed, e);
        } finally {
            activeRuns.unregister(active);
            wakeScheduler.run();
        }
    }

    private void plan(ActiveRuns.ActiveRun active) {
        Task task = task(active.taskId());
        Run run = run(active);
        Optional<Config.Project> project = project(task, active);
        if (project.isEmpty() || stoppedBeforeAgent(active)) {
            return;
        }
        Optional<Path> worktree = task.worktree() == null ? createWorktree(task, project.get(), active) : existingWorktree(task, project.get(), active);
        if (worktree.isEmpty() || stoppedBeforeAgent(active)) {
            return;
        }

        // A task that already has a plan is being corrected: this run's instruction is the member's reply.
        String prompt = task.planJson() == null ? Prompts.plan(task) : Prompts.correction(task, run);
        Config.RunLimits limits = planLimits.apply(project.get());
        RunRequest request = new RunRequest(RunKind.PLAN, worktree.get(), prompt, task.sessionId(), agentStartedBefore(active, RunKind.PLAN), List.of(),
                limits.budgetUsd(), project.get().planModel(), project.get().planEffort(), workspaces.runLogBase(task.id(), active.seq()));
        Optional<AgentResult> result = runAgent(active, project.get(), request, limits.timeout());
        if (result.isEmpty() || endedWithoutSuccess(active, result.get(), limits.timeout())) {
            return;
        }
        finishPlan(active, result.get());
    }

    private void implement(ActiveRuns.ActiveRun active) {
        Task task = task(active.taskId());
        Run run = run(active);
        Optional<Config.Project> project = project(task, active);
        if (project.isEmpty() || stoppedBeforeAgent(active)) {
            return;
        }
        Optional<Path> worktree = existingWorktree(task, project.get(), active);
        if (worktree.isEmpty()) {
            return;
        }
        String startSha;
        try {
            // Local-only files (e.g. .env) the build and tests need; never part of planning runs.
            workspaces.copyFiles(project.get(), worktree.get());
            startSha = delivery.head(worktree.get());
        } catch (WorkspaceException e) {
            transitions.failed(task.id(), active.seq(), FailureReason.SETUP, e.getMessage(), null);
            return;
        }
        if (stoppedBeforeAgent(active)) {
            return;
        }

        // The first execution run starts the building session from the approved plan; later ones continue it (ADR 0017).
        UUID buildSession = task.buildSessionId();
        if (buildSession == null) {
            buildSession = UUID.randomUUID();
            transitions.recordBuildSession(task.id(), buildSession);
        }
        boolean resume = agentStartedBefore(active, RunKind.EXECUTE);
        Config.RunLimits limits = executeLimits.apply(project.get());
        RunRequest request = new RunRequest(RunKind.EXECUTE, worktree.get(), executePrompt(task, run, resume),
                buildSession, resume, List.of(), limits.budgetUsd(), project.get().executeModel(), project.get().executeEffort(),
                workspaces.runLogBase(task.id(), active.seq()));
        Optional<AgentResult> result = runAgent(active, project.get(), request, limits.timeout());
        if (result.isEmpty() || endedWithoutSuccess(active, result.get(), limits.timeout())) {
            return;
        }
        deliver(task, run, worktree.get(), startSha, result.get());
    }

    /**
     * A session that never ran (the first execution, or one whose earlier runs all failed before their agent started) gets
     * the approved plan; a resumed one is told only what this run adds.
     */
    private static String executePrompt(Task task, Run run, boolean resume) {
        if (!resume) {
            return Prompts.execute(task, task.planJson());
        }
        return switch (run.cause()) {
            case RETRY -> Prompts.retry(task, run.instruction());
            case FOLLOW_UP -> Prompts.followUp(task, run);
            default -> Prompts.execute(task, task.planJson());
        };
    }

    private void deliver(Task task, Run run, Path worktree, String startSha, AgentResult result) {
        Delivery.Commit commit = commit(task, result.summary());
        Delivery.Result delivered;
        try {
            delivered = delivery.deliver(worktree, task.id(), task.baseBranch(), startSha, commit, task.prUrl());
        } catch (WorkspaceException e) {
            transitions.failed(task.id(), run.seq(), FailureReason.DELIVERY, e.getMessage(), result);
            return;
        }
        transitions.completed(task.id(), run.seq(), result, delivered.files(), delivered.prUrl());
    }

    /** Delivers a failed delivery's work again, without the agent: the run's instruction is that run's summary. */
    private void deliverAgain(ActiveRuns.ActiveRun active) {
        Task task = task(active.taskId());
        Run run = run(active);
        Optional<Config.Project> project = project(task, active);
        if (project.isEmpty() || stoppedBeforeAgent(active)) {
            return;
        }
        Optional<Path> worktree = existingWorktree(task, project.get(), active);
        if (worktree.isEmpty()) {
            return;
        }
        Delivery.Result delivered;
        try {
            delivered = delivery.redeliver(worktree.get(), task.id(), task.baseBranch(), task.baseSha(), commit(task, run.instruction()),
                    task.prUrl());
        } catch (WorkspaceException e) {
            transitions.failed(task.id(), run.seq(), FailureReason.DELIVERY, e.getMessage(), null);
            return;
        }
        transitions.completed(task.id(), run.seq(), null, run.instruction(), delivered.files(), delivered.prUrl());
    }

    /** The task's delivery commit: its title, the agent's summary with secrets masked, and who asked and approved. */
    private Delivery.Commit commit(Task task, String summary) {
        String body = summary == null ? "" : redactor.redact(summary).strip();
        List<String> trailers = new ArrayList<>(List.of("Requested-by: " + task.requester().name()));
        approver(task.id()).ifPresent(approver -> trailers.add("Approved-by: " + approver));
        return new Delivery.Commit("dispatch #" + task.id() + ": " + task.title(), body, trailers);
    }

    /** Whoever approved the task's plan; later runs (retries, follow-ups) do not change that. */
    private Optional<String> approver(long taskId) {
        return db.transactionReturning(tx -> Runs.forTask(tx, taskId)).stream()
                .filter(run -> run.cause() == RunCause.APPROVAL && run.requestedByName() != null)
                .map(Run::requestedByName).findFirst();
    }

    private boolean agentStartedBefore(ActiveRuns.ActiveRun active, RunKind kind) {
        return db.transactionReturning(tx -> Runs.agentStartedBefore(tx, active.taskId(), kind, active.seq()));
    }

    /** No copyFiles here: planning needs no local secrets, and whatever the agent reads may be quoted in the group. */
    private Optional<Path> createWorktree(Task task, Config.Project project, ActiveRuns.ActiveRun active) {
        try {
            Workspaces.PreparedWorktree worktree = workspaces.createWorktree(project, task.id());
            transitions.recordWorktree(task.id(), worktree.path(), worktree.baseSha());
            return Optional.of(worktree.path());
        } catch (WorkspaceException e) {
            transitions.failed(task.id(), active.seq(), FailureReason.SETUP, e.getMessage(), null);
            return Optional.empty();
        }
    }

    /** Later runs continue in the worktree the first planning run created; one the idle sweep removed is added back. */
    private Optional<Path> existingWorktree(Task task, Config.Project project, ActiveRuns.ActiveRun active) {
        if (task.worktree() != null && Files.isDirectory(task.worktree())) {
            return Optional.of(task.worktree());
        }
        if (task.worktree() == null) {
            transitions.failed(task.id(), active.seq(), FailureReason.SETUP, "the task has no worktree", null);
            return Optional.empty();
        }
        try {
            Path recreated = workspaces.recreateWorktree(project, task.id());
            Log.info("worktree.recreated", "task", task.id(), "worktree", recreated);
            return Optional.of(recreated);
        } catch (WorkspaceException e) {
            transitions.failed(task.id(), active.seq(), FailureReason.SETUP, "worktree " + task.worktree() + " is missing and could not be "
                    + "recreated: " + e.getMessage(), null);
            return Optional.empty();
        }
    }

    private Optional<Config.Project> project(Task task, ActiveRuns.ActiveRun active) {
        Optional<Config.Project> project = projects.byName(task.project());
        if (project.isEmpty()) {
            transitions.failed(task.id(), active.seq(), FailureReason.SETUP,
                    "project " + task.project() + " is no longer configured", null);
        }
        return project;
    }

    /** Starts the agent and waits for it under the timeout; empty when the run's failure is already recorded. */
    private Optional<AgentResult> runAgent(ActiveRuns.ActiveRun active, Config.Project project, RunRequest request,
                                           Duration timeout) {
        long taskId = active.taskId();
        int seq = active.seq();
        RunHandle handle;
        try {
            handle = agents.get(project.agent()).start(request);
        } catch (AgentStartException e) {
            transitions.failed(taskId, seq, FailureReason.AGENT, e.getMessage(), null);
            return Optional.empty();
        }
        transitions.recordProcess(taskId, seq, handle.process().pid(), handle.processStart());
        active.attach(handle);

        Thread watchdog = Thread.ofVirtual().name("run-timeout-" + taskId + "." + seq).start(() -> {
            try {
                Thread.sleep(timeout);
                active.stop(ActiveRuns.StopReason.TIMEOUT);
            } catch (InterruptedException e) {
                // The run ended before the timeout; nothing to stop.
            }
        });
        try {
            return Optional.of(handle.await());
        } catch (InterruptedException e) {
            handle.cancel();
            Thread.currentThread().interrupt();
            transitions.failed(taskId, seq, FailureReason.INTERRUPTED, "run thread was interrupted", null);
            return Optional.empty();
        } finally {
            watchdog.interrupt();
        }
    }

    /** Records how the run ended unless its agent succeeded; returns false when the run goes on. */
    private boolean endedWithoutSuccess(ActiveRuns.ActiveRun active, AgentResult result, Duration timeout) {
        ActiveRuns.StopReason stopReason = active.stopReason();
        if (stopReason != null) {
            recordStop(active, stopReason, result, timeout);
            return true;
        }
        switch (result.outcome()) {
            case SUCCEEDED -> {
                return false;
            }
            case BUDGET_EXCEEDED -> transitions.failed(active.taskId(), active.seq(), FailureReason.BUDGET, result.error(), result);
            case FAILED -> transitions.failed(active.taskId(), active.seq(), FailureReason.AGENT, result.error(), result);
        }
        return true;
    }

    private boolean stoppedBeforeAgent(ActiveRuns.ActiveRun active) {
        ActiveRuns.StopReason stopReason = active.stopReason();
        if (stopReason == null) {
            return false;
        }
        recordStop(active, stopReason, null, null);
        return true;
    }

    /** @param result null when the run was stopped before its agent started */
    private void recordStop(ActiveRuns.ActiveRun active, ActiveRuns.StopReason stopReason, AgentResult result, Duration timeout) {
        long taskId = active.taskId();
        int seq = active.seq();
        switch (stopReason) {
            case CANCELLED -> transitions.cancelled(taskId, seq, result);
            case TIMEOUT -> transitions.failed(taskId, seq, FailureReason.TIMEOUT, "stopped after " + format(timeout), result);
            case INTERRUPTED -> transitions.failed(taskId, seq, FailureReason.INTERRUPTED,
                    "Dispatch stopped while the run was active", result);
        }
    }

    private void finishPlan(ActiveRuns.ActiveRun active, AgentResult result) {
        long taskId = active.taskId();
        int seq = active.seq();
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

    private Task task(long taskId) {
        return db.transactionReturning(tx -> Tasks.find(tx, taskId))
                .orElseThrow(() -> new IllegalStateException("claimed run for missing task " + taskId));
    }

    private Run run(ActiveRuns.ActiveRun active) {
        return db.transactionReturning(tx -> Runs.find(tx, active.taskId(), active.seq()))
                .orElseThrow(() -> new IllegalStateException("claimed run " + active.taskId() + "." + active.seq() + " is missing"));
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
