package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.Groups;
import dispatch.core.Projects;
import dispatch.core.TaskService;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import dispatch.ui.UiServer.Caller;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a member and an admin see and may do on the Mini App's task pages (spec: Task pages, ADR 0020). */
class TasksApiTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");
    private static final Caller BOLD_CALLER = new Caller("telegram:100", "Bold", true);
    private static final Caller ALI_CALLER = new Caller("telegram:200", "Ali", false);
    private static final Caller STRANGER = new Caller("telegram:999", "Eve", false);

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TestClock clock;
    private TaskService tasks;
    private TasksApi api;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-23T10:00:00Z"));
        Config.Project alm = new Config.Project("alm", null, "https://github.com/acme/alm.git", null, "main", "claude-code",
                null, null, List.of(), null, null, null);
        // Bold is the admin. TaskService decides what someone may do from this config, never from the Caller it is
        // handed, so the admin has to be an admin here for the cancel rules to hold.
        Groups groups = new Groups(new Config.Telegram(List.of(100L), List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm")))));
        tasks = new TaskService(groups, new Projects(List.of(alm), project -> Optional.empty()), new ActiveRuns(), clock,
                () -> { }, () -> { });
        api = new TasksApi(db, tasks, groups);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void myTasksHoldsOnlyMyOwnTasks() {
        long mine = create(BOLD, "Fix the login timeout");
        long theirs = create(ALI, "Add the export button");

        JsonNode listed = api.list(ALI_CALLER, Json.object());

        assertEquals(List.of(theirs), taskIds(listed), "a teammate's task is not on my page at all, not even as a headline");
        assertTrue(mine > 0);
    }

    @Test
    void anAdminSeesEveryTaskOfTheirGroupsWithSomeoneElsesAsAHeadline() {
        long boldsOwn = create(BOLD, "Fix the login timeout");
        long alis = create(ALI, "Add the export button");

        JsonNode listed = api.list(BOLD_CALLER, Json.object().put("scope", "group"));

        assertEquals(List.of(boldsOwn, alis).stream().sorted().toList(), taskIds(listed).stream().sorted().toList(),
                "both tasks of the group");
        assertEquals("Ali", item(listed, alis).path("requester").asText());
        assertFalse(item(listed, alis).path("costUsd").isNumber(), "someone else's cost stays hidden (ADR 0020)");
    }

    @Test
    void aMemberWhoIsNoAdminMayNotAskForTheGroupsTasks() {
        create(ALI, "Add the export button");

        ApiException refused = assertThrows(ApiException.class, () -> api.list(ALI_CALLER, Json.object().put("scope", "group")));

        assertEquals(403, refused.status());
        assertEquals("not_admin", refused.code());
    }

    @Test
    void someoneElsesTimelineStopsAtTheHeadline() {
        long alis = create(ALI, "Add the export button");

        JsonNode own = api.timeline(ALI_CALLER, Json.object().put("taskId", alis));
        JsonNode asAdmin = api.timeline(BOLD_CALLER, Json.object().put("taskId", alis));

        assertFalse(own.path("headline").asBoolean(false), "my own task shows its runs");
        assertTrue(own.has("runs"));
        assertTrue(asAdmin.path("headline").asBoolean(false), "someone else's stops at the headline (ADR 0020)");
        assertFalse(asAdmin.has("runs"));
        assertTrue(asAdmin.path("costUsd").isNull());
    }

    @Test
    void aTaskInNoGroupOfTheCallerIsNotFoundRatherThanRefused() {
        long alis = create(ALI, "Add the export button");

        ApiException missing = assertThrows(ApiException.class,
                () -> api.timeline(STRANGER, Json.object().put("taskId", alis)));

        assertEquals(404, missing.status(), "its existence must not leak");
    }

    @Test
    void theRequesterCancelsTheirOwnTaskAndAnAdminCancelsAnybodys() {
        long alis = create(ALI, "Add the export button");
        long boldsOwn = create(BOLD, "Fix the login timeout");

        assertEquals("CANCELLED", api.cancel(ALI_CALLER, Json.object().put("taskId", alis)).path("result").asText());
        assertEquals("CANCELLED", api.cancel(BOLD_CALLER, Json.object().put("taskId", boldsOwn)).path("result").asText());
    }

    /**
     * Retrying for real needs a failed run, which {@code TaskLifecycleTest} already covers end to end. What is new
     * here is the boundary: an admin may cancel anybody's task but never retry it.
     */
    @Test
    void anAdminMayCancelSomeoneElsesTaskButNeverRetryIt() {
        long alis = create(ALI, "Add the export button");

        ApiException refused = assertThrows(ApiException.class,
                () -> api.retry(BOLD_CALLER, Json.object().put("taskId", alis)));

        assertEquals(403, refused.status());
        assertEquals("cannot_retry", refused.code());
        assertTrue(refused.getMessage().contains("only the member who gave it"), refused.getMessage());
        assertEquals("CANCELLED", api.cancel(BOLD_CALLER, Json.object().put("taskId", alis)).path("result").asText());
    }

    private long create(Requester who, String title) {
        String origin = who.ref() + "/" + Math.abs(title.hashCode());
        db.transaction(tx -> tasks.create(tx, who, "alm", title, Priority.NORMAL, origin));
        return Long.parseLong(SqlRows.single(dbFile, "SELECT id FROM task WHERE origin_ref = ?", origin).get("id"));
    }

    private static List<Long> taskIds(JsonNode listed) {
        return StreamSupport.stream(listed.path("tasks").spliterator(), false).map(task -> task.path("taskId").asLong()).toList();
    }

    private static JsonNode item(JsonNode listed, long taskId) {
        return StreamSupport.stream(listed.path("tasks").spliterator(), false)
                .filter(task -> task.path("taskId").asLong() == taskId).findFirst().orElseThrow();
    }
}
