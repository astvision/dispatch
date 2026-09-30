package dispatch.core;

import dispatch.Log;
import dispatch.OwnerOnly;
import dispatch.Redactor;
import dispatch.agent.Agent;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.AgentStartException;
import dispatch.agent.RunHandle;
import dispatch.agent.RunRequest;
import dispatch.agent.sandbox.Confinement;
import dispatch.config.Config;
import dispatch.domain.Attachment;
import dispatch.domain.FailureReason;
import dispatch.domain.RunKind;
import dispatch.workspace.Delivery;
import dispatch.workspace.WorkspaceException;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The machine work of one run, in this process: the worktree, the task's files, the agent under its timeout, and its
 * delivery. It reads no store and no config — everything arrives in the {@link Job} and leaves in the {@link JobResult}
 * — so W-3 can put HTTP between it and the {@link Coordinator}.
 */
public final class JobRunner implements Worker {

    /** Kept back from each verify-loop agent call's time, so the run can still deliver after a call that timed out. */
    static final Duration DELIVERY_RESERVE = Duration.ofMinutes(2);

    private final Workspaces workspaces;
    private final Delivery delivery;
    private final Map<String, Agent> agents;
    private final Redactor redactor;
    private final AttachmentSource attachmentSource;
    private final TestRunner tests;
    private final Clock clock;
    private final Duration deliveryReserve;

    /** Without a sandbox for the verify loop's test command; for tests and callers that never run the loop. */
    public JobRunner(Workspaces workspaces, Delivery delivery, Map<String, Agent> agents, Redactor redactor,
                     AttachmentSource attachmentSource) {
        this(workspaces, delivery, agents, redactor, attachmentSource,
                new TestCommand(Confinement.none("no sandbox configured")), Clock.systemUTC());
    }

    /**
     * @param redactor         masks secrets in the agent's summary before it becomes a commit message and pull request
     * @param attachmentSource downloads the files sent with a task
     * @param tests            runs the project's test command for the verify loop
     */
    public JobRunner(Workspaces workspaces, Delivery delivery, Map<String, Agent> agents, Redactor redactor,
                     AttachmentSource attachmentSource, TestRunner tests, Clock clock) {
        this(workspaces, delivery, agents, redactor, attachmentSource, tests, clock, DELIVERY_RESERVE);
    }

    /** @param deliveryReserve see {@link #DELIVERY_RESERVE}; a test shortens it so a loop call times out in seconds */
    JobRunner(Workspaces workspaces, Delivery delivery, Map<String, Agent> agents, Redactor redactor,
              AttachmentSource attachmentSource, TestRunner tests, Clock clock, Duration deliveryReserve) {
        this.workspaces = workspaces;
        this.delivery = delivery;
        this.agents = Map.copyOf(agents);
        this.redactor = redactor;
        this.attachmentSource = attachmentSource;
        this.tests = tests;
        this.clock = clock;
        this.deliveryReserve = deliveryReserve;
    }

    @Override
    public JobResult run(Job job, JobEvents events, ActiveRuns.ActiveRun control) {
        return switch (job.kind()) {
            case PLAN -> plan(job, events, control);
            case EXECUTE -> implement(job, events, control);
            case DELIVER -> deliverAgain(job, control);
            case SPLIT, ASSISTANT, REVIEW -> throw new IllegalStateException(job.kind() + " is never a task's run");
        };
    }

    private JobResult plan(Job job, JobEvents events, ActiveRuns.ActiveRun control) {
        if (control.stopReason() != null) {
            return stopped(job, control.stopReason(), null);
        }
        Path worktree;
        TaskFiles files;
        try {
            worktree = job.worktree() == null ? createWorktree(job, events) : existingWorktree(job);
            if (control.stopReason() != null) {
                return stopped(job, control.stopReason(), null);
            }
            files = attachments(job);
        } catch (WorkspaceException e) {
            return JobResult.failed(FailureReason.SETUP, e.getMessage(), null);
        }
        return runAgent(job, events, control, request(job, worktree, files), Duration.ofMillis(job.timeoutMillis()));
    }

