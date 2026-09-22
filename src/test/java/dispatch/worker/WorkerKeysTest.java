package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Workers;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pairing and the keys it makes: a code works once, a key is only ever stored as its hash, and revoking is final. */
class WorkerKeysTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private final TestClock clock = new TestClock(Instant.parse("2026-09-23T10:00:00Z"));
    private WorkerKeys keys;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        keys = new WorkerKeys(db, clock);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aCodePairsOneComputerAndNeverASecond() {
        String code = keys.newCode(BOLD);

        WorkerKeys.NewKey paired = keys.pair(code, "ann-laptop").orElseThrow();

        assertEquals("telegram:100", paired.memberRef());
        assertTrue(keys.pair(code, "another-laptop").isEmpty(), "a pairing code is used once");
        assertEquals(1, keys.of("telegram:100").size());
    }

    @Test
    void aCodeIsEightReadableCharactersAndDiesAfterTenMinutes() {
        String code = keys.newCode(BOLD);

        assertEquals(8, code.length(), code);
        assertTrue(code.matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}"), "no I, O, 0 or 1 to mistype: " + code);
        clock.advance(WorkerKeys.CODE_LIFETIME.plusSeconds(1));
        assertTrue(keys.pair(code, "ann-laptop").isEmpty(), "ten minutes is all a code gets");
    }

    @Test
    void neitherTheKeyNorTheCodeIsEverStored() {
        String code = keys.newCode(BOLD);
        WorkerKeys.NewKey paired = keys.pair(code, "ann-laptop").orElseThrow();

        String workerRow = SqlRows.single(dbFile, "SELECT * FROM worker WHERE id = ?", paired.workerId()).toString();
        String codeRow = SqlRows.single(dbFile, "SELECT * FROM pairing_code LIMIT 1").toString();
        assertFalse(workerRow.contains(paired.key()), workerRow);
        assertFalse(codeRow.contains(code), codeRow);
        String hash = SqlRows.single(dbFile, "SELECT key_sha256 FROM worker WHERE id = ?", paired.workerId()).get("key_sha256");
        assertTrue(hash.matches("[0-9a-f]{64}"), "a SHA-256 in lowercase hex: " + hash);
    }

    @Test
    void aKeyNamesItsMemberAndAnythingElseIsRefused() {
        WorkerKeys.NewKey paired = keys.pair(keys.newCode(BOLD), "ann-laptop").orElseThrow();

        assertEquals("telegram:100", keys.authenticate(paired.key()).orElseThrow().memberRef());
        assertTrue(keys.authenticate(null).isEmpty());
        assertTrue(keys.authenticate("").isEmpty());
        assertTrue(keys.authenticate("not-a-key").isEmpty());
        // The compare is MessageDigest.isEqual over the hashes, so where a wrong key differs changes nothing.
        String firstCharDiffers = (paired.key().charAt(0) == 'A' ? 'B' : 'A') + paired.key().substring(1);
        String lastCharDiffers = paired.key().substring(0, paired.key().length() - 1)
                + (paired.key().endsWith("A") ? "B" : "A");
        assertTrue(keys.authenticate(firstCharDiffers).isEmpty(), firstCharDiffers);
        assertTrue(keys.authenticate(lastCharDiffers).isEmpty(), lastCharDiffers);
    }

    @Test
    void twoWorkersOfTheSameMemberGetDifferentKeys() {
        WorkerKeys.NewKey first = keys.pair(keys.newCode(BOLD), "laptop").orElseThrow();
        WorkerKeys.NewKey second = keys.pair(keys.newCode(BOLD), "laptop").orElseThrow();

        assertNotEquals(first.key(), second.key());
        assertEquals(List.of("laptop", "laptop"), keys.of("telegram:100").stream().map(Workers.Paired::name).toList());
    }

    @Test
    void onlyTheOwnerOrAnAdminRevokesAndARevokedKeyIsDead() {
        WorkerKeys.NewKey paired = keys.pair(keys.newCode(BOLD), "ann-laptop").orElseThrow();

        assertFalse(keys.revoke(paired.workerId(), ALI.ref(), false), "another member cannot revoke it");
        assertTrue(keys.authenticate(paired.key()).isPresent());
        assertTrue(keys.revoke(paired.workerId(), ALI.ref(), true), "an admin may revoke anyone's");
        assertTrue(keys.authenticate(paired.key()).isEmpty());
        assertFalse(keys.revoke(paired.workerId(), BOLD.ref(), false), "and it is revoked only once");
        assertTrue(keys.of(BOLD.ref()).isEmpty());
    }

    @Test
    void removingAMemberRevokesTheirWorkers() {
        WorkerKeys.NewKey bold = keys.pair(keys.newCode(BOLD), "bold-laptop").orElseThrow();
        WorkerKeys.NewKey ali = keys.pair(keys.newCode(ALI), "ali-laptop").orElseThrow();

        keys.revokeWorkersOfEveryoneExcept(Set.of(BOLD.ref()));

        assertTrue(keys.authenticate(bold.key()).isPresent());
        assertTrue(keys.authenticate(ali.key()).isEmpty(), "Ali is no longer a member");
    }

    @Test
    void noCurrentMembersRevokesEveryWorker() {
        WorkerKeys.NewKey bold = keys.pair(keys.newCode(BOLD), "bold-laptop").orElseThrow();

        keys.revokeWorkersOfEveryoneExcept(Set.of());

        assertTrue(keys.authenticate(bold.key()).isEmpty(), "nobody is a member, so nobody may have a worker");
    }

    @Test
    void aBlankNameIsRefusedRatherThanStored() {
        String code = keys.newCode(BOLD);

        assertThrows(IllegalArgumentException.class, () -> keys.pair(code, "   "));
    }

    @Test
    void aNameIsCleanedAndCappedAtFortyCharacters() {
        keys.pair(keys.newCode(BOLD), "ann\u0007's laptop" + "x".repeat(50));

        String storedName = keys.of(BOLD.ref()).getFirst().name();
        assertEquals(40, storedName.length(), storedName);
        assertFalse(storedName.contains("\u0007"), storedName);
    }

    @Test
    void usingAKeyRecordsWhenItWasLastSeen() {
        WorkerKeys.NewKey paired = keys.pair(keys.newCode(BOLD), "ann-laptop").orElseThrow();
        clock.advance(java.time.Duration.ofMinutes(5));

        keys.authenticate(paired.key());

        Optional<Workers.Paired> worker = keys.of(BOLD.ref()).stream().findFirst();
        assertEquals(Instant.parse("2026-09-23T10:05:00Z"), worker.orElseThrow().lastSeenAt());
    }

    @Test
    void anExpiredCodeIsSweptAway() {
        keys.newCode(BOLD);
        clock.advance(WorkerKeys.CODE_LIFETIME.plusSeconds(1));

        keys.newCode(ALI);

        Map<String, String> count = SqlRows.single(dbFile, "SELECT count(*) AS n FROM pairing_code");
        assertEquals("1", count.get("n"), "a new code sweeps the dead ones");
    }
}
