package dispatch.ui;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Who may use the web UI: whoever opened the one-time link that `dispatch ui` printed, as the Jupyter notebook does it. The
 * link gives one browser a session cookie and then stops working; restarting `dispatch ui` ends every session.
 */
final class UiAuth implements UiServer.Auth {

    /** Whoever holds the link already acts as the owner, with what they may do in a shell (SECURITY.md). */
    private static final UiServer.Caller OWNER = new UiServer.Caller("local", "you", true);
    private static final String NO_SESSION =
            "Open Dispatch through the link dispatch ui printed. Each link works once; restart dispatch ui for a new one.";

    private final int port;
    private final String cookie;
    private final SecureRandom random = new SecureRandom();
    private final String token = randomValue();
    private final AtomicBoolean tokenUsed = new AtomicBoolean();
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private final Set<String> hosts;
    private final Set<String> origins;

    UiAuth(int port) {
        this.port = port;
        // Port-scoped: two `dispatch ui` instances on different ports must not share a session, since the browser
        // sends every cookie whose name and path match to 127.0.0.1 regardless of which port set it.
        this.cookie = "dispatch_session_" + port;
        this.hosts = Set.of("127.0.0.1:" + port, "localhost:" + port);
        this.origins = Set.of("http://127.0.0.1:" + port, "http://localhost:" + port);
    }

    @Override
    public Optional<URI> entryUri() {
        return Optional.of(URI.create("http://127.0.0.1:" + port + "/?t=" + token));
    }

    @Override
    public void headers(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
    }

    /** Against DNS rebinding: a page on another site that resolves its name to 127.0.0.1 still sends its own Host. */
    @Override
    public boolean hostAllowed(String host) {
        return host != null && hosts.contains(host);
    }

    /** Redeems the one-time link: "/?t=TOKEN" becomes a session cookie and a redirect to "/". */
    @Override
    public boolean login(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/")) {
            return false;
        }
        Optional<String> candidate = queryValue(exchange.getRequestURI().getRawQuery(), "t");
        if (candidate.isEmpty()) {
            return false;
        }
        Optional<String> session = redeem(candidate.get());
        if (session.isEmpty()) {
            byte[] body = "This link was used already or is wrong. Restart dispatch ui for a new one."
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(401, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
            return true;
        }
        exchange.getResponseHeaders().set("Set-Cookie", cookie + "=" + session.get() + "; HttpOnly; SameSite=Strict; Path=/");
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(302, -1);
        return true;
    }

    /** The pages are behind the session too: this server is the owner's shell, not a public site. */
    @Override
    public Optional<String> pageRefusal(HttpExchange exchange) {
        return hasSession(exchange.getRequestHeaders().getFirst("Cookie")) ? Optional.empty() : Optional.of(NO_SESSION);
    }

    /**
     * There is one caller here, the owner. The Origin check lives here rather than in {@link UiServer} because it
     * belongs to this authentication alone: it protects a cookie, and a cookie is what the Mini App does not have.
     */
    @Override
    public UiServer.Caller caller(HttpExchange exchange) {
        if (!hasSession(exchange.getRequestHeaders().getFirst("Cookie"))) {
            throw new ApiException(401, "session", "this page's session ended; restart dispatch ui and open the link it prints");
        }
        String method = exchange.getRequestMethod();
        boolean reading = method.equals("GET") || method.equals("HEAD");
        if (!reading && !originAllowed(exchange.getRequestHeaders().getFirst("Origin"))) {
            throw new ApiException(403, "origin", "this request did not come from the Dispatch page");
        }
        return OWNER;
    }

    /** A new session when {@code candidate} is the token and it was not used yet. */
    private Optional<String> redeem(String candidate) {
        boolean matches = MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!matches || !tokenUsed.compareAndSet(false, true)) {
            return Optional.empty();
        }
        String session = randomValue();
        sessions.add(session);
        return Optional.of(session);
    }

    private boolean hasSession(String cookieHeader) {
        if (cookieHeader == null) {
            return false;
        }
        for (String pair : cookieHeader.split(";")) {
            String[] nameValue = pair.strip().split("=", 2);
            if (nameValue.length == 2 && nameValue[0].equals(cookie) && sessions.contains(nameValue[1])) {
                return true;
            }
        }
        return false;
    }

    /** Against cross-site requests that change something. */
    private boolean originAllowed(String origin) {
        return origin != null && origins.contains(origin);
    }

    /** 256 random bits. */
    private String randomValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
}
