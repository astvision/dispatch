package dispatch.worker;

import dispatch.agent.sandbox.SandboxSetting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.config.ConfigException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** worker.yaml: where this computer's clones are, and nothing the team machine already knows. */
class WorkerConfigLoaderTest {

    @TempDir
    Path dir;

    @Test
    void aWorkerConfigMapsProjectsToLocalClones() throws Exception {
        // Absolute on this OS: /home/ann/work/crm is not, on Windows.
        String crm = dir.resolve("work").resolve("crm").toString();
        WorkerConfig config = WorkerConfigLoader.load(write("""
                team: https://team.example.com
                name: ann-laptop
                maxConcurrentRuns: 2
                claudeCommand: /usr/local/bin/claude
                projects:
                  crm:
                    path: '%s'
                    model: opus
                """.formatted(crm.replace("'", "''"))));

        assertEquals("https://team.example.com", config.team());
        assertEquals("ann-laptop", config.name());
        assertEquals(2, config.maxConcurrentRuns());
        assertEquals("/usr/local/bin/claude", config.claudeCommand());
        assertEquals(crm, config.projects().get("crm").path());
        assertEquals("opus", config.projects().get("crm").model());
    }

    @Test
    void whatIsLeftOutGetsAWorkingDefault() throws Exception {
        WorkerConfig config = WorkerConfigLoader.load(write("""
                team: https://team.example.com
                name: ann-laptop
                """));

        assertEquals(1, config.maxConcurrentRuns());
        assertEquals("claude", config.claudeCommand());
        assertEquals("gh", config.ghCommand());
        assertTrue(config.projects().isEmpty());
    }

    @Test
    void everyProblemIsReportedAtOnce() throws Exception {
        Path file = write("""
                team: ftp://team.example.com
                name: ""
                maxConcurrentRuns: 0
                projects:
                  crm:
                    path: work/crm
                """);

        ConfigException error = assertThrows(ConfigException.class, () -> WorkerConfigLoader.load(file));

        assertTrue(error.getMessage().contains("team: must start with https://"), error.getMessage());
        assertTrue(error.getMessage().contains("name: required"), error.getMessage());
        assertTrue(error.getMessage().contains("maxConcurrentRuns: at least 1"), error.getMessage());
        assertTrue(error.getMessage().contains("projects.crm.path: must be an absolute path"), error.getMessage());
    }

    @Test
    void aHighMaxConcurrentRunsIsAccepted() throws Exception {
        // WorkerClient, not this validation, is what keeps this worker's requests to the team machine within its
        // limit; this number only trades this computer's own resource use against throughput.
        WorkerConfig config = WorkerConfigLoader.load(write("""
                team: https://team.example.com
                name: ann-laptop
                maxConcurrentRuns: 20
                """));

        assertEquals(20, config.maxConcurrentRuns());
    }

    @Test
    void theDefaultStateDirIsNotWhereAPersonalDispatchRunsOnThisMachine() throws Exception {
        WorkerConfig config = WorkerConfigLoader.load(write("""
                team: https://team.example.com
                name: ann-laptop
                """));

        assertEquals("worker", config.stateDir().getFileName().toString());
        assertTrue(config.stateDir().isAbsolute(), config.stateDir().toString());
    }

    @Test
    void anEmptyFileIsAClearErrorNotACrash() throws Exception {
        Path file = write("");

        ConfigException error = assertThrows(ConfigException.class, () -> WorkerConfigLoader.load(file));

        assertTrue(error.getMessage().contains(file.toString()), error.getMessage());
    }

    @Test
    void aWorkerListsThePluginsAndMcpServersItsRunsLoadAndNeverABlankName() throws Exception {
        WorkerConfigLoader.load(write("""
                team: https://team.example.com
                name: ann-laptop
                claudePlugins: [frontend-design@claude-plugins-official]
                claudeMcpServers: [mongodb]
                claudeSkills: [graphify]
                """));
        Path blank = write("""
                team: https://team.example.com
                name: ann-laptop
                claudePlugins: ['']
                claudeSkills: [.hidden]
                """);

        ConfigException error = assertThrows(ConfigException.class, () -> WorkerConfigLoader.load(blank));

        assertTrue(error.getMessage().contains("claudePlugins, claudeMcpServers and claudeSkills: list names, without blank entries"),
                error.getMessage());
        assertTrue(error.getMessage().contains("claudeSkills: .hidden is not a directory name in ~/.claude/skills"),
                error.getMessage());
    }

    private Path write(String yaml) throws Exception {
        Path file = dir.resolve("worker.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    private static final String MINIMAL_WORKER = """
            team: https://team.example.com
            name: ann-laptop
            claudeCommand: /usr/local/bin/claude
            projects: {}
            """;

    @Test
    void workerSandboxIsAutoUnlessTurnedOff() throws Exception {
        assertEquals(SandboxSetting.AUTO, WorkerConfigLoader.load(write(MINIMAL_WORKER)).sandbox());
        assertEquals(SandboxSetting.OFF, WorkerConfigLoader.load(write(MINIMAL_WORKER + "sandbox: off\n")).sandbox());
    }

    @Test
    void anUnknownWorkerSandboxSettingIsRefused() throws Exception {
        Path file = write(MINIMAL_WORKER + "sandbox: on\n");

        ConfigException error = assertThrows(ConfigException.class, () -> WorkerConfigLoader.load(file));

        assertTrue(error.getMessage().contains("sandbox: auto or off, not on"), error.getMessage());
    }
}
