package dispatch.core;

import dispatch.Log;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.domain.Attachment;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.InvalidPlanException;
import dispatch.domain.Plan;
import dispatch.domain.Run;
import dispatch.domain.RunCause;
import dispatch.domain.RunKind;
import dispatch.domain.Task;
import dispatch.store.Attachments;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Carries one claimed run to its end on the calling thread. It reads the store and the config into a {@link Job}, gives
 * that to a {@link Worker} — in this process {@link JobRunner}, in a team the requester's own computer (W-3) — and turns
 * the {@link JobResult} into exactly one outcome transition. Whatever goes wrong, the run never stays RUNNING.
 */
public final class Coordinator {

    private final Database db;
    private final Projects projects;
    private final RunTransitions transitions;
    private final ActiveRuns activeRuns;
    private final Function<Config.Project, Config.RunLimits> planLimits;
    private final Function<Config.Project, Config.RunLimits> executeLimits;
    private final Worker worker;
    private final Runnable wakeScheduler;

    public Coordinator(Database db, Projects projects, RunTransitions transitions, ActiveRuns activeRuns,
                       Function<Config.Project, Config.RunLimits> planLimits,
                       Function<Config.Project, Config.RunLimits> executeLimits, Worker worker, Runnable wakeScheduler) {
        this.db = db;
        this.projects = projects;
        this.transitions = transitions;
        this.activeRuns = activeRuns;
        this.planLimits = planLimits;
        this.executeLimits = executeLimits;
        this.worker = worker;
        this.wakeScheduler = wakeScheduler;
    }

    public void execute(ClaimedRun claimed) {
        ActiveRuns.ActiveRun active = activeRuns.register(claimed.taskId(), claimed.seq());
        try {
            job(claimed).ifPresent(job -> apply(job, worker.run(job, events(claimed), active)));
        } catch (RuntimeException e) {
            Log.error("run.crashed", e, "task", claimed.taskId(), "run", claimed.seq());
            failAfterCrash(claimed, e);
        } finally {
            activeRuns.unregister(active);
            wakeScheduler.run();
        }
    }

    /** The store writes a job cannot wait for: the next run needs both even when this one never finishes. */
    private JobEvents events(ClaimedRun claimed) {
        return new JobEvents() {

            @Override
            public void worktreeCreated(String worktree, String baseSha) {
                transitions.recordWorktree(claimed.taskId(), Path.of(worktree), baseSha);
            }

            @Override
            public void agentStarted(Long pid, Instant processStart) {
                transitions.agentStarted(claimed.taskId(), claimed.seq(), pid, processStart);
            }
        };
    }

    /** Everything the worker needs, read here and only here; empty when the run already failed because its project is gone. */
    private Optional<Job> job(ClaimedRun claimed) {
        long taskId = claimed.taskId();
        int seq = claimed.seq();
        Task task = db.transactionReturning(tx -> Tasks.find(tx, taskId))
                .orElseThrow(() -> new IllegalStateException("claimed run for missing task " + taskId));
        Run run = db.transactionReturning(tx -> Runs.find(tx, taskId, seq))
                .orElseThrow(() -> new IllegalStateException("claimed run " + taskId + "." + seq + " is missing"));
        Optional<Config.Project> project = projects.byName(task.project());
        if (project.isEmpty()) {
            transitions.failed(taskId, seq, FailureReason.SETUP, "project " + task.project() + " is no longer configured", null);
            return Optional.empty();
        }
        return Optional.of(switch (run.kind()) {
            case PLAN -> planJob(task, run, project.get());
            case EXECUTE -> executeJob(task, run, project.get());
            case DELIVER -> deliverJob(task, run, project.get());
            case SPLIT, ASSISTANT -> throw new IllegalStateException(run.kind() + " is never a task's run");
        });
    }

    private Job planJob(Task task, Run run, Config.Project project) {
        Config.RunLimits limits = planLimits.apply(project);
        // A task that already has a plan is being corrected: this run's instruction is the member's reply.
        String prompt = task.planJson() == null ? Prompts.plan(task) : Prompts.correction(task, run);
        return newJob(task, run, project, task.sessionId(), agentStartedBefore(task.id(), RunKind.PLAN, run.seq()), prompt,
                project.planModel(), project.planEffort(), limits.timeout().toMillis(), limits.budgetUsd(),
                attachments(task.id()), null);
    }

