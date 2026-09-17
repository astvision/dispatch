package dispatch.core;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Wakes a polling loop early; wake-ups that arrive while the loop is busy are not lost. */
public final class Signal {

    private final Semaphore permits = new Semaphore(0);

    public void wake() {
        permits.release();
    }

    /** Returns when woken or when {@code timeout} passes; pending wake-ups are consumed together. */
    public void await(Duration timeout) throws InterruptedException {
        if (permits.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            permits.drainPermits();
        }
    }
}
