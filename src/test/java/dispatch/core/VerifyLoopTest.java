package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerifyLoopTest {

    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final Deque<Boolean> testResults = new ArrayDeque<>();
    private final List<String> calls = new ArrayList<>();
    private String reviewAnswer = "{\"verdict\":\"ok\",\"findings\":[]}";
    private AgentOutcome fixOutcome = AgentOutcome.SUCCEEDED;
    private AgentResult fixAnswer;

    private final TestRunner tests = (command, dir, log, timeout, stop) -> {
        calls.add("test");
        boolean pass = testResults.isEmpty() || testResults.pop();
        return new TestRunner.TestRun(pass ? 0 : 1, false, false, pass ? "ok" : "FooTest failed");
    };

    private final VerifyLoop.Agents agents = new VerifyLoop.Agents() {
        @Override
        public AgentResult fix(String prompt, BigDecimal budgetUsd, Duration timeout) {
            calls.add("fix");
            return fixAnswer != null ? fixAnswer : result(fixOutcome, null, new BigDecimal("0.10"));
        }

        @Override
        public AgentResult review(String prompt, BigDecimal budgetUsd, Duration timeout) {
            calls.add("review");
            return result(AgentOutcome.SUCCEEDED, reviewAnswer, new BigDecimal("0.05"));
        }

        @Override
        public String diff() {
            return "diff --git a/x b/x";
        }
    };

    private VerifyLoop.Outcome run(String testCommand, Duration timeLeft, BigDecimal budget, BigDecimal spent) {
        VerifyLoop.Setup setup = new VerifyLoop.Setup(testCommand, Path.of("/w/7"), Path.of("/s/runs/7/2"), "Review this:",
                NOW.plus(timeLeft), budget, spent);
        return new VerifyLoop(tests, clock).run(setup, agents, () -> false);
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

    @Test
    void blockingFindingsAreFixedAndRetested() {
        reviewAnswer = "{\"verdict\":\"changes\",\"findings\":[{\"severity\":\"blocking\",\"file\":\"A.java\",\"line\":1,\"text\":\"NPE\"},"
                + "{\"severity\":\"minor\",\"file\":\"B.java\",\"line\":2,\"text\":\"name\"}]}";

        VerifyLoop.Outcome outcome = run();

        assertEquals(List.of("test", "review", "fix", "test"), calls);
        // Only the minor finding is left: the blocking one was fixed and the tests pass again.
        assertEquals(Verification.ReviewState.FINDINGS, outcome.verification().review());
        assertEquals(List.of(new Review.Finding("minor", "B.java", 2, "name")), outcome.verification().findings());
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
