package dispatch;

import dispatch.agent.Agent;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.config.Config;
import dispatch.config.MemberWriter;
import dispatch.core.ActiveRuns;
import dispatch.core.Coordinator;
import dispatch.core.DraftExpiry;
import dispatch.core.Sweeper;
import dispatch.core.Groups;
import dispatch.core.JobRunner;
import dispatch.core.Membership;
import dispatch.core.Projects;
import dispatch.core.Recovery;
import dispatch.core.RunTransitions;
import dispatch.core.Scheduler;
import dispatch.core.Signal;
import dispatch.core.Splitter;
import dispatch.core.TaskService;
import dispatch.core.Worker;
import dispatch.store.Database;
import dispatch.telegram.BotApi;
import dispatch.telegram.OutboxSender;
import dispatch.telegram.Poller;
import dispatch.telegram.Renderer;
import dispatch.telegram.TelegramException;
import dispatch.telegram.UpdateHandler;
import dispatch.worker.RemoteWorkers;
import dispatch.worker.WorkerApi;
import dispatch.worker.WorkerKeys;
import dispatch.workspace.Delivery;
import dispatch.workspace.Gh;
import dispatch.workspace.Git;
import dispatch.workspace.Workspaces;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One Dispatch instance, wired explicitly (ADR 0002). */
public final class App {

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

    private final Database db;
    private final Poller poller;
    private final Scheduler scheduler;
    private final OutboxSender sender;
    private final DraftExpiry draftExpiry;
    private final Sweeper sweeper;
    private final Splitter splitter;
    private final ActiveRuns activeRuns;
    private final WorkerApi workerApi;
    private final Consumer<Throwable> onFatal;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private Thread pollerThread;
    private Thread schedulerThread;
    private Thread senderThread;
    private Thread draftExpiryThread;
    private Thread sweeperThread;

    /** @param workerApi null in personal mode, where no computer ever reaches this machine */
    private App(Database db, Poller poller, Scheduler scheduler, OutboxSender sender, DraftExpiry draftExpiry, Sweeper sweeper, Splitter splitter,
                ActiveRuns activeRuns, WorkerApi workerApi, Consumer<Throwable> onFatal) {
        this.db = db;
        this.poller = poller;
        this.scheduler = scheduler;
        this.sender = sender;
        this.draftExpiry = draftExpiry;
        this.sweeper = sweeper;
        this.splitter = splitter;
        this.activeRuns = activeRuns;
        this.workerApi = workerApi;
        this.onFatal = onFatal;
    }

