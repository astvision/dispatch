package dispatch.telegram;

/**
 * Telegram's side of the channel-neutral references the core stores: "telegram:&lt;id&gt;" for users and chats,
 * "telegram:&lt;chat&gt;/&lt;message&gt;" for messages.
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

    static String message(long chatId, long messageId) {
        return PREFIX + chatId + "/" + messageId;
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
        return slash < 0 ? null : Long.parseLong(body.substring(slash + 1));
    }

    private static String body(String ref) {
        if (ref == null || !ref.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not a Telegram reference: " + ref);
        }
        return ref.substring(PREFIX.length());
    }
}
