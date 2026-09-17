package dispatch.telegram;

/** A failed Bot API call. errorCode 0 means Telegram was not reached (network, timeout). */
public final class TelegramException extends RuntimeException {

    private final int errorCode;
    private final Integer retryAfterSeconds;

    TelegramException(String message, int errorCode, Integer retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int errorCode() {
        return errorCode;
    }

    /** Telegram's flood-control delay, null unless it sent one. */
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** Retrying cannot help: bad request, bad token, bot removed from the chat, or unknown method. */
    public boolean isPermanent() {
        return errorCode == 400 || errorCode == 401 || errorCode == 403 || errorCode == 404;
    }
}
