package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CliTest {

    private static final Locations DEFAULTS = Locations.of("Linux", Map.of(), Path.of("home", "bold"));
    private static final Path DEFAULT_CONFIG = DEFAULTS.configFile();

    @Test
    void withoutArgumentsTheBotRunsFromTheDefaultConfig() {
        assertEquals(new Cli.Run(DEFAULT_CONFIG, null), parse());
        assertEquals(new Cli.Run(DEFAULT_CONFIG, null), parse("run"));
        assertEquals(new Cli.Run(Path.of("team.yaml"), null), parse("run", "--config", "team.yaml"));
    }

    @Test
    void aConfigFileAloneRunsTheBotAsTheSystemdUnitDoes() {
        assertEquals(new Cli.Run(Path.of("/etc/dispatch/backend.yaml"), null), parse("/etc/dispatch/backend.yaml"));
    }

    @Test
    void checkLooksAtTheDefaultOrAGivenConfig() {
        assertEquals(new Cli.Check(DEFAULT_CONFIG), parse("check"));
        assertEquals(new Cli.Check(Path.of("team.yaml")), parse("check", "--config", "team.yaml"));
    }

    @Test
    void projectAddTakesTheCloneAndOptionalSettings() {
        assertEquals(new Cli.ProjectAdd(DEFAULT_CONFIG, Path.of("work/alm"), "alm", "a", "develop", "opus", "high", "backend"),
                parse("project", "add", "work/alm", "--name", "alm", "--alias", "a", "--base", "develop", "--model", "opus",
                        "--effort", "high", "--group", "backend"));
        assertEquals(new Cli.ProjectAdd(DEFAULT_CONFIG, Path.of("work/alm"), null, null, null, null, null, null),
                parse("project", "add", "work/alm"));
        assertTrue(error("project", "add").contains("project add needs the folder of a git clone"));
        assertTrue(error("project", "remove", "alm").contains("unknown command 'project remove'"));
    }

    @Test
    void initWritesTheDefaultOrAGivenConfig() {
        assertEquals(new Cli.Init(DEFAULT_CONFIG, false), parse("init"));
        assertEquals(new Cli.Init(Path.of("mine.yaml"), true), parse("init", "--force", "--config", "mine.yaml"));
    }

    @Test
    void initAsksTheAdvancedQuestionsOnlyWhenAskedTo() {
        assertEquals(new Cli.Init(DEFAULT_CONFIG, false, true), parse("init", "--advanced"));
        assertEquals(false, parse("init") instanceof Cli.Init init && init.advanced());
        assertTrue(error("check", "--advanced").contains("check does not take --advanced"));
    }

    @Test
    void serviceActionsAndTheRunLogFile() {
        assertEquals(new Cli.Service(DEFAULT_CONFIG, "install"), parse("service", "install"));
        assertEquals(new Cli.Service(Path.of("team.yaml"), "status"), parse("service", "status", "--config", "team.yaml"));
        assertEquals(new Cli.Run(DEFAULT_CONFIG, Path.of("dispatch.log")), parse("run", "--log-file", "dispatch.log"));
        assertTrue(error("service", "restart").contains("service needs one of: install, start, stop, status, uninstall"));
    }

    @Test
    void helpIsShownOnRequest() {
        assertEquals(new Cli.Help(), parse("--help"));
        assertEquals(new Cli.Help(), parse("help"));
        assertTrue(Cli.usage(DEFAULTS).contains(DEFAULT_CONFIG.toString()), "the help names the default config file");
    }

    @Test
    void uiDefaultsToPort7878AndOpensTheBrowser() {
        assertEquals(new Cli.Ui(DEFAULT_CONFIG, 7878, true), parse("ui"));
    }

    @Test
    void uiTakesAPortAndCanLeaveTheBrowserClosed() {
        assertEquals(new Cli.Ui(Path.of("x.yaml"), 9000, false), parse("ui", "--port", "9000", "--no-browser", "--config", "x.yaml"));
    }

    @Test
    void uiRefusesAPortThatIsNotOne() {
        CliException e = assertThrows(CliException.class, () -> parse("ui", "--port", "70000"));
        assertTrue(e.getMessage().contains("--port"), e.getMessage());
    }

    @Test
    void mistakesAreExplained() {
        assertTrue(error("frobnicate").contains("unknown command 'frobnicate'"));
        assertTrue(error("run", "--force").contains("run does not take --force"));
        assertTrue(error("run", "--config").contains("--config needs a value"));
        assertTrue(error("run", "extra").contains("run does not take 'extra'"));
    }

    @Test
    void workerPairAndRunAreParsed() {
        Cli.WorkerPair pair = (Cli.WorkerPair) parse("worker", "pair", "https://team.example.com", "ABCD2345", "--name", "ann-laptop");
        Cli.WorkerRun run = (Cli.WorkerRun) parse("worker", "run");

        assertEquals("https://team.example.com", pair.url());
        assertEquals("ABCD2345", pair.code());
        assertEquals("ann-laptop", pair.name());
        assertEquals(DEFAULTS.workerFile(), run.workerFile());
    }

    @Test
    void workerNeedsAKnownSubcommandAndItsArguments() {
        assertEquals("worker needs one of: init, pair, run, service",
                assertThrows(CliException.class, () -> parse("worker")).getMessage());
        assertEquals("worker pair needs the team URL and the code from /worker",
                assertThrows(CliException.class, () -> parse("worker", "pair", "https://team.example.com")).getMessage());
        assertEquals("unknown command 'worker restart'",
                assertThrows(CliException.class, () -> parse("worker", "restart")).getMessage());
    }

    @Test
    void workerInitRunAndServiceAreParsed() {
        Locations defaults = new Locations(Path.of("/home/ann/.config/dispatch/dispatch.yaml"), Path.of("/state"));

        assertEquals(new Cli.WorkerInit(Path.of("/home/ann/.config/dispatch/worker.yaml"), false),
                Cli.parse(new String[] {"worker", "init"}, defaults));
        assertEquals(new Cli.WorkerInit(Path.of("/tmp/w.yaml"), true),
                Cli.parse(new String[] {"worker", "init", "--config", "/tmp/w.yaml", "--force"}, defaults));
        assertEquals(new Cli.WorkerRun(Path.of("/home/ann/.config/dispatch/worker.yaml"), Path.of("/tmp/w.log")),
                Cli.parse(new String[] {"worker", "run", "--log-file", "/tmp/w.log"}, defaults));
        assertEquals(new Cli.WorkerService(Path.of("/home/ann/.config/dispatch/worker.yaml"), "install"),
                Cli.parse(new String[] {"worker", "service", "install"}, defaults));
        assertTrue(assertThrows(CliException.class, () -> Cli.parse(new String[] {"worker", "service"}, defaults))
                .getMessage().contains("worker service needs one of"));
        assertTrue(assertThrows(CliException.class, () -> Cli.parse(new String[] {"worker", "nope"}, defaults))
                .getMessage().contains("unknown command 'worker nope'"));
    }

    private static Cli.Invocation parse(String... args) {
        return Cli.parse(args, DEFAULTS);
    }

    private static String error(String... args) {
        return assertThrows(CliException.class, () -> Cli.parse(args, DEFAULTS)).getMessage();
    }
}