    private JobResult implement(Job job, JobEvents events, ActiveRuns.ActiveRun control) {
        if (control.stopReason() != null) {
            return stopped(job, control.stopReason(), null);
        }
        Path worktree;
        TaskFiles files;
        String startSha;
        try {
            worktree = existingWorktree(job);
            files = attachments(job);
            // Local-only files (e.g. .env) the build and tests need; never part of planning runs.
            workspaces.copyFiles(config(job.project()), worktree);
            startSha = delivery.head(worktree);
        } catch (WorkspaceException e) {
            return JobResult.failed(FailureReason.SETUP, e.getMessage(), null);
        }
        String moved = branchMoved(job, worktree, job.expectedHead());
        if (moved != null) {
            return JobResult.failed(FailureReason.SETUP, moved, null);
        }
        if (control.stopReason() != null) {
            return stopped(job, control.stopReason(), null);
        }
        // The whole run's time: the loop's tests and agent calls share what the implementation left.
        Instant deadline = clock.instant().plusMillis(job.timeoutMillis());
        JobResult result = runAgent(job, events, control, request(job, worktree, files), Duration.ofMillis(job.timeoutMillis()));
        if (result.outcome() != JobResult.Outcome.SUCCEEDED) {
            return result;
        }
        if (!job.project().loopOn()) {
            return deliver(job, worktree, startSha, result.agent(), null);
        }
        VerifyLoop.Outcome verified = new VerifyLoop(tests, clock).run(
                new VerifyLoop.Setup(job.project().test(), worktree, workspaces.runLogBase(job.taskId(), job.seq()),
                        job.reviewPrompt(), deadline, job.budgetUsd(), cost(result.agent())),
                loopAgents(job, events, control, worktree, files, startSha),
                // An interrupt ends a test step as stopped; the loop must not go on to the reviewer.
                () -> control.stopReason() != null || Thread.currentThread().isInterrupted());
        // Every outcome records what the whole run cost, so the loop's calls count even when nothing is delivered.
        AgentResult spent = combined(result.agent(), verified.runs());
        if (control.stopReason() != null) {
            return stopped(job, control.stopReason(), spent);
        }
        if (Thread.currentThread().isInterrupted()) {
            // Dispatch is going down under this run; the flag stays set for whoever interrupted it.
            return JobResult.failed(FailureReason.INTERRUPTED, "run thread was interrupted", spent);
        }
        return deliver(job, worktree, startSha, spent, verified.verification());
    }

    /** The verify loop's agent calls: fixes resume the building session, the reviewer is a fresh read-only one. */
    private VerifyLoop.Agents loopAgents(Job job, JobEvents events, ActiveRuns.ActiveRun control, Path worktree, TaskFiles files,
                                         String startSha) {
        Path logBase = workspaces.runLogBase(job.taskId(), job.seq());
        return new VerifyLoop.Agents() {
            private int fixes;

            @Override
            public AgentResult fix(String prompt, BigDecimal budgetUsd, Duration timeout) {
                fixes++;
                return call(job, events, control, new RunRequest(RunKind.EXECUTE, worktree, prompt, job.sessionId(), true,
                        files.dirs(), budgetUsd, job.model(), job.effort(), Path.of(logBase + ".fix-" + fixes)), timeout);
            }

            @Override
            public AgentResult review(String prompt, BigDecimal budgetUsd, Duration timeout) {
                return call(job, events, control, new RunRequest(RunKind.REVIEW, worktree, prompt, UUID.randomUUID(), false,
                        files.dirs(), budgetUsd, job.model(), job.effort(), Path.of(logBase + ".review")), timeout);
            }

            @Override
            public String diff() {
                // The whole task's change since its branch started, so a follow-up's reviewer sees what it builds on;
                // a job from before the worktree recorded its base has only this run's start.
                String since = job.baseSha() != null ? job.baseSha() : startSha;
                // The reviewer gets told why instead of a diff; a failure here never stops the delivery.
                try {
                    return delivery.diff(worktree, since);
                } catch (RuntimeException e) {
                    return "(the diff could not be read: " + e.getMessage() + ")";
                }
            }

            @Override
            public void testStarted(ProcessHandle process) {
                // The pid Recovery kills after a crash is the test tree's while it runs, as for each agent call.
                events.agentStarted(process.pid(), process.info().startInstant().orElse(null));
            }
        };
    }

    /** The implementation's result with the loop's cost, turns and denials added in; the rest is the implementation's. */
    private static AgentResult combined(AgentResult implemented, List<AgentResult> loop) {
        BigDecimal cost = implemented.costUsd();
        Integer turns = implemented.turns();
        List<String> denials = new ArrayList<>(implemented.denials() == null ? List.of() : implemented.denials());
        for (AgentResult run : loop) {
            if (run.costUsd() != null) {
                cost = cost == null ? run.costUsd() : cost.add(run.costUsd());
            }
            if (run.turns() != null) {
                turns = turns == null ? run.turns() : turns + run.turns();
            }
            if (run.denials() != null) {
                denials.addAll(run.denials());
            }
        }
        return new AgentResult(implemented.outcome(), implemented.exitCode(), implemented.sessionId(),
                implemented.structuredOutput(), implemented.summary(), cost, turns, denials, implemented.error(),
                implemented.model(), implemented.requestedModel(), implemented.sandbox());
    }

