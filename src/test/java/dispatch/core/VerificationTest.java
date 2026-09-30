package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VerificationTest {

    @Test
    void passingTestsAndACleanReview() {
        Verification v = new Verification(Verification.Tests.PASSED, 2, "ok", Verification.ReviewState.OK, List.of(), null, null);

        assertEquals("""
                Verification
                - Tests: pass (run 2)
                - Review: ok""", v.block());
    }

    @Test
    void failingTestsShowTheTailAndLeftoverFindings() {
        Verification v = new Verification(Verification.Tests.FAILING, 4, "BUILD FAILURE\nFooTest", Verification.ReviewState.FINDINGS,
                List.of(new Review.Finding("minor", "src/A.java", 3, "rename x")), null, "budget");

        String block = v.block();

        assertTrue(block.contains("- Tests: failing after 4 runs"), block);
        assertTrue(block.contains("    BUILD FAILURE\n    FooTest"), block);
        assertTrue(block.contains("- Review: 1 finding left\n  - minor `src/A.java:3` `rename x`"), block);
        assertTrue(block.contains("- Stopped early: budget"), block);
    }

    @Test
    void noTestCommandAndAFailedReviewer() {
        Verification v = new Verification(Verification.Tests.NO_COMMAND, 0, null, Verification.ReviewState.FAILED, List.of(),
                "the reviewer's answer is not JSON", null);

        assertTrue(v.block().contains("- Tests: no test command configured"));
        assertTrue(v.block().contains("- Review: failed (the reviewer's answer is not JSON)"));
    }

    @Test
    void oneFailingRunIsSingular() {
        Verification v = new Verification(Verification.Tests.FAILING, 1, "FooTest", Verification.ReviewState.NOT_RUN, List.of(), null, null);

        assertTrue(v.block().contains("- Tests: failing after 1 run\n"), v.block());
    }

    @Test
    void testsNotRerunAfterTheLastChangeSayWhatTheLastRunShowed() {
        Verification passed = new Verification(Verification.Tests.UNVERIFIED, 1, true, "ok", Verification.ReviewState.NOT_RUN,
                List.of(), null, "time");
        Verification failing = new Verification(Verification.Tests.UNVERIFIED, 2, false, "FooTest", Verification.ReviewState.NOT_RUN,
                List.of(), null, "time");

        assertTrue(passed.block().contains("- Tests: not re-run after the last change (last run: pass, run 1)\n- Review"),
                passed.block());
        assertTrue(failing.block().contains("- Tests: not re-run after the last change (last run: failing, run 2)\n\n    FooTest"),
                failing.block());
    }

    @Test
    void fixedBlockingFindingsThatNobodyReReviewedAreAllListed() {
        Verification v = new Verification(Verification.Tests.PASSED, 2, "ok", Verification.ReviewState.FIXED_UNREVIEWED,
                List.of(new Review.Finding("blocking", "A.java", 1, "NPE"), new Review.Finding("minor", "B.java", 0, "name")), null, null);

        assertTrue(v.block().endsWith("- Review: 1 blocking fixed, not re-reviewed\n  - blocking `A.java:1` `NPE`\n  - minor `B.java` `name`"),
                v.block());
    }

    @Test
    void findingsInThePullRequestNeitherPingNorCrossLink() {
        Verification v = new Verification(Verification.Tests.NOT_RUN, 0, null, Verification.ReviewState.FINDINGS,
                List.of(new Review.Finding("minor", "src/`x`.java", 2, "ask @alice about #12 and `y`")), null, null);

        assertTrue(v.block().endsWith("  - minor `src/'x'.java:2` `ask @alice about #12 and 'y'`"), v.block());
    }
}
