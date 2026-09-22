package dispatch.cli;

import dispatch.telegram.BotApi;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * `dispatch check`: prints each of {@link Checks}' findings as it is found. Exit code 1 when anything would stop tasks from
 * running; warnings do not count.
 */
public final class CheckCommand {

    private final Terminal terminal;
    private final Checks checks;

    /** @param bots the Telegram client for a bot token */
    public CheckCommand(Terminal terminal, Function<String, BotApi> bots) {
        this.terminal = terminal;
        this.checks = new Checks(bots);
    }

    public int run(Path configFile, Map<String, String> processEnvironment) {
        List<Checks.Finding> findings = checks.run(configFile, processEnvironment, finding -> {
            switch (finding.level()) {
                case OK -> terminal.ok(finding.message());
                case WARN -> terminal.warn(finding.message());
                case FAIL -> terminal.fail(finding.message());
            }
        });
        return Checks.failed(findings) ? 1 : 0;
    }
}
