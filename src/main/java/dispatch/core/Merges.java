package dispatch.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.domain.OutboxKind;
import dispatch.domain.Phase;
import dispatch.domain.Requester;
import dispatch.domain.Task;
import dispatch.store.Database;
import dispatch.store.Events;
import dispatch.store.Outbox;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import dispatch.workspace.WorkspaceException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * The Merge button (ADR 0007, amended): the requester merges their delivered task's pull request from its result. The
 * tap is checked like every task action (ADR 0027); GitHub is asked in the background, never inside the update's
 * transaction, and what it answered is said under the result.
 */
public final class Merges {

    /** GitHub, as merging needs it. */
    public interface PullRequests {

        /** OPEN, MERGED or CLOSED. */
        String state(String url);

        /** Marks it ready and squash-merges it, deleting its branch; throws with GitHub's own words when it refuses. */
        void squashMerge(String url);
    }

    /** How a tap on the Merge button ended; the chat says it in words. */
    public enum Outcome {
        /** GitHub is being asked; the answer comes under the result. */
        STARTED,
        /** An earlier tap is still being merged. */
        RUNNING,
        /** Merged already. */
        MERGED,
        /** Not this member's task to merge. */
        NOT_ALLOWED,
        /** Nothing delivered to merge, or the task moved on. */
        WRONG_STATE
    }

    private final Database db;
    private final TaskAccess access;
    private final PullRequests pullRequests;
    private final Clock clock;
    private final Runnable wakeOutbox;
    private final Executor background;
    /** ponytail: in memory, enough because one process owns each state directory (ADR 0005); lost on restart, like the merge in flight. */
    private final Set<Long> merging = ConcurrentHashMap.newKeySet();

    /** @param background where GitHub is asked, off the thread that handles Telegram updates */
    public Merges(Database db, Groups groups, PullRequests pullRequests, Clock clock, Runnable wakeOutbox, Executor background) {
        this.db = db;
        this.access = new TaskAccess(groups);
        this.pullRequests = pullRequests;
        this.clock = clock;
        this.wakeOutbox = wakeOutbox;
        this.background = background;
    }

    /** {@code who} tapped Merge under task {@code taskId}'s result, {@code messageRef}; what follows is said under it. */
    public Outcome request(Tx tx, Requester who, long taskId, String messageRef) {
        TaskAccess.Verdict verdict = access.of(tx, who.ref(), taskId);
        Optional<Refusal> refused = verdict.refusal(TaskAccess.Action.MERGE);
        if (refused.isPresent()) {
            if (refused.get() == Refusal.MERGED) {
                // A result the merge was not tapped under still shows the button: it goes now.
                redrawMerged(tx, taskId, who.ref(), messageRef, clock.instant());
                tx.afterCommit(wakeOutbox);
                return Outcome.MERGED;
            }
            return switch (refused.get()) {
                case NOT_MEMBER, NOT_FOUND, NOT_REQUESTER -> Outcome.NOT_ALLOWED;
                default -> Outcome.WRONG_STATE;
            };
        }
        if (merging.contains(taskId)) {
            return Outcome.RUNNING;
        }
        Task task = verdict.task();
        // Only once the tap is committed: a rolled-back update must not leave a merge running, nor one marked as running.
        tx.afterCommit(() -> {
            if (merging.add(taskId)) {
                background.execute(() -> merge(task, who, messageRef));
            }
        });
        return Outcome.STARTED;
    }

    /** What GitHub answered: merged, or why not, as MERGE_REFUSED says it. */
    private record Answer(boolean merged, ObjectNode refusal) {

        static final Answer MERGED = new Answer(true, null);

        static Answer refused(ObjectNode why) {
            return new Answer(false, why);
        }
    }

