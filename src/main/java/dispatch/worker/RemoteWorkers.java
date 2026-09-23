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
 * <p>Nothing here writes the store's run state: the Coordinator still makes the one transition per run. An offer's
 * {@code answer} future is the single source of truth for how a job ended: every path that decides the job is over —
 * a worker's own {@link #result}, or this class giving up on its behalf in {@link #givenUp} — completes it exactly
 * once, under {@link #lock}, together with setting {@code expired}. Nothing may act on an offer's behalf once
 * {@code expired} is true.
 */
public final class RemoteWorkers implements Worker {

    /** Without progress for this long, the worker is gone. */
    public static final Duration LEASE = Duration.ofSeconds(60);
    /** How long /api/worker/next waits before answering "nothing"; the protocol default for Task 6's server. */
    public static final Duration LONG_POLL = Duration.ofSeconds(25);
    /** How often a waiting thread looks up from its future to check the clock; the production default. */
    private static final Duration TICK = Duration.ofMillis(200);

    private final Database db;
    private final Clock clock;
    private final Runnable wakeScheduler;
    private final Duration longPoll;
    private final Duration tick;
    private final Object lock = new Object();
    private final List<Offer> offers = new ArrayList<>();
    /** Set by {@link #stopPolling}: no more jobs go out and every parked poll answers "nothing" at once. */
    private boolean closed;

    /** @param wakeScheduler called when a worker asks for work, so a run queued while it was away starts at once */
    public RemoteWorkers(Database db, Clock clock, Runnable wakeScheduler) {
        this(db, clock, wakeScheduler, LONG_POLL, TICK);
    }

    /**
     * @param longPoll overrides {@link #LONG_POLL}; a real wall-clock bound, so tests don't pay a real 25 s wait.
     * @param tick     overrides the give-up/lease-watchdog poll interval, so a test racing it doesn't need up to 200 ms
     *                 of real time per attempt to land in the window it is testing.
     */
    RemoteWorkers(Database db, Clock clock, Runnable wakeScheduler, Duration longPoll, Duration tick) {
        this.db = db;
        this.clock = clock;
        this.wakeScheduler = wakeScheduler;
        this.longPoll = longPoll;
        this.tick = tick;
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
        /** The job is over, one way or another: nothing this worker sends about it counts any more. */
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
                    return offer.answer.get(tick.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    givenUp(offer);
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
        record TaskInfo(String memberRef, Long onlyWorker) {
        }
        TaskInfo info = db.transactionReturning(tx -> {
            String memberRef = Tasks.find(tx, job.taskId())
                    .orElseThrow(() -> new IllegalStateException("job for missing task " + job.taskId()))
                    .requester().ref();
            return new TaskInfo(memberRef, Tasks.workerOf(tx, job.taskId()).orElse(null));
        });
        Offer offer = new Offer(job, events, control, info.memberRef(), info.onlyWorker(), clock.instant());
        synchronized (lock) {
            offers.add(offer);
            lock.notifyAll();
        }
        Log.info("worker.job_offered", "task", job.taskId(), "run", job.seq(), "member", info.memberRef(), "worker",
                info.onlyWorker());
        return offer;
    }

    /** Ends this offer's wait when nobody will finish it: shutdown, unclaimed for {@link #LEASE}, or claimed but silent. */
    private void givenUp(Offer offer) {
        Instant now = clock.instant();
        synchronized (lock) {
            if (offer.expired) {
                // Already resolved — by a worker's own result(), or by an earlier call here. Nothing left to decide.
                return;
            }
            if (offer.control.stopReason() == ActiveRuns.StopReason.INTERRUPTED) {
                offer.expired = true;
                offer.answer.complete(JobResult.failed(FailureReason.INTERRUPTED, "Dispatch stopped while the run was active", null));
                return;
            }
            if (offer.takenBy == null) {
                if (now.isBefore(offer.offeredAt.plus(LEASE))) {
                    return;
                }
                Log.warn("worker.offer_expired", "task", offer.job.taskId(), "run", offer.job.seq(), "member", offer.memberRef);
                offer.expired = true;
                offer.answer.complete(JobResult.failed(FailureReason.INTERRUPTED, "no computer took this run", null));
                return;
            }
            if (now.isBefore(offer.leaseUntil)) {
                return;
            }
            Log.warn("worker.lease_expired", "task", offer.job.taskId(), "run", offer.job.seq(), "worker", offer.takenBy,
                    "member", offer.memberRef);
            offer.leaseUntil = now;
            offer.takenBy = null;
            offer.expired = true;
            offer.answer.complete(JobResult.failed(FailureReason.INTERRUPTED,
                    "your computer stopped reporting for " + LEASE.toSeconds() + "s", null));
        }
    }

    /** Whether any job is waiting or running; the team machine's own tests wait for this. */
    public boolean hasOffers() {
        synchronized (lock) {
            return !offers.isEmpty();
        }
    }

    /**
     * Ends every parked {@code /api/worker/next} now and refuses to hand out any more jobs. {@link WorkerApi#close}
     * calls this before it stops its server: a poll parked in {@link #awaitMatch} is waiting on this class's own lock,
     * so nothing else can end that wait cleanly, and without it the poll keeps its thread and socket for the rest of
     * its 25 s — well past the one second {@code HttpServer.stop} waits — and shutdown ends by tearing the request
     * down instead of answering it.
     */
    public void stopPolling() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    /**
     * The next job for {@code worker}, waiting up to this instance's long-poll bound. A job is this worker's when its
     * requester is the worker's member and either the task has no computer yet or this is that computer.
     */
    public Optional<Job> next(Workers.Paired worker) throws InterruptedException {
        wakeScheduler.run();
        Offer taken = awaitMatch(worker);
        return taken == null ? Optional.empty() : Optional.of(recordAndReturn(taken, worker));
    }

    /**
     * Waits under {@link #lock} for a matching offer and reserves it there, atomically with the match: two computers
     * polling at once can never both reserve the same offer.
     *
     * <p>The wait itself is bounded by the wall clock, not the injected {@link Clock}: it is how long this HTTP request
     * may actually block, not a business fact a test needs to move by hand the way it moves the lease.
     */
    private Offer awaitMatch(Workers.Paired worker) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + longPoll.toNanos();
        synchronized (lock) {
            while (true) {
                if (closed) {
                    return null;
                }
                Optional<Offer> match = offers.stream().filter(offer -> matches(offer, worker)).findFirst();
                if (match.isPresent()) {
                    Offer offer = match.get();
                    offer.takenBy = worker.id();
                    offer.leaseUntil = clock.instant().plus(LEASE);
                    return offer;
                }
                long leftMillis = (deadlineNanos - System.nanoTime()) / 1_000_000;
                if (leftMillis <= 0) {
                    return null;
                }
                // At least 1 ms: Object.wait(0) means "wait forever", and a sub-millisecond tick truncates to 0.
                lock.wait(Math.max(1, Math.min(leftMillis, tick.toMillis())));
            }
        }
    }

    private static boolean matches(Offer offer, Workers.Paired worker) {
        return offer.takenBy == null && !offer.expired && offer.memberRef.equals(worker.memberRef())
                && (offer.onlyWorker == null || offer.onlyWorker == worker.id());
    }

    /**
     * Records the task's affinity outside {@link #lock}, so one worker's SQLite write never blocks every other worker's
     * next/progress/result or the lease watchdog. A failed write releases the reservation {@link #awaitMatch} made, so
     * the offer is takeable again on the next poll instead of dying unmatchable.
     *
     * <p>Because this write is outside {@link #lock}, a shutdown-driven give-up can expire the offer while it is still
     * in flight: the worker that took it may still receive the {@link Job} for a run the coordinator already
     * concluded. That worker's first {@code progress}/{@code result} then finds {@link #held} refusing it with 409 —
     * it keeps the worktree it was given, so a retry delivers to it again.
     */
    private Job recordAndReturn(Offer offer, Workers.Paired worker) {
        try {
            db.transaction(tx -> Tasks.recordWorker(tx, offer.job.taskId(), worker.id(), clock.instant()));
        } catch (RuntimeException e) {
            synchronized (lock) {
                if (!offer.expired) {
                    offer.takenBy = null;
                    offer.leaseUntil = null;
                }
            }
            throw e;
        }
        Log.info("worker.job_taken", "task", offer.job.taskId(), "run", offer.job.seq(), "worker", worker.id(),
                "name", worker.name());
        return offer.job;
    }

    /** What the worker is doing; renews the lease and answers whether the member cancelled the task. */
    public boolean progress(Workers.Paired worker, Progress progress) {
        Offer offer;
        boolean newWorktree;
        boolean newAgent;
        boolean cancelled;
        synchronized (lock) {
            offer = held(worker, progress.taskId(), progress.seq());
            offer.leaseUntil = clock.instant().plus(LEASE);
            newWorktree = progress.worktree() != null && !offer.worktreeRecorded;
            if (newWorktree) {
                offer.worktreeRecorded = true;
            }
            newAgent = progress.agentStarted() && !offer.agentRecorded;
            if (newAgent) {
                offer.agentRecorded = true;
            }
            if (progress.steps() != null) {
                offer.control.reportActivity(new AgentActivity(progress.steps(), progress.lastAction()));
            }
            cancelled = offer.control.stopReason() == ActiveRuns.StopReason.CANCELLED;
        }
        // Reserved under the lock above so two overlapping posts can each fire at most once; written outside it so a
        // slow DB write never blocks every other worker's next/progress/result.
        if (newWorktree) {
            fireEffect(offer, () -> offer.events.worktreeCreated(progress.worktree(), progress.baseSha()));
        }
        if (newAgent) {
            // No pid: that process runs on the member's computer and this machine kills only its own orphans.
            fireEffect(offer, () -> offer.events.agentStarted(null, null));
        }
        return cancelled;
    }

    /**
     * Fires a store-writing effect for {@code offer}, refusing it with 409 when a give-up (shutdown or lease expiry)
     * has already closed the offer — or does so while the write is still in flight, since the write itself
     * deliberately runs outside {@link #lock} so it can never block every other worker's next/progress/result (see
     * {@link #recordAndReturn}). The check before the call skips an effect already known to be doomed; the one after
     * catches a give-up that lands during the call itself. Either way the value the write recorded (a real worktree
     * path, a real agent start) was true when reported — only the run had already ended without it — so it is left
     * as is, not rolled back.
     */
    private void fireEffect(Offer offer, Runnable effect) {
        requireOpen(offer);
        effect.run();
        requireOpen(offer);
    }

    private void requireOpen(Offer offer) {
        synchronized (lock) {
            if (offer.expired) {
                throw new ApiException(409, "lease_expired",
                        "this run's lease has expired; keep its worktree, the member can retry it");
            }
        }
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
        Offer offer;
        synchronized (lock) {
            offer = held(worker, taskId, seq);
            offer.takenBy = null;
            offer.expired = true;
        }
        // held() refused an already-expired offer under the same lock acquisition that set expired above, so no
        // givenUp() can have completed this future first: complete() returns false only if that invariant is ever
        // broken, and this branch is the guard that turns such a break into a 409 the worker can act on rather than
        // a silently dropped result.
        if (!offer.answer.complete(result)) {
            throw new ApiException(409, "lease_expired",
                    "this run's lease has expired; keep its worktree, the member can retry it");
        }
        Log.info("worker.job_reported", "task", taskId, "run", seq, "worker", worker.id(), "outcome", result.outcome());
    }

    /** The job {@code worker} holds the lease for, so it can fetch that task's files. */
    public Job leased(Workers.Paired worker, long taskId) {
        return held(worker, taskId, -1).job;
    }

    /** @param seq -1 when the caller does not name a run */
    private Offer held(Workers.Paired worker, long taskId, int seq) {
        synchronized (lock) {
            // A stale, already-expired offer for this same task can still be in the list (removed only once run()'s
            // own wait loop returns, a window after givenUp() marks it expired): excluding it here, not just checking
            // it below, is what stops a re-offered task (its next run) from resolving to that dead offer instead.
            Optional<Offer> found = offers.stream().filter(offer -> offer.job.taskId() == taskId && !offer.expired).findFirst();
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
