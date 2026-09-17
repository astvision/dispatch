package dispatch.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Attributes;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

/**
 * The terminal Dispatch's setup runs in, through JLine, which gives raw keyboard input on macOS, Linux and Windows alike
 * (ADR 0016). Without a real terminal (input or output redirected) JLine gives a dumb one: choices and confirmations are
 * then asked as plain questions, and secrets are refused.
 */
public final class JLineTerminal implements Terminal, AutoCloseable {

    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final int ESCAPE = 27;
    private static final int CTRL_C = 3;
    /** How long to wait for the rest of an escape sequence, such as an arrow key's. */
    private static final long SEQUENCE_WAIT_MILLIS = 50;

    private final org.jline.terminal.Terminal terminal;
    private final LineReader reader;
    private final PrintWriter out;
    private final boolean dumb;

    JLineTerminal(org.jline.terminal.Terminal terminal) {
        this.terminal = terminal;
        this.out = terminal.writer();
        this.dumb = terminal.getType().startsWith(org.jline.terminal.Terminal.TYPE_DUMB);
        // No history file: nothing typed during setup is kept on disk.
        this.reader = LineReaderBuilder.builder().terminal(terminal).option(LineReader.Option.DISABLE_EVENT_EXPANSION, true).build();
    }

    /** The terminal this process runs in. */
    public static JLineTerminal system() {
        try {
            return new JLineTerminal(TerminalBuilder.builder().system(true).dumb(true).build());
        } catch (IOException e) {
            throw new CliException("cannot open the terminal: " + e.getMessage());
        }
    }

    @Override
    public void say(String line) {
        out.println(line);
        out.flush();
    }

    @Override
    public void step(String title) {
        out.println();
        say(styled(title, AttributedStyle.BOLD.foreground(AttributedStyle.CYAN)));
    }

