package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.AgentActivity;
import dispatch.agent.AgentResult;
import dispatch.agent.RunHandle;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ActiveRunsTest {

    private final ActiveRuns activeRuns = new ActiveRuns();

    @Test
    void runRegisteredAfterShutdownBeganIsAlreadyInterrupted() {
        activeRuns.stopAll(ActiveRuns.StopReason.INTERRUPTED);

        ActiveRuns.ActiveRun lateRun = activeRuns.register(1, 1);

        assertEquals(ActiveRuns.StopReason.INTERRUPTED, lateRun.stopReason());
    }

    @Test
    void handleAttachedToAStoppedRunIsCancelledImmediately() {
        ActiveRuns.ActiveRun run = activeRuns.register(1, 1);
        run.stop(ActiveRuns.StopReason.CANCELLED);
        CountingHandle handle = new CountingHandle();

        run.attach(handle);

        assertEquals(1, handle.cancels.get());
    }

    @Test
    void firstStopReasonWins() {
        ActiveRuns.ActiveRun run = activeRuns.register(1, 1);
        CountingHandle handle = new CountingHandle();
        run.attach(handle);

        assertTrue(run.stop(ActiveRuns.StopReason.CANCELLED));
        run.stop(ActiveRuns.StopReason.TIMEOUT);

        assertEquals(ActiveRuns.StopReason.CANCELLED, run.stopReason());
        assertEquals(1, handle.cancels.get());
    }

    @Test
    void unregisteredRunIsNoLongerStopped() {
        ActiveRuns.ActiveRun run = activeRuns.register(1, 1);
        activeRuns.unregister(run);

        activeRuns.stop(1, ActiveRuns.StopReason.CANCELLED);

        assertNull(run.stopReason());
    }

    @Test
    void activityComesFromTheRunsAgentOnceAttached() {
        ActiveRuns.ActiveRun run = activeRuns.register(7, 2);
        assertEquals(Optional.empty(), activeRuns.activity(7), "no agent started yet");

        run.attach(new CountingHandle());

        assertEquals(Optional.of(new AgentActivity(3, "Bash: make help")), activeRuns.activity(7));
        assertEquals(Optional.empty(), activeRuns.activity(8));
    }

    private static final class CountingHandle implements RunHandle {

        final AtomicInteger cancels = new AtomicInteger();

        @Override
        public ProcessHandle process() {
            return ProcessHandle.current();
        }

        @Override
        public AgentResult await() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public void cancel() {
            cancels.incrementAndGet();
        }

        @Override
        public AgentActivity activity() {
            return new AgentActivity(3, "Bash: make help");
        }
    }
}
