package dispatch.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;

/**
 * The real terminal. In a terminal, everything is read through the console, so secrets can be read with echo off on macOS,
 * Linux and Windows alike, and no buffered reader takes input the console needs. Piped input serves plain questions only.
 */
public final class ConsoleTerminal implements Terminal {

    /** Null unless attached to a terminal: since JDK 22, System.console() may also return one for redirected streams. */
    private final Console console = System.console() != null && System.console().isTerminal() ? System.console() : null;
    private final BufferedReader input = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));

    @Override
    public void say(String line) {
        System.out.println(line);
    }

    @Override
    public String ask(String question, String defaultValue) {
        String prompt = question + (defaultValue == null ? ": " : " [" + defaultValue + "]: ");
        try {
            String answer;
            if (console != null) {
                answer = console.readLine("%s", prompt);
            } else {
                System.out.print(prompt);
                System.out.flush();
                answer = input.readLine();
            }
            if (answer == null) {
                return null;
            }
            return answer.isBlank() && defaultValue != null ? defaultValue : answer.strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String askSecret(String question) {
        if (console == null) {
            throw new CliException("run this in a terminal: a secret is never read from a pipe or file, where it could be echoed or logged");
        }
        char[] answer = console.readPassword("%s", question + ": ");
        return answer == null ? null : new String(answer).strip();
    }
}
