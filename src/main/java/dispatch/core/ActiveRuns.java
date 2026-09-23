package dispatch.core;

import dispatch.agent.AgentActivity;
import dispatch.agent.RunHandle;
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

        /** A stop requested before the agent started still cancels it as soon as it is attached. */
        public synchronized void attach(RunHandle handle) {
            this.handle = handle;
            if (stopReason != null) {
                handle.cancel();
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
