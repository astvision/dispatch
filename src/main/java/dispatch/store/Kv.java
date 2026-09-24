package dispatch.store;

import java.util.Optional;

/** Small persistent settings, e.g. the Telegram update offset. */
public final class Kv {

    private Kv() {
    }

    public static Optional<String> get(Tx tx, String key) {
        return tx.one("SELECT value FROM kv WHERE key = ?", row -> row.string("value"), key);
    }

    public static void put(Tx tx, String key, String value) {
        tx.update("INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", key, value);
    }

    public static void delete(Tx tx, String key) {
        tx.update("DELETE FROM kv WHERE key = ?", key);
    }
}
