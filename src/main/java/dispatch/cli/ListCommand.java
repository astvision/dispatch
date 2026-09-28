package dispatch.cli;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** {@code dispatch list}: every instance set up on this computer. Offline and fast: never calls Telegram (M). */
public final class ListCommand {

    private final PrintStream out;
    private final Function<String, Service> services;

    public ListCommand(PrintStream out, Function<String, Service> services) {
        this.out = out;
        this.services = services;
    }

    public int run(Locations defaults, Map<String, String> env) {
        List<Instances.Found> found = Instances.discover(defaults, env);
        if (found.isEmpty()) {
            out.println("no Dispatch set up on this computer yet: dispatch init");
            return 0;
        }
        found.forEach(this::print);
        return 0;
    }

    private void print(Instances.Found found) {
        String name = found.name() == null ? "default" : found.name();
        if (found.error() != null) {
            out.println(name + "  (does not load: " + firstLine(found.error()) + ")");
            return;
        }
        String bot = "bot " + Instances.botId(found.config().secrets().telegramBotToken());
        String mode = found.config().isTeam() ? "team" : "personal";
        out.printf("%-10s %-22s %-9s %s%n", name, bot, mode, serviceState(found.name()));
    }

    private String serviceState(String instance) {
        Service.Status status = services.apply(instance).status();
        String suffix = instance == null ? "" : " --instance " + instance;
        if (!status.installed()) {
            return "no service  (dispatch service install" + suffix + ")";
        }
        return status.running() ? "running" : "stopped   (dispatch service start" + suffix + ")";
    }

    private static String firstLine(String error) {
        int newline = error.indexOf('\n');
        return newline < 0 ? error : error.substring(0, newline);
    }
}
