package dispatch.ui;

import dispatch.Text;
import dispatch.cli.Checks;
import dispatch.cli.CliException;
import dispatch.cli.Locations;
import dispatch.cli.RunCommand;
import dispatch.cli.Service;
import dispatch.config.Config;
import dispatch.config.ConfigException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** GET /api/overview: what Dispatch is, where its files are, whether its service runs and what `dispatch check` finds. */
public final class OverviewApi {

    public record ServiceView(String name, boolean installed, boolean running, Text detail, List<Text> notes) {
    }

    /**
     * @param name       the config's team, which names the instance on the strip (D-2); null before setup
     * @param configured whether the config file exists; before setup, the page says how to set Dispatch up
     */
    public record Overview(String version, String name, String configFile, String stateDir, boolean configured, ServiceView service,
                           List<Checks.Finding> findings) {
    }

    private final Path configFile;
    private final Locations locations;
    private final Checks checks;
    private final Service service;
    private final Map<String, String> processEnvironment;
    private final String version;

    public OverviewApi(Path configFile, Locations locations, Checks checks, Service service, Map<String, String> processEnvironment,
                       String version) {
        this.configFile = configFile;
        this.locations = locations;
        this.checks = checks;
        this.service = service;
        this.processEnvironment = processEnvironment;
        this.version = version;
    }

    public Overview get() {
        ServiceView serviceView = serviceView(service);
        List<Checks.Finding> findings = checks.run(configFile, processEnvironment, finding -> { });
        Optional<Config> config = config();
        Path stateDir = config.map(Config::stateDir).orElseGet(locations::stateDir);
        return new Overview(version, config.map(Config::team).orElse(null), configFile.toString(), stateDir.toString(),
                Files.exists(configFile), serviceView, findings);
    }

    /** The service's status as the pages show it. */
    static ServiceView serviceView(Service service) {
        Service.Status status = service.status();
        return new ServiceView(service.describe(), status.installed(), status.running(), status.detail(), status.notes());
    }

    /** The config as it now loads; empty when there is none yet, or it does not load (the checks say why). */
    private Optional<Config> config() {
        try {
            return Optional.of(RunCommand.prepare(configFile, processEnvironment).config());
        } catch (CliException | ConfigException e) {
            return Optional.empty();
        }
    }
}
