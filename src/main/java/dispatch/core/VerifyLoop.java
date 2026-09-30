package dispatch.core;

import dispatch.Log;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Tests the building session's change, hands failures back, has a fresh reviewer check it, and says what it found
 * (spec: verify loop). It never delivers and never fails the run: whatever happens, the run delivers what the worktree holds.
 */
public final class VerifyLoop {

    public static final int FIX_ROUNDS = 3;
    public static final int REVIEW_ROUNDS = 1;
    public static final Duration TEST_TIMEOUT = Duration.ofMinutes(10);
    static final Duration TEST_STEP_NEEDS = Duration.ofMinutes(12);
    static final Duration AGENT_STEP_NEEDS = Duration.ofMinutes(5);
    public static final int DIFF_LIMIT = 60_000;
    static final BigDecimal BUDGET_FLOOR = new BigDecimal("0.05");

    public interface Agents {
        /** Resumes the building session; a result whose outcome is not SUCCEEDED ends the loop. */
        AgentResult fix(String prompt, BigDecimal budgetUsd, Duration timeout);

        /** Starts a fresh read-only reviewer. */
        AgentResult review(String prompt, BigDecimal budgetUsd, Duration timeout);

        /** The change since the run's start commit, as git shows it. */
        String diff();
    }

    /** @param budgetUsd null for no budget; @param spentUsd what the run has already cost */
    public record Setup(String testCommand, Path worktree, Path logBase, String reviewPrompt, Instant deadline,
                        BigDecimal budgetUsd, BigDecimal spentUsd) {
    }

    public record Outcome(Verification verification, List<AgentResult> runs) {
    }

    private final TestRunner tests;
    private final Clock clock;

    public VerifyLoop(TestRunner tests, Clock clock) {
        this.tests = tests;
        this.clock = clock;
    }

    public Outcome run(Setup setup, Agents agents, BooleanSupplier stopRequested) {
        return new Pass(setup, agents, stopRequested).run();
    }

    /** One loop's state: kept in an object so each step reads as the spec's list. */
    private final class Pass {
        private final Setup setup;
        private final Agents agents;
        private final BooleanSupplier stop;
        private final List<AgentResult> runs = new ArrayList<>();
        private BigDecimal spent;
        private int fixesLeft = FIX_ROUNDS;
        private int testRuns;
        private Verification.Tests testState;
        private String tail;
        private Verification.ReviewState reviewState = Verification.ReviewState.NOT_RUN;
        private List<Review.Finding> left = List.of();
        private String reviewError;
        private String stoppedBy;

        Pass(Setup setup, Agents agents, BooleanSupplier stop) {
            this.setup = setup;
            this.agents = agents;
            this.stop = stop;
            this.spent = setup.spentUsd() == null ? BigDecimal.ZERO : setup.spentUsd();
            this.testState = setup.testCommand() == null ? Verification.Tests.NO_COMMAND : Verification.Tests.NOT_RUN;
        }

        Outcome run() {
            testAndFix();
            if (stoppedBy == null && !stop.getAsBoolean() && setup.reviewPrompt() != null) {
                review();
            }
            Verification verification = new Verification(testState, testRuns, tail, reviewState, left, reviewError, stoppedBy);
            return new Outcome(verification, List.copyOf(runs));
        }

        private void testAndFix() {
            if (setup.testCommand() == null) {
                return;
            }
            while (true) {
                if (stop.getAsBoolean()) {
                    return;
                }
                if (timeLeft().compareTo(TEST_STEP_NEEDS) < 0) {
                    stoppedBy = "time";
                    Log.info("verify.step", "step", "test", "outcome", "stopped: time");
                    return;
                }
                Path log = Path.of(setup.logBase() + ".test-" + (testRuns + 1) + ".log");
                TestRunner.TestRun result = tests.run(setup.testCommand(), setup.worktree(), log, TEST_TIMEOUT, stop);
                testRuns++;
                tail = result.tail();
                if (result.stopped()) {
                    return;
                }
                testState = result.passed() ? Verification.Tests.PASSED : Verification.Tests.FAILING;
                Log.info("verify.step", "step", "test", "run", testRuns, "outcome", testState);
                if (result.passed() || fixesLeft == 0) {
                    return;
                }
                if (!fix(Prompts.testFailure(setup.testCommand(), tail))) {
                    return;
                }
            }
        }

        private void review() {
            if (!agentStepAllowed()) {
                return;
            }
            AgentResult result = agents.review(setup.reviewPrompt() + "\n" + capped(agents.diff()), budgetLeft(), timeLeft());
            record(result);
            if (result.outcome() != AgentOutcome.SUCCEEDED) {
                reviewState = Verification.ReviewState.FAILED;
                reviewError = result.error();
                Log.info("verify.step", "step", "review", "outcome", "failed", "error", result.error());
                return;
            }
            Review parsed;
            try {
                parsed = Review.parse(result.structuredOutput());
            } catch (IllegalArgumentException e) {
                reviewState = Verification.ReviewState.FAILED;
                reviewError = e.getMessage();
                Log.info("verify.step", "step", "review", "outcome", "unusable answer", "error", e.getMessage());
                return;
            }
            Log.info("verify.step", "step", "review", "outcome", parsed.verdict(), "blocking", parsed.blocking().size());
            if (parsed.blocking().isEmpty()) {
                keepMinor(parsed);
                return;
            }
            if (fixesLeft == 0 || !fix(Prompts.reviewFindings(parsed.blocking()))) {
                reviewState = Verification.ReviewState.FINDINGS;
                left = parsed.findings();
                return;
            }
            keepMinor(parsed);
            testAndFix();
        }

        private void keepMinor(Review parsed) {
            reviewState = parsed.minor().isEmpty() ? Verification.ReviewState.OK : Verification.ReviewState.FINDINGS;
            left = parsed.minor();
        }

        /** @return true when the fix ran and succeeded, so the loop may go on */
        private boolean fix(String prompt) {
            if (!agentStepAllowed()) {
                return false;
            }
            fixesLeft--;
            AgentResult result = agents.fix(prompt, budgetLeft(), timeLeft());
            record(result);
            Log.info("verify.step", "step", "fix", "outcome", result.outcome());
            if (result.outcome() != AgentOutcome.SUCCEEDED) {
                stoppedBy = "fix failed: " + result.error();
                return false;
            }
            return true;
        }

        private boolean agentStepAllowed() {
            if (stop.getAsBoolean()) {
                return false;
            }
            if (timeLeft().compareTo(AGENT_STEP_NEEDS) < 0) {
                stoppedBy = "time";
                return false;
            }
            if (setup.budgetUsd() != null
                    && setup.budgetUsd().subtract(spent).compareTo(setup.budgetUsd().multiply(BUDGET_FLOOR)) < 0) {
                stoppedBy = "budget";
                return false;
            }
            return true;
        }

        private void record(AgentResult result) {
            runs.add(result);
            if (result.costUsd() != null) {
                spent = spent.add(result.costUsd());
            }
        }

        private BigDecimal budgetLeft() {
            return setup.budgetUsd() == null ? null : setup.budgetUsd().subtract(spent);
        }

        private Duration timeLeft() {
            return Duration.between(clock.instant(), setup.deadline());
        }

        private String capped(String diff) {
            if (diff.length() <= DIFF_LIMIT) {
                return diff;
            }
            return diff.substring(0, DIFF_LIMIT) + "\n… (the diff is longer; the rest was cut at " + DIFF_LIMIT + " characters)";
        }
    }
}
