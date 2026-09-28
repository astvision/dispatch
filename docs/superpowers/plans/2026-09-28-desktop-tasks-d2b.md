# D-2b: Giving a Task and the Live Overview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The owner gives a task from `dispatch ui`, reads Тойм as a live dashboard — what waits on them with its one
action, what runs and is queued, spend per day for 30 days by project — and is told by the browser when a task starts
waiting on them.

**Architecture:** Everything goes through D-2a's desk port (ADR 0030). The bot gains `TaskService.give` (the checks
`create` makes, answered to the page; a `desk:<uuid>` origin; a line in the private chat), a `DeskTasksApi` holding the
desk-only routes `/api/tasks/new` and `/api/tasks/spend`, and a fuller `/api/live` (the three task lists, the
concurrency limit, the acting admin's projects). The outbox sends a reply whose target is not Telegram's as a plain
message, so a stray `desk:` reference can never stop the sender. The page reads `/api/live` as the strip already does:
the Overview renders that reading, the spend chart reads `/api/tasks/spend`, and notifications ride on the same reading
(every 30 s while the tab is hidden, when switched on).

**Tech Stack:** Java 25 (JDK HttpServer and HttpClient, Jackson, SQLite), React 19 + antd 6 + Vite, vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-28-desktop-tasks-design.md` (D-2b milestone). D-2a's plan
(`2026-09-28-desktop-tasks-d2a.md`) and ADR 0030 set the desk port this plan builds on.

**Branch:** `d2b-overview`, from `origin/main` `c9888ce` (D-2a as pushed and deployed). Local `main` also holds another
session's unpushed `m-instances` merge (`c7620f3`); this branch stays off it, so its pull request publishes only D-2b,
and it merges into local `main` at the end (Task 12).

## Global Constraints

- No new dependency, Java or npm: the chart is CSS bars.
- The desk-only routes live in `DeskTasksApi`, which only `DeskServer` serves; the Mini App's `TasksApi` routes do not
  change.
- ADR 0020 unchanged: a task given on the desktop is the acting admin's own, in one of their groups' projects
  (`TaskService` refuses others); the Overview's Approve is the requester's decision, exactly as in the chat.
- A refusal on the desk is answered on the page only, in the page's language; nothing reaches Telegram for a task the
  desk refused (D-2a's `TasksApi.cancel` pattern).
- Every page word through `t()`, with its key in `ui/src/i18n/en.ts` and `mn.ts`; every server message a page can show
  is `Text.of(key, …)` in `texts_en.properties` and `texts_mn.properties`, apostrophes doubled; the bot's Telegram words
  in `messages_mn.properties`. No English word on the Mongolian page beyond code values, names and other programs'
  words: the pages are "хөтөч" or "энэ хуудас", never "desktop" (`TextTest` and `i18n.test` pin it).
- The board look (D-1): colours from `ui/src/board.ts` (the chart's project colours are added there); a lamp always
  carries its words.
- Reading (spec): `/api/live` and each page's own data every 5 seconds while the tab is in view; with the tab hidden
  nothing is read, except `/api/live` every 30 seconds while notifications are on.
- Tests: `./mvnw -q -o test -Dtest=A,B -Dsurefire.failIfNoSpecifiedTests=false` (commas between classes);
  `cd ui && npx vitest run && npx tsc --noEmit`.
- Secret hygiene: scan the diff before every push, one `/usr/bin/grep -E` pattern per call, `"$HOME"` for the home path.
- The executor passes this plan to `task-start` and `task-done` as `docs/superpowers/plans/2026-09-28-desktop-tasks-d2b.md`,
  relative to the worktree (an absolute path keys a second workspace).

## Review Focus

1. **A double click on Өгөх** (the owner clicks twice before the bot answers): one task, not two — the button is busy
   until the answer. Task 6 pins it.
2. **A run that starts late in the evening in a zone east of UTC** (Mongolia is UTC+8): its cost counts on the owner's
   day, not UTC's. Task 3 pins it with an Ulaanbaatar clock.
3. **A browser that refuses notifications** (the owner blocked them, or the browser has none): the switch stays off
   and says why; nothing else changes. Task 9 pins it.
4. **Approving from the Overview a plan replaced since the reading:** the bot's stale refusal shows in the page's
   language, and the Overview reads again. Task 8 pins it.
5. **A task the desk may not give** (a project outside the admin's groups, a missing clone, empty words): refused on
   the page, and not a word in Telegram. Tasks 2 and 4 pin it.

---

### Task 1: A reply target that is not Telegram's is sent as a plain message

**Files:**
- Modify: `src/main/java/dispatch/telegram/Refs.java` (`isTelegram`)
- Modify: `src/main/java/dispatch/telegram/OutboxSender.java:123-125`
- Test: `src/test/java/dispatch/telegram/OutboxSenderTest.java`

**Interfaces:**
- Produces: `Refs.isTelegram(String ref): boolean` (package-private). A `desk:` reply target becomes no reply target.

Today `OutboxSender` parses every reply target with `Refs.messageId`, which throws on a reference that is not
Telegram's, outside the send's `try`: the exception ends the sender's loop, and a loop that dies ends the bot (App), so
one such row would stop the bot at every start.

- [ ] **Step 1: Write the failing test** (in `OutboxSenderTest`, beside `dueMessageIsSentAsReplyAndMarkedSent`)

```java
    @Test
    void aReplyTargetOutsideTelegramIsSentAsAPlainMessage() throws Exception {
        // A task given on the desktop (D-2b) comes from "desk:…": there is no Telegram message to reply to.
        long id = db.transactionReturning(tx -> Outbox.enqueue(tx, null, OutboxKind.TASK_QUEUED, "telegram:100", "desk:5f0c9a2e",
                Json.object().put("taskId", 42).put("project", "alm").put("requester", "Bold").put("priority", "NORMAL")
                        .put("title", "Fix the login timeout"), clock.instant()));

        assertTrue(sender.deliverDue());

        JsonNode body = telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json();
        assertEquals(100, body.get("chat_id").asLong());
        assertFalse(body.has("reply_parameters"), body.toString());
        assertEquals("SENT", row(id).get("status"));
    }
```

- [ ] **Step 2: Run it**

Run: `./mvnw -q -o test -Dtest=OutboxSenderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `IllegalArgumentException: not a Telegram reference: desk:5f0c9a2e`.

- [ ] **Step 3: Implement.** In `Refs`, after `threadId`:

```java
    /** Whether {@code ref} is Telegram's at all: a task given on the desktop has a "desk:" origin (D-2b), nothing to reply to. */
    static boolean isTelegram(String ref) {
        return ref != null && ref.startsWith(PREFIX);
    }
```

In `OutboxSender`, replace the two lines that compute `replyTo` and `thread`:

```java
        // A reply target outside Telegram (a task given on the desktop, D-2b) is no message to answer: sent plainly.
        String replyRef = Refs.isTelegram(message.replyToRef()) ? message.replyToRef() : null;
        Long replyTo = replyRef == null ? null : Refs.messageId(replyRef);
        Long thread = replyRef == null ? null : Refs.threadId(replyRef);
```

- [ ] **Step 4: Run it**

Run: `./mvnw -q -o test -Dtest=OutboxSenderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit** — "Send a reply whose target is not Telegram's as a plain message".

---

### Task 2: A task given on the desktop

**Files:**
- Modify: `src/main/java/dispatch/core/TaskService.java` (`DESK_ORIGIN`, `Given`, `give`, `memberProject`; `create` uses
  `memberProject`)
- Modify: `src/main/java/dispatch/domain/OutboxKind.java` (`TASK_GIVEN_ON_DESK`)
- Modify: `src/main/java/dispatch/telegram/Renderer.java`, `src/main/resources/messages_mn.properties`
- Create: `src/test/java/dispatch/core/GiveTest.java`
- Modify: `src/test/java/dispatch/telegram/RendererTest.java` (`samplePayload`, one case)

**Interfaces:**
- Consumes: Task 1 — the new line has no reply target, so it is sent plainly.
- Produces: `TaskService.DESK_ORIGIN = "desk:"`; `record TaskService.Given(CreateResult result, long taskId, String reason)`;
  `TaskService.give(Tx tx, Requester who, String projectKey, String text, Priority priority): Given`;
  `OutboxKind.TASK_GIVEN_ON_DESK` with the payload `{taskId, project, priority, title}`.

- [ ] **Step 1: Write the failing tests.** `GiveTest`:

```java
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
```

In `RendererTest.samplePayload`, add the case beside `TASK_QUEUED`:

```java
            case TASK_GIVEN_ON_DESK -> Json.object().put("taskId", 12).put("project", "alm").put("priority", "URGENT")
                    .put("title", "Fix the login timeout");
```

and the case:

```java
    @Test
    void aTaskGivenOnTheDesktopSaysWhereItCameFromInThePrivateChat() {
        String html = renderer.render(OutboxKind.TASK_GIVEN_ON_DESK, Json.object().put("taskId", 12).put("project", "alm")
                .put("priority", "URGENT").put("title", "Fix the login timeout")).html();

        assertEquals("🖥 <b>#12</b> alm · 🔴\nХөтөч дээрээс өгсөн: Fix the login timeout", html);
    }
```

- [ ] **Step 2: Run them**

Run: `./mvnw -q -o test -Dtest=GiveTest,RendererTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation: `give`, `Given`, `DESK_ORIGIN` and `TASK_GIVEN_ON_DESK` do not exist.

- [ ] **Step 3: Implement.** `OutboxKind`, after `TASK_QUEUED`:

```java
    /** The requester's own line about a task they gave on the desktop (D-2b), so the chat hears of it as of any other. */
    TASK_GIVEN_ON_DESK,
```

`messages_mn.properties`, after `task.queued`:

```properties
task.givenOnDesk=🖥 <b>#{0}</b> {1} · {2}\nХөтөч дээрээс өгсөн: {3}
```

`Renderer`, in the switch beside `TASK_QUEUED`:

```java
            case TASK_GIVEN_ON_DESK -> plain(format("task.givenOnDesk", taskId(payload), escape(payload.path("project").asText()),
                    icon(payload).strip(), escapeWithin(payload.path("title").asText(), TITLE_LIMIT)));
```

`TaskService`: in `create`, replace
`Optional<Config.Project> found = projects.find(projectKey).filter(project -> mine.contains(project.name()));` with
`Optional<Config.Project> found = memberProject(who, projectKey);` (keep `mine`: the refusal lists it). Then add:

```java
    /** Where a task given on the desktop comes from (D-2b): no Telegram message, so nothing in the chat to reply to. */
    public static final String DESK_ORIGIN = "desk:";

    /** What giving a task on the desktop came to: the new task's number, or why not ({@code reason}: an unavailable project's). */
    public record Given(CreateResult result, long taskId, String reason) {
    }

    /**
     * A task given on the desktop (D-2b), as the requester's own: {@link #create}'s checks, answered to the page and never
     * to the chat, then the task with a {@link #DESK_ORIGIN} origin, and a line in the requester's private chat saying
     * where it came from, so the chat stays in step.
     */
    public Given give(Tx tx, Requester who, String projectKey, String text, Priority priority) {
        if (!groups.isMember(who.ref())) {
            return new Given(CreateResult.NOT_ALLOWED, 0, null);
        }
        if (text == null || text.isBlank()) {
            return new Given(CreateResult.EMPTY, 0, null);
        }
        Optional<Config.Project> project = memberProject(who, projectKey);
        if (project.isEmpty()) {
            return new Given(CreateResult.UNKNOWN_PROJECT, 0, null);
        }
        Optional<String> unavailable = projects.unavailableReason(project.get());
        if (unavailable.isPresent()) {
            return new Given(CreateResult.PROJECT_UNAVAILABLE, 0, unavailable.get());
        }
        Instant now = clock.instant();
        long id = insertTask(tx, who, project.get(), text.strip(), priority, DESK_ORIGIN + UUID.randomUUID(), now);
        Task task = Tasks.find(tx, id).orElseThrow();
        Outbox.enqueueForRequester(tx, task, OutboxKind.TASK_GIVEN_ON_DESK, Json.object().put("taskId", id)
                .put("project", task.project()).put("priority", priority.name()).put("title", task.title()), now);
        tx.afterCommit(wakeOutbox);
        return new Given(CreateResult.CREATED, id, null);
    }

    /** The project {@code projectKey} names (its name or alias), if it is one of the requester's groups' projects. */
    private Optional<Config.Project> memberProject(Requester who, String projectKey) {
        Set<String> mine = groups.projectsOfMember(who.ref());
        return projects.find(projectKey).filter(project -> mine.contains(project.name()));
    }
```

- [ ] **Step 4: Run them**

Run: `./mvnw -q -o test -Dtest=GiveTest,RendererTest,TaskLifecycleTest,TextTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (`TaskLifecycleTest` covers `create`'s refusals after the `memberProject` change).

- [ ] **Step 5: Commit** — "Give a task on the desktop as the requester's own, and say so in the private chat".

---

### Task 3: Spend per day and project

**Files:**
- Modify: `src/main/java/dispatch/store/Runs.java` (`Started`, `startedSince`)
- Create: `src/main/java/dispatch/ui/DeskTasksApi.java` (the spend route)
- Modify: `src/main/java/dispatch/ui/DeskServer.java` (serves `DeskTasksApi`'s routes beside `TasksApi`'s)
- Create: `src/test/java/dispatch/ui/DeskTasksApiTest.java`
- Modify: `src/test/java/dispatch/ui/DeskServerTest.java` (the spend over HTTP)

**Interfaces:**
- Produces: `record Runs.Started(Instant startedAt, String project, BigDecimal costUsd)`;
  `Runs.startedSince(Tx, Instant): List<Started>` (finished runs, oldest first);
  `DeskTasksApi(Database db, TaskService tasks, Clock clock)` with
  `routes(): Map<String, BiFunction<UiServer.Caller, JsonNode, Object>>` and `spend(UiServer.Caller, JsonNode): ObjectNode`;
  `POST /api/tasks/spend {"days": n}` (1 to 90, 30 when absent) →
  `{from, to, days: [{day, usd: {project: "0.10"}}], projects: [{project, usd, runs, unpriced}], totalUsd}`: every day
  of the window, oldest first; the projects costliest first; `unpriced` counts finished runs that reported no cost.

- [ ] **Step 1: Write the failing tests.** `DeskTasksApiTest`:

```java
package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.Groups;
import dispatch.core.Projects;
import dispatch.core.RunTransitions;
import dispatch.core.TaskService;
import dispatch.domain.ClaimedRun;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.testing.TestClock;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The desk's own task routes (D-2b). */
class DeskTasksApiTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final UiServer.Caller OWNER = new UiServer.Caller(BOLD.ref(), BOLD.name(), true);

    @TempDir
    Path dir;

    private Database db;
    private TestClock clock;
    private TaskService tasks;
    private RunTransitions transitions;

    @BeforeEach
    void setUp() {
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
        // 23:30 on the 27th in Ulaanbaatar (UTC+8).
        clock = new TestClock(Instant.parse("2026-09-27T15:30:00Z"));
        Groups groups = new Groups(new Config.Telegram(List.of(100L), List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold")), List.of("alm", "life")))));
        tasks = new TaskService(groups, new Projects(List.of(project("alm", "claude-code"), project("life", "codex")),
                project -> Optional.empty()), new ActiveRuns(), clock, () -> { }, () -> { });
        transitions = new RunTransitions(db, clock, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aRunLateInTheEveningEastOfUtcCountsOnTheOwnersDayAndARunWithoutCostIsCounted() {
        planned("alm", new BigDecimal("0.10"));
        clock.advance(Duration.ofHours(1)); // 00:30 on the 28th in Ulaanbaatar, still the 27th in UTC
        planned("alm", new BigDecimal("0.20"));
        planned("life", null);              // Codex reports no cost
        startedOnly("alm");                 // still running: nothing to count yet
        DeskTasksApi api = new DeskTasksApi(db, tasks, Clock.fixed(Instant.parse("2026-09-28T02:00:00Z"), ZoneId.of("Asia/Ulaanbaatar")));

        JsonNode spend = api.spend(OWNER, Json.read("{\"days\": 2}"));

        assertEquals("2026-09-27", spend.path("days").get(0).path("day").asText());
        assertEquals("0.10", spend.path("days").get(0).path("usd").path("alm").asText());
        assertEquals("2026-09-28", spend.path("days").get(1).path("day").asText());
        assertEquals("0.20", spend.path("days").get(1).path("usd").path("alm").asText());
        JsonNode alm = spend.path("projects").get(0);
        assertEquals("alm", alm.path("project").asText());
        assertEquals("0.30", alm.path("usd").asText());
        assertEquals(2, alm.path("runs").asInt());
        JsonNode life = spend.path("projects").get(1);
        assertEquals("life", life.path("project").asText());
        assertEquals("0.00", life.path("usd").asText());
        assertEquals(1, life.path("unpriced").asInt());
        assertEquals("0.30", spend.path("totalUsd").asText());
    }

    @Test
    void theWindowIsThirtyDaysUnlessAskedAndNeverMoreThanNinety() {
        DeskTasksApi api = new DeskTasksApi(db, tasks, Clock.fixed(Instant.parse("2026-09-28T02:00:00Z"), ZoneId.of("Asia/Ulaanbaatar")));

        assertEquals(30, api.spend(OWNER, Json.read("{}")).path("days").size());
        assertEquals(90, api.spend(OWNER, Json.read("{\"days\": 400}")).path("days").size());
        assertEquals(1, api.spend(OWNER, Json.read("{\"days\": 0}")).path("days").size());
    }

    /** A task in {@code project} whose plan ran now and reported {@code cost} (null: none). */
    private void planned(String project, BigDecimal cost) {
        ClaimedRun run = started(project);
        Plan plan = new Plan("Make the timeout configurable", List.of(), List.of("Read auth.timeout"), List.of(), List.of());
        transitions.planSucceeded(run.taskId(), run.seq(), plan,
                new AgentResult(AgentOutcome.SUCCEEDED, 0, "s", plan.toJson(), null, cost, 3, List.of(), null, null, null));
    }

    private void startedOnly(String project) {
        started(project);
    }

    private ClaimedRun started(String project) {
        String origin = BOLD.ref() + "/" + System.nanoTime();
        db.transaction(tx -> tasks.create(tx, BOLD, project, "Fix the login timeout", Priority.NORMAL, origin));
        return db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
    }

    private static Config.Project project(String name, String agent) {
        return new Config.Project(name, null, "https://github.com/acme/" + name + ".git", null, "main", agent,
                null, null, List.of(), null, null, null);
    }
}
```

In `DeskServerTest`, add:

```java
    @Test
    void theSpendPerDayIsServedToTheDesk() throws Exception {
        planned(BOLD);

        JsonNode spend = Json.read(post("/api/tasks/spend", "{\"days\":30}").body());

        assertEquals(30, spend.path("days").size());
        assertEquals("2026-09-28", spend.path("days").get(29).path("day").asText());
        assertEquals("0.10", spend.path("days").get(29).path("usd").path("alm").asText());
        assertEquals("0.10", spend.path("totalUsd").asText());
    }
```

- [ ] **Step 2: Run them**

Run: `./mvnw -q -o test -Dtest=DeskTasksApiTest,DeskServerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation: `DeskTasksApi` does not exist.

- [ ] **Step 3: Implement.** `Runs`, beside `spentSince`:

```java
    /** A finished run's start, project and reported cost (null for Codex and Gemini CLI, which report none). */
    public record Started(Instant startedAt, String project, BigDecimal costUsd) {
    }

    /** The runs started at or after {@code since} that have finished, oldest first: the desk's spend per day (D-2b). */
    public static List<Started> startedSince(Tx tx, Instant since) {
        return tx.list("""
                SELECT r.started_at, t.project, r.cost_usd FROM run r JOIN task t ON t.id = r.task_id
                WHERE r.started_at >= ? AND r.finished_at IS NOT NULL ORDER BY r.started_at""",
                row -> new Started(row.instant("started_at"), row.string("project"), row.decimal("cost_usd")), since);
    }
```

`DeskTasksApi`:

```java
package dispatch.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.core.TaskService;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.ui.UiServer.Caller;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;

/** The desk's own task routes, which the Mini App does not have (D-2b): the spend per day, and giving a task. */
final class DeskTasksApi {

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 90;

    private final Database db;
    private final TaskService tasks;
    private final Clock clock;

    DeskTasksApi(Database db, TaskService tasks, Clock clock) {
        this.db = db;
        this.tasks = tasks;
        this.clock = clock;
    }

    Map<String, BiFunction<Caller, JsonNode, Object>> routes() {
        return Map.of("/api/tasks/spend", this::spend);
    }

    /**
     * {"days": 30} → the cost per day and project, each run by the day it started in the bot's time zone, the whole
     * instance's (the desk is the owner's). A project on Codex or Gemini CLI reports no cost: its runs are counted.
     */
    ObjectNode spend(Caller caller, JsonNode body) {
        int days = Math.clamp(body.path("days").asInt(DEFAULT_DAYS), 1, MAX_DAYS);
        ZoneId zone = clock.getZone();
        LocalDate last = LocalDate.now(clock);
        LocalDate first = last.minusDays(days - 1);
        List<Runs.Started> runs = db.transactionReturning(tx -> Runs.startedSince(tx, first.atStartOfDay(zone).toInstant()));
        Map<LocalDate, Map<String, BigDecimal>> perDay = new TreeMap<>();
        Map<String, BigDecimal> perProject = new TreeMap<>();
        Map<String, Integer> runsOf = new TreeMap<>();
        Map<String, Integer> unpricedOf = new TreeMap<>();
        for (Runs.Started run : runs) {
            runsOf.merge(run.project(), 1, Integer::sum);
            if (run.costUsd() == null) {
                unpricedOf.merge(run.project(), 1, Integer::sum);
                continue;
            }
            perDay.computeIfAbsent(LocalDate.ofInstant(run.startedAt(), zone), day -> new TreeMap<>())
                    .merge(run.project(), run.costUsd(), BigDecimal::add);
            perProject.merge(run.project(), run.costUsd(), BigDecimal::add);
        }
        ObjectNode answer = Json.object().put("from", first.toString()).put("to", last.toString());
        ArrayNode dayList = answer.putArray("days");
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            ObjectNode usd = dayList.addObject().put("day", day.toString()).putObject("usd");
            perDay.getOrDefault(day, Map.of()).forEach((project, amount) -> usd.put(project, usd(amount)));
        }
        ArrayNode projectList = answer.putArray("projects");
        Comparator<String> costliest = Comparator.comparing((String project) -> perProject.getOrDefault(project, BigDecimal.ZERO))
                .reversed().thenComparing(Comparator.naturalOrder());
        runsOf.keySet().stream().sorted(costliest).forEach(project -> projectList.addObject().put("project", project)
                .put("usd", usd(perProject.getOrDefault(project, BigDecimal.ZERO)))
                .put("runs", runsOf.get(project)).put("unpriced", unpricedOf.getOrDefault(project, 0)));
        answer.put("totalUsd", usd(perProject.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
        return answer;
    }

    private static String usd(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
```

`DeskServer.start`: serve both route maps (imports: `com.fasterxml.jackson.databind.JsonNode`, `java.util.HashMap`,
`java.util.function.BiFunction`):

```java
        Map<String, BiFunction<UiServer.Caller, JsonNode, Object>> routes = new HashMap<>(tasksApi.routes());
        routes.putAll(new DeskTasksApi(db, tasks, clock).routes());
        UiServer server = UiServer.start(0, "/desk-serves-no-pages", port -> new DeskAuth(port, token, groups),
                Map.of("/api/live", live::get), routes);
```

- [ ] **Step 4: Run them**

Run: `./mvnw -q -o test -Dtest=DeskTasksApiTest,DeskServerTest,RunsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. Then show the zone test can fail: grouping by `ZoneOffset.UTC` instead of `zone` puts both alm runs on
the 27th and fails `aRunLateInTheEveningEastOfUtc…`; restore.

- [ ] **Step 5: Commit** — "Count the spend per day and project, on the owner's day".

---

### Task 4: The desk gives a task, and the live summary carries what the Overview shows

**Files:**
- Modify: `src/main/java/dispatch/ui/DeskTasksApi.java` (`/api/tasks/new`)
- Modify: `src/main/java/dispatch/core/TaskService.java` (`statusPayload`: `planSeq` on the viewer's own waiting task)
- Modify: `src/main/java/dispatch/ui/LiveApi.java` (`maxConcurrent`, `projects`, `tasks`)
- Modify: `src/main/java/dispatch/ui/DeskServer.java` (`start(…, int maxConcurrent)`), `src/main/java/dispatch/App.java`
- Modify: `src/main/resources/texts_en.properties`, `texts_mn.properties`
- Test: `src/test/java/dispatch/ui/DeskServerTest.java`, `src/test/java/dispatch/ui/TasksApiTest.java`; update the
  `DeskServer.start` calls in `DeskProxyTest`

**Interfaces:**
- Consumes: `TaskService.give`, `Given` (Task 2); `DeskTasksApi` (Task 3).
- Produces: `POST /api/tasks/new {project, text, priority}` → `{taskId}`, or 400 `empty` / 400 `bad_priority` /
  404 `unknown_project` / 409 `project_unavailable` / 403 `not_a_member`, each in the page's language;
  `GET /api/live` gains `maxConcurrent: int`, `projects: string[]` (the acting admin's, sorted) and
  `tasks: {running: [...], queued: [...], awaitingApproval: [...]}` (the status payload's rows in the owner's view; a
  waiting row of the viewer's own carries `planSeq`); `DeskServer.start(Path, Database, TaskService, Groups, Clock,
  String version, String name, int maxConcurrent)`.

- [ ] **Step 1: Write the failing tests.** In `DeskServerTest`: `setUp` passes `2` as the new last argument to
  `DeskServer.start`; add a language overload of `post` and three cases:

```java
    @Test
    void aTaskGivenOnTheDeskIsTheAdminsOwnAndQueuesItsPlan() throws Exception {
        JsonNode given = Json.read(post("/api/tasks/new", "{\"project\":\"alm\",\"text\":\"Fix the login timeout\",\"priority\":\"URGENT\"}").body());
        JsonNode queued = Json.read(get("/api/live").body()).path("tasks").path("queued");

        assertTrue(given.path("taskId").asLong() > 0, given.toString());
        assertEquals(given.path("taskId").asLong(), queued.get(0).path("taskId").asLong());
        assertTrue(queued.get(0).path("mine").asBoolean());
    }

    @Test
    void whatTheDeskMayNotGiveIsRefusedInThePagesLanguageAndNotInTheChat() throws Exception {
        HttpResponse<String> empty = post("/api/tasks/new", "{\"project\":\"alm\",\"text\":\"  \"}", "mn");
        HttpResponse<String> unknown = post("/api/tasks/new", "{\"project\":\"crm\",\"text\":\"Fix it\"}", "en");
        HttpResponse<String> priority = post("/api/tasks/new", "{\"project\":\"alm\",\"text\":\"Fix it\",\"priority\":\"SOON\"}", "en");

        assertEquals(400, empty.statusCode());
        assertEquals("даалгаварт хэдэн үг бичнэ үү", Json.read(empty.body()).path("message").asText());
        assertEquals(404, unknown.statusCode());
        assertEquals("crm is not one of your projects", Json.read(unknown.body()).path("message").asText());
        assertEquals(400, priority.statusCode());
        assertEquals("0", SqlRows.single(dir.resolve("dispatch.db"), "SELECT count(*) AS n FROM outbox").get("n"));
    }

    @Test
    void theLiveSummaryCarriesWhatTheOverviewShows() throws Exception {
        planned(BOLD);

        JsonNode live = Json.read(get("/api/live").body());

        assertEquals(2, live.path("maxConcurrent").asInt());
        assertEquals("[\"alm\"]", live.path("projects").toString());
        JsonNode waiting = live.path("tasks").path("awaitingApproval").get(0);
        assertEquals(1, waiting.path("planSeq").asInt());
        assertTrue(waiting.path("actions").toString().contains("approve"), waiting.toString());
    }

    private HttpResponse<String> post(String path, String body, String language) throws Exception {
        return http.send(authorized(path).header("Content-Type", "application/json").header("Accept-Language", language)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }
```

(imports: `assertTrue`, `dispatch.testing.SqlRows`). In `TasksApiTest`, pin that the Mini App gives no tasks:

```java
    @Test
    void theMiniAppGivesNoTaskAndCountsNoSpend() {
        Set<String> routes = new TasksApi(db, tasks, groups).routes().keySet();

        assertFalse(routes.contains("/api/tasks/new"));
        assertFalse(routes.contains("/api/tasks/spend"));
    }
```

- [ ] **Step 2: Run them**

Run: `./mvnw -q -o test -Dtest=DeskServerTest,TasksApiTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation (`DeskServer.start` takes no `maxConcurrent`); after Step 3's signature alone, the three
desk cases fail on 404 and missing fields.

- [ ] **Step 3: Implement.** Bundles (en, then mn):

```properties
refusal.taskEmpty=a task needs a few words
refusal.projectNotYours={0} is not one of your projects
refusal.projectUnavailable=the project {0} cannot take tasks now: {1}
refusal.badPriority={0} is not a priority; use URGENT, NORMAL or LOW
```

```properties
refusal.taskEmpty=даалгаварт хэдэн үг бичнэ үү
refusal.projectNotYours={0} таны төслүүдийн нэг биш
refusal.projectUnavailable=«{0}» төсөл одоогоор ажиллах боломжгүй: {1}
refusal.badPriority={0} гэсэн зэрэглэл алга; URGENT, NORMAL эсвэл LOW
```

`DeskTasksApi` (imports: `dispatch.Text`, `dispatch.core.TaskService`, `dispatch.domain.Priority`,
`dispatch.domain.Requester`): add `"/api/tasks/new", this::give` to `routes()` and:

```java
    /**
     * {"project", "text", "priority"} → {"taskId"}: a task of the acting admin's own. A refusal is answered here only
     * ({@link TaskService#give} writes nothing for one), so the chat hears of the tasks the desk gave, not of its typos.
     */
    ObjectNode give(Caller caller, JsonNode body) {
        String project = body.path("project").asText("");
        Priority priority = priority(body.path("priority").asText("NORMAL"));
        TaskService.Given given = db.transactionReturning(tx ->
                tasks.give(tx, new Requester(caller.ref(), caller.name()), project, body.path("text").asText(""), priority));
        return switch (given.result()) {
            case CREATED -> Json.object().put("taskId", given.taskId());
            case EMPTY -> throw new ApiException(400, "empty", Text.of("refusal.taskEmpty"));
            case UNKNOWN_PROJECT -> throw new ApiException(404, "unknown_project", Text.of("refusal.projectNotYours", project));
            case PROJECT_UNAVAILABLE -> throw new ApiException(409, "project_unavailable",
                    Text.of("refusal.projectUnavailable", project, given.reason()));
            case NOT_ALLOWED -> throw new ApiException(403, "not_a_member", Text.of("refusal.notMember"));
            case DUPLICATE -> throw new IllegalStateException("a desk origin is new every time, yet the task came back a duplicate");
        };
    }

    private static Priority priority(String given) {
        try {
            return Priority.valueOf(given);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "bad_priority", Text.of("refusal.badPriority", given));
        }
    }
```

`TaskService.statusPayload`, in the viewer's own waiting task's `currentPlan(...).ifPresent(plan -> …)`, first line:

```java
                    item.put("planSeq", plan.path("planSeq").asInt());
```

`LiveApi` (imports: `com.fasterxml.jackson.databind.node.ObjectNode` is there; add `dispatch.Json`): keep the groups and
the limit, and widen `Live`:

```java
    record Live(String version, String name, int running, int queued, List<Waiting> waitingOnYou, int waitingOnOthers,
                String todayUsd, String monthUsd, int maxConcurrent, List<String> projects, ObjectNode tasks) {
    }
```

The constructor takes `int maxConcurrent` last and stores `groups` and `maxConcurrent`; in `get`, build the lists and
return them:

```java
            ObjectNode lists = Json.object();
            lists.set("running", status.withArray("running"));
            lists.set("queued", status.withArray("queued"));
            lists.set("awaitingApproval", status.withArray("awaitingApproval"));
            List<String> projects = groups.projectsOfMember(caller.ref()).stream().sorted().toList();
            return new Live(version, name, status.withArray("running").size(), status.withArray("queued").size(), mine, others,
                    usd(Runs.spentSince(tx, today)), usd(Runs.spentSince(tx, month)), maxConcurrent, projects, lists);
```

`DeskServer.start` takes `int maxConcurrent` last and passes it to `new LiveApi(…, maxConcurrent)`; `App` passes
`config.scheduler().maxConcurrentRuns()`; `DeskProxyTest`'s two `DeskServer.start` calls pass `2`.

- [ ] **Step 4: Run them**

Run: `./mvnw -q -o test -Dtest=DeskServerTest,DeskProxyTest,DeskTasksApiTest,TasksApiTest,AppTest,StatusAndHistoryTest,TextTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit** — "Give a task through the desk, and tell the Overview what runs and what waits".

---

### Task 5: The page reads what D-2b serves

**Files:**
- Modify: `ui/src/api.ts` (`Live`, `LiveTask`, `Spend`, `Priority`, `giveTask`, `getSpend`; `TaskRow.planSeq`)
- Modify: `ui/src/desktop/usePolling.ts` (a hidden beat; one reading at a time)
- Test: `ui/src/desktop/usePolling.test.ts`, `ui/src/api.test.ts`; every `Live` fixture in `ui/src` (`grep -rn todayUsd ui/src`)

**Interfaces:**
- Consumes: the routes of Tasks 3 and 4.
- Produces: `type LiveTask = Omit<TaskRow, "state">`; `Live` gains `maxConcurrent: number`, `projects: string[]`,
  `tasks: {running: LiveTask[]; queued: LiveTask[]; awaitingApproval: LiveTask[]}`; `interface Spend`;
  `type Priority = "URGENT" | "NORMAL" | "LOW"`; `giveTask(project, text, priority): Promise<{taskId: number}>`;
  `getSpend(days, signal?): Promise<Spend>`; `usePolling(read, intervalMs = 5000, key?, hiddenIntervalMs?)`.

- [ ] **Step 1: Write the failing tests.** In `usePolling.test.ts`:

```ts
test("while hidden it keeps a slower beat when given one", async () => {
  vi.useFakeTimers();
  const read = vi.fn().mockResolvedValue(1);
  renderHook(() => usePolling(read, 5000, undefined, 30_000));
  await act(() => vi.advanceTimersByTimeAsync(0));
  act(() => setVisibility("hidden"));
  await act(() => vi.advanceTimersByTimeAsync(5000));
  expect(read).toHaveBeenCalledTimes(2); // the beat set while in view
  await act(() => vi.advanceTimersByTimeAsync(29_000));
  expect(read).toHaveBeenCalledTimes(2);
  await act(() => vi.advanceTimersByTimeAsync(1000));
  expect(read).toHaveBeenCalledTimes(3);
});

test("a tab shown again while a reading is out starts no second beat", async () => {
  vi.useFakeTimers();
  let answer: (value: number) => void = () => {};
  const read = vi.fn()
    .mockResolvedValueOnce(1)
    .mockImplementationOnce(() => new Promise<number>((done) => { answer = done; }))
    .mockResolvedValue(3);
  renderHook(() => usePolling(read, 5000));
  await act(() => vi.advanceTimersByTimeAsync(5000)); // the first answer, and the second reading out
  act(() => setVisibility("hidden"));
  act(() => setVisibility("visible"));
  await act(async () => answer(2));
  await act(() => vi.advanceTimersByTimeAsync(5000));
  expect(read).toHaveBeenCalledTimes(3);
  await act(() => vi.advanceTimersByTimeAsync(5000));
  expect(read).toHaveBeenCalledTimes(4);
});
```

In `api.test.ts`:

```ts
describe("the desk's own calls", () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    vi.resetModules();
    fetchMock.mockReset();
    fetchMock.mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({ taskId: 12 }) });
    vi.stubGlobal("fetch", fetchMock);
    vi.doMock("./telegram", () => ({ inTelegram: false, initData: null }));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.doUnmock("./telegram");
  });

  it("gives a task with its project, words and priority", async () => {
    const { giveTask } = await import("./api");
    await expect(giveTask("alm", "Fix the login timeout", "URGENT")).resolves.toEqual({ taskId: 12 });
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe("/api/tasks/new");
    expect(JSON.parse(init.body)).toEqual({ project: "alm", text: "Fix the login timeout", priority: "URGENT" });
  });
});
```

- [ ] **Step 2: Run them**

Run: `cd ui && npx vitest run src/desktop/usePolling.test.ts src/api.test.ts`
Expected: FAIL — the hidden beat reads nothing while hidden (2 calls, not 3); the second case counts 5 readings; `giveTask`
is not a function.

- [ ] **Step 3: Implement.** `api.ts`: add `planSeq?: number;` to `TaskRow` (beside `openQuestions`), then:

```ts
/** A task as the live reading lists it: a list row without its state, which the list it is in says. */
export type LiveTask = Omit<TaskRow, "state">;

