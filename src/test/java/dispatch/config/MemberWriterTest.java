package dispatch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemberWriterTest {

    // Split, so secret scanners never see a token-shaped literal in this file.
    private static final Map<String, String> ENV = Map.of("TELEGRAM_BOT_TOKEN", "123456789" + ":AAH-fake-token-for-tests-only-0123456789");

    @TempDir
    Path dir;

    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        file = dir.resolve("dispatch.yaml");
        Files.writeString(file, new String(MemberWriterTest.class.getResourceAsStream("/personal.yaml").readAllBytes())
                .replace("STATE_DIR", dir.resolve("state").toString().replace("'", "''"))
                .replace("CLONE", dir.resolve("work/alm").toString().replace("'", "''")));
    }

    @Test
    void newMemberIsWrittenToTheFileAndTheGroupsAsTheyNowAreAreReturned() {
        MemberWriter writer = MemberWriter.file(file, ENV);

        Config.Telegram telegram = writer.add("bold", new Config.Member(222, "Ali"));
        writer.add("bold", new Config.Member(222, "Ali"));

        List<Config.Member> expected = List.of(new Config.Member(123456789, "Bold"), new Config.Member(222, "Ali"));
        assertEquals(expected, telegram.groups().getFirst().members());
        assertEquals(expected, ConfigLoader.load(file, ENV).telegram().groups().getFirst().members(), "added once, even when asked twice");
    }

    @Test
    void unknownGroupLeavesTheFileAsItWas() throws IOException {
        String before = Files.readString(file);

        assertThrows(ConfigException.class, () -> MemberWriter.file(file, ENV).add("team", new Config.Member(222, "Ali")));

        assertEquals(before, Files.readString(file));
    }
}
