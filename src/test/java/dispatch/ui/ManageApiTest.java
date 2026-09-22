package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.cli.CliException;
import dispatch.cli.Service;
import dispatch.config.Config;
import dispatch.config.ConfigLoader;
import dispatch.testing.GitFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ManageApiTest {

    // Split, so secret scanners never see a token-shaped literal in this file.
    private static final String TOKEN = "123456789" + ":AAH-fake-token-for-tests-only-0123456789";
    private static final String CUSTOM_SECRET = "my-secret-" + "custom-value-not-matching-pattern";
    private static final String TEAM = """
            # Our team's Dispatch
            team: acme
            stateDir: STATE

            telegram:
              admins:            # who lets people join
                - 100
              groups:
                - name: acme
                  chatId: -1001234567890
                  members:
                    - id: 100
                      name: 'Bold'
                    - id: 222
                      name: 'Ali'   # backend
                  projects:
                    - alm
                    - crm

            delivery:
              authorName: 'Dispatch (acme)'
              authorEmail: 'dispatch@example.com'

            scheduler:
              maxConcurrentRuns: 2

            limits:
              plan:
                timeout: 15m
                budgetUsd: 2
              execute:
                timeout: 60m
                budgetUsd: 10

            agents:
              claude-code:
                command: 'claude'

            projects:
              - name: alm
                path: ALM
                baseBranch: main
                agent: claude-code
              - name: crm
                path: CRM
                baseBranch: main
                agent: claude-code
                model: opus     # the big one
            """;

    @TempDir
    Path dir;

    private Path config;
    private String original;
    private final StubService service = new StubService();
    private ManageApi manage;

    @BeforeEach
    void setUp() throws IOException {
        config = dir.resolve("dispatch.yaml");
        original = TEAM.replace("STATE", yamlPath(dir.resolve("state"))).replace("ALM", yamlPath(dir.resolve("alm")))
                .replace("CRM", yamlPath(dir.resolve("crm")));
        Files.writeString(config, original);
        manage = new ManageApi(config, service, Map.of("TELEGRAM_BOT_TOKEN", TOKEN));
    }

    @Test
    void theConfigIsShownWithItsVersionAndWithoutSecrets() throws Exception {
        JsonNode view = call("/api/manage/config", "{}");

        assertEquals(sha256(original), view.path("version").asText());
        assertFalse(view.path("personal").asBoolean());
        assertEquals("15m", view.path("settings").path("planTimeout").asText());
        assertEquals("1h", view.path("settings").path("executeTimeout").asText());
        assertEquals(10, view.path("settings").path("executeBudgetUsd").asInt());
        assertEquals("gh", view.path("settings").path("ghCommand").asText(), "the default when the config names none");
        assertEquals("crm", view.path("projects").get(1).path("name").asText());
        assertEquals("opus", view.path("projects").get(1).path("model").asText());
        assertEquals("acme", view.path("projects").get(1).path("group").asText());
        assertTrue(view.path("groups").get(0).path("members").get(0).path("admin").asBoolean(), "Bold is an admin");
        assertFalse(view.path("groups").get(0).path("members").get(1).path("admin").asBoolean(), "Ali is not");
        assertFalse(Json.write(view).contains(TOKEN));
    }

    @Test
    void savingSettingsChangesOnlyTheirValuesAndKeepsTheRestAsWritten() throws Exception {
        JsonNode saved = call("/api/manage/settings", settings(version(), "3", "/opt/gh/bin/gh"));

        String expected = original.replace("    budgetUsd: 2\n", "    budgetUsd: 3\n")
                .replace("  authorEmail: 'dispatch@example.com'\n", "  authorEmail: 'dispatch@example.com'\n  ghCommand: /opt/gh/bin/gh\n");
        assertEquals(expected, Files.readString(config), "60m stays 60m although the page shows it as 1h");
        assertEquals(original, Files.readString(dir.resolve("dispatch.yaml.bak")), "the previous text is kept");
        assertTrue(saved.path("saved").asBoolean() && saved.path("restartNeeded").asBoolean(), saved.toString());
        assertEquals(sha256(expected), saved.path("version").asText());
    }

    @Test
    void aSaveAgainstAConfigChangedOnDiskIsRefusedAndChangesNothing() throws Exception {
        String stale = version();
        String changedElsewhere = original.replace("maxConcurrentRuns: 2", "maxConcurrentRuns: 4");
        Files.writeString(config, changedElsewhere);

        ApiException refused = assertThrows(ApiException.class, () -> call("/api/manage/settings", settings(stale, "3", "gh")));

        assertEquals(409, refused.status());
        assertEquals("changed", refused.code());
        assertEquals("the config changed on disk since this page loaded it; reload to see the change", refused.getMessage());
        assertEquals(changedElsewhere, Files.readString(config));
        assertFalse(Files.exists(dir.resolve("dispatch.yaml.bak")));
    }

    @Test
    void anInvalidSaveChangesNothing() throws Exception {
        byte[] before = Files.readAllBytes(config);
        String body = settings(version(), "2", "gh").replace("\"maxConcurrentRuns\":2", "\"maxConcurrentRuns\":0");

        CliException refused = assertThrows(CliException.class, () -> call("/api/manage/settings", body));

        assertTrue(refused.getMessage().contains("scheduler.maxConcurrentRuns: required, at least 1"), refused.getMessage());
        assertTrue(refused.getMessage().contains(config.toString()), "names the config, not a draft: " + refused.getMessage());
        assertArrayEquals(before, Files.readAllBytes(config));
        assertFalse(Files.exists(dir.resolve("dispatch.yaml.bak")), "a save that fails validation must not touch the backup either");
    }

    @Test
    void aProjectIsAddedFromAClone() throws Exception {
        GitFixture repos = GitFixture.create(dir, "life");

        call("/api/manage/projects/add", """
                {"version":"%s","folder":"%s","name":"life","baseBranch":"main","alias":"lf","plan":{"model":"opus"}}"""
                .formatted(version(), json(repos.repo("life").toString())));

        Config loaded = load();
        Config.Project life = loaded.projects().get(2);
        assertEquals("lf", life.alias());
        assertEquals("opus", life.planModel());
        assertEquals(repos.origin.toString(), life.repo(), "origin comes from the clone, not from the page");
        assertEquals(List.of("alm", "crm", "life"), loaded.telegram().groups().getFirst().projects());
        assertTrue(Files.readString(config).startsWith("# Our team's Dispatch\n"), "comments stay");
    }

    @Test
    void aProjectWithACarriageReturnInBaseBranchIsRefusedAndChangesNothing() throws Exception {
        GitFixture repos = GitFixture.create(dir, "life");
        byte[] before = Files.readAllBytes(config);

        CliException refused = assertThrows(CliException.class, () -> call("/api/manage/projects/add", """
                {"version":"%s","folder":"%s","name":"life","baseBranch":"main\\rx"}"""
                .formatted(version(), json(repos.repo("life").toString()))));

        assertEquals("a value must be plain text on one line", refused.getMessage());
        assertArrayEquals(before, Files.readAllBytes(config));
        assertFalse(Files.exists(dir.resolve("dispatch.yaml.bak")));
    }

    @Test
    void aProjectIsEditedFieldByField() throws Exception {
        call("/api/manage/projects/edit", """
                {"version":"%s","name":"crm","baseBranch":"develop","alias":null,"model":null,"effort":"high",
                 "plan":{"model":"fable","effort":null},"execute":null}""".formatted(version()));

        assertEquals(original.replace("""
                    baseBranch: main
                    agent: claude-code
                    model: opus     # the big one
                """, """
                    baseBranch: develop
                    agent: claude-code
                    effort: high
                    plan:
                      model: fable
                """), Files.readString(config));
    }

    @Test
    void aModelWithASpaceOrAQuoteIsRefused() throws Exception {
        CliException spaced = assertThrows(CliException.class, () -> call("/api/manage/projects/edit", """
                {"version":"%s","name":"crm","baseBranch":"main","alias":null,"model":"claude opus","effort":null,
                 "plan":null,"execute":null}""".formatted(version())));
        CliException quoted = assertThrows(CliException.class, () -> call("/api/manage/projects/edit", """
                {"version":"%s","name":"crm","baseBranch":"main","alias":null,"model":"opus'","effort":null,
                 "plan":null,"execute":null}""".formatted(version())));

        assertEquals("model: use a model name like opus or a model id like claude-opus-5", spaced.getMessage());
        assertEquals("model: use a model name like opus or a model id like claude-opus-5", quoted.getMessage());
    }

    @Test
    void aFullModelIdIsAccepted() throws Exception {
        call("/api/manage/projects/edit", """
                {"version":"%s","name":"crm","baseBranch":"main","alias":null,"model":"claude-opus-5","effort":null,
                 "plan":null,"execute":null}""".formatted(version()));

        assertEquals("claude-opus-5", load().projects().get(1).model());
    }

    @Test
    void aProjectIsRemovedFromTheProjectsAndItsGroup() throws Exception {
        call("/api/manage/projects/remove", "{\"version\":\"" + version() + "\",\"name\":\"crm\"}");

        assertEquals(original.replace("        - crm\n", "").replace("""
                  - name: crm
                    path: %s
                    baseBranch: main
                    agent: claude-code
                    model: opus     # the big one
                """.formatted(yamlPath(dir.resolve("crm"))), ""), Files.readString(config));
    }

    @Test
    void theLastProjectIsKept() throws Exception {
        call("/api/manage/projects/remove", "{\"version\":\"" + version() + "\",\"name\":\"crm\"}");

        CliException refused = assertThrows(CliException.class,
                () -> call("/api/manage/projects/remove", "{\"version\":\"" + version() + "\",\"name\":\"alm\"}"));

        assertEquals("alm is the only project; add another before removing it", refused.getMessage());
    }

    @Test
    void aNoOpSaveDoesNotTouchTheBackupAndSaysNoRestartIsNeeded() throws Exception {
        Files.writeString(dir.resolve("dispatch.yaml.bak"), "sentinel: previous backup\n");

        JsonNode saved = call("/api/manage/people/admin", "{\"version\":\"" + version() + "\",\"id\":100,\"admin\":true}");

        assertTrue(saved.path("saved").asBoolean(), saved.toString());
        assertFalse(saved.path("restartNeeded").asBoolean(), "Bold is already admin: nothing actually changed: " + saved);
        assertEquals(sha256(original), saved.path("version").asText(), "the text, and so its version, is unchanged");
        assertEquals(original, Files.readString(config), "nothing changed on disk");
        assertEquals("sentinel: previous backup\n", Files.readString(dir.resolve("dispatch.yaml.bak")),
                "the real previous backup must not be lost to a save that changed nothing");
    }

    @Test
    void peopleAreRenamedMadeAdminAndRemoved() throws Exception {
        call("/api/manage/people/rename", "{\"version\":\"" + version() + "\",\"id\":222,\"name\":\"Ali Ba'ba\"}");
        call("/api/manage/people/admin", "{\"version\":\"" + version() + "\",\"id\":222,\"admin\":true}");
        call("/api/manage/people/admin", "{\"version\":\"" + version() + "\",\"id\":100,\"admin\":false}");

        assertEquals(original.replace("name: 'Ali'   # backend", "name: 'Ali Ba''ba'   # backend")
                .replace("    - 100\n", "    - 222\n"), Files.readString(config));
        assertEquals(List.of(222L), load().telegram().admins());

        call("/api/manage/people/remove", "{\"version\":\"" + version() + "\",\"group\":\"acme\",\"id\":100}");

        assertEquals(List.of(new Config.Member(222, "Ali Ba'ba")), load().telegram().groups().getFirst().members());
    }

    @Test
    void aTeamKeepsAMemberInEachGroupAndAnAdmin() throws Exception {
        CliException lastAdmin = assertThrows(CliException.class,
                () -> call("/api/manage/people/admin", "{\"version\":\"" + version() + "\",\"id\":100,\"admin\":false}"));
        CliException onlyAdminLeaving = assertThrows(CliException.class,
                () -> call("/api/manage/people/remove", "{\"version\":\"" + version() + "\",\"group\":\"acme\",\"id\":100}"));
        call("/api/manage/people/remove", "{\"version\":\"" + version() + "\",\"group\":\"acme\",\"id\":222}");
        CliException lastMember = assertThrows(CliException.class,
                () -> call("/api/manage/people/remove", "{\"version\":\"" + version() + "\",\"group\":\"acme\",\"id\":100}"));

        assertEquals("Bold is the team's only admin; make someone else admin first", lastAdmin.getMessage());
        assertEquals("Bold is the team's only admin; make someone else admin first", onlyAdminLeaving.getMessage());
        assertEquals("Bold is the only member of group acme, and a group needs one", lastMember.getMessage());
    }

    @Test
    void anAdminIdWithNoMatchingMemberDoesNotCountAsASecondAdmin() throws Exception {
        Files.writeString(config, original.replace("    - 100\n", "    - 100\n    - 999\n"));

        CliException demoteRefused = assertThrows(CliException.class,
                () -> call("/api/manage/people/admin", "{\"version\":\"" + version() + "\",\"id\":100,\"admin\":false}"));
        CliException removeRefused = assertThrows(CliException.class,
                () -> call("/api/manage/people/remove", "{\"version\":\"" + version() + "\",\"group\":\"acme\",\"id\":100}"));

        assertEquals("Bold is the team's only admin; make someone else admin first", demoteRefused.getMessage());
        assertEquals("Bold is the team's only admin; make someone else admin first", removeRefused.getMessage());
    }

    @Test
    void aPersonalBotHasNoAdminsToChange() throws Exception {
        String personal = new String(ManageApiTest.class.getResourceAsStream("/personal.yaml").readAllBytes())
                .replace("STATE_DIR", dir.resolve("state").toString().replace("'", "''"))
                .replace("CLONE", dir.resolve("alm").toString().replace("'", "''"));
        Files.writeString(config, personal);

        CliException refused = assertThrows(CliException.class,
                () -> call("/api/manage/people/admin", "{\"version\":\"" + version() + "\",\"id\":123456789,\"admin\":true}"));

        assertTrue(call("/api/manage/config", "{}").path("personal").asBoolean());
        assertTrue(refused.getMessage().startsWith("a personal bot has no admins"), refused.getMessage());
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permissions")
    void theBackupHasTheConfigsPermissions() throws Exception {
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));

        call("/api/manage/settings", settings(version(), "3", "gh"));

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("dispatch.yaml.bak"))));
    }

    @Test
    void theLogShowsItsLastLinesFilteredAndRedacted() throws Exception {
        Path log = dir.resolve("state/dispatch.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, """
                ts=2026-09-22T10:00:00.000Z level=INFO event=app.started
                ts=2026-09-22T10:00:01.000Z level=WARN event=telegram.poll_failed error="bot %s refused"
                ts=2026-09-22T10:00:02.000Z level=INFO event=task.created task=1
                ts=2026-09-22T10:00:03.000Z level=ERROR event=task.run_failed task=1
                """.formatted(TOKEN));

        JsonNode all = call("/api/manage/logs", "{}");
        JsonNode warnings = call("/api/manage/logs", "{\"level\":\"WARN\"}");
        JsonNode lastTask = call("/api/manage/logs", "{\"event\":\"task.\",\"lines\":1}");

        assertTrue(all.path("exists").asBoolean());
        assertEquals(log.toString(), all.path("file").asText());
        assertEquals(4, all.path("lines").size());
        assertEquals("ts=2026-09-22T10:00:01.000Z level=WARN event=telegram.poll_failed error=\"bot [redacted] refused\"",
                warnings.path("lines").get(0).asText());
        assertEquals(1, warnings.path("lines").size());
        assertEquals("ts=2026-09-22T10:00:03.000Z level=ERROR event=task.run_failed task=1", lastTask.path("lines").get(0).asText());
        assertEquals(1, lastTask.path("lines").size());
    }

    @Test
    void beforeTheServiceWritesItsLogThereIsNone() throws Exception {
        JsonNode logs = call("/api/manage/logs", "{}");

        assertFalse(logs.path("exists").asBoolean());
        assertEquals(0, logs.path("lines").size());
        assertEquals(dir.resolve("state/dispatch.log").toString(), logs.path("file").asText());
    }

    @Test
    void aLongLogIsReadFromItsEnd() throws IOException {
        Path log = dir.resolve("long.log");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40_000; i++) {
            text.append("ts=2026-09-22T10:00:00.000Z level=INFO event=tick i=").append(i).append('\n');
        }
        Files.writeString(log, text);

        assertEquals(List.of("ts=2026-09-22T10:00:00.000Z level=INFO event=tick i=39998", "ts=2026-09-22T10:00:00.000Z level=INFO event=tick i=39999"),
                ManageApi.tail(log, 2, null, null));
        assertTrue(ManageApi.tail(log, 40_000, null, null).getFirst().startsWith("ts="), "a line cut by the 1 MiB window is left out");
    }

    @Test
    void aCutExactlyAtALineBoundaryIsNotDiscarded() throws IOException {
        Path log = dir.resolve("boundary.log");
        // Deterministic: every line is exactly 1024 bytes (1023 chars + '\n')
        // TAIL_BYTES = 1024 * 1024, so 1024 lines = 1 MiB exactly
        // Write 1024 + 10 lines, so the cut lands exactly after line 1024's '\n'
        int lineSize = 1024;
        int numLines = (ManageApi.TAIL_BYTES / lineSize) + 10;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < numLines; i++) {
            // Start with line index; pad to lineSize - 1 with 'a', then add '\n'
            String line = String.format("ts=x level=INFO event=line.%08d ", i);
            while (line.length() < lineSize - 1) {
                line += 'a';
            }
            text.append(line).append('\n');
        }
        Files.writeString(log, text);

        List<String> result = ManageApi.tail(log, 2000, null, null);

        // The first returned line should be from line index 10 (the first complete line in the window)
        assertTrue(result.size() > 0, "should have at least one line in the window");
        assertTrue(result.get(0).contains("line.00000010"),
                "first line in window should be line 10, not a mid-line fragment: " + result.get(0));
    }

    @Test
    void restartGoesThroughTheServiceOnlyWhenItIsInstalled() throws Exception {
        JsonNode restarted = call("/api/service/restart", "{}");
        service.installed = false;
        CliException notInstalled = assertThrows(CliException.class, () -> call("/api/service/restart", "{}"));

        assertEquals(List.of("stop", "start"), service.actions);
        assertTrue(restarted.path("running").asBoolean(), restarted.toString());
        assertEquals("Dispatch does not run as a background service here; stop it and start it again where it runs", notInstalled.getMessage());
    }

    @Test
    void multiLinePrivateKeysAreRedactedAsAWhole() throws Exception {
        Path log = dir.resolve("state/dispatch.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "ts=2026-09-22T10:00:00.000Z level=INFO event=app.started\n"
                + "-----BEGIN PRIVATE KEY-----\n"
                + "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDJ+lZjZ2J3K+Z5\n"
                + "-----END PRIVATE KEY-----\n"
                + "ts=2026-09-22T10:00:01.000Z level=INFO event=app.ready\n");

        JsonNode logs = call("/api/manage/logs", "{}");
        String response = Json.write(logs);

        assertFalse(response.contains("BEGIN PRIVATE KEY"), "PEM key header should be redacted");
        assertFalse(response.contains("MIIEvgIBADANBgk"), "PEM key body should be redacted");
        assertTrue(response.contains("[redacted]"), "should contain redaction marker");
        assertEquals(3, logs.path("lines").size(), "should have 3 lines: before PEM, [redacted], and after PEM");
    }

    @Test
    void customSecretsInFileAreRedacted() throws Exception {
        Path secretFile = dir.resolve("dispatch.env");
        Files.writeString(secretFile, "GH_TOKEN=" + CUSTOM_SECRET + "\n");
        // Set proper permissions so SecretsFile doesn't reject it
        try {
            Files.setPosixFilePermissions(secretFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Skip on non-POSIX systems; the test will still work but without the permission check
        }

        Path log = dir.resolve("state/dispatch.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "ts=2026-09-22T10:00:00.000Z level=INFO event=app.started token=" + CUSTOM_SECRET + "\n");

        JsonNode logs = call("/api/manage/logs", "{}");

        assertFalse(logs.path("lines").get(0).asText().contains(CUSTOM_SECRET),
                "custom secret should be redacted: " + logs.path("lines").get(0).asText());
        assertTrue(logs.path("lines").get(0).asText().contains("[redacted]"), "should contain redaction marker");
    }

    private String settings(String version, String planBudget, String gh) {
        return """
                {"version":"%s","planTimeout":"15m","planBudgetUsd":%s,"executeTimeout":"1h","executeBudgetUsd":10,
                 "maxConcurrentRuns":2,"authorName":"Dispatch (acme)","authorEmail":"dispatch@example.com",
                 "claudeCommand":"claude","ghCommand":"%s"}""".formatted(version, planBudget, gh);
    }

    private String version() throws Exception {
        return call("/api/manage/config", "{}").path("version").asText();
    }

    private Config load() {
        return ConfigLoader.load(config, Map.of("TELEGRAM_BOT_TOKEN", TOKEN));
    }

    private JsonNode call(String path, String body) throws Exception {
        Object answer = manage.routes().get(path).apply(Json.MAPPER.readTree(body));
        return Json.MAPPER.readTree(Json.write(answer));
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String yamlPath(Path path) {
        return "'" + path.toString().replace("'", "''") + "'";
    }

    private static String json(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** A background service that records what it was asked to do. */
    static final class StubService implements Service {

        final List<String> actions = new ArrayList<>();
        boolean installed = true;

        @Override
        public String describe() {
            return "stub service";
        }

        @Override
        public void install(Service.Spec spec) {
            actions.add("install");
        }

        @Override
        public void start() {
            actions.add("start");
        }

        @Override
        public void stop() {
            actions.add("stop");
        }

        @Override
        public Service.Status status() {
            return new Service.Status(installed, installed, installed ? "running" : "not installed", List.of());
        }

        @Override
        public void uninstall() {
            actions.add("uninstall");
        }
    }
}
