package dispatch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    private static final Map<String, String> ENV = Map.of(
            "TELEGRAM_BOT_TOKEN", "123:abc",
            "GH_TOKEN", "github_pat_x",
            "STATE_DIRECTORY", "/var/lib/dispatch/backend");

    @TempDir
    Path dir;

    @Test
    void loadsValidConfigResolvingStateDirSecretsAndProjectLimits() throws IOException {
        Config config = ConfigLoader.load(write(VALID), ENV);

        assertEquals("backend", config.team());
        assertEquals(Path.of("/var/lib/dispatch/backend"), config.stateDir());
        assertEquals(-1001234567890L, config.telegram().groupChatId());
        assertEquals(List.of(new Config.Member(123456789L, "Bold"), new Config.Member(222333444L, "Ali")),
                config.telegram().members());
        assertEquals(2, config.scheduler().maxConcurrentRuns());
        assertEquals("/usr/local/bin/claude", config.agents().get("claude-code").command());
        assertEquals(new Config.Secrets("123:abc", "github_pat_x"), config.secrets());

        Config.Project alm = config.projects().getFirst();
        assertEquals("alm", alm.alias());
        assertEquals(List.of(".env"), alm.copyFiles());
        assertEquals(new Config.RunLimits(Duration.ofMinutes(20), new BigDecimal("2")), config.planLimits(alm));

        Config.Project crm = config.projects().get(1);
        assertNull(crm.alias());
        assertEquals(List.of(), crm.copyFiles());
        assertEquals(new Config.RunLimits(Duration.ofMinutes(15), new BigDecimal("2")), config.planLimits(crm));
    }

    @Test
    void shippedExampleConfigLoads() {
        Config config = ConfigLoader.load(Path.of("deploy/example.yaml"), ENV);

        assertEquals("backend", config.team());
        assertEquals("alm", config.projects().getFirst().alias());
    }

    @Test
    void stateDirInFileWinsOverEnvironment() throws IOException {
        Config config = ConfigLoader.load(write("stateDir: /srv/dispatch\n" + VALID), ENV);

        assertEquals(Path.of("/srv/dispatch"), config.stateDir());
    }

    @Test
    void unknownKeyIsReportedWithItsPath() throws IOException {
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(write(VALID.replace("baseBranch: main", "basebranch: main")), ENV));

        assertTrue(error.getMessage().contains("basebranch"), error.getMessage());
        assertTrue(error.getMessage().contains("projects[0]"), error.getMessage());
    }

    @Test
    void invalidDurationIsReported() throws IOException {
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(write(VALID.replace("timeout: 15m", "timeout: 15x")), ENV));

        assertTrue(error.getMessage().contains("15x"), error.getMessage());
    }

    @Test
    void everyValidationProblemIsReportedAtOnce() throws IOException {
        String broken = VALID
                .replace("alias: alm", "alias: crm")
                .replace("id: 222333444", "id: -5")
                .replace("- \".env\"", "- \"../secrets.env\"")
                .replace("agent: claude-code\n    model", "agent: codex\n    model");

        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(write(broken), Map.of("STATE_DIRECTORY", "/var/lib/dispatch/backend")));

        String message = error.getMessage();
        assertTrue(message.contains("TELEGRAM_BOT_TOKEN"), message);
        assertTrue(message.contains("telegram.members[1].id"), message);
        assertTrue(message.contains("'crm' is used by more than one project"), message);
        assertTrue(message.contains("projects[0].copyFiles[0]"), message);
        assertTrue(message.contains("projects[0].agent: 'codex' is not configured under agents"), message);
    }

    @Test
    void missingStateDirIsReported() throws IOException {
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(write(VALID), Map.of("TELEGRAM_BOT_TOKEN", "123:abc")));

        assertTrue(error.getMessage().contains("stateDir"), error.getMessage());
    }

    private Path write(String yaml) throws IOException {
        Path file = dir.resolve("backend.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    private static final String VALID = """
            team: backend
            telegram:
              groupChatId: -1001234567890
              members:
                - id: 123456789
                  name: Bold
                - id: 222333444
                  name: Ali
            scheduler:
              maxConcurrentRuns: 2
            limits:
              plan:
                timeout: 15m
                budgetUsd: 2
            agents:
              claude-code:
                command: /usr/local/bin/claude
            projects:
              - name: autoland-management
                alias: alm
                repo: https://github.com/acme/autoland-management.git
                baseBranch: main
                agent: claude-code
                model: opus
                copyFiles:
                  - ".env"
                limits:
                  plan:
                    timeout: 20m
              - name: crm
                repo: https://github.com/acme/crm.git
                baseBranch: develop
                agent: claude-code
            """;
}
