package dispatch;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Structured logfmt lines on stdout (collected by journald), one line per event so they stay greppable. */
public final class Log {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Log() {
    }

    public static void info(String event, Object... pairs) {
        write(line(Instant.now(), "INFO", event, pairs), null);
    }

    public static void warn(String event, Object... pairs) {
        write(line(Instant.now(), "WARN", event, pairs), null);
    }

    public static void error(String event, Throwable error, Object... pairs) {
        write(line(Instant.now(), "ERROR", event, pairs), error);
    }

    static String line(Instant ts, String level, String event, Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("log pairs must be key/value, got " + pairs.length + " items for " + event);
        }
        StringBuilder out = new StringBuilder(128)
                .append("ts=").append(TIMESTAMP.format(ts))
                .append(" level=").append(level)
                .append(" event=").append(event);
        for (int i = 0; i < pairs.length; i += 2) {
            out.append(' ').append(pairs[i]).append('=').append(value(pairs[i + 1]));
        }
        return out.toString();
    }

    private static String value(Object value) {
        if (value == null) {
            return "null";
        }
        String text = value.toString();
        boolean plain = !text.isEmpty() && text.chars().noneMatch(c -> c <= ' ' || c == '"' || c == '=');
        if (plain) {
            return text;
        }
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + '"';
    }

    private static void write(String line, Throwable error) {
        if (error == null) {
            System.out.println(line);
            return;
        }
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        // One println keeps the event line and its stack trace together when threads log concurrently.
        System.out.println(line + " error=" + value(String.valueOf(error.getMessage())) + System.lineSeparator() + trace);
    }
}