    private void merge(Task task, Requester who, String messageRef) {
        try {
            Answer answer = ask(task);
            try {
                if (answer.merged()) {
                    merged(task, who, messageRef);
                } else {
                    refused(task, messageRef, answer.refusal());
                }
            } catch (RuntimeException e) {
                // GitHub has answered; only recording it failed. A later tap asks again and records what GitHub shows.
                Log.error("merge.not_recorded", e, "task", task.id(), "pr", task.prUrl(), "merged", answer.merged());
            }
        } finally {
            merging.remove(task.id());
        }
    }

    /** GitHub alone, outside any transaction; its state after the merge is the answer, never gh's exit code. */
    private Answer ask(Task task) {
        String url = task.prUrl();
        try {
            String state = pullRequests.state(url);
            if (state.equals("CLOSED")) {
                return Answer.refused(Json.object().put("closed", true));
            }
            // Merged already, on GitHub or by a tap whose answer a restart lost: only recording it is left.
            if (state.equals("MERGED")) {
                return Answer.MERGED;
            }
            // A follow-up that began since the tap changes what would be merged: it goes first.
            if (!stillMergeable(task.id())) {
                return Answer.refused(Json.object().put("busy", true));
            }
            String error = null;
            try {
                pullRequests.squashMerge(url);
            } catch (WorkspaceException e) {
                // GitHub's own refusal, such as a check still running, unless the merge happened all the same.
                error = e.getMessage();
            }
            // A merge queue or auto-merge takes the merge without merging yet; a merge whose branch could not be deleted
            // fails though it merged.
            if (pullRequests.state(url).equals("MERGED")) {
                return Answer.MERGED;
            }
            return Answer.refused(error != null ? Json.object().put("error", error) : Json.object().put("queued", true));
        } catch (RuntimeException e) {
            // gh missing or logged out, or GitHub unreachable: the requester reads why and taps again later.
            Log.warn("merge.github_failed", "task", task.id(), "pr", url, "error", String.valueOf(e.getMessage()));
            return Answer.refused(Json.object().put("error", e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private boolean stillMergeable(long taskId) {
        return db.transactionReturning(tx -> Tasks.find(tx, taskId))
                .map(current -> current.phase() == Phase.COMPLETED && current.mergedAt() == null).orElse(false);
    }

    private void merged(Task task, Requester who, String messageRef) {
        Instant now = clock.instant();
        boolean recorded = db.transactionReturning(tx -> {
            if (!Tasks.merged(tx, task.id(), now)) {
                return false;
            }
            Phase phase = Tasks.find(tx, task.id()).orElseThrow().phase();
            Events.record(tx, task.id(), null, who.ref(), phase, phase, "merged", now);
            Outbox.enqueue(tx, task.id(), OutboxKind.TASK_MERGED, task.requester().ref(), messageRef,
                    Json.object().put("taskId", task.id()).put("base", task.baseBranch()).put("prUrl", task.prUrl()), now);
            redrawMerged(tx, task.id(), task.requester().ref(), messageRef, now);
            return true;
        });
        Log.info("merge.done", "task", task.id(), "pr", task.prUrl(), "recorded", recorded);
        wakeOutbox.run();
    }

    private void refused(Task task, String messageRef, ObjectNode reason) {
        db.transaction(tx -> Outbox.enqueue(tx, task.id(), OutboxKind.MERGE_REFUSED, task.requester().ref(), messageRef,
                reason.put("taskId", task.id()), clock.instant()));
        Log.warn("merge.refused", "task", task.id(), "pr", task.prUrl(), "reason", reason.toString());
        wakeOutbox.run();
    }

    /** The result {@code messageRef}, if it is task {@code taskId}'s own, redrawn as merged: without its button. */
    private static void redrawMerged(Tx tx, long taskId, String chatRef, String messageRef, Instant now) {
        Outbox.findSent(tx, messageRef)
                .filter(sent -> sent.kind() == OutboxKind.TASK_COMPLETED && Objects.equals(sent.taskId(), taskId))
                .ifPresent(sent -> Outbox.enqueueEdit(tx, taskId, OutboxKind.TASK_COMPLETED, chatRef, messageRef,
                        ((ObjectNode) Json.read(sent.payload())).put("merged", true), now));
    }
}
