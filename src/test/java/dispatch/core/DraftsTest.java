package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.config.Config;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Giving a task privately (ADR 0012): a message becomes a draft until its project and priority are chosen. */
class DraftsTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester SARA = new Requester("telegram:300", "Sara");
    private static final String BACKEND = "telegram:-100";

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TestClock clock;
    private final AtomicInteger schedulerWakes = new AtomicInteger();
    private Set<String> unavailable = Set.of();
    private TaskService tasks;
    private Projects projects;
    private Groups groups;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
        Config.Project alm = project("autoland-management", "alm");
        Config.Project crm = project("crm", null);
        Config.Project life = project("life", null);
        projects = new Projects(List.of(alm, crm, life),
                project -> unavailable.contains(project.name()) ? Optional.of("no clone") : Optional.empty());
        groups = new Groups(List.of(
                new Config.Group("backend", -100L, List.of(new Config.Member(100, "Bold")), List.of("autoland-management", "crm")),
                new Config.Group("mobile", -300L, List.of(new Config.Member(100, "Bold"), new Config.Member(300, "Sara")),
                        List.of("life"))));
        tasks = new TaskService(groups, projects, new ActiveRuns(), clock, schedulerWakes::incrementAndGet, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void privateMessageBecomesADraftThatAsksForProjectAndPriority() {
        DraftResult result = db.transactionReturning(tx -> tasks.draft(tx, BOLD, null, "Fix login timeout\nLogs show 30s", "telegram:100/5"));

        assertEquals(DraftResult.DRAFTED, result);
        Map<String, String> draft = row("SELECT * FROM draft");
        assertEquals("OPEN", draft.get("status"));
        assertEquals("Fix login timeout\nLogs show 30s", draft.get("description"));
        assertNull(draft.get("project"));
        Map<String, String> prompt = row("SELECT * FROM outbox WHERE kind = 'DRAFT_PROMPT'");
        assertEquals("telegram:100", prompt.get("chat_ref"));
        assertEquals("telegram:100/5", prompt.get("reply_to_ref"));
        JsonNode payload = Json.read(prompt.get("payload"));
        assertEquals(Long.parseLong(draft.get("id")), payload.get("draftId").asLong());
        assertEquals("Fix login timeout", payload.get("title").asText());
        assertEquals("OPEN", payload.get("status").asText());
        assertTrue(payload.get("project").isNull());
        assertEquals(3, payload.get("projects").size());
        assertEquals("alm", payload.get("projects").get(0).get("alias").asText());
        assertEquals("life", payload.get("projects").get(2).get("name").asText());
        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"));
    }

    @Test
    void theOnlyAvailableProjectIsChosenForYou() {
        db.transaction(tx -> tasks.draft(tx, SARA, null, "Add make help", "telegram:300/1"));

        assertEquals("life", row("SELECT project FROM draft").get("project"));
        assertEquals("life", Json.read(row("SELECT payload FROM outbox").get("payload")).get("project").asText());
    }

    @Test
    void projectNamedWithTheTaskIsPreselectedUnlessItIsNotOneTheMemberCanUse() {
        db.transaction(tx -> tasks.draft(tx, BOLD, "crm", "Rename the report", "telegram:100/2"));
        unavailable = Set.of("crm");
        db.transaction(tx -> tasks.draft(tx, BOLD, "crm", "Rename the report again", "telegram:100/3"));

        assertEquals("crm", row("SELECT project FROM draft WHERE origin_ref = 'telegram:100/2'").get("project"));
        assertNull(row("SELECT project FROM draft WHERE origin_ref = 'telegram:100/3'").get("project"), "asked again instead");
    }

    @Test
    void choosingTheProjectThenThePriorityGivesTheTask() {
        long draftId = draft(BOLD, "Fix login timeout", "telegram:100/5");
        clock.advance(Duration.ofSeconds(20));

        assertEquals(DraftChoice.PROJECT_CHOSEN, db.transactionReturning(tx -> tasks.chooseProject(tx, BOLD, draftId, "crm")));
        assertEquals(DraftChoice.CREATED, db.transactionReturning(tx -> tasks.choosePriority(tx, BOLD, draftId, Priority.URGENT)));

        Map<String, String> task = row("SELECT * FROM task");
        assertEquals("crm", task.get("project"));
        assertEquals("URGENT", task.get("priority"));
        assertEquals("Fix login timeout", task.get("description"));
        assertEquals("telegram:100/5", task.get("origin_ref"));
        assertEquals(BACKEND, task.get("chat_ref"), "the project's group hears about it");
        assertEquals("2026-09-17T10:00:20.000Z", task.get("created_at"));
        Map<String, String> draft = row("SELECT * FROM draft");
        assertEquals("CREATED", draft.get("status"));
        assertEquals(task.get("id"), draft.get("task_id"));
        assertEquals("QUEUED", row("SELECT status FROM run WHERE task_id = ?", task.get("id")).get("status"));

        Map<String, String> announcement = row("SELECT * FROM outbox WHERE kind = 'TASK_QUEUED'");
        assertEquals(BACKEND, announcement.get("chat_ref"));
        assertNull(announcement.get("reply_to_ref"));
        JsonNode payload = Json.read(announcement.get("payload"));
        assertEquals("Bold", payload.get("requester").asText());
        assertEquals("URGENT", payload.get("priority").asText());
        assertEquals("Fix login timeout", payload.get("title").asText());
        assertEquals(1, schedulerWakes.get());

        JsonNode prompt = db.transactionReturning(tx -> tasks.draftPayload(tx, draftId)).orElseThrow();
        assertEquals("CREATED", prompt.get("status").asText());
        assertEquals(Long.parseLong(task.get("id")), prompt.get("taskId").asLong());
        assertEquals("crm", prompt.get("project").asText());
        assertEquals("URGENT", prompt.get("priority").asText());
    }

    @Test
    void withTopicsOnEachGivenTaskGetsItsOwnTopicInTheWritersPrivateChat() {
        tasks = new TaskService(groups, projects, new ActiveRuns(), clock, schedulerWakes::incrementAndGet, () -> { }, true, draftId -> { });
        long draftId = draft(SARA, "Add make help", "telegram:300/20");

        db.transaction(tx -> tasks.choosePriority(tx, SARA, draftId, Priority.LOW));

        Map<String, String> topic = row("SELECT * FROM outbox WHERE kind = 'TOPIC_CREATE'");
        assertEquals("telegram:300", topic.get("chat_ref"));
        assertEquals(row("SELECT id FROM task").get("id"), topic.get("task_id"));
        assertTrue(db.transactionReturning(tx -> tasks.draftPayload(tx, draftId)).orElseThrow().get("topic").asBoolean());
    }

    @Test
    void priorityBeforeTheProjectIsRefused() {
        long draftId = draft(BOLD, "Fix login timeout", "telegram:100/6");

        assertEquals(DraftChoice.CHOOSE_PROJECT_FIRST, db.transactionReturning(tx -> tasks.choosePriority(tx, BOLD, draftId, Priority.LOW)));
        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"));
    }

    @Test
    void onlyTheWriterChoosesAndOnlyAmongTheirOwnAvailableProjects() {
        long bolds = draft(BOLD, "Fix login timeout", "telegram:100/7");
        long saras = draft(SARA, "Add make help", "telegram:300/8");
        unavailable = Set.of("crm");

        assertEquals(DraftChoice.NOT_REQUESTER, db.transactionReturning(tx -> tasks.chooseProject(tx, SARA, bolds, "life")));
        assertEquals(DraftChoice.PROJECT_UNAVAILABLE, db.transactionReturning(tx -> tasks.chooseProject(tx, SARA, saras, "crm")));
        assertEquals(DraftChoice.PROJECT_UNAVAILABLE, db.transactionReturning(tx -> tasks.chooseProject(tx, BOLD, bolds, "crm")));
        assertEquals(DraftChoice.NOT_FOUND, db.transactionReturning(tx -> tasks.chooseProject(tx, BOLD, 999, "life")));
        assertNull(row("SELECT project FROM draft WHERE id = ?", bolds).get("project"));
    }

    @Test
    void secondPriorityPressSaysTheTaskAlreadyExists() {
        long draftId = draft(SARA, "Add make help", "telegram:300/9");
        db.transaction(tx -> tasks.choosePriority(tx, SARA, draftId, Priority.NORMAL));

        assertEquals(DraftChoice.ALREADY_CREATED, db.transactionReturning(tx -> tasks.choosePriority(tx, SARA, draftId, Priority.URGENT)));
        assertEquals("1", row("SELECT count(*) AS n FROM task").get("n"));
    }

    @Test
    void redeliveredMessageMakesOneDraft() {
        draft(BOLD, "Fix login timeout", "telegram:100/10");

        assertEquals(DraftResult.DUPLICATE, db.transactionReturning(tx -> tasks.draft(tx, BOLD, null, "Fix login timeout", "telegram:100/10")));
        assertEquals("1", row("SELECT count(*) AS n FROM draft").get("n"));
    }

    @Test
    void blankTaskAsksForUsageAndAMemberWithoutAvailableProjectsIsTold() {
        unavailable = Set.of("life");

        assertEquals(DraftResult.EMPTY, db.transactionReturning(tx -> tasks.draft(tx, BOLD, null, "  \n", "telegram:100/11")));
        assertEquals(DraftResult.NO_PROJECTS, db.transactionReturning(tx -> tasks.draft(tx, SARA, null, "Add make help", "telegram:300/12")));

        assertEquals("TASK_USAGE", row("SELECT kind FROM outbox WHERE reply_to_ref = 'telegram:100/11'").get("kind"));
        Map<String, String> none = row("SELECT * FROM outbox WHERE reply_to_ref = 'telegram:300/12'");
        assertEquals("NO_PROJECTS", none.get("kind"));
        assertEquals("telegram:300", none.get("chat_ref"));
        assertEquals("0", row("SELECT count(*) AS n FROM draft").get("n"));
    }

    @Test
    void draftLeftUnansweredForADayExpiresWithANote() {
        long old = draft(BOLD, "Fix login timeout", "telegram:100/13");
        clock.advance(Duration.ofHours(23));
        long recent = draft(BOLD, "Rename the report", "telegram:100/14");
        clock.advance(Duration.ofHours(2));

        int expired = db.transactionReturning(tx -> tasks.expireDrafts(tx, clock.instant().minus(Duration.ofHours(24))));

        assertEquals(1, expired);
        assertEquals("EXPIRED", row("SELECT status FROM draft WHERE id = ?", old).get("status"));
        assertEquals("OPEN", row("SELECT status FROM draft WHERE id = ?", recent).get("status"));
        Map<String, String> note = row("SELECT * FROM outbox WHERE kind = 'DRAFT_EXPIRED'");
        assertEquals("telegram:100", note.get("chat_ref"));
        assertEquals("telegram:100/13", note.get("reply_to_ref"));
        assertEquals("Fix login timeout", Json.read(note.get("payload")).get("title").asText(), "parts of one message expire apart");
        assertEquals(DraftChoice.EXPIRED, db.transactionReturning(tx -> tasks.choosePriority(tx, BOLD, old, Priority.NORMAL)));
    }

    private long draft(Requester who, String text, String originRef) {
        assertEquals(DraftResult.DRAFTED, db.transactionReturning(tx -> tasks.draft(tx, who, null, text, originRef)));
        return Long.parseLong(row("SELECT id FROM draft WHERE origin_ref = ?", originRef).get("id"));
    }

    private static Config.Project project(String name, String alias) {
        return new Config.Project(name, alias, "https://github.com/acme/" + name + ".git", null, "main", "claude-code", null, null, List.of(), null, null, null);
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
