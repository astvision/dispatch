package dispatch;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Masks secrets in text that leaves the process: log lines and chat messages. Known values (Dispatch's own tokens) are
 * masked exactly; common credential formats are masked by pattern, which also catches secrets the agent or git print.
 * Best effort: it cannot recognise a secret it has never seen in an unknown format.
 */
public final class Redactor {

    static final String MASK = "[redacted]";

    /** Environment variables whose values are secrets wherever they appear. */
    private static final Set<String> SECRET_VARIABLES = Set.of("TELEGRAM_BOT_TOKEN", "GH_TOKEN", "ANTHROPIC_API_KEY",
            "CLAUDE_CODE_OAUTH_TOKEN", "OPENAI_API_KEY", "DISPATCH_WORKER_KEY");
    /** Shorter values would mask ordinary words; real tokens are far longer. */
    private static final int MIN_SECRET_LENGTH = 8;
    /** Order matters: the Anthropic format must be masked before the generic "sk-" one. */
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("(?<=://)[^/\\s:@]+:[^/\\s@]+(?=@)"),
            Pattern.compile("(?<![A-Za-z0-9])\\d{6,12}:[A-Za-z0-9_-]{30,}"),
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("(?<![A-Za-z0-9])sk-(?:proj-)?[A-Za-z0-9_-]{20,}"),
            Pattern.compile("(?<![A-Za-z0-9])(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"),
            Pattern.compile("(?<![A-Z0-9])AKIA[0-9A-Z]{16}(?![A-Z0-9])"));

    private final List<String> knownSecrets;

    private Redactor(List<String> knownSecrets) {
        this.knownSecrets = knownSecrets;
    }

    /** Masks the values of Dispatch's secret variables found in {@code environment}, plus the common formats. */
    public static Redactor fromEnvironment(Map<String, String> environment) {
        List<String> secrets = environment.entrySet().stream()
                .filter(entry -> SECRET_VARIABLES.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && value.strip().length() >= MIN_SECRET_LENGTH)
                .map(String::strip)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        return new Redactor(secrets);
    }

    public static Redactor patternsOnly() {
        return new Redactor(List.of());
    }

    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (String secret : knownSecrets) {
            result = result.replace(secret, MASK);
        }
        for (Pattern pattern : PATTERNS) {
            result = pattern.matcher(result).replaceAll(MASK);
        }
        return result;
    }
}
