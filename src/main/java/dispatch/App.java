package dispatch;

import dispatch.agent.Agent;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.DraftExpiry;
import dispatch.core.Groups;
import dispatch.core.Projects;
import dispatch.core.Recovery;
import dispatch.core.RunExecutor;
import dispatch.core.RunTransitions;
import dispatch.core.Scheduler;
import dispatch.core.Signal;
import dispatch.core.TaskService;
import dispatch.store.Database;
import dispatch.telegram.BotApi;
import dispatch.telegram.OutboxSender;
import dispatch.telegram.Poller;
import dispatch.telegram.Renderer;
import dispatch.telegram.TelegramException;
import dispatch.telegram.UpdateHandler;
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
    private final ActiveRuns activeRuns;
    private final Consumer<Throwable> onFatal;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private Thread pollerThread;
    private Thread schedulerThread;
    private Thread senderThread;
    private Thread draftExpiryThread;

    private App(Database db, Poller poller, Scheduler scheduler, OutboxSender sender, DraftExpiry draftExpiry, ActiveRuns activeRuns,
                Consumer<Throwable> onFatal) {
        this.db = db;
        this.poller = poller;
        this.scheduler = scheduler;
        this.sender = sender;
        this.draftExpiry = draftExpiry;
        this.activeRuns = activeRuns;
        this.onFatal = onFatal;
    }

    /**
     * Opens state, checks the bot token, recovers runs interrupted by a previous process, then starts polling, scheduling
     * and sending. {@code onFatal} receives errors that stop a core loop (e.g. storage failures).
     */
    public static App start(Config config, BotApi api, Map<String, String> environment, Clock clock, Consumer<Throwable> onFatal) {
        Path stateDir = config.stateDir();
        Git git = new Git("git", config.secrets().ghToken(), Duration.ofMinutes(5));
        Workspaces workspaces = new Workspaces(stateDir, git);
        Delivery delivery = new Delivery(git, new Gh(config.delivery().ghCommand(), config.secrets().ghToken(), Duration.ofMinutes(2)),
                config.delivery().authorName(), config.delivery().authorEmail());
        Redactor redactor = Redactor.fromEnvironment(environment);
        workspaces.createDirectories().ifPresent(warning -> Log.warn("state.permissions_too_open", "detail", warning));
        Database db = Database.open(stateDir.resolve("dispatch.db"));
        db.migrate();
        String botUsername = api.getMe().path("username").asText();

        Signal schedulerSignal = new Signal();
        Signal outboxSignal = new Signal();
        Projects projects = new Projects(config.projects(), workspaces::unavailableReason);
        ActiveRuns activeRuns = new ActiveRuns();
        RunTransitions transitions = new RunTransitions(db, clock, outboxSignal::wake);
        Groups groups = new Groups(config.telegram().groups());
        TaskService tasks = new TaskService(groups, projects, activeRuns, clock,
                schedulerSignal::wake, outboxSignal::wake);
        Map<String, Agent> agents = Map.of("claude-code",
                new ClaudeCodeAgent(config.agents().get("claude-code").command(), environment, Duration.ofSeconds(10)));
        RunExecutor executor = new RunExecutor(db, projects, workspaces, delivery, agents, transitions, activeRuns,
                config::planLimits, config::executeLimits, redactor, schedulerSignal::wake);

        new Recovery(db, transitions, Duration.ofSeconds(10)).run();
        projects.all().forEach(project -> projects.unavailableReason(project).ifPresent(reason ->
                Log.error("project.unavailable", null, "project", project.name(), "reason", reason)));

        Renderer renderer = new Renderer(Renderer.mongolian(), clock, botUsername);
        registerCommandMenus(api, renderer, groups);
        OutboxSender sender = new OutboxSender(db, api, renderer, redactor, outboxSignal, clock, Duration.ofSeconds(30));
        UpdateHandler handler = new UpdateHandler(db, tasks, groups, projects, api, renderer, redactor, botUsername, clock,
                outboxSignal::wake);
        Poller poller = new Poller(api, handler, 50, Duration.ofSeconds(1), Duration.ofMinutes(1));

        App[] app = new App[1];
        Scheduler scheduler = new Scheduler(db, config.scheduler().maxConcurrentRuns(), schedulerSignal, clock,
                run -> Thread.ofVirtual().name("run-" + run.taskId() + "." + run.seq())
                        .start(app[0].guarded(() -> executor.execute(run))),
                Duration.ofSeconds(5));
        DraftExpiry draftExpiry = new DraftExpiry(db, tasks, clock, Duration.ofHours(24), Duration.ofMinutes(1));
        app[0] = new App(db, poller, scheduler, sender, draftExpiry, activeRuns, onFatal);
        app[0].startThreads();
        Log.info("dispatch.started", "team", config.team(), "bot", botUsername, "groups", groups.all().size(),
                "projects", config.projects().size(), "state_dir", stateDir);
        return app[0];
    }

    /** Blocks until polling ends (after {@link #stop()}). */
    public void join() throws InterruptedException {
        pollerThread.join();
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
        try {
            schedulerThread.join(Duration.ofSeconds(10));
            activeRuns.stopAll(ActiveRuns.StopReason.INTERRUPTED);
            if (!activeRuns.awaitIdle(STOP_TIMEOUT)) {
                Log.warn("dispatch.runs_still_active", "waited_seconds", STOP_TIMEOUT.toSeconds());
            }
            draftExpiry.stop();
            draftExpiryThread.join(Duration.ofSeconds(10));
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

    /** Best effort: a group's menu fails while the bot is not yet in it, and works again on the next start. */
    private static void registerCommandMenus(BotApi api, Renderer renderer, Groups groups) {
        for (Config.Group group : groups.all()) {
            try {
                // Groups only read: tasks are given and cancelled privately (ADR 0012).
                api.setMyCommands(group.chatId(), commands(renderer, "status", "history", "help"));
            } catch (TelegramException e) {
                Log.warn("telegram.command_menu_failed", "group", group.name(), "chat_id", group.chatId(), "error", e.getMessage());
            }
        }
        try {
            api.setPrivateChatCommands(commands(renderer, "task", "status", "history", "cancel", "help"));
        } catch (TelegramException e) {
            Log.warn("telegram.command_menu_failed", "scope", "all_private_chats", "error", e.getMessage());
        }
    }

    private static List<BotApi.BotCommand> commands(Renderer renderer, String... names) {
        return java.util.Arrays.stream(names).map(name -> new BotApi.BotCommand(name, renderer.text("command." + name))).toList();
    }
}