    private Job executeJob(Task task, Run run, Config.Project project) {
        Config.RunLimits limits = executeLimits.apply(project);
        boolean resume = agentStartedBefore(task.id(), RunKind.EXECUTE, run.seq());
        return newJob(task, run, project, buildSession(task), resume, executePrompt(task, run, resume), project.executeModel(),
                project.executeEffort(), limits.timeout().toMillis(), limits.budgetUsd(), attachments(task.id()), null);
    }

    /** A delivery run has no agent: it commits what the failed delivery left, with that run's summary as the body. */
    private Job deliverJob(Task task, Run run, Config.Project project) {
        return newJob(task, run, project, null, false, null, null, null, 0L, null, List.of(), run.instruction());
    }

    private Job newJob(Task task, Run run, Config.Project project, UUID sessionId, boolean resume, String prompt, String model,
                    String effort, long timeoutMillis, BigDecimal budgetUsd, List<Attachment> attachments,
                    String deliverySummary) {
        Job.Project on = new Job.Project(project.name(), project.repo(), project.path(), project.baseBranch(), project.agent(),
                project.copyFiles());
        return new Job(task.id(), run.seq(), run.kind(), on, task.baseBranch(), task.baseSha(),
                task.worktree() == null ? null : task.worktree().toString(), task.prUrl(), sessionId, resume, prompt, model,
                effort, timeoutMillis, budgetUsd, attachments, "dispatch #" + task.id() + ": " + task.title(),
                trailers(task, run.kind()), deliverySummary);
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

    /**
     * The delivery commit's trailers: who asked, and whoever approved the plan (later runs do not change that). Only
     * EXECUTE and DELIVER jobs ever deliver, so a PLAN job skips the {@code Runs.forTask} query and gets none.
     */
    private List<String> trailers(Task task, RunKind kind) {
        if (kind != RunKind.EXECUTE && kind != RunKind.DELIVER) {
            return List.of();
        }
        List<String> trailers = new ArrayList<>(List.of("Requested-by: " + task.requester().name()));
        db.transactionReturning(tx -> Runs.forTask(tx, task.id())).stream()
                .filter(run -> run.cause() == RunCause.APPROVAL && run.requestedByName() != null)
                .map(Run::requestedByName).findFirst()
                .ifPresent(approver -> trailers.add("Approved-by: " + approver));
        return trailers;
    }

    private List<Attachment> attachments(long taskId) {
        return db.transactionReturning(tx -> Attachments.forTask(tx, taskId));
    }

    private boolean agentStartedBefore(long taskId, RunKind kind, int seq) {
        return db.transactionReturning(tx -> Runs.agentStartedBefore(tx, taskId, kind, seq));
    }

    /** The first execution run starts the building session from the approved plan; later ones continue it (ADR 0017). */
    private UUID buildSession(Task task) {
        if (task.buildSessionId() != null) {
            return task.buildSessionId();
        }
        UUID session = UUID.randomUUID();
        transitions.recordBuildSession(task.id(), session);
        return session;
    }

    /** Exactly one transition per run. */
    private void apply(Job job, JobResult result) {
        switch (result.outcome()) {
            case CANCELLED -> transitions.cancelled(job.taskId(), job.seq(), result.agent());
            case FAILED -> transitions.failed(job.taskId(), job.seq(), result.failureReason(), result.failureDetail(),
                    result.agent());
            case SUCCEEDED -> succeeded(job, result);
        }
    }

    private void succeeded(Job job, JobResult result) {
        switch (job.kind()) {
            case PLAN -> finishPlan(job.taskId(), job.seq(), result.agent());
            case EXECUTE -> transitions.completed(job.taskId(), job.seq(), result.agent(), result.files(), result.prUrl());
            case DELIVER -> transitions.completed(job.taskId(), job.seq(), null, job.deliverySummary(), result.files(),
                    result.prUrl());
            case SPLIT, ASSISTANT -> throw new IllegalStateException(job.kind() + " is never a task's run");
        }
    }

    /** The plan is parsed here, not by the worker: a worker's answer is checked before it becomes the task's plan. */
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
}
