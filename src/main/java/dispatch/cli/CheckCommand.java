package dispatch.cli;

import dispatch.telegram.BotApi;
import dispatch.worker.WorkerChecks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /**
     * Checks whichever sides this machine has: a member's computer (worker.yaml beside the config file), the team's
     * own instance (dispatch.yaml), or both. A machine that only has a worker is never told to run dispatch init.
     */
    public int run(Path configFile, Map<String, String> processEnvironment) {
        Path workerFile = configFile.resolveSibling("worker.yaml");
        List<Checks.Finding> findings = new ArrayList<>();
        if (Files.exists(workerFile)) {
            findings.addAll(WorkerChecks.run(workerFile, processEnvironment, this::show));
        }
        if (Files.exists(configFile) || findings.isEmpty()) {
            findings.addAll(checks.run(configFile, processEnvironment, this::show));
        }
        return Checks.failed(findings) ? 1 : 0;
    }

    private void show(Checks.Finding finding) {
        switch (finding.level()) {
            case OK -> terminal.ok(finding.message());
            case WARN -> terminal.warn(finding.message());
            case FAIL -> terminal.fail(finding.message());
        }
    }
}
