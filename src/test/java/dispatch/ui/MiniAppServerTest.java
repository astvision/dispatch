package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.config.Config;
import dispatch.config.ConfigLoader;
import dispatch.core.ActiveRuns;
import dispatch.core.Groups;
import dispatch.core.Projects;
import dispatch.core.TaskService;
import dispatch.store.Database;
import dispatch.testing.TestClock;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Mini App as `dispatch run` serves it: who reaches which route, and what loads before anyone has proved anything. */
class MiniAppServerTest {

    private static final String TOKEN = "123456:TEST-BOT-TOKEN";
    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private Database db;
    private UiServer server;
    private Groups groups;
    private Path configFile;

    @BeforeEach
    void setUp() throws IOException {
        configFile = writeConfig();
        Config config = ConfigLoader.load(configFile, Map.of("TELEGRAM_BOT_TOKEN", TOKEN));
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
        groups = new Groups(config.telegram());
        TaskService tasks = new TaskService(groups, new Projects(config.projects(), project -> Optional.empty()),
                new ActiveRuns(), new TestClock(NOW), () -> { }, () -> { });
        server = MiniApp.start(config, configFile, db, tasks, groups, "dispatch_backend_bot", token -> {
            // Only an unlink's best-effort leave asks for one, and it survives this as it survives Telegram failing.
            throw new IllegalStateException("no bot in these tests");
        }, Map.of("TELEGRAM_BOT_TOKEN", TOKEN), java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC), "/ui-test").orElseThrow();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        db.close();
    }

    @Test
    void thePageLoadsBeforeAnyoneHasProvedAnything() throws Exception {
        // Telegram puts the launch data in the URL fragment, which the browser never sends: the very first request
        // cannot carry it, because the script that reads it has not run yet.
        assertEquals(200, get("/", null).statusCode());
        assertEquals(200, get("/assets/app.js", null).statusCode(), "and the script that reads it must load too");
        assertEquals(200, get("/tasks", null).statusCode(), "a client-side route is the page as well");
        assertEquals(401, get("/api/me", null).statusCode(), "a route is a different matter");
    }

    @Test
    void telegramsClientsMayFrameThePage() throws Exception {
        HttpResponse<String> page = get("/", null);

        assertEquals(TelegramAuth.FRAME_ANCESTORS, page.headers().firstValue("Content-Security-Policy").orElseThrow());
        assertTrue(page.headers().firstValue("X-Frame-Options").isEmpty(), "DENY would leave members looking at nothing");
        assertEquals("nosniff", page.headers().firstValue("X-Content-Type-Options").orElseThrow());
    }

    @Test
    void aSignedRequestIsToldWhoItIsAndWhatItMayReach() throws Exception {
        HttpResponse<String> member = get("/api/me", initData(200));
        HttpResponse<String> admin = get("/api/me", initData(100));

        assertEquals(200, member.statusCode());
        assertTrue(member.body().contains("\"ref\":\"telegram:200\""), member.body());
        assertTrue(member.body().contains("\"admin\":false"), member.body());
        assertTrue(admin.body().contains("\"admin\":true"), admin.body());
        assertTrue(member.body().contains("\"bot\":\"dispatch_backend_bot\""), "the header names the bot: " + member.body());
    }

    @Test
    void aMemberIsListedOnlyTheProjectsOfTheirOwnGroups() throws Exception {
        HttpResponse<String> ali = get("/api/projects", initData(200));
        HttpResponse<String> bold = get("/api/projects", initData(100));

        assertEquals(200, ali.statusCode(), ali.body());
        assertTrue(ali.body().contains("\"name\":\"alm\""), ali.body());
        assertTrue(ali.body().contains("\"baseBranch\":\"main\""), ali.body());
        assertFalse(ali.body().contains("crm"), "Ali is not in the group that owns crm: " + ali.body());
        assertTrue(bold.body().contains("\"name\":\"crm\""), bold.body());
        assertTrue(bold.body().contains("\"alias\":\"c\""), bold.body());
        assertEquals(401, get("/api/projects", null).statusCode());
    }

    @Test
    void anyMemberReadsAndSavesTheirOwnGroupAckPreference() throws Exception {
        HttpResponse<String> defaulted = get("/api/me/prefs", initData(200));
        assertEquals(200, defaulted.statusCode());
        assertTrue(defaulted.body().contains("\"groupAck\":\"reaction\""), defaulted.body());

        HttpResponse<String> saved = post("/api/me/prefs", initData(200), "{\"groupAck\":\"silent\"}");
        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(saved.body().contains("\"groupAck\":\"silent\""), saved.body());
        assertTrue(get("/api/me/prefs", initData(200)).body().contains("\"groupAck\":\"silent\""), "read back what was saved");

        HttpResponse<String> invalid = post("/api/me/prefs", initData(200), "{\"groupAck\":\"loud\"}");
        assertEquals(400, invalid.statusCode());
        assertTrue(invalid.body().contains("\"error\":\"invalid\""), invalid.body());

        assertEquals(200, get("/api/me/prefs", initData(100)).statusCode(), "any member, not only an admin");
        assertEquals(401, get("/api/me/prefs", null).statusCode());
    }

    @Test
    void onlyAnAdminReachesTheManagementPages() throws Exception {
        HttpResponse<String> memberManaging = post("/api/manage/config", initData(200));
        HttpResponse<String> memberTasks = post("/api/tasks/list", initData(200));
        HttpResponse<String> adminManaging = post("/api/manage/config", initData(100));

        assertEquals(403, memberManaging.statusCode());
        assertTrue(memberManaging.body().contains("\"error\":\"not_admin\""), memberManaging.body());
        assertEquals(200, memberTasks.statusCode(), "their own tasks are theirs to see");
        assertEquals(200, adminManaging.statusCode(), adminManaging.body());
    }

    /** The Mini App runs inside the bot: an unlink applies to the running groups, where dispatch ui needs a restart. */
    @Test
    void anUnlinkHereAppliesToTheRunningBotWithoutARestart() throws Exception {
        String version = ManageApi.version(Files.readAllBytes(configFile));

        HttpResponse<String> unlinked = post("/api/manage/groups/unlink", initData(100),
                "{\"version\":\"" + version + "\",\"name\":\"mobile\"}");

        assertEquals(200, unlinked.statusCode(), unlinked.body());
        assertTrue(unlinked.body().contains("\"restartNeeded\":false"), unlinked.body());
        assertFalse(groups.isGroupChat("telegram:-1009876543210"), "the running bot no longer serves the chat");
        assertTrue(groups.isGroupChat("telegram:-1001234567890"), "the other group is untouched");
    }

    /** The rules themselves are {@link TasksApiTest}'s; here, that the task sheet's routes are served, signed only. */
    @Test
    void theTaskSheetsRoutesAreServedToASignedMember() throws Exception {
        for (String route : List.of("detail", "answer", "approve", "reject")) {
            HttpResponse<String> missing = post("/api/tasks/" + route, initData(200), "{\"taskId\":4242,\"planSeq\":1,\"index\":1,\"text\":\"x\"}");
            assertEquals(404, missing.statusCode(), route + ": " + missing.body());
            assertTrue(missing.body().contains("\"error\":\"not_found\""), missing.body());
            assertEquals(401, post("/api/tasks/" + route, null).statusCode(), route);
        }
    }

    @Test
    void theSetupRoutesAreNotHereAtAll() throws Exception {
        assertEquals(404, post("/api/setup/state", initData(100)).statusCode(), "Dispatch is never set up from Telegram");
        assertEquals(404, post("/api/setup/write", initData(100)).statusCode());
        assertEquals(401, post("/api/setup/state", null).statusCode(), "and unsigned it does not even get that far");
    }

    @Test
    void stoppingFreesThePort() throws Exception {
        int port = server.port();
        server.close();
        server = null;

        try (java.net.ServerSocket taken = new java.net.ServerSocket()) {
            taken.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            assertFalse(taken.isClosed());
        }
    }

    private HttpResponse<String> get(String path, String initData) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (initData != null) {
            request.header("Authorization", "tma " + initData);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String initData) throws Exception {
        return post(path, initData, "{}");
    }

    private HttpResponse<String> post(String path, String initData, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (initData != null) {
            request.header("Authorization", "tma " + initData);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    /** A real free port, because the config refuses port 0: it is a setting a person writes, not a test's shorthand. */
    private static int freePort() throws IOException {
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private Path writeConfig() throws IOException {
        Path stateDir = Files.createDirectories(dir.resolve("state"));
        Path configFile = dir.resolve("dispatch.yaml");
        int port = freePort();
        Files.writeString(configFile, """
                team: backend
                stateDir: %s
                telegram:
                  admins: [100]
                  groups:
                    - name: backend
                      chatId: -1001234567890
                      members:
                        - id: 100
                          name: Bold
                        - id: 200
                          name: Ali
                      projects: [alm]
                    - name: mobile
                      chatId: -1009876543210
                      members:
                        - id: 100
                          name: Bold
                      projects: [crm]
                delivery:
                  authorName: Dispatch (backend)
                  authorEmail: dispatch@example.com
                scheduler:
                  maxConcurrentRuns: 2
                limits:
                  plan: { timeout: 15m, budgetUsd: 2 }
                  execute: { timeout: 60m, budgetUsd: 10 }
                agents:
                  claude-code: { command: claude }
                projects:
                  - name: alm
                    repo: https://github.com/acme/alm.git
                    baseBranch: main
                    agent: claude-code
                  - name: crm
                    alias: c
                    repo: https://github.com/acme/crm.git
                    baseBranch: develop
                    agent: claude-code
                workers:
                  publicUrl: https://team.example.com
                  port: 7880
                miniApp:
                  publicUrl: http://127.0.0.1:%d
                  port: %d
                """.formatted(stateDir.toString().replace("\\", "/"), port, port));
        return configFile;
    }

    /** Launch data as Telegram signs it; the algorithm is spelled out again in {@link TelegramAuthTest}. */
    private static String initData(long userId) {
        Map<String, String> fields = new TreeMap<>(Map.of(
                "auth_date", String.valueOf(NOW.getEpochSecond()),
                "user", "{\"id\":" + userId + ",\"first_name\":\"Someone\"}"));
        List<String> checked = new ArrayList<>();
        fields.forEach((key, value) -> checked.add(key + "=" + value));
        String hash = java.util.HexFormat.of().formatHex(
                hmac(hmac("WebAppData".getBytes(StandardCharsets.UTF_8), TOKEN), String.join("\n", checked)));
        List<String> encoded = new ArrayList<>();
        fields.forEach((key, value) -> encoded.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return String.join("&", encoded) + "&hash=" + hash;
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
