package dispatch.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.agent.Agent;
import dispatch.agent.AgentResult;
import dispatch.agent.AgentStartException;
import dispatch.agent.RunHandle;
import dispatch.agent.RunRequest;
import dispatch.domain.OutboxKind;
import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.store.Conversations;
import dispatch.store.Database;
import dispatch.store.Outbox;
import dispatch.store.Tx;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The bot's assistant (A-1): a member's plain private message is answered by a Claude Code session of their own, which
 * reads their tasks and code and proposes actions they confirm with a tap. Each message is one turn, run on its own
 * virtual thread outside any transaction; one member's turns run one at a time, in order, so their session is never
 * resumed twice at once. A turn that fails offers the message as a task draft instead, so nothing is lost.
 */
public final class Assistant {

    /** Cheap and fast for conversation; a turn that needs more is answered again by {@link #ESCALATED_MODEL}. */
    static final String MODEL = "haiku";
    static final String ESCALATED_MODEL = "sonnet";
    /** A message after this much silence starts a new session. */
    static final Duration IDLE_RESET = Duration.ofHours(12);
    /** "Think it through": the member asks for the stronger model directly. */
    private static final String THINK_HARD = "сайн бодоорой";
    private static final int MAX_ACTIONS = 3;
    private static final Duration TYPING_INTERVAL = Duration.ofSeconds(4);

    private final Database db;
    private final TaskService tasks;
    private final AssistantActions actions;
    private final Groups groups;
    private final Projects projects;
    private final Agent agent;
    private final AssistantHome home;
    private final Function<String, Optional<Path>> cloneOf;
    private final Clock clock;
    private final Duration timeout;
    private final Consumer<String> typing;
    private final Runnable wakeOutbox;
    private final Map<String, ReentrantLock> turns = new ConcurrentHashMap<>();
    private final Set<RunHandle> running = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;

    /**
     * @param cloneOf a project's clone on this machine, which the assistant may read; empty where there is none (team mode)
     * @param timeout how long a turn may take; the stronger model, reading code, gets three times as long
     * @param typing  shows the member's chat that the bot is writing; best effort
     */
    public Assistant(Database db, TaskService tasks, AssistantActions actions, Groups groups, Projects projects, Agent agent,
                     AssistantHome home, Function<String, Optional<Path>> cloneOf, Clock clock, Duration timeout,
                     Consumer<String> typing, Runnable wakeOutbox) {
        this.db = db;
        this.tasks = tasks;
        this.actions = actions;
        this.groups = groups;
        this.projects = projects;
        this.agent = agent;
        this.home = home;
        this.cloneOf = cloneOf;
        this.clock = clock;
        this.timeout = timeout;
        this.typing = typing;
        this.wakeOutbox = wakeOutbox;
    }

    /**
     * Answers {@code text} in the background, after the caller's transaction.
     *
     * @param originRef the member's message, which the answer replies to
     * @param chatRef   their private chat
     */
    public void submit(Tx tx, Requester who, String text, String originRef, String chatRef) {
        tx.afterCommit(() -> Thread.ofVirtual().name("assistant-" + who.ref()).start(() -> answer(who, text, originRef, chatRef)));
    }

    /** /new: the member's next message starts a fresh session. */
    public void reset(Tx tx, String memberRef) {
        Conversations.forgetSession(tx, memberRef);
    }

    /** Stops running turns without recording anything, since storage is about to close. */
    public void stop() {
        stopped = true;
        running.forEach(RunHandle::cancel);
    }

    /** One turn, on the calling thread, after the member's earlier turns. */
    void answer(Requester who, String text, String originRef, String chatRef) {
        ReentrantLock lock = turns.computeIfAbsent(who.ref(), ref -> new ReentrantLock(true));
        lock.lock();
        try {
            if (stopped) {
                return;
            }
            Consumer<Tx> outcome = turn(who, text, originRef, chatRef);
            if (!stopped) {
                db.transaction(outcome);
            }
        } catch (RuntimeException e) {
            // Storage itself is failing; the message is not answered, and the log says why.
            Log.error("assistant.not_recorded", e, "member", who.ref());
        } finally {
            lock.unlock();
        }
    }

