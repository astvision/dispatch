package dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LogTest {

    private static final Instant TS = Instant.parse("2026-09-17T10:15:30.123456Z");

    @Test
    void formatsEventAndPairsAsLogfmt() {
        String line = Log.line(TS, "INFO", "task.transition", "task", 42, "from", "PLANNING", "to", "FAILED");

        assertEquals("ts=2026-09-17T10:15:30.123Z level=INFO event=task.transition task=42 from=PLANNING to=FAILED", line);
    }

    @Test
    void quotesValuesThatWouldBreakParsing() {
        String line = Log.line(TS, "WARN", "git.failed", "detail", "fatal: \"origin\" not found\nhint", "empty", "", "missing", null);

        assertEquals("ts=2026-09-17T10:15:30.123Z level=WARN event=git.failed"
                + " detail=\"fatal: \\\"origin\\\" not found\\nhint\" empty=\"\" missing=null", line);
    }

    @Test
    void rejectsKeyWithoutValue() {
        assertThrows(IllegalArgumentException.class, () -> Log.line(TS, "INFO", "event", "orphan"));
    }

    @Test
    void credentialFormatsAreMaskedEvenWithoutConfiguredSecrets() {
        String line = Log.line(TS, "WARN", "git.failed", "detail", "unable to access https://bot:hunter2secret@github.com/acme/app");

        assertEquals("ts=2026-09-17T10:15:30.123Z level=WARN event=git.failed detail=\"unable to access https://[redacted]@github.com/acme/app\"", line);
    }

    @Test
    void configuredSecretValuesNeverReachLogLinesOrStackTraces() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Log.useRedactor(Redactor.fromEnvironment(Map.of("GH_TOKEN", "value-of-the-gh-token")));
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));

            Log.info("git.run", "args", "fetch with value-of-the-gh-token");
            Log.error("git.failed", new IllegalStateException("remote said value-of-the-gh-token is invalid"));
        } finally {
            System.setOut(original);
            Log.useRedactor(Redactor.patternsOnly());
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("value-of-the-gh-token"), output);
        assertTrue(output.contains("args=\"fetch with [redacted]\""), output);
        assertTrue(output.contains("remote said [redacted] is invalid"), output);
    }
}
