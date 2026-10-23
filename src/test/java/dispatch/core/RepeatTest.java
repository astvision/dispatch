package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.domain.FailureReason;
import dispatch.domain.Run;
import dispatch.domain.RunCause;
import dispatch.domain.RunKind;
import dispatch.domain.RunStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RepeatTest {

    @ParameterizedTest(name = "{0} {1} that failed for {2} repeats as {3} {4} with {5}")
    @CsvSource(nullValues = "-", value = {
            "PLAN,    TASK,       TIMEOUT,      PLAN,    RETRY,  step",
            "PLAN,    CORRECTION, USAGE_LIMIT,  PLAN,    RETRY,  step",
            "EXECUTE, APPROVAL,   AGENT,        EXECUTE, RETRY,  step",
            "EXECUTE, FOLLOW_UP,  USAGE_LIMIT,  EXECUTE, RETRY,  step",
            "EXECUTE, RETRY,      BUDGET,       EXECUTE, RETRY,  step",
            "EXECUTE, CI_FIX,     USAGE_LIMIT,  EXECUTE, CI_FIX, step",
            "EXECUTE, CI_FIX,     TIMEOUT,      EXECUTE, CI_FIX, step",
            "EXECUTE, APPROVAL,   DELIVERY,     DELIVER, RETRY,  output",
            "EXECUTE, CI_FIX,     DELIVERY,     DELIVER, RETRY,  output",
            "DELIVER, RETRY,      DELIVERY,     DELIVER, RETRY,  step",
            "EXECUTE, CI_FIX,     -,            EXECUTE, CI_FIX, step",
    })
    void aStepRepeatsAsTheRunThatFinishesIt(RunKind kind, RunCause cause, FailureReason reason, RunKind repeatKind,
                                            RunCause repeatCause, String instruction) {
        Run step = new Run(1, 2, kind, cause, RunStatus.FAILED, "step", "telegram:1", "Bold", null, null, null, null, null, null,
                null, reason);

        assertEquals(new Repeat(repeatKind, repeatCause, instruction), Repeat.of(step, () -> "output"));
    }
}