export interface Live {
  version: string;
  name: string;
  running: number;
  queued: number;
  waitingOnYou: { taskId: number; title: string }[];
  waitingOnOthers: number;
  todayUsd: string;
  monthUsd: string;
  /** How many runs the bot runs at once (its scheduler.maxConcurrentRuns). */
  maxConcurrent: number;
  /** The projects the desktop's admin may give a task in. */
  projects: string[];
  tasks: { running: LiveTask[]; queued: LiveTask[]; awaitingApproval: LiveTask[] };
}

export interface Spend {
  from: string;
  to: string;
  /** Every day from `from` to `to`, oldest first; `usd` holds the projects that cost something that day. */
  days: { day: string; usd: Record<string, string> }[];
  /** Each project with finished runs in the window, costliest first; `unpriced` runs reported no cost (Codex, Gemini CLI). */
  projects: { project: string; usd: string; runs: number; unpriced: number }[];
  totalUsd: string;
}

export type Priority = "URGENT" | "NORMAL" | "LOW";

export const giveTask = (project: string, text: string, priority: Priority) =>
  post<{ taskId: number }>("/api/tasks/new", { project, text, priority });
export const getSpend = (days: number, signal?: AbortSignal) => post<Spend>("/api/tasks/spend", { days }, signal);
```

Every `Live` fixture gains `maxConcurrent: 2, projects: ["alm"], tasks: { running: [], queued: [], awaitingApproval: [] }`.

`usePolling.ts`:

```ts
/**
 * Reads now and again {@code intervalMs} after each answer while the page is in view (D-2). A hidden tab reads nothing
 * until it is shown, unless {@code hiddenIntervalMs} keeps a slower beat there (D-2b: notifications). A failure keeps
 * the last answer, so a restarting bot does not blank a page. One reading at a time: a tab shown again while one is out
 * waits for its answer, which sets the next beat.
 */
