package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR 0018, amended: "Вэб UI нээх" gets a fresh link from the web UI running here, or from one started for it. */
class WebUiTest {

    @TempDir
    Path state;

    private final List<UiServer> servers = new ArrayList<>();
    private final List<List<String>> started = new ArrayList<>();

    @AfterEach
    void tearDown() {
        servers.forEach(UiServer::close);
    }

    @Test
    void aRunningWebUiHandsOutALinkAndNothingIsStarted() throws Exception {
        UiServer running = webUi("key-1");

        String link = webUi(List.of("start"), Duration.ofSeconds(2)).open();

        assertTrue(link.startsWith("http://127.0.0.1:" + running.port() + "/?t="), link);
        assertTrue(started.isEmpty());
    }

    @Test
    void withNoneRunningOneIsStartedAndItsLinkReturned() {
        WebUi opener = new WebUi(state, List.of("systemd-run", "dispatch", "ui"), "dispatch ui", command -> {
            started.add(command);
            try {
                webUi("key-2");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Duration.ofSeconds(5));

        String link = opener.open();

        assertEquals(List.of(List.of("systemd-run", "dispatch", "ui")), started);
        assertTrue(link.startsWith("http://127.0.0.1:"), link);
    }

    /** A crash leaves ui.json naming a port nothing listens on: it is started again rather than trusted. */
    @Test
    void aLeftoverFileIsNotTrustedAndOneThatNeverStartsSaysWhatToRun() throws Exception {
        int nothingThere;
        try (ServerSocket socket = new ServerSocket(0)) {
            nothingThere = socket.getLocalPort();
        }
        new UiFile(nothingThere, "stale", "old").write(state);

        ApiException e = assertThrows(ApiException.class, () -> webUi(List.of("start"), Duration.ofMillis(500)).open());

        assertEquals(1, started.size());
        assertEquals("webui_not_started", e.code());
        assertTrue(e.text().render(dispatch.Language.EN).contains("dispatch ui --instance team"), e.text().render(dispatch.Language.EN));
    }

    @Test
    void whereItCannotBeStartedTheAnswerSaysWhatToRun() {
        ApiException e = assertThrows(ApiException.class, () -> webUi(null, Duration.ofSeconds(1)).open());

        assertEquals("webui_by_hand", e.code());
        assertTrue(started.isEmpty());
    }

    @Test
    void itRunsOutsideTheBotsOwnUnitWithTheBotsPathAndStopsWhenUnused() {
        // Paths as this OS prints them: the command itself only ever runs on Linux.
        Path jar = Path.of("/opt/d.jar");
        Path config = Path.of("/c/dispatch.yaml");
        List<String> team = WebUi.systemdCommand("/usr/bin/java", jar, Path.of("/c/team.yaml"), "team", "/usr/bin");
        List<String> own = WebUi.systemdCommand("/usr/bin/java", jar, config, null, null);

        assertEquals(List.of("systemd-run", "--user", "--collect", "--quiet", "--unit=dispatch-ui-team", "--setenv=PATH=/usr/bin",
                "/usr/bin/java", "-jar", jar.toString(), "ui", "--no-browser", "--idle-minutes", "30", "--instance", "team"), team);
        assertEquals(List.of("--unit=dispatch-ui"), own.subList(4, 5));
        assertEquals(List.of("--config", config.toString()), own.subList(own.size() - 2, own.size()));
    }

    private WebUi webUi(List<String> command, Duration wait) {
        return new WebUi(state, command, "dispatch ui --instance team", started::add, wait);
    }

    /** A web UI as `dispatch ui` runs it: a keyed link server and the ui.json naming it. */
    private UiServer webUi(String key) throws IOException {
        UiServer server = UiServer.start(0, "/ui-test", port -> new UiAuth(port, key), Map.of(), Map.of());
        servers.add(server);
        new UiFile(server.port(), key, "test").write(state);
        return server;
    }
}
