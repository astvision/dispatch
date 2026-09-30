package dispatch.core;

import java.util.List;

/**
 * What the verify loop found before delivery; written into the delivery commit (the PR body) and the Telegram result.
 *
 * @param testRuns      how many times the test command ran
 * @param lastRunPassed whether the last test run passed; what UNVERIFIED reports as the last result
 * @param testTail    the last run's output tail; null when the tests never ran
 * @param reviewError why the reviewer gave no usable answer; null otherwise
 * @param stoppedBy   "budget", "time", or "fix failed: <reason>" when the loop stopped early; null otherwise
 */
public record Verification(Tests tests, int testRuns, boolean lastRunPassed, String testTail, ReviewState review, List<Review.Finding> findings,
                           String reviewError, String stoppedBy) {

    /** UNVERIFIED: a fix changed the code after the last test run and nothing re-ran the tests. */
    public enum Tests { PASSED, FAILING, UNVERIFIED, NO_COMMAND, NOT_RUN }

    /** FIXED_UNREVIEWED: the blocking findings went to a fix that succeeded, and nothing reviewed that fix. */
    public enum ReviewState { OK, FINDINGS, FIXED_UNREVIEWED, FAILED, NOT_RUN }

    public Verification {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /** For a state whose last run is the one {@code tests} names (PASSED or not). */
    public Verification(Tests tests, int testRuns, String testTail, ReviewState review, List<Review.Finding> findings,
                        String reviewError, String stoppedBy) {
        this(tests, testRuns, tests == Tests.PASSED, testTail, review, findings, reviewError, stoppedBy);
    }

    /** The English block for the commit body; the tail is indented so Markdown shows it as code. */
    public String block() {
        StringBuilder out = new StringBuilder("Verification\n");
        out.append(switch (tests) {
            case PASSED -> "- Tests: pass (run " + testRuns + ")";
            case FAILING -> "- Tests: failing after " + runs() + "\n\n" + indented(testTail) + "\n";
            case UNVERIFIED -> "- Tests: not re-run after the last change (last run: " + (lastRunPassed ? "pass" : "failing")
                    + ", run " + testRuns + ")" + (lastRunPassed ? "" : "\n\n" + indented(testTail) + "\n");
            case NO_COMMAND -> "- Tests: no test command configured";
            case NOT_RUN -> "- Tests: not run";
        });
        out.append('\n').append(switch (review) {
            case OK -> "- Review: ok";
            case FINDINGS -> "- Review: " + findings.size() + (findings.size() == 1 ? " finding" : " findings") + " left" + listed();
            case FIXED_UNREVIEWED -> "- Review: " + blockingCount() + " blocking fixed, not re-reviewed" + listed();
            case FAILED -> "- Review: failed (" + reviewError + ")";
            case NOT_RUN -> "- Review: not run";
        });
        if (stoppedBy != null) {
            out.append("\n- Stopped early: ").append(stoppedBy);
        }
        return out.toString();
    }

    /** How many of the findings are blocking: what FIXED_UNREVIEWED says was fixed. */
    private long blockingCount() {
        return findings.stream().filter(finding -> finding.severity().equals("blocking")).count();
    }

    private String runs() {
        return testRuns + (testRuns == 1 ? " run" : " runs");
    }

    private String listed() {
        StringBuilder out = new StringBuilder();
        for (Review.Finding finding : findings) {
            // As code spans: an @name or #number in a reviewer's text must not ping anyone or link an issue from the PR.
            out.append("\n  - ").append(finding.severity()).append(' ')
                    .append(code(finding.file() + (finding.line() > 0 ? ":" + finding.line() : ""))).append(' ')
                    .append(code(finding.text()));
        }
        return out.toString();
    }

    private static String code(String text) {
        return "`" + text.replace('`', '\'') + "`";
    }

    private static String indented(String text) {
        return text == null ? "" : text.strip().lines().map(line -> "    " + line).reduce((a, b) -> a + "\n" + b).orElse("");
    }
}
