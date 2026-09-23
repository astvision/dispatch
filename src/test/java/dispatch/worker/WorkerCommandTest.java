package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.cli.Cli;
import dispatch.cli.CliException;
import dispatch.cli.SecretsFile;
import dispatch.cli.Service;
import dispatch.cli.ServiceCommand;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
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

    @Test
    void workerSpecFailsFastWhenTheKeyIsMissing() throws Exception {
        // A service installed on this setup would restart every 10 s forever, always failing the same way: this must
        // be caught before install, not discovered by watching it crash-loop.
        Path workerFile = dir.resolve("worker.yaml");
        Files.writeString(workerFile, """
                team: https://team.example.com
                name: ann-laptop
                """);

        CliException error = assertThrows(CliException.class, () -> WorkerCommand.workerSpec(workerFile, Map.of()));

        assertTrue(error.getMessage().contains(SecretsFile.beside(workerFile).toString()), error.getMessage());
        assertTrue(error.getMessage().contains("dispatch worker init"), error.getMessage());
    }

    @Test
    void specForUsesTheWorkerConfigsOwnStateDirForTheLog() throws Exception {
        // Not a workerSpec test: workerSpec always asks ServiceCommand.runningJar() for the jar, which is null when
        // this test runs from target/classes rather than a packaged jar, so it cannot be driven to success here (it
        // would only ever exercise the "no jar" CliException, already covered by workerSpecFailsFastWhenTheKeyIsMissing
        // failing on a different check first). This instead drives the same worker.yaml -> Service.Spec logic
        // workerSpec forwards to, through specFor's other overload with a stand-in jar, as InitCommandTest's service
        // tests do.
        Path workerFile = dir.resolve("worker.yaml");
        Path stateDir = dir.resolve("worker-state");
        Files.writeString(workerFile, """
                team: https://team.example.com
                name: ann-laptop
                stateDir: %s
                """.formatted(stateDir));
        Path jar = Files.writeString(dir.resolve("dispatch.jar"), "stand-in");

        WorkerConfig config = WorkerConfigLoader.load(workerFile);
        Service.Spec spec = ServiceCommand.specFor(jar, workerFile, config.stateDir(), Service.Kind.WORKER.logName(), Map.of());

        assertEquals(stateDir, spec.stateDir());
        assertEquals(stateDir.resolve("dispatch-worker.log"), spec.logFile());
    }

    @Test
    void aSecondWorkerRunRefusesToStartWhileTheFirstHoldsTheStateDir() throws Exception {
        Path workerFile = dir.resolve("worker.yaml");
        Path stateDir = dir.resolve("worker-state");
        Files.writeString(workerFile, """
                team: https://team.example.com
                name: ann-laptop
                stateDir: %s
                """.formatted(stateDir));
        SecretsFile.write(SecretsFile.beside(workerFile), Map.of(WorkerCommand.KEY_VARIABLE, "some-key"));
        Files.createDirectories(stateDir);
        // Stands in for the first `dispatch worker run`'s own lock: same file, held exclusively, before the second
        // ever starts. The team URL never has to answer, because a held lock is refused before it is ever contacted.
        try (FileChannel heldByFirst = FileChannel.open(stateDir.resolve("agents.lock"), StandardOpenOption.CREATE,
                     StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock ignored = heldByFirst.lock()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            int status = new WorkerCommand(new PrintStream(out, true, StandardCharsets.UTF_8))
                    .run(new Cli.WorkerRun(workerFile, null), Map.of());

            String printed = out.toString(StandardCharsets.UTF_8);
            assertEquals(1, status, printed);
            assertTrue(printed.contains(stateDir.toString()), printed);
            assertTrue(printed.contains("already holds"), printed);
        }
    }
}
