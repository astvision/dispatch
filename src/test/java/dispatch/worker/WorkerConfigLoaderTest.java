package dispatch.worker;

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
        WorkerConfig config = WorkerConfigLoader.load(write("""
                team: https://team.example.com
                name: ann-laptop
                maxConcurrentRuns: 2
                claudeCommand: /usr/local/bin/claude
                projects:
                  crm:
                    path: /home/ann/work/crm
                    model: opus
                """));

        assertEquals("https://team.example.com", config.team());
        assertEquals("ann-laptop", config.name());
        assertEquals(2, config.maxConcurrentRuns());
        assertEquals("/usr/local/bin/claude", config.claudeCommand());
        assertEquals("/home/ann/work/crm", config.projects().get("crm").path());
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

    private Path write(String yaml) throws Exception {
        Path file = dir.resolve("worker.yaml");
        Files.writeString(file, yaml);
        return file;
    }
}
