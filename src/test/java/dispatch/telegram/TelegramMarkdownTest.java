package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Random;
import org.junit.jupiter.api.Test;

class TelegramMarkdownTest {

    @Test
    void aFencedBlockIsPreformattedAndNothingInsideItIsConverted() {
        assertEquals("<pre>if (a &lt; b &amp;&amp; c) { **x** }</pre>",
                TelegramMarkdown.toHtml("```java\nif (a < b && c) { **x** }\n```"));
    }

    @Test
    void inlineCodeAndBold() {
        assertEquals("Use <code>List&lt;String&gt;</code> for <b>speed</b>, not <code>**this**</code>",
                TelegramMarkdown.toHtml("Use `List<String>` for **speed**, not `**this**`"));
    }

    @Test
    void headingsAreBoldAndListItemsGetABullet() {
        assertEquals("<b>Cause</b>\n• one\n  • two", TelegramMarkdown.toHtml("## Cause\n- one\n  * two"));
    }

    @Test
    void whatIsNotClosedStaysTheTextItWas() {
        assertEquals("```\nopen", TelegramMarkdown.toHtml("```\nopen"));
        assertEquals("a `b", TelegramMarkdown.toHtml("a `b"));
        assertEquals("**c", TelegramMarkdown.toHtml("**c"));
    }

    @Test
    void htmlInTheTextIsEscaped() {
        assertEquals("&lt;b&gt;x&lt;/b&gt; &amp; &lt;script&gt;", TelegramMarkdown.toHtml("<b>x</b> & <script>"));
    }

    @Test
    void tagsAreBalancedAndNoOtherTagGetsThroughWhateverTheInput() {
        Random random = new Random(7);
        String alphabet = "`*#- \n<>&ab";
        for (int i = 0; i < 2000; i++) {
            StringBuilder input = new StringBuilder();
            for (int j = random.nextInt(60); j > 0; j--) {
                input.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String html = TelegramMarkdown.toHtml(input.toString());
            for (String tag : new String[] {"b", "code", "pre"}) {
                assertEquals(count(html, "<" + tag + ">"), count(html, "</" + tag + ">"), input + " -> " + html);
            }
            String rest = html.replaceAll("</?(b|code|pre)>", "");
            assertFalse(rest.contains("<") || rest.contains(">"), input + " -> " + html);
        }
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }
}
