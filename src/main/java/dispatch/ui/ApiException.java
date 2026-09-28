package dispatch.ui;

import dispatch.Text;

/**
 * An API error with its own HTTP status and error code, e.g. 409 "conflict"; its message is written for people, in the
 * language the page asked for (English in the log).
 */
public final class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Text text;

    public ApiException(int status, String code, Text message) {
        super(message.english());
        this.status = status;
        this.code = code;
        this.text = message;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Text text() {
        return text;
    }
}
