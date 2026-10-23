package dispatch.data;

import dispatch.Log;
import dispatch.ProcessTrees;
import dispatch.config.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** An SSH port forward from a free local port to a project's Mongo, open for one report run (ADR 0043). */
public final class Tunnel implements AutoCloseable {

    private static final Duration POLL = Duration.ofMillis(200);
    private static final Duration CLOSE_GRACE = Duration.ofSeconds(5);
    private static final int STDERR_LINES = 5;

    private final int port;
    private final Runnable end;

    /**
     * A tunnel on {@code port} that {@code end} takes down; {@link #open} passes the end of its ssh, a test something it counts.
     */
    public Tunnel(int port, Runnable end) {
        this.port = port;
        this.end = end;
    }

    /**
     * Starts {@code ssh} (the client command and its fixed options) and returns once the local port accepts a connection.
     *
     * @throws TunnelException if ssh exits first, or the port does not accept within {@code wait}; ssh is then gone
     */
    public static Tunnel open(List<String> ssh, DataSource source, Duration wait) {
        int port = freePort();
        List<String> command = command(ssh, port, source);
        Process process;
        try {
            process = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            throw new TunnelException("cannot start " + command.getFirst() + " for " + source.ssh() + ": " + e.getMessage(), e);
        }
        List<String> stderrTail = new ArrayList<>();
        Thread reader = Thread.ofVirtual().name("tunnel-stderr-" + process.pid()).start(() -> readStderr(process, stderrTail));
        Log.info("tunnel.starting", "ssh", source.ssh(), "port", port, "pid", process.pid());

        Instant deadline = Instant.now().plus(wait);
        boolean opened = false;
        try {
            while (true) {
                if (!process.isAlive()) {
                    reader.join(1000);
                    throw new TunnelException("ssh to " + source.ssh() + " exited with " + process.exitValue() + ": " + lastLine(stderrTail));
                }
                if (accepts(port)) {
                    Log.info("tunnel.open", "ssh", source.ssh(), "port", port);
                    opened = true;
                    return new Tunnel(port, () -> ProcessTrees.terminate(process.toHandle(), CLOSE_GRACE));
                }
                if (Instant.now().isAfter(deadline)) {
                    throw new TunnelException("ssh to " + source.ssh() + " did not forward within " + wait.toSeconds() + " s: " + lastLine(stderrTail));
                }
                Thread.sleep(POLL.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TunnelException("interrupted while opening the tunnel to " + source.ssh(), e);
        } finally {
            // Any failure above leaves no ssh behind; on success the tunnel owns it.
            if (!opened) {
                ProcessTrees.terminate(process.toHandle(), CLOSE_GRACE);
            }
        }
    }

    static List<String> command(List<String> ssh, int port, DataSource source) {
        List<String> command = new ArrayList<>(ssh);
        command.addAll(List.of("-N", "-o", "BatchMode=yes", "-o", "ExitOnForwardFailure=yes", "-o", "ConnectTimeout=15",
                "-L", "127.0.0.1:" + port + ":" + source.mongo(), source.ssh()));
        return command;
    }

    public int port() {
        return port;
    }

    /** Ends ssh and anything it started, waiting up to 5 s; safe to call twice. */
    @Override
    public void close() {
        end.run();
        Log.info("tunnel.closed", "port", port);
    }

    private static int freePort() {
        // ponytail: a port taken between close and ssh binding makes ssh exit through ExitOnForwardFailure, so the run
        // fails clearly; retry when it's seen.
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new TunnelException("no free local port for the tunnel: " + e.getMessage(), e);
        }
    }

    private static boolean accepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void readStderr(Process process, List<String> tail) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (tail) {
                    tail.add(line);
                    if (tail.size() > STDERR_LINES) {
                        tail.removeFirst();
                    }
                }
            }
        } catch (IOException e) {
            // The stream closes when ssh is killed; the tail read so far is all there is.
        }
    }

    private static String lastLine(List<String> tail) {
        synchronized (tail) {
            return tail.isEmpty() ? "(no output)" : tail.getLast();
        }
    }
}
