package dispatch;

import dispatch.cli.AskCommand;
import dispatch.cli.TeleportCommand;
import dispatch.cli.CheckCommand;
import dispatch.cli.Cli;
import dispatch.cli.CliException;
import dispatch.cli.JLineTerminal;
import dispatch.cli.InitCommand;
import dispatch.cli.ListCommand;
import dispatch.cli.Locations;
import dispatch.cli.ProjectAddCommand;
import dispatch.cli.Service;
import dispatch.cli.ServiceCommand;
import dispatch.cli.RunCommand;
import dispatch.config.ConfigException;
import dispatch.config.MemberWriter;
import dispatch.telegram.BotApi;
import dispatch.ui.UiCommand;
import dispatch.ui.UiServer;
import dispatch.worker.WorkerCommand;
import dispatch.worker.WorkerInitCommand;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        // Before anything is logged: every log line from here on has this process's secrets masked.
        Log.useRedactor(Redactor.fromEnvironment(System.getenv()));
        Locations defaults = Locations.current();
        Cli.Invocation invocation;
        try {
            invocation = Cli.parse(args, defaults);
        } catch (CliException e) {
            System.err.println(e.getMessage() + "\n");
            System.err.print(Cli.usage(defaults));
            System.exit(2);
            return;
        }
        switch (invocation) {
            case Cli.Help _ -> System.out.print(Cli.usage(defaults));
            case Cli.ListInstances _ -> System.exit(new ListCommand(System.out,
                    instance -> Service.forThisMachine(Service.Kind.DISPATCH, instance)).run(defaults, System.getenv()));
            case Cli.Run run -> run(run.configFile(), run.logFile(), run.instance() != null ? run.instance()
                    // A unit installed before it passed --instance: the instance as the config file's name says.
                    : defaults.instanceOf(run.configFile()));
            case Cli.Init init -> {
                JLineTerminal terminal = JLineTerminal.system();
                System.exit(new InitCommand(terminal, BotApi::create, defaults, Duration.ofMinutes(3),
                        instance -> ServiceCommand.forThisMachine(terminal, instance)).run(init, System.getenv()));
            }
            case Cli.Service service -> {
                JLineTerminal terminal = JLineTerminal.system();
                System.exit(ServiceCommand.forThisMachine(terminal, service.instance()).run(service, System.getenv()));
            }
            case Cli.Check check -> System.exit(new CheckCommand(JLineTerminal.system(), BotApi::create, defaults)
                    .run(check.configFile(), System.getenv()));
            case Cli.ProjectAdd add -> System.exit(new ProjectAddCommand(JLineTerminal.system()).run(add, System.getenv()));
            case Cli.Ui ui -> ui(ui, defaults);
            case Cli.WorkerInit init -> {
                JLineTerminal terminal = JLineTerminal.system();
                System.exit(new WorkerInitCommand(terminal, defaults, new ServiceCommand(terminal,
                        Service.forThisMachine(Service.Kind.WORKER), ServiceCommand.runningJar()))
                        .run(init, System.getenv()));
            }
            case Cli.Ask ask -> System.exit(AskCommand.run(ask, System.getenv(), System.out));
            case Cli.Teleport teleport -> System.exit(TeleportCommand.run(teleport, System.getenv()));
            case Cli.WorkerPair pair -> System.exit(new WorkerCommand(System.out).pair(pair));
            case Cli.WorkerRun worker -> {
                redirect(worker.logFile());
                System.exit(new WorkerCommand(System.out).run(worker, System.getenv()));
            }
            case Cli.WorkerService service -> System.exit(WorkerCommand.service(JLineTerminal.system(), service,
                    System.getenv()));
        }
    }

    private static void ui(Cli.Ui options, Locations defaults) throws InterruptedException {
        UiServer server;
        UiCommand command = new UiCommand(System.out, BotApi::create, defaults.forInstance(options.instance()),
                Service.forThisMachine(Service.Kind.DISPATCH, options.instance()), "/ui");
        try {
            server = command.start(options, System.getenv());
        } catch (CliException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            command.forget(server);
        }, "dispatch-ui-shutdown"));
        if (options.idleMinutes() == 0) {
            new CountDownLatch(1).await(); // until Ctrl+C
            return;
        }
        // Started by the bot for the Mini App (ADR 0018): an open page asks every few seconds, so a quiet server is unused.
        java.time.Duration idle = java.time.Duration.ofMinutes(options.idleMinutes());
        while (java.time.Duration.between(server.lastRequestAt(), java.time.Instant.now()).compareTo(idle) < 0) {
            Thread.sleep(60_000);
        }
        System.out.println("Dispatch UI: no request for " + options.idleMinutes() + " minutes, stopping.");
        System.exit(0);
    }

    /** @param instance the instance this bot is, null for the default one */
    private static void run(Path configFile, Path logFile, String instance) throws InterruptedException {
        redirect(logFile);
        RunCommand.Prepared prepared;
        try {
            prepared = RunCommand.prepare(configFile, System.getenv());
        } catch (CliException | ConfigException e) {
            System.err.println(Redactor.fromEnvironment(System.getenv()).redact(e.getMessage()));
            Log.error("config.invalid", null, "detail", e.getMessage());
            System.exit(2);
            return;
        }
        // The secrets file's values are masked too.
        Log.useRedactor(Redactor.fromEnvironment(prepared.environment()));

        App app;
        try {
            // Instants stay UTC everywhere; the zone (TZ) only affects clock times shown in chat.
            app = App.start(prepared.config(), configFile, instance, MemberWriter.file(configFile, prepared.environment()),
                    BotApi.create(prepared.config().secrets().telegramBotToken()), prepared.environment(), Clock.systemDefaultZone(), fatal -> {
                        Log.error("dispatch.fatal", fatal);
                        System.exit(1);
                    });
        } catch (RuntimeException e) {
            Log.error("dispatch.start_failed", e);
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "dispatch-shutdown"));
        app.join();
    }

    /** A background service has no terminal: everything that would be shown goes to the log file instead. */
    private static void redirect(Path logFile) {
        if (logFile == null) {
            return;
        }
        try {
            Files.createDirectories(logFile.toAbsolutePath().getParent());
            PrintStream log = new PrintStream(new FileOutputStream(logFile.toFile(), true), true, StandardCharsets.UTF_8);
            System.setOut(log);
            System.setErr(log);
        } catch (IOException e) {
            System.err.println("cannot write the log file " + logFile + ": " + e.getMessage());
            System.exit(2);
        }
    }
}