    /**
     * Opens state, checks the bot token, recovers runs interrupted by a previous process, then starts polling, scheduling
     * and sending. {@code onFatal} receives errors that stop a core loop (e.g. storage failures).
     */
    /** @param members adds people whom an admin let join to the config (ADR 0015) */
    public static App start(Config config, MemberWriter members, BotApi api, Map<String, String> environment, Clock clock,
                            Consumer<Throwable> onFatal) {
        Path stateDir = config.stateDir();
        Git git = new Git("git", config.secrets().ghToken(), Duration.ofMinutes(5));
        Workspaces workspaces = new Workspaces(stateDir, git);
        Delivery delivery = new Delivery(git, new Gh(config.delivery().ghCommand(), config.secrets().ghToken(), Duration.ofMinutes(2)),
                config.delivery().authorName(), config.delivery().authorEmail());
        Redactor redactor = Redactor.fromEnvironment(environment);
        // Team mode never runs a task's agent or holds a worktree here; each member's own computer does (ADR 0021).
        (config.workers() == null ? workspaces.createDirectories() : workspaces.createTeamDirectories())
                .ifPresent(warning -> Log.warn("state.permissions_too_open", "detail", warning));
        Database db = Database.open(stateDir.resolve("dispatch.db"));
        db.migrate();
        com.fasterxml.jackson.databind.JsonNode me = api.getMe();
        String botUsername = me.path("username").asText();
        // Set per bot in @BotFather; with it, each task gets its own topic in the requester's private chat.
        boolean taskTopics = me.path("has_topics_enabled").asBoolean(false);

        Signal schedulerSignal = new Signal();
        Signal outboxSignal = new Signal();
        Projects projects = new Projects(config.projects(), workspaces::unavailableReason);
        ActiveRuns activeRuns = new ActiveRuns();
        RunTransitions transitions = new RunTransitions(db, clock, outboxSignal::wake);
        Groups groups = new Groups(config.telegram());
        Splitter[] splitter = new Splitter[1];
        Map<String, Agent> agents = Map.of("claude-code",
                new ClaudeCodeAgent(config.agents().get("claude-code").command(), environment, Duration.ofSeconds(10)));
        WorkerKeys workerKeys = null;
        Worker worker;
        WorkerApi workerApi = null;
        if (config.workers() == null) {
            // Personal mode runs the job in this process, exactly as before.
            worker = new JobRunner(workspaces, delivery, agents, redactor, api::downloadFile);
        } else {
            workerKeys = new WorkerKeys(db, clock);
            // Someone taken out of the config takes their computers' access with them.
            workerKeys.revokeWorkersOfEveryoneExcept(memberRefs(groups));
            RemoteWorkers remoteWorkers = new RemoteWorkers(db, clock, schedulerSignal::wake);
            try {
                workerApi = WorkerApi.start(config, groups, workerKeys, remoteWorkers, api::downloadFile, db, clock);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot listen on 127.0.0.1:" + config.workers().port()
                        + " for members' computers: " + e.getMessage(), e);
            }
            worker = remoteWorkers;
        }
        Coordinator coordinator = new Coordinator(db, projects, transitions, activeRuns, config::planLimits,
                config::executeLimits, worker, schedulerSignal::wake);
        TaskService tasks = new TaskService(groups, projects, activeRuns, clock,
                schedulerSignal::wake, outboxSignal::wake, taskTopics, draftId -> splitter[0].start(draftId),
                config.workers() != null);
        // Splitting happens before a project is chosen, so it cannot use the project's agent (ADR 0013).
        splitter[0] = new Splitter(db, tasks, agents.get("claude-code"), workspaces.splitsDir(), clock, Duration.ofMinutes(1));

        new Recovery(db, transitions, Duration.ofSeconds(10)).run();
        db.transaction(tasks::failInterruptedSplits);
        Git slowGit = git.withTimeout(Duration.ofMinutes(30));
        for (Config.Project project : projects.all()) {
            if (workspaces.needsClone(project)) {
                // Tasks for it are refused with "cloning ..." until the clone is there.
                Thread.ofVirtual().name("clone-" + project.name()).start(() -> workspaces.cloneMissing(project, slowGit));
            } else {
                projects.unavailableReason(project).ifPresent(reason ->
                        Log.error("project.unavailable", null, "project", project.name(), "reason", reason));
            }
        }

        Renderer renderer = new Renderer(Renderer.mongolian(), clock, botUsername);
        registerCommandMenus(api, renderer, groups);
        OutboxSender sender = new OutboxSender(db, api, renderer, redactor, outboxSignal, clock, Duration.ofSeconds(30));
        UpdateHandler handler = new UpdateHandler(db, tasks, new Membership(groups, members, clock, outboxSignal::wake), groups, projects,
                api, renderer, redactor, botUsername, clock, outboxSignal::wake, workerKeys,
                config.workers() == null ? null : config.workers().publicUrl());
        Poller poller = new Poller(api, handler, 50, Duration.ofSeconds(1), Duration.ofMinutes(1));