export function usePolling<T>(read: (signal: AbortSignal) => Promise<T>, intervalMs = 5000, key?: unknown,
                              hiddenIntervalMs?: number) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [nonce, setNonce] = useState(0);
  const latest = useRef(read);

  useEffect(() => {
    latest.current = read;
  });

  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let reading = false;
    const hidden = () => document.visibilityState === "hidden";
    const tick = async () => {
      if (reading || (hidden() && hiddenIntervalMs === undefined)) return;
      reading = true;
      try {
        const answer = await latest.current(controller.signal);
        if (controller.signal.aborted) return;
        setData(answer);
        setError(null);
      } catch (e) {
        if (controller.signal.aborted) return;
        setError(e instanceof ApiError ? e : new ApiError("unknown", String(e)));
      } finally {
        reading = false;
      }
      clearTimeout(timer);
      timer = setTimeout(() => void tick(), hidden() ? hiddenIntervalMs ?? intervalMs : intervalMs);
    };
    const shown = () => {
      if (!hidden()) {
        clearTimeout(timer);
        void tick();
      }
    };
    document.addEventListener("visibilitychange", shown);
    void tick();
    return () => {
      controller.abort();
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", shown);
    };
  }, [intervalMs, key, nonce, hiddenIntervalMs]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);
  return { data, error, reload };
}
```

- [ ] **Step 4: Run them**

Run: `cd ui && npx vitest run && npx tsc --noEmit`
Expected: PASS, type check clean.

- [ ] **Step 5: Commit** — "Read the desk's new routes, and keep a slower beat while hidden when asked".

---

### Task 6: Даалгавар өгөх

**Files:**
- Create: `ui/src/desktop/tasks/GiveTask.tsx`, `ui/src/desktop/tasks/GiveTask.test.tsx`
- Modify: `ui/src/desktop/tasks/TasksPage.tsx` (the button beside the title, its panel; a given task opens in the side panel)
- Modify: `ui/src/i18n/en.ts`, `mn.ts`

**Interfaces:**
- Consumes: `giveTask`, `Priority`, `Live.projects` (Task 5).
- Produces: `GiveTask({ projects: string[]; onGiven: (taskId: number) => void })`.

The spec's panel offers "the project (every project)". The list is the acting admin's groups' projects (`Live.projects`):
on a personal bot that is every project, and on a team `TaskService` refuses a project outside the admin's groups
(ADR 0020, the spec's own decision), so the page offers only what the bot accepts.

- [ ] **Step 1: Write the failing tests.** `GiveTask.test.tsx`:

```tsx
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../../api";
import GiveTask from "./GiveTask";

