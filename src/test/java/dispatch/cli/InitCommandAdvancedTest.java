package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.Json;
import dispatch.config.Config;
import dispatch.config.ConfigLoader;
import dispatch.telegram.BotApi;
import dispatch.testing.FakeTelegram;
import dispatch.testing.GitFixture;
import java.io.IOException;
import java.math.BigDecimal;
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

/** `dispatch init --advanced`; InitCommandTest covers the quick setup, unchanged. */
class InitCommandAdvancedTest {

    // Split, so secret scanners never see a token-shaped literal in this file.
    private static final String TOKEN = "123456789" + ":AAH-fake-token-for-tests-only-0123456789";
    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    @TempDir
    Path dir;

    private FakeTelegram telegram;
    private GitFixture repos;
    private Path config;
    private Locations locations;

    @BeforeEach
    void setUp() throws IOException {
        telegram = FakeTelegram.start();
        repos = GitFixture.create(dir, "alm");
        config = dir.resolve("config/dispatch.yaml");
        locations = new Locations(config, dir.resolve("state/dispatch"));
    }

    @AfterEach
    void tearDown() {
        telegram.close();
    }

    @Test
    void advancedSetupAsksForPerPhaseSettingsLimitsAndTheRest() throws IOException {
        telegram.pushUpdate(start(1, 100, "Bold"));
        ScriptedTerminal terminal = new ScriptedTerminal(
                "", TOKEN, "y", JAVA,               // just me, the token, Bold is me, claude
                repos.repo("alm").toString(),       // a project
                "", "", "Opus", "",                 // name, base branch, model for both phases, effort: default
                "almy",                             // alias
                "Fable", "",                        // planning: its own model, the same effort
                "", "Max",                          // execution: the same model, its own effort
                "",                                 // add another project? no
                "", "bold@example.com",             // commit author name and email
                "30m", "",                          // planning timeout, budget: default
                "", "12.5",                         // execution timeout: default, budget
                "3",                                // concurrent runs
                "",                                 // state directory: default
                "/opt/gh/bin/gh",                   // gh command
                "",                                 // write this setup? yes
                "n");                               // background service? no

        int exit = new InitCommand(terminal, token -> new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5)),
                locations, Duration.ofSeconds(10), new ServiceCommand(terminal, new NoService(), null))
                .run(new Cli.Init(config, false, true), Map.of("PATH", ""));

        assertEquals(0, exit, terminal.output());
        Config written = ConfigLoader.load(config, SecretsFile.environment(config, Map.of()));
        Config.Project project = written.projects().getFirst();
        assertEquals("almy", project.alias());
        assertEquals("fable", project.planModel());
        assertEquals("opus", project.executeModel(), "execution keeps the model chosen for both phases");
        assertEquals("max", project.executeEffort());
        assertEquals(new Config.RunLimits(Duration.ofMinutes(30), new BigDecimal("2")), written.limits().plan());
        assertEquals(new Config.RunLimits(Duration.ofMinutes(60), new BigDecimal("12.5")), written.limits().execute());
        assertEquals(3, written.scheduler().maxConcurrentRuns());
        assertEquals(locations.stateDir().toAbsolutePath(), written.stateDir());
        assertEquals("/opt/gh/bin/gh", written.delivery().ghCommand());
        assertTrue(terminal.output().contains("STEP Advanced"), terminal.output());
    }

    @Test
    void aTimeoutThatIsNotADurationIsAskedAgain() throws IOException {
        telegram.pushUpdate(start(1, 100, "Bold"));
        ScriptedTerminal terminal = new ScriptedTerminal("", TOKEN, "y", JAVA, repos.repo("alm").toString(), "", "", "", "",
                "", "", "", "", "",                 // alias, planning and execution: all as chosen
                "",                                 // add another project? no
                "", "bold@example.com",
                "soon", "20m",                      // not a duration, then one
                "", "", "", "", "", "", "", "n");

        int exit = new InitCommand(terminal, token -> new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5)),
                locations, Duration.ofSeconds(10), new ServiceCommand(terminal, new NoService(), null))
                .run(new Cli.Init(config, false, true), Map.of("PATH", ""));

        assertEquals(0, exit, terminal.output());
        assertTrue(terminal.output().contains("WARN invalid duration 'soon'"), terminal.output());
        String yaml = Files.readString(config);
        assertTrue(yaml.contains("    timeout: 20m\n"), yaml);
        assertTrue(!yaml.contains("alias:") && !yaml.contains("plan:\n      "), "nothing per phase was chosen: " + yaml);
    }

    @Test
    void anInvalidStateDirectoryIsAskedAgainInsteadOfCrashing() throws IOException {
        telegram.pushUpdate(start(1, 100, "Bold"));
        ScriptedTerminal terminal = new ScriptedTerminal("", TOKEN, "y", JAVA, repos.repo("alm").toString(), "", "", "", "",
                "", "", "", "", "",                 // alias, planning and execution: all as chosen
                "",                                 // add another project? no
                "", "bold@example.com",
                "", "", "", "", "",                 // planning timeout/budget, execution timeout/budget, runs: default
                "bad\u0000path", "",                // state directory: a NUL character (invalid), then the default
                "",                                 // gh command: default
                "",                                 // write this setup? yes
                "n");                               // background service? no

        int exit = new InitCommand(terminal, token -> new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5)),
                locations, Duration.ofSeconds(10), new ServiceCommand(terminal, new NoService(), null))
                .run(new Cli.Init(config, false, true), Map.of("PATH", ""));

        assertEquals(0, exit, terminal.output());
        assertTrue(terminal.output().contains("WARN not a valid path"), terminal.output());
        assertFalse(terminal.output().contains("Exception"), "a clean re-ask, not a raw stack trace: " + terminal.output());
        Config written = ConfigLoader.load(config, SecretsFile.environment(config, Map.of()));
        assertEquals(locations.stateDir().toAbsolutePath(), written.stateDir(), "the default was used once the bad answer was rejected");
    }

    private static final class NoService implements Service {

        @Override
        public String describe() {
            return "test service";
        }

        @Override
        public void install(Service.Spec spec) {
            throw new AssertionError("not asked for");
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

    private static com.fasterxml.jackson.databind.JsonNode start(long updateId, long userId, String firstName) {
        return Json.read("""
                {"update_id":%d,"message":{"message_id":1,"from":{"id":%d,"is_bot":false,"first_name":"%s"},
                 "chat":{"id":%d,"type":"private"},"date":1789640000,"text":"/start"}}""".formatted(updateId, userId, firstName, userId));
    }
}
