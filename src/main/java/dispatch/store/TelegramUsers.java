package dispatch.store;

import java.time.Instant;
import java.util.Optional;

/** The username book (G-1c): each member's last seen Telegram username, so an @username in a group names a member. */
public final class TelegramUsers {

    private TelegramUsers() {
    }

    /**
     * Stores {@code username} as {@code userId}'s, taking it from any account that held it before. Every message records
     * its author, so an unchanged name (compared case-sensitively: a case change is a change) writes nothing.
     */
    public static void record(Tx tx, long userId, String username, Instant now) {
        Optional<String> stored = tx.one("SELECT username FROM telegram_user WHERE user_id = ?", row -> row.string("username"), userId);
        if (stored.filter(username::equals).isPresent()) {
            return;
        }
        tx.update("DELETE FROM telegram_user WHERE lower(username) = lower(?) AND user_id <> ?", username, userId);
        tx.update("""
                        INSERT INTO telegram_user (user_id, username, updated_at) VALUES (?, ?, ?)
                        ON CONFLICT (user_id) DO UPDATE SET username = excluded.username, updated_at = excluded.updated_at""",
                userId, username, now);
    }

    /** @param username with or without the leading "@", in any case */
    public static Optional<Long> idOf(Tx tx, String username) {
        String bare = username.startsWith("@") ? username.substring(1) : username;
        return tx.one("SELECT user_id FROM telegram_user WHERE lower(username) = lower(?)", row -> row.longValue("user_id"), bare);
    }
}
