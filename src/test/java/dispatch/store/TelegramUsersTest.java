package dispatch.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The username book (G-1c): an @username in a group resolves to the member who last used it. */
class TelegramUsersTest {

    private static final Instant T0 = Instant.parse("2026-09-24T10:00:00Z");

    @TempDir
    Path dir;

    private Database db;

    @BeforeEach
    void open() {
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void lookupIsCaseInsensitiveAndIgnoresTheAt() {
        db.transaction(tx -> TelegramUsers.record(tx, 200, "Ali_Dev", T0));

        assertEquals(Optional.of(200L), db.transactionReturning(tx -> TelegramUsers.idOf(tx, "ali_dev")));
        assertEquals(Optional.of(200L), db.transactionReturning(tx -> TelegramUsers.idOf(tx, "@ALI_DEV")));
        assertEquals(Optional.empty(), db.transactionReturning(tx -> TelegramUsers.idOf(tx, "sara_dev")));
    }

    @Test
    void aNewUsernameReplacesTheOldOne() {
        db.transaction(tx -> TelegramUsers.record(tx, 200, "ali_dev", T0));
        db.transaction(tx -> TelegramUsers.record(tx, 200, "ali_new", T0.plusSeconds(60)));

        assertEquals(Optional.empty(), db.transactionReturning(tx -> TelegramUsers.idOf(tx, "ali_dev")));
        assertEquals(Optional.of(200L), db.transactionReturning(tx -> TelegramUsers.idOf(tx, "ali_new")));
    }

    @Test
    void aUsernameMovingToAnotherAccountClearsTheOldRow() {
        db.transaction(tx -> TelegramUsers.record(tx, 200, "ali_dev", T0));
        db.transaction(tx -> TelegramUsers.record(tx, 300, "ALI_DEV", T0.plusSeconds(60)));

        assertEquals(Optional.of(300L), db.transactionReturning(tx -> TelegramUsers.idOf(tx, "ali_dev")));
        assertEquals(1, db.transactionReturning(tx -> tx.one("SELECT count(*) AS n FROM telegram_user", row -> row.intValue("n")))
                .orElseThrow());
    }
}
