package dispatch.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.worker.Readiness;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkersReadinessTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    @TempDir
    Path dir;
    private Database db;
    private long workerId;

    @BeforeEach
    void setUp() {
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
        workerId = db.transactionReturning(tx -> Workers.insert(tx, "telegram:100", "laptop", "sha", NOW));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aWorkerThatReportedNothingIsReady() {
        Readiness stored = db.transactionReturning(tx -> Workers.readiness(tx, workerId));

        assertTrue(stored.claude().ok());
        assertTrue(stored.projects().isEmpty());
    }

    @Test
    void aReportComesBackAsItWentIn() {
        Readiness sent = new Readiness(new Readiness.Check(true, "2.1.280"),
                new Readiness.Check(false, "not logged in"),
                Map.of("alm", new Readiness.Check(true, null), "life", new Readiness.Check(false, "clone missing")));
        db.transaction(tx -> Workers.saveReadiness(tx, workerId, sent, NOW));

        Readiness stored = db.transactionReturning(tx -> Workers.readiness(tx, workerId));

        assertTrue(stored.claude().ok());
        assertEquals("2.1.280", stored.claude().detail());
        assertFalse(stored.gh().ok());
        assertEquals("not logged in", stored.gh().detail());
        assertEquals(2, stored.projects().size());
        assertFalse(stored.projects().get("life").ok());
    }

    @Test
    void aLaterReportReplacesTheOneBeforeIt() {
        db.transaction(tx -> Workers.saveReadiness(tx, workerId,
                new Readiness(new Readiness.Check(false, "gone"), new Readiness.Check(true, null),
                        Map.of("alm", new Readiness.Check(false, "clone missing"))), NOW));
        db.transaction(tx -> Workers.saveReadiness(tx, workerId,
                new Readiness(new Readiness.Check(true, "2.1.280"), new Readiness.Check(true, null),
                        Map.of("alm", new Readiness.Check(true, null))), NOW.plusSeconds(60)));

        Readiness stored = db.transactionReturning(tx -> Workers.readiness(tx, workerId));

        assertTrue(stored.claude().ok(), "a computer that was fixed is not still broken");
        assertTrue(stored.projects().get("alm").ok());
        assertEquals(1, stored.projects().size(), "stale project rows are replaced, not added to");
    }

    @Test
    void readinessOfMemberPrefersTheWorkerWithNoBlocker() {
        long brokenLaptop = workerId;
        long workingDesktop = db.transactionReturning(tx -> Workers.insert(tx, "telegram:100", "desktop", "sha2", NOW));
        db.transaction(tx -> Workers.saveReadiness(tx, brokenLaptop,
                new Readiness(new Readiness.Check(false, "not installed"), new Readiness.Check(true, null), Map.of()), NOW));
        db.transaction(tx -> Workers.saveReadiness(tx, workingDesktop,
                new Readiness(new Readiness.Check(true, "2.1.280"), new Readiness.Check(true, null), Map.of()), NOW));

        Readiness member = db.transactionReturning(tx -> Workers.readinessOfMember(tx, "telegram:100"));

        assertTrue(member.claude().ok(), "one working machine is enough; the member is not reported as stuck");
    }

    @Test
    void readinessOfMemberFallsBackToAnyReportWhenEveryWorkerIsBroken() {
        db.transaction(tx -> Workers.saveReadiness(tx, workerId,
                new Readiness(new Readiness.Check(false, "not installed"), new Readiness.Check(true, null), Map.of()), NOW));

        Readiness member = db.transactionReturning(tx -> Workers.readinessOfMember(tx, "telegram:100"));

        assertFalse(member.claude().ok());
    }

    @Test
    void readinessOfMemberWithNoWorkerIsReady() {
        Readiness member = db.transactionReturning(tx -> Workers.readinessOfMember(tx, "telegram:999"));

        assertTrue(member.claude().ok());
        assertTrue(member.gh().ok());
    }
}
