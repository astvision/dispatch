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
    void aBadTeamUrlOrComputerNameIsRefusedBeforeAnyCodeIsSpent() throws Exception {
        Path workerFile = dir.resolve("config/worker.yaml");
        ScriptedTerminal terminal = new ScriptedTerminal(
                "http://192.168.1.50:7878",                // refused: plain http is only for 127.0.0.1
                "http://127.0.0.1:" + api.port(),          // now valid
                "Ann's laptop",                            // refused: apostrophe and space are not allowed
                "ann-laptop",                               // now valid
                keys.newCode(BOLD),
                "Skip it", JAVA, JAVA, "y");

        int status = init(terminal, workerFile);

        assertEquals(0, status, terminal.output());
        assertTrue(terminal.output().contains("must start with https://"), terminal.output());
        assertTrue(terminal.output().contains("letters, digits"), terminal.output());
    }

    @Test
    void aFailedCloneFallsBackToTheProjectMenuInsteadOfAbortingTheWizard() throws Exception {
        Path workerFile = dir.resolve("config/worker.yaml");
        Path blockedTarget = dir.resolve("state/worker/repos/alm");
        Files.createDirectories(blockedTarget.getParent());
        Files.createFile(blockedTarget); // not a directory: git clone into it must fail
        ScriptedTerminal terminal = new ScriptedTerminal(
                "http://127.0.0.1:" + api.port(), "ann-laptop", keys.newCode(BOLD),
                "Clone it here",                                           // fails: the target already exists as a file
                "A clone I already have", repos.repo("alm").toString(),    // recovers to an existing clone instead
                "", "",
                JAVA, JAVA, "y");

        int status = init(terminal, workerFile);

        assertEquals(0, status, terminal.output());
        assertTrue(terminal.output().contains("cannot clone"), terminal.output());
        assertEquals(repos.repo("alm").toString(), WorkerConfigLoader.load(workerFile).projects().get("alm").path());
    }

    @Test
    void aGenuinelyInterruptedCloneCleansUpSoALaterForcedRunCanRetryIt() throws Exception {
        // Corrupt the origin's one blob so `git clone` transfers the objects, creates .git, then fails at checkout
        // -- exactly the shape of a genuinely interrupted clone: a partly-populated target, left by this attempt.
        String blob = GitFixture.sh(dir, "git", "--git-dir=" + repos.origin, "rev-parse", "HEAD:README.md");
        Path object = repos.origin.resolve("objects").resolve(blob.substring(0, 2)).resolve(blob.substring(2));
        byte[] original = Files.readAllBytes(object);
        object.toFile().setWritable(true);
        Files.writeString(object, "not a valid git object\n");

        Path workerFile = dir.resolve("config/worker.yaml");
        ScriptedTerminal firstAttempt = new ScriptedTerminal(
                "http://127.0.0.1:" + api.port(), "ann-laptop", keys.newCode(BOLD),
                "Clone it here",                           // fails partway: the corrupted object breaks checkout
                "Skip it",                                 // move on for now; the project stays unmapped
                JAVA, JAVA, "y");

        int firstStatus = init(firstAttempt, workerFile);

        assertEquals(0, firstStatus, firstAttempt.output());
        assertTrue(firstAttempt.output().contains("cannot clone"), firstAttempt.output());
        Path target = dir.resolve("state/worker/repos/alm");
        assertFalse(Files.exists(target), "the failed attempt's own partial directory must be cleaned up: " + firstAttempt.output());

        Files.write(object, original); // the transient failure is over

        ScriptedTerminal secondAttempt = new ScriptedTerminal(
                "",                                        // "already paired ... pair again?" no: reuse it
                "Clone it here", "", "",
                JAVA, JAVA, "y");
        int secondStatus = new WorkerInitCommand(secondAttempt,
                new Locations(dir.resolve("config/dispatch.yaml"), dir.resolve("state")), null)
                .run(new Cli.WorkerInit(workerFile, true), Map.of());

        assertEquals(0, secondStatus, secondAttempt.output());
        assertTrue(Files.isDirectory(target.resolve(".git")), "the retried clone must succeed: " + secondAttempt.output());
        assertEquals(target.toString(), WorkerConfigLoader.load(workerFile).projects().get("alm").path());
    }

    @Test
    void aMalformedWorkerEnvDoesNotLoseTheKeyAPairingJustBought() throws Exception {
        Path workerFile = dir.resolve("config/worker.yaml");
        Path envFile = SecretsFile.beside(workerFile);
        Files.createDirectories(envFile.getParent());
        Files.writeString(envFile, "not a valid line at all\n"); // no '=': SecretsFile.read throws on this
        ScriptedTerminal terminal = new ScriptedTerminal(
                "http://127.0.0.1:" + api.port(), "ann-laptop", keys.newCode(BOLD),
                "Skip it", JAVA, JAVA, "y");

        int status = init(terminal, workerFile);

        assertEquals(0, status, terminal.output());
        String key = SecretsFile.read(envFile).get(WorkerCommand.KEY_VARIABLE);
        assertTrue(key != null && !key.isBlank(), "the key must survive a malformed existing worker.env: " + terminal.output());
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

    @Test
    void aRejectedOrExpiredCodeIsRetriedByReAskingTheUrlNotJustTheCode() throws Exception {
        Path workerFile = dir.resolve("config/worker.yaml");
        // A blank re-ask answer would be swallowed by required()'s own blank-retry, landing on the next script item
        // as if no question had been asked at all -- an explicit, non-blank repeat is the only answer that proves
        // the URL was actually asked again, not skipped straight to another code prompt.
        ScriptedTerminal terminal = new ScriptedTerminal(
                "http://127.0.0.1:" + api.port(), "ann-laptop",
                "not-a-real-code",                         // unknown/expired: the server answers 401 pairing_code
                "http://127.0.0.1:" + api.port(),          // the team URL question, asked again, answered explicitly
                keys.newCode(BOLD),                         // a real, still-unspent code
                "Skip it", JAVA, JAVA, "y");

        int status = init(terminal, workerFile);

        assertEquals(0, status, terminal.output());
        assertTrue(terminal.output().contains("pairing failed"), terminal.output());
        long urlQuestions = terminal.output().lines().filter(line -> line.contains("Team URL (your team owner has it")).count();
        assertEquals(2, urlQuestions, "the URL must be re-asked after a rejected code, not just the code: " + terminal.output());
        long nameQuestions = terminal.output().lines().filter(line -> line.contains("A name for this computer")).count();
        assertEquals(1, nameQuestions, "the computer name, answered once already, must not be re-asked: " + terminal.output());
        assertEquals("ann-laptop", WorkerConfigLoader.load(workerFile).name());
    }

    @Test
    void anUnreachableTeamUrlIsRetriedAfterReAskingTheUrl() throws Exception {
        Path workerFile = dir.resolve("config/worker.yaml");
        ScriptedTerminal terminal = new ScriptedTerminal(
                "http://127.0.0.1:1", "ann-laptop",
                "does-not-matter",                         // the connection fails before any code is checked
                "http://127.0.0.1:" + api.port(),          // team URL re-asked; the real one this time
                keys.newCode(BOLD),
                "Skip it", JAVA, JAVA, "y");

        int status = init(terminal, workerFile);

        assertEquals(0, status, terminal.output());
        assertTrue(terminal.output().contains("pairing failed"), terminal.output());
        assertEquals("http://127.0.0.1:" + api.port(), WorkerConfigLoader.load(workerFile).team());
    }

    @Test
    void anAbortedInitAfterPairingIsResumedWithoutANewCode() throws Exception {
        Path workerFile = dir.resolve("config/worker.yaml");
        String code = keys.newCode(BOLD);
        // No more answers after the code: aborts at the very next question (alm's "how does it get onto this computer?").
        ScriptedTerminal firstAttempt = new ScriptedTerminal("http://127.0.0.1:" + api.port(), "ann-laptop", code);

        int firstStatus = init(firstAttempt, workerFile);

        assertEquals(1, firstStatus, firstAttempt.output());
        assertFalse(Files.exists(workerFile), "worker.yaml must not exist after an abort: " + firstAttempt.output());
        String savedKey = SecretsFile.read(SecretsFile.beside(workerFile)).get(WorkerCommand.KEY_VARIABLE);
        assertTrue(savedKey != null && !savedKey.isBlank(), "the key from the spent code must survive the abort");

        // Only the team URL and the computer name: the code that pairing already spent is never asked for again.
        ScriptedTerminal secondAttempt = new ScriptedTerminal(
                "http://127.0.0.1:" + api.port(), "ann-laptop", "Skip it", JAVA, JAVA, "y");

        int secondStatus = init(secondAttempt, workerFile);

        assertEquals(0, secondStatus, secondAttempt.output());
        assertFalse(secondAttempt.output().contains("Pairing code"), "resuming must not ask for a new code: " + secondAttempt.output());
        assertEquals("ann-laptop", WorkerConfigLoader.load(workerFile).name());
        assertEquals(savedKey, SecretsFile.read(SecretsFile.beside(workerFile)).get(WorkerCommand.KEY_VARIABLE));
    }

    /** No ServiceCommand: these tests must not install anything on the machine that runs them. */
    private int init(ScriptedTerminal terminal, Path workerFile) {
        return new WorkerInitCommand(terminal, new Locations(dir.resolve("config/dispatch.yaml"), dir.resolve("state")),
                null).run(new Cli.WorkerInit(workerFile, false), Map.of());
    }
}
