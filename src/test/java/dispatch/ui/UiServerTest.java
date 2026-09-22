package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.cli.CliException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UiServerTest {

    private final HttpClient http = HttpClient.newHttpClient(); // never follows redirects
    private UiServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = UiServer.start(0, "/ui-test", Map.<String, Supplier<Object>>of(
                "/api/ping", () -> Map.of("ok", true),
                "/api/refused", () -> {
                    throw new CliException("no config at x; create one with: dispatch init");
                },
                "/api/broken", Object::new));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void theLoginLinkGivesASessionCookieOnce() throws Exception {
        HttpResponse<String> login = get(server.loginUri(), null);

        assertEquals(302, login.statusCode());
        assertEquals("/", login.headers().firstValue("Location").orElseThrow());
        String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict") && setCookie.contains("Path=/"), setCookie);
        assertEquals(200, get(path("/api/ping"), cookie(setCookie)).statusCode());
        assertEquals(401, get(server.loginUri(), null).statusCode(), "the link works once");
    }

    @Test
    void theApiNeedsASession() throws Exception {
        HttpResponse<String> response = get(path("/api/ping"), null);

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("\"error\":\"session\""), response.body());
        assertEquals(401, get(path("/api/ping"), "dispatch_session_" + server.port() + "=made-up").statusCode());
    }

    @Test
    void aWrongTokenGivesNoSession() throws Exception {
        HttpResponse<String> response = get(URI.create("http://127.0.0.1:" + server.port() + "/?t=wrong"), null);

        assertEquals(401, response.statusCode());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
    }

    @Test
    void anotherHostNameIsRefused() throws Exception {
        String cookie = login();

        String status = rawStatusLine("GET /api/ping HTTP/1.1\r\nHost: attacker.example:" + server.port() + "\r\nCookie: " + cookie
                + "\r\nConnection: close\r\n\r\n");

        assertTrue(status.contains(" 403 "), "DNS rebinding: " + status);
    }

    @Test
    void writesNeedThisPagesOrigin() throws Exception {
        String cookie = login();

        HttpResponse<String> foreign = http.send(HttpRequest.newBuilder(path("/api/ping")).header("Cookie", cookie)
                .header("Origin", "http://attacker.example").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> own = http.send(HttpRequest.newBuilder(path("/api/ping")).header("Cookie", cookie)
                .header("Origin", "http://127.0.0.1:" + server.port()).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, foreign.statusCode());
        assertEquals(405, own.statusCode(), "passes the origin check; only GET routes exist yet");
    }

    @Test
    void apiResultsAreJsonAndErrorsSayWhatToDo() throws Exception {
        String cookie = login();

        HttpResponse<String> ok = get(path("/api/ping"), cookie);
        HttpResponse<String> refused = get(path("/api/refused"), cookie);
        HttpResponse<String> missing = get(path("/api/nothing"), cookie);

        assertEquals("{\"ok\":true}", ok.body());
        assertEquals("application/json; charset=utf-8", ok.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(400, refused.statusCode());
        assertTrue(refused.body().contains("\"error\":\"invalid\"") && refused.body().contains("dispatch init"), refused.body());
        assertEquals(404, missing.statusCode());
    }

    @Test
    void theBundledPagesAreServedAndUnknownPagesGetTheApp() throws Exception {
        String cookie = login();

        HttpResponse<String> index = get(path("/"), cookie);
        HttpResponse<String> route = get(path("/projects"), cookie);
        HttpResponse<String> script = get(path("/assets/app.js"), cookie);

        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("test ui"), index.body());
        assertEquals("DENY", index.headers().firstValue("X-Frame-Options").orElseThrow());
        assertEquals(index.body(), route.body(), "client-side routes get index.html");
        assertEquals("text/javascript; charset=utf-8", script.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(404, get(path("/assets/missing.js"), cookie).statusCode());
        assertEquals(404, get(path("/assets/..%2F..%2Fpersonal.yaml"), cookie).statusCode());
    }

    @Test
    void aRouteThatCannotBeSerializedAnswers500() throws Exception {
        String cookie = login();

        HttpResponse<String> response = get(path("/api/broken"), cookie);

        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("\"error\":\"internal\""), response.body());
    }

    @Test
    void aDirectoryPathAnswersInsteadOfResettingTheConnection() throws Exception {
        String cookie = login();

        HttpResponse<String> assets = get(path("/assets"), cookie);
        HttpResponse<String> assetsSlash = get(path("/assets/"), cookie);

        assertEquals(200, assets.statusCode(), "a path with no extension in its last segment falls through to index.html");
        assertEquals(200, assetsSlash.statusCode(), "a path with no extension in its last segment falls through to index.html");
    }

    /**
     * java.net.URI (used by HttpClient) refuses to build a URI with a malformed percent-escape at all, and
     * com.sun.net.httpserver.HttpServer itself rejects one in the request line before dispatching to any context
     * handler, with its own 400 page. So this is written over a raw socket and checks that the connection completes
     * normally rather than being reset; UiServer's own query-decoding never actually sees malformed input.
     */
    @Test
    void aMalformedPercentEscapeNeverReachesTheHandler() throws Exception {
        String status = rawStatusLine("GET /?t=%zz HTTP/1.1\r\nHost: 127.0.0.1:" + server.port() + "\r\nConnection: close\r\n\r\n");

        assertTrue(status.contains(" 400 "), status);
    }

    @Test
    void itListensOnLoopbackOnlyAndKnowsWhetherAUiIsBundled() {
        assertEquals("127.0.0.1", server.loginUri().getHost());
        assertTrue(UiServer.hasUi("/ui-test"));
        assertFalse(UiServer.hasUi("/no-such-ui"));
    }

    @Test
    void postRoutesGetTheJsonBodyAndAnswerWithTheirStatus() throws Exception {
        try (UiServer posting = UiServer.start(0, "/ui-test", Map.of(), Map.<String, Function<JsonNode, Object>>of(
                "/api/echo", body -> Map.of("got", body.path("name").asText()),
                "/api/busy", body -> {
                    throw new ApiException(409, "conflict", "Dispatch is running with this bot; stop it first");
                }))) {
            String cookie = cookie(get(posting.loginUri(), null).headers().firstValue("Set-Cookie").orElseThrow());
            String origin = "http://127.0.0.1:" + posting.port();

            HttpResponse<String> echo = post(posting, "/api/echo", cookie, origin, "{\"name\":\"Bold\"}");
            HttpResponse<String> empty = post(posting, "/api/echo", cookie, origin, "");
            HttpResponse<String> busy = post(posting, "/api/busy", cookie, origin, "{}");
            HttpResponse<String> notJson = post(posting, "/api/echo", cookie, origin, "{name");
            HttpResponse<String> tooLarge = post(posting, "/api/echo", cookie, origin, "{\"name\":\"" + "x".repeat(70_000) + "\"}");
            HttpResponse<String> readingAPostRoute = http.send(HttpRequest.newBuilder(URI.create(origin + "/api/echo"))
                    .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals("{\"got\":\"Bold\"}", echo.body());
            assertEquals("{\"got\":\"\"}", empty.body(), "an empty body is {}");
            assertEquals(409, busy.statusCode());
            assertTrue(busy.body().contains("\"error\":\"conflict\"") && busy.body().contains("stop it first"), busy.body());
            assertEquals(400, notJson.statusCode());
            assertTrue(notJson.body().contains("not JSON"), notJson.body());
            assertEquals(413, tooLarge.statusCode());
            assertEquals(405, readingAPostRoute.statusCode());
        }
    }

    private String login() throws Exception {
        return cookie(get(server.loginUri(), null).headers().firstValue("Set-Cookie").orElseThrow());
    }

    private static String cookie(String setCookie) {
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private URI path(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private HttpResponse<String> get(URI uri, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET();
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(UiServer target, String path, String cookie, String origin, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target.port() + path)).header("Cookie", cookie)
                .header("Origin", origin).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** HttpClient will not send another Host header, so this one request is written by hand. */
    private String rawStatusLine(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            StringBuilder line = new StringBuilder();
            for (int c = in.read(); c != -1 && c != '\n'; c = in.read()) {
                line.append((char) c);
            }
            return line.toString();
        }
    }
}
