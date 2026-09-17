package dispatch.telegram;

/**
 * Telegram's side of the channel-neutral references the core stores: "telegram:&lt;id&gt;" for users and chats,
 * "telegram:&lt;chat&gt;/&lt;message&gt;" for messages, with "@&lt;thread&gt;" appended for a message written in a topic.
 */
final class Refs {

    private static final String PREFIX = "telegram:";

    private Refs() {
    }

    static String user(long userId) {
        return PREFIX + userId;
    }

    static String chat(long chatId) {
        return PREFIX + chatId;
    }

    /** @param threadId the topic the message was written in, null outside topics */
    static String message(long chatId, long messageId, Long threadId) {
        return PREFIX + chatId + "/" + messageId + (threadId == null ? "" : "@" + threadId);
    }

    static long chatId(String ref) {
        String body = body(ref);
        int slash = body.indexOf('/');
        return Long.parseLong(slash < 0 ? body : body.substring(0, slash));
    }

    /** Null for a chat reference. */
    static Long messageId(String ref) {
        String body = body(ref);
        int slash = body.indexOf('/');
        if (slash < 0) {
            return null;
        }
        int at = body.indexOf('@', slash);
        return Long.parseLong(at < 0 ? body.substring(slash + 1) : body.substring(slash + 1, at));
    }

    /** Null unless the reference is to a message written in a topic. */
    static Long threadId(String ref) {
        String body = body(ref);
        int at = body.indexOf('@');
        return at < 0 ? null : Long.parseLong(body.substring(at + 1));
    }

    private static String body(String ref) {
        if (ref == null || !ref.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not a Telegram reference: " + ref);
        }
        return ref.substring(PREFIX.length());
    }
}
