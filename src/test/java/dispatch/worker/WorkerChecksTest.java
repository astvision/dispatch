package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.cli.Checks;
import dispatch.cli.SecretsFile;
import dispatch.testing.GitFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

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

    /**
     * {@link WorkerChecks#readiness()} directly: it is the source of truth for every downstream decision and
     * message in this milestone (ADR 0021), so its mapping — which check lands in which field — needs its own
     * pin, not just incidental coverage through {@link #aWorkingWorkerPassesAndNamesItsTeamPairingAndProjects}'s
     * {@code Checks.Finding} assertions.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "alwaysSucceeds installs a POSIX shell script")
    void readinessReportsAHealthyComputer() throws Exception {
        WorkerConfig config = new WorkerConfig("http://127.0.0.1:" + api.port(), "ann-laptop", 1, JAVA,
                alwaysSucceeds("gh").toString(), dir,
                Map.of("alm", new WorkerConfig.Project(repos.repo("alm").toString(), null, null)));

        Readiness readiness = new WorkerChecks(config).readiness();

        assertTrue(readiness.claude().ok());
        assertNotNull(readiness.claude().detail(), "the version line, so a member can be told which one is too old");
        assertTrue(readiness.gh().ok());
        assertTrue(readiness.projects().get("alm").ok());
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "alwaysSucceeds installs a POSIX shell script")
    void readinessNamesTheCommandWhenClaudeCannotRunAndGhIsUnaffected() throws Exception {
        String noSuchClaude = dir.resolve("no-such-claude").toString();
        WorkerConfig config = new WorkerConfig("http://127.0.0.1:" + api.port(), "ann-laptop", 1, noSuchClaude,
                alwaysSucceeds("gh").toString(), dir, Map.of());

        Readiness readiness = new WorkerChecks(config).readiness();

        assertFalse(readiness.claude().ok());
        assertTrue(readiness.claude().detail().contains(noSuchClaude), readiness.claude().detail());
        assertTrue(readiness.gh().ok(), "claude's own failure must not drag gh's field down with it");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "alwaysSucceeds installs a POSIX shell script")
    void readinessMarksOnlyTheProjectWithAMissingClone() throws Exception {
        Path missing = dir.resolve("no-such-clone");
        WorkerConfig config = new WorkerConfig("http://127.0.0.1:" + api.port(), "ann-laptop", 1, JAVA,
                alwaysSucceeds("gh").toString(), dir,
                Map.of("alm", new WorkerConfig.Project(repos.repo("alm").toString(), null, null),
                        "other", new WorkerConfig.Project(missing.toString(), null, null)));

        Readiness readiness = new WorkerChecks(config).readiness();

        assertTrue(readiness.projects().get("alm").ok(), "the other project must stay ok");
        assertFalse(readiness.projects().get("other").ok());
        assertTrue(readiness.projects().get("other").detail().contains(missing.toString()),
                readiness.projects().get("other").detail());
    }

    /**
     * A command that always exits 0, whatever arguments it is called with — an authenticated {@code gh}, without
     * {@link dispatch.testing.FakeGh}'s own recording (it writes {@code fake-gh.args}/{@code fake-gh.env} into its
     * working directory, and {@link dispatch.cli.Setup#ghLoggedIn} runs it in this process's real {@code $HOME}).
     */
    private Path alwaysSucceeds(String name) throws Exception {
        Path script = Files.createDirectories(dir.resolve("bin")).resolve(name);
        Files.writeString(script, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
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
