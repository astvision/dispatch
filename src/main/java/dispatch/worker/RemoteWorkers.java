package dispatch.worker;

import dispatch.Log;
import dispatch.agent.AgentActivity;
import dispatch.core.ActiveRuns;
import dispatch.core.Job;
import dispatch.core.JobEvents;
import dispatch.core.JobResult;
import dispatch.core.Worker;
import dispatch.domain.FailureReason;
import dispatch.store.Database;
import dispatch.store.Tasks;
import dispatch.store.Workers;
import dispatch.ui.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The {@link Worker} of a team: a job is offered to the requester's own computers and the calling thread waits for the
 * one that takes it. A worker holds the job under a lease it renews with progress every 10 s; when the lease expires the
 * run comes back as {@code INTERRUPTED}, exactly as a restart mid-run does today, and whatever that worker reports
 * afterwards is refused with 409 — it keeps its worktree, so /retry delivers it again.
 *
 * <p>Nothing here writes the store's run state: the Coordinator still makes the one transition per run.
 */
public final class RemoteWorkers implements Worker {

    /** Without progress for this long, the worker is gone. */
    public static final Duration LEASE = Duration.ofSeconds(60);
    /** How long /api/worker/next waits before answering "nothing". */
    public static final Duration LONG_POLL = Duration.ofSeconds(25);
    /** How often a waiting thread looks up from its future to check the clock. */
    private static final Duration TICK = Duration.ofMillis(200);

    private final Database db;
    private final Clock clock;
    private final Runnable wakeScheduler;
    private final Object lock = new Object();
    private final List<Offer> offers = new ArrayList<>();

    /** @param wakeScheduler called when a worker asks for work, so a run queued while it was away starts at once */
    public RemoteWorkers(Database db, Clock clock, Runnable wakeScheduler) {
        this.db = db;
        this.clock = clock;
        this.wakeScheduler = wakeScheduler;
    }

    /** One job waiting for, or running on, a member's computer. */
    private static final class Offer {

        private final Job job;
        private final JobEvents events;
        private final ActiveRuns.ActiveRun control;
        private final String memberRef;
        private final Long onlyWorker;
        private final Instant offeredAt;
        private final CompletableFuture<JobResult> answer = new CompletableFuture<>();
        private Long takenBy;
        private Instant leaseUntil;
        /** The lease ran out: this job is over and nothing this worker sends about it counts any more. */
        private boolean expired;
        private boolean worktreeRecorded;
        private boolean agentRecorded;

        private Offer(Job job, JobEvents events, ActiveRuns.ActiveRun control, String memberRef, Long onlyWorker,
                      Instant offeredAt) {
            this.job = job;
            this.events = events;
            this.control = control;
            this.memberRef = memberRef;
            this.onlyWorker = onlyWorker;
            this.offeredAt = offeredAt;
        }
    }

