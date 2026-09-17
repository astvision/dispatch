package dispatch.core;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.agent.Agent;
import dispatch.agent.AgentResult;
import dispatch.agent.AgentStartException;
import dispatch.agent.RunHandle;
import dispatch.agent.RunRequest;
import dispatch.domain.Draft;
import dispatch.domain.RunKind;
import dispatch.store.Database;
import dispatch.store.Drafts;
import dispatch.store.Tx;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Asks the agent which independent tasks a draft's message holds (ADR 0013). Each split runs on its own virtual thread,
 * beside the task runs and outside their queue: a cheap model, no tools, at most two at a time, each capped by budget and
 * timeout. How it ended is recorded on the draft, whose prompt is then redrawn.
 */
public final class Splitter {

    /** Measured at about $0.015 and 3 seconds per message; the budget leaves room for the model retrying its answer. */
    static final String MODEL = "haiku";
    static final BigDecimal BUDGET_USD = new BigDecimal("0.25");
    private static final int MAX_TOPICS = 10;
    /** Telegram's message limit: no topic of a real message is longer. */
    private static final int MAX_TOPIC_LENGTH = 4096;
    private static final int MAX_CONCURRENT = 2;

    private final Database db;
    private final TaskService tasks;
    private final Agent agent;
    private final Path workdir;
    private final Clock clock;
    private final Duration timeout;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);
    private final Set<RunHandle> running = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;

    /** @param workdir an empty directory the agent runs in; it also keeps the split logs */
    public Splitter(Database db, TaskService tasks, Agent agent, Path workdir, Clock clock, Duration timeout) {
        this.db = db;
        this.tasks = tasks;
        this.agent = agent;
        this.workdir = workdir;
        this.clock = clock;
        this.timeout = timeout;
    }

    public void start(long draftId) {
        Thread.ofVirtual().name("split-" + draftId).start(() -> split(draftId));
    }

    /**
     * Stops running splits without recording anything, since storage is about to close. Their drafts stay SPLITTING, and
     * the next start reports them as failed.
     */
    public void stop() {
        stopped = true;
        running.forEach(RunHandle::cancel);
    }

    /** Runs one split to its end on the calling thread. */
    void split(long draftId) {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (stopped) {
                return;
            }
            Consumer<Tx> outcome = propose(draftId);
            if (!stopped) {
                db.transaction(outcome);
            }
        } catch (RuntimeException e) {
            // Storage itself is failing: the draft stays SPLITTING, and the next start reports it as failed.
            Log.error("split.not_recorded", e, "draft", draftId);
        } finally {
            slots.release();
        }
    }

    /** How the split ended, to record in one transaction: its topics, or why there are none. */
    private Consumer<Tx> propose(long draftId) {
        try {
            Draft draft = db.transactionReturning(tx -> Drafts.find(tx, draftId))
                    .orElseThrow(() -> new IllegalStateException("draft " + draftId + " is missing"));
            List<String> topics = topics(ask(draftId, draft.description()));
            return tx -> tasks.splitProposed(tx, draftId, topics);
        } catch (SplitFailed e) {
            return tx -> tasks.splitFailed(tx, draftId, e.getMessage());
        } catch (RuntimeException e) {
            Log.error("split.crashed", e, "draft", draftId);
            return tx -> tasks.splitFailed(tx, draftId, "Dispatch error: " + e.getMessage());
        }
    }

    private AgentResult ask(long draftId, String message) throws SplitFailed {
        RunRequest request = new RunRequest(RunKind.SPLIT, workdir, Prompts.split(message), null, false, List.of(), BUDGET_USD, MODEL,
                null, workdir.resolve(draftId + "-" + clock.millis()));
        RunHandle handle;
        try {
            handle = agent.start(request);
        } catch (AgentStartException e) {
            throw new SplitFailed(e.getMessage());
        }
        running.add(handle);
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread watchdog = Thread.ofVirtual().name("split-timeout-" + draftId).start(() -> {
            try {
                Thread.sleep(timeout);
                timedOut.set(true);
                handle.cancel();
            } catch (InterruptedException e) {
                // The split ended in time.
            }
        });
        try {
            AgentResult result = handle.await();
            Log.info("split.agent_done", "draft", draftId, "outcome", result.outcome(), "cost_usd", result.costUsd(),
                    "turns", result.turns());
            if (timedOut.get()) {
                throw new SplitFailed("stopped after " + timeout.toSeconds() + "s");
            }
            return result;
        } catch (InterruptedException e) {
            handle.cancel();
            Thread.currentThread().interrupt();
            throw new SplitFailed("split thread was interrupted");
        } finally {
            watchdog.interrupt();
            running.remove(handle);
        }
    }

    /** The agent's answer as topics; anything but one to ten non-blank texts is a failure, never guessed at. */
    private static List<String> topics(AgentResult result) throws SplitFailed {
        switch (result.outcome()) {
            case SUCCEEDED -> {
                // Checked below.
            }
            case BUDGET_EXCEEDED -> throw new SplitFailed("budget exceeded: " + result.error());
            case FAILED -> throw new SplitFailed(result.error());
        }
        if (result.structuredOutput() == null) {
            throw new SplitFailed("agent finished without topics");
        }
        List<String> topics = new ArrayList<>();
        for (JsonNode topic : Json.read(result.structuredOutput()).path("topics")) {
            String text = topic.isTextual() ? topic.asText().strip() : "";
            if (text.isEmpty() || text.length() > MAX_TOPIC_LENGTH) {
                throw new SplitFailed("topic " + (topics.size() + 1) + " is not a usable text: " + topic);
            }
            topics.add(text);
        }
        if (topics.isEmpty() || topics.size() > MAX_TOPICS) {
            throw new SplitFailed("expected 1 to " + MAX_TOPICS + " topics, got " + topics.size());
        }
        return List.copyOf(topics);
    }

    private static final class SplitFailed extends Exception {

        SplitFailed(String message) {
            super(message);
        }
    }
}
