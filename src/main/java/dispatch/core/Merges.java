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
        Optional<TaskAccess.Refusal> refused = verdict.refusal(TaskAccess.Action.MERGE);
        if (refused.isPresent()) {
            return switch (refused.get()) {
                case MERGED -> Outcome.MERGED;
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

    private void merge(Task task, Requester who, String messageRef) {
        try {
            String state = pullRequests.state(task.prUrl());
            if (state.equals("CLOSED")) {
                refused(task, messageRef, Json.object().put("closed", true));
                return;
            }
            // Merged already, on GitHub or by a tap whose answer a restart lost: only recording it is left.
            if (!state.equals("MERGED")) {
                pullRequests.squashMerge(task.prUrl());
            }
            merged(task, who, messageRef);
        } catch (WorkspaceException e) {
            // GitHub's own refusal, such as a check still running: the requester reads why and taps again later.
            refused(task, messageRef, Json.object().put("error", e.getMessage()));
        } catch (RuntimeException e) {
            Log.error("merge.failed", e, "task", task.id(), "pr", task.prUrl());
            refused(task, messageRef, Json.object().put("error", e.getMessage() == null ? e.toString() : e.getMessage()));
        } finally {
            merging.remove(task.id());
        }
    }

    private void merged(Task task, Requester who, String messageRef) {
        Instant now = clock.instant();
        db.transaction(tx -> {
            Tasks.merged(tx, task.id(), now);
            Events.record(tx, task.id(), null, who.ref(), Phase.COMPLETED, Phase.COMPLETED, "merged", now);
            Outbox.enqueue(tx, task.id(), OutboxKind.TASK_MERGED, task.requester().ref(), messageRef,
                    Json.object().put("taskId", task.id()).put("base", task.baseBranch()).put("prUrl", task.prUrl()), now);
            // The result that offered it is redrawn without its button.
            Outbox.findSent(tx, messageRef).filter(sent -> sent.kind() == OutboxKind.TASK_COMPLETED).ifPresent(sent ->
                    Outbox.enqueueEdit(tx, task.id(), OutboxKind.TASK_COMPLETED, task.requester().ref(), messageRef,
                            ((ObjectNode) Json.read(sent.payload())).put("merged", true), now));
        });
        Log.info("merge.done", "task", task.id(), "pr", task.prUrl());
        wakeOutbox.run();
    }

    private void refused(Task task, String messageRef, ObjectNode reason) {
        db.transaction(tx -> Outbox.enqueue(tx, task.id(), OutboxKind.MERGE_REFUSED, task.requester().ref(), messageRef,
                reason.put("taskId", task.id()), clock.instant()));
        Log.warn("merge.refused", "task", task.id(), "pr", task.prUrl(), "reason", reason.toString());
        wakeOutbox.run();
    }
}
