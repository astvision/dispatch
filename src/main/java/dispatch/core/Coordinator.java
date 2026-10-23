package dispatch.core;

import dispatch.Json;
import dispatch.Log;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.domain.AgentKind;
import dispatch.domain.Attachment;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.InvalidPlanException;
import dispatch.domain.Plan;
import dispatch.domain.Run;
import dispatch.domain.RunCause;
import dispatch.domain.RunKind;
import dispatch.domain.RunStep;
import dispatch.domain.Task;
import dispatch.store.Attachments;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
    private final String branchPrefix;

    public Coordinator(Database db, Projects projects, RunTransitions transitions, ActiveRuns activeRuns,
                       Function<Config.Project, Config.RunLimits> planLimits,
                       Function<Config.Project, Config.RunLimits> executeLimits, Worker worker, Runnable wakeScheduler) {
        this(db, projects, transitions, activeRuns, planLimits, executeLimits, worker, wakeScheduler, null);
    }

    /** @param branchPrefix the instance's own prefix for task branches (M: several instances on one computer); null for "dispatch" */
    public Coordinator(Database db, Projects projects, RunTransitions transitions, ActiveRuns activeRuns,
                       Function<Config.Project, Config.RunLimits> planLimits,
                       Function<Config.Project, Config.RunLimits> executeLimits, Worker worker, Runnable wakeScheduler,
                       String branchPrefix) {
        this.db = db;
        this.projects = projects;
        this.transitions = transitions;
        this.activeRuns = activeRuns;
        this.planLimits = planLimits;
        this.executeLimits = executeLimits;
        this.worker = worker;
        this.wakeScheduler = wakeScheduler;
        this.branchPrefix = branchPrefix;
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
                transitions.recordWorktree(claimed.taskId(), worktree, baseSha);
            }

            @Override
            public void agentStarted(Long pid, Instant processStart) {
                transitions.agentStarted(claimed.taskId(), claimed.seq(), pid, processStart);
            }

            @Override
            public void stepStarted(int n, RunStep.Kind kind, int round) {
                transitions.stepStarted(claimed.taskId(), claimed.seq(), n, kind, round);
            }

            @Override
            public void stepEnded(int n, RunStep.Outcome outcome, String detail) {
                transitions.stepEnded(claimed.taskId(), claimed.seq(), n, outcome, detail);
            }

            @Override
            public void stepsReported(List<RunStep> steps) {
                transitions.stepsReported(claimed.taskId(), claimed.seq(), steps);
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
            case REPORT -> reportJob(task, run, project.get());
            case SPLIT, ASSISTANT, REVIEW -> throw new IllegalStateException(run.kind() + " is never a task's run");
        });
    }

    private Job planJob(Task task, Run run, Config.Project project) {
        Config.RunLimits limits = planLimits.apply(project);
        // A task that already has a plan is being corrected, or, after an answer, continued: the run's instruction is the reply.
        String prompt = task.planJson() == null ? Prompts.plan(task)
                : !Plan.endsWithoutCode(task.planJson()) ? Prompts.correction(task, run)
                : Plan.answers(task.planJson()) ? Prompts.answerFollowUp(task, run) : Prompts.reportFollowUp(task, run);
        return newJob(task, run, project, task.sessionId(), agentStartedBefore(task.id(), RunKind.PLAN, run.seq()), prompt,
                project.planModel(), project.planEffort(), limits.timeout().toMillis(), limits.budgetUsd(),
                attachments(task.id()), null, null, null, dataSeen(task, run));
    }

    /**
     * Whether the task's session has seen report data (ADR 0043): it ran a report, or its plan proposes one. Every follow-up
     * resumes that session, so only such a one is kept from answering; a code question's reply may answer again.
     */
    private boolean dataSeen(Task task, Run run) {
        if (task.planJson() != null && Plan.parse(task.planJson()).result() == Plan.Result.REPORT) {
            return true;
        }
        return agentStartedBefore(task.id(), RunKind.REPORT, run.seq());
    }

    private Job executeJob(Task task, Run run, Config.Project project) {
        Config.RunLimits limits = executeLimits.apply(project);
        boolean resume = agentStartedBefore(task.id(), RunKind.EXECUTE, run.seq());
        return newJob(task, run, project, buildSession(task), resume, executePrompt(task, run, resume), project.executeModel(),
                project.executeEffort(), limits.timeout().toMillis(), limits.budgetUsd(), attachments(task.id()), null,
                project.loopOn() ? Prompts.review(task, task.planJson(), reviewInstruction(task, run)) : null, picks(task), false);
    }

    /**
     * The approved report, in the session that planned it (spec: data reports): planning's model with an execution's limits,
     * as it runs the queries. The session has seen the attachments; a report has no review and loads no picks.
     */
    private Job reportJob(Task task, Run run, Config.Project project) {
        Config.RunLimits limits = executeLimits.apply(project);
        return newJob(task, run, project, task.sessionId(), true, Prompts.report(task), project.planModel(), project.planEffort(),
                limits.timeout().toMillis(), limits.budgetUsd(), List.of(), null, null, null, false);
    }

    /** The plan's plugin picks (spec: plugin picks); a task without a plan has none. A plan run never loads them. */
    private static List<String> picks(Task task) {
        return task.planJson() == null ? null : Plan.parse(task.planJson()).plugins();
    }

    /** A delivery run has no agent: it commits what the failed delivery left, with that run's summary as the body. */
    private Job deliverJob(Task task, Run run, Config.Project project) {
        return newJob(task, run, project, null, false, null, null, null, 0L, null, List.of(), run.instruction(), null, null, false);
    }

    private Job newJob(Task task, Run run, Config.Project project, UUID sessionId, boolean resume, String prompt, String model,
                    String effort, long timeoutMillis, BigDecimal budgetUsd, List<Attachment> attachments,
                    String deliverySummary, String reviewPrompt, List<String> plugins, boolean dataSeen) {
        Job.Project on = new Job.Project(project.name(), project.repo(), project.path(), project.baseBranch(), project.agent(),
                project.copyFiles(), project.loopOn() ? project.test() : null, project.loopOn() ? Boolean.TRUE : null,
                skills(project));
        return new Job(task.id(), run.seq(), run.kind(), on, task.baseBranch(), task.baseSha(),
                task.worktree(), task.prUrl(), sessionId, resume, prompt, model,
                effort, timeoutMillis, budgetUsd, attachments, "dispatch #" + task.id() + ": " + task.title(),
                trailers(task, run.kind()), deliverySummary, Config.branchFor(branchPrefix, task.id()), reviewPrompt,
                expectedHead(task.id(), run.kind()), plugins, dataSeen);
    }

    /**
     * True only where the run's agent can load the dispatch plugin (spec: agent skills); null means off.
     */
    private static Boolean skills(Config.Project project) {
        return project.skillsOn() && AgentKind.of(project.agent()).loadsPlugins() ? Boolean.TRUE : null;
    }

    /** Null for a PLAN or REPORT job, which neither builds on the branch nor pushes it: nothing to check. */
    private String expectedHead(long taskId, RunKind kind) {
        if (kind == RunKind.PLAN || kind == RunKind.REPORT) {
            return null;
        }
        return db.transactionReturning(tx -> Tasks.expectedHead(tx, taskId));
    }

    /**
     * What this run adds to the approved plan; the approval's (and its retry's) instruction is the plan itself, so none.
     * A CI fix adds none either: its instruction is a failed log, which nobody asked the change to do.
     */
    private static String reviewInstruction(Task task, Run run) {
        if (run.cause() == RunCause.APPROVAL || run.cause() == RunCause.CI_FIX) {
            return null;
        }
        return Objects.equals(run.instruction(), task.planJson()) ? null : run.instruction();
    }

    /**
     * A session that never ran (the first execution, or one whose earlier runs all failed before their agent started) gets
     * the approved plan; a resumed one is told only what this run adds.
     */
    private String executePrompt(Task task, Run run, boolean resume) {
        if (!resume) {
            return Prompts.execute(task, task.planJson());
        }
        return switch (run.cause()) {
            case RETRY -> task.failureReason() != null ? Prompts.retry(task, run.instruction())
                    // The task did not fail: a usage limit re-queued this run (ADR 0040); the run before says why.
                    : Prompts.retry(task, run.instruction(), db.transactionReturning(tx -> Runs.find(tx, task.id(), run.seq() - 1))
                            .map(previous -> previous.failureReason() == null ? "unknown reason" : previous.failureReason().name())
                            .orElse("unknown reason"));
            case FOLLOW_UP -> Prompts.followUp(task, run);
            case CI_FIX -> Prompts.ciFix(task, run);
            default -> Prompts.execute(task, task.planJson());
        };
    }

    /**
     * The delivery commit's trailers: who asked, and whoever approved the plan (later runs do not change that). Only
     * EXECUTE and DELIVER jobs ever deliver, so a PLAN or REPORT job skips the {@code Runs.forTask} query and gets none.
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
            case FAILED -> {
                if (result.failureReason() == FailureReason.DELIVERY) {
                    transitions.deliveryFailed(job.taskId(), job.seq(), result.failureDetail(), result.agent(), result.head());
                } else if (result.failureReason() == FailureReason.USAGE_LIMIT && result.agent() != null) {
                    transitions.limited(job.taskId(), job.seq(), result.failureDetail(), result.agent());
                } else {
                    transitions.failed(job.taskId(), job.seq(), result.failureReason(), result.failureDetail(), result.agent());
                }
            }
            case SUCCEEDED -> {
                succeeded(job, result);
                if (result.agent() != null && result.agent().limit() != null) {
                    // A verify-loop call met the limit though the run delivered: hold the machine all the same.
                    transitions.hold(job.taskId(), result.agent().limit());
                }
            }
        }
    }

    private void succeeded(Job job, JobResult result) {
        switch (job.kind()) {
            case PLAN -> finishPlan(job.taskId(), job.seq(), result.agent(), job.project().name());
            case EXECUTE -> transitions.completed(job.taskId(), job.seq(), result.agent(), result.agent().summary(), result.files(),
                    result.prUrl(), result.verification(), result.head());
            case DELIVER -> transitions.completed(job.taskId(), job.seq(), null, job.deliverySummary(), result.files(),
                    result.prUrl(), null, result.head());
            case REPORT -> finishReport(job.taskId(), job.seq(), result.agent());
            case SPLIT, ASSISTANT, REVIEW -> throw new IllegalStateException(job.kind() + " is never a task's run");
        }
    }

    /** The plan is parsed here, not by the worker: a worker's answer is checked before it becomes the task's plan. */
    private void finishPlan(long taskId, int seq, AgentResult result, String projectName) {
        if (result.structuredOutput() == null) {
            transitions.failed(taskId, seq, FailureReason.AGENT, "agent finished without returning a plan", result);
            return;
        }
        try {
            Plan plan = Plan.parse(result.structuredOutput());
            if (plan.result() == Plan.Result.REPORT && !hasData(projectName)) {
                throw new InvalidPlanException("result report needs the project's data, which is not configured");
            }
            transitions.planSucceeded(taskId, seq, plan, result);
        } catch (InvalidPlanException e) {
            transitions.failed(taskId, seq, FailureReason.AGENT, "plan did not match the schema: " + e.getMessage(), result);
        }
    }

    /** The report is checked here, as a plan is: only a whole HTML document reaches the requester as the answer. */
    private void finishReport(long taskId, int seq, AgentResult result) {
        String html = reportHtml(result.structuredOutput());
        if (html == null || !isHtmlDocument(html)) {
            transitions.failed(taskId, seq, FailureReason.AGENT, "the report run returned no HTML document", result);
            return;
        }
        transitions.reported(taskId, seq, html, result);
    }

    /** The answer's html; null when there is none or the answer is not JSON, which is the agent's failure, as a bad plan is. */
    private static String reportHtml(String structuredOutput) {
        if (structuredOutput == null) {
            return null;
        }
        try {
            return Json.read(structuredOutput).path("html").asText(null);
        } catch (IllegalStateException malformed) {
            return null;
        }
    }

    private static boolean isHtmlDocument(String text) {
        String head = text.strip().toLowerCase(Locale.ROOT);
        return head.startsWith("<!doctype html") || head.startsWith("<html");
    }

    private boolean hasData(String projectName) {
        return projects.byName(projectName).map(project -> project.data() != null).orElse(false);
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
