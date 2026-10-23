package dispatch.data;

/** An SSH tunnel to a project's Mongo could not be opened. */
public final class TunnelException extends RuntimeException {

    public TunnelException(String message) {
        super(message);
    }

    public TunnelException(String message, Throwable cause) {
        super(message, cause);
    }
}
