package dispatch.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.config.Config;
import dispatch.domain.OutboxKind;
import dispatch.domain.Phase;
import dispatch.domain.Task;
import dispatch.store.Database;
import dispatch.store.Events;
import dispatch.store.Outbox;
import dispatch.store.TaskCi;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import dispatch.workspace.Gh;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Watches the checks of each pull request this bot delivered (ADR 0041). A delivery arms a watch on its commit
 * ({@link RunTransitions}); every {@link #INTERVAL} this asks GitHub about the pending ones and writes what it learned:
 * green says the pull request is ready, red starts a fix run in the task's building session, at most
 * {@link #MAX_FIX_ROUNDS} in a row. GitHub is asked outside any transaction; each verdict is written in one that reads the
 * watch and its task again and drops the verdict when either moved meanwhile. Only a bot that delivers on its own
 * machine runs one.
 */
public final class CiWatch implements Runnable {

    /** GitHub, as watching needs it. */
    public interface Checks {

        /** OPEN, MERGED or CLOSED, and the commit the pull request's branch is at. */
        Gh.PullRequest pullRequest(String url);

        /** Every check on the pull request's head commit; none for a repository that runs none. */
        List<Gh.Check> checks(String url);

        /** The end of a failed check's log; empty when there is none to read. */
        String failedLog(String checkLink);
    }

    /** Automatic fix runs in a row before the pull request is handed back. */
    public static final int MAX_FIX_ROUNDS = 2;
    static final Duration INTERVAL = Duration.ofSeconds(60);
    /** With nothing to watch, only a delivery's wake-up matters. */
    private static final Duration IDLE = Duration.ofHours(1);
    /** Checks appear within seconds of a push; a repository silent this long runs none. */
    static final Duration NO_CHECKS_AFTER = Duration.ofMinutes(10);
    static final Duration STUCK_AFTER = Duration.ofHours(6);

    private enum Verdict { NONE, PENDING, FAILED, CANCELLED, PASSED }

    private final Database db;
    private final Projects projects;
    private final TaskCommands commands;
    private final Checks checks;
    private final Clock clock;
    private final Signal signal;
    private final Runnable wakeOutbox;
    private volatile boolean stopped;

    /** @param signal woken by each delivery, so a new commit is asked about at once */
    public CiWatch(Database db, Projects projects, TaskCommands commands, Checks checks, Clock clock, Signal signal,
                   Runnable wakeOutbox) {
        this.db = db;
        this.projects = projects;
        this.commands = commands;
        this.checks = checks;
        this.clock = clock;
        this.signal = signal;
        this.wakeOutbox = wakeOutbox;
    }

    @Override
    public void run() {
        Log.info("ci.started");
        while (!stopped) {
            pass();
            boolean watching = db.transactionReturning(TaskCi::anyOpen);
            try {
                signal.await(watching ? INTERVAL : IDLE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.info("ci.stopped");
    }

    public void stop() {
        stopped = true;
        signal.wake();
    }

    /** One pass over every open watch; returns how many verdicts it wrote. */
    public int pass() {
        int written = 0;
        for (TaskCi.Watch watch : db.transactionReturning(TaskCi::open)) {
            try {
                if (decide(watch)) {
                    written++;
                }
            } catch (RuntimeException e) {
                // gh missing or logged out, GitHub unreachable or rate-limiting: the watch waits, and the next pass asks again.
                Log.warn("ci.ask_failed", "task", watch.taskId(), "error", String.valueOf(e.getMessage()));
            }
        }
        if (written > 0) {
            wakeOutbox.run();
        }
        return written;
    }

    private boolean decide(TaskCi.Watch watch) {
        Optional<Task> found = db.transactionReturning(tx -> Tasks.find(tx, watch.taskId()));
        if (found.isEmpty()) {
            return false;
        }
        Task task = found.get();
        if (task.phase() == Phase.CANCELLED) {
            return stop(watch, "ENDED");
        }
        if (task.phase() != Phase.COMPLETED) {
            // A run is going, or it failed: its next delivery arms the watch again.
            return false;
        }
        if (watch.state() == TaskCi.State.FIXING) {
            return settleFix(watch, task);
        }
        if (task.mergedAt() != null) {
            return stop(watch, "MERGED");
        }
        if (!projects.byName(task.project()).map(Config.Project::ciOn).orElse(false)) {
            return stop(watch, "OFF");
        }
        Gh.PullRequest pullRequest = checks.pullRequest(task.prUrl());
        if (pullRequest.state().equals("MERGED")) {
            return write(watch, (tx, now) -> {
                String result = Outbox.latestResult(tx, task.id()).map(Outbox.Result::sentRef).orElse(null);
                Merges.recordMerged(tx, task, "github", result, now);
                TaskCi.settle(tx, task.id(), TaskCi.State.STOPPED, "MERGED", null, now);
            });
        }
        if (pullRequest.state().equals("CLOSED")) {
            return stop(watch, "CLOSED");
        }
        if (!watch.headSha().equals(pullRequest.headSha())) {
            // Someone else pushed: what the checks say is no longer about Dispatch's commit (the branch guard's rule).
            Log.warn("ci.head_moved", "task", task.id(), "watched", watch.headSha(), "found", pullRequest.headSha());
            return stop(watch, "MOVED");
        }
        List<Gh.Check> all = checks.checks(task.prUrl());
        Duration waited = Duration.between(watch.armedAt(), clock.instant());
        return switch (verdict(all)) {
            case NONE -> waited.compareTo(NO_CHECKS_AFTER) > 0 ? none(watch, task) : touched(watch);
            case PENDING -> waited.compareTo(STUCK_AFTER) > 0 ? gaveUp(watch, task, "STUCK", Json.MAPPER.createArrayNode(), null)
                    : touched(watch);
            case CANCELLED -> gaveUp(watch, task, "CANCELLED", Json.MAPPER.createArrayNode(), null);
            case PASSED -> passed(watch, task);
            case FAILED -> red(watch, task, all.stream().filter(check -> check.bucket().equals("fail")).toList());
        };
    }

    /** Running checks outrank a failed one: a fix waits for every log. A cancelled run is somebody's decision, not a defect. */
    private static Verdict verdict(List<Gh.Check> all) {
        if (all.isEmpty()) {
            return Verdict.NONE;
        }
        if (all.stream().anyMatch(check -> check.bucket().equals("pending"))) {
            return Verdict.PENDING;
        }
        if (all.stream().anyMatch(check -> check.bucket().equals("fail"))) {
            return Verdict.FAILED;
        }
        return all.stream().anyMatch(check -> check.bucket().equals("cancel")) ? Verdict.CANCELLED : Verdict.PASSED;
    }

    /** GitHub had no verdict yet. */
    private boolean touched(TaskCi.Watch watch) {
        db.transaction(tx -> TaskCi.checked(tx, watch.taskId(), clock.instant()));
        return false;
    }

    private boolean stop(TaskCi.Watch watch, String reason) {
        return write(watch, (tx, now) -> TaskCi.settle(tx, watch.taskId(), TaskCi.State.STOPPED, reason, null, now));
    }

    /** No checks: the result stops saying they run. */
    private boolean none(TaskCi.Watch watch, Task task) {
        return write(watch, (tx, now) -> {
            TaskCi.settle(tx, task.id(), TaskCi.State.NONE, null, null, now);
            redraw(tx, task, null, now);
        });
    }

    private boolean passed(TaskCi.Watch watch, Task task) {
        return write(watch, (tx, now) -> {
            TaskCi.settle(tx, task.id(), TaskCi.State.PASSED, null, null, now);
            Events.record(tx, task.id(), null, TaskCommands.CI_ACTOR, task.phase(), task.phase(), "ci passed", now);
            redraw(tx, task, Json.object().put("state", "PASSED"), now);
            reply(tx, task, OutboxKind.CI_PASSED, Json.object(), now);
        });
    }

    /**
     * Hands the pull request back to its requester.
     *
     * @param failed  the failed checks, [{name, link}]; empty when none failed
     * @param summary the fix run's own words, when it changed nothing; null otherwise
     */
    private boolean gaveUp(TaskCi.Watch watch, Task task, String reason, ArrayNode failed, String summary) {
        return write(watch, (tx, now) -> {
            TaskCi.settle(tx, task.id(), TaskCi.State.GAVE_UP, reason, failed.isEmpty() ? null : Json.write(failed), now);
            Events.record(tx, task.id(), null, TaskCommands.CI_ACTOR, task.phase(), task.phase(), "ci gave up: " + reason, now);
            ObjectNode line = Json.object().put("state", "GAVE_UP");
            if (!failed.isEmpty()) {
                line.put("check", failed.get(0).path("name").asText());
            }
            redraw(tx, task, line, now);
            ObjectNode payload = Json.object().put("reason", reason).put("summary", summary);
            payload.set("checks", failed);
            reply(tx, task, OutboxKind.CI_GAVE_UP, payload, now);
        });
    }

    private boolean red(TaskCi.Watch watch, Task task, List<Gh.Check> failed) {
        return false;
    }

    private boolean settleFix(TaskCi.Watch watch, Task task) {
        return false;
    }

    /**
     * Writes a verdict, unless the watch or its task is no longer what GitHub was asked about: another commit, another
     * state, or a run begun meanwhile.
     */
    private boolean write(TaskCi.Watch seen, BiConsumer<Tx, Instant> verdict) {
        return db.transactionReturning(tx -> {
            Optional<TaskCi.Watch> current = TaskCi.find(tx, seen.taskId());
            Optional<Task> task = Tasks.find(tx, seen.taskId());
            boolean same = current.isPresent() && task.isPresent() && current.get().state() == seen.state()
                    && current.get().headSha().equals(seen.headSha())
                    && (task.get().phase() == Phase.COMPLETED || task.get().phase() == Phase.CANCELLED);
            if (!same) {
                return false;
            }
            verdict.accept(tx, clock.instant());
            return true;
        });
    }

    /** The task's newest result, drawn again with {@code ci} as its line; null for no line. */
    private static void redraw(Tx tx, Task task, ObjectNode ci, Instant now) {
        Outbox.latestResult(tx, task.id()).ifPresent(result -> {
            ObjectNode payload = (ObjectNode) Json.read(result.payload());
            if (ci == null) {
                payload.remove("ci");
            } else {
                payload.set("ci", ci);
            }
            Outbox.enqueueEditOf(tx, task.id(), OutboxKind.TASK_COMPLETED, task.requester().ref(), result.id(), payload, now);
        });
    }

    /** News under the task's newest result, to its requester alone (ADR 0011). */
    private static void reply(Tx tx, Task task, OutboxKind kind, ObjectNode payload, Instant now) {
        String under = Outbox.latestResult(tx, task.id()).map(Outbox.Result::sentRef).orElse(null);
        Outbox.enqueue(tx, task.id(), kind, task.requester().ref(), under, payload.put("taskId", task.id()), now);
    }
}