    @Override
    public JobResult run(Job job, JobEvents events, ActiveRuns.ActiveRun control) {
        Offer offer = offer(job, events, control);
        try {
            while (true) {
                try {
                    return offer.answer.get(TICK.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    JobResult givenUp = givenUp(offer);
                    if (givenUp != null) {
                        return givenUp;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JobResult.failed(FailureReason.INTERRUPTED, "Dispatch stopped while the run was active", null);
        } catch (ExecutionException e) {
            throw new IllegalStateException("a job's answer is never completed with an error", e);
        } finally {
            synchronized (lock) {
                offers.remove(offer);
                lock.notifyAll();
            }
        }
    }

    private Offer offer(Job job, JobEvents events, ActiveRuns.ActiveRun control) {
        String memberRef = db.transactionReturning(tx -> Tasks.find(tx, job.taskId()))
                .orElseThrow(() -> new IllegalStateException("job for missing task " + job.taskId()))
                .requester().ref();
        Long onlyWorker = db.transactionReturning(tx -> Tasks.workerOf(tx, job.taskId())).orElse(null);
        Offer offer = new Offer(job, events, control, memberRef, onlyWorker, clock.instant());
        synchronized (lock) {
            offers.add(offer);
            lock.notifyAll();
        }
        Log.info("worker.job_offered", "task", job.taskId(), "run", job.seq(), "member", memberRef, "worker", onlyWorker);
        return offer;
    }

    /** Why this job is over although no worker reported; null while it may still run. */
    private JobResult givenUp(Offer offer) {
        Instant now = clock.instant();
        synchronized (lock) {
            if (offer.control.stopReason() == ActiveRuns.StopReason.INTERRUPTED) {
                return JobResult.failed(FailureReason.INTERRUPTED, "Dispatch stopped while the run was active", null);
            }
            if (offer.takenBy == null) {
                if (now.isBefore(offer.offeredAt.plus(LEASE))) {
                    return null;
                }
                Log.warn("worker.offer_expired", "task", offer.job.taskId(), "run", offer.job.seq(), "member", offer.memberRef);
                return JobResult.failed(FailureReason.INTERRUPTED, "no computer took this run", null);
            }
            if (now.isBefore(offer.leaseUntil)) {
                return null;
            }
            // Drop the lease first: the worker's late result must find nothing and get 409.
            Log.warn("worker.lease_expired", "task", offer.job.taskId(), "run", offer.job.seq(), "worker", offer.takenBy);
            offer.leaseUntil = now;
            offer.takenBy = null;
            offer.expired = true;
            return JobResult.failed(FailureReason.INTERRUPTED,
                    "your computer stopped reporting for " + LEASE.toSeconds() + "s", null);
        }
    }

    /** Whether any job is waiting or running; the team machine's own tests wait for this. */
    public boolean hasOffers() {
        synchronized (lock) {
            return !offers.isEmpty();
        }
    }

    /**
     * The next job for {@code worker}, waiting up to {@link #LONG_POLL}. A job is this worker's when its requester is the
     * worker's member and either the task has no computer yet or this is that computer.
     *
     * <p>The wait is bounded by the wall clock, not the injected {@link Clock}: it is how long this HTTP request may
     * actually block, not a business fact a test needs to move by hand the way it moves the lease.
     */
    public Optional<Job> next(Workers.Paired worker) throws InterruptedException {
        wakeScheduler.run();
        long deadlineNanos = System.nanoTime() + LONG_POLL.toNanos();
        synchronized (lock) {
            while (true) {
                Optional<Offer> match = offers.stream().filter(offer -> matches(offer, worker)).findFirst();
                if (match.isPresent()) {
                    return Optional.of(take(match.get(), worker));
                }
                long leftMillis = (deadlineNanos - System.nanoTime()) / 1_000_000;
                if (leftMillis <= 0) {
                    return Optional.empty();
                }
                lock.wait(Math.min(leftMillis, TICK.toMillis()));
            }
        }
    }

    private static boolean matches(Offer offer, Workers.Paired worker) {
        return offer.takenBy == null && !offer.expired && offer.memberRef.equals(worker.memberRef())
                && (offer.onlyWorker == null || offer.onlyWorker == worker.id());
    }

    private Job take(Offer offer, Workers.Paired worker) {
        Instant now = clock.instant();
        offer.takenBy = worker.id();
        offer.leaseUntil = now.plus(LEASE);
        // Recorded at once: from here the task's worktree and session live on this computer and nowhere else.
        db.transaction(tx -> Tasks.recordWorker(tx, offer.job.taskId(), worker.id(), now));
        Log.info("worker.job_taken", "task", offer.job.taskId(), "run", offer.job.seq(), "worker", worker.id(),
                "name", worker.name());
        return offer.job;
    }

    /** What the worker is doing; renews the lease and answers whether the member cancelled the task. */
    public boolean progress(Workers.Paired worker, Progress progress) {
        Offer offer = held(worker, progress.taskId(), progress.seq());
        synchronized (lock) {
            offer.leaseUntil = clock.instant().plus(LEASE);
        }
        if (progress.worktree() != null && !offer.worktreeRecorded) {
            offer.events.worktreeCreated(progress.worktree(), progress.baseSha());
            offer.worktreeRecorded = true;
        }
        if (progress.agentStarted() && !offer.agentRecorded) {
            // No pid: that process runs on the member's computer and this machine kills only its own orphans.
            offer.events.agentStarted(null, null);
            offer.agentRecorded = true;
        }
        if (progress.steps() != null) {
            offer.control.reportActivity(new AgentActivity(progress.steps(), progress.lastAction()));
        }
        return offer.control.stopReason() == ActiveRuns.StopReason.CANCELLED;
    }

    /**
     * How the job ended. The Coordinator's thread wakes with this and makes the run's one transition.
     *
     * @param progress what a worker posts every 10 s and whenever the store must learn something at once; every field
     *                 but the run itself may be absent
     */
    public record Progress(long taskId, int seq, String worktree, String baseSha, boolean agentStarted, Integer steps,
                           String lastAction) {
    }

    public void result(Workers.Paired worker, long taskId, int seq, JobResult result) {
        Offer offer = held(worker, taskId, seq);
        synchronized (lock) {
            offer.takenBy = null;
            offer.leaseUntil = clock.instant();
        }
        offer.answer.complete(result);
        Log.info("worker.job_reported", "task", taskId, "run", seq, "worker", worker.id(), "outcome", result.outcome());
    }

    /** The job {@code worker} holds the lease for, so it can fetch that task's files. */
    public Job leased(Workers.Paired worker, long taskId) {
        return held(worker, taskId, -1).job;
    }

    /** @param seq -1 when the caller does not name a run */
    private Offer held(Workers.Paired worker, long taskId, int seq) {
        synchronized (lock) {
            Optional<Offer> found = offers.stream().filter(offer -> offer.job.taskId() == taskId).findFirst();
            if (found.isEmpty() || found.get().takenBy == null) {
                throw new ApiException(409, "lease_expired",
                        "this run's lease has expired; keep its worktree, the member can retry it");
            }
            Offer offer = found.get();
            if (offer.takenBy != worker.id()) {
                throw new ApiException(403, "not_your_run", "this run belongs to another computer");
            }
            if (seq >= 0 && offer.job.seq() != seq) {
                throw new ApiException(409, "lease_expired", "this computer holds run " + offer.job.seq() + " of task "
                        + taskId + ", not " + seq);
            }
            if (clock.instant().isAfter(offer.leaseUntil)) {
                throw new ApiException(409, "lease_expired",
                        "this run's lease has expired; keep its worktree, the member can retry it");
            }
            return offer;
        }
    }
}