    private static BigDecimal cost(AgentResult result) {
        return result.costUsd() == null ? BigDecimal.ZERO : result.costUsd();
    }

    /** @param verification what the verify loop found, written under the summary; null when the loop did not run */
    private JobResult deliver(Job job, Path worktree, String startSha, AgentResult result, Verification verification) {
        String summary = verification == null ? result.summary()
                : (result.summary() == null ? "" : result.summary()) + "\n\n" + verification.block();
        // The failed run's summary is what a DELIVER retry commits, so it carries the block the commit would have had.
        AgentResult kept = withSummary(result, summary);
        String offBranch = offBranch(job, worktree, startSha);
        if (offBranch != null) {
            return guarded(job, JobResult.failed(FailureReason.DELIVERY, offBranch, kept), job.expectedHead());
        }
        Delivery.Result delivered;
        try {
            delivered = delivery.deliver(worktree, job.taskId(), job.branchName(), job.baseBranch(), startSha,
                    commit(job, summary), job.prUrl());
        } catch (WorkspaceException e) {
            return guarded(job, JobResult.failed(FailureReason.DELIVERY, e.getMessage(), kept), headAfter(e, startSha));
        }
        return guarded(job, JobResult.delivered(result, delivered.files(), delivered.prUrl(), verification),
                delivered.commitSha() != null ? delivered.commitSha() : startSha);
    }

    /**
     * Null when the task's branch, and the worktree's HEAD on it, are at {@code expected}; otherwise why the run must not
     * build on it. Another task's agent can move any branch of the clone they share (ADR 0032); checked before the agent
     * starts, it anchors the commit the delivery folds back to.
     *
     * @param expected null checks nothing: a job from before the guard
     */
    private String branchMoved(Job job, Path worktree, String expected) {
        if (expected == null) {
            return null;
        }
        String branch = job.branchName();
        String found;
        try {
            String branchHead = delivery.branchHead(worktree, branch);
            found = branchHead.equals(expected) ? delivery.head(worktree) : branchHead;
        } catch (WorkspaceException e) {
            found = "(unreadable: " + e.getMessage() + ")";
        }
        if (found.equals(expected)) {
            return null;
        }
        Log.warn("task.branch_moved", "task", job.taskId(), "run", job.seq(), "branch", branch, "expected", expected,
                "found", found);
        return "branch " + branch + " moved to " + found + " but Dispatch left it at " + expected
                + "; another run may have moved it, so nothing was built on it or pushed";
    }

    /**
     * Null when the worktree is still on the task's branch, which is all delivery needs: a branch moved during the run, by
     * another task's agent or by this one's committing, is folded back to {@code start}, since the move never touched this
     * worktree's index or files, so the delivery commit sits on {@code start} and holds only this run's change. A HEAD
     * detached or pointed at another branch would commit where the push never looks, so that is refused.
     *
     * @param start where the delivery resets the branch to; only logged
     */
    private String offBranch(Job job, Path worktree, String start) {
        if (job.expectedHead() == null) {
            return null;
        }
        String branch = job.branchName();
        String checkedOut;
        String branchHead;
        try {
            checkedOut = delivery.checkedOutRef(worktree);
            branchHead = delivery.branchHead(worktree, branch);
        } catch (WorkspaceException e) {
            return "cannot read branch " + branch + ": " + e.getMessage() + "; nothing was pushed";
        }
        if (!("refs/heads/" + branch).equals(checkedOut)) {
            Log.warn("task.branch_moved", "task", job.taskId(), "run", job.seq(), "branch", branch, "head", checkedOut);
            return "the worktree left branch " + branch + " (HEAD is " + (checkedOut == null ? "detached" : checkedOut)
                    + "), so nothing was pushed";
        }
        if (!branchHead.equals(start)) {
            Log.warn("task.branch_moved", "task", job.taskId(), "run", job.seq(), "branch", branch, "expected", start,
                    "found", branchHead);
        }
        return null;
    }

    /** The branch's head once a delivery failed: Dispatch's own commit when it got that far, else where it started. */
    private static String headAfter(WorkspaceException e, String start) {
        return e instanceof Delivery.CommittedException committed ? committed.commitSha() : start;
    }

    /** {@code result} with the branch's head, only for a job that carried an expected head: an older team machine rejects the field. */
    private static JobResult guarded(Job job, JobResult result, String head) {
        return job.expectedHead() == null ? result : result.withHead(head);
    }

