package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.cli.Cli;
import dispatch.cli.SecretsFile;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** dispatch worker pair, against a real WorkerApi. */
class WorkerCommandTest extends WorkerApiFixture {

    @Test
    void pairingCreatesTheConfigDirectoryWhenItDoesNotExistYet() throws Exception {
        // A worker machine never runs `dispatch init`; this directory is exactly as absent as it is on a fresh laptop.
        Path workerFile = dir.resolve("nested/config/worker.yaml");
        String code = keys.newCode(BOLD);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int status = new WorkerCommand(new PrintStream(out, true, StandardCharsets.UTF_8))
                .pair(new Cli.WorkerPair(workerFile, "http://127.0.0.1:" + api.port(), code, "ann-laptop"));

        String printed = out.toString(StandardCharsets.UTF_8);
        assertEquals(0, status, printed);
        assertTrue(Files.exists(workerFile), "worker.yaml was not created: " + printed);
        Path env = SecretsFile.beside(workerFile);
        assertTrue(Files.exists(env), "worker.env was not created: " + printed);
        String key = SecretsFile.read(env).get(WorkerCommand.KEY_VARIABLE);
        assertTrue(key != null && !key.isBlank(), "no worker key was written");
        assertFalse(printed.contains(key), "the key must never be printed: " + printed);
    }

    @Test
    void pairingWithABareFilenameConfigPathDoesNotBurnTheCode() throws Exception {
        // Cli.Arguments.workerFile(defaults) is `Path.of(value)` verbatim for --config: a bare filename like this has
        // no parent as given, only as an absolute path — the exact shape that NPE'd past this try's IOException catch.
        Path workerFile = Path.of("dispatch-worker-command-test-" + UUID.randomUUID() + ".yaml");
        Path env = SecretsFile.beside(workerFile);
        String code = keys.newCode(BOLD);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            int status = new WorkerCommand(new PrintStream(out, true, StandardCharsets.UTF_8))
                    .pair(new Cli.WorkerPair(workerFile, "http://127.0.0.1:" + api.port(), code, "ann-laptop"));

            String printed = out.toString(StandardCharsets.UTF_8);
            assertEquals(0, status, printed);
            assertTrue(Files.exists(workerFile), "worker.yaml was not created: " + printed);
            assertTrue(Files.exists(env), "worker.env was not created: " + printed);
        } finally {
            Files.deleteIfExists(workerFile.toAbsolutePath());
            Files.deleteIfExists(env.toAbsolutePath());
        }
    }
}
