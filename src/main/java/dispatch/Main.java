package dispatch;

import dispatch.config.Config;
import dispatch.config.ConfigException;
import dispatch.config.ConfigLoader;
import dispatch.telegram.BotApi;
import java.nio.file.Path;
import java.time.Clock;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        // Before anything is logged: every log line from here on has this process's secrets masked.
        Redactor redactor = Redactor.fromEnvironment(System.getenv());
        Log.useRedactor(redactor);
        if (args.length != 1) {
            System.err.println("usage: java -jar dispatch.jar <config.yaml>");
            System.exit(2);
        }
        Config config;
        try {
            config = ConfigLoader.load(Path.of(args[0]), System.getenv());
        } catch (ConfigException e) {
            System.err.println(redactor.redact(e.getMessage()));
            Log.error("config.invalid", null, "detail", e.getMessage());
            System.exit(2);
            return;
        }

        App app;
        try {
            // Instants stay UTC everywhere; the zone (TZ) only affects clock times shown in chat.
            app = App.start(config, BotApi.create(config.secrets().telegramBotToken()), System.getenv(), Clock.systemDefaultZone(),
                    fatal -> {
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