    private static AgentResult withSummary(AgentResult result, String summary) {
        return new AgentResult(result.outcome(), result.exitCode(), result.sessionId(), result.structuredOutput(), summary,
                result.costUsd(), result.turns(), result.denials(), result.error(), result.model(), result.requestedModel(),
                result.sandbox());
    }

    /** Delivers a failed delivery's work again, without the agent: the commit body is that run's summary. */
    private JobResult deliverAgain(Job job, ActiveRuns.ActiveRun control) {
        if (control.stopReason() != null) {
            return stopped(job, control.stopReason(), null);
        }
        Path worktree;
        try {
            worktree = existingWorktree(job);
        } catch (WorkspaceException e) {
            return JobResult.failed(FailureReason.SETUP, e.getMessage(), null);
        }
        // redeliver resets to what origin has, or to the base: a moved branch is recovered from there.
        String offBranch = offBranch(job, worktree, job.expectedHead());
        if (offBranch != null) {
            return guarded(job, JobResult.failed(FailureReason.DELIVERY, offBranch, null), job.expectedHead());
        }
        Delivery.Result delivered;
        try {
            delivered = delivery.redeliver(worktree, job.taskId(), job.branchName(), job.baseBranch(), job.baseSha(),
                    commit(job, job.deliverySummary()), job.prUrl());
        } catch (WorkspaceException e) {
            return guarded(job, JobResult.failed(FailureReason.DELIVERY, e.getMessage(), null), headAfter(e, job.expectedHead()));
        }
        // Nothing to deliver at all leaves the branch where it started.
        return guarded(job, JobResult.delivered(null, delivered.files(), delivered.prUrl()),
                delivered.commitSha() != null ? delivered.commitSha() : job.baseSha());
    }

    /** The task's delivery commit: the job's subject and trailers, and the summary with secrets masked as its body. */
    private Delivery.Commit commit(Job job, String summary) {
        String body = summary == null ? "" : redactor.redact(summary).strip();
        return new Delivery.Commit(job.commitSubject(), body, job.commitTrailers());
    }

    private RunRequest request(Job job, Path worktree, TaskFiles files) {
        return new RunRequest(job.kind(), worktree, job.prompt() + files.note(), job.sessionId(), job.resume(), files.dirs(),
                job.budgetUsd(), job.model(), job.effort(), workspaces.runLogBase(job.taskId(), job.seq()));
    }

    /** No copyFiles here: planning needs no local secrets, and whatever the agent reads may be quoted in the group. */
    private Path createWorktree(Job job, JobEvents events) {
        Workspaces.PreparedWorktree worktree = workspaces.createWorktree(config(job.project()), job.taskId(), job.branchName());
        events.worktreeCreated(worktree.path().toString(), worktree.baseSha());
        return worktree.path();
    }

    /** Later runs continue in the worktree the first planning run created; one the idle sweep removed is added back. */
    private Path existingWorktree(Job job) {
        if (job.worktree() == null) {
            throw new WorkspaceException("the task has no worktree");
        }
        Path worktree = Path.of(job.worktree());
        if (Files.isDirectory(worktree)) {
            return worktree;
        }
        try {
            Path recreated = workspaces.recreateWorktree(config(job.project()), job.taskId(), job.branchName());
            Log.info("worktree.recreated", "task", job.taskId(), "worktree", recreated);
            return recreated;
        } catch (WorkspaceException e) {
            throw new WorkspaceException("worktree " + worktree + " is missing and could not be recreated: " + e.getMessage(), e);
        }
    }

    /** A task's downloaded files: the directories the agent may read, and what its prompt says about them. */
    private record TaskFiles(List<Path> dirs, String note) {

        static final TaskFiles NONE = new TaskFiles(List.of(), "");
    }

    /**
     * Downloads the job's files that are not there yet. Each file lands under a temporary name first, so one cut short is
     * fetched again by the next run.
     */
    private TaskFiles attachments(Job job) {
        List<Attachment> files = job.attachments();
        if (files.isEmpty()) {
            return TaskFiles.NONE;
        }
        Path dir = workspaces.attachmentsDir(job.taskId());
        for (Attachment file : files) {
            Path target = dir.resolve(file.name());
            if (file.tooLarge() || Files.exists(target)) {
                continue;
            }
            try {
                OwnerOnly.createDirectories(dir);
                Path partial = dir.resolve(file.name() + ".part");
                attachmentSource.download(file.fileRef(), partial);
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                throw new WorkspaceException("cannot download " + file.name() + ": " + e.getMessage(), e);
            }
        }
        return new TaskFiles(List.of(dir), Prompts.attachments(dir, files));
    }

