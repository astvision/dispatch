package dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
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
}