vi.mock("../../api", async (importOriginal) => ({ ...(await importOriginal<typeof import("../../api")>()), giveTask: vi.fn() }));

afterEach(() => vi.resetAllMocks());

test("gives the task of its one project, words and priority, once however often it is clicked", async () => {
  let answer: (given: { taskId: number }) => void = () => {};
  vi.mocked(api.giveTask).mockImplementation(() => new Promise((done) => { answer = done; }));
  const given = vi.fn();
  render(<GiveTask projects={["alm"]} onGiven={given} />);

  fireEvent.change(screen.getByRole("textbox", { name: "Task" }), { target: { value: "Fix the login timeout" } });
  fireEvent.click(screen.getByRole("radio", { name: "🔴 urgent" }));
  const give = screen.getByRole("button", { name: "Give" });
  fireEvent.click(give);
  fireEvent.click(give);
  answer({ taskId: 12 });

  await vi.waitFor(() => expect(given).toHaveBeenCalledWith(12));
  expect(api.giveTask).toHaveBeenCalledTimes(1);
  expect(api.giveTask).toHaveBeenCalledWith("alm", "Fix the login timeout", "URGENT");
});

test("a refusal shows in the panel, and nothing opens", async () => {
  vi.mocked(api.giveTask).mockRejectedValue(new api.ApiError("project_unavailable", "the project alm cannot take tasks now: no clone"));
  const given = vi.fn();
  render(<GiveTask projects={["alm"]} onGiven={given} />);

  fireEvent.change(screen.getByRole("textbox", { name: "Task" }), { target: { value: "Fix it" } });
  fireEvent.click(screen.getByRole("button", { name: "Give" }));

  expect(await screen.findByText("the project alm cannot take tasks now: no clone")).toBeInTheDocument();
  expect(given).not.toHaveBeenCalled();
});

test("with no project to give in, the panel says so", () => {
  render(<GiveTask projects={[]} onGiven={vi.fn()} />);

  expect(screen.getByText("None of your groups has a project to give a task in.")).toBeInTheDocument();
});
```

- [ ] **Step 2: Run them**

Run: `cd ui && npx vitest run src/desktop/tasks/GiveTask.test.tsx`
Expected: FAIL — `./GiveTask` does not exist.

- [ ] **Step 3: Implement.** Dictionaries (en, then mn):

```ts
  "give.open": "Give a task",
  "give.title": "Give a task",
  "give.project": "Project",
  "give.chooseProject": "Choose a project",
  "give.text": "Task",
  "give.textHint": "What should be done, in a few sentences",
  "give.priority": "Priority",
  "give.submit": "Give",
  "give.noProjects": "None of your groups has a project to give a task in.",
```

```ts
  "give.open": "Даалгавар өгөх",
  "give.title": "Даалгавар өгөх",
  "give.project": "Төсөл",
  "give.chooseProject": "Төсөл сонгох",
  "give.text": "Даалгавар",
  "give.textHint": "Юу хийхийг хэдэн өгүүлбэрээр",
  "give.priority": "Чухал зэрэг",
  "give.submit": "Өгөх",
  "give.noProjects": "Таны бүлгүүдэд даалгавар өгөх төсөл алга.",
```

`GiveTask.tsx`:

```tsx
import { Alert, Button, Flex, Input, Radio, Select } from "antd";
import { useState } from "react";
import { ApiError, giveTask, type Priority } from "../../api";
import { useT, type Key } from "../../i18n/i18n";

const PRIORITIES: Priority[] = ["URGENT", "NORMAL", "LOW"];

/**
 * Даалгавар өгөх (D-2b): a task of the desktop's admin, in one of their projects. The bot checks it again and answers a
 * refusal here only; given, the new task opens in the side panel. The button is busy until the bot answers, so a double
 * click gives one task.
 */
