package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.config.Config;
import dispatch.core.Groups;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.testing.TestClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Who may talk to the worker endpoints at all: the key check, the Host check, pairing and the setup route. */
class WorkerApiTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");

    @TempDir
    Path dir;

    private Database db;
    private WorkerKeys keys;
    private WorkerApi api;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(Instant.parse("2026-09-23T10:00:00Z"));

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
        keys = new WorkerKeys(db, clock);
        api = WorkerApi.start(config(), groups(), keys);
    }

    @AfterEach
    void tearDown() {
        api.close();
        db.close();
    }

    @Test
    void aCodePairsOnceOverHttpAndTheKeyIsTheOnlyThingReturned() throws Exception {
        String code = keys.newCode(BOLD);

        HttpResponse<String> answer = post(WorkerApi.PAIR, null, "{\"code\":\"" + code + "\",\"name\":\"ann-laptop\"}");

        assertEquals(200, answer.statusCode());
        JsonNode paired = Json.read(answer.body());
        assertEquals("backend", paired.get("team").asText());
        assertTrue(paired.get("workerId").asLong() > 0);
        assertEquals(43, paired.get("key").asText().length(), "32 random bytes as base64url without padding");
        HttpResponse<String> again = post(WorkerApi.PAIR, null, "{\"code\":\"" + code + "\",\"name\":\"ann-laptop\"}");
        assertEquals(401, again.statusCode());
        assertEquals("pairing_code", Json.read(again.body()).get("error").asText());
    }

    @Test
    void aLowercaseCodeStillPairs() throws Exception {
        String code = keys.newCode(BOLD);

        HttpResponse<String> answer = post(WorkerApi.PAIR, null,
                "{\"code\":\"" + code.toLowerCase() + "\",\"name\":\"ann-laptop\"}");

        assertEquals(200, answer.statusCode());
    }

    @Test
    void everyOtherRouteNeedsAKeyAndAWrongOneIsRefusedWhereverItDiffers() throws Exception {
        String key = pair(BOLD, "ann-laptop");

        assertEquals(200, post(WorkerApi.PROJECTS, key, "{}").statusCode());
        assertEquals(401, post(WorkerApi.PROJECTS, null, "{}").statusCode());
        assertEquals(401, post(WorkerApi.PROJECTS, "not-a-key", "{}").statusCode());
        // The compare is MessageDigest.isEqual over the hashes, so a near miss is refused like anything else.
        HttpResponse<String> firstCharDiffers = post(WorkerApi.PROJECTS, (key.charAt(0) == 'A' ? 'B' : 'A') + key.substring(1), "{}");
        HttpResponse<String> lastCharDiffers = post(WorkerApi.PROJECTS,
                key.substring(0, key.length() - 1) + (key.endsWith("A") ? "B" : "A"), "{}");
        assertEquals(401, firstCharDiffers.statusCode());
        assertEquals(401, lastCharDiffers.statusCode());
        assertEquals("unauthorized", Json.read(firstCharDiffers.body()).get("error").asText());
        assertEquals(Json.read(firstCharDiffers.body()), Json.read(lastCharDiffers.body()), "and refused the same way");
    }

    @Test
    void aRevokedKeyIsAsGoodAsAnUnknownOneAndSaysToPairAgain() throws Exception {
        String key = pair(BOLD, "ann-laptop");
        String stranger = pair(ALI, "bob-laptop");

        keys.revoke(keys.of(BOLD.ref()).getFirst().id(), BOLD.ref(), false);

        HttpResponse<String> revoked = post(WorkerApi.PROJECTS, key, "{}");
        assertEquals(401, revoked.statusCode());
        assertEquals("unauthorized", Json.read(revoked.body()).get("error").asText(),
                "a revoked key and an unknown one answer alike, so nobody can probe which keys existed");
        assertTrue(revoked.body().contains("dispatch worker pair"), revoked.body());
        assertEquals(200, post(WorkerApi.PROJECTS, stranger, "{}").statusCode(), "Ali's computer is untouched");
    }

    @Test
    void aRequestForAnotherHostIsRefused() throws Exception {
        String key = pair(BOLD, "ann-laptop");

        // HttpClient refuses to send its own Host header (a restricted header per RFC 7230), so this one request is
        // written by hand, as UiServerTest's DNS-rebinding test does.
        Raw answer = rawPost(WorkerApi.PROJECTS, "evil.example.com", key, "{}");

        assertEquals(403, answer.status());
        assertEquals("host", Json.read(answer.body()).get("error").asText());
    }

    @Test
    void theKeyIsNeverLogged() throws Exception {
        PrintStream out = System.out;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        String key;
        try {
            System.setOut(new PrintStream(logged, true, StandardCharsets.UTF_8));
            key = pair(BOLD, "ann-laptop");
            post(WorkerApi.PROJECTS, key, "{}");
            post(WorkerApi.PROJECTS, key + "x", "{}");
        } finally {
            System.setOut(out);
        }

        String lines = logged.toString(StandardCharsets.UTF_8);
        assertFalse(lines.contains(key), lines);
        assertTrue(lines.contains("worker.paired"), lines);
    }

    @Test
    void theSetupRouteAnswersTheMembersProjectsAndTheTeamsCommitAuthor() throws Exception {
        String key = pair(BOLD, "ann-laptop");

        JsonNode answer = Json.read(post(WorkerApi.PROJECTS, key, "{}").body());

        assertEquals("backend", answer.get("team").asText());
        assertEquals("Dispatch (backend)", answer.get("authorName").asText());
        assertEquals(1, answer.get("projects").size());
        assertEquals("alm", answer.get("projects").get(0).get("name").asText());
        assertEquals("git@github.com:acme/alm.git", answer.get("projects").get(0).get("repo").asText());
        assertEquals("main", answer.get("projects").get(0).get("baseBranch").asText());
    }

    @Test
    void aGetAndAnOversizedBodyAreRefusedWithoutTouchingTheKey() throws Exception {
        String key = pair(BOLD, "ann-laptop");

        HttpResponse<String> get = http.send(HttpRequest.newBuilder(uri(WorkerApi.PROJECTS))
                .header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> huge = post(WorkerApi.PROJECTS, key, "{\"x\":\"" + "y".repeat(70_000) + "\"}");
        HttpResponse<String> unknown = post("/api/worker/nope", key, "{}");

        assertEquals(405, get.statusCode());
        assertEquals(413, huge.statusCode());
        assertEquals(404, unknown.statusCode());
    }

    private String pair(Requester member, String name) throws Exception {
        return Json.read(post(WorkerApi.PAIR, null, pairBody(keys.newCode(member), name)).body()).get("key").asText();
    }

    private static String pairBody(String code, String name) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}";
    }

    private HttpResponse<String> post(String path, String key, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + api.port() + path);
    }

    private record Raw(int status, String body) {
    }

    private Raw rawPost(String path, String host, String key, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String head = "POST " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Authorization: Bearer " + key + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        try (Socket socket = new Socket("127.0.0.1", api.port())) {
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int status = Integer.parseInt(response.substring(9, 12));
            return new Raw(status, response.substring(response.indexOf("\r\n\r\n") + 4));
        }
    }

    private Config config() {
        return new Config("backend", dir, new Config.Telegram(List.of(), List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm")))),
                new Config.Scheduler(2), Config.Worktrees.DEFAULT,
                new Config.Limits(new Config.RunLimits(Duration.ofMinutes(30), new java.math.BigDecimal("2")),
                        new Config.RunLimits(Duration.ofMinutes(60), new java.math.BigDecimal("10"))),
                Map.of("claude-code", new Config.Agent("claude")),
                List.of(new Config.Project("alm", null, "git@github.com:acme/alm.git", null, "main", "claude-code", "opus",
                        "high", List.of(), null, null, null)),
                new Config.Delivery("Dispatch (backend)", "dispatch-backend@example.com", "gh"),
                new Config.Workers("http://127.0.0.1:0", 0), new Config.Secrets("token", null));
    }

    private Groups groups() {
        return new Groups(List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm"))));
    }
}
