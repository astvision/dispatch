package dispatch.ui;

import dispatch.Text;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who may use the web UI: whoever opened the one-time link that `dispatch ui` printed, as the Jupyter notebook does it. The
 * link gives one browser a session cookie and then stops working; restarting `dispatch ui` ends every session.
 *
 * <p>The bot on this computer may ask for another link ({@code POST /link} with the key from {@link UiFile}), which the
 * Mini App opens in the admin's browser (ADR 0018, amended): a link still only works on 127.0.0.1, and a minted one
 * expires after {@link #MINTED_FOR_MILLIS} unused.
 */
final class UiAuth implements UiServer.Auth {

    /** Whoever holds the link already acts as the owner, with what they may do in a shell (SECURITY.md). */
    /** The header {@code POST /link} carries the key in. */
    static final String KEY_HEADER = "X-Dispatch-Key";

    private static final UiServer.Caller OWNER = new UiServer.Caller("local", "you", true);
    private static final String NO_SESSION =
            "Open Dispatch through the link dispatch ui printed. Each link works once; restart dispatch ui for a new one.";

    /** Long enough for Telegram to hand the link to the browser; a link lying around unused is worth nothing to keep. */
    static final long MINTED_FOR_MILLIS = 5 * 60 * 1000;

    private final int port;
    /** What {@code POST /link} must carry; null when this server hands out no more links than the printed one. */
    private final String key;
    private final String cookie;
    private final SecureRandom random = new SecureRandom();
    private final String token = randomValue();
    /** Unused links and when each stops working; the printed one never does until it is used. */
    private final Map<String, Long> unused = new ConcurrentHashMap<>(Map.of(token, Long.MAX_VALUE));
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private final Set<String> hosts;
    private final Set<String> origins;

    UiAuth(int port) {
        this(port, null);
    }

    UiAuth(int port, String key) {
        this.port = port;
        this.key = key;
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
    public Optional<String> hostRefusal(String host) {
        return host != null && hosts.contains(host)
                ? Optional.empty()
                : Optional.of("Open Dispatch through the link dispatch ui printed.");
    }

    /**
     * Redeems a one-time link: "/?t=TOKEN" becomes a session cookie and a redirect to "/". Also answers the bot's
     * {@code POST /link} with a new one.
     */
    @Override
    public boolean login(HttpExchange exchange) throws IOException {
        if (key != null && exchange.getRequestURI().getPath().equals("/link")) {
            mint(exchange);
            return true;
        }
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

    /** A new one-time link for whoever holds the key, which only the owner's own processes can read (UiFile). */
    private void mint(HttpExchange exchange) throws IOException {
        String given = exchange.getRequestHeaders().getFirst(KEY_HEADER);
        boolean allowed = exchange.getRequestMethod().equals("POST") && given != null
                && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
        if (!allowed) {
            send(exchange, 403, "text/plain; charset=utf-8", "Only the bot on this computer may ask for a link.");
            return;
        }
        long now = System.currentTimeMillis();
        unused.values().removeIf(until -> until < now);
        String minted = randomValue();
        unused.put(minted, now + MINTED_FOR_MILLIS);
        send(exchange, 200, "application/json; charset=utf-8",
                dispatch.Json.write(Map.of("url", "http://127.0.0.1:" + port + "/?t=" + minted)));
    }

    private static void send(HttpExchange exchange, int status, String type, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
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
            throw new ApiException(401, "session", Text.of("refusal.sessionEnded"));
        }
        String method = exchange.getRequestMethod();
        boolean reading = method.equals("GET") || method.equals("HEAD");
        if (!reading && !originAllowed(exchange.getRequestHeaders().getFirst("Origin"))) {
            throw new ApiException(403, "origin", Text.of("refusal.otherOrigin"));
        }
        return OWNER;
    }

    /** A new session when {@code candidate} is an unused link that has not expired; the link is used up either way. */
    private Optional<String> redeem(String candidate) {
        byte[] given = candidate.getBytes(StandardCharsets.UTF_8);
        String match = null;
        for (String link : unused.keySet()) {
            if (MessageDigest.isEqual(given, link.getBytes(StandardCharsets.UTF_8))) {
                match = link;
            }
        }
        // remove() is the claim: of two browsers racing with one link, only one gets a non-null answer.
        Long until = match == null ? null : unused.remove(match);
        if (until == null || until < System.currentTimeMillis()) {
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
