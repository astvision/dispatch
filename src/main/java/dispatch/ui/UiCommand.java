package dispatch.ui;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.cli.Cli;
import dispatch.cli.CliException;
import dispatch.cli.Locations;
import dispatch.cli.Service;
import dispatch.cli.ServiceCommand;
import dispatch.telegram.BotApi;
import java.awt.Desktop;
import java.io.IOException;
import java.io.PrintStream;
import java.net.BindException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/** `dispatch ui`: serves the web UI on 127.0.0.1 and prints its one-time login link (ADR 0018). */
public final class UiCommand {

    private final PrintStream out;
    private final Function<String, BotApi> bots;
    private final Locations locations;
    private final Service service;
    private final String resourceRoot;

    /** @param resourceRoot the classpath folder of the bundled pages: "/ui" in the release jar */
    public UiCommand(PrintStream out, Function<String, BotApi> bots, Locations locations, Service service, String resourceRoot) {
        this.out = out;
        this.bots = bots;
        this.locations = locations;
        this.service = service;
        this.resourceRoot = resourceRoot;
    }

    /** The running server; the caller keeps the process alive and closes it. */
    public UiServer start(Cli.Ui options, Map<String, String> processEnvironment) {
        if (!UiServer.hasUi(resourceRoot)) {
            throw new CliException("this build has no web UI; install the release jar, or build it with the UI: "
                    + "(cd ui && npm ci && npm run build) && ./mvnw -Pui package");
        }
        Path configFile = options.configFile().toAbsolutePath();
        if (options.instance() != null && !Files.exists(configFile)) {
            throw new CliException("no bot named " + options.instance() + " on this computer yet; set it up first with: "
                    + "dispatch init --instance " + options.instance());
        }
        UiRoutes management = UiRoutes.management(configFile, locations, bots, service, processEnvironment, version(), null);
        UiServer server;
        try {
            SetupApi setup = new SetupApi(configFile, locations, bots, service, ServiceCommand.runningJar(), processEnvironment);
            Map<String, BiFunction<UiServer.Caller, JsonNode, Object>> postRoutes =
                    new HashMap<>(UiRoutes.anyCaller(setup.routes(), false));
            postRoutes.putAll(management.post(false));
            server = startOn(options, postRoutes, management);
        } catch (BindException e) {
            throw new CliException("port " + options.port() + " is in use; choose another with --port");
        } catch (IOException e) {
            throw new CliException("cannot start the web UI: " + e.getMessage());
        }
        // Printed, never logged: whoever has the link can use Dispatch as you.
        int port = server.port();
        out.println("Dispatch UI: " + server.loginUri());
        out.println("  The link works once. Keep this running; Ctrl+C stops it.");
        out.println("  On a server? From your computer: ssh -L " + port + ":localhost:" + port + " SERVER, then open the link there.");
        if (options.openBrowser()) {
            openBrowser(server.loginUri());
        }
        return server;
    }

    /** An explicit --port is kept or refused; the default one gives way to the next free port (another instance's UI may hold it). */
    private UiServer startOn(Cli.Ui options, Map<String, BiFunction<UiServer.Caller, JsonNode, Object>> post, UiRoutes management)
            throws IOException {
        int last = options.portGiven() ? options.port() : Math.min(65535, options.port() + 20);
        for (int port = options.port(); ; port++) {
            try {
                return UiServer.start(port, resourceRoot, management.get(false), post);
            } catch (BindException e) {
                if (port >= last) {
                    throw e;
                }
            }
        }
    }

    private void openBrowser(URI link) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(link);
                return;
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // falls through: the link is printed above
        }
        out.println("  No browser to open here: open the link yourself.");
    }

    private static String version() {
        return Optional.ofNullable(UiCommand.class.getPackage().getImplementationVersion()).orElse("dev");
    }
}
