package dispatch.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dispatch.Json;
import dispatch.cli.CliException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The web UI's HTTP server, on 127.0.0.1 only (ADR 0018). It checks every request with {@link UiAuth}, answers the API
 * routes with JSON and serves the bundled pages from the classpath.
 */
public final class UiServer implements AutoCloseable {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            ".html", "text/html; charset=utf-8",
            ".js", "text/javascript; charset=utf-8",
            ".css", "text/css; charset=utf-8",
            ".svg", "image/svg+xml",
            ".png", "image/png",
            ".ico", "image/x-icon",
            ".json", "application/json; charset=utf-8",
            ".woff2", "font/woff2");
    private static final int MAX_BODY = 64 * 1024;

    private final HttpServer server;
    private final UiAuth auth;
    private final String resourceRoot;
    private final Map<String, Supplier<Object>> getRoutes;
    private final Map<String, Function<JsonNode, Object>> postRoutes;

    private UiServer(HttpServer server, String resourceRoot, Map<String, Supplier<Object>> getRoutes,
                     Map<String, Function<JsonNode, Object>> postRoutes) {
        this.server = server;
        this.auth = new UiAuth(server.getAddress().getPort());
        this.resourceRoot = resourceRoot;
        this.getRoutes = Map.copyOf(getRoutes);
        this.postRoutes = Map.copyOf(postRoutes);
    }

    /**
     * @param port         0 for any free port
     * @param resourceRoot the classpath folder of the bundled pages, e.g. "/ui"
     * @param getRoutes    API paths, e.g. "/api/overview", and what they answer as JSON
     */
    public static UiServer start(int port, String resourceRoot, Map<String, Supplier<Object>> getRoutes) throws IOException {
        return start(port, resourceRoot, getRoutes, Map.of());
    }

    /** @param postRoutes API paths that change something, and what they answer, given the request's JSON body */
    public static UiServer start(int port, String resourceRoot, Map<String, Supplier<Object>> getRoutes,
                                 Map<String, Function<JsonNode, Object>> postRoutes) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        UiServer ui = new UiServer(http, resourceRoot, getRoutes, postRoutes);
        http.createContext("/", ui::handle);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        return ui;
    }

    /** Whether this build bundles the pages: a source build without the ui profile does not. */
    public static boolean hasUi(String resourceRoot) {
        return UiServer.class.getResource(resourceRoot + "/index.html") != null;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public URI loginUri() {
        return URI.create("http://127.0.0.1:" + port() + "/?t=" + auth.token());
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                respond(exchange);
            } catch (RuntimeException e) {
                // Last-resort safety net: without this, the JDK HttpServer drops the connection silently (TRACE-level
                // log only) and the person staring at a blank browser tab has nothing to go on.
                System.err.println("dispatch ui: " + exchange.getRequestURI().getPath() + " failed");
                e.printStackTrace();
                json(exchange, 500, error("internal", "something went wrong; the terminal running dispatch ui shows what"));
            }
        }
    }

    private void respond(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        if (!auth.hostAllowed(exchange.getRequestHeaders().getFirst("Host"))) {
            text(exchange, 403, "Open Dispatch through the link dispatch ui printed.");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        Optional<String> token = queryValue(exchange.getRequestURI().getRawQuery(), "t");
        if (path.equals("/") && token.isPresent()) {
            login(exchange, token.get());
            return;
        }
        boolean api = path.startsWith("/api/");
        if (!auth.hasSession(exchange.getRequestHeaders().getFirst("Cookie"))) {
            if (api) {
                json(exchange, 401, error("session", "this page's session ended; restart dispatch ui and open the link it prints"));
            } else {
                text(exchange, 401, "Open Dispatch through the link dispatch ui printed. Each link works once; restart dispatch ui for a new one.");
            }
            return;
        }
        String method = exchange.getRequestMethod();
        boolean reading = method.equals("GET") || method.equals("HEAD");
        if (!reading && !auth.originAllowed(exchange.getRequestHeaders().getFirst("Origin"))) {
            json(exchange, 403, error("origin", "this request did not come from the Dispatch page"));
            return;
        }
        if (api) {
            api(exchange, path, reading);
        } else {
            page(exchange, path);
        }
    }

    private void login(HttpExchange exchange, String token) throws IOException {
        Optional<String> session = auth.redeem(token);
        if (session.isEmpty()) {
            text(exchange, 401, "This link was used already or is wrong. Restart dispatch ui for a new one.");
            return;
        }
        exchange.getResponseHeaders().set("Set-Cookie", auth.cookieName() + "=" + session.get() + "; HttpOnly; SameSite=Strict; Path=/");
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(302, -1);
    }

    private void api(HttpExchange exchange, String path, boolean reading) throws IOException {
        Supplier<Object> getRoute = getRoutes.get(path);
        Function<JsonNode, Object> postRoute = postRoutes.get(path);
        if (getRoute == null && postRoute == null) {
            json(exchange, 404, error("not_found", "no such API: " + path));
            return;
        }
        if (reading ? getRoute == null : postRoute == null) {
            json(exchange, 405, error("method", path + " only answers " + (reading ? "POST" : "GET")));
            return;
        }
        JsonNode body = null;
        if (!reading) {
            byte[] raw = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (raw.length > MAX_BODY) {
                // Drain remaining bytes (up to 1 MiB total) to avoid JDK client reset after 413
                exchange.getRequestBody().readNBytes(1024 * 1024 - raw.length);
                json(exchange, 413, error("too_large", "the request is larger than " + MAX_BODY / 1024 + " KiB"));
                return;
            }
            try {
                body = raw.length == 0 ? Json.object() : Json.MAPPER.readTree(raw);
            } catch (JsonProcessingException e) {
                json(exchange, 400, error("invalid", "the request is not JSON"));
                return;
            }
        }
        String answer;
        try {
            answer = Json.write(reading ? getRoute.get() : postRoute.apply(body));
        } catch (ApiException e) {
            json(exchange, e.status(), error(e.code(), e.getMessage()));
            return;
        } catch (CliException e) {
            json(exchange, 400, error("invalid", e.getMessage()));
            return;
        } catch (RuntimeException e) {
            // Covers both a route that throws and a result Jackson can't serialize (e.g. an empty bean).
            System.err.println("dispatch ui: " + path + " failed");
            e.printStackTrace();
            json(exchange, 500, error("internal", "something went wrong; the terminal running dispatch ui shows what"));
            return;
        }
        json(exchange, 200, answer);
    }

    private void page(HttpExchange exchange, String path) throws IOException {
        if (path.contains("..")) {
            text(exchange, 404, "not found");
            return;
        }
        String file = fileFor(path);
        InputStream content = UiServer.class.getResourceAsStream(resourceRoot + file);
        if (content == null) {
            text(exchange, 404, "not found");
            return;
        }
        try (InputStream toClose = content) {
            byte[] bytes = toClose.readAllBytes();
            String extension = file.substring(file.lastIndexOf('.'));
            exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"));
            // Vite names assets by their content, so they may be cached; index.html must not be.
            exchange.getResponseHeaders().set("Cache-Control", file.startsWith("/assets/") ? "max-age=31536000, immutable" : "no-store");
            send(exchange, 200, bytes);
        }
    }

    /**
     * A path whose last segment has no extension is either a client-side route (e.g. /projects) or a directory
     * (e.g. /assets, /assets/); either way the app decides what to show, and we never try to open it as a classpath
     * resource: {@code getResourceAsStream} on a directory entry can return a non-null, non-file stream (or throw,
     * depending on how the classes are packaged), which is not a page to serve.
     */
    private static String fileFor(String path) {
        return lastSegment(path).contains(".") ? path : "/index.html";
    }

    private static String error(String code, String message) {
        return Json.write(Map.of("error", code, "message", message));
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        send(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void text(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        send(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        boolean head = exchange.getRequestMethod().equals("HEAD");
        exchange.sendResponseHeaders(status, head ? -1 : body.length);
        if (!head) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static Optional<String> queryValue(String rawQuery, String name) {
        if (rawQuery == null) {
            return Optional.empty();
        }
        for (String pair : rawQuery.split("&")) {
            String[] nameValue = pair.split("=", 2);
            if (nameValue.length == 2 && nameValue[0].equals(name)) {
                return Optional.of(java.net.URLDecoder.decode(nameValue[1], StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
