package dispatch.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
