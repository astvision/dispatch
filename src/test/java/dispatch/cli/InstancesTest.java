package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstancesTest {

    @TempDir
    Path home;

    @Test
    void findsTheDefaultAndNamedInstancesAndKeepsOneThatDoesNotLoad() throws IOException {
        Locations defaults = Locations.of("Linux", Map.of(), home);
        TestConfigs.write(defaults.configFile(), "111:AAA", "alm");
        TestConfigs.write(defaults.forInstance("team").configFile(), "222:BBB", "alm");
        Files.writeString(defaults.configDir().resolve("broken.yaml"), "team: [");
        Files.writeString(defaults.configDir().resolve("worker.yaml"), "team: https://x\n");

        List<Instances.Found> found = Instances.discover(defaults, Map.of());

        assertEquals(Arrays.asList(null, "broken", "team"), found.stream().map(Instances.Found::name).toList());
        assertNotNull(found.get(1).error());
        assertEquals("222", Instances.botId(found.get(2).config().secrets().telegramBotToken()));
    }

    /**
     * The process environment overrides a config's own .env (SecretsFile.environment), which is right for the
     * instance dispatch was actually invoked for, but wrong for every OTHER instance discovered here: forcing this
     * process's token onto them would make them all look like the same bot (M: several instances on one computer).
     */
    @Test
    void theProcessEnvironmentsBotTokenDoesNotOverrideAnotherInstancesOwnToken() throws IOException {
        Locations defaults = Locations.of("Linux", Map.of(), home);
        TestConfigs.write(defaults.configFile(), "111:AAA", "alm");
        TestConfigs.write(defaults.forInstance("team").configFile(), "222:BBB", "alm");

        List<Instances.Found> found = Instances.discover(defaults, Map.of("TELEGRAM_BOT_TOKEN", "999:ZZZ"));

        assertEquals("111", Instances.botId(found.get(0).config().secrets().telegramBotToken()));
        assertEquals("222", Instances.botId(found.get(1).config().secrets().telegramBotToken()));
    }
}
