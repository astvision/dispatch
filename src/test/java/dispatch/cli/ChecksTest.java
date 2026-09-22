package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.telegram.BotApi;
import dispatch.testing.FakeTelegram;
import dispatch.testing.GitFixture;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChecksTest {

    // Split, so secret scanners never see a token-shaped literal in this file.
    private static final String TOKEN = "123456789" + ":AAH-fake-token-for-tests-only-0123456789";
    // Split for the same reason; this one goes in a repo: URL's userinfo, not a config secret.
    private static final String REPO_CREDENTIAL = "x-access-token:" + "fake-repo-credential-for-tests-0123456789";
    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    @TempDir
    Path dir;

    private FakeTelegram telegram;
    private GitFixture repos;
    private Path config;

    @BeforeEach
    void setUp() throws IOException {
        telegram = FakeTelegram.start();
        repos = GitFixture.create(dir, "alm");
        config = dir.resolve("dispatch.yaml");
    }

    @AfterEach
    void tearDown() {
        telegram.close();
    }

    @Test
    void eachStepIsAFindingWithItsLevelAndArea() throws IOException {
        writeConfig();
        List<Checks.Finding> seen = new ArrayList<>();

        List<Checks.Finding> findings = checks().run(config, Map.of(), seen::add);

        assertEquals(findings, seen, "every finding is passed on as it is found");
        assertEquals(new Checks.Finding(Checks.Level.OK, "config", "config " + config), findings.getFirst());
        assertTrue(findings.contains(new Checks.Finding(Checks.Level.OK, "bot", "bot @" + FakeTelegram.BOT_USERNAME + " (topics off)")),
                findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.area().equals("project alm") && f.level() == Checks.Level.OK), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.area().equals("gh") && f.level() == Checks.Level.WARN), findings.toString());
        assertFalse(Checks.failed(findings), "warnings are not failures");
        assertFalse(findings.toString().contains(TOKEN));
    }

    @Test
    void aRepoUrlsCredentialsNeverAppearInTheNotClonedYetFinding() throws IOException {
        writeConfig();
        Files.writeString(config, Files.readString(config)
                .replace("    path: '" + quoted(repos.repo("alm")) + "'\n",
                        // ssh, not https: the config loader itself already rejects an http(s) repo URL with credentials.
                        "    repo: 'ssh://" + REPO_CREDENTIAL + "@example.com/alm.git'\n"));
        try (var files = Files.walk(repos.repo("alm"))) {
            files.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(file -> {
                file.setWritable(true);
                file.delete();
            });
        }

        List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

        Checks.Finding project = findings.stream().filter(f -> f.area().equals("project alm")).findFirst()
                .orElseThrow(() -> new AssertionError(findings.toString()));
        assertTrue(project.message().contains("not cloned yet"), project.message());
        assertFalse(project.message().contains(REPO_CREDENTIAL), project.message());
    }

    @Test
    void aMissingConfigIsOneFailure() {
        List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

        assertEquals(1, findings.size(), findings.toString());
        assertEquals(Checks.Level.FAIL, findings.getFirst().level());
        assertEquals("config", findings.getFirst().area());
        assertTrue(findings.getFirst().message().contains("dispatch init"), findings.toString());
        assertTrue(Checks.failed(findings));
    }

    private Checks checks() {
        return new Checks(token -> new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5)));
    }

    private void writeConfig() throws IOException {
        String yaml = new String(ChecksTest.class.getResourceAsStream("/personal.yaml").readAllBytes())
                .replace("STATE_DIR", quoted(dir.resolve("state")))
                .replace("CLONE", quoted(repos.repo("alm")))
                .replace("command: 'claude'", "command: '" + JAVA.replace("'", "''") + "'")
                .replace("  authorEmail: 'bold@example.com'\n", "  authorEmail: 'bold@example.com'\n  ghCommand: '" + JAVA.replace("'", "''") + "'\n");
        Files.writeString(config, yaml);
        SecretsFile.write(SecretsFile.beside(config), Map.of("TELEGRAM_BOT_TOKEN", TOKEN));
    }

    private static String quoted(Path path) {
        return path.toString().replace("'", "''");
    }
}
