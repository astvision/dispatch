package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.cli.Checks;
import dispatch.cli.SecretsFile;
import dispatch.testing.GitFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** dispatch check on a member's own computer, against a real WorkerApi. */
class WorkerChecksTest extends WorkerApiFixture {

    /** A command that exists on every OS and answers --version; `auth status` then fails, which is a warning. */
    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    /** A real repository, so a clone of it is recognised as this project's. */
    @Override
    String almRepo() {
        return repos.origin.toString();
    }

    @Test
    void aWorkingWorkerPassesAndNamesItsTeamPairingAndProjects() throws Exception {
        Path workerFile = writeWorker(pair(), repos.repo("alm").toString());

        List<Checks.Finding> findings = WorkerChecks.run(workerFile, Map.of(), finding -> { });

        assertFalse(Checks.failed(findings), findings.toString());
        assertTrue(findings.contains(new Checks.Finding(Checks.Level.OK, "team",
                "team: http://127.0.0.1:" + api.port() + " answers")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.area().equals("pairing")
                && f.message().startsWith("pairing: paired with ")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.area().equals("project alm") && f.level() == Checks.Level.OK),
                findings.toString());
    }

    @Test
    void aRevokedKeyIsAFailureThatSaysToPairAgain() throws Exception {
        String key = pair();
        Path workerFile = writeWorker(key, repos.repo("alm").toString());
        long workerId = db.transactionReturning(tx -> dispatch.store.Workers.ofMember(tx, BOLD.ref())).getFirst().id();
        keys.revoke(workerId, BOLD.ref(), false);

        List<Checks.Finding> findings = WorkerChecks.run(workerFile, Map.of(), finding -> { });

        assertTrue(findings.contains(new Checks.Finding(Checks.Level.FAIL, "pairing",
                "pairing: this computer's key is not valid any more; pair again: dispatch worker init")),
                findings.toString());
        assertTrue(Checks.failed(findings));
    }

    @Test
    void aProjectTheTeamHasButThisComputerDoesNotIsAFailure() throws Exception {
        Path workerFile = writeWorker(pair(), null);

        List<Checks.Finding> findings = WorkerChecks.run(workerFile, Map.of(), finding -> { });

        assertTrue(findings.contains(new Checks.Finding(Checks.Level.FAIL, "project alm",
                "project alm: the team has this project, but this computer does not; run: dispatch worker init")),
                findings.toString());
    }

    @Test
    void aCloneOfTheWrongRepositoryIsAWarning() throws Exception {
        Path other = dir.resolve("elsewhere");
        GitFixture.sh(dir, "git", "init", "--quiet", "-b", "main", other.toString());
        GitFixture.sh(other, "git", "remote", "add", "origin", "https://example.invalid/other.git");
        Path workerFile = writeWorker(pair(), other.toString());

        List<Checks.Finding> findings = WorkerChecks.run(workerFile, Map.of(), finding -> { });

        assertTrue(findings.stream().anyMatch(f -> f.area().equals("project alm") && f.level() == Checks.Level.WARN
                && f.message().contains("has origin https://example.invalid/other.git")), findings.toString());
    }

    @Test
    void anUnreachableTeamFailsOnceAndSkipsTheProjectList() throws Exception {
        Path workerFile = writeWorker(pair(), repos.repo("alm").toString());
        api.close();

        List<Checks.Finding> findings = WorkerChecks.run(workerFile, Map.of(), finding -> { });

        assertTrue(findings.stream().anyMatch(f -> f.area().equals("team") && f.level() == Checks.Level.FAIL
                && f.message().startsWith("team: cannot reach ")), findings.toString());
        assertEquals(0, findings.stream().filter(f -> f.area().startsWith("project ")).count(),
                "without the team's list there is nothing to compare against: " + findings);
    }

    /** worker.yaml and worker.env as `dispatch worker init` writes them; {@code clone} null maps no project at all. */
    private Path writeWorker(String key, String clone) throws Exception {
        Path workerFile = dir.resolve("config/worker.yaml");
        Files.createDirectories(workerFile.getParent());
        StringBuilder yaml = new StringBuilder("team: 'http://127.0.0.1:" + api.port() + "'\nname: 'ann-laptop'\n")
                .append("stateDir: '").append(dir.resolve("state/worker")).append("'\n")
                .append("claudeCommand: '").append(JAVA).append("'\n")
                .append("ghCommand: '").append(JAVA).append("'\n");
        if (clone != null) {
            yaml.append("projects:\n  alm:\n    path: '").append(clone).append("'\n");
        }
        Files.writeString(workerFile, yaml.toString());
        SecretsFile.write(SecretsFile.beside(workerFile), Map.of(WorkerCommand.KEY_VARIABLE, key));
        return workerFile;
    }
}
