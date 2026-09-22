package dispatch.ui;

import dispatch.cli.Checks;
import dispatch.cli.CliException;
import dispatch.cli.Locations;
import dispatch.cli.RunCommand;
import dispatch.cli.Service;
import dispatch.config.ConfigException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** GET /api/overview: what Dispatch is, where its files are, whether its service runs and what `dispatch check` finds. */
public final class OverviewApi {

    public record ServiceView(String name, boolean installed, boolean running, String detail, List<String> notes) {
    }

    /** @param configured whether the config file exists; before setup, the page says how to set Dispatch up */
    public record Overview(String version, String configFile, String stateDir, boolean configured, ServiceView service,
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
        return new Overview(version, configFile.toString(), stateDir().toString(), Files.exists(configFile), serviceView, findings);
    }

    /** The service's status as the pages show it. */
    static ServiceView serviceView(Service service) {
        Service.Status status = service.status();
        return new ServiceView(service.describe(), status.installed(), status.running(), status.detail(), status.notes());
    }

    /** The config's state directory; the default one when there is no config yet, or it does not load (the checks say why). */
    private Path stateDir() {
        try {
            return RunCommand.prepare(configFile, processEnvironment).config().stateDir();
        } catch (CliException | ConfigException e) {
            return locations.stateDir();
        }
    }
}
