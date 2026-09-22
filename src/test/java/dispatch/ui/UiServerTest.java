package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                }));
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
        assertEquals(401, get(path("/api/ping"), "dispatch_session=made-up").statusCode());
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
    void itListensOnLoopbackOnlyAndKnowsWhetherAUiIsBundled() {
        assertEquals("127.0.0.1", server.loginUri().getHost());
        assertTrue(UiServer.hasUi("/ui-test"));
        assertFalse(UiServer.hasUi("/no-such-ui"));
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
