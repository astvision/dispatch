package dispatch.worker;

import dispatch.Log;
import dispatch.Redactor;
import dispatch.agent.Agent;
import dispatch.core.ActiveRuns;
import dispatch.core.Job;
import dispatch.core.JobEvents;
import dispatch.core.JobResult;
import dispatch.core.JobRunner;
import dispatch.domain.FailureReason;
import dispatch.workspace.Delivery;
import dispatch.workspace.Workspaces;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code dispatch worker run}: asks the team machine for this member's next job, runs it with the same {@link JobRunner}
 * a personal Dispatch uses — the member's own clone, Claude Code login and {@code gh} — and reports back. Progress every
 * 10 s renews the run's lease and brings back the member's cancel.
 */
public final class WorkerLoop implements Runnable {

    /** The spec's progress interval: also how soon a cancel reaches the agent. */
    public static final Duration PROGRESS = Duration.ofSeconds(10);
    private static final Duration RETRY_AFTER_ERROR = Duration.ofSeconds(5);
    /**
     * Backoff before each retry after {@link #report}'s first attempt fails for a reason that might clear up on its own
     * (the team machine unreachable, a timeout, a 429): up to 3 retries, so a computed result is not thrown away on one
     * transient failure. Not used for a 409 (the lease is already gone; a retry cannot change that) or a revoked key
     * (no retry will ever succeed).
     */
    private static final List<Duration> RESULT_RETRY_BACKOFF =
            List.of(Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10));
    /**
     * How long {@link #stop} waits for an in-flight run to report before returning, as {@code App.stop} does (ADR 0008).
     * Above {@link #report}'s own worst case: up to 4 attempts (the first plus {@link #RESULT_RETRY_BACKOFF}'s 3
     * retries) at {@code WorkerClient}'s 30 s call timeout each, plus the 17 s of backoff between them — 137 s — with
     * margin.
     */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(150);

    private final WorkerConfig config;
    private final WorkerClient client;
    private final Map<String, Agent> agentsByType;
    private final Workspaces workspaces;
    private final Delivery delivery;
    private final Redactor redactor;
    private final ActiveRuns activeRuns;
    private final LocalAgents agents;
    private final Duration progressInterval;
    private final AtomicInteger running = new AtomicInteger();
    private volatile boolean stopped;

    public WorkerLoop(WorkerConfig config, WorkerClient client, Map<String, Agent> agentsByType, Workspaces workspaces,
                      Delivery delivery, Redactor redactor, ActiveRuns activeRuns) {
        this(config, client, agentsByType, workspaces, delivery, redactor, activeRuns, PROGRESS);
    }

    /** @param progressInterval overrides {@link #PROGRESS}; a real wall-clock duration, so a test need not pay a real 10 s wait per tick. */
    WorkerLoop(WorkerConfig config, WorkerClient client, Map<String, Agent> agentsByType, Workspaces workspaces,
              Delivery delivery, Redactor redactor, ActiveRuns activeRuns, Duration progressInterval) {
        this.config = config;
        this.client = client;
        this.agentsByType = Map.copyOf(agentsByType);
        this.workspaces = workspaces;
        this.delivery = delivery;
        this.redactor = redactor;
        this.activeRuns = activeRuns;
        this.agents = new LocalAgents(config.stateDir());
        this.progressInterval = progressInterval;
    }

    /** One runner per job, so its attachment source knows which task's files it may fetch. */
    private JobRunner runnerFor(Job job) {
        return new JobRunner(workspaces, delivery, agentsByType, redactor,
                (fileRef, target) -> client.attachment(job.taskId(), fileRef, target));
    }

    @Override
    public void run() {
        agents.killOrphans(Duration.ofSeconds(10));
        Log.info("worker.started", "team", config.team(), "name", config.name(),
                "max_concurrent_runs", config.maxConcurrentRuns());
        while (!stopped) {
            if (running.get() >= config.maxConcurrentRuns()) {
                sleep(Duration.ofMillis(200));
                continue;
            }
            try {
                Optional<Job> job = client.next();
                job.ifPresent(this::start);
            } catch (WorkerClient.RevokedException e) {
                Log.error("worker.key_revoked", null, "detail", e.getMessage());
                // The key is dead: nothing this run does can ever be reported back, so stop it now rather than let
                // it burn the member's Claude usage to completion for an outcome nobody will learn.
                stop();
            } catch (RuntimeException e) {
                Log.warn("worker.poll_failed", "error", e.getMessage());
                sleep(RETRY_AFTER_ERROR);
            }
        }
        Log.info("worker.stopped");
    }

    /**
     * Cancels every run this computer holds and waits for each to report before returning, so a member's Ctrl+C (or the
     * shutdown hook it triggers) does not tear the process down mid network call and drop a result the run already
     * computed. A run whose report genuinely cannot reach the team machine (no network) still returns once its own
     * {@link WorkerClient} call times out; that run's lease then expires on the team side and it is retried, worktree
     * intact — the same outcome as any other crash (ADR 0008).
     */
    public void stop() {
        stopped = true;
        activeRuns.stopAll(ActiveRuns.StopReason.INTERRUPTED);
        try {
            if (!activeRuns.awaitIdle(STOP_TIMEOUT)) {
                Log.warn("worker.runs_still_active", "waited_seconds", STOP_TIMEOUT.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void start(Job job) {
        running.incrementAndGet();
        Thread.ofVirtual().name("worker-run-" + job.taskId() + "." + job.seq()).start(() -> {
            try {
                carry(job);
            } finally {
                running.decrementAndGet();
            }
        });
    }

    private void carry(Job job) {
        ActiveRuns.ActiveRun control = activeRuns.register(job.taskId(), job.seq());
        Thread ticker = null;
        try {
            Optional<Job> local = withLocalClone(job);
            if (local.isEmpty()) {
                report(job, JobResult.failed(FailureReason.SETUP,
                        "project " + job.project().name() + " is not set up on your computer: run dispatch worker init", null));
                return;
            }
            ticker = Thread.ofVirtual().name("worker-progress-" + job.taskId()).start(() -> tick(job, control));
            report(job, runnerFor(job).run(local.get(), events(job), control));
        } catch (RuntimeException e) {
            Log.error("worker.run_failed", e, "task", job.taskId(), "run", job.seq());
            report(job, JobResult.failed(FailureReason.INTERNAL, "the worker broke: " + e.getMessage(), null));
        } finally {
            if (ticker != null) {
                ticker.interrupt();
            }
            agents.forget(job.taskId(), job.seq());
            activeRuns.unregister(control);
        }
    }

    /** The team's project as this computer has it: its own clone, and its own model and effort when it set any. */
    private Optional<Job> withLocalClone(Job job) {
        WorkerConfig.Project mine = config.projects().get(job.project().name());
        if (mine == null) {
            return Optional.empty();
        }
        Job.Project project = new Job.Project(job.project().name(), job.project().repo(), mine.path(),
                job.project().baseBranch(), job.project().agent(), job.project().copyFiles());
        return Optional.of(new Job(job.taskId(), job.seq(), job.kind(), project, job.baseBranch(), job.baseSha(),
                job.worktree(), job.prUrl(), job.sessionId(), job.resume(), job.prompt(),
                mine.model() == null ? job.model() : mine.model(), mine.effort() == null ? job.effort() : mine.effort(),
                job.timeoutMillis(), job.budgetUsd(), job.attachments(), job.commitSubject(), job.commitTrailers(),
                job.deliverySummary()));
    }

    /**
     * The two store writes the team machine cannot wait for, sent the moment they happen — best-effort, like the 10 s
     * ticker: {@link JobRunner} calls these from inside {@code runAgent}, before {@code control.attach(handle)} runs, so
     * an exception here would leave this run's handle never attached and its process reachable by neither a cancel nor
     * {@link ActiveRuns#stopAll}, while {@link #carry}'s {@code finally} still calls {@link LocalAgents#forget} — orphaning
     * the agent from both this run's cancel path and the next start's {@link LocalAgents#killOrphans}.
     */
    private JobEvents events(Job job) {
        return new JobEvents() {

            @Override
            public void worktreeCreated(String worktree, String baseSha) {
                try {
                    client.progress(new RemoteWorkers.Progress(job.taskId(), job.seq(), worktree, baseSha, false, null, null));
                } catch (RuntimeException e) {
                    Log.warn("worker.progress_failed", "task", job.taskId(), "error", e.getMessage());
                }
            }

            @Override
            public void agentStarted(Long pid, Instant processStart) {
                if (pid != null) {
                    agents.record(job.taskId(), job.seq(), pid, processStart);
                }
                try {
                    client.progress(new RemoteWorkers.Progress(job.taskId(), job.seq(), null, null, true, null, null));
                } catch (RuntimeException e) {
                    Log.warn("worker.progress_failed", "task", job.taskId(), "error", e.getMessage());
                }
            }
        };
    }

    /** Every {@link #progressInterval}: what the agent is doing, and whatever the team machine answers about a cancel. */
    private void tick(Job job, ActiveRuns.ActiveRun control) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(progressInterval);
                var activity = activeRuns.activity(job.taskId());
                boolean cancel = client.progress(new RemoteWorkers.Progress(job.taskId(), job.seq(), null, null, false,
                        activity.map(a -> a.steps()).orElse(null), activity.map(a -> a.lastAction()).orElse(null)));
                if (cancel) {
                    control.stop(ActiveRuns.StopReason.CANCELLED);
                    return;
                }
            } catch (InterruptedException e) {
                return;
            } catch (WorkerClient.LeaseExpiredException e) {
                // The team machine gave up on this run; stopping the agent frees the computer, the worktree stays.
                Log.warn("worker.lease_lost", "task", job.taskId(), "run", job.seq(), "detail", e.getMessage());
                control.stop(ActiveRuns.StopReason.INTERRUPTED);
                return;
            } catch (RuntimeException e) {
                Log.warn("worker.progress_failed", "task", job.taskId(), "error", e.getMessage());
            }
        }
    }

    /**
     * Sends the job's result, retrying a transient failure up to {@link #RESULT_RETRY_BACKOFF}'s length more times so
     * one network blip does not throw away a run that already succeeded (possibly already delivered). A 409 and a
     * revoked key are not retried: neither can ever succeed, and both already have a real cause logged elsewhere.
     */
    private void report(Job job, JobResult result) {
        for (int attempt = 0; ; attempt++) {
            try {
                client.result(job.taskId(), job.seq(), result);
                return;
            } catch (WorkerClient.LeaseExpiredException e) {
                Log.warn("worker.result_refused", "task", job.taskId(), "run", job.seq(), "detail", e.getMessage());
                return;
            } catch (WorkerClient.RevokedException e) {
                Log.error("worker.result_not_sent", e, "task", job.taskId(), "run", job.seq());
                return;
            } catch (RuntimeException e) {
                if (attempt >= RESULT_RETRY_BACKOFF.size()) {
                    Log.error("worker.result_not_sent", e, "task", job.taskId(), "run", job.seq());
                    return;
                }
                Log.warn("worker.result_retry", "task", job.taskId(), "run", job.seq(), "attempt", attempt + 1,
                        "error", e.getMessage());
                sleep(RESULT_RETRY_BACKOFF.get(attempt));
            }
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
