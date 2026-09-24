package dispatch.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.domain.RunKind;
import dispatch.worker.Readiness;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkersReadinessTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");
    private static final Instant SEEN_SINCE = NOW.minus(Workers.SEEN_WITHIN);

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
    void oneWorkingComputerIsEnoughToHoldNothing() {
        long brokenLaptop = workerId;
        long workingDesktop = db.transactionReturning(tx -> Workers.insert(tx, "telegram:100", "desktop", "sha2", NOW));
        live(brokenLaptop, CLAUDE_BROKEN);
        live(workingDesktop, new Readiness(new Readiness.Check(true, "2.1.280"), new Readiness.Check(true, null), Map.of()));

        assertTrue(blockerOf(null, "alm", RunKind.PLAN).isEmpty(), "the member is not reported as stuck");
    }

    @Test
    void whenEveryLiveComputerIsHeldItSaysWhy() {
        live(workerId, CLAUDE_BROKEN);

        Readiness.Blocker blocker = blockerOf(null, "alm", RunKind.PLAN).orElseThrow();

        assertEquals("claude", blocker.code());
        assertEquals("not installed", blocker.detail());
    }

    @Test
    void aMemberWithNoComputerIsNotHeld() {
        assertTrue(db.transactionReturning(tx -> Workers.blockerOf(tx, "telegram:999", null, SEEN_SINCE, "alm", RunKind.PLAN))
                .isEmpty());
    }

    @Test
    void aStaleReportIsNotABlocker() {
        db.transaction(tx -> Workers.touch(tx, workerId, SEEN_SINCE.minusSeconds(1)));
        db.transaction(tx -> Workers.saveReadiness(tx, workerId, CLAUDE_BROKEN, NOW));

        assertTrue(blockerOf(null, "alm", RunKind.PLAN).isEmpty(),
                "a stale report is not 'Claude Code is broken'; that's the offline path's job");
    }

    @Test
    void aBlockerOnlyCountsForTheRunItHolds() {
        live(workerId, new Readiness(new Readiness.Check(true, "2.1.280"), new Readiness.Check(false, "not logged in"),
                Map.of("life", new Readiness.Check(false, "clone missing"))));

        assertTrue(blockerOf(null, "alm", RunKind.PLAN).isEmpty(), "gh does not hold a plan, nor life's clone an alm run");
        assertEquals("gh", blockerOf(null, "alm", RunKind.EXECUTE).orElseThrow().code());
        assertEquals("clone", blockerOf(null, "life", RunKind.PLAN).orElseThrow().code());
    }

    @Test
    void aPinnedTaskOnlyAsksItsOwnComputer() {
        long brokenLaptop = workerId;
        long workingDesktop = db.transactionReturning(tx -> Workers.insert(tx, "telegram:100", "desktop", "sha2", NOW));
        live(brokenLaptop, CLAUDE_BROKEN);
        live(workingDesktop, Readiness.READY);

        assertEquals("claude", blockerOf(brokenLaptop, "alm", RunKind.EXECUTE).orElseThrow().code(),
                "its worktree is on the laptop, so the working desktop cannot take it");
    }

    private static final Readiness CLAUDE_BROKEN =
            new Readiness(new Readiness.Check(false, "not installed"), new Readiness.Check(true, null), Map.of());

    private void live(long worker, Readiness readiness) {
        db.transaction(tx -> Workers.touch(tx, worker, NOW));
        db.transaction(tx -> Workers.saveReadiness(tx, worker, readiness, NOW));
    }

    private Optional<Readiness.Blocker> blockerOf(Long pinnedWorker, String project, RunKind kind) {
        return db.transactionReturning(tx -> Workers.blockerOf(tx, "telegram:100", pinnedWorker, SEEN_SINCE, project, kind));
    }
}
