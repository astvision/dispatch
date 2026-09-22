package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TelegramNamesTest {

    @Test
    void controlCharactersAndLineSeparatorsBecomeASpaceAndRunsCollapse() {
        assertEquals("Bold Bat", TelegramNames.clean("Bold\u0007 Bat"));
        assertEquals("Bold Bat", TelegramNames.clean("Bold\tBat"));
        assertEquals("Bold Bat", TelegramNames.clean("Bold\nBat"));
        assertEquals("Bold Bat", TelegramNames.clean("Bold\u0085Bat"));
        assertEquals("Bold Bat", TelegramNames.clean("Bold Bat"));
        assertEquals("Bold Bat", TelegramNames.clean("Bold Bat"));
        assertEquals("Bold Bat", TelegramNames.clean("Bold   \u0001\u0002  Bat"));
    }

    @Test
    void plainTextIsLeftAsIs() {
        assertEquals("Bold", TelegramNames.clean("Bold"));
        assertEquals("Bold Bat", TelegramNames.clean("Bold Bat"));
    }

    @Test
    void allControlIsStrippedToEmpty() {
        assertEquals("", TelegramNames.clean("\u0007\u0001"));
        assertEquals("", TelegramNames.clean("   "));
    }
}