        App[] app = new App[1];
        Scheduler scheduler = new Scheduler(db, config.scheduler().maxConcurrentRuns(), schedulerSignal, clock,
                run -> Thread.ofVirtual().name("run-" + run.taskId() + "." + run.seq())
                        .start(app[0].guarded(() -> coordinator.execute(run))),
                Duration.ofSeconds(5), config.workers() == null ? null : dispatch.store.Workers.SEEN_WITHIN);
        DraftExpiry draftExpiry = new DraftExpiry(db, tasks, clock, Duration.ofHours(24), Duration.ofMinutes(1));
        Sweeper sweeper = new Sweeper(db, projects, workspaces, clock, Duration.ofDays(config.worktrees().idleDays()), Duration.ofHours(1));
        app[0] = new App(db, poller, scheduler, sender, draftExpiry, sweeper, splitter[0], activeRuns, workerApi, onFatal);
        app[0].startThreads();
        Log.info("dispatch.started", "team", config.team(), "bot", botUsername, "task_topics", taskTopics, "groups", groups.all().size(),
                "projects", config.projects().size(), "state_dir", stateDir);
        return app[0];
    }

    /** Blocks until polling ends (after {@link #stop()}). */
    public void join() throws InterruptedException {
        pollerThread.join();
    }

    /** The port members' computers connect to; 0 in personal mode. Tests start with port 0 and ask afterwards. */
    public int workerPort() {
        return workerApi == null ? 0 : workerApi.port();
    }

    /** Graceful stop: no new updates or runs; active runs end as interrupted and are reported (ADR 0008). */
    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        Log.info("dispatch.stopping");
        poller.stop();
        pollerThread.interrupt();
        scheduler.stop();
        sweeper.stop();
        splitter.stop();
        try {
            schedulerThread.join(Duration.ofSeconds(10));
            activeRuns.stopAll(ActiveRuns.StopReason.INTERRUPTED);
            if (!activeRuns.awaitIdle(STOP_TIMEOUT)) {
                Log.warn("dispatch.runs_still_active", "waited_seconds", STOP_TIMEOUT.toSeconds());
            }
            if (workerApi != null) {
                // After runs are idle, so a worker still reporting a run's outcome during shutdown gets through first.
                workerApi.close();
            }
            draftExpiry.stop();
            draftExpiryThread.join(Duration.ofSeconds(10));
            sweeperThread.join(Duration.ofSeconds(10));
            sender.stop();
            senderThread.join(Duration.ofSeconds(10));
            pollerThread.join(Duration.ofSeconds(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        db.close();
        Log.info("dispatch.stopped");
    }

    private void startThreads() {
        pollerThread = Thread.ofVirtual().name("telegram-poller").start(guarded(poller));
        schedulerThread = Thread.ofVirtual().name("scheduler").start(guarded(scheduler));
        senderThread = Thread.ofVirtual().name("outbox-sender").start(guarded(sender));
        draftExpiryThread = Thread.ofVirtual().name("draft-expiry").start(guarded(draftExpiry));
        sweeperThread = Thread.ofVirtual().name("sweeper").start(guarded(sweeper));
    }

    /** A loop that dies unexpectedly would leave the instance half-working; report it as fatal instead. */
    private Runnable guarded(Runnable loop) {
        return () -> {
            try {
                loop.run();
            } catch (Throwable t) {
                if (stopping.get()) {
                    Log.warn("dispatch.error_while_stopping", "error", String.valueOf(t));
                } else {
                    onFatal.accept(t);
                }
            }
        };
    }

    private static java.util.Set<String> memberRefs(Groups groups) {
        return groups.all().stream().flatMap(group -> group.members().stream())
                .map(member -> "telegram:" + member.id()).collect(java.util.stream.Collectors.toSet());
    }

    /** Best effort: a group's menu fails while the bot is not yet in it, and works again on the next start. */
    private static void registerCommandMenus(BotApi api, Renderer renderer, Groups groups) {
        for (Config.Group group : groups.all()) {
            if (group.chatId() == null) {
                continue;
            }
            try {
                // Groups only read: tasks are given and cancelled privately (ADR 0012).
                api.setMyCommands(group.chatId(), commands(renderer, "status", "history", "stats", "projects", "help"));
            } catch (TelegramException e) {
                Log.warn("telegram.command_menu_failed", "group", group.name(), "chat_id", group.chatId(), "error", e.getMessage());
            }
        }
        try {
            api.setPrivateChatCommands(commands(renderer, "task", "status", "history", "stats", "cancel", "retry", "worker", "projects", "help"));
        } catch (TelegramException e) {
            Log.warn("telegram.command_menu_failed", "scope", "all_private_chats", "error", e.getMessage());
        }
    }

    private static List<BotApi.BotCommand> commands(Renderer renderer, String... names) {
        return java.util.Arrays.stream(names).map(name -> new BotApi.BotCommand(name, renderer.text("command." + name))).toList();
    }
}
