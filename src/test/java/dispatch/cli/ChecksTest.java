package dispatch.cli;

import dispatch.agent.sandbox.Bubblewrap;
import dispatch.agent.sandbox.NoSandbox;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dispatch.agent.claude.OwnerPlugins;
import dispatch.telegram.BotApi;
import dispatch.testing.FakeClaude;
import dispatch.testing.FakeTelegram;
import dispatch.testing.GitFixture;
import dispatch.testing.OwnerPluginsFixture;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
        assertEquals(line(Checks.Level.OK, "config", "config " + config), line(findings.getFirst()));
        assertTrue(lines(findings).contains(line(Checks.Level.OK, "bot", "bot @" + FakeTelegram.BOT_USERNAME + " (topics off)")),
                findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.area().equals("project alm") && f.level() == Checks.Level.OK), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.area().equals("gh") && f.level() == Checks.Level.WARN), findings.toString());
        assertFalse(Checks.failed(findings), "warnings are not failures");
        assertFalse(findings.toString().contains(TOKEN));
    }

    /** M: several instances on one computer. */
    @Test
    void anotherInstanceWithTheSameBotOrPortOrBranchesInTheSameCloneIsReported() throws IOException {
        Locations defaults = Locations.of("Linux", Map.of(), dir);
        TestConfigs.write(defaults.configFile(), TOKEN, "alm");
        TestConfigs.write(defaults.forInstance("team").configFile(), TOKEN, "alm"); // same bot, same clone, no prefix
        for (Path yaml : List.of(defaults.configFile(), defaults.forInstance("team").configFile())) {
            Files.writeString(yaml, Files.readString(yaml) + "\nminiApp:\n  publicUrl: 'http://127.0.0.1:7879'\n  port: 7879\n");
        }

        List<Checks.Finding> findings = checks(defaults).run(defaults.forInstance("team").configFile(), Map.of(), finding -> { });

        assertTrue(findings.stream().anyMatch(f -> f.level() == Checks.Level.FAIL && f.message().english().contains("same bot as instance default")),
                findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.level() == Checks.Level.FAIL && f.message().english().contains("dispatch/<task> branches")),
                findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.level() == Checks.Level.WARN && f.message().english().contains("port 7879 is also instance")),
                findings.toString());
    }

    /** M: several instances on one computer. */
    @Test
    void aFileThatDoesNotLoadDoesNotFailAnotherInstancesCheck() throws IOException {
        Locations defaults = Locations.of("Linux", Map.of(), dir);
        TestConfigs.write(defaults.configFile(), TOKEN, "alm");
        Files.writeString(defaults.configDir().resolve("broken.yaml"), "team: [");

        List<Checks.Finding> findings = checks(defaults).run(defaults.configFile(), Map.of(), finding -> { });

        assertTrue(findings.stream().noneMatch(f -> f.area().equals("instances") && f.level() == Checks.Level.FAIL), findings.toString());
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
        assertTrue(project.message().english().contains("not cloned yet"), project.message().english());
        assertFalse(project.message().english().contains(REPO_CREDENTIAL), project.message().english());
        String mongolian = project.message().render(dispatch.Language.MN);
        assertTrue(mongolian.contains("клон хийгээгүй"), mongolian);
        assertFalse(mongolian.contains(REPO_CREDENTIAL), "masked in the page's language too: " + mongolian);
    }

    @Test
    void aMissingConfigIsOneFailure() {
        List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

        assertEquals(1, findings.size(), findings.toString());
        assertEquals(Checks.Level.FAIL, findings.getFirst().level());
        assertEquals("config", findings.getFirst().area());
        assertTrue(findings.getFirst().message().english().contains("dispatch init"), findings.toString());
        assertTrue(Checks.failed(findings));
    }

    @Test
    void anInvalidConfigsReasonIsWrittenInThePagesLanguage() throws IOException {
        writeConfig();
        Files.writeString(config, Files.readString(config).replace("maxConcurrentRuns: 1", "maxConcurrentRuns: 0"));

        List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

        assertEquals(1, findings.size(), findings.toString());
        String mongolian = findings.getFirst().message().render(dispatch.Language.MN);
        assertTrue(mongolian.contains("буруу байна"), mongolian);
        assertTrue(mongolian.contains("scheduler.maxConcurrentRuns: заавал, хамгийн багадаа 1"), mongolian);
    }

    @Test
    void teamModeWarnsRatherThanFailsWhenClaudeCannotRun() throws IOException {
        // ADR 0021: no task's agent ever runs on the team machine, but splitting a message with ✂️ still does, so a
        // missing claude there is worth a warning, not a failure that would also flip dispatch check's exit code.
        int port = freePort();
        writeConfig();
        Files.writeString(config, Files.readString(config)
                .replace("command: '" + JAVA.replace("'", "''") + "'", "command: 'dispatch-test-missing-claude-binary'")
                .replace("    - name: bold\n", "    - name: bold\n      chatId: -1001234567890\n")
                + "\nworkers:\n  publicUrl: 'http://127.0.0.1:" + port + "'\n  port: " + port + "\n");

        List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

        Checks.Finding claude = findings.stream().filter(f -> f.area().equals("claude-code")).findFirst()
                .orElseThrow(() -> new AssertionError(findings.toString()));
        assertEquals(Checks.Level.WARN, claude.level(), findings.toString());
        assertTrue(claude.message().english().contains("splitting a message"), claude.message().english());
        assertFalse(Checks.failed(findings), findings.toString());
    }

    /** ADR 0026: each agent is checked by its own CLI, and a missing one says what to install. */
    @Test
    void aMissingCodexSaysToInstallCodex() throws IOException {
        writeConfig();
        Files.writeString(config, Files.readString(config)
                .replace("agents:\n", "agents:\n  codex:\n    command: 'dispatch-test-missing-codex-binary'\n")
                .replace("    agent: claude-code\n", "    agent: codex\n"));

        List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

        Checks.Finding codex = area(findings, "codex");
        assertEquals(Checks.Level.FAIL, codex.level());
        assertEquals("codex: cannot run dispatch-test-missing-codex-binary; install the Codex CLI (npm install -g @openai/codex)"
                + " or set agents.codex.command to its full path", codex.message().english());
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the stand-in codex is a POSIX shell script")
    void aCodexThatIsNotLoggedInIsAWarning() throws IOException {
        Path codex = dir.resolve("codex");
        Files.writeString(codex, """
                #!/bin/sh
                if [ "$1" = "--version" ]; then echo "codex-cli 0.155.1"; exit 0; fi
                if [ "$1 $2" = "login status" ]; then echo "Not logged in" >&2; exit 1; fi
                exit 2
                """);
        Files.setPosixFilePermissions(codex, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        writeConfig();
        Files.writeString(config, Files.readString(config)
                .replace("agents:\n", "agents:\n  codex:\n    command: '" + quoted(codex) + "'\n"));

        List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

        Checks.Finding found = area(findings, "codex");
        assertEquals(Checks.Level.WARN, found.level(), findings.toString());
        assertEquals("codex: codex-cli 0.155.1, but not logged in: run " + codex + " login", found.message().english(),
                "the command as configured, which is the one to log in with");
    }

    private static Checks.Finding area(List<Checks.Finding> findings, String area) {
        return findings.stream().filter(f -> f.area().equals(area)).findFirst()
                .orElseThrow(() -> new AssertionError(findings.toString()));
    }

    @Test
    void aTeamMachineChecksItsWorkerPortAndAsksForNoGh() throws IOException {
        int free = freePort();
        writeTeamConfig(free);

        List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

        assertTrue(lines(findings).contains(line(Checks.Level.OK, "gh",
                "gh: not needed here; members' computers make the pull requests")), findings.toString());
        assertTrue(lines(findings).contains(line(Checks.Level.OK, "workers",
                "workers: nothing listens on 127.0.0.1:" + free + " yet; it starts with dispatch run")),
                findings.toString());
        assertFalse(Checks.failed(findings), findings.toString());
    }

    @Test
    void theMiniAppIsReportedOffUnlessItIsConfigured() throws IOException {
        writeConfig();

        List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

        assertTrue(lines(findings).contains(line(Checks.Level.OK, "miniApp",
                "miniApp: off; nothing is served to Telegram and the bot shows no Manage button")), findings.toString());
        assertFalse(Checks.failed(findings), findings.toString());
    }

    @Test
    void aConfiguredMiniAppIsCheckedOnItsPortAndItsPublicUrl() throws Exception {
        int free = freePort();
        writeConfig();
        Files.writeString(config, Files.readString(config)
                + "\nminiApp:\n  publicUrl: 'http://127.0.0.1:" + free + "'\n  port: " + free + "\n");

        List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

        assertTrue(lines(findings).contains(line(Checks.Level.OK, "miniApp",
                "miniApp: nothing listens on 127.0.0.1:" + free + " yet; it starts with dispatch run")), findings.toString());
        assertFalse(Checks.failed(findings), findings.toString());
    }

    @Test
    void aRunningMiniAppIsRecognisedOnItsPortAndItsPublicUrl() throws Exception {
        HttpServer dispatchLike = stubWorkerApi();
        try {
            int port = dispatchLike.getAddress().getPort();
            writeConfig();
            Files.writeString(config, Files.readString(config)
                    + "\nminiApp:\n  publicUrl: 'http://127.0.0.1:" + port + "'\n  port: " + port + "\n");

            List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

            assertTrue(lines(findings).contains(line(Checks.Level.OK, "miniApp",
                    "miniApp: 127.0.0.1:" + port + " answers as this Dispatch")), findings.toString());
            assertTrue(lines(findings).contains(line(Checks.Level.OK, "miniApp",
                    "miniApp: http://127.0.0.1:" + port + " reaches this Dispatch")), findings.toString());
        } finally {
            dispatchLike.stop(0);
        }
    }

    /** The case the owner most needs told: Dispatch is up, but the tunnel in front of it is not forwarding. */
    @Test
    void aMiniAppWhosePublicUrlDoesNotReachItWarnsAboutTheTunnel() throws Exception {
        HttpServer dispatchLike = stubWorkerApi();
        try {
            int port = dispatchLike.getAddress().getPort();
            int unreachable = freePort();
            writeConfig();
            Files.writeString(config, Files.readString(config)
                    + "\nminiApp:\n  publicUrl: 'http://127.0.0.1:" + unreachable + "'\n  port: " + port + "\n");

            List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

            Checks.Finding warning = findings.stream()
                    .filter(finding -> finding.area().equals("miniApp") && finding.level() == Checks.Level.WARN)
                    .findFirst().orElseThrow(() -> new AssertionError(findings.toString()));
            assertTrue(warning.message().english().contains("http://127.0.0.1:" + unreachable), warning.message().english());
            assertTrue(warning.message().english().contains("tunnel or reverse proxy"), warning.message().english());
            assertTrue(warning.message().english().contains("127.0.0.1:" + port), warning.message().english());
            assertFalse(Checks.failed(findings), "a tunnel that is not up yet is a warning, not a failure");
        } finally {
            dispatchLike.stop(0);
        }
    }

    @Test
    void aRunningTeamMachineRecognisesItsOwnWorkerApiAndItsPublicUrl() throws Exception {
        HttpServer dispatchLike = stubWorkerApi();
        try {
            writeTeamConfig(dispatchLike.getAddress().getPort());

            List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

            int port = dispatchLike.getAddress().getPort();
            assertTrue(lines(findings).contains(line(Checks.Level.OK, "workers",
                    "workers: 127.0.0.1:" + port + " answers as this Dispatch")), findings.toString());
            assertTrue(lines(findings).contains(line(Checks.Level.OK, "workers",
                    "workers: http://127.0.0.1:" + port + " reaches this Dispatch")), findings.toString());
        } finally {
            dispatchLike.stop(0);
        }
    }

    @Test
    void aTrailingSlashInThePublicUrlIsHarmless() throws Exception {
        HttpServer dispatchLike = stubWorkerApi();
        try {
            int port = dispatchLike.getAddress().getPort();
            writeTeamConfig(port, "http://127.0.0.1:" + port + "/");

            List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

            assertTrue(lines(findings).contains(line(Checks.Level.OK, "workers",
                    "workers: http://127.0.0.1:" + port + "/ reaches this Dispatch")), findings.toString());
        } finally {
            dispatchLike.stop(0);
        }
    }

    @Test
    void somethingElseOnTheWorkerPortIsAWarning() throws Exception {
        HttpServer other = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        other.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write("hi".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        other.start();
        try {
            writeTeamConfig(other.getAddress().getPort());

            List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

            assertTrue(lines(findings).contains(line(Checks.Level.WARN, "workers",
                    "workers: something other than Dispatch answers on 127.0.0.1:" + other.getAddress().getPort()
                            + "; stop it or set another workers.port")), findings.toString());
        } finally {
            other.stop(0);
        }
    }

    /** personal.yaml with a group chat, which makes it a team, and the workers block a team then needs. */
    private void writeTeamConfig(int port) throws IOException {
        writeTeamConfig(port, "http://127.0.0.1:" + port);
    }

    private void writeTeamConfig(int port, String publicUrl) throws IOException {
        writeConfig();
        Files.writeString(config, Files.readString(config)
                .replace("    - name: bold\n", "    - name: bold\n      chatId: -1001234567890\n")
                + "\nworkers:\n  publicUrl: '" + publicUrl + "'\n  port: " + port + "\n");
    }

    /** What Dispatch answers an unauthenticated worker request; nothing else answers exactly this. */
    /** Both of Dispatch's own refusals to an unsigned request: the worker API's, and the Mini App's. */
    private static HttpServer stubWorkerApi() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        com.sun.net.httpserver.HttpHandler unauthorized = exchange -> {
            byte[] body = "{\"error\":\"unauthorized\",\"message\":\"no\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        };
        server.createContext("/api/worker/projects", unauthorized);
        server.createContext("/api/me", unauthorized);
        server.start();
        return server;
    }

    private static int freePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private Checks checks() {
        // An isolated Locations, so the instances check never sees this machine's real config directory.
        return checks(Locations.of("Linux", Map.of(), dir));
    }

    private Checks checks(Locations defaults) {
        return new Checks(token -> new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5)), defaults);
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

    /** A finding as `dispatch check` prints it, with its level and area in front: comparable with one written out. */
    private static String line(Checks.Level level, String area, String message) {
        return level + " " + area + ": " + message;
    }

    private static String line(Checks.Finding finding) {
        return line(finding.level(), finding.area(), finding.message().english());
    }

    private static java.util.List<String> lines(java.util.List<Checks.Finding> findings) {
        return findings.stream().map(ChecksTest::line).toList();
    }

    @Test
    void sandboxFindingSaysWhichOrWhyNone() {
        Checks.Finding ok = Checks.sandbox(new Bubblewrap("/usr/bin/bwrap", true));
        assertEquals(Checks.Level.OK, ok.level());
        assertEquals("sandbox", ok.area());
        assertEquals("sandbox: bubblewrap", ok.message().english());

        Checks.Finding none = Checks.sandbox(new NoSandbox("bubblewrap (bwrap) is not installed"));
        assertEquals(Checks.Level.WARN, none.level());
        assertTrue(none.message().english().contains("bubblewrap (bwrap) is not installed"), none.message().english());
    }

    @Test
    void aSandboxWithoutOverlaysIsAWarning() {
        Checks.Finding finding = Checks.sandbox(new Bubblewrap("/usr/bin/bwrap", false));

        assertEquals(Checks.Level.WARN, finding.level());
        assertTrue(finding.message().english().contains("without overlays"), finding.message().english());
        assertTrue(finding.message().english().contains("Claude Code's state"), "MessageFormat keeps the apostrophe: "
                + finding.message().english());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aListedPluginThatIsMissingFailsTheCheckWithTheRunsText() throws IOException {
        Path claude = FakeClaude.install(Files.createDirectories(dir.resolve("bin")));
        OwnerPluginsFixture.installed(claude, Map.of());
        Path config = Files.writeString(dir.resolve("plugins.yaml"), "agents:\n  claude-code:\n    plugins: [a@m]\n");

        Checks.Finding finding = Checks.ownerPlugins(OwnerPlugins.instance(config, dir), claude.toString(),
                FakeClaude.environment()).orElseThrow();

        assertEquals(Checks.Level.FAIL, finding.level());
        assertTrue(finding.message().english().startsWith("claude-code plugin a@m is not installed on this computer"),
                finding.message().english());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void listedPluginsThatAreAllFoundPassAndNoListsSayNothing() throws IOException {
        Path claude = FakeClaude.install(Files.createDirectories(dir.resolve("bin")));
        OwnerPluginsFixture.installed(claude, Map.of("a@m", OwnerPluginsFixture.plugin(dir.resolve("cache/a"), null)));
        Path listed = Files.writeString(dir.resolve("plugins.yaml"), "agents:\n  claude-code:\n    plugins: [a@m]\n");
        Path none = Files.writeString(dir.resolve("none.yaml"), "agents:\n  claude-code:\n    command: claude\n");

        Checks.Finding finding = Checks.ownerPlugins(OwnerPlugins.instance(listed, dir), claude.toString(),
                FakeClaude.environment()).orElseThrow();

        assertEquals(Checks.Level.OK, finding.level());
        assertEquals("claude-code: 1 listed plugin(s) and 0 MCP server(s) found", finding.message().english());
        assertTrue(Checks.ownerPlugins(OwnerPlugins.instance(none, dir), claude.toString(), FakeClaude.environment()).isEmpty());
    }

    /** Resolved as the next run will resolve them, so what is missing shows before a task fails on it (spec: owner plugins). */
    @Test
    void dispatchCheckResolvesThisInstancesListedPlugins() throws IOException {
        writeConfig();
        Files.writeString(config, Files.readString(config).replace("agents:\n  claude-code:\n",
                "agents:\n  claude-code:\n    plugins: [a@m]\n"));

        List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

        assertTrue(findings.stream().anyMatch(f -> f.level() == Checks.Level.FAIL && f.area().equals("claude-code")
                && f.message().english().startsWith("cannot list Claude Code plugins")), findings.toString());
    }
}
