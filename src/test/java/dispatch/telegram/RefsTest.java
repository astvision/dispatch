package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class RefsTest {

    @Test
    void messageInATopicCarriesItsThread() {
        String ref = Refs.message(100, 77, 55L);

        assertEquals("telegram:100/77@55", ref);
        assertEquals(100, Refs.chatId(ref));
        assertEquals(77L, Refs.messageId(ref));
        assertEquals(55L, Refs.threadId(ref));
    }

    @Test
    void partOfAMessageStillNamesTheMessageAndItsThread() {
        assertEquals(77L, Refs.messageId("telegram:100/77#2"));
        assertNull(Refs.threadId("telegram:100/77#2"));
        assertEquals(77L, Refs.messageId("telegram:100/77@55#3"));
        assertEquals(55L, Refs.threadId("telegram:100/77@55#3"));
    }

    @Test
    void messageOutsideTopicsHasNoThread() {
        String ref = Refs.message(-100, 12, null);

        assertEquals("telegram:-100/12", ref);
        assertEquals(12L, Refs.messageId(ref));
        assertNull(Refs.threadId(ref));
        assertNull(Refs.threadId("telegram:-100"));
    }
}
