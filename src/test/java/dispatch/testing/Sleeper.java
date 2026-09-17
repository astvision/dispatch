package dispatch.testing;

import java.io.IOException;

/** A process that only waits, for tests that need a live process on any OS; `sleep` does not exist on Windows. */
public final class Sleeper {

    private Sleeper() {
    }

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(Long.parseLong(args[0]) * 1000);
    }

    public static Process start(long seconds) throws IOException {
        String java = ProcessHandle.current().info().command().orElse("java");
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Sleeper.class.getName(), Long.toString(seconds))
                .start();
    }
}
