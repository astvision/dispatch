package dispatch.core;

import dispatch.Log;
import dispatch.OwnerOnly;
import dispatch.Redactor;
import dispatch.agent.Agent;
import dispatch.agent.AgentResult;
import dispatch.agent.AgentStartException;
import dispatch.agent.RunHandle;
import dispatch.agent.RunRequest;
import dispatch.config.Config;
import dispatch.domain.Attachment;
import dispatch.domain.FailureReason;
import dispatch.workspace.Delivery;
import dispatch.workspace.WorkspaceException;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The machine work of one run, in this process: the worktree, the task's files, the agent under its timeout, and its
 * delivery. It reads no store and no config — everything arrives in the {@link Job} and leaves in the {@link JobResult}
 * — so W-3 can put HTTP between it and the {@link Coordinator}.
 */
public final class JobRunner implements Worker {

    private final Workspaces workspaces;
    private final Delivery delivery;
    private final Map<String, Agent> agents;
    private final Redactor redactor;
    private final AttachmentSource attachmentSource;

    /**
     * @param redactor         masks secrets in the agent's summary before it becomes a commit message and pull request
     * @param attachmentSource downloads the files sent with a task
     */
    public JobRunner(Workspaces workspaces, Delivery delivery, Map<String, Agent> agents, Redactor redactor,
                     AttachmentSource attachmentSource) {
        this.workspaces = workspaces;
        this.delivery = delivery;
        this.agents = Map.copyOf(agents);
        this.redactor = redactor;
        this.attachmentSource = attachmentSource;
    }

    @Override
    public JobResult run(Job job, JobEvents events, ActiveRuns.ActiveRun control) {
        return switch (job.kind()) {
            case PLAN -> plan(job, events, control);
            case EXECUTE -> implement(job, events, control);
            case DELIVER -> deliverAgain(job, control);
            case SPLIT -> throw new IllegalStateException("a split is never a task's run");
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
        return runAgent(job, events, control, request(job, worktree, files));
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
        if (control.stopReason() != null) {
            return stopped(job, control.stopReason(), null);
        }
        JobResult result = runAgent(job, events, control, request(job, worktree, files));
        if (result.outcome() != JobResult.Outcome.SUCCEEDED) {
            return result;
        }
        return deliver(job, worktree, startSha, result.agent());
    }

    private JobResult deliver(Job job, Path worktree, String startSha, AgentResult result) {
        Delivery.Result delivered;
        try {
            delivered = delivery.deliver(worktree, job.taskId(), job.baseBranch(), startSha, commit(job, result.summary()),
                    job.prUrl());
        } catch (WorkspaceException e) {
            return JobResult.failed(FailureReason.DELIVERY, e.getMessage(), result);
        }
        return JobResult.delivered(result, delivered.files(), delivered.prUrl());
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
        Delivery.Result delivered;
        try {
            delivered = delivery.redeliver(worktree, job.taskId(), job.baseBranch(), job.baseSha(),
                    commit(job, job.deliverySummary()), job.prUrl());
        } catch (WorkspaceException e) {
            return JobResult.failed(FailureReason.DELIVERY, e.getMessage(), null);
        }
        return JobResult.delivered(null, delivered.files(), delivered.prUrl());
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
        Workspaces.PreparedWorktree worktree = workspaces.createWorktree(config(job.project()), job.taskId());
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
            Path recreated = workspaces.recreateWorktree(config(job.project()), job.taskId());
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

    /** Starts the agent, waits for it under the job's timeout, and turns how it ended into the job's result. */
    private JobResult runAgent(Job job, JobEvents events, ActiveRuns.ActiveRun control, RunRequest request) {
        RunHandle handle;
        try {
            handle = agents.get(job.project().agent()).start(request);
        } catch (AgentStartException e) {
            return JobResult.failed(FailureReason.AGENT, e.getMessage(), null);
        }
        events.agentStarted(handle.process().pid(), handle.processStart());
        control.attach(handle);

        Duration timeout = Duration.ofMillis(job.timeoutMillis());
        Thread watchdog = Thread.ofVirtual().name("run-timeout-" + job.taskId() + "." + job.seq()).start(() -> {
            try {
                Thread.sleep(timeout);
                control.stop(ActiveRuns.StopReason.TIMEOUT);
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
            return JobResult.failed(FailureReason.INTERRUPTED, "run thread was interrupted", null);
        } finally {
            watchdog.interrupt();
        }
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
