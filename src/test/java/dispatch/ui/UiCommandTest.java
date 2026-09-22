package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.cli.Cli;
import dispatch.cli.CliException;
import dispatch.cli.Locations;
import dispatch.cli.Service;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiCommandTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();

    @Test
    void itPrintsTheLinkAndServesTheOverview() throws Exception {
        try (UiServer server = command("/ui-test").start(new Cli.Ui(dir.resolve("dispatch.yaml"), 0, false), Map.of())) {
            String output = printed.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains(server.loginUri().toString()), output);
            assertTrue(output.contains("ssh -L " + server.port() + ":localhost:" + server.port()), output);

            HttpClient http = HttpClient.newHttpClient();
            String setCookie = http.send(HttpRequest.newBuilder(server.loginUri()).build(), HttpResponse.BodyHandlers.discarding())
                    .headers().firstValue("Set-Cookie").orElseThrow();
            HttpResponse<String> overview = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/overview"))
                    .header("Cookie", setCookie.substring(0, setCookie.indexOf(';'))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, overview.statusCode(), overview.body());
            assertTrue(overview.body().contains("\"configured\":false"), overview.body());

            HttpResponse<String> setupState = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/setup/state"))
                    .header("Cookie", setCookie.substring(0, setCookie.indexOf(';')))
                    .header("Origin", "http://127.0.0.1:" + server.port())
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, setupState.statusCode(), setupState.body());
            assertTrue(setupState.body().contains("\"configExists\":false"), setupState.body());
        }
    }

    @Test
    void theManagementRoutesAreServed() throws Exception {
        try (UiServer server = command("/ui-test").start(new Cli.Ui(dir.resolve("dispatch.yaml"), 0, false), Map.of())) {
            HttpClient http = HttpClient.newHttpClient();
            String setCookie = http.send(HttpRequest.newBuilder(server.loginUri()).build(), HttpResponse.BodyHandlers.discarding())
                    .headers().firstValue("Set-Cookie").orElseThrow();

            HttpResponse<String> config = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/manage/config"))
                    .header("Cookie", setCookie.substring(0, setCookie.indexOf(';')))
                    .header("Origin", "http://127.0.0.1:" + server.port())
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(400, config.statusCode(), config.body());
            assertTrue(config.body().contains("set Dispatch up first"), config.body());
        }
    }

    @Test
    void aBuildWithoutTheUiSaysHowToGetIt() {
        CliException e = assertThrows(CliException.class,
                () -> command("/no-such-ui").start(new Cli.Ui(dir.resolve("dispatch.yaml"), 0, false), Map.of()));

        assertTrue(e.getMessage().contains("no web UI") && e.getMessage().contains("-Pui"), e.getMessage());
    }

    @Test
    void aTakenPortNamesTheOption() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            CliException e = assertThrows(CliException.class,
                    () -> command("/ui-test").start(new Cli.Ui(dir.resolve("dispatch.yaml"), taken.getLocalPort(), false), Map.of()));

            assertTrue(e.getMessage().contains("--port"), e.getMessage());
        }
    }

    private UiCommand command(String resourceRoot) {
        return new UiCommand(new PrintStream(printed, true, StandardCharsets.UTF_8), token -> {
            throw new AssertionError("no config, so the bot is never asked");
        }, new Locations(dir.resolve("dispatch.yaml"), dir.resolve("state")), new NoService(), resourceRoot);
    }

    private static final class NoService implements Service {

        @Override
        public String describe() {
            return "no service";
        }

        @Override
        public void install(Service.Spec spec) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public Service.Status status() {
            return new Service.Status(false, false, "not installed", List.of());
        }

        @Override
        public void uninstall() {
        }
    }
}
