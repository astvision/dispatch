package dispatch.core;

import dispatch.agent.AgentActivity;
import dispatch.agent.RunHandle;
import dispatch.domain.RunStep;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Runs being executed by this process, so cancel, timeout and shutdown can reach their agent processes. */
public final class ActiveRuns {

    public enum StopReason {
        CANCELLED,
        TIMEOUT,
        INTERRUPTED
    }

    private final Map<Long, ActiveRun> byTask = new ConcurrentHashMap<>();
    /** Set by stopAll: runs registered afterwards (claimed just before shutdown) start out stopped. */
    private volatile StopReason closedWith;

    /** Registered when a run is claimed, before its worktree or agent exist, so shutdown waits for setup too. */
    public ActiveRun register(long taskId, int seq) {
        ActiveRun run = new ActiveRun(taskId, seq);
        ActiveRun existing = byTask.putIfAbsent(taskId, run);
        if (existing != null) {
            throw new IllegalStateException("task " + taskId + " already has active run " + existing.seq());
        }
        StopReason closed = closedWith;
        if (closed != null) {
            run.stop(closed);
        }
        return run;
    }

    public void unregister(ActiveRun run) {
        byTask.remove(run.taskId(), run);
    }

    public void stop(long taskId, StopReason reason) {
        ActiveRun run = byTask.get(taskId);
        if (run != null) {
            run.stop(reason);
        }
    }

    /** ⏭ on step {@code step} of the task's active run (RM-4); false when the task has no active run here. */
    public boolean skip(long taskId, int step) {
        ActiveRun run = byTask.get(taskId);
        if (run == null) {
            return false;
        }
        run.skip(step);
        return true;
    }

    /** 📦 deliver now on the task's active run (RM-4); false when the task has no active run here. */
    public boolean deliverNow(long taskId) {
        ActiveRun run = byTask.get(taskId);
        if (run == null) {
            return false;
        }
        run.deliverNow();
        return true;
    }

    /** The task's run carried in this process, for what the run monitor offers (RM-4); empty when it has none. */
    public Optional<ActiveRun> run(long taskId) {
        return Optional.ofNullable(byTask.get(taskId));
    }

    /** What the task's agent is doing now; empty when the task has no active run or its agent has not started. */
    public Optional<AgentActivity> activity(long taskId) {
        ActiveRun run = byTask.get(taskId);
        return run == null ? Optional.empty() : run.activity();
    }

    /** Whether a run of this task is being carried right now in this process. */
    public boolean isActive(long taskId) {
        return byTask.containsKey(taskId);
    }

    public void stopAll(StopReason reason) {
        closedWith = reason;
        byTask.values().forEach(run -> run.stop(reason));
    }

    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (!byTask.isEmpty()) {
            if (Instant.now().isAfter(deadline)) {
                return false;
            }
            Thread.sleep(100);
        }
        return true;
    }

    public static final class ActiveRun {

        private final long taskId;
        private final int seq;
        private RunHandle handle;
        private AgentActivity reported;
        private StopReason stopReason;
        /** The step running now and its kind; 0 and null between steps. */
        private int currentStep;
        private RunStep.Kind currentKind;
        /** The highest step the requester skipped (RM-4): a tap only ever names the step it saw running. */
        private int skippedStep;
        private boolean deliverNow;

        private ActiveRun(long taskId, int seq) {
            this.taskId = taskId;
            this.seq = seq;
        }

        public long taskId() {
            return taskId;
        }

        public int seq() {
            return seq;
        }

        /**
         * A stop requested before the agent started still cancels it as soon as it is attached, and so does a ⏭ or 📦 tapped
         * after its fix or review step began but before its agent started.
         */
        public synchronized void attach(RunHandle handle) {
            this.handle = handle;
            if (stopReason != null) {
                handle.cancel();
                return;
            }
            if (skippedStep == currentStep || deliverNow) {
                cancelIfSkippable(currentStep);
            }
        }

        /** The first reason wins; later stops are ignored so a timeout cannot relabel a member's cancel. */
        public synchronized boolean stop(StopReason reason) {
            if (stopReason != null) {
                return false;
            }
            stopReason = reason;
            if (handle != null) {
                handle.cancel();
            }
            return true;
        }

        public synchronized StopReason stopReason() {
            return stopReason;
        }

        public synchronized void stepStarted(int step, RunStep.Kind kind) {
            currentStep = step;
            currentKind = kind;
        }

        public synchronized void stepEnded(int step) {
            if (currentStep == step) {
                currentStep = 0;
                currentKind = null;
            }
        }

        /**
         * ⏭ on {@code step}. When it is this run's running test, fix or review, a fix or review call is cancelled here and a
         * test stops at its next check; a tap on a step that already ended changes nothing. On the team machine, which runs
         * no agent for a worker's run, it is only remembered for the worker's next progress.
         */
        public synchronized void skip(int step) {
            skippedStep = Math.max(skippedStep, step);
            cancelIfSkippable(step);
        }

        public synchronized boolean skipRequested(int step) {
            return skippedStep == step;
        }

        public synchronized int skippedStep() {
            return skippedStep;
        }

        /** 📦: the running test, fix or review is cancelled as ⏭ does, and the loop runs nothing more. */
        public synchronized void deliverNow() {
            deliverNow = true;
            cancelIfSkippable(currentStep);
        }

        public synchronized boolean deliverNowRequested() {
            return deliverNow;
        }

        private void cancelIfSkippable(int step) {
            boolean loopStep = currentKind == RunStep.Kind.FIX || currentKind == RunStep.Kind.REVIEW;
            if (step != 0 && step == currentStep && loopStep && handle != null) {
                handle.cancel();
            }
        }

        public synchronized Optional<AgentActivity> activity() {
            if (handle != null) {
                return Optional.of(handle.activity());
            }
            return Optional.ofNullable(reported);
        }

        /** What a remote worker's agent is doing, from its progress every 10 s; a local run reads its handle instead. */
        public synchronized void reportActivity(AgentActivity activity) {
            this.reported = activity;
        }
    }
}
