package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.domain.RunKind;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The gating table from the spec: what each blocker holds, and which one is reported when several apply. */
class ReadinessTest {

    private static final Readiness.Check OK = new Readiness.Check(true, "2.1.280");
    private static final Readiness.Check BROKEN = new Readiness.Check(false, "cannot run claude");

    @Test
    void aWorkingComputerHoldsNothing() {
        Readiness ready = new Readiness(OK, OK, Map.of("alm", OK));

        assertTrue(ready.blocker("alm", RunKind.PLAN).isEmpty());
        assertTrue(ready.blocker("alm", RunKind.EXECUTE).isEmpty());
        assertTrue(ready.blocker("alm", RunKind.DELIVER).isEmpty());
    }

    @Test
    void claudeHoldsEveryKindOfRun() {
        Readiness broken = new Readiness(BROKEN, OK, Map.of("alm", OK));

        for (RunKind kind : RunKind.values()) {
            assertEquals("claude", broken.blocker("alm", kind).orElseThrow().code(), kind.name());
        }
        assertEquals("cannot run claude", broken.blocker("alm", RunKind.PLAN).orElseThrow().detail());
    }

    @Test
    void ghHoldsOnlyRunsThatDeliver() {
        Readiness noGh = new Readiness(OK, new Readiness.Check(false, "not logged in"), Map.of("alm", OK));

        assertTrue(noGh.blocker("alm", RunKind.PLAN).isEmpty(), "planning never touches GitHub");
        assertEquals("gh", noGh.blocker("alm", RunKind.EXECUTE).orElseThrow().code());
        assertEquals("gh", noGh.blocker("alm", RunKind.DELIVER).orElseThrow().code());
    }

    @Test
    void aMissingCloneHoldsOnlyThatProject() {
        Readiness partial = new Readiness(OK, OK, Map.of("alm", OK, "life", new Readiness.Check(false, "clone missing")));

        assertTrue(partial.blocker("alm", RunKind.PLAN).isEmpty());
        assertEquals("clone", partial.blocker("life", RunKind.PLAN).orElseThrow().code());
    }

    @Test
    void aProjectTheWorkerNeverReportedIsNotHeld() {
        // A worker paired before the project existed reports nothing for it; the run fails honestly instead of waiting.
        Readiness partial = new Readiness(OK, OK, Map.of("alm", OK));

        assertTrue(partial.blocker("crm", RunKind.PLAN).isEmpty());
    }

    @Test
    void claudeIsReportedBeforeAnyOtherBlocker() {
        Readiness everythingBroken = new Readiness(BROKEN, new Readiness.Check(false, "not logged in"),
                Map.of("alm", new Readiness.Check(false, "clone missing")));

        assertEquals("claude", everythingBroken.blocker("alm", RunKind.DELIVER).orElseThrow().code(),
                "one message, and the one that blocks the most");
    }

    @Test
    void aWorkerThatReportedNothingCountsAsReady() {
        // A team machine upgraded before its workers must not stall everyone.
        assertTrue(Readiness.READY.blocker("alm", RunKind.EXECUTE).isEmpty());
    }
}
