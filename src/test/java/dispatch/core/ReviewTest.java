package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewTest {

    @Test
    void readsVerdictAndSplitsBlockingFromMinor() {
        Review review = Review.parse("""
                {"verdict":"changes","findings":[
                  {"severity":"blocking","file":"src/A.java","line":12,"text":"NPE when x is null"},
                  {"severity":"minor","file":"src/B.java","line":0,"text":"name"}]}""");

        assertEquals("changes", review.verdict());
        assertEquals(List.of(new Review.Finding("blocking", "src/A.java", 12, "NPE when x is null")), review.blocking());
        assertEquals(1, review.minor().size());
    }

    @Test
    void okWithoutFindings() {
        Review review = Review.parse("{\"verdict\":\"ok\",\"findings\":[]}");

        assertTrue(review.blocking().isEmpty());
    }

    @Test
    void anAnswerInsideAJsonFenceIsRead() {
        // Gemini answers in text; its parser already unwraps fences for plans, but the loop must not depend on it.
        Review review = Review.parse("```json\n{\"verdict\":\"ok\",\"findings\":[]}\n```");

        assertEquals("ok", review.verdict());
    }

    @Test
    void anythingElseIsRefusedWithAReason() {
        assertThrows(IllegalArgumentException.class, () -> Review.parse(null));
        assertThrows(IllegalArgumentException.class, () -> Review.parse("Looks good to me!"));
        assertThrows(IllegalArgumentException.class, () -> Review.parse("{\"verdict\":\"maybe\",\"findings\":[]}"));
        assertThrows(IllegalArgumentException.class,
                () -> Review.parse("{\"verdict\":\"ok\",\"findings\":[{\"severity\":\"huge\",\"file\":\"a\",\"line\":1,\"text\":\"t\"}]}"));
    }
}
