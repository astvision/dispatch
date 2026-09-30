package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.testing.TestClock;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerifyLoopTest {

    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");
    private final TestClock clock = new TestClock(NOW);
    private final Deque<Boolean> testResults = new ArrayDeque<>();
    private final List<String> calls = new ArrayList<>();
    private String reviewAnswer = "{\"verdict\":\"ok\",\"findings\":[]}";
    private AgentOutcome fixOutcome = AgentOutcome.SUCCEEDED;
    private AgentResult fixAnswer;
    private AgentOutcome reviewOutcome = AgentOutcome.SUCCEEDED;
    /** How long each fix takes on the loop's clock. */
    private Duration fixTakes = Duration.ZERO;
    /** What each fix costs. */
    private BigDecimal fixCost = new BigDecimal("0.10");
    /** Set by a fix when the member stops the run during it. */
    private boolean stopAfterFix;
    private boolean stopped;
    private final List<BigDecimal> fixBudgets = new ArrayList<>();

    private final TestRunner tests = (command, dir, log, timeout, stop, started) -> {
        calls.add("test");
        boolean pass = testResults.isEmpty() || testResults.pop();
        return new TestRunner.TestRun(pass ? 0 : 1, false, false, pass ? "ok" : "FooTest failed");
    };

    private final VerifyLoop.Agents agents = new VerifyLoop.Agents() {
        @Override
        public AgentResult fix(String prompt, BigDecimal budgetUsd, Duration timeout) {
            calls.add("fix");
            fixBudgets.add(budgetUsd);
            clock.advance(fixTakes);
            stopped = stopAfterFix;
            return fixAnswer != null ? fixAnswer : result(fixOutcome, null, fixCost);
        }

        @Override
        public AgentResult review(String prompt, BigDecimal budgetUsd, Duration timeout) {
            calls.add("review");
            return result(reviewOutcome, reviewAnswer, new BigDecimal("0.05"));
        }

        @Override
        public String diff() {
            return "diff --git a/x b/x";
        }

        @Override
        public void testStarted(ProcessHandle process) {
            // the loop's tests start no process
        }
    };

    private VerifyLoop.Outcome run(String testCommand, Duration timeLeft, BigDecimal budget, BigDecimal spent) {
        VerifyLoop.Setup setup = new VerifyLoop.Setup(testCommand, Path.of("/w/7"), Path.of("/s/runs/7/2"), "Review this:",
                NOW.plus(timeLeft), budget, spent);
        return new VerifyLoop(tests, clock).run(setup, agents, () -> stopped);
    }

    private VerifyLoop.Outcome run() {
        return run("./mvnw -q test", Duration.ofMinutes(60), new BigDecimal("10"), new BigDecimal("1"));
    }

    @Test
    void greenAtOnceThenACleanReview() {
        VerifyLoop.Outcome outcome = run();

        assertEquals(List.of("test", "review"), calls);
        assertEquals(Verification.Tests.PASSED, outcome.verification().tests());
        assertEquals(1, outcome.verification().testRuns());
        assertEquals(Verification.ReviewState.OK, outcome.verification().review());
        assertEquals(1, outcome.runs().size());
    }

    @Test
    void redThenGreenAfterOneFix() {
        testResults.addAll(List.of(false, true));

        VerifyLoop.Outcome outcome = run();

        assertEquals(List.of("test", "fix", "test", "review"), calls);
        assertEquals(Verification.Tests.PASSED, outcome.verification().tests());
        assertEquals(2, outcome.verification().testRuns());
    }

    @Test
    void stillRedAfterThreeFixesIsDeliveredAsFailing() {
        testResults.addAll(List.of(false, false, false, false));

        VerifyLoop.Outcome outcome = run();

        assertEquals(List.of("test", "fix", "test", "fix", "test", "fix", "test", "review"), calls);
        assertEquals(Verification.Tests.FAILING, outcome.verification().tests());
        assertEquals("FooTest failed", outcome.verification().testTail());
    }

    private static final String BLOCKING_AND_MINOR = "{\"verdict\":\"changes\",\"findings\":["
            + "{\"severity\":\"blocking\",\"file\":\"A.java\",\"line\":1,\"text\":\"NPE\"},"
            + "{\"severity\":\"minor\",\"file\":\"B.java\",\"line\":2,\"text\":\"name\"}]}";
    private static final List<Review.Finding> BOTH_FINDINGS = List.of(new Review.Finding("blocking", "A.java", 1, "NPE"),
            new Review.Finding("minor", "B.java", 2, "name"));

    @Test
    void blockingFindingsAreFixedAndRetested() {
        reviewAnswer = BLOCKING_AND_MINOR;

        VerifyLoop.Outcome outcome = run();

        assertEquals(List.of("test", "review", "fix", "test"), calls);
        // Nothing re-reviewed the fix, so the block says so and keeps every finding the reviewer made.
        assertEquals(Verification.ReviewState.FIXED_UNREVIEWED, outcome.verification().review());
        assertEquals(BOTH_FINDINGS, outcome.verification().findings());
        assertEquals(Verification.Tests.PASSED, outcome.verification().tests());
        assertEquals(2, outcome.verification().testRuns());
    }

    @Test
    void aReviewFixWithNoTimeLeftForTheRetestLeavesTheTestsUnverified() {
        reviewAnswer = BLOCKING_AND_MINOR;
        fixTakes = Duration.ofMinutes(50);

        VerifyLoop.Outcome outcome = run();

        assertEquals(List.of("test", "review", "fix"), calls);
        assertEquals(Verification.Tests.UNVERIFIED, outcome.verification().tests());
        assertTrue(outcome.verification().lastRunPassed());
        assertEquals(1, outcome.verification().testRuns());
        assertEquals(Verification.ReviewState.FIXED_UNREVIEWED, outcome.verification().review());
        assertEquals(BOTH_FINDINGS, outcome.verification().findings());
        assertEquals("time", outcome.verification().stoppedBy());
    }

    @Test
    void aTestFixWithNoTimeLeftForTheRetestLeavesTheTestsUnverified() {
        testResults.add(false);
        fixTakes = Duration.ofMinutes(50);

        VerifyLoop.Outcome outcome = run();

        assertEquals(List.of("test", "fix"), calls);
        assertEquals(Verification.Tests.UNVERIFIED, outcome.verification().tests());
        assertFalse(outcome.verification().lastRunPassed());
        assertEquals("time", outcome.verification().stoppedBy());
    }

    @Test
    void aFixThatSpendsTheBudgetStillGetsItsRetest() {
        // The budget floor gates agent calls only: the re-test runs, so its result is what the block reports.
        testResults.addAll(List.of(false, false));
        fixCost = new BigDecimal("8.70");

        VerifyLoop.Outcome outcome = run();

        assertEquals(List.of("test", "fix", "test"), calls);
        assertEquals(Verification.Tests.FAILING, outcome.verification().tests());
        assertEquals(2, outcome.verification().testRuns());
        assertEquals("budget", outcome.verification().stoppedBy());
    }

    @Test
    void aStopDuringAFixEndsTheLoopWithTheChangeUntested() {
        testResults.add(false);
        stopAfterFix = true;

        VerifyLoop.Outcome outcome = run();

        assertEquals(List.of("test", "fix"), calls);
        assertEquals(Verification.Tests.UNVERIFIED, outcome.verification().tests());
        assertEquals(Verification.ReviewState.NOT_RUN, outcome.verification().review());
    }

    @Test
    void noBudgetMeansNoBudgetStop() {
        testResults.addAll(List.of(false, true));

        VerifyLoop.Outcome outcome = run("./mvnw -q test", Duration.ofMinutes(60), null, BigDecimal.ONE);

        assertEquals(List.of("test", "fix", "test", "review"), calls);
        assertEquals(java.util.Collections.singletonList(null), fixBudgets);
        assertEquals(Verification.Tests.PASSED, outcome.verification().tests());
        assertEquals(Verification.ReviewState.OK, outcome.verification().review());
    }

    @Test
    void blockingFindingsWithNoFixesLeftAreAllListed() {
        testResults.addAll(List.of(false, false, false, false));
        reviewAnswer = BLOCKING_AND_MINOR;

        VerifyLoop.Outcome outcome = run();

        assertEquals(List.of("test", "fix", "test", "fix", "test", "fix", "test", "review"), calls);
        assertEquals(Verification.ReviewState.FINDINGS, outcome.verification().review());
        assertEquals(BOTH_FINDINGS, outcome.verification().findings());
        assertEquals(Verification.Tests.FAILING, outcome.verification().tests());
    }

    @Test
    void aReviewerThatFailsIsReportedWithItsError() {
        reviewOutcome = AgentOutcome.FAILED;

        VerifyLoop.Outcome outcome = run();

        assertEquals(Verification.ReviewState.FAILED, outcome.verification().review());
        assertEquals("agent broke", outcome.verification().reviewError());
    }

    @Test
    void aFailingReviewerNeverBlocksDelivery() {
        reviewAnswer = "Looks fine to me";

        VerifyLoop.Outcome outcome = run();

        assertEquals(Verification.ReviewState.FAILED, outcome.verification().review());
        assertTrue(outcome.verification().reviewError().contains("not JSON"), outcome.verification().reviewError());
    }

    @Test
    void noTestCommandGoesStraightToReview() {
        VerifyLoop.Outcome outcome = run(null, Duration.ofMinutes(60), new BigDecimal("10"), BigDecimal.ONE);

        assertEquals(List.of("review"), calls);
        assertEquals(Verification.Tests.NO_COMMAND, outcome.verification().tests());
    }

    @Test
    void tooLittleTimeLeftStopsBeforeTheTests() {
        VerifyLoop.Outcome outcome = run("./mvnw -q test", Duration.ofMinutes(11), new BigDecimal("10"), BigDecimal.ONE);

        assertEquals(List.of(), calls);
        assertEquals(Verification.Tests.NOT_RUN, outcome.verification().tests());
        assertEquals("time", outcome.verification().stoppedBy());
    }

    @Test
    void anAlmostSpentBudgetStopsBeforeTheNextAgentCall() {
        testResults.add(false);

        // 9.60 of 10 spent: 0.40 left is under 5% of 10 (0.50).
        VerifyLoop.Outcome outcome = run("./mvnw -q test", Duration.ofMinutes(60), new BigDecimal("10"), new BigDecimal("9.60"));

        assertEquals(List.of("test"), calls);
        assertEquals(Verification.Tests.FAILING, outcome.verification().tests());
        assertEquals("budget", outcome.verification().stoppedBy());
    }

    @Test
    void aFixThatFailsStopsTheLoopWithTheError() {
        testResults.add(false);
        fixOutcome = AgentOutcome.FAILED;

        VerifyLoop.Outcome outcome = run();

        assertEquals(List.of("test", "fix"), calls);
        assertTrue(outcome.verification().stoppedBy().startsWith("fix failed"), outcome.verification().stoppedBy());
    }

    @Test
    void aFixThatFailsWithoutAnErrorSaysHowItEnded() {
        testResults.add(false);
        fixAnswer = new AgentResult(AgentOutcome.FAILED, 1, "s", null, null, null, null, List.of(), null, null, null);

        VerifyLoop.Outcome outcome = run();

        assertEquals("fix failed: FAILED", outcome.verification().stoppedBy());
    }

    private static AgentResult result(AgentOutcome outcome, String structured, BigDecimal cost) {
        return new AgentResult(outcome, outcome == AgentOutcome.SUCCEEDED ? 0 : 1, "s", structured, "done", cost, 1, List.of(),
                outcome == AgentOutcome.SUCCEEDED ? null : "agent broke", null, null);
    }
}
