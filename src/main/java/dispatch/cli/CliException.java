package dispatch.cli;

/** A command line Dispatch cannot act on, or a setup step that failed. The message says what to do about it. */
public final class CliException extends RuntimeException {

    public CliException(String message) {
        super(message);
    }
}
