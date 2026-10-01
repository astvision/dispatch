package dispatch.store;

import java.util.LinkedHashMap;
import java.util.Map;
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

    /** Every entry whose key starts with {@code prefix}, by key. */
    public static Map<String, String> withPrefix(Tx tx, String prefix) {
        Map<String, String> found = new LinkedHashMap<>();
        // substr rather than LIKE: a key may hold '_' or '%', which LIKE would read as wildcards.
        tx.list("SELECT key, value FROM kv WHERE substr(key, 1, ?) = ? ORDER BY key", row -> found.put(row.string("key"), row.string("value")),
                prefix.length(), prefix);
        return found;
    }

    public static void delete(Tx tx, String key) {
        tx.update("DELETE FROM kv WHERE key = ?", key);
    }
}
