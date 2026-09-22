package dispatch.telegram;

/**
 * Cleans a name Telegram sends before it becomes part of a config value. Telegram accepts a display name with control
 * characters (a stray Bell, a bidi mark) or the Unicode line/paragraph separators; a config value must stay plain text on
 * one line ({@link dispatch.config.ConfigText#requirePlainText}). Cleaning once at intake, here, means a display name
 * Telegram accepted never fails a later join with CONFIG_FAILED.
 */
public final class TelegramNames {

    private TelegramNames() {
    }

    /**
     * @return {@code raw} with every ISO control character and the Unicode NEL/line-separator/paragraph-separator turned
     * into a space, runs of whitespace collapsed to one space, and the ends trimmed; empty when nothing else is left
     */
    public static String clean(String raw) {
        StringBuilder cleaned = new StringBuilder(raw.length());
        raw.codePoints().forEach(cp -> cleaned.appendCodePoint(isLineBreaking(cp) ? ' ' : cp));
        return cleaned.toString().replaceAll("\\s+", " ").strip();
    }

    private static boolean isLineBreaking(int codePoint) {
        //   and   are written as hex, not ' ' escapes: javac would translate those before the
        // lexer runs, and a real line/paragraph separator in the source would shift every line number after it.
        return Character.isISOControl(codePoint) || codePoint == 0x0085 || codePoint == 0x2028 || codePoint == 0x2029;
    }
}
