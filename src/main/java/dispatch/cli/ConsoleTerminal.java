package dispatch.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;

/** The real terminal. Secrets are read through the console with echo off, which works in macOS, Linux and Windows terminals. */
public final class ConsoleTerminal implements Terminal {

    private final Console console = System.console();
    private final BufferedReader input = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));

    @Override
    public void say(String line) {
        System.out.println(line);
    }

    @Override
    public String ask(String question, String defaultValue) {
        System.out.print(question + (defaultValue == null ? ": " : " [" + defaultValue + "]: "));
        System.out.flush();
        try {
            String answer = input.readLine();
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
        char[] answer = console.readPassword(question + ": ");
        return answer == null ? null : new String(answer).strip();
    }
}