    /** What the turn ended with, to record in one transaction. */
    private Consumer<Tx> turn(Requester who, String text, String originRef, String chatRef) {
        Instant started = clock.instant();
        Set<String> visible = groups.projectsOfMember(who.ref());
        record Context(Optional<UUID> session, String snapshot) {
        }
        Context context = db.transactionReturning(tx -> new Context(
                Conversations.session(tx, who.ref(), started.minus(IDLE_RESET)), snapshot(tx, visible, who.ref(), started)));
        UUID session = context.session().orElseGet(UUID::randomUUID);
        boolean thinkHard = text.toLowerCase(Locale.ROOT).contains(THINK_HARD);
        List<Spent> spent = new ArrayList<>();
        Thread typingLoop = Thread.ofVirtual().name("assistant-typing-" + who.ref()).start(() -> keepTyping(chatRef));
        try {
            Map<String, String> environment = home.environmentFor(who.ref(), visible);
            String prompt = Prompts.assistant(context.snapshot(), text);
            JsonNode output = ask(who, session, context.session().isPresent(), thinkHard ? ESCALATED_MODEL : MODEL, prompt,
                    visible, environment, spent);
            if (!thinkHard && output.path("escalate").asBoolean(false)) {
                output = ask(who, session, true, ESCALATED_MODEL, Prompts.assistantEscalated(), visible, environment, spent);
            }
            JsonNode answer = output;
            return tx -> answered(tx, who, session, answer, originRef, chatRef, spent);
        } catch (TurnFailed e) {
            return tx -> failed(tx, who, text, originRef, chatRef, spent, e.getMessage());
        } catch (RuntimeException e) {
            Log.error("assistant.crashed", e, "member", who.ref());
            return tx -> failed(tx, who, text, originRef, chatRef, spent, "Dispatch error: " + e.getMessage());
        } finally {
            typingLoop.interrupt();
        }
    }

    private record Spent(String model, BigDecimal costUsd) {
    }

    /** One run of the agent in the member's session; its structured answer, or why there is none. */
    private JsonNode ask(Requester who, UUID session, boolean resume, String model, String prompt, Set<String> visible,
                         Map<String, String> environment, List<Spent> spent) throws TurnFailed {
        List<Path> clones = visible.stream().sorted().map(cloneOf).flatMap(Optional::stream).toList();
        RunRequest request = new RunRequest(RunKind.ASSISTANT, home.dir(), prompt, session, resume, clones, null, model, null,
                home.dir().resolve("logs").resolve(who.ref().replaceAll("[^A-Za-z0-9-]", "-") + "-" + clock.millis()), environment);
        RunHandle handle;
        try {
            handle = agent.start(request);
        } catch (AgentStartException e) {
            throw new TurnFailed(e.getMessage());
        }
        running.add(handle);
        Duration limit = model.equals(ESCALATED_MODEL) ? timeout.multipliedBy(3) : timeout;
        Thread watchdog = Thread.ofVirtual().name("assistant-timeout-" + who.ref()).start(() -> {
            try {
                Thread.sleep(limit);
                handle.cancel();
            } catch (InterruptedException e) {
                // The turn ended in time.
            }
        });
        try {
            AgentResult result = handle.await();
            spent.add(new Spent(model, result.costUsd()));
            return output(result, limit);
        } catch (InterruptedException e) {
            handle.cancel();
            Thread.currentThread().interrupt();
            throw new TurnFailed("assistant thread was interrupted");
        } finally {
            watchdog.interrupt();
            running.remove(handle);
        }
    }

    /** The agent's answer; anything but a reply with a list of actions is a failed turn, never guessed at. */
    private static JsonNode output(AgentResult result, Duration limit) throws TurnFailed {
        switch (result.outcome()) {
            case SUCCEEDED -> {
                // Checked below.
            }
            case BUDGET_EXCEEDED, FAILED -> throw new TurnFailed(result.error() + " (timeout " + limit.toSeconds() + "s)");
        }
        if (result.structuredOutput() == null) {
            throw new TurnFailed("agent finished without an answer");
        }
        JsonNode output;
        try {
            output = Json.read(result.structuredOutput());
        } catch (IllegalStateException e) {
            throw new TurnFailed("answer is not JSON: " + result.structuredOutput());
        }
        if (output.path("reply").asText("").isBlank() || !output.path("actions").isArray()) {
            throw new TurnFailed("answer is not a reply with actions: " + result.structuredOutput());
        }
        return output;
    }

