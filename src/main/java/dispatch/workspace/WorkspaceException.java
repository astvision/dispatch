package dispatch.workspace;

/** Preparing a task's workspace failed; the message is meant for the team (it ends up in the failure report). */
public final class WorkspaceException extends RuntimeException {

    public WorkspaceException(String message) {
        super(message);
    }

    public WorkspaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
