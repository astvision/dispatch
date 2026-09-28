package dispatch.config;

import dispatch.Text;

/** A config Dispatch cannot run with; the message says what to fix, in English and in a page's language. */
public class ConfigException extends RuntimeException {

    private final Text text;

    /** Words that read the same in every language, such as what the YAML parser wrote. */
    public ConfigException(String message) {
        super(message);
        this.text = Text.raw(message);
    }

    public ConfigException(Text message) {
        super(message.english());
        this.text = message;
    }

    public Text text() {
        return text;
    }
}
