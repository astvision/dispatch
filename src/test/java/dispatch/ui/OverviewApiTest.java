package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.Json;
import dispatch.cli.Checks;
import dispatch.cli.Locations;
import dispatch.cli.Service;
import dispatch.telegram.BotApi;
import dispatch.testing.FakeTelegram;
import dispatch.testing.GitFixture;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverviewApiTest {

    // Split, so secret scanners never see a token-shaped literal in this file.
    private static final String TOKEN = "123456789" + ":AAH-fake-token-for-tests-only-0123456789";
    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    @TempDir
    Path dir;

    private FakeTelegram telegram;
    private Path config;

    @BeforeEach
    void setUp() throws IOException {
        telegram = FakeTelegram.start();
        config = dir.resolve("dispatch.yaml");
    }

    @AfterEach
    void tearDown() {
        telegram.close();
    }

    @Test
    void aWorkingSetupShowsItsServiceAndChecks() throws IOException {
        GitFixture repos = GitFixture.create(dir, "alm");
        String yaml = new String(OverviewApiTest.class.getResourceAsStream("/personal.yaml").readAllBytes())
                .replace("STATE_DIR", dir.resolve("state").toString().replace("'", "''"))
                .replace("CLONE", repos.repo("alm").toString().replace("'", "''"))
                .replace("command: 'claude'", "command: '" + JAVA.replace("'", "''") + "'");
        Files.writeString(config, yaml);

        OverviewApi.Overview overview = api(new StubService(true, true, "active (running)"), Map.of("TELEGRAM_BOT_TOKEN", TOKEN)).get();

        assertTrue(overview.configured());
        assertEquals("0.1.0-test", overview.version());
        assertEquals(config.toString(), overview.configFile());
        assertEquals(dir.resolve("state").toString(), overview.stateDir(), "the config's state directory");
        assertEquals(new OverviewApi.ServiceView("stub service", true, true, "active (running)", List.of("a note")), overview.service());
        assertTrue(overview.findings().stream().anyMatch(f -> f.area().equals("bot") && f.level() == Checks.Level.OK), overview.findings().toString());
        String json = Json.write(overview);
        assertTrue(json.contains("\"level\":\"OK\""), json);
        assertFalse(json.contains(TOKEN), "the bot token never reaches the browser");
    }

    @Test
    void beforeSetupItSaysSoAndShowsWhereThingsWillGo() {
        OverviewApi.Overview overview = api(new StubService(false, false, "not installed"), Map.of()).get();

        assertFalse(overview.configured());
        assertEquals(dir.resolve("state/dispatch").toString(), overview.stateDir(), "the default state directory");
        assertEquals(Checks.Level.FAIL, overview.findings().getFirst().level());
        assertFalse(overview.service().installed());
    }

    private OverviewApi api(Service service, Map<String, String> processEnvironment) {
        return new OverviewApi(config, new Locations(config, dir.resolve("state/dispatch")),
                new Checks(token -> new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5))),
                service, processEnvironment, "0.1.0-test");
    }

    /** A background service that only reports what it was given. */
    private record StubService(boolean installed, boolean running, String detail) implements Service {

        @Override
        public String describe() {
            return "stub service";
        }

        @Override
        public void install(Service.Spec spec) {
            throw new AssertionError("the overview only reads");
        }

        @Override
        public void start() {
            throw new AssertionError("the overview only reads");
        }

        @Override
        public void stop() {
            throw new AssertionError("the overview only reads");
        }

        @Override
        public Service.Status status() {
            return new Service.Status(installed, running, detail, List.of("a note"));
        }

        @Override
        public void uninstall() {
            throw new AssertionError("the overview only reads");
        }
    }
}
