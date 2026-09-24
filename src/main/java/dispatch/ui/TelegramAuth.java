package dispatch.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import dispatch.Json;
import dispatch.core.Groups;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Who may use the Mini App: whoever Telegram says opened it. Every request carries the launch data Telegram signed
 * with the bot token ({@code Authorization: tma <initData>}), which is checked here before anything else happens
 * (spec: Security). There is no cookie and no session, so there is nothing for a cross-site request to ride on.
 *
 * <p>The signature is the one Telegram documents: the secret key is HMAC-SHA256 of the bot token under the literal
 * key {@code "WebAppData"}, and the hash is HMAC-SHA256 of the data-check string under that secret.
 */
public final class TelegramAuth implements UiServer.Auth {

    /** Telegram's web clients show the page in a frame, so DENY would leave members looking at nothing. */
    static final String FRAME_ANCESTORS = "frame-ancestors https://web.telegram.org https://*.telegram.org";
    static final String NOT_A_MEMBER = "you are not in a group of this Dispatch; ask an admin to add you";
    static final String EXPIRED = "this Mini App has been open too long; close it and open it again";
    static final String UNAUTHORIZED = "open this page from the bot in Telegram";
    /** Long enough for a member to read a page and act on it, short enough that stolen launch data goes stale. */
    private static final Duration FRESH_FOR = Duration.ofHours(1);
    /** A phone's clock can be a little ahead of the server's; much more than that is not a clock difference. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

    private final byte[] secretKey;
    private final Supplier<Groups> groups;
    private final boolean team;
    private final Set<String> hosts;
    private final Clock clock;

    /**
     * @param groups asked per request, not held: membership changes in this process when someone joins (ADR 0015),
     *               and a member who joined since start must not have to wait for a restart to open the Mini App
     * @param team   false for a personal bot, whose sole member is the owner and may manage it
     */
    public TelegramAuth(String botToken, Supplier<Groups> groups, boolean team, String publicUrl, int port, Clock clock) {
        this.secretKey = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken);
        this.groups = groups;
        this.team = team;
        this.hosts = allowedHosts(publicUrl, port);
        this.clock = clock;
    }

    @Override
    public void headers(HttpExchange exchange) {
        // Replaces X-Frame-Options: DENY on this port only; the two would contradict each other.
        exchange.getResponseHeaders().set("Content-Security-Policy", FRAME_ANCESTORS);
    }

    @Override
    public Optional<String> hostRefusal(String host) {
        return hostAllowed(host) ? Optional.empty() : Optional.of("This is not the address this Dispatch answers on.");
    }

    /**
     * The page and its assets load without proving anything: Telegram puts the launch data in the URL fragment, which
     * a browser never sends, so the first request cannot carry it — the script that reads it has not run yet. The
     * bundle is the same static files {@code dispatch ui} serves and reaches no route by itself.
     */
    @Override
    public Optional<String> pageRefusal(HttpExchange exchange) {
        return Optional.empty();
    }

    @Override
    public UiServer.Caller caller(HttpExchange exchange) {
        return verify(exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Host"));
    }

    /**
     * The whole check, in the order the spec gives it: signature, freshness, who, host.
     *
     * @throws ApiException with the code the page needs to explain the refusal
     */
    UiServer.Caller verify(String authorization, String host) {
        if (!hostAllowed(host)) {
            throw new ApiException(403, "host", "This is not the address this Dispatch answers on.");
        }
        String initData = launchData(authorization);
        List<String> pairs = split(initData);
        String hash = null;
        TreeMap<String, String> checked = new TreeMap<>();
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = pair.substring(0, equals);
            String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            if (key.equals("hash")) {
                hash = value;
            } else {
                checked.put(key, value);
            }
        }
        if (hash == null || checked.isEmpty()) {
            throw new ApiException(401, "unauthorized", UNAUTHORIZED);
        }
        List<String> lines = new ArrayList<>();
        checked.forEach((key, value) -> lines.add(key + "=" + value));
        String expected = HexFormat.of().formatHex(hmac(secretKey, String.join("\n", lines)));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), hash.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(401, "unauthorized", UNAUTHORIZED);
        }
        fresh(checked.get("auth_date"));
        return who(checked.get("user"));
    }

    private static String launchData(String authorization) {
        if (authorization == null || !authorization.startsWith("tma ")) {
            throw new ApiException(401, "unauthorized", UNAUTHORIZED);
        }
        String initData = authorization.substring("tma ".length()).strip();
        if (initData.isEmpty()) {
            throw new ApiException(401, "unauthorized", UNAUTHORIZED);
        }
        return initData;
    }

    /** Stale launch data is refused as its own code, because the page answers it by reopening rather than by asking. */
    private void fresh(String authDate) {
        long seconds;
        try {
            seconds = Long.parseLong(Optional.ofNullable(authDate).orElse("").strip());
        } catch (NumberFormatException e) {
            throw new ApiException(401, "unauthorized", UNAUTHORIZED);
        }
        Instant signed = Instant.ofEpochSecond(seconds);
        Instant now = clock.instant();
        if (signed.isBefore(now.minus(FRESH_FOR)) || signed.isAfter(now.plus(CLOCK_SKEW))) {
            throw new ApiException(401, "expired", EXPIRED);
        }
    }

    /** The role is decided here, from the config, never from anything the page sent. */
    private UiServer.Caller who(String user) {
        JsonNode parsed;
        try {
            parsed = user == null ? null : Json.read(user);
        } catch (RuntimeException e) {
            throw new ApiException(401, "unauthorized", UNAUTHORIZED);
        }
        if (parsed == null || !parsed.hasNonNull("id")) {
            throw new ApiException(401, "unauthorized", UNAUTHORIZED);
        }
        String ref = "telegram:" + parsed.get("id").asLong();
        Groups current = groups.get();
        boolean admin = current.isAdmin(ref);
        if (!current.isMember(ref) && !admin) {
            throw new ApiException(403, "not_a_member", NOT_A_MEMBER);
        }
        // A personal bot has no admins list, so its one member would otherwise be locked out of their own Dispatch.
        return new UiServer.Caller(ref, parsed.path("first_name").asText(ref), admin || !team);
    }

    private boolean hostAllowed(String host) {
        return host != null && hosts.contains(host);
    }

    /** The tunnel's host, and the loopback port the tunnel forwards to, which {@code dispatch check} probes. */
    private static Set<String> allowedHosts(String publicUrl, int port) {
        Set<String> hosts = new java.util.HashSet<>(Set.of("127.0.0.1:" + port, "localhost:" + port));
        try {
            URI uri = new URI(publicUrl);
            if (uri.getHost() != null) {
                hosts.add(uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort());
            }
        } catch (URISyntaxException e) {
            // ConfigLoader already refused a URL this broken; nothing to add to the loopback entries.
        }
        return Collections.unmodifiableSet(hosts);
    }

    private static List<String> split(String query) {
        List<String> pairs = new ArrayList<>();
        Collections.addAll(pairs, query.split("&"));
        return pairs;
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required of every JDK", e);
        }
    }
}