export default function GiveTask({ projects, onGiven }: { projects: string[]; onGiven: (taskId: number) => void }) {
  const t = useT();
  const [project, setProject] = useState<string | null>(projects.length === 1 ? projects[0] : null);
  const [text, setText] = useState("");
  const [priority, setPriority] = useState<Priority>("NORMAL");
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);

  if (projects.length === 0) return <Alert type="info" showIcon message={t("give.noProjects")} />;

  const give = async () => {
    if (busy || !project || !text.trim()) return;
    setBusy(true);
    setRefusal(null);
    try {
      onGiven((await giveTask(project, text.trim(), priority)).taskId);
    } catch (e) {
      setRefusal(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Flex vertical gap={12}>
      <Select aria-label={t("give.project")} value={project} placeholder={t("give.chooseProject")}
              options={projects.map((name) => ({ value: name, label: name }))} onChange={setProject} />
      <Input.TextArea aria-label={t("give.text")} autoSize={{ minRows: 4, maxRows: 12 }} value={text}
                      placeholder={t("give.textHint")} onChange={(e) => setText(e.target.value)} />
      <Radio.Group aria-label={t("give.priority")} optionType="button" value={priority} onChange={(e) => setPriority(e.target.value)}
                   options={PRIORITIES.map((value) => ({ value, label: t(`tasks.priority.${value}` as Key) }))} />
      {refusal && <Alert type="error" showIcon message={refusal} />}
      <Button type="primary" loading={busy} disabled={!project || !text.trim()} onClick={() => void give()}>
        {t("give.submit")}
      </Button>
    </Flex>
  );
}
```

`TasksPage.tsx` (imports `GiveTask`, `useDesktopStatus` from `../status`): a `giving` state; the title row becomes

```tsx
      <Flex justify="space-between" align="center" wrap gap={8}>
        <Typography.Title level={4} style={{ margin: 0 }}>{t("tasks.title")}</Typography.Title>
        <Button type="primary" onClick={() => setGiving(true)}>{t("give.open")}</Button>
      </Flex>
```

and beside the task's drawer:

```tsx
      <Drawer open={giving} onClose={() => setGiving(false)} placement="right" size={narrow ? "100%" : 520} destroyOnHidden
              title={t("give.title")}>
        {giving && <GiveTask projects={live?.projects ?? []} onGiven={(taskId) => {
          setGiving(false);
          setOpen(taskId);
          reload();
        }} />}
      </Drawer>
```

(`const { live } = useDesktopStatus();` at the top). The bot-not-running branch keeps its notice without the button.

- [ ] **Step 4: Run them**

Run: `cd ui && npx vitest run && npx tsc --noEmit`
Expected: PASS, type check clean.

- [ ] **Step 5: Commit** — "Give a task from the desktop, and open it in the side panel".

---

### Task 7: Spend per day as bars

**Files:**
- Create: `ui/src/desktop/overview/SpendChart.tsx`, `ui/src/desktop/overview/SpendChart.test.tsx`
- Modify: `ui/src/board.ts` (`PROJECT_COLOURS`), `ui/src/board.css`, `ui/src/i18n/en.ts`, `mn.ts`

**Interfaces:**
- Consumes: `getSpend`, `Spend` (Task 5).
- Produces: `SpendBars({ spend: Spend })` (pure) and the default `SpendChart()` (reads the last 30 days every 5 s while
  in view).

- [ ] **Step 1: Write the failing tests.** `SpendChart.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { expect, test } from "vitest";
import type { Spend } from "../../api";
import { SpendBars } from "./SpendChart";

const spend: Spend = {
  from: "2026-09-26",
  to: "2026-09-28",
  days: [
    { day: "2026-09-26", usd: { crm: "0.30" } },
    { day: "2026-09-27", usd: {} },
    { day: "2026-09-28", usd: { crm: "0.10", alm: "0.05" } },
  ],
  projects: [
    { project: "crm", usd: "0.40", runs: 3, unpriced: 0 },
    { project: "alm", usd: "0.05", runs: 1, unpriced: 0 },
    { project: "life", usd: "0.00", runs: 2, unpriced: 2 },
  ],
  totalUsd: "0.45",
};

test("each day is a bar stacked by project, the costliest day full height, and each project's total below", () => {
  const { container } = render(<SpendBars spend={spend} />);

  const bars = container.querySelectorAll(".spend-bar");
  expect(bars).toHaveLength(3);
  expect((bars[0].firstElementChild as HTMLElement).style.height).toBe("92px");
  expect(bars[1].children).toHaveLength(0);
  expect(screen.getByText("crm $0.40")).toBeInTheDocument();
  expect(screen.getByText("alm $0.05")).toBeInTheDocument();
  expect(screen.getByRole("img", { name: "Spend per day for 3 days, $0.45 in all" })).toBeInTheDocument();
});

test("a project on Codex or Gemini CLI is counted, not priced", () => {
  render(<SpendBars spend={spend} />);

  expect(screen.getByText("life: 2 runs, no cost reported")).toBeInTheDocument();
});

test("days without runs say so", () => {
  render(<SpendBars spend={{ ...spend, days: spend.days.map((day) => ({ ...day, usd: {} })), projects: [], totalUsd: "0.00" }} />);

  expect(screen.getByText("No runs in these 3 days.")).toBeInTheDocument();
});
```

- [ ] **Step 2: Run them**

Run: `cd ui && npx vitest run src/desktop/overview/SpendChart.test.tsx`
Expected: FAIL — `./SpendChart` does not exist.

- [ ] **Step 3: Implement.** Dictionaries (en, then mn):

```ts
  "spend.title": "Spend per day",
  "spend.window": "{days} days · ${usd}",
  "spend.day": "{day}: ${usd}",
  "spend.summary": "Spend per day for {days} days, ${usd} in all",
  "spend.unpriced": "{project}: {count} runs, no cost reported",
  "spend.none": "No runs in these {days} days.",
```

```ts
  "spend.title": "Өдөр бүрийн зардал",
  "spend.window": "{days} хоног · ${usd}",
  "spend.day": "{day}: ${usd}",
  "spend.summary": "{days} хоногийн өдөр бүрийн зардал, нийт ${usd}",
  "spend.unpriced": "{project}: {count} удаа ажилласан, зардал мэдэгдээгүй",
  "spend.none": "Энэ {days} хоногт ажилласан зүйл алга.",
```

`board.ts`:

```ts
/** The spend chart's colours, one per project in order of cost (D-2b); they repeat past six projects. */
export const PROJECT_COLOURS = [BOARD.amber, BOARD.green, "#7fb3d5", "#c39bd3", "#e59866", "#76d7c4"] as const;
```

`board.css`:

```css
/* Spend per day (D-2b): a bar per day, stacked by project, the costliest day at full height. */
.panel-head { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 8px; font-size: 13px; font-weight: 600; }
.spend-bars { display: flex; align-items: flex-end; gap: 3px; height: 92px; padding-top: 4px; border-bottom: 1px solid #24363a; }
.spend-bar { flex: 1; min-width: 0; display: flex; flex-direction: column-reverse; }
.spend-bar > span { display: block; }
.spend-legend { display: flex; flex-wrap: wrap; gap: 6px 14px; margin-top: 8px; font-size: 12px; opacity: 0.85; }
.spend-legend i { display: inline-block; width: 8px; height: 8px; border-radius: 2px; margin-right: 5px; }
```

`SpendChart.tsx`:

```tsx
import { Typography } from "antd";
import { getSpend, type Spend } from "../../api";
import { PROJECT_COLOURS } from "../../board";
import { useT } from "../../i18n/i18n";
import { usePolling } from "../usePolling";

const DAYS = 30;
const HEIGHT = 92;

const sum = (usd: Record<string, string>) => Object.values(usd).reduce((total, amount) => total + Number(amount), 0);

/** Spend per day as bars, one colour per project (D-2b); a project on Codex or Gemini CLI is counted, not priced. */
export function SpendBars({ spend }: { spend: Spend }) {
  const t = useT();
  if (spend.projects.length === 0) {
    return <Typography.Text type="secondary">{t("spend.none", { days: spend.days.length })}</Typography.Text>;
  }
  const colour = new Map(spend.projects.map((entry, i) => [entry.project, PROJECT_COLOURS[i % PROJECT_COLOURS.length]]));
  const highest = Math.max(0, ...spend.days.map((day) => sum(day.usd)));
  return (
    <>
      <div className="spend-bars" role="img" aria-label={t("spend.summary", { days: spend.days.length, usd: spend.totalUsd })}>
        {spend.days.map((day) => (
          <div key={day.day} className="spend-bar" title={t("spend.day", { day: day.day, usd: sum(day.usd).toFixed(2) })}>
            {Object.entries(day.usd).map(([project, usd]) => (
              <span key={project}
                    style={{ height: highest ? Math.round((Number(usd) / highest) * HEIGHT) : 0, background: colour.get(project) }} />
            ))}
          </div>
        ))}
      </div>
      <div className="spend-legend">
        {spend.projects.map((entry) => (
          <span key={entry.project}>
            <i style={{ background: colour.get(entry.project) }} aria-hidden="true" />
            {Number(entry.usd) === 0 && entry.unpriced > 0
              ? t("spend.unpriced", { project: entry.project, count: entry.unpriced })
              : `${entry.project} $${entry.usd}`}
          </span>
        ))}
      </div>
    </>
  );
}

/** The Overview's chart: the last 30 days, read with the rest of the page while it is in view. */
export default function SpendChart() {
  const t = useT();
  const { data } = usePolling((signal) => getSpend(DAYS, signal));
  return (
    <section className="panel-box">
      <div className="panel-head">
        <span>{t("spend.title")}</span>
        {data && <span className="hint">{t("spend.window", { days: DAYS, usd: data.totalUsd })}</span>}
      </div>
      {data && <SpendBars spend={data} />}
    </section>
  );
}
```

- [ ] **Step 4: Run them**

Run: `cd ui && npx vitest run && npx tsc --noEmit`
Expected: PASS, type check clean.

- [ ] **Step 5: Commit** — "Draw the spend per day as bars by project".

---

### Task 8: The live Тойм

**Files:**
- Create: `ui/src/desktop/tasks/TaskDrawer.tsx` (TasksPage's side panel, shared)
- Modify: `ui/src/desktop/tasks/TasksPage.tsx` (uses `TaskDrawer`)
- Create: `ui/src/desktop/overview/LiveBoard.tsx`, `ui/src/desktop/overview/LiveBoard.test.tsx`
- Modify: `ui/src/OverviewPage.tsx` (D-1's cards as components; the `live` layout), `ui/src/OverviewPage.test.tsx`
- Modify: `ui/src/desktop/Shell.tsx` (export `ServiceLamp` and `ChecksLamp`), `ui/src/App.tsx`
- Modify: `ui/src/board.css`, `ui/src/i18n/en.ts`, `mn.ts`

**Interfaces:**
- Consumes: `Live.tasks`, `Live.maxConcurrent`, `LiveTask.planSeq`, `approvePlan` (Tasks 4, 5); `SpendChart` (Task 7).
- Produces: `TaskDrawer({ taskId: number | null; onClose; onChanged; navigate })`; `LiveBoard({ navigate })`;
  `OverviewPage({ installAndStop?, live?, navigate? })` — `live` with `navigate` is the desktop's Тойм; without, D-1's
  layout (the Mini App's) is unchanged.

- [ ] **Step 1: Write the failing tests.** `LiveBoard.test.tsx`:

```tsx
import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import * as api from "../../api";
import { DesktopProviders } from "../status";
import LiveBoard from "./LiveBoard";

vi.mock("../../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../api")>()),
  getOverview: vi.fn(),
  getLive: vi.fn(),
  getSpend: vi.fn(),
  approvePlan: vi.fn(),
  getTaskDetail: vi.fn(),
}));

const overview: api.Overview = {
  configured: true, version: "0.2.0", name: "acme", configFile: "/c", stateDir: "/s",
  service: { name: "systemd user service dispatch.service", installed: true, running: true, detail: "active", notes: [] },
  findings: [],
};
const row = (taskId: number, extra: Partial<api.LiveTask>): api.LiveTask => ({
  taskId, project: "alm", title: `Task ${taskId}`, priority: "NORMAL", requester: "Bold", mine: true, actions: [], ...extra,
});
const live: api.Live = {
  version: "0.2.0", name: "acme", running: 1, queued: 1,
  waitingOnYou: [{ taskId: 14, title: "Task 14" }, { taskId: 15, title: "Task 15" }], waitingOnOthers: 1,
  todayUsd: "3.40", monthUsd: "41.20", maxConcurrent: 2, projects: ["alm"],
  tasks: {
    running: [row(13, { kind: "EXECUTE", startedAt: new Date(Date.now() - 12 * 60_000).toISOString(), mine: false, requester: "Ali" })],
    queued: [row(12, { queuedAt: new Date().toISOString() })],
    awaitingApproval: [
      row(14, { planSeq: 2, actions: ["approve", "correct", "reject"] }),
      row(15, { planSeq: 1, openQuestions: 1, actions: ["answer"] }),
      row(16, { mine: false, requester: "Ali" }),
    ],
  },
};

beforeEach(() => {
  vi.mocked(api.getOverview).mockResolvedValue(overview);
  vi.mocked(api.getSpend).mockResolvedValue({ from: "2026-08-30", to: "2026-09-28", days: [], projects: [], totalUsd: "0.00" });
});
afterEach(() => vi.resetAllMocks());

function renderBoard() {
  render(<DesktopProviders><LiveBoard navigate={vi.fn()} /></DesktopProviders>);
}

test("the counters, what waits on you with its one action, and what runs out of how many at once", async () => {
  vi.mocked(api.getLive).mockResolvedValue(live);
  renderBoard();

  expect(await screen.findByText("$41.20")).toBeInTheDocument();
  expect(screen.getByText("$3.40")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "To answer" })).toBeInTheDocument();
  expect(screen.queryByText(/Task 16/)).not.toBeInTheDocument();
  expect(screen.getByText("1 / 2 at once")).toBeInTheDocument();
  expect(screen.getByText(/Task 12/)).toBeInTheDocument();
});

test("Approve approves the plan the reading showed; a plan replaced since says why and is read again", async () => {
  vi.mocked(api.getLive).mockResolvedValue(live);
  vi.mocked(api.approvePlan).mockRejectedValue(new api.ApiError("stale", "The plan was replaced; read it again"));
  renderBoard();

  fireEvent.click(await screen.findByRole("button", { name: "Approve" }));

  expect(await screen.findByText("The plan was replaced; read it again")).toBeInTheDocument();
  expect(api.approvePlan).toHaveBeenCalledWith(14, 2);
  await vi.waitFor(() => expect(api.getLive).toHaveBeenCalledTimes(2));
});

