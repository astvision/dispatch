package dispatch.telegram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An agent's markdown answer as Telegram HTML (spec: answers). Text is escaped before anything is converted, so agent text
 * can never add a tag; then only a closed fence, inline code, bold, a heading and a list item become the three tags this
 * emits. Whatever is not closed stays the text it was.
 */
final class TelegramMarkdown {

    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*\\n]+)\\*\\*");
    private static final Pattern HEADING = Pattern.compile("#{1,3} +(.+)");
    private static final Pattern BULLET = Pattern.compile("([ \\t]*)[-*] +");

    private TelegramMarkdown() {
    }

    static String toHtml(String markdown) {
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            int close = lines[i].strip().startsWith("```") ? closingFence(lines, i + 1) : -1;
            if (close < 0) {
                out.add(line(lines[i]));
                continue;
            }
            out.add("<pre>" + escape(String.join("\n", Arrays.asList(lines).subList(i + 1, close))) + "</pre>");
            i = close;
        }
        return String.join("\n", out);
    }

    private static int closingFence(String[] lines, int from) {
        for (int i = from; i < lines.length; i++) {
            if (lines[i].strip().equals("```")) {
                return i;
            }
        }
        return -1;
    }

    private static String line(String raw) {
        Matcher heading = HEADING.matcher(raw);
        if (heading.matches()) {
            // Already bold: its own ** would nest a tag in a tag.
            return "<b>" + inline(heading.group(1), false) + "</b>";
        }
        Matcher bullet = BULLET.matcher(raw);
        if (bullet.lookingAt()) {
            return bullet.group(1) + "• " + inline(raw.substring(bullet.end()), true);
        }
        return inline(raw, true);
    }

    /** Inline code first, so nothing inside it reads as bold. */
    private static String inline(String raw, boolean bold) {
        StringBuilder html = new StringBuilder();
        Matcher code = INLINE_CODE.matcher(raw);
        int last = 0;
        while (code.find()) {
            html.append(prose(raw.substring(last, code.start()), bold)).append("<code>").append(escape(code.group(1)))
                    .append("</code>");
            last = code.end();
        }
        return html.append(prose(raw.substring(last), bold)).toString();
    }

    private static String prose(String raw, boolean bold) {
        String escaped = escape(raw);
        return bold ? BOLD.matcher(escaped).replaceAll("<b>$1</b>") : escaped;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
