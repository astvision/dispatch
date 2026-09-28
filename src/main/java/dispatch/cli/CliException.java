package dispatch.cli;

import dispatch.Text;

/**
 * A command line Dispatch cannot act on, or a setup step that failed. The message says what to do about it: in English
 * for the terminal, and in a page's language when a page asked.
 */
public class CliException extends RuntimeException {

    private final Text text;

    /** Words that read the same in every language, such as what another program wrote. */
    public CliException(String message) {
        super(message);
        this.text = Text.raw(message);
    }

    public CliException(Text message) {
        super(message.english());
        this.text = message;
    }

    public Text text() {
        return text;
    }
}