    private void answered(Tx tx, Requester who, UUID session, JsonNode output, String originRef, String chatRef, List<Spent> spent) {
        Instant now = clock.instant();
        Conversations.saveSession(tx, who.ref(), session, now);
        spent.forEach(turn -> Conversations.recordTurn(tx, who.ref(), turn.model(), turn.costUsd(), now));
        ObjectNode payload = Json.object().put("reply", output.path("reply").asText().strip());
        ArrayNode proposed = payload.putArray("actions");
        ArrayNode notes = payload.putArray("notes");
        int count = 0;
        for (JsonNode action : output.path("actions")) {
            if (++count > MAX_ACTIONS) {
                break;
            }
            AssistantActions.Checked checked = actions.check(tx, who, action);
            if (checked.valid()) {
                long id = Conversations.proposeAction(tx, who.ref(), checked.payload(), now);
                proposed.add(checked.payload().deepCopy().put("id", id));
            } else {
                notes.add(checked.payload());
            }
        }
        Outbox.enqueue(tx, null, OutboxKind.ASSISTANT_REPLY, chatRef, originRef, payload, now);
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(() -> Log.info("assistant.turn", "member", who.ref(), "models", models(spent), "cost_usd", cost(spent),
                "actions", proposed.size(), "notes", notes.size()));
    }

    /** One line saying the assistant could not answer, then the usual draft prompt for the message, so nothing is lost. */
    private void failed(Tx tx, Requester who, String text, String originRef, String chatRef, List<Spent> spent, String error) {
        Instant now = clock.instant();
        // Whatever went wrong may be the session itself; the next message starts a fresh one.
        Conversations.forgetSession(tx, who.ref());
        spent.forEach(turn -> Conversations.recordTurn(tx, who.ref(), turn.model(), turn.costUsd(), now));
        Outbox.enqueue(tx, null, OutboxKind.ASSISTANT_REPLY, chatRef, originRef, Json.object().put("failed", true), now);
        tx.afterCommit(wakeOutbox);
        tasks.draft(tx, who, null, text, originRef);
        tx.afterCommit(() -> Log.warn("assistant.failed", "member", who.ref(), "models", models(spent), "cost_usd", cost(spent),
                "error", error));
    }

    /**
     * What the member's tasks look like right now, so a plain "what is going on?" needs no tool call: their own running,
     * queued and waiting tasks, and the projects a task can be given for.
     */
    private String snapshot(Tx tx, Set<String> visible, String memberRef, Instant now) {
        ObjectNode status = tasks.statusPayload(tx, visible, memberRef);
        ObjectNode snapshot = Json.object().put("now", now.toString());
        for (String list : List.of("awaitingApproval", "running", "queued")) {
            ArrayNode mine = snapshot.putArray(list);
            status.withArray(list).forEach(item -> {
                if (item.path("mine").asBoolean(false)) {
                    mine.add(item);
                }
            });
        }
        ArrayNode listed = snapshot.putArray("projects");
        projects.all().stream().filter(project -> visible.contains(project.name()))
                .forEach(project -> listed.addObject().put("name", project.name()).put("alias", project.alias()));
        return snapshot.toString();
    }

    private void keepTyping(String chatRef) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    typing.accept(chatRef);
                } catch (RuntimeException e) {
                    Log.warn("assistant.typing_failed", "chat", chatRef, "error", e.getMessage());
                }
                Thread.sleep(TYPING_INTERVAL);
            }
        } catch (InterruptedException e) {
            // The turn is over.
        }
    }

    private static String models(List<Spent> spent) {
        return String.join(",", spent.stream().map(Spent::model).toList());
    }

    private static BigDecimal cost(List<Spent> spent) {
        return spent.stream().map(Spent::costUsd).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static final class TurnFailed extends Exception {

        TurnFailed(String message) {
            super(message);
        }
    }
}
