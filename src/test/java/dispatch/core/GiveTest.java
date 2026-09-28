package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.config.Config;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A task given on the desktop (D-2b): the chat's rules, answered to the page, and a line in the private chat. */
class GiveTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TaskService tasks;
    private final Map<String, String> unavailable = new HashMap<>();

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        // Bold's group has alm; crm is another group's. No group has a chat, so Bold's task lives in his private chat.
        Groups groups = new Groups(new Config.Telegram(List.of(100L), List.of(
                new Config.Group("backend", null, List.of(new Config.Member(100, "Bold")), List.of("alm")),
                new Config.Group("sales", null, List.of(new Config.Member(300, "Saraa")), List.of("crm")))));
        tasks = new TaskService(groups, new Projects(List.of(project("alm"), project("crm")),
                project -> Optional.ofNullable(unavailable.get(project.name()))), new ActiveRuns(),
                new TestClock(Instant.parse("2026-09-28T10:00:00Z")), () -> { }, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aTaskGivenOnTheDesktopComesFromTheDeskAndSaysSoInThePrivateChat() {
        TaskService.Given given = give(BOLD, "alm", "  Fix the login timeout  ");

        assertEquals(CreateResult.CREATED, given.result());
        Map<String, String> task = SqlRows.single(dbFile, "SELECT id, origin_ref, description, priority FROM task");
        assertEquals(String.valueOf(given.taskId()), task.get("id"));
        assertTrue(task.get("origin_ref").startsWith(TaskService.DESK_ORIGIN), task.toString());
        assertEquals("Fix the login timeout", task.get("description"));
        assertEquals("URGENT", task.get("priority"));
        Map<String, String> line = SqlRows.single(dbFile,
                "SELECT chat_ref, reply_to_ref, payload FROM outbox WHERE kind = 'TASK_GIVEN_ON_DESK'");
        assertEquals("telegram:100", line.get("chat_ref"));
        assertNull(line.get("reply_to_ref"));
        assertTrue(line.get("payload").contains("\"title\":\"Fix the login timeout\""), line.toString());
        assertEquals("PLAN", SqlRows.single(dbFile, "SELECT kind FROM run").get("kind"));
    }

    @Test
    void whatTheDeskMayNotGiveIsRefusedWithoutAWordToTheChat() {
        unavailable.put("alm", "no clone at /srv/alm");

        assertEquals(CreateResult.EMPTY, give(BOLD, "alm", "   ").result());
        assertEquals(CreateResult.UNKNOWN_PROJECT, give(BOLD, "crm", "Fix it").result());
        assertEquals(CreateResult.UNKNOWN_PROJECT, give(BOLD, "nope", "Fix it").result());
        assertEquals(CreateResult.NOT_ALLOWED, give(new Requester("telegram:999", "Stranger"), "alm", "Fix it").result());
        TaskService.Given noClone = give(BOLD, "alm", "Fix it");
        assertEquals(CreateResult.PROJECT_UNAVAILABLE, noClone.result());
        assertEquals("no clone at /srv/alm", noClone.reason());

        assertEquals("0", SqlRows.single(dbFile, "SELECT count(*) AS n FROM task").get("n"));
        assertEquals("0", SqlRows.single(dbFile, "SELECT count(*) AS n FROM outbox").get("n"));
    }

    private TaskService.Given give(Requester who, String project, String text) {
        return db.transactionReturning(tx -> tasks.give(tx, who, project, text, Priority.URGENT));
    }

    private static Config.Project project(String name) {
        return new Config.Project(name, null, "https://github.com/acme/" + name + ".git", null, "main", "claude-code",
                null, null, List.of(), null, null, null);
    }
}
