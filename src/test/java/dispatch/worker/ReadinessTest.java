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

    /** ADR 0026: a run is held by the agent its project runs on, not by Claude Code whatever the project. */
    @Test
    void aProjectIsHeldOnlyByItsOwnAgent() {
        Readiness noClaude = new Readiness(BROKEN, OK, Map.of("alm", OK), Map.of("codex", OK));
        Readiness noCodex = new Readiness(OK, OK, Map.of("alm", OK), Map.of("codex", new Readiness.Check(false, "cannot run codex")));

        assertTrue(noClaude.blocker("alm", "codex", RunKind.PLAN).isEmpty(), "Claude Code is not what a Codex project needs");
        assertEquals("claude", noClaude.blocker("alm", "claude-code", RunKind.PLAN).orElseThrow().code());
        assertEquals("codex", noCodex.blocker("alm", "codex", RunKind.EXECUTE).orElseThrow().code());
        assertEquals("cannot run codex", noCodex.blocker("alm", "codex", RunKind.PLAN).orElseThrow().detail());
        assertTrue(noCodex.blocker("alm", "gemini", RunKind.PLAN).isEmpty(),
                "an agent the computer never reported on holds nothing: the run fails saying what to add");
    }

    @Test
    void aWorkingComputerHoldsNothing() {
        Readiness ready = new Readiness(OK, OK, Map.of("alm", OK));

        assertTrue(ready.blocker("alm", "claude-code", RunKind.PLAN).isEmpty());
        assertTrue(ready.blocker("alm", "claude-code", RunKind.EXECUTE).isEmpty());
        assertTrue(ready.blocker("alm", "claude-code", RunKind.DELIVER).isEmpty());
    }

    @Test
    void claudeHoldsEveryKindOfRun() {
        Readiness broken = new Readiness(BROKEN, OK, Map.of("alm", OK));

        for (RunKind kind : RunKind.values()) {
            assertEquals("claude", broken.blocker("alm", "claude-code", kind).orElseThrow().code(), kind.name());
        }
        assertEquals("cannot run claude", broken.blocker("alm", "claude-code", RunKind.PLAN).orElseThrow().detail());
    }

    @Test
    void ghHoldsOnlyRunsThatDeliver() {
        Readiness noGh = new Readiness(OK, new Readiness.Check(false, "not logged in"), Map.of("alm", OK));

        assertTrue(noGh.blocker("alm", "claude-code", RunKind.PLAN).isEmpty(), "planning never touches GitHub");
        assertEquals("gh", noGh.blocker("alm", "claude-code", RunKind.EXECUTE).orElseThrow().code());
        assertEquals("gh", noGh.blocker("alm", "claude-code", RunKind.DELIVER).orElseThrow().code());
    }

    @Test
    void aMissingCloneHoldsOnlyThatProject() {
        Readiness partial = new Readiness(OK, OK, Map.of("alm", OK, "life", new Readiness.Check(false, "clone missing")));

        assertTrue(partial.blocker("alm", "claude-code", RunKind.PLAN).isEmpty());
        assertEquals("clone", partial.blocker("life", "claude-code", RunKind.PLAN).orElseThrow().code());
    }

    @Test
    void aProjectTheWorkerNeverReportedIsNotHeld() {
        // A worker paired before the project existed reports nothing for it; the run fails honestly instead of waiting.
        Readiness partial = new Readiness(OK, OK, Map.of("alm", OK));

        assertTrue(partial.blocker("crm", "claude-code", RunKind.PLAN).isEmpty());
    }

    @Test
    void claudeIsReportedBeforeAnyOtherBlocker() {
        Readiness everythingBroken = new Readiness(BROKEN, new Readiness.Check(false, "not logged in"),
                Map.of("alm", new Readiness.Check(false, "clone missing")));

        assertEquals("claude", everythingBroken.blocker("alm", "claude-code", RunKind.DELIVER).orElseThrow().code(),
                "one message, and the one that blocks the most");
    }

    @Test
    void aWorkerThatReportedNothingCountsAsReady() {
        // A team machine upgraded before its workers must not stall everyone.
        assertTrue(Readiness.READY.blocker("alm", "claude-code", RunKind.EXECUTE).isEmpty());
    }
}
