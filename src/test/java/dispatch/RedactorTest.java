package dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Token-shaped test values are assembled at runtime so secret scanners never see a literal one in the source. */
class RedactorTest {

    private static final String TELEGRAM_TOKEN = "7412369850:" + "AAH" + "x".repeat(32);
    private static final String GITHUB_CLASSIC = "gh" + "p_" + "a1B2c3D4e5".repeat(4);
    private static final String GITHUB_FINE_GRAINED = "github" + "_pat_" + "11ABCDEFG0" + "_x".repeat(20);
    private static final String ANTHROPIC_KEY = "sk-" + "ant-api03-" + "Zz9".repeat(10);

    private final Redactor patterns = Redactor.patternsOnly();

    @Test
    void knownSecretValuesAreMaskedWhereverTheyAppear() {
        Redactor redactor = Redactor.fromEnvironment(Map.of("GH_TOKEN", "short-but-real-token", "PATH", "/usr/bin"));

        String redacted = redactor.redact("git failed with password=short-but-real-token in /usr/bin");

        assertEquals("git failed with password=[redacted] in /usr/bin", redacted);
    }

    @Test
    void tooShortEnvironmentValuesAreNotTreatedAsSecrets() {
        Redactor redactor = Redactor.fromEnvironment(Map.of("GH_TOKEN", "abc"));

        assertEquals("abc is fine", redactor.redact("abc is fine"));
    }

    @Test
    void commonTokenFormatsAreMaskedEvenWhenNotConfigured() {
        String text = "bot " + TELEGRAM_TOKEN + ", github " + GITHUB_CLASSIC + " and " + GITHUB_FINE_GRAINED
                + ", anthropic " + ANTHROPIC_KEY + ", aws AKIA" + "ABCDEFGHIJKLMNOP";

        String redacted = patterns.redact(text);

        assertFalse(redacted.contains(TELEGRAM_TOKEN), redacted);
        assertFalse(redacted.contains(GITHUB_CLASSIC), redacted);
        assertFalse(redacted.contains(GITHUB_FINE_GRAINED), redacted);
        assertFalse(redacted.contains(ANTHROPIC_KEY), redacted);
        assertFalse(redacted.contains("AKIAABCDEFGHIJKLMNOP"), redacted);
        assertEquals("bot [redacted], github [redacted] and [redacted], anthropic [redacted], aws [redacted]", redacted);
    }

    @Test
    void credentialsInUrlsAreMaskedButHostAndPathStay() {
        String redacted = patterns.redact("fatal: unable to access 'https://x-access-token:s3cr3tValue@github.com/acme/app.git/'");

        assertEquals("fatal: unable to access 'https://[redacted]@github.com/acme/app.git/'", redacted);
    }

    @Test
    void privateKeyBlocksAreMaskedWhole() {
        String key = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\nQQ==\n-----END OPENSSH PRIVATE KEY-----";

        assertEquals("key: [redacted] end", patterns.redact("key: " + key + " end"));
    }

    @Test
    void ordinaryTextIsLeftAlone() {
        String text = "#42 run 42.2 failed at 2026-09-17T10:00:00Z: git@github.com:acme/app.git ssh://git@host/repo 12:30";

        assertEquals(text, patterns.redact(text));
        assertTrue(patterns.redact(null) == null);
    }
}
