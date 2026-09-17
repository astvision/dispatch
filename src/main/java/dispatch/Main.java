package dispatch;

import dispatch.cli.CheckCommand;
import dispatch.cli.Cli;
import dispatch.cli.CliException;
import dispatch.cli.ConsoleTerminal;
import dispatch.cli.Locations;
import dispatch.cli.ProjectAddCommand;
import dispatch.cli.RunCommand;
import dispatch.config.ConfigException;
import dispatch.telegram.BotApi;
import java.nio.file.Path;
import java.time.Clock;

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
            case Cli.Help help -> System.out.print(Cli.usage(defaults));
            case Cli.Run run -> run(run.configFile());
            case Cli.Check check -> System.exit(new CheckCommand(new ConsoleTerminal(), BotApi::create).run(check.configFile(), System.getenv()));
            case Cli.ProjectAdd add -> System.exit(new ProjectAddCommand(new ConsoleTerminal()).run(add, System.getenv()));
        }
    }

    private static void run(Path configFile) throws InterruptedException {
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
            app = App.start(prepared.config(), BotApi.create(prepared.config().secrets().telegramBotToken()), prepared.environment(),
                    Clock.systemDefaultZone(), fatal -> {
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
}
