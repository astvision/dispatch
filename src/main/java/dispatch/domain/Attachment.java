package dispatch.domain;

/**
 * A file sent with a task, downloaded into the task's attachments directory for its agent to read.
 *
 * @param fileRef the channel's reference for downloading it
 * @param name    a safe file name, unique within the task
 * @param size    bytes, null if the channel did not say
 */
public record Attachment(String fileRef, String name, Long size) {

    /** The Bot API's limit for downloading a file; larger ones are listed as skipped. */
    public static final long MAX_BYTES = 20L * 1024 * 1024;

    public boolean tooLarge() {
        return size != null && size > MAX_BYTES;
    }

    /**
     * {@code original} reduced to letters, digits, '.', '_' and '-', numbered so two files never collide. The name comes from
     * whoever sent the file, so it must not be able to leave the directory it is saved in.
     */
    public static String safeName(int number, String original) {
        String cleaned = original == null ? "" : original.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^[.]+", "");
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(cleaned.length() - 80);
        }
        return number + "-" + (cleaned.isEmpty() ? "file" : cleaned);
    }
}
