package dispatch.core;

import java.nio.file.Path;

/** Where a task's attachments are downloaded from: the channel they were sent in. */
public interface AttachmentSource {

    /** Saves the file {@code fileRef} as {@code target}; throws with a reason the member can read when it cannot. */
    void download(String fileRef, Path target);
}
