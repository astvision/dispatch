package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetupTest {

    // Split, so secret scanners never see a token-shaped literal in this file.
    private static final String TOKEN = "123456789" + ":AAH-fake-token-for-tests-only-0123456789";

    @TempDir
    Path dir;

    private FakeTelegram telegram;
    private Path config;

    @BeforeEach
    void setUp() throws IOException {
        telegram = FakeTelegram.start();
        config = dir.resolve("config/dispatch.yaml");
    }

    @AfterEach
    void tearDown() {
        telegram.close();
    }

    @Test
    void aWrittenSetupLoadsWithItsTokenInTheSecretsFile() throws IOException {
        GitFixture repos = GitFixture.create(dir, "alm");
        Setup.Answers answers = new Setup.Answers("bold", false, List.of(new Config.Member(100, "Bold")), null, "claude",
                List.of(new ProjectAddCommand.Project("alm", null, repos.repo("alm"), null, "main", "claude-code", "opus", null)),
                "Dispatch (Bold)", "bold@example.com");

        Setup.write(config, Setup.render(answers, dir.resolve("state")), TOKEN);

        Config written = ConfigLoader.load(config, SecretsFile.environment(config, Map.of()));
        assertEquals(TOKEN, written.secrets().telegramBotToken());
        assertEquals(List.of(new Config.Member(100, "Bold")), written.telegram().groups().getFirst().members());
        assertEquals("opus", written.projects().getFirst().model());
        assertFalse(Files.readString(config).contains(TOKEN), "the token goes only into the secrets file");
    }

    @Test
    void anInvalidSetupWritesNothingAtAll() throws IOException {
        CliException e = assertThrows(CliException.class, () -> Setup.write(config, "team: [\n", TOKEN));

        assertTrue(e.getMessage().contains(config.toString()), "names the config, not a temporary file: " + e.getMessage());
        assertFalse(Files.exists(config));
        assertFalse(Files.exists(SecretsFile.beside(config)));
        try (Stream<Path> left = Files.list(config.getParent())) {
            assertEquals(List.of(), left.toList(), "no draft is left behind");
        }
    }

    @Test
    void aTokenTelegramRefusesIsExplainedWithoutShowingIt() {
        telegram.respond("getMe", 401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");

        CliException refused = assertThrows(CliException.class, () -> Setup.bot(TOKEN, this::bot));
        CliException malformed = assertThrows(CliException.class, () -> Setup.bot("not a token", this::bot));

        assertTrue(refused.getMessage().startsWith("Telegram refused that token"), refused.getMessage());
        assertFalse(refused.getMessage().contains(TOKEN));
        assertTrue(malformed.getMessage().startsWith("that is not a bot token"), malformed.getMessage());
    }

    @Test
    void theBotNeverShowsItsTokenWhenPrinted() {
        Setup.Bot bot = Setup.bot(TOKEN, this::bot);

        assertEquals(FakeTelegram.BOT_USERNAME, bot.username());
        assertFalse(bot.toString().contains(TOKEN), bot.toString());
    }

    @Test
    void anotherReaderOfTheBotIsAConflict() {
        telegram.respond("getUpdates", 409,
                "{\"ok\":false,\"error_code\":409,\"description\":\"Conflict: terminated by other getUpdates request\"}");
        Setup.Updates updates = new Setup.Updates(bot(TOKEN));

        Setup.ConflictException e = assertThrows(Setup.ConflictException.class,
                () -> updates.next(Setup::isPrivateMessage, Duration.ofSeconds(2)));

        assertTrue(e.getMessage().contains("stop it first"), e.getMessage());
    }

    @Test
    void aQuickPersonalSetupRendersTheLayoutInitHasAlwaysWritten() {
        Path state = dir.resolve("state");
        Path alm = dir.resolve("alm");
        Setup.Answers answers = new Setup.Answers("bold", false, List.of(new Config.Member(100, "Bold")), null, "claude",
                List.of(new ProjectAddCommand.Project("alm", null, alm, null, "main", "claude-code", "opus", null)),
                "Dispatch (Bold)", "bold@example.com");

        assertEquals("""
                # Written by dispatch init. Edit it freely: dispatch check says if something is wrong.
                team: bold
                stateDir: %s

                telegram:
                  groups:
                    - name: bold     # a personal bot: no group chat, everything stays private
                      members:
                        - id: 100
                          name: 'Bold'
                      projects:
                        - alm

                delivery:
                  authorName: 'Dispatch (Bold)'
                  authorEmail: 'bold@example.com'

                scheduler:
                  maxConcurrentRuns: 1

                limits:              # per run, for every project; a project may set its own under limits
                  plan:
                    timeout: 15m
                    budgetUsd: 2
                  execute:
                    timeout: 60m
                    budgetUsd: 10

                agents:
                  claude-code:
                    command: 'claude'

                projects:
                  - name: alm
                    path: %s
                    baseBranch: main
                    agent: claude-code
                    model: opus
                """.formatted(quoted(state.toAbsolutePath()), quoted(alm)), Setup.render(answers, state));
    }

    @Test
    void aQuickTeamSetupRendersTheLayoutInitHasAlwaysWritten() {
        Path state = dir.resolve("state");
        Path alm = dir.resolve("alm");
        Setup.Answers answers = new Setup.Answers("acme", true, List.of(new Config.Member(100, "Bold"), new Config.Member(222, "Ali")),
                new Setup.Chat(-1001234567890L, "ACME backend"), "claude",
                List.of(new ProjectAddCommand.Project("alm", null, alm, "git@github.com:acme/alm.git", "main", "claude-code", null, null)),
                "Dispatch (Bold)", "bold@example.com");

        assertEquals("""
                # Written by dispatch init. Edit it freely: dispatch check says if something is wrong.
                team: acme
                stateDir: %s

                telegram:
                  admins:            # who lets people join, from Telegram (ADR 0015)
                    - 100
                  groups:
                    - name: acme
                      chatId: -1001234567890     # ACME backend
                      members:
                        - id: 100
                          name: 'Bold'
                        - id: 222
                          name: 'Ali'
                      projects:
                        - alm

                delivery:
                  authorName: 'Dispatch (Bold)'
                  authorEmail: 'bold@example.com'

                scheduler:
                  maxConcurrentRuns: 2

                limits:              # per run, for every project; a project may set its own under limits
                  plan:
                    timeout: 15m
                    budgetUsd: 2
                  execute:
                    timeout: 60m
                    budgetUsd: 10

                agents:
                  claude-code:
                    command: 'claude'

                projects:
                  - name: alm
                    path: %s
                    repo: 'git@github.com:acme/alm.git'
                    baseBranch: main
                    agent: claude-code
                """.formatted(quoted(state.toAbsolutePath()), quoted(alm)), Setup.render(answers, state));
    }
    @Test
    void advancedAnswersAreWrittenAndEverythingElseKeepsItsDefault() throws IOException {
        GitFixture repos = GitFixture.create(dir, "alm");
        Path state = dir.resolve("elsewhere/state");
        Setup.Answers answers = new Setup.Answers("bold", false, List.of(new Config.Member(100, "Bold")), null, "claude",
                List.of(new ProjectAddCommand.Project("alm", "a", repos.repo("alm"), null, "main", "claude-code", null, "high",
                        new Config.PhaseSettings("opus", null), new Config.PhaseSettings(null, "low"))),
                "Dispatch (Bold)", "bold@example.com",
                new Setup.Advanced("30m", null, null, new BigDecimal("12.5"), 3, state, "/opt/gh/bin/gh"));

        String yaml = Setup.render(answers, dir.resolve("default-state"));
        Setup.write(config, yaml, TOKEN);

        Config written = ConfigLoader.load(config, SecretsFile.environment(config, Map.of()));
        assertEquals(new Config.RunLimits(Duration.ofMinutes(30), new BigDecimal("2")), written.limits().plan());
        assertEquals(new Config.RunLimits(Duration.ofMinutes(60), new BigDecimal("12.5")), written.limits().execute());
        assertEquals(3, written.scheduler().maxConcurrentRuns());
        assertEquals(state.toAbsolutePath(), written.stateDir());
        assertEquals("/opt/gh/bin/gh", written.delivery().ghCommand());
        Config.Project project = written.projects().getFirst();
        assertEquals("a", project.alias());
        assertEquals("opus", project.planModel());
        assertEquals("high", project.planEffort(), "the effort for both phases, since plan sets none of its own");
        assertNull(project.executeModel());
        assertEquals("low", project.executeEffort());
        assertTrue(yaml.contains("""
                    plan:
                      model: opus
                    execute:
                      effort: low
                """), yaml);
    }

    @Test
    void advancedAnswersEqualToTheDefaultsRenderTheQuickLayout() {
        Path state = dir.resolve("state");
        List<ProjectAddCommand.Project> projects = List.of(new ProjectAddCommand.Project("alm", null, dir.resolve("alm"), null, "main",
                "claude-code", null, null, new Config.PhaseSettings(null, null), null));
        Setup.Answers quick = new Setup.Answers("bold", false, List.of(new Config.Member(100, "Bold")), null, "claude", projects,
                "Dispatch (Bold)", "bold@example.com");
        Setup.Answers typedDefaults = new Setup.Answers("bold", false, List.of(new Config.Member(100, "Bold")), null, "claude", projects,
                "Dispatch (Bold)", "bold@example.com",
                new Setup.Advanced("15m", new BigDecimal("2"), "60m", new BigDecimal("10"), 1, state, "gh"));

        assertEquals(Setup.render(quick, state), Setup.render(typedDefaults, state));
    }

    private static String quoted(Path path) {
        return "'" + path.toString().replace("'", "''") + "'";
    }

    private BotApi bot(String token) {
        return new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5));
    }
}
