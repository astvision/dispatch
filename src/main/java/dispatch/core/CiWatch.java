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
import dispatch.store.Runs;
import dispatch.store.TaskCi;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import dispatch.workspace.Gh;
import dispatch.workspace.WorkspaceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** Failed logs read for one fix: a matrix that fails everywhere fails the same way. */
    private static final int LOGS_READ = 3;
    private static final int LOG_LIMIT = 30_000;
    /** A check's link up to its Actions run: the checks of one run share one failed log. */
    private static final Pattern RUN_LINK = Pattern.compile("^https://[^/]+/[^/]+/[^/]+/actions/runs/\\d+");

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
                // A defect in deciding one watch must not end the others, nor the bot: it is said in full, every pass.
                Log.error("ci.watch_failed", e, "task", watch.taskId(), "state", watch.state());
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
            return stopAndClear(watch, task, "OFF");
        }
        try {
            return ask(watch, task);
        } catch (WorkspaceException e) {
            // gh missing or logged out, GitHub unreachable or refusing: the watch waits and the next pass asks again, for as
            // long as checks that never end would be waited for.
            Log.warn("ci.ask_failed", "task", task.id(), "pr", task.prUrl(), "error", String.valueOf(e.getMessage()));
            return waited(watch).compareTo(STUCK_AFTER) > 0 && gaveUp(watch, task, "STUCK", Json.MAPPER.createArrayNode(), null);
        }
    }

    /** Asks GitHub how the watched commit stands and writes what it says. */
    private boolean ask(TaskCi.Watch watch, Task task) {
        Gh.PullRequest pullRequest = checks.pullRequest(task.prUrl());
        if (pullRequest.state().equals("MERGED")) {
            return write(watch, (tx, now) -> {
                String result = Outbox.latestResult(tx, task.id()).map(Outbox.Result::sentRef).orElse(null);
                Merges.recordMerged(tx, task, "github", result, now);
                TaskCi.settle(tx, task.id(), TaskCi.State.STOPPED, "MERGED", null, now);
            });
        }
        if (pullRequest.state().equals("CLOSED")) {
            return stopAndClear(watch, task, "CLOSED");
        }
        if (!watch.headSha().equals(pullRequest.headSha())) {
            // Someone else pushed: what the checks say is no longer about Dispatch's commit (the branch guard's rule).
            Log.warn("ci.head_moved", "task", task.id(), "watched", watch.headSha(), "found", pullRequest.headSha());
            return stopAndClear(watch, task, "MOVED");
        }
        List<Gh.Check> all = checks.checks(task.prUrl());
        Duration waited = waited(watch);
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

    /** How long the watched commit has been delivered. */
    private Duration waited(TaskCi.Watch watch) {
        return Duration.between(watch.armedAt(), clock.instant());
    }

    /** Ends a watch whose result needs no redraw: a merged one is redrawn by its merge, a cancelled task's says so itself. */
    private boolean stop(TaskCi.Watch watch, String reason) {
        return write(watch, (tx, now) -> TaskCi.settle(tx, watch.taskId(), TaskCi.State.STOPPED, reason, null, now));
    }

    /** Ends a watch on a pull request that stays open or was closed: its result stops saying the checks run. */
    private boolean stopAndClear(TaskCi.Watch watch, Task task, String reason) {
        return write(watch, (tx, now) -> {
            TaskCi.settle(tx, task.id(), TaskCi.State.STOPPED, reason, null, now);
            redraw(tx, task, null, now);
        });
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

    /** Red on Dispatch's own commit: a fix run while rounds are left, else the pull request goes back to its requester. */
    private boolean red(TaskCi.Watch watch, Task task, List<Gh.Check> failed) {
        ArrayNode failedJson = Json.MAPPER.createArrayNode();
        failed.forEach(check -> failedJson.addObject().put("name", check.name()).put("link", check.link()));
        if (watch.fixRounds() >= MAX_FIX_ROUNDS) {
            return gaveUp(watch, task, "CAP", failedJson, null);
        }
        // Read before the transaction: GitHub is never asked inside one.
        String instruction = instruction(failed, task.prUrl());
        int round = watch.fixRounds() + 1;
        String first = failed.getFirst().name();
        return write(watch, (tx, now) -> {
            ObjectNode news = Json.object().put("check", first).put("round", round);
            if (!commands.ciFix(tx, task.id(), watch.headSha(), instruction, news)) {
                return;
            }
            TaskCi.fixing(tx, task.id(), Json.write(failedJson), now);
            Events.record(tx, task.id(), null, TaskCommands.CI_ACTOR, Phase.COMPLETED, Phase.COMPLETED, "ci failed: " + first, now);
            redraw(tx, task, Json.object().put("state", "FIXING").put("check", first).put("round", round), now);
        });
    }

    /**
     * A fix run ended and its task is delivered again. Had it pushed, its delivery would have armed the watch anew; the
     * branch still at the watched commit means it changed nothing, and its own words say why.
     */
    private boolean settleFix(TaskCi.Watch watch, Task task) {
        record Ended(String head, String summary) {
        }
        Ended ended = db.transactionReturning(tx -> new Ended(Tasks.expectedHead(tx, task.id()),
                Runs.latest(tx, task.id()).flatMap(run -> Runs.output(tx, task.id(), run.seq())).orElse("")));
        if (!watch.headSha().equals(ended.head())) {
            // Delivered, yet not armed: the project stopped being watched while the fix ran.
            return stop(watch, "OFF");
        }
        ArrayNode failed = watch.checksJson() == null ? Json.MAPPER.createArrayNode() : (ArrayNode) Json.read(watch.checksJson());
        return gaveUp(watch, task, "UNCHANGED", failed, ended.summary());
    }

    /**
     * What the fix run is told: every failed check, and the end of the failed logs, one per Actions run. Only runs of the
     * pull request's own repository are read: a check may link anywhere, and this machine's gh reads whatever its login can.
     */
    private String instruction(List<Gh.Check> failed, String prUrl) {
        int pull = prUrl.indexOf("/pull/");
        String ownRuns = pull < 0 ? null : prUrl.substring(0, pull) + "/actions/runs/";
        StringBuilder text = new StringBuilder("Failed checks:");
        Set<String> runs = new LinkedHashSet<>();
        for (Gh.Check check : failed) {
            text.append("\n- ").append(check.name()).append(": ").append(check.link());
            Matcher run = RUN_LINK.matcher(check.link());
            if (ownRuns != null && run.find() && run.group().startsWith(ownRuns) && runs.size() < LOGS_READ) {
                runs.add(run.group());
            }
        }
        StringBuilder logs = new StringBuilder();
        for (String run : runs) {
            try {
                String log = checks.failedLog(run).strip();
                if (!log.isEmpty()) {
                    logs.append(logs.isEmpty() ? "" : "\n").append(log);
                }
            } catch (WorkspaceException e) {
                // The fix starts on names and links alone rather than not at all.
                Log.warn("ci.log_unread", "run", run, "error", String.valueOf(e.getMessage()));
            }
        }
        if (logs.isEmpty()) {
            return text.toString();
        }
        String tail = logs.length() <= LOG_LIMIT ? logs.toString() : logs.substring(logs.length() - LOG_LIMIT);
        // A log is quoted, here and in the fix prompt: it must not be able to end its own quotation.
        tail = tail.replace("</log>", "</ log>").replace("</ci-failure>", "</ ci-failure>");
        return text.append("\n\nEnd of the failed log:\n<log>\n").append(tail).append("\n</log>").toString();
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
