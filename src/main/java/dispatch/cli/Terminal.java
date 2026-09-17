package dispatch.cli;

import java.util.List;
import java.util.function.Supplier;

/**
 * What the setup commands show the person at the terminal, and ask them. Every question throws {@link CliException} when
 * input ends or the person presses Ctrl+C, so a cancelled setup never continues with a made-up answer.
 */
public interface Terminal {

    /** @param hint shown dimmed beside the label; empty for none */
    record Option<T>(String label, String hint, T value) {
    }

    void say(String line);

    /** A heading for the next part of a setup, e.g. "1/5 Your bot". */
    void step(String title);

    void ok(String line);

    void warn(String line);

    void fail(String line);

    /** The answer, or {@code defaultValue} when the answer is blank; {@code defaultValue} may be null. */
    String ask(String question, String defaultValue);

    /** An answer that is not shown while typed, for secrets. */
    String askSecret(String question);

    /** One option's value, picked with the arrow keys (or its number) and Enter. */
    <T> T choose(String question, List<Option<T>> options, int defaultIndex);

    boolean confirm(String question, boolean defaultValue);

    /** Runs {@code work} while showing that something is happening, and returns its result. */
    <T> T during(String message, Supplier<T> work);
}
