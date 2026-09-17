package dispatch.store;

/** A storage failure. Callers must not swallow it: state integrity depends on the database. */
public final class DatabaseException extends RuntimeException {

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