test("a row opens its task in the side panel", async () => {
  vi.mocked(api.getLive).mockResolvedValue(live);
  vi.mocked(api.getTaskDetail).mockReturnValue(new Promise(() => {}));
  renderBoard();

  fireEvent.click(await screen.findByRole("button", { name: /Task 13/ }));

  expect(within(await screen.findByRole("dialog")).getByText("#13")).toBeInTheDocument();
});
```

In `OverviewPage.test.tsx` (its `serve` stub), a desktop renderer and two cases:

```tsx
const live: Live = { version: "0.1.0", name: "bold", running: 0, queued: 0, waitingOnYou: [], waitingOnOthers: 0,
  todayUsd: "0.00", monthUsd: "0.00", maxConcurrent: 2, projects: ["alm"], tasks: { running: [], queued: [], awaitingApproval: [] } };
const spend = { from: "2026-08-30", to: "2026-09-28", days: [], projects: [], totalUsd: "0.00" };

function renderDesktop() {
  render(
    <LanguageProvider storage={remembered} languages={["en-US"]}>
      <DesktopProviders><OverviewPage installAndStop live navigate={vi.fn()} /></DesktopProviders>
    </LanguageProvider>,
  );
}

test("the desktop's Overview puts the service and the checks on a line each, opening D-1's full panels", async () => {
  serve({ "/api/overview": { body: overview }, "/api/live": { body: live }, "/api/tasks/spend": { body: spend } });
  renderDesktop();

  expect(await screen.findByText("Nothing waits on you.")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Restart" })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Details" }));
  expect(await screen.findByRole("button", { name: "Restart" })).toBeInTheDocument();
});

test("with the bot stopped the desktop's Overview says so beside the service and its Restart", async () => {
  serve({ "/api/overview": { body: overview },
          "/api/live": { status: 503, body: { error: "bot_not_running", message: "The bot is not running" } } });
  renderDesktop();

  expect(await screen.findByText("The bot is not running: start the service to see what it does.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Restart" })).toBeInTheDocument();
});
```

(imports: `DesktopProviders`, `type Live`.)

- [ ] **Step 2: Run them**

Run: `cd ui && npx vitest run src/desktop/overview/LiveBoard.test.tsx src/OverviewPage.test.tsx`
Expected: FAIL — `./LiveBoard` does not exist; `OverviewPage` has no `live` layout.

- [ ] **Step 3: Implement.** Dictionaries (en, then mn):

```ts
  "overview.live.running": "Running",
  "overview.live.waitingOnYou": "Waiting on you",
  "overview.live.today": "Today",
  "overview.live.month": "This month",
  "overview.live.queued": "Queued",
  "overview.live.runningOf": "{count} / {max} at once",
  "overview.live.nothingWaits": "Nothing waits on you.",
  "overview.live.nothingRuns": "Nothing is running.",
  "overview.live.questions": "{count} questions",
  "overview.live.open": "Open",
  "overview.live.botNotRunning": "The bot is not running: start the service to see what it does.",
  "overview.details": "Details",
  "overview.see": "See",
```

```ts
  "overview.live.running": "Явж байна",
  "overview.live.waitingOnYou": "Таныг хүлээж",
  "overview.live.today": "Өнөөдөр",
  "overview.live.month": "Энэ сар",
  "overview.live.queued": "Дараалалд",
  "overview.live.runningOf": "{count} / {max} зэрэг",
  "overview.live.nothingWaits": "Таныг хүлээж буй зүйл алга.",
  "overview.live.nothingRuns": "Юу ч явахгүй байна.",
  "overview.live.questions": "{count} асуулт",
  "overview.live.open": "Нээх",
  "overview.live.botNotRunning": "Бот ажиллахгүй байна: юу хийж байгааг харахын тулд сервисийг эхлүүлнэ үү.",
  "overview.details": "Дэлгэрэнгүй",
  "overview.see": "Харах",
```

`TaskDrawer.tsx` — TasksPage's drawer, moved; TasksPage renders
`<TaskDrawer taskId={open} onClose={() => setOpen(null)} onChanged={reload} navigate={navigate} />`:

```tsx
import { Drawer } from "antd";
import { useNarrow } from "../../useNarrow";
import TaskView from "./TaskView";

/** One task in the side panel (D-2): the Tasks page's and the Overview's; its Details opens the task's own page. */
export default function TaskDrawer({ taskId, onClose, onChanged, navigate }: {
  taskId: number | null;
  onClose: () => void;
  onChanged: () => void;
  navigate: (path: string) => void;
}) {
  const narrow = useNarrow();
  return (
    <Drawer open={taskId !== null} onClose={onClose} placement="right" size={narrow ? "100%" : 520} destroyOnHidden
            title={taskId === null ? null : `#${taskId}`}>
      {taskId !== null && (
        <TaskView key={taskId} taskId={taskId} layout="panel" onChanged={onChanged} onDetails={() => navigate(`/tasks/${taskId}`)} />
      )}
    </Drawer>
  );
}
```

`LiveBoard.tsx`:

```tsx
import { Alert, Button, Spin, Typography } from "antd";
import { useState } from "react";
import { ApiError, approvePlan, type LiveTask } from "../../api";
import { useT, type Key } from "../../i18n/i18n";
import { Lamp } from "../Shell";
import { useDesktopStatus } from "../status";
import { age } from "../tasks/groups";
import TaskDrawer from "../tasks/TaskDrawer";
import SpendChart from "./SpendChart";

function Counter({ colour, label, value }: { colour?: "green" | "amber" | "quiet"; label: string; value: string }) {
  return (
    <div className="counter">
      <div className="counter-label">{colour ? <Lamp colour={colour}>{label}</Lamp> : label}</div>
      <div className="counter-value">{value}</div>
    </div>
  );
}