    @Override
    public void ok(String line) {
        say(styled("✔ ", AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN)) + line);
    }

    @Override
    public void warn(String line) {
        say(styled("! ", AttributedStyle.BOLD.foreground(AttributedStyle.YELLOW)) + line);
    }

    @Override
    public void fail(String line) {
        say(styled("✖ ", AttributedStyle.BOLD.foreground(AttributedStyle.RED)) + line);
    }

    @Override
    public String ask(String question, String defaultValue) {
        String answer = readLine(prompt(question, defaultValue), null);
        return answer.isBlank() && defaultValue != null ? defaultValue : answer.strip();
    }

    @Override
    public String askSecret(String question) {
        if (dumb) {
            throw new CliException("run this in a terminal: a secret is never read from a pipe or file, where it could be echoed or logged");
        }
        return readLine(prompt(question, null), '•').strip();
    }

    @Override
    public <T> T choose(String question, List<Option<T>> options, int defaultIndex) {
        if (dumb) {
            return chooseByNumber(question, options, defaultIndex);
        }
        int selected = defaultIndex;
        Attributes saved = terminal.enterRawMode();
        terminal.puts(Capability.cursor_invisible);
        try {
            say(prompt(question, null) + styled("(↑↓ and Enter)", AttributedStyle.DEFAULT.faint()));
            drawOptions(options, selected);
            while (true) {
                int key = read(-1);
                int moved = switch (key) {
                    case '\r', '\n' -> Integer.MIN_VALUE;
                    case 'k' -> -1;
                    case 'j' -> 1;
                    case ESCAPE -> arrow();
                    default -> 0;
                };
                if (key == -1 || key == CTRL_C) {
                    throw new CliException("cancelled");
                }
                if (key >= '1' && key <= '9' && key - '1' < options.size()) {
                    selected = key - '1';
                    moved = Integer.MIN_VALUE;
                }
                if (moved == Integer.MIN_VALUE) {
                    break;
                }
                selected = Math.floorMod(selected + moved, options.size());
                out.print("[" + options.size() + "A");
                drawOptions(options, selected);
            }
            // Collapse the list into the question's answer.
            out.print("[" + (options.size() + 1) + "A\r[J");
            say(prompt(question, null) + styled(options.get(selected).label(), AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)));
            return options.get(selected).value();
        } finally {
            terminal.puts(Capability.cursor_visible);
            terminal.setAttributes(saved);
            out.flush();
        }
    }

    @Override
    public boolean confirm(String question, boolean defaultValue) {
        if (dumb) {
            return ask(question + " (y/n)", defaultValue ? "y" : "n").toLowerCase().startsWith("y");
        }
        Attributes saved = terminal.enterRawMode();
        try {
            out.print(prompt(question, null) + styled(defaultValue ? "(Y/n) " : "(y/N) ", AttributedStyle.DEFAULT.faint()));
            out.flush();
            while (true) {
                int key = read(-1);
                if (key == -1 || key == CTRL_C) {
                    out.println();
                    throw new CliException("cancelled");
                }
                Boolean answer = switch (key) {
                    case 'y', 'Y' -> true;
                    case 'n', 'N' -> false;
                    case '\r', '\n' -> defaultValue;
                    default -> null;
                };
                if (answer != null) {
                    say(styled(answer ? "Yes" : "No", AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)));
                    return answer;
                }
            }
        } finally {
            terminal.setAttributes(saved);
            out.flush();
        }
    }

    @Override
    public <T> T during(String message, Supplier<T> work) {
        if (dumb) {
            say("… " + message);
            return work.get();
        }
        AtomicBoolean done = new AtomicBoolean();
        frame(0, message);
        Thread spinner = Thread.ofVirtual().name("setup-spinner").start(() -> {
            for (int i = 1; !done.get(); i++) {
                frame(i, message);
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        try {
            return work.get();
        } finally {
            done.set(true);
            spinner.interrupt();
            try {
                spinner.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (out) {
                out.print("\r[2K");
                out.flush();
            }
        }
    }

    @Override
    public void close() {
        try {
            terminal.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void frame(int index, String message) {
        synchronized (out) {
            out.print("\r[2K" + styled(SPINNER[index % SPINNER.length], AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)) + " " + message);
            out.flush();
        }
    }

    private void drawOptions(List<? extends Option<?>> options, int selected) {
        for (int i = 0; i < options.size(); i++) {
            Option<?> option = options.get(i);
            String hint = option.hint().isEmpty() ? "" : "  " + styled(option.hint(), AttributedStyle.DEFAULT.faint());
            String line = i == selected
                    ? styled("❯ " + option.label(), AttributedStyle.BOLD.foreground(AttributedStyle.CYAN)) + hint
                    : "  " + option.label() + hint;
            out.print("\r[2K" + line + "\n");
        }
        out.flush();
    }

    /** The move an arrow key's escape sequence asks for; 0 for anything else. */
    private int arrow() {
        int bracket = read(SEQUENCE_WAIT_MILLIS);
        if (bracket != '[' && bracket != 'O') {
            return 0;
        }
        return switch (read(SEQUENCE_WAIT_MILLIS)) {
            case 'A' -> -1;
            case 'B' -> 1;
            default -> 0;
        };
    }

    /** The next key, -1 at the end of input, or -2 when {@code timeoutMillis} passes first (negative waits for ever). */
    private int read(long timeoutMillis) {
        try {
            NonBlockingReader keys = terminal.reader();
            return timeoutMillis < 0 ? keys.read() : keys.read(timeoutMillis);
        } catch (IOException e) {
            throw new CliException("cannot read the keyboard: " + e.getMessage());
        }
    }

    private <T> T chooseByNumber(String question, List<Option<T>> options, int defaultIndex) {
        say(prompt(question, null));
        for (int i = 0; i < options.size(); i++) {
            say("  " + (i + 1) + ") " + options.get(i).label() + (options.get(i).hint().isEmpty() ? "" : "  " + options.get(i).hint()));
        }
        String answer = ask("Number", Integer.toString(defaultIndex + 1));
        try {
            int index = Integer.parseInt(answer.strip()) - 1;
            if (index >= 0 && index < options.size()) {
                return options.get(index).value();
            }
        } catch (NumberFormatException e) {
            // Asked again below.
        }
        warn("choose a number from 1 to " + options.size());
        return chooseByNumber(question, options, defaultIndex);
    }

    private String readLine(String prompt, Character mask) {
        try {
            return mask == null ? reader.readLine(prompt) : reader.readLine(prompt, mask);
        } catch (UserInterruptException | EndOfFileException e) {
            throw new CliException("cancelled");
        }
    }

    private String prompt(String question, String defaultValue) {
        return styled("? ", AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)) + styled(question, AttributedStyle.BOLD)
                + (defaultValue == null ? " " : " " + styled("(" + defaultValue + ")", AttributedStyle.DEFAULT.faint()) + " ");
    }

    private String styled(String text, AttributedStyle style) {
        return new AttributedStringBuilder().style(style).append(text).toAnsi(terminal);
    }
}