    /** Starts the agent, waits for it under {@code timeout}, and turns how it ended into the job's result. */
    private JobResult runAgent(Job job, JobEvents events, ActiveRuns.ActiveRun control, RunRequest request, Duration timeout) {
        Ran ran = startAndAwait(job, events, control, request, timeout, true);
        if (ran.result() == null) {
            return JobResult.failed(ran.failure(), ran.detail(), null);
        }
        AgentResult result = ran.result();
        ActiveRuns.StopReason stopReason = control.stopReason();
        if (stopReason != null) {
            return stopped(job, stopReason, result);
        }
        return switch (result.outcome()) {
            case SUCCEEDED -> JobResult.succeeded(result);
            case BUDGET_EXCEEDED -> JobResult.failed(FailureReason.BUDGET, result.error(), result);
            case FAILED -> JobResult.failed(FailureReason.AGENT, result.error(), result);
        };
    }

    /**
     * One of the verify loop's agent calls: a run that never reported becomes a FAILED result saying why. Its timeout, less
     * {@link #deliveryReserve}, ends only this call, never the run, so the run still delivers.
     */
    private AgentResult call(Job job, JobEvents events, ActiveRuns.ActiveRun control, RunRequest request, Duration timeout) {
        Duration limit = timeout.minus(deliveryReserve);
        Ran ran = startAndAwait(job, events, control, request, limit.isNegative() ? Duration.ZERO : limit, false);
        if (ran.result() != null) {
            return ran.result();
        }
        return new AgentResult(AgentOutcome.FAILED, -1, null, null, null, null, null, List.of(), ran.detail(), null, null);
    }

    /** How an agent call ended: its result, or why there is none. */
    private record Ran(AgentResult result, FailureReason failure, String detail) {
    }

    /**
     * Starts the agent and waits for it. Once {@code timeout} has passed a watchdog stops the whole run as TIMEOUT, or, when
     * {@code stopsTheRun} is false, cancels only this agent, whose call then fails as timed out.
     */
    private Ran startAndAwait(Job job, JobEvents events, ActiveRuns.ActiveRun control, RunRequest request, Duration timeout,
                              boolean stopsTheRun) {
        String type = job.project().agent();
        Agent agent = agents.get(type);
        if (agent == null) {
            // A member's computer need not have every agent the team's projects use (ADR 0026).
            return new Ran(null, FailureReason.AGENT, "the project runs on " + type + ", which is not configured on this "
                    + "computer; add it under agents (or " + type.replace("-code", "") + "Command in worker.yaml) and restart");
        }
        RunHandle handle;
        try {
            handle = agent.start(request);
        } catch (AgentStartException e) {
            return new Ran(null, FailureReason.AGENT, e.getMessage());
        }
        events.agentStarted(handle.process().pid(), handle.processStart());
        control.attach(handle);

        java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
        Thread watchdog = Thread.ofVirtual().name("run-timeout-" + job.taskId() + "." + job.seq()).start(() -> {
            try {
                Thread.sleep(timeout);
                expired.set(true);
                if (stopsTheRun) {
                    control.stop(ActiveRuns.StopReason.TIMEOUT);
                } else {
                    handle.cancel();
                }
            } catch (InterruptedException e) {
                // The run ended before the timeout; nothing to stop.
            }
        });
        try {
            AgentResult result = handle.await();
            if (!stopsTheRun && expired.get()) {
                return new Ran(null, FailureReason.TIMEOUT, "timed out after " + format(timeout));
            }
            return new Ran(result, null, null);
        } catch (InterruptedException e) {
            handle.cancel();
            Thread.currentThread().interrupt();
            return new Ran(null, FailureReason.INTERRUPTED, "run thread was interrupted");
        } finally {
            watchdog.interrupt();
        }
    }

    /** @param result null when the run was stopped before its agent reported */
    private static JobResult stopped(Job job, ActiveRuns.StopReason reason, AgentResult result) {
        return switch (reason) {
            case CANCELLED -> JobResult.cancelled(result);
            case TIMEOUT -> JobResult.failed(FailureReason.TIMEOUT,
                    "stopped after " + format(Duration.ofMillis(job.timeoutMillis())), result);
            case INTERRUPTED -> JobResult.failed(FailureReason.INTERRUPTED, "Dispatch stopped while the run was active", result);
        };
    }

    /** The job's project as the workspace helpers take it; only the fields they read are filled. */
    private static Config.Project config(Job.Project project) {
        return new Config.Project(project.name(), null, project.repo(), project.path(), project.baseBranch(), project.agent(),
                null, null, project.copyFiles(), null, null, null);
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
