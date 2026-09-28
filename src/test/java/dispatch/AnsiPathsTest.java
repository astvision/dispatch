package dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** A path as Windows' ANSI code page can spell it, for native code that reads it there. */
class AnsiPathsTest {

    private static final Charset ENGLISH_WINDOWS = Charset.forName("windows-1252");

    @TempDir
    Path dir;

    @Test
    void aPathTheCodePageCanSpellStaysAsItIs() {
        assertEquals(Optional.of("C:\\Users\\José\\AppData\\Local\\Temp\\"),
                AnsiPaths.of("C:\\Users\\José\\AppData\\Local\\Temp\\", ENGLISH_WINDOWS, path -> fail("no short name needed")));
    }

    @Test
    void aPathWithOtherLettersIsGivenByItsShortName() {
        assertEquals(Optional.of("C:\\Users\\5C0E~1\\AppData\\Local\\Temp\\"),
                AnsiPaths.of("C:\\Users\\Өлзий\\AppData\\Local\\Temp\\", ENGLISH_WINDOWS, path -> "C:\\Users\\5C0E~1\\AppData\\Local\\Temp\\"));
    }

    @Test
    void aPathWithoutAShortNameCannotBeSpelled() {
        // Windows answers with the long name when the volume keeps no short names.
        assertEquals(Optional.empty(), AnsiPaths.of("D:\\Өлзий\\Temp\\", ENGLISH_WINDOWS, path -> path));
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "short names are Windows'")
    void windowsSpellsAFolderOutsideItsCodePageByItsShortName() throws IOException {
        // Ө is in no Windows code page, not even Mongolian Windows' 1251.
        Path folder = Files.createDirectory(dir.resolve("Өлзий тест"));

        String spelled = AnsiPaths.of(folder).orElseThrow();

        assertTrue(Charset.forName(System.getProperty("native.encoding")).newEncoder().canEncode(spelled), spelled);
        assertTrue(Files.isSameFile(folder, Path.of(spelled)), spelled);
    }
}
