package dispatch.domain;

import java.time.Instant;

/**
 * One step of a run as the run monitor shows it: the implementation, a test run, a fix, the review or the delivery of an
 * execution, or a planning run's one agent call.
 *
 * @param n        the step's 1-based place in its run
 * @param round    which test run, fix or review this is, from 1; 1 for the others
 * @param endedAt  null while the step runs
 * @param outcome  null while the step runs
 * @param detail   JSON: {"tail": …} for a test, {"findings": [...]} for a review, {"error": …} for a failure; null for none
 */
public record RunStep(int n, Kind kind, int round, Instant startedAt, Instant endedAt, Outcome outcome, String detail) {

    public enum Kind { PLAN, IMPLEMENT, TEST, FIX, REVIEW, DELIVER }

    /**
     * DONE: an agent call or the delivery finished. PASSED and FAILED: a test run, or FAILED for a call that did not
     * succeed. OK and FINDINGS: a review. SKIPPED: the requester skipped it. STOPPED: a cancel or shutdown ended it.
     */
    public enum Outcome { DONE, PASSED, FAILED, OK, FINDINGS, SKIPPED, STOPPED }
}
