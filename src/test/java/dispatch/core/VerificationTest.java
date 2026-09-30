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
        assertTrue(block.contains("- Review: 1 finding left\n  - minor src/A.java:3 rename x"), block);
        assertTrue(block.contains("- Stopped early: budget"), block);
    }

    @Test
    void noTestCommandAndAFailedReviewer() {
        Verification v = new Verification(Verification.Tests.NO_COMMAND, 0, null, Verification.ReviewState.FAILED, List.of(),
                "the reviewer's answer is not JSON", null);

        assertTrue(v.block().contains("- Tests: no test command configured"));
        assertTrue(v.block().contains("- Review: failed (the reviewer's answer is not JSON)"));
    }
}