/** A task of yours waiting on you, with its one action: Approve a plan that asks nothing, else open it to answer. */
function WaitingRow({ row, onOpen, onChanged }: { row: LiveTask; onOpen: (taskId: number) => void; onChanged: () => void }) {
  const t = useT();
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const questions = row.openQuestions ?? 0;
  const approvable = questions === 0 && row.planSeq !== undefined && row.actions.includes("approve");
  const approve = async () => {
    setBusy(true);
    setRefusal(null);
    try {
      await approvePlan(row.taskId, row.planSeq as number);
    } catch (e) {
      // A plan replaced since the reading, or a task that moved on: the bot's own words, then a fresh reading.
      setRefusal(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy(false);
      onChanged();
    }
  };
  return (
    <div className="overview-item">
      <span className="mono task-id">#{row.taskId}</span>
      <button type="button" className="overview-title" onClick={() => onOpen(row.taskId)}>
        {row.title} <span className="hint">· {row.project}{questions > 0 && ` · ${t("overview.live.questions", { count: questions })}`}</span>
      </button>
      {approvable
        ? <Button size="small" type="primary" loading={busy} onClick={() => void approve()}>{t("tasks.approve")}</Button>
        : <Button size="small" onClick={() => onOpen(row.taskId)}>{t(questions > 0 ? "tasks.state.answer" : "overview.live.open")}</Button>}
      {refusal && <Alert className="overview-refusal" type="error" showIcon message={refusal} />}
    </div>
  );
}

/** A running or queued task: its kind and how long it has run or waited. */
function RunningRow({ row, now, onOpen }: { row: LiveTask; now: Date; onOpen: (taskId: number) => void }) {
  const t = useT();
  const since = age(row.startedAt ?? row.queuedAt, now);
  return (
    <div className="overview-item">
      <span className="mono task-id">#{row.taskId}</span>
      <button type="button" className="overview-title" onClick={() => onOpen(row.taskId)}>
        {row.title} <span className="hint">· {row.project}{row.requester && ` · ${row.requester}`}</span>
      </button>
      <span className="hint">
        {row.kind && t(`tasks.kind.${row.kind}` as Key)}{since && ` · ${t(`tasks.ago.${since.unit}` as Key, { n: since.n })}`}
      </span>
    </div>
  );
}

/**
 * The live Тойм (D-2b): what runs, what waits on you with its one action, and what it costs, from the reading the strip's
 * lamps use. The page shows it only while the bot answers.
 */
export default function LiveBoard({ navigate }: { navigate: (path: string) => void }) {
  const t = useT();
  const { live, liveError, reloadLive } = useDesktopStatus();
  const [open, setOpen] = useState<number | null>(null);
  if (!live) return liveError ? <Typography.Text type="danger">{liveError.message}</Typography.Text> : <Spin />;
  const now = new Date();
  const waiting = live.tasks.awaitingApproval.filter((row) => row.mine);
  const { running, queued } = live.tasks;
  return (
    <>
      <div className="counters">
        <Counter colour={running.length > 0 ? "green" : "quiet"} label={t("overview.live.running")} value={String(running.length)} />
        <Counter colour={waiting.length > 0 ? "amber" : "quiet"} label={t("overview.live.waitingOnYou")} value={String(waiting.length)} />
        <Counter label={t("overview.live.today")} value={`$${live.todayUsd}`} />
        <Counter label={t("overview.live.month")} value={`$${live.monthUsd}`} />
      </div>
      <div className="overview-cols">
        <div className="overview-col">
          <section className="panel-box">
            <div className="panel-head"><span>{t("overview.live.waitingOnYou")}</span><span className="hint">{waiting.length}</span></div>
            {waiting.length === 0
              ? <Typography.Text type="secondary">{t("overview.live.nothingWaits")}</Typography.Text>
              : waiting.map((row) => <WaitingRow key={row.taskId} row={row} onOpen={setOpen} onChanged={reloadLive} />)}
          </section>
          <section className="panel-box">
            <div className="panel-head">
              <span>{t("overview.live.running")}</span>
              <span className="hint">{t("overview.live.runningOf", { count: running.length, max: live.maxConcurrent })}</span>
            </div>
            {running.length === 0
              ? <Typography.Text type="secondary">{t("overview.live.nothingRuns")}</Typography.Text>
              : running.map((row) => <RunningRow key={row.taskId} row={row} now={now} onOpen={setOpen} />)}
            {queued.length > 0 && (
              <>
                <div className="panel-head overview-queued"><span>{t("overview.live.queued")}</span><span className="hint">{queued.length}</span></div>
                {queued.map((row) => <RunningRow key={row.taskId} row={row} now={now} onOpen={setOpen} />)}
              </>
            )}
          </section>
        </div>
        <div className="overview-col"><SpendChart /></div>
      </div>
      <TaskDrawer taskId={open} onClose={() => setOpen(null)} onChanged={reloadLive} navigate={navigate} />
    </>
  );
}
```

`board.css`:

```css
/* The live Тойм (D-2b): four counters, then what waits and runs beside the spend; one column on a narrow screen. */
.counters { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.counter { background: #172427; border: 1px solid #24363a; border-radius: 6px; padding: 10px 12px; }
.counter-label { font-size: 12px; opacity: 0.8; }
.counter-value { font-size: 22px; font-weight: 700; font-variant-numeric: tabular-nums; }
.overview-cols { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr); gap: 12px; align-items: start; }
.overview-col { display: grid; gap: 12px; }
.overview-item { display: grid; grid-template-columns: 44px minmax(0, 1fr) auto; gap: 8px; align-items: center; padding: 6px 0;
                 border-top: 1px solid rgba(128, 128, 128, 0.15); }
.overview-item:first-of-type { border-top: 0; }
.overview-title { background: none; border: 0; padding: 0; color: inherit; font: inherit; text-align: left; cursor: pointer;
                  overflow-wrap: anywhere; }
.overview-refusal { grid-column: 1 / -1; }
.overview-queued { margin-top: 10px; }
.overview-foot { display: flex; flex-wrap: wrap; gap: 8px 24px; align-items: center; }
@media (max-width: 900px) {
  .overview-cols { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 640px) {
  .counters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
```

`Shell.tsx`: `export function ServiceLamp…` and `export function ChecksLamp…` (no other change).

`OverviewPage.tsx`: move the three cards into components with the same markup and hooks —
`DispatchCard({ overview })`, `ServiceCard({ overview, installAndStop, reload })` (it owns `RestartContext`'s `mark`,
`useRestart`, the two `useAction`s and the restart effect) and `ChecksCard({ overview, loading, reload })` — and render
them in order where the page rendered them. Add the props `live = false` and `navigate?: (path: string) => void`, and
before D-1's layout:

```tsx
  if (live && navigate) return <LiveOverview overview={overview} loading={loading} reload={reload} navigate={navigate} />;
```

with

```tsx
/** The desktop's Тойм (D-2b): the live board, then the service and the checks one line each, opening D-1's panels. */
function LiveOverview({ overview, loading, reload, navigate }: {
  overview: Overview;
  loading: boolean;
  reload: () => Promise<void>;
  navigate: (path: string) => void;
}) {
  const t = useT();
  const narrow = useNarrow();
  const { botRunning } = useDesktopStatus();
  const [panel, setPanel] = useState<"service" | "checks" | null>(null);
  return (
    <Space direction="vertical" size="large" style={{ width: "100%" }}>
      <Flex justify="space-between" align="center" wrap gap={8}>
        <Typography.Title level={4} style={{ margin: 0 }}>{t("overview.title")}</Typography.Title>
      </Flex>
      {botRunning === false
        ? (
          <>
            <Alert type="info" showIcon message={t("overview.live.botNotRunning")} />
            <ServiceCard overview={overview} installAndStop reload={reload} />
          </>
        )
        : <LiveBoard navigate={navigate} />}
      <div className="panel-box overview-foot">
        <span><ServiceLamp overview={overview} />{" "}
          <Button type="link" size="small" onClick={() => setPanel("service")}>{t("overview.details")}</Button></span>
        <span><ChecksLamp overview={overview} />{" "}
          <Button type="link" size="small" onClick={() => setPanel("checks")}>{t("overview.see")}</Button></span>
      </div>
      <Drawer open={panel !== null} onClose={() => setPanel(null)} placement="right" size={narrow ? "100%" : 520} destroyOnHidden
              title={panel === "checks" ? t("overview.checks") : t("overview.service")}>
        {panel === "service" && (
          <Space direction="vertical" size="large" style={{ width: "100%" }}>
            <DispatchCard overview={overview} />
            <ServiceCard overview={overview} installAndStop reload={reload} />
          </Space>
        )}
        {panel === "checks" && <ChecksCard overview={overview} loading={loading} reload={reload} />}
      </Drawer>
    </Space>
  );
}
```

(imports: `Drawer`, `Flex` from antd; `useState`; `type Overview`; `ServiceLamp`, `ChecksLamp` from `./desktop/Shell`;
`LiveBoard`; `useNarrow`.) `App.tsx`, in `WebUi`'s page choice before `<Page path={page} />`:

```tsx
        : page === "/" ? <OverviewPage installAndStop live navigate={navigate} />
```

- [ ] **Step 4: Run them**

Run: `cd ui && npx vitest run && npx tsc --noEmit`
Expected: PASS (D-1's Overview tests unchanged), type check clean.

- [ ] **Step 5: Commit** — "Make Тойм a live board: what waits on you, what runs, what it costs".

---

### Task 9: Notifications

**Files:**
- Create: `ui/src/desktop/notify.ts`, `ui/src/desktop/notify.test.tsx`
- Modify: `ui/src/desktop/status.tsx` (the switch's state; the 30-second hidden beat; a notice per newly waiting task)
- Modify: `ui/src/OverviewPage.tsx` (the Мэдэгдэл switch in `LiveOverview`'s title row)
- Modify: `ui/src/i18n/en.ts`, `mn.ts`

**Interfaces:**
- Consumes: `usePolling`'s `hiddenIntervalMs` (Task 5); `LiveOverview` (Task 8).
- Produces: `notificationsSupported()`, `notificationsOn()`, `setNotifications(on): Promise<Switched>` with
  `type Switched = "on" | "off" | "blocked" | "unsupported"`, `newlyWaiting(before, now)`, `showNotice(text, taskId)`,
  `openTaskPage(taskId)`; `DesktopStatus` gains `notifications: boolean` and `setNotifications(on): Promise<Switched>`.

- [ ] **Step 1: Write the failing tests.** `notify.test.tsx`:

```tsx
import { act, render } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import * as api from "../api";
import { newlyWaiting, notificationsOn, setNotifications } from "./notify";
import { DesktopProviders } from "./status";

vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getOverview: vi.fn(),
  getLive: vi.fn(),
}));

let visibility: DocumentVisibilityState = "visible";
Object.defineProperty(document, "visibilityState", { configurable: true, get: () => visibility });
function setVisibility(next: DocumentVisibilityState) {
  visibility = next;
  document.dispatchEvent(new Event("visibilitychange"));
}

class FakeNotification {
  static permission: NotificationPermission = "default";
  static answer: NotificationPermission = "granted";
  static requestPermission = vi.fn(async () => {
    FakeNotification.permission = FakeNotification.answer;
    return FakeNotification.answer;
  });
  static shown: FakeNotification[] = [];
  onclick: (() => void) | null = null;
  close = vi.fn();
  constructor(readonly title: string) {
    FakeNotification.shown.push(this);
  }
}

const overview: api.Overview = {
  configured: true, version: "0.2.0", name: "acme", configFile: "/c", stateDir: "/s",
  service: { name: "systemd user service dispatch.service", installed: true, running: true, detail: "active", notes: [] },
  findings: [],
};
const live: api.Live = {
  version: "0.2.0", name: "acme", running: 0, queued: 0, waitingOnYou: [{ taskId: 14, title: "Fix the login timeout" }],
  waitingOnOthers: 0, todayUsd: "0.00", monthUsd: "0.00", maxConcurrent: 2, projects: ["alm"],
  tasks: { running: [], queued: [], awaitingApproval: [] },
};

beforeEach(() => {
  FakeNotification.permission = "default";
  FakeNotification.answer = "granted";
  FakeNotification.shown = [];
  FakeNotification.requestPermission.mockClear();
  vi.stubGlobal("Notification", FakeNotification);
  window.localStorage.clear();
  vi.mocked(api.getOverview).mockResolvedValue(overview);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  vi.mocked(api.getLive).mockReset();
  setVisibility("visible");
  window.history.pushState(null, "", "/");
});

test("the switch asks the browser once and is remembered in it", async () => {
  expect(await setNotifications(true)).toBe("on");
  expect(await setNotifications(true)).toBe("on");

  expect(FakeNotification.requestPermission).toHaveBeenCalledTimes(1);
  expect(notificationsOn()).toBe(true);
});

test("a browser that refuses leaves the switch off and says why", async () => {
  FakeNotification.answer = "denied";

  expect(await setNotifications(true)).toBe("blocked");
  expect(notificationsOn()).toBe(false);
});

test("a browser without notifications cannot turn them on", async () => {
  vi.stubGlobal("Notification", undefined);

  expect(await setNotifications(true)).toBe("unsupported");
});

test("only a task that starts waiting is news, not those waiting when the page opened", () => {
  expect(newlyWaiting(null, [{ taskId: 14, title: "a" }])).toEqual([]);
  expect(newlyWaiting([14], [{ taskId: 14, title: "a" }, { taskId: 15, title: "b" }])).toEqual([{ taskId: 15, title: "b" }]);
});

test("a task that starts waiting on you is shown once, and a click opens its page", async () => {
  vi.useFakeTimers();
  FakeNotification.permission = "granted";
  window.localStorage.setItem("dispatch.notify", "on");
  vi.mocked(api.getLive).mockResolvedValueOnce(live)
    .mockResolvedValue({ ...live, waitingOnYou: [...live.waitingOnYou, { taskId: 15, title: "Excel import" }] });
  render(<DesktopProviders><p>page</p></DesktopProviders>);

  await act(() => vi.advanceTimersByTimeAsync(0));
  expect(FakeNotification.shown).toHaveLength(0);
  await act(() => vi.advanceTimersByTimeAsync(5000));
  expect(FakeNotification.shown.map((notice) => notice.title)).toEqual(["#15 is waiting on you: Excel import"]);
  await act(() => vi.advanceTimersByTimeAsync(5000));
  expect(FakeNotification.shown).toHaveLength(1);

  act(() => FakeNotification.shown[0].onclick?.());
  expect(window.location.pathname).toBe("/tasks/15");
});

test("with notifications on, a hidden tab still reads every 30 seconds; with them off, not at all", async () => {
  vi.useFakeTimers();
  vi.mocked(api.getLive).mockResolvedValue(live);
  const { unmount } = render(<DesktopProviders><p>page</p></DesktopProviders>);
  await act(() => vi.advanceTimersByTimeAsync(0));
  act(() => setVisibility("hidden"));
  await act(() => vi.advanceTimersByTimeAsync(40_000));
  expect(api.getLive).toHaveBeenCalledTimes(1);
  unmount();
  act(() => setVisibility("visible"));

  vi.mocked(api.getLive).mockClear();
  FakeNotification.permission = "granted";
  window.localStorage.setItem("dispatch.notify", "on");
  render(<DesktopProviders><p>page</p></DesktopProviders>);
  await act(() => vi.advanceTimersByTimeAsync(0));
  act(() => setVisibility("hidden"));
  await act(() => vi.advanceTimersByTimeAsync(35_000));
  expect(api.getLive).toHaveBeenCalledTimes(3); // 0 s, 5 s (the beat set in view), 35 s
});
```

- [ ] **Step 2: Run them**

Run: `cd ui && npx vitest run src/desktop/notify.test.tsx`
Expected: FAIL — `./notify` does not exist.

- [ ] **Step 3: Implement.** Dictionaries (en, then mn):

```ts
  "notify.switch": "Notifications",
  "notify.waiting": "#{id} is waiting on you: {title}",
  "notify.blocked": "This browser blocks notifications from this page; allow them in its site settings.",
  "notify.unsupported": "This browser cannot show notifications.",
```

```ts
  "notify.switch": "Мэдэгдэл",
  "notify.waiting": "#{id} таныг хүлээж байна: {title}",
  "notify.blocked": "Энэ хөтөч энэ хуудсаас мэдэгдэл хаасан байна; сайтын тохиргооноос зөвшөөрнө үү.",
  "notify.unsupported": "Энэ хөтөч мэдэгдэл харуулж чадахгүй.",
```

`notify.ts`:

```ts
const KEY = "dispatch.notify";
let remembered = false;

export type Switched = "on" | "off" | "blocked" | "unsupported";

/** Whether this browser has notifications at all (127.0.0.1 is a secure context, so `dispatch ui` qualifies). */
export function notificationsSupported(): boolean {
  return typeof window.Notification === "function";
}

/** The Мэдэгдэл switch (D-2b): on only when this browser allows them and the owner turned them on here. */
export function notificationsOn(): boolean {
  if (!notificationsSupported() || Notification.permission !== "granted") return false;
  try {
    return window.localStorage.getItem(KEY) === "on" || remembered;
  } catch {
    return remembered;
  }
}

/** Turns the switch on — asking the browser the first time — or off; says what it now is, and why not when it cannot. */
export async function setNotifications(on: boolean): Promise<Switched> {
  if (!on) {
    remember(false);
    return "off";
  }
  if (!notificationsSupported()) return "unsupported";
  const permission = Notification.permission === "default" ? await Notification.requestPermission() : Notification.permission;
  if (permission !== "granted") {
    remember(false);
    return "blocked";
  }
  remember(true);
  return "on";
}

function remember(on: boolean) {
  remembered = on;
  try {
    window.localStorage.setItem(KEY, on ? "on" : "off");
  } catch {
    // Remembered for this visit only.
  }
}

/** The tasks now waiting on you that the reading before did not hold; the first reading is where the page starts, not news. */
export function newlyWaiting(before: number[] | null, now: { taskId: number; title: string }[]) {
  if (before === null) return [];
  return now.filter((task) => !before.includes(task.taskId));
}

/** Opens a task's page from outside the page's router (a notification's click): the router follows popstate. */
export function openTaskPage(taskId: number) {
  window.history.pushState(null, "", `/tasks/${taskId}`);
  window.dispatchEvent(new PopStateEvent("popstate"));
}

/** Shows one notice; its click opens the task's page. False when the browser would not show it. */
export function showNotice(text: string, taskId: number): boolean {
  if (!notificationsOn()) return false;
  try {
    const notice = new Notification(text, { tag: `dispatch-task-${taskId}` });
    notice.onclick = () => {
      window.focus();
      openTaskPage(taskId);
      notice.close();
    };
    return true;
  } catch (e) {
    // Some browsers (Chrome on Android) show notifications only through a service worker, which this page has not.
    console.warn("dispatch: the browser would not show a notification", e);
    return false;
  }
}
```

`status.tsx`: `DesktopStatus` gains `notifications: boolean` and `setNotifications: (on: boolean) => Promise<Switched>`;
`NO_LIVE` gains `notifications: false, setNotifications: async () => "unsupported" as const`. In `LiveReading`:

```tsx
  const t = useT();
  const [notifying, setNotifying] = useState(notificationsOn);
  const { data, error, reload } = usePolling(getLive, 5000, undefined, notifying ? HIDDEN_BEAT_MS : undefined);
  const seen = useRef<number[] | null>(null);

  useEffect(() => {
    if (!data) return;
    const fresh = newlyWaiting(seen.current, data.waitingOnYou);
    seen.current = data.waitingOnYou.map((task) => task.taskId);
    if (!notifying) return;
    for (const task of fresh) {
      if (!showNotice(t("notify.waiting", { id: task.taskId, title: task.title }), task.taskId)) {
        // A browser that would not show one (its permission taken back) turns the switch off rather than fail every beat.
        void setNotifications(false).then(() => setNotifying(false));
        return;
      }
    }
  }, [data, notifying, t]);

  const switchNotifications = useCallback(async (on: boolean) => {
    const now = await setNotifications(on);
    setNotifying(now === "on");
    return now;
  }, []);
```

with `const HIDDEN_BEAT_MS = 30_000;` at the top, and `notifications: notifying, setNotifications: switchNotifications` in
the value (imports: `useCallback`, `useRef`, `useState`; `useT`; the `notify` functions and `Switched`).

`OverviewPage.tsx`, in `LiveOverview`'s title row after the title:

```tsx
        <NotificationSwitch />
```

with

```tsx
/** Мэдэгдэл (D-2b): asks the browser once, and says why when it cannot. */
function NotificationSwitch() {
  const t = useT();
  const { notifications, setNotifications } = useDesktopStatus();
  const [why, setWhy] = useState<string | null>(null);
  const change = async (on: boolean) => {
    const now = await setNotifications(on);
    setWhy(now === "blocked" ? t("notify.blocked") : now === "unsupported" ? t("notify.unsupported") : null);
  };
  return (
    <Flex align="center" gap={8} wrap>
      <Typography.Text type="secondary">{t("notify.switch")}</Typography.Text>
      <Switch aria-label={t("notify.switch")} checked={notifications} onChange={(on) => void change(on)} />
      {why && <Typography.Text type="warning">{why}</Typography.Text>}
    </Flex>
  );
}
```

(import `Switch` from antd.)

- [ ] **Step 4: Run them**

Run: `cd ui && npx vitest run && npx tsc --noEmit`
Expected: PASS, type check clean.

- [ ] **Step 5: Commit** — "Tell the owner when a task starts waiting on them".

---

### Task 10: Carried from D-2a's review (the owner may strike this task)

Three of D-2a's deferred minors touch these pages; each is small and pinned by its own test.

**Files:**
- Modify: `ui/src/desktop/tasks/TaskPage.tsx` (the bot-down notice gets the Overview button), `TaskPage.test.tsx`
- Modify: `src/main/java/dispatch/ui/DeskProxy.java` (a stale file's port: a 401 or an answer that is no JSON),
  `src/test/java/dispatch/ui/DeskProxyTest.java`
- Modify: `ui/src/desktop/tasks/TasksPage.tsx` (the filters' "all" is `""`, not `null`), `TasksPage.test.tsx`

**Interfaces:** none new.

- [ ] **Step 1: Write the failing tests.** `TaskPage.test.tsx`:

```tsx
test("with the bot stopped the task's page says so and offers the Overview", async () => {
  vi.mocked(api.getTaskDetail).mockRejectedValue(new api.ApiError("bot_not_running", "The bot is not running"));
  vi.mocked(api.taskTimeline).mockRejectedValue(new api.ApiError("bot_not_running", "The bot is not running"));
  vi.mocked(api.getLogs).mockResolvedValue(logs);
  const navigate = vi.fn();
  render(<TaskPage taskId={14} navigate={navigate} />);

  fireEvent.click(await screen.findByRole("button", { name: "Open the overview" }));

  expect(navigate).toHaveBeenCalledWith("/");
});
```

(`logs` is the file's fixture.) `DeskProxyTest`:

```java
    @Test
    void anotherBotOnAStaleFilesPortIsABotThatIsNotRunning() throws IOException {
        try (DeskServer other = DeskServer.start(dir.resolve("other"), db, tasks, groups, clock, "0.3.0", "other", 2)) {
            new DeskFile(other.port(), DeskFile.newToken(), "0.3.0", "acme").write(state);

            UiServer.Forwarded answer = proxy.forward("/api/live", "GET", new byte[0], Language.EN, null);

            assertEquals(503, answer.status());
            assertEquals("bot_not_running", Json.read(new String(answer.json(), UTF_8)).path("error").asText());
        }
    }

    @Test
    void aProgramThatIsNoBotOnAStaleFilesPortIsABotThatIsNotRunning() throws IOException {
        HttpServer web = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        web.createContext("/", exchange -> {
            byte[] page = "<html>hello</html>".getBytes(UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        });
        web.start();
        try {
            new DeskFile(web.getAddress().getPort(), DeskFile.newToken(), "0.3.0", "acme").write(state);

            assertEquals(503, proxy.forward("/api/live", "GET", new byte[0], Language.EN, null).status());
        } finally {
            web.stop(0);
        }
    }
```

(imports: `com.sun.net.httpserver.HttpServer`, `java.net.InetSocketAddress`.) `TasksPage.test.tsx`:

```tsx
test("the list's filters give antd nothing to warn about", async () => {
  const warned = vi.spyOn(console, "error").mockImplementation(() => {});
  vi.mocked(api.listTasks).mockResolvedValue({ tasks: [] });
  render(<TasksPage navigate={vi.fn()} />);

  await screen.findByText("No tasks here");

  expect(warned.mock.calls.flat().join(" ")).not.toMatch(/should not be `null`/);
  warned.mockRestore();
});
```

- [ ] **Step 2: Run them**

Run: `./mvnw -q -o test -Dtest=DeskProxyTest -Dsurefire.failIfNoSpecifiedTests=false` and
`cd ui && npx vitest run src/desktop/tasks/TaskPage.test.tsx src/desktop/tasks/TasksPage.test.tsx`
Expected: FAIL — no Overview button; the proxy passes the 401 and the HTML page on; antd warns about `null`. If the
last one passes at once (antd warns only when the menu opens), ledger a ruling and keep the change for its reason.

- [ ] **Step 3: Implement.** `TaskPage.tsx`: the bot-not-running `Alert` becomes

```tsx
<Result status="info" title={t("tasks.botNotRunning")}
        extra={<Button onClick={() => navigate("/")}>{t("tasks.openOverview")}</Button>} />
```

`DeskProxy.forward`, after `http.send`:

```java
            if (!fromTheBot(answer)) {
                // A crashed bot's port taken by another program, or by another bot that refuses this file's token.
                Log.warn("desk.stale_port", "port", desk.get().port(), "status", answer.statusCode());
                return notRunning(language);
            }
            return new UiServer.Forwarded(answer.statusCode(), answer.body());
```

with

```java
    /** The bot's own answer: JSON, and never a refusal of the token it wrote itself. */
    private static boolean fromTheBot(HttpResponse<byte[]> answer) {
        boolean json = answer.headers().firstValue("Content-Type").orElse("").startsWith("application/json");
        return json && answer.statusCode() != 401;
    }
```

`TasksPage.tsx`: both filters' "all" option is `{ value: "", label: … }`, their `value` is `filter.project ?? ""` and
`filter.person ?? ""`, and their `onChange` stores `project || null` and `person || null`.

- [ ] **Step 4: Run them**

Run: `./mvnw -q -o test -Dtest=DeskProxyTest -Dsurefire.failIfNoSpecifiedTests=false`; `cd ui && npx vitest run && npx tsc --noEmit`
Expected: PASS.

- [ ] **Step 5: Commit** — "Carry three of D-2a's small findings: the Overview button, a stale port, the filters".

---

### Task 11: Browser tests, screenshots, docs

**Files:**
- Create: `ui/e2e/overview.spec.ts`
- Modify: `README.en.md`, `README.md`, `docs/ARCHITECTURE.md`, `SECURITY.md`,
  `docs/adr/0030-the-desktop-reaches-the-bot-through-a-desk-port.md`

- [ ] **Step 1: The browser test** (the e2e runs `dispatch ui` alone, so the bot is never running):

```ts
import { expect, test } from "@playwright/test";

test("without the bot Тойм says so beside the service, in either language, and Tasks offers no task to give", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("The bot is not running: start the service to see what it does.")).toBeVisible();
  await page.getByRole("button", { name: "Монгол" }).click();
  await expect(page.getByText("Бот ажиллахгүй байна: юу хийж байгааг харахын тулд сервисийг эхлүүлнэ үү.")).toBeVisible();
  await page.goto("/tasks");
  await expect(page.getByText("Бот ажиллахгүй байна: даалгавар бот ажиллаж байхад харагдана.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Даалгавар өгөх" })).toHaveCount(0);
});
```

- [ ] **Step 2: Run** `(cd ui && npm run build) && ./mvnw -q -o -Pui package -DskipTests && (cd ui && npm run e2e)`; fix
  until PASS.
- [ ] **Step 3: Screenshots:** from the built pages with a mocked desk (D-2a's way): Тойм live, the give panel on
  Даалгавар, and Тойм with the bot stopped, at 1280 and 390, in Mongolian; fix what reads badly; send the owner the
  Mongolian ones.
- [ ] **Step 4: Docs:**
  - README.en.md, "Manage it in the browser": the Overview bullet becomes the live board — what waits on you with its
    one action (Approve, or open it to answer), what runs and is queued and how many run at once, today's and this
    month's spend and 30 days of spend per day by project (Codex and Gemini CLI report no cost: their runs are counted),
    the service and the checks one line each opening their full panels, and the **Notifications** switch, which asks
    the browser once and then shows a notice, opening its page, when a task starts waiting on you. The Tasks bullet
    gains **Give a task**: project, words and priority; the task is yours, opens in the side panel, and your private
    chat hears of it.
  - README.md: the Хөтөч row gains "даалгавар өгөх, мэдэгдэл".
  - ARCHITECTURE: the `ui` row names `DeskTasksApi` (giving a task, the spend per day, desk-only); the D-2b milestone
    row reads "(built)" with what it holds.
  - SECURITY, the desk port: whoever holds the token or a session can also give tasks in the named admin's projects.
  - ADR 0030: the desk's routes add giving a task and the spend per day.
- [ ] **Step 5: Commit** — "Test Тойм in a browser, and write D-2b down".

---

### Task 12: Push, watch, review, merge

- [ ] **Step 1:** Scan the diff (`git diff origin/main > ../d2b.diff`; the Global Constraints' patterns, `"$HOME"`
  included).
- [ ] **Step 2:** Push `d2b-overview`, open a draft PR against `main`, watch the run on the three OSes to the end, fix red.
- [ ] **Step 3:** A fresh reviewer on the whole branch with the spec, this plan and the ledger's rulings; fix Critical
  and Important test-first.
- [ ] **Step 4:** Merge into local `main` with `--no-ff`. Local `main` holds another session's `m-instances` merge
  (`c7620f3`), so the merge may meet its changes (`App.java`, `UiCommand`): resolve, then run the whole suite, `npx
  vitest run`, the type check and the e2e on the merge. Ask the owner before pushing `main` (it would publish M too) or
  deploying.
