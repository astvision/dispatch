package dispatch.ui;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.Language;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.Groups;
import dispatch.core.Projects;
import dispatch.core.TaskService;
import dispatch.store.Database;
import dispatch.testing.TestClock;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** dispatch ui's side of the desk port: task calls go on to a real running desk (D-2). */
class DeskProxyTest {

    @TempDir
    Path dir;

    private Path state;
    private Database db;
    private TestClock clock;
    private Groups groups;
    private TaskService tasks;
    private DeskServer desk;
    private DeskProxy proxy;

    @BeforeEach
    void setUp() throws IOException {
        state = dir.resolve("state");
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-28T10:00:00Z"));
        Config.Project alm = new Config.Project("alm", null, "https://github.com/acme/alm.git", null, "main", "claude-code",
                null, null, List.of(), null, null, null);
        groups = new Groups(new Config.Telegram(List.of(100L), List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm")))));
        tasks = new TaskService(groups, new Projects(List.of(alm), project -> Optional.empty()), new ActiveRuns(), clock,
                () -> { }, () -> { });
        desk = DeskServer.start(state, db, tasks, groups, clock, "0.3.0", "acme");
        proxy = new DeskProxy(() -> state);
    }

    @AfterEach
    void tearDown() {
        desk.close();
        db.close();
    }

    @Test
    void aCallGoesOnToTheBotInThePagesLanguage() {
        UiServer.Forwarded answer = proxy.forward("/api/tasks/detail", "POST", "{\"taskId\":99}".getBytes(UTF_8), Language.MN, null);

        assertEquals(404, answer.status());
        assertEquals("#99 даалгавар энд алга", Json.read(new String(answer.json(), UTF_8)).path("message").asText());
    }

    @Test
    void aStaleFileIsABotThatIsNotRunning() throws IOException {
        int closedPort = desk.port();
        desk.close();
        new DeskFile(closedPort, DeskFile.newToken(), "0.3.0", "acme").write(state);

        UiServer.Forwarded answer = proxy.forward("/api/live", "GET", new byte[0], Language.MN, null);

        assertEquals(503, answer.status());
        JsonNode body = Json.read(new String(answer.json(), UTF_8));
        assertEquals("bot_not_running", body.path("error").asText());
        assertEquals("Бот ажиллахгүй байна", body.path("message").asText());
    }

    @Test
    void aRestartedBotIsFoundAtItsNewPort() throws IOException {
        desk.close();
        desk = DeskServer.start(state, db, tasks, groups, clock, "0.3.0", "acme");

        assertEquals(200, proxy.forward("/api/live", "GET", new byte[0], Language.EN, null).status());
    }
}
