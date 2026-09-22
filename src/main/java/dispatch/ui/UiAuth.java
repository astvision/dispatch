package dispatch.ui;

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
final class UiAuth {

    static final String COOKIE = "dispatch_session";

    private final SecureRandom random = new SecureRandom();
    private final String token = randomValue();
    private final AtomicBoolean tokenUsed = new AtomicBoolean();
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private final Set<String> hosts;
    private final Set<String> origins;

    UiAuth(int port) {
        this.hosts = Set.of("127.0.0.1:" + port, "localhost:" + port);
        this.origins = Set.of("http://127.0.0.1:" + port, "http://localhost:" + port);
    }

    String token() {
        return token;
    }

    /** A new session when {@code candidate} is the token and it was not used yet. */
    Optional<String> redeem(String candidate) {
        boolean matches = candidate != null
                && MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!matches || !tokenUsed.compareAndSet(false, true)) {
            return Optional.empty();
        }
        String session = randomValue();
        sessions.add(session);
        return Optional.of(session);
    }

    boolean hasSession(String cookieHeader) {
        if (cookieHeader == null) {
            return false;
        }
        for (String cookie : cookieHeader.split(";")) {
            String[] nameValue = cookie.strip().split("=", 2);
            if (nameValue.length == 2 && nameValue[0].equals(COOKIE) && sessions.contains(nameValue[1])) {
                return true;
            }
        }
        return false;
    }

    /** Against DNS rebinding: a page on another site that resolves its name to 127.0.0.1 still sends its own Host. */
    boolean hostAllowed(String host) {
        return host != null && hosts.contains(host);
    }

    /** Against cross-site requests that change something. */
    boolean originAllowed(String origin) {
        return origin != null && origins.contains(origin);
    }

    /** 256 random bits. */
    private String randomValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
