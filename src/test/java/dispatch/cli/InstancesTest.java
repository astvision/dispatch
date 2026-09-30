package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void anotherInstancesConfigDirStateAndClonesAreHiddenAndOneThatDoesNotLoadIsLogged() throws IOException {
        Locations defaults = Locations.of("Linux", Map.of(), home);
        TestConfigs.write(defaults.forInstance("team").configFile(), "222:BBB", "alm");
        Files.writeString(defaults.configDir().resolve("broken.yaml"), "team: [");
        Path workerFile = Files.createDirectories(home.resolve("elsewhere")).resolve("worker.yaml");

        java.io.ByteArrayOutputStream logged = new java.io.ByteArrayOutputStream();
        java.io.PrintStream out = System.out;
        List<Path> hidden;
        try {
            System.setOut(new java.io.PrintStream(logged, true, java.nio.charset.StandardCharsets.UTF_8));
            hidden = Instances.othersPrivate(defaults, workerFile, Map.of());
        } finally {
            System.setOut(out);
        }

        assertTrue(hidden.contains(defaults.configDir()), "the team instance's secrets file lives there: " + hidden);
        assertTrue(hidden.contains(defaults.configDir().resolve("state-team")), hidden.toString());
        assertTrue(hidden.contains(defaults.configDir().resolve("alm")), hidden.toString());
        String log = logged.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(log.contains("level=WARN event=sandbox.instance_unreadable") && log.contains("broken.yaml"), log);
    }
}
