package dispatch.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.config.DataSource;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.LINUX, OS.MAC})
class TunnelTest {

    private static final DataSource SOURCE = new DataSource("webapp-prod", "db1:27017", "app", "mongodb://u:" + "p@db1:27017/app");

    @TempDir
    Path dir;

    /** A fake ssh that serves the forwarded local port with python's http.server, as a real tunnel would accept. */
    private Path fakeSsh(String body) throws IOException {
        Path script = dir.resolve("ssh-" + System.nanoTime());
        Files.writeString(script, "#!/bin/bash\n" + body + "\n");
        script.toFile().setExecutable(true);
        return script;
    }

    private static final String FORWARD = """
            for a in "$@"; do case $a in 127.0.0.1:*) spec=${a#127.0.0.1:};; esac; done
            port=${spec%%:*}
            exec python3 -m http.server "$port" --bind 127.0.0.1
            """;

    private static boolean accepts(int port) {
        try (Socket ignored = new Socket("127.0.0.1", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void theCommandIsBatchModeWithExitOnForwardFailure() {
        assertEquals(List.of("ssh", "-N", "-o", "BatchMode=yes", "-o", "ExitOnForwardFailure=yes", "-o", "ConnectTimeout=15",
                "-L", "127.0.0.1:40001:db1:27017", "webapp-prod"), Tunnel.command(List.of("ssh"), 40001, SOURCE));
    }

    @Test
    void aTunnelOpensOnceItsPortAccepts() throws Exception {
        Path ssh = fakeSsh(FORWARD);
        Tunnel tunnel = Tunnel.open(List.of(ssh.toString()), SOURCE, Duration.ofSeconds(10));
        int port = tunnel.port();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            assertTrue(socket.isConnected());
        }
        tunnel.close();
        assertTrue(!accepts(port), "port still accepts after close");
    }

    @Test
    void aTunnelWhoseSshExitsFailsWithItsError() throws Exception {
        Path ssh = fakeSsh("echo 'Permission denied (publickey).' >&2\nexit 255");
        TunnelException e = assertThrows(TunnelException.class,
                () -> Tunnel.open(List.of(ssh.toString()), SOURCE, Duration.ofSeconds(10)));
        assertTrue(e.getMessage().contains("webapp-prod"), e.getMessage());
        assertTrue(e.getMessage().contains("Permission denied"), e.getMessage());
    }

    @Test
    void aTunnelWhoseSshNeverForwardsFailsAfterTheWaitAndIsKilled() throws Exception {
        Path pidFile = dir.resolve("pid");
        Path ssh = fakeSsh("echo $$ > " + pidFile + "\nsleep 60\n");
        assertThrows(TunnelException.class, () -> Tunnel.open(List.of(ssh.toString()), SOURCE, Duration.ofSeconds(1)));
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false, "script still alive");
        Set<ProcessHandle> sleepers = Set.copyOf(ProcessHandle.current().descendants()
                .filter(p -> p.info().commandLine().orElse("").contains("sleep 60")).toList());
        assertTrue(sleepers.isEmpty(), "sleep still running");
    }

    @Test
    void twoTunnelsGetTwoPorts() throws Exception {
        Path ssh = fakeSsh(FORWARD);
        try (Tunnel first = Tunnel.open(List.of(ssh.toString()), SOURCE, Duration.ofSeconds(10));
             Tunnel second = Tunnel.open(List.of(ssh.toString()), SOURCE, Duration.ofSeconds(10))) {
            assertNotEquals(first.port(), second.port());
            first.close();
            assertTrue(accepts(second.port()));
        }
    }
}
