package dispatch.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ListCommandTest {

    @TempDir
    Path home;

    @Test
    void printsOneLinePerInstanceWithModeAndServiceState() throws IOException {
        Locations defaults = Locations.of("Linux", java.util.Map.of(), home);
        TestConfigs.write(defaults.configFile(), "111:AAA", "alm");
        TestConfigs.write(defaults.forInstance("team").configFile(), "222:BBB", "alm");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exit = new ListCommand(new PrintStream(out, true, UTF_8), instance -> new FakeService(instance == null))
                .run(defaults, java.util.Map.of());

        String text = out.toString(UTF_8);
        assertEquals(0, exit);
        assertTrue(text.contains("default") && text.contains("personal") && text.contains("running"), text);
        assertTrue(text.contains("team") && text.contains("stopped") && text.contains("dispatch service start --instance team"), text);
    }

    @Test
    void anInstanceThatFailsToLoadNeverPrintsARawTokenShapedValue() throws IOException {
        Locations defaults = Locations.of("Linux", java.util.Map.of(), home);
        TestConfigs.write(defaults.configFile(), "111:AAA", "alm");
        // A token pasted into a Long field (chatId): Jackson's type-mismatch message embeds the raw value verbatim.
        String tokenShaped = "123456789:" + "A".repeat(35);
        Files.writeString(defaults.forInstance("team").configFile(), """
                team: bold
                stateDir: '%s'
                telegram:
                  groups:
                    - name: bold
                      chatId: %s
                      members:
                        - id: 123456789
                          name: 'Bold'
                      projects: []
                scheduler:
                  maxConcurrentRuns: 1
                delivery:
                  authorName: 'x'
                  authorEmail: 'x@example.com'
                limits:
                  plan:
                    timeout: 15m
                    budgetUsd: 2
                  execute:
                    timeout: 60m
                    budgetUsd: 10
                agents:
                  claude-code:
                    command: 'claude'
                """.formatted(home.resolve("state-team"), tokenShaped));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exit = new ListCommand(new PrintStream(out, true, UTF_8), instance -> new FakeService(instance == null))
                .run(defaults, java.util.Map.of());

        String text = out.toString(UTF_8);
        assertEquals(0, exit);
        assertTrue(text.contains("(does not load:"), text);
        assertFalse(text.contains(tokenShaped), text);
    }

    private static final class FakeService implements Service {

        private final boolean running;

        FakeService(boolean running) {
            this.running = running;
        }

        @Override
        public String describe() {
            return "fake";
        }

        @Override
        public void install(Spec spec) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public Status status() {
            return new Status(true, running, running ? "running" : "stopped", List.of());
        }

        @Override
        public void uninstall() {
        }

        @Override
        public Kind kind() {
            return Kind.DISPATCH;
        }
    }
}
