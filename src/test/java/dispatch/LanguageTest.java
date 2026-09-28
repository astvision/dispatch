package dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LanguageTest {

    @Test
    void theFirstRangeOfAcceptLanguageDecides() {
        assertEquals(Language.MN, Language.fromAcceptLanguage("mn"));
        assertEquals(Language.MN, Language.fromAcceptLanguage("mn-MN,mn;q=0.9,en;q=0.8"));
        assertEquals(Language.MN, Language.fromAcceptLanguage("MN-Cyrl-MN"));
        assertEquals(Language.MN, Language.fromAcceptLanguage(" mn;q=1"));
        assertEquals(Language.EN, Language.fromAcceptLanguage("en-US,en;q=0.9,mn;q=0.8"));
        assertEquals(Language.EN, Language.fromAcceptLanguage("ru"));
        assertEquals(Language.EN, Language.fromAcceptLanguage("mnx"));
        assertEquals(Language.EN, Language.fromAcceptLanguage(""));
        assertEquals(Language.EN, Language.fromAcceptLanguage(null));
    }
}
