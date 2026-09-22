package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void aGetAndAnOversizedBodyAreRefusedWithoutTouchingTheKeyAtAll() throws Exception {
        // No key on any of these: each is refused for a reason that has nothing to do with who is asking.
        HttpResponse<String> get =
                http.send(HttpRequest.newBuilder(uri(WorkerApi.PROJECTS)).GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> huge = post(WorkerApi.PROJECTS, null, "{\"x\":\"" + "y".repeat(70_000) + "\"}");
        HttpResponse<String> unknown = post("/api/worker/nope", null, "{}");

        assertEquals(405, get.statusCode());
        assertEquals("method", Json.read(get.body()).get("error").asText());
        assertEquals(413, huge.statusCode());
        assertEquals("too_large", Json.read(huge.body()).get("error").asText());
        assertEquals(404, unknown.statusCode());
        assertEquals("not_found", Json.read(unknown.body()).get("error").asText());
    }

    @Test
    void malformedJsonIsRefused() throws Exception {
        String key = pair(BOLD, "ann-laptop");

        HttpResponse<String> answer = post(WorkerApi.PROJECTS, key, "{not json");

        assertEquals(400, answer.statusCode());
        assertEquals("invalid", Json.read(answer.body()).get("error").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {WorkerApi.NEXT, WorkerApi.PROGRESS, WorkerApi.ATTACHMENT, WorkerApi.RESULT})
    void everyProtocolRouteRefusesWithoutAKeyEvenBeforeItExists(String path) throws Exception {
        // Task 7 wires these routes' bodies; this only pins that the key check in front of them stays in place.
        HttpResponse<String> answer = post(path, null, "{}");

        assertEquals(401, answer.statusCode());
        assertEquals("unauthorized", Json.read(answer.body()).get("error").asText());
    }

    @Test
    void aControlCharacterOnlyNameIsRefusedAsInvalidNotA500() throws Exception {
        String code = keys.newCode(BOLD);

        Captured captured = capturingStdout(() ->
                post(WorkerApi.PAIR, null, "{\"code\":\"" + code + "\",\"name\":\"\\u0000\\u0001\\u0002\"}"));

        assertEquals(400, captured.answer().statusCode());
        assertEquals("invalid", Json.read(captured.answer().body()).get("error").asText());
        assertFalse(captured.log().contains("IllegalArgumentException"), captured.log());
        assertFalse(captured.log().contains("level=ERROR"), captured.log());
    }

    @Test
    void anOverlongNameIsRefusedAsInvalidNotA500() throws Exception {
        String code = keys.newCode(BOLD);

        Captured captured = capturingStdout(() ->
                post(WorkerApi.PAIR, null, "{\"code\":\"" + code + "\",\"name\":\"" + "a".repeat(41) + "\"}"));

        assertEquals(400, captured.answer().statusCode());
        assertEquals("invalid", Json.read(captured.answer().body()).get("error").asText());
        assertFalse(captured.log().contains("level=ERROR"), captured.log());
    }

    @Test
    void aStalledBodyEndsAsARefusalNotAHeldThread() throws Exception {
        // The JDK's request stream is backed by the connection's own channel: freeing a thread blocked reading it
        // closes that channel (java.nio.channels.Channel's own contract for an interrupted blocking operation), so a
        // stalled client is refused by losing its connection outright, not by a graceful JSON body. Either way, the
        // client sees this end quickly instead of the read hanging until its own socket timeout.
        try (WorkerApi impatient = WorkerApi.start(config(), groups(), keys, Duration.ofMillis(300))) {
            String head = "POST " + WorkerApi.PROJECTS + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + impatient.port() + "\r\n"
                    + "Authorization: Bearer whatever\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: 1000\r\n"
                    + "Connection: close\r\n\r\n";
            long elapsedMs;
            try (Socket socket = new Socket("127.0.0.1", impatient.port())) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                // Content-Length promises 1000 bytes of body; none of them ever arrive.
                long start = System.nanoTime();
                socket.getInputStream().readAllBytes();
                elapsedMs = (System.nanoTime() - start) / 1_000_000;
            }
            assertTrue(elapsedMs < 5000,
                    "held the request thread for " + elapsedMs + "ms instead of refusing at the read deadline");
        }
    }

    @Test
    void anOversizedBodyThatNeverFinishesStillFreesTheConnectionAndTheServerStaysUp() throws Exception {
        // body()'s own bounded reads (MAX_BODY+1, then an 8 KiB drain) consume at most 73,729 bytes before throwing
        // 413, and that 413 is written and flushed before this exchange is ever closed — so it always arrives
        // quickly, fix or no fix, and reading only it would prove nothing. What actually depends on the fix is what
        // happens on this connection AFTER that: with a Content-Length far beyond what body() ever reads, and
        // nothing else sent, the JDK request stream is still short of EOF, so finishing this exchange means
        // HttpExchange.close()'s own drain (LeftOverInputStream, a plain blocking read with no timeout of its own)
        // has to run — and neither side is going to send anything else or close first, so without closeBounded that
        // drain (and this connection) hangs until something external intervenes. This test's own client-side
        // SO_TIMEOUT is that intervention: short enough that an unbounded close() fails this test with a
        // SocketTimeoutException instead of silently passing.
        try (WorkerApi impatient = WorkerApi.start(config(), groups(), keys, Duration.ofMillis(500))) {
            String key = pair(BOLD, "ann-laptop");
            byte[] oversized = new byte[80_000];
            Arrays.fill(oversized, (byte) 'y');
            String head = "POST " + WorkerApi.PROJECTS + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + impatient.port() + "\r\n"
                    + "Authorization: Bearer " + key + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + (10 * 1024 * 1024) + "\r\n\r\n";
            long elapsedMs;
            try (Socket socket = new Socket("127.0.0.1", impatient.port())) {
                socket.setSoTimeout(5000);
                OutputStream out = socket.getOutputStream();
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                out.write(oversized); // declares 10 MB; sends 80,000 and stops. The other ~9.9 MB never arrive.
                out.flush();

                // The 413: sent before any close is attempted, so this always returns quickly on its own.
                byte[] first = new byte[8192];
                int n1 = socket.getInputStream().read(first);
                assertTrue(n1 > 0 && new String(first, 0, n1, StandardCharsets.UTF_8).contains("413"),
                        "expected the 413 first: " + (n1 > 0 ? new String(first, 0, n1, StandardCharsets.UTF_8) : "<eof>"));

                // What the fix actually protects: reading past the response, on the same still-open connection,
                // must not hang. A -1 (the server closed it) or bytes from a served pipelined request are both a
                // clean end; only a held-forever read (caught by this test's own SO_TIMEOUT, above) is the bug.
                long start = System.nanoTime();
                byte[] second = new byte[8192];
                socket.getInputStream().read(second);
                elapsedMs = (System.nanoTime() - start) / 1_000_000;
            }
            assertTrue(elapsedMs < 5000,
                    "held the connection for " + elapsedMs + "ms instead of freeing it at the close deadline");

            // The server itself is unaffected by the stalled connection above: a fresh request against the same
            // instance, on its own socket, is still served normally.
            HttpResponse<String> healthCheck = http.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + impatient.port() + WorkerApi.PROJECTS))
                            .header("Authorization", "Bearer " + key)
                            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, healthCheck.statusCode());
        }
    }

    @Test
    void hostMatchingIsCaseInsensitive() throws Exception {
        String key = pair(BOLD, "ann-laptop");

        Raw answer = rawPost(WorkerApi.PROJECTS, "LOCALHOST:" + api.port(), key, "{}");

        assertEquals(200, answer.status());
    }

    @Test
    void publicUrlWithAnExplicitPortDoesNotAlsoAllowTheBareHost() throws Exception {
        String key = pair(BOLD, "ann-laptop");
        try (WorkerApi withPort = WorkerApi.start(config("https://team.example.com:8443"), groups(), keys)) {
            Raw bareHost = rawPost(withPort.port(), WorkerApi.PROJECTS, "team.example.com", key, "{}");
            Raw hostWithPort = rawPost(withPort.port(), WorkerApi.PROJECTS, "team.example.com:8443", key, "{}");

            assertEquals(403, bareHost.status(), "the constraint only names the host with its port, if any");
            assertEquals(200, hostWithPort.status());
        }
    }

    @Test
    void itListensOnLoopbackOnly() throws Exception {
        InetAddress local;
        try {
            local = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            return; // no resolvable hostname in this sandbox; nothing to prove exclusion against
        }
        if (local.isLoopbackAddress()) {
            return; // no routable address here either
        }
        assertThrows(IOException.class, () -> new Socket(local, api.port()).close(),
                "must not accept connections on a non-loopback address");
    }

    private record Captured(HttpResponse<String> answer, String log) {
    }

    private Captured capturingStdout(Callable<HttpResponse<String>> action) throws Exception {
        PrintStream out = System.out;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(logged, true, StandardCharsets.UTF_8));
            return new Captured(action.call(), logged.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
        }
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
        return rawPost(api.port(), path, host, key, body);
    }

    private Raw rawPost(int port, String path, String host, String key, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String head = "POST " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Authorization: Bearer " + key + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        try (Socket socket = new Socket("127.0.0.1", port)) {
            // A regression in the server's read/close handling must fail this test, not hang the build.
            socket.setSoTimeout(5000);
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
        return config("http://127.0.0.1:0");
    }

    private Config config(String publicUrl) {
        return new Config("backend", dir, new Config.Telegram(List.of(), List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm")))),
                new Config.Scheduler(2), Config.Worktrees.DEFAULT,
                new Config.Limits(new Config.RunLimits(Duration.ofMinutes(30), new java.math.BigDecimal("2")),
                        new Config.RunLimits(Duration.ofMinutes(60), new java.math.BigDecimal("10"))),
                Map.of("claude-code", new Config.Agent("claude")),
                List.of(new Config.Project("alm", null, "git@github.com:acme/alm.git", null, "main", "claude-code", "opus",
                        "high", List.of(), null, null, null)),
                new Config.Delivery("Dispatch (backend)", "dispatch-backend@example.com", "gh"),
                new Config.Workers(publicUrl, 0), new Config.Secrets("token", null));
    }

    private Groups groups() {
        return new Groups(List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm"))));
    }
}
