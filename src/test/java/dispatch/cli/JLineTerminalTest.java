package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jline.terminal.Attributes;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

/** The real terminal, driven through a virtual xterm with the bytes keys send. */
class JLineTerminalTest {

    private static final String ESC = String.valueOf((char) 27);
    private static final String DOWN = ESC + "[B";
    private static final String UP = ESC + "[A";
    private static final String CTRL_C = String.valueOf((char) 3);

    private final ByteArrayOutputStream screen = new ByteArrayOutputStream();

    @Test
    void arrowKeysChooseAnOptionAndEnterConfirmsIt() throws IOException {
        List<Terminal.Option<String>> efforts = List.of(new Terminal.Option<>("Default", "Claude Code decides", null),
                new Terminal.Option<>("High", "", "high"), new Terminal.Option<>("Max", "slowest", "max"));

        try (JLineTerminal terminal = terminal(DOWN + DOWN + UP + DOWN + "\r")) {
            assertEquals("max", terminal.choose("Effort", efforts, 0));
        }

        String shown = shown();
        assertTrue(shown.contains("Effort") && shown.contains("Max"), shown);
    }

    @Test
    void numberKeysPickAnOptionDirectly() throws IOException {
        try (JLineTerminal terminal = terminal("2")) {
            assertEquals("team", terminal.choose("Who uses this bot?",
                    List.of(new Terminal.Option<>("Just me", "", "me"), new Terminal.Option<>("My team", "", "team")), 0));
        }
    }

    @Test
    void enterKeepsTheDefaultAnswer() throws IOException {
        try (JLineTerminal terminal = terminal("\r\r\r")) {
            assertEquals("b", terminal.choose("Pick", List.of(new Terminal.Option<>("A", "", "a"), new Terminal.Option<>("B", "", "b")), 1));
            assertTrue(terminal.confirm("Start it now?", true));
            assertEquals("main", terminal.ask("Branch", "main"));
        }
    }

    @Test
    void typedAnswersAndSecretsAreRead() throws IOException {
        String secret = "123456789" + ":AAH-secret-for-tests";
        try (JLineTerminal terminal = terminal("develop\r" + secret + "\rn")) {
            assertEquals("develop", terminal.ask("Branch", "main"));
            assertEquals(secret, terminal.askSecret("Bot token"));
            assertFalse(terminal.confirm("Start it now?", true));
        }

        assertFalse(shown().contains("AAH-secret-for-tests"), "a secret is never echoed: " + shown());
    }

    @Test
    void ctrlCOrTheEndOfInputCancels() throws IOException {
        try (JLineTerminal terminal = terminal(CTRL_C)) {
            assertThrows(CliException.class, () -> terminal.choose("Pick", List.of(new Terminal.Option<>("A", "", "a")), 0));
        }
        try (JLineTerminal terminal = terminal("")) {
            assertThrows(CliException.class, () -> terminal.ask("Branch", "main"));
        }
    }

    @Test
    void workRunsWhileTheSpinnerShowsItsMessage() throws IOException {
        try (JLineTerminal terminal = terminal("")) {
            assertEquals(42, terminal.during("Checking the token", () -> 42));
            terminal.ok("@acme_dispatch_bot");
        }

        assertTrue(shown().contains("Checking the token") && shown().contains("@acme_dispatch_bot"), shown());
    }

    /**
     * A virtual terminal whose keys are all typed at once. Its echo starts off, as a real terminal's is while JLine reads:
     * otherwise the virtual terminal would echo keys typed ahead of a prompt, which no real prompt does.
     */
    private JLineTerminal terminal(String keys) throws IOException {
        Attributes noEcho = new Attributes();
        noEcho.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        return new JLineTerminal(TerminalBuilder.builder().system(false).type("xterm-256color").encoding(StandardCharsets.UTF_8)
                .attributes(noEcho).streams(new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)), screen).build());
    }

    private String shown() {
        return screen.toString(StandardCharsets.UTF_8);
    }
}
