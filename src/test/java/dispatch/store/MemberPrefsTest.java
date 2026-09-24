package dispatch.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.domain.GroupAck;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A member's own choice of how a group hears about their task (G-1e). */
class MemberPrefsTest {

    @TempDir
    Path dir;

    private Database db;

    @BeforeEach
    void setUp() {
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void defaultsToReactionUntilSet() {
        assertEquals(GroupAck.REACTION, db.transactionReturning(tx -> MemberPrefs.groupAck(tx, 100)));

        db.transaction(tx -> MemberPrefs.setGroupAck(tx, 100, GroupAck.SILENT, Instant.parse("2026-09-17T10:00:00Z")));

        assertEquals(GroupAck.SILENT, db.transactionReturning(tx -> MemberPrefs.groupAck(tx, 100)));
        assertEquals(GroupAck.REACTION, db.transactionReturning(tx -> MemberPrefs.groupAck(tx, 200)), "another member's own default");
    }

    @Test
    void settingItAgainReplacesTheOldChoice() {
        db.transaction(tx -> MemberPrefs.setGroupAck(tx, 100, GroupAck.REACTION_AND_LINE, Instant.parse("2026-09-17T10:00:00Z")));

        db.transaction(tx -> MemberPrefs.setGroupAck(tx, 100, GroupAck.SILENT, Instant.parse("2026-09-17T10:05:00Z")));

        assertEquals(GroupAck.SILENT, db.transactionReturning(tx -> MemberPrefs.groupAck(tx, 100)));
    }
}
