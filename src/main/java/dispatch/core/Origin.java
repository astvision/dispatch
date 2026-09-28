package dispatch.core;

import java.util.Objects;
import java.util.UUID;

/**
 * Where a task comes from: the message that gave it, or a page. Unique per task, so giving the same one twice gives one
 * task (ADR 0010). It names a source; it never says where a reply goes.
 */
public record Origin(String ref) {

    /** What a page's origin starts with: no Telegram message, so nothing in the chat to reply to (D-2b). */
    public static final String PAGE = "desk:";

    public Origin {
        Objects.requireNonNull(ref, "ref");
    }

    /** A page has no message a repeat could name again: a new origin every time. */
    public static Origin page() {
        return new Origin(PAGE + UUID.randomUUID());
    }
}
