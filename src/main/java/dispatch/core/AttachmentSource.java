package dispatch.core;

import java.nio.file.Path;

/**
 * Where a task's attachments are downloaded from: the channel they were sent in.
 *
 * <p>An implementation must bound its own network wait. Nothing above this interface puts a deadline on
 * {@link #download}: a caller that fetches a task's file over HTTP (the worker protocol's {@code /api/worker/attachment})
 * runs it inline, before writing any response, so a fetch that never returns parks that request's thread for as long
 * as the hang lasts.
 */
public interface AttachmentSource {

    /** Saves the file {@code fileRef} as {@code target}; throws with a reason the member can read when it cannot. */
    void download(String fileRef, Path target);
}
