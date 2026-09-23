package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.cli.Cli;
import dispatch.cli.Locations;
import dispatch.cli.SecretsFile;
import dispatch.testing.GitFixture;
import dispatch.testing.ScriptedTerminal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** dispatch worker init, against a real WorkerApi: the member's own computer, set up in one command. */
class WorkerInitCommandTest extends WorkerApiFixture {

    /** A command that exists on every OS and answers --version, as CheckCommandTest uses: the JVM running this test. */
    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    /** A real repository, so "a clone I already have" matches it and "clone it here" can actually clone it. */
    @Override
    String almRepo() {
        return repos.origin.toString();
    }

    @Test
    void itPairsMapsAClonePicksAModelAndWritesBothFiles() throws Exception {
        Path workerFile = dir.resolve("config/worker.yaml");
        ScriptedTerminal terminal = new ScriptedTerminal(
                "http://127.0.0.1:" + api.port(),          // Team URL
                "ann-laptop",                              // A name for this computer
                keys.newCode(BOLD),                        // Pairing code
                "A clone I already have",                  // alm: how it gets onto this computer
                repos.repo("alm").toString(),              // Folder of the clone
                "Opus",                                    // Model on this computer
                "",                                        // Effort: the team's
                JAVA,                                      // claude command
                JAVA,                                      // GitHub CLI command (not logged in: a warning, not a failure)
                "y");                                      // Write this setup?

        int status = init(terminal, workerFile);

        assertEquals(0, status, terminal.output());
        WorkerConfig config = WorkerConfigLoader.load(workerFile);
        assertEquals("ann-laptop", config.name());
        assertEquals("http://127.0.0.1:" + api.port(), config.team());
        assertEquals(repos.repo("alm").toString(), config.projects().get("alm").path());
        assertEquals("opus", config.projects().get("alm").model());
        String key = SecretsFile.read(SecretsFile.beside(workerFile)).get(WorkerCommand.KEY_VARIABLE);
        assertTrue(key != null && !key.isBlank(), "no worker key was written");
        assertFalse(terminal.output().contains(key), "the key must never be shown: " + terminal.output());
    }

    @Test
    void itClonesAProjectIntoItsOwnStateDirectoryWhenTheMemberHasNoClone() throws Exception {
        Path workerFile = dir.resolve("config/worker.yaml");
        ScriptedTerminal terminal = new ScriptedTerminal(
                "http://127.0.0.1:" + api.port(), "ann-laptop", keys.newCode(BOLD),
                "Clone it here",                           // alm: let the worker clone it
                "", "",                                    // model, effort: the team's
                JAVA, JAVA, "y");

        int status = init(terminal, workerFile);

        assertEquals(0, status, terminal.output());
        Path clone = dir.resolve("state/worker/repos/alm");
        assertTrue(Files.isDirectory(clone.resolve(".git")), "alm was not cloned: " + terminal.output());
        assertEquals(clone.toString(), WorkerConfigLoader.load(workerFile).projects().get("alm").path());
    }

    @Test
    void aCloneOfAnotherRepositoryIsRefusedUnlessTheMemberConfirmsIt() throws Exception {
        Path other = dir.resolve("elsewhere");
        GitFixture.sh(dir, "git", "init", "--quiet", "-b", "main", other.toString());
        Path workerFile = dir.resolve("config/worker.yaml");
        ScriptedTerminal terminal = new ScriptedTerminal(
                "http://127.0.0.1:" + api.port(), "ann-laptop", keys.newCode(BOLD),
                "A clone I already have", other.toString(),
                "n",                                       // Use it anyway? no
                "A clone I already have", repos.repo("alm").toString(), "", "",
                JAVA, JAVA, "y");

        int status = init(terminal, workerFile);

        assertEquals(0, status, terminal.output());
        assertTrue(terminal.output().contains("is not a clone of"), terminal.output());
        assertEquals(repos.repo("alm").toString(), WorkerConfigLoader.load(workerFile).projects().get("alm").path());
    }

    @Test
    void anExistingWorkerYamlIsKeptUnlessForced() throws Exception {
        Path workerFile = dir.resolve("config/worker.yaml");
        Files.createDirectories(workerFile.getParent());
        Files.writeString(workerFile, "team: https://team.example.com\nname: ann-laptop\n");
        ScriptedTerminal terminal = new ScriptedTerminal();

        int status = init(terminal, workerFile);

        assertEquals(1, status, terminal.output());
        assertTrue(terminal.output().contains("dispatch worker init --force"), terminal.output());
        assertEquals("team: https://team.example.com\nname: ann-laptop\n", Files.readString(workerFile));
    }

    /** No ServiceCommand: these tests must not install anything on the machine that runs them. */
    private int init(ScriptedTerminal terminal, Path workerFile) {
        return new WorkerInitCommand(terminal, new Locations(dir.resolve("config/dispatch.yaml"), dir.resolve("state")),
                null).run(new Cli.WorkerInit(workerFile, false), Map.of());
    }
}
