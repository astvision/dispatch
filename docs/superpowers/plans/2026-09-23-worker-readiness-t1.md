# Worker readiness (T-1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A run is never started against a computer that cannot do the work, and the member is told which of `claude`, `gh` or a missing clone is the reason.

**Architecture:** The worker already long-polls `/api/worker/next`. It attaches a readiness report to that request — the same checks `dispatch check` runs on a worker, re-run every 60 s and cached. The team machine stores the report as columns on `worker` (plus a `worker_project` row per project), so `Runs.claimNext` can gate a claim with a plain SQL join and no JSON functions. A held run records why on the task, which both the private message and `/status` read.

**Tech Stack:** Java 25, plain JDK `HttpServer`, SQLite via `dispatch.store`, JUnit 5. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-23-assignment-and-readiness-design.md`

## Global Constraints

- Readiness is re-checked on the worker every **60 s** and cached; the poll never spawns a process.
- A readiness report counts only while the worker was seen within `Workers.SEEN_WITHIN` (60 s). Older is *offline*, which Dispatch already words differently.
- Gating table, exactly: `claude` not ok holds **every** run; `gh` not ok holds only `EXECUTE` and `DELIVER`; a project's clone not ok holds only that project's runs.
- **A worker that reports no readiness at all counts as ready.** A team machine upgraded before its workers must not stall everyone.
- Blocker reasons cross the wire as codes (`claude`, `gh`, `clone`) plus a detail string, never as English sentences: the bot speaks Mongolian and the text lives in `messages_mn.properties`.
- T-1 gates on the **requester's** workers, as Dispatch does today. The switch to the assignee is T-2's; do not anticipate it.

---

## File Structure

- `src/main/resources/db/016-worker-readiness.sql` (create) — readiness columns on `worker`, the `worker_project` table, `blocked_reason` on `task`.
- `src/main/java/dispatch/worker/Readiness.java` (create) — the report and the gating rules. Pure; no I/O, no store.
- `src/main/java/dispatch/worker/WorkerChecks.java` (modify) — produce a `Readiness` from the checks it already runs.
- `src/main/java/dispatch/worker/WorkerLoop.java` (modify) — cache the report, refresh every 60 s.
- `src/main/java/dispatch/worker/WorkerClient.java` (modify) — send it in the `/next` body.
- `src/main/java/dispatch/worker/WorkerApi.java` (modify) — read it off the `/next` body and store it.
- `src/main/java/dispatch/store/Workers.java` (modify) — save and load a report.
- `src/main/java/dispatch/store/Runs.java` (modify) — the claim gate.
- `src/main/java/dispatch/core/TaskService.java` (modify) — record the blocker, tell once, show in `/status`.
- `src/main/java/dispatch/domain/OutboxKind.java`, `src/main/java/dispatch/telegram/Renderer.java`, `src/main/resources/messages_mn.properties` (modify) — the message.
- `docs/adr/0022-a-task-has-an-assignee-and-a-worker-proves-it-is-ready.md` (create).

---

### Task 1: The Readiness report and its gating rules

**Files:**
- Create: `src/main/java/dispatch/worker/Readiness.java`
- Test: `src/test/java/dispatch/worker/ReadinessTest.java`

**Interfaces:**
- Consumes: `dispatch.domain.RunKind` (`PLAN`, `EXECUTE`, `DELIVER`).
- Produces: `Readiness(Check claude, Check gh, Map<String,Check> projects)`; `Readiness.Check(boolean ok, String detail)`; `Readiness.Blocker(String code, String detail)`; `Optional<Blocker> blocker(String project, RunKind kind)`; `Readiness.READY`.

- [ ] **Step 1: Write the failing test**

```java
package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.domain.RunKind;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The gating table from the spec: what each blocker holds, and which one is reported when several apply. */
class ReadinessTest {

    private static final Readiness.Check OK = new Readiness.Check(true, "2.1.280");
    private static final Readiness.Check BROKEN = new Readiness.Check(false, "cannot run claude");

    @Test
    void aWorkingComputerHoldsNothing() {
        Readiness ready = new Readiness(OK, OK, Map.of("alm", OK));

        assertTrue(ready.blocker("alm", RunKind.PLAN).isEmpty());
        assertTrue(ready.blocker("alm", RunKind.EXECUTE).isEmpty());
        assertTrue(ready.blocker("alm", RunKind.DELIVER).isEmpty());
    }

    @Test
    void claudeHoldsEveryKindOfRun() {
        Readiness broken = new Readiness(BROKEN, OK, Map.of("alm", OK));

        for (RunKind kind : RunKind.values()) {
            assertEquals("claude", broken.blocker("alm", kind).orElseThrow().code(), kind.name());
        }
        assertEquals("cannot run claude", broken.blocker("alm", RunKind.PLAN).orElseThrow().detail());
    }

    @Test
    void ghHoldsOnlyRunsThatDeliver() {
        Readiness noGh = new Readiness(OK, new Readiness.Check(false, "not logged in"), Map.of("alm", OK));

        assertTrue(noGh.blocker("alm", RunKind.PLAN).isEmpty(), "planning never touches GitHub");
        assertEquals("gh", noGh.blocker("alm", RunKind.EXECUTE).orElseThrow().code());
        assertEquals("gh", noGh.blocker("alm", RunKind.DELIVER).orElseThrow().code());
    }

    @Test
    void aMissingCloneHoldsOnlyThatProject() {
        Readiness partial = new Readiness(OK, OK, Map.of("alm", OK, "life", new Readiness.Check(false, "clone missing")));

        assertTrue(partial.blocker("alm", RunKind.PLAN).isEmpty());
        assertEquals("clone", partial.blocker("life", RunKind.PLAN).orElseThrow().code());
    }

    @Test
    void aProjectTheWorkerNeverReportedIsNotHeld() {
        // A worker paired before the project existed reports nothing for it; the run fails honestly instead of waiting.
        Readiness partial = new Readiness(OK, OK, Map.of("alm", OK));

        assertTrue(partial.blocker("crm", RunKind.PLAN).isEmpty());
    }

    @Test
    void claudeIsReportedBeforeAnyOtherBlocker() {
        Readiness everythingBroken = new Readiness(BROKEN, new Readiness.Check(false, "not logged in"),
                Map.of("alm", new Readiness.Check(false, "clone missing")));

        assertEquals("claude", everythingBroken.blocker("alm", RunKind.DELIVER).orElseThrow().code(),
                "one message, and the one that blocks the most");
    }

    @Test
    void aWorkerThatReportedNothingCountsAsReady() {
        // A team machine upgraded before its workers must not stall everyone.
        assertTrue(Readiness.READY.blocker("alm", RunKind.EXECUTE).isEmpty());
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `./mvnw -q test -Dtest=ReadinessTest`
Expected: FAIL — `Readiness cannot be resolved to a type`.

- [ ] **Step 3: Write the implementation**

```java
package dispatch.worker;

import dispatch.domain.RunKind;
import java.util.Map;
import java.util.Optional;

/**
 * What a member's computer says about itself (spec: Readiness). It travels on the poll the worker already makes, and
 * the team machine keeps the last one to decide whether a run may be claimed.
 *
 * <p>Pure: it reads no store, runs no process and speaks no language. A blocker is a code and a detail, never a
 * sentence — the bot's words live in messages_mn.properties.
 */
public record Readiness(Check claude, Check gh, Map<String, Check> projects) {

    /** What a worker that reported nothing counts as: ready. A machine upgraded first must not stall its workers. */
    public static final Readiness READY = new Readiness(new Check(true, null), new Check(true, null), Map.of());

    public record Check(boolean ok, String detail) {
    }

    /** @param code one of "claude", "gh", "clone"; the Renderer turns it into words */
    public record Blocker(String code, String detail) {
    }

    public Readiness {
        projects = Map.copyOf(projects);
    }

    /**
     * Why a run of {@code kind} on {@code project} cannot start here, or empty when it can. At most one blocker is
     * reported, the one that holds the most, so a member gets one message rather than three.
     */
    public Optional<Blocker> blocker(String project, RunKind kind) {
        if (!claude.ok()) {
            return Optional.of(new Blocker("claude", claude.detail()));
        }
        Check clone = projects.get(project);
        // A project the worker never reported on is not held: better a run that fails saying why than one that waits
        // forever for a report that is never coming.
        if (clone != null && !clone.ok()) {
            return Optional.of(new Blocker("clone", clone.detail()));
        }
        if (!gh.ok() && (kind == RunKind.EXECUTE || kind == RunKind.DELIVER)) {
            return Optional.of(new Blocker("gh", gh.detail()));
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `./mvnw -q test -Dtest=ReadinessTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/worker/Readiness.java src/test/java/dispatch/worker/ReadinessTest.java
git commit -m "Say what a computer being ready means"
```

---

### Task 2: Store a worker's readiness

**Files:**
- Create: `src/main/resources/db/016-worker-readiness.sql`
- Modify: `src/main/java/dispatch/store/Workers.java`
- Test: `src/test/java/dispatch/store/WorkersReadinessTest.java`

**Interfaces:**
- Consumes: `Readiness` from Task 1; `dispatch.store.Tx`.
- Produces: `Workers.saveReadiness(Tx tx, long workerId, Readiness readiness, Instant now)` and `Workers.readiness(Tx tx, long workerId)` returning `Readiness` (never null — `Readiness.READY` when nothing was stored).

- [ ] **Step 1: Write the migration**

```sql
-- T-1: what a member's computer says about itself, so a run is never claimed for a computer that cannot do the work.
-- Stored as columns rather than a JSON blob so the claim query stays a plain join (no SQLite JSON1 dependency).
-- NULL everywhere means "reported nothing", which counts as ready: a team machine upgraded before its workers
-- must not stall them.
ALTER TABLE worker ADD COLUMN claude_ok INTEGER;
ALTER TABLE worker ADD COLUMN claude_detail TEXT;
ALTER TABLE worker ADD COLUMN gh_ok INTEGER;
ALTER TABLE worker ADD COLUMN gh_detail TEXT;
ALTER TABLE worker ADD COLUMN readiness_at TEXT;

-- One row per project the worker reported on; a project with no row is not held.
CREATE TABLE worker_project (
    worker_id INTEGER NOT NULL REFERENCES worker (id),
    project   TEXT    NOT NULL,
    ok        INTEGER NOT NULL,
    detail    TEXT,
    PRIMARY KEY (worker_id, project)
);

-- Why a task's next run is not starting, so it is said once and /status can show it.
ALTER TABLE task ADD COLUMN blocked_reason TEXT;
```

- [ ] **Step 2: Write the failing test**

```java
package dispatch.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.worker.Readiness;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkersReadinessTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    @TempDir
    Path dir;
    private Database db;
    private long workerId;

    @BeforeEach
    void setUp() {
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
        workerId = db.transactionReturning(tx -> Workers.insert(tx, "telegram:100", "laptop", "sha", NOW));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aWorkerThatReportedNothingIsReady() {
        Readiness stored = db.transactionReturning(tx -> Workers.readiness(tx, workerId));

        assertTrue(stored.claude().ok());
        assertTrue(stored.projects().isEmpty());
    }

    @Test
    void aReportComesBackAsItWentIn() {
        Readiness sent = new Readiness(new Readiness.Check(true, "2.1.280"),
                new Readiness.Check(false, "not logged in"),
                Map.of("alm", new Readiness.Check(true, null), "life", new Readiness.Check(false, "clone missing")));
        db.transaction(tx -> Workers.saveReadiness(tx, workerId, sent, NOW));

        Readiness stored = db.transactionReturning(tx -> Workers.readiness(tx, workerId));

        assertTrue(stored.claude().ok());
        assertEquals("2.1.280", stored.claude().detail());
        assertFalse(stored.gh().ok());
        assertEquals("not logged in", stored.gh().detail());
        assertEquals(2, stored.projects().size());
        assertFalse(stored.projects().get("life").ok());
    }

    @Test
    void aLaterReportReplacesTheOneBeforeIt() {
        db.transaction(tx -> Workers.saveReadiness(tx, workerId,
                new Readiness(new Readiness.Check(false, "gone"), new Readiness.Check(true, null),
                        Map.of("alm", new Readiness.Check(false, "clone missing"))), NOW));
        db.transaction(tx -> Workers.saveReadiness(tx, workerId,
                new Readiness(new Readiness.Check(true, "2.1.280"), new Readiness.Check(true, null),
                        Map.of("alm", new Readiness.Check(true, null))), NOW.plusSeconds(60)));

        Readiness stored = db.transactionReturning(tx -> Workers.readiness(tx, workerId));

        assertTrue(stored.claude().ok(), "a computer that was fixed is not still broken");
        assertTrue(stored.projects().get("alm").ok());
        assertEquals(1, stored.projects().size(), "stale project rows are replaced, not added to");
    }
}
```

- [ ] **Step 3: Run the test and watch it fail**

Run: `./mvnw -q test -Dtest=WorkersReadinessTest`
Expected: FAIL — `Workers.readiness` is undefined.

- [ ] **Step 4: Implement both methods in `Workers`**

```java
    /** Replaces this worker's whole report: a computer that was fixed must not stay broken here. */
    public static void saveReadiness(Tx tx, long workerId, Readiness readiness, Instant now) {
        tx.update("UPDATE worker SET claude_ok = ?, claude_detail = ?, gh_ok = ?, gh_detail = ?, readiness_at = ? WHERE id = ?",
                readiness.claude().ok() ? 1 : 0, readiness.claude().detail(),
                readiness.gh().ok() ? 1 : 0, readiness.gh().detail(), now, workerId);
        tx.update("DELETE FROM worker_project WHERE worker_id = ?", workerId);
        readiness.projects().forEach((project, check) ->
                tx.update("INSERT INTO worker_project (worker_id, project, ok, detail) VALUES (?, ?, ?, ?)",
                        workerId, project, check.ok() ? 1 : 0, check.detail()));
    }

    /** Never null: a worker that reported nothing counts as ready (see {@link Readiness#READY}). */
    public static Readiness readiness(Tx tx, long workerId) {
        Optional<Row> row = tx.queryOne("SELECT claude_ok, claude_detail, gh_ok, gh_detail FROM worker WHERE id = ?", workerId);
        if (row.isEmpty() || row.get().integer("claude_ok") == null) {
            return Readiness.READY;
        }
        Map<String, Readiness.Check> projects = new LinkedHashMap<>();
        for (Row project : tx.query("SELECT project, ok, detail FROM worker_project WHERE worker_id = ?", workerId)) {
            projects.put(project.text("project"), new Readiness.Check(project.integer("ok") == 1, project.text("detail")));
        }
        Row worker = row.get();
        return new Readiness(new Readiness.Check(worker.integer("claude_ok") == 1, worker.text("claude_detail")),
                new Readiness.Check(worker.integer("gh_ok") == 1, worker.text("gh_detail")), projects);
    }
```

> Match `Row`'s actual accessors while implementing — read `src/main/java/dispatch/store/Row.java` first and use its
> real method names rather than the `integer`/`text` used above if they differ.

- [ ] **Step 5: Run the test and watch it pass**

Run: `./mvnw -q test -Dtest=WorkersReadinessTest`
Expected: PASS, 3 tests.

- [ ] **Step 6: Run the store's existing tests, to prove the migration broke nothing**

Run: `./mvnw -q test -Dtest='*Store*,WorkerApiTest,WorkerProtocolTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/016-worker-readiness.sql src/main/java/dispatch/store/Workers.java \
        src/test/java/dispatch/store/WorkersReadinessTest.java
git commit -m "Keep each computer's last readiness report"
```

---

### Task 3: The worker reports readiness on the poll it already makes

**Files:**
- Modify: `src/main/java/dispatch/worker/WorkerChecks.java`
- Modify: `src/main/java/dispatch/worker/WorkerClient.java:122-131`
- Modify: `src/main/java/dispatch/worker/WorkerLoop.java:99`
- Modify: `src/main/java/dispatch/worker/WorkerApi.java:265-274`
- Test: `src/test/java/dispatch/worker/WorkerProtocolTest.java`

**Interfaces:**
- Consumes: `Readiness` (Task 1), `Workers.saveReadiness` (Task 2).
- Produces: `WorkerChecks.readiness()` returning `Readiness`; `WorkerClient.next(Readiness)`; the `/api/worker/next` request body gains `readiness`.

- [ ] **Step 1: Write the failing protocol test**

Add to `WorkerProtocolTest`:

```java
    @Test
    void nextCarriesTheWorkersReadinessAndItIsStored() throws Exception {
        String key = pair();

        post(WorkerApi.NEXT, key, """
                {"readiness": {"claude": {"ok": true, "detail": "2.1.280"},
                               "gh": {"ok": false, "detail": "not logged in"},
                               "projects": {"alm": {"ok": true}}}}""");

        Readiness stored = db.transactionReturning(tx ->
                Workers.readiness(tx, Workers.ofMember(tx, BOLD.ref()).getFirst().id()));
        assertTrue(stored.claude().ok());
        assertFalse(stored.gh().ok());
        assertEquals("not logged in", stored.gh().detail());
        assertTrue(stored.projects().get("alm").ok());
    }

    @Test
    void nextWithoutReadinessLeavesTheWorkerReady() throws Exception {
        // An older worker sends "{}"; it must keep working rather than being treated as broken.
        String key = pair();

        post(WorkerApi.NEXT, key, "{}");

        Readiness stored = db.transactionReturning(tx ->
                Workers.readiness(tx, Workers.ofMember(tx, BOLD.ref()).getFirst().id()));
        assertTrue(stored.claude().ok());
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q test -Dtest=WorkerProtocolTest`
Expected: FAIL — the readiness is not stored, `stored.gh().ok()` is still true.

- [ ] **Step 3: Read the readiness on the server**

In `WorkerApi`'s `case NEXT ->`, before `workers.next(worker)`:

```java
            case NEXT -> {
                readiness(body).ifPresent(reported ->
                        db.transaction(tx -> Workers.saveReadiness(tx, worker.id(), reported, clock.instant())));
                Optional<Job> job = workers.next(worker);
```

and a private helper on `WorkerApi`:

```java
    /** Empty when the worker sent none: an older worker keeps working and counts as ready. */
    private static Optional<Readiness> readiness(JsonNode body) {
        JsonNode reported = body.path("readiness");
        if (!reported.isObject()) {
            return Optional.empty();
        }
        Map<String, Readiness.Check> projects = new LinkedHashMap<>();
        reported.path("projects").fields().forEachRemaining(entry ->
                projects.put(entry.getKey(), check(entry.getValue())));
        return Optional.of(new Readiness(check(reported.path("claude")), check(reported.path("gh")), projects));
    }

    private static Readiness.Check check(JsonNode node) {
        // A field a worker left out is "fine": only an explicit false holds anything.
        return new Readiness.Check(node.path("ok").asBoolean(true), node.path("detail").asText(null));
    }
```

> `WorkerApi` has no `Database` or `Clock` today — add both to its constructor and to `WorkerApi.start(...)`,
> and pass them from `App.java` where `WorkerApi.start` is called. `WorkerApiFixture` constructs it too.

- [ ] **Step 4: Run the protocol test and watch it pass**

Run: `./mvnw -q test -Dtest=WorkerProtocolTest`
Expected: PASS.

- [ ] **Step 5: Produce the report on the worker**

Add to `WorkerChecks`, reusing the checks it already runs:

```java
    /** The same things `dispatch check` proves about this computer, as the team machine wants them. */
    public Readiness readiness() {
        Readiness.Check claude = Setup.claudeVersion(config.claudeCommand())
                .map(line -> new Readiness.Check(true, line))
                .orElseGet(() -> new Readiness.Check(false, "cannot run " + config.claudeCommand()));
        Readiness.Check gh = ghAuthenticated()
                ? new Readiness.Check(true, null)
                : new Readiness.Check(false, "not logged in");
        Map<String, Readiness.Check> projects = new LinkedHashMap<>();
        for (WorkerConfig.Project project : config.projects()) {
            projects.put(project.name(), cloneUsable(project)
                    ? new Readiness.Check(true, null)
                    : new Readiness.Check(false, "clone missing at " + project.path()));
        }
        return new Readiness(claude, gh, projects);
    }
```

> `ghAuthenticated()` and `cloneUsable(...)` are the predicates behind the findings `WorkerChecks` already
> produces — extract them from the existing methods rather than writing a second copy of either.

- [ ] **Step 6: Cache it in `WorkerLoop` and send it**

At `WorkerLoop:99`, replace `client.next()`:

```java
                Optional<Job> job = client.next(readiness());
```

and add to `WorkerLoop`, with `REFRESH = Duration.ofSeconds(60)`:

```java
    /**
     * The report sent with each poll, recomputed at most every {@link #REFRESH}. The poll runs every few seconds and
     * these checks spawn processes, so they are cached rather than run per request.
     */
    private Readiness readiness() {
        Instant now = clock.instant();
        if (cachedReadiness == null || checkedAt == null || checkedAt.isBefore(now.minus(REFRESH))) {
            cachedReadiness = checks.readiness();
            checkedAt = now;
        }
        return cachedReadiness;
    }
```

and in `WorkerClient`:

```java
    public Optional<Job> next(Readiness readiness) {
        JsonNode answer = call(WorkerApi.NEXT, Json.write(Json.object().set("readiness", Json.MAPPER.valueToTree(readiness))),
                POLL_TIMEOUT);
```

- [ ] **Step 7: Run the worker tests**

Run: `./mvnw -q test -Dtest='WorkerLoopTest,WorkerClientTest,WorkerChecksTest,WorkerProtocolTest,WorkerApiTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dispatch/worker src/main/java/dispatch/App.java src/test/java/dispatch/worker
git commit -m "Report a computer's readiness on the poll it already makes"
```

---

### Task 4: Hold a run whose computer is not ready

**Files:**
- Modify: `src/main/java/dispatch/store/Runs.java` (`claimNext`)
- Test: `src/test/java/dispatch/core/SchedulerTest.java`

**Interfaces:**
- Consumes: the `worker`/`worker_project` columns from Task 2.
- Produces: `Runs.claimNext(Tx, int, Instant, Instant)` unchanged in signature — the gate is added inside its SQL.

- [ ] **Step 1: Write the failing test**

Add to `SchedulerTest`:

```java
    @Test
    void aRunIsNotClaimedWhileItsMembersComputerCannotRunClaude() {
        long taskId = queuedPlanFor(BOLD, "alm");
        long workerId = pairedWorkerFor(BOLD);
        db.transaction(tx -> Workers.touch(tx, workerId, NOW));
        db.transaction(tx -> Workers.saveReadiness(tx, workerId,
                new Readiness(new Readiness.Check(false, "cannot run claude"), new Readiness.Check(true, null),
                        Map.of("alm", new Readiness.Check(true, null))), NOW));

        assertTrue(db.transactionReturning(tx -> Runs.claimNext(tx, 2, NOW, NOW.minusSeconds(60))).isEmpty());

        db.transaction(tx -> Workers.saveReadiness(tx, workerId,
                new Readiness(new Readiness.Check(true, "2.1.280"), new Readiness.Check(true, null),
                        Map.of("alm", new Readiness.Check(true, null))), NOW));

        assertEquals(taskId, db.transactionReturning(tx -> Runs.claimNext(tx, 2, NOW, NOW.minusSeconds(60)))
                .orElseThrow().taskId(), "fixing it starts the run with no further action");
    }

    @Test
    void ghDoesNotHoldAPlanningRun() {
        long taskId = queuedPlanFor(BOLD, "alm");
        long workerId = pairedWorkerFor(BOLD);
        db.transaction(tx -> Workers.touch(tx, workerId, NOW));
        db.transaction(tx -> Workers.saveReadiness(tx, workerId,
                new Readiness(new Readiness.Check(true, "2.1.280"), new Readiness.Check(false, "not logged in"),
                        Map.of("alm", new Readiness.Check(true, null))), NOW));

        assertEquals(taskId, db.transactionReturning(tx -> Runs.claimNext(tx, 2, NOW, NOW.minusSeconds(60)))
                .orElseThrow().taskId(), "planning never touches GitHub");
    }
```

> `queuedPlanFor` and `pairedWorkerFor` are helpers to add to `SchedulerTest` if it has no equivalent; follow the
> fixture style `WorkerApiFixture` uses for inserting a task and pairing a worker.

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q test -Dtest=SchedulerTest`
Expected: FAIL — the run is claimed although `claude` is broken.

- [ ] **Step 3: Add the gate to `claimNext`'s SQL**

Inside the existing `seenSince` branch, alongside the `last_seen_at` condition:

```sql
  AND EXISTS (
        SELECT 1 FROM worker w
         WHERE w.member_ref = t.requester_ref
           AND w.revoked_at IS NULL
           AND w.last_seen_at >= :seenSince
           -- NULL claude_ok means the worker reported nothing, which counts as ready.
           AND (w.claude_ok IS NULL OR w.claude_ok = 1)
           AND (w.gh_ok IS NULL OR w.gh_ok = 1 OR r.kind = 'PLAN')
           AND NOT EXISTS (SELECT 1 FROM worker_project p
                            WHERE p.worker_id = w.id AND p.project = t.project AND p.ok = 0))
```

> Read `claimNext`'s current SQL before editing: keep its existing joins and aliases, and add this as one more
> condition on the same worker existence check rather than a second `EXISTS` over `worker`.

- [ ] **Step 4: Run the test and watch it pass**

Run: `./mvnw -q test -Dtest=SchedulerTest`
Expected: PASS.

- [ ] **Step 5: Prove the team end-to-end path still runs**

Run: `./mvnw -q test -Dtest='TeamWorkersTest,SchedulerTest,RunExecutorTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dispatch/store/Runs.java src/test/java/dispatch/core/SchedulerTest.java
git commit -m "Do not claim a run for a computer that cannot do it"
```

---

### Task 5: Tell the member which thing is wrong, once

**Files:**
- Modify: `src/main/java/dispatch/domain/OutboxKind.java`
- Modify: `src/main/java/dispatch/core/TaskService.java`
- Modify: `src/main/java/dispatch/telegram/Renderer.java`
- Modify: `src/main/resources/messages_mn.properties`
- Test: `src/test/java/dispatch/TeamWorkersTest.java`
- Test: `src/test/java/dispatch/telegram/RendererTest.java`

**Interfaces:**
- Consumes: `Readiness.Blocker` (Task 1), `Workers.readiness` (Task 2), `task.blocked_reason` (Task 2).
- Produces: `OutboxKind.WORKER_BLOCKED` with payload `{"taskId": N, "code": "claude|gh|clone", "detail": "..."}`.

- [ ] **Step 1: Write the failing end-to-end test**

Add to `TeamWorkersTest`:

```java
    @Test
    void aMemberIsToldWhichThingIsWrongOnceAndTheRunStartsWhenItIsFixed() throws Exception {
        // The worker is connected but its Claude Code cannot run.
        workerReports(new Readiness(new Readiness.Check(false, "cannot run claude"), new Readiness.Check(true, null),
                Map.of("alm", new Readiness.Check(true, null))));

        giveTask(1, "Fix the login timeout", "NORMAL");

        JsonNode told = awaitMessageContaining(messages.getString("blocked.claude").substring(0, 12));
        assertEquals(100, told.get("chat_id").asLong(), "the member, privately");
        int before = telegram.drain("sendMessage").size();
        Thread.sleep(2000);
        assertEquals(before, telegram.drain("sendMessage").size(), "said once, not on every poll");

        workerReports(new Readiness(new Readiness.Check(true, "2.1.280"), new Readiness.Check(true, null),
                Map.of("alm", new Readiness.Check(true, null))));

        assertTrue(awaitMessageContaining("#1") != null, "the held run starts by itself");
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q test -Dtest=TeamWorkersTest`
Expected: FAIL — no blocked message is ever sent.

- [ ] **Step 3: Add the kind and the words**

`OutboxKind`:

```java
    /** To the member privately: their computer is connected but cannot do the work, and which thing is wrong. */
    WORKER_BLOCKED,
```

`messages_mn.properties`:

```properties
blocked.claude=⚠️ <b>#{0}</b>: таны компьютер дээр Claude Code ажиллахгүй байна ({1}).\nЗасаад дахин оролдоход даалгавар өөрөө эхэлнэ.
blocked.gh=⚠️ <b>#{0}</b>: таны компьютер дээр GitHub CLI нэвтрээгүй байна.\nАжиллуулна уу: <code>gh auth login</code>
blocked.clone=⚠️ <b>#{0}</b>: төслийн хуулбар таны компьютер дээр олдсонгүй ({1}).\nАжиллуулна уу: <code>dispatch worker init</code>
```

`Renderer.render`, beside the other worker kinds:

```java
            case WORKER_BLOCKED -> plain(format("blocked." + payload.path("code").asText(),
                    taskId(payload), escape(payload.path("detail").asText(""))));
```

> `RendererTest.everyKindRendersWithinTelegramLimitsWithoutPlaceholders` walks every `OutboxKind` in an exhaustive
> switch — add `case WORKER_BLOCKED -> Json.object().put("taskId", 1).put("code", "claude").put("detail", "gone");`
> to its `samplePayload`, or the suite will not compile.

- [ ] **Step 4: Say it once, from the scheduler's own transaction**

In `TaskService`, a method the Coordinator calls when a run could not be claimed for a readiness reason:

```java
    /**
     * Records why this task's next run is not starting and tells the member — but only when the reason changed, so a
     * held task produces one message rather than one per poll.
     */
    public void blocked(Tx tx, long taskId, String requesterRef, Readiness.Blocker blocker) {
        Optional<Task> task = Tasks.find(tx, taskId);
        if (task.isEmpty() || blocker.code().equals(Tasks.blockedReason(tx, taskId))) {
            return;
        }
        Tasks.setBlockedReason(tx, taskId, blocker.code());
        enqueue(tx, taskId, OutboxKind.WORKER_BLOCKED, requesterRef, null,
                Json.object().put("taskId", taskId).put("code", blocker.code()).put("detail", blocker.detail()),
                clock.instant());
    }

    /** Clears it when the run finally starts, so the next block is told again. */
    public void unblocked(Tx tx, long taskId) {
        Tasks.setBlockedReason(tx, taskId, null);
    }
```

> `Tasks.blockedReason` / `Tasks.setBlockedReason` are two one-line accessors to add to `dispatch.store.Tasks`
> over the `blocked_reason` column from Task 2.

- [ ] **Step 5: Show it in `/status`**

In `TaskService.statusPayload`, where a queued item already gets `waitingForWorker`:

```java
                    String reason = Tasks.blockedReason(tx, run.taskId());
                    if (reason != null) {
                        item.put("blocked", reason);
                    }
```

and in `Renderer`'s status rendering, a queued line with `blocked` shows `status.blocked.<code>` instead of
`status.waitingForWorker`:

```properties
status.blocked.claude=Claude Code ажиллахгүй байна
status.blocked.gh=GitHub CLI нэвтрээгүй
status.blocked.clone=төслийн хуулбар алга
```

- [ ] **Step 6: Run the tests**

Run: `./mvnw -q test -Dtest='TeamWorkersTest,RendererTest,StatusAndHistoryTest,TaskLifecycleTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dispatch/core/TaskService.java src/main/java/dispatch/store/Tasks.java \
        src/main/java/dispatch/domain/OutboxKind.java src/main/java/dispatch/telegram/Renderer.java \
        src/main/resources/messages_mn.properties src/test/java/dispatch
git commit -m "Tell a member which thing on their computer is stopping a task"
```

---

### Task 6: ADR 0022 and the docs

**Files:**
- Create: `docs/adr/0022-a-task-has-an-assignee-and-a-worker-proves-it-is-ready.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `README.md`

- [ ] **Step 1: Write ADR 0022**

Follow the shape of `docs/adr/0021-team-members-tasks-run-on-their-own-computers.md`: what was decided, why, the
rejected alternatives, then `## Consequences`. It must record: readiness rides the existing poll; the gating table;
that a worker reporting nothing counts as ready, and why; and the honest limit — `claude --version` proves the
binary runs, not that the session is signed in.

Only the readiness half of the ADR's subject is built in T-1. Write the assignee half as decided-but-not-yet-built,
and T-2 fills it in.

- [ ] **Step 2: Update ARCHITECTURE.md**

Add ADR 0022 to "Decisions at a glance", and a T-1 row to the Milestones table.

- [ ] **Step 3: Update README.md**

In "Your own computer in a team", say that a task waits when this computer cannot run it and that the bot names the
reason privately.

- [ ] **Step 4: Prove the whole suite is green**

Run: `./mvnw test`
Expected: PASS, no failures.

- [ ] **Step 5: Commit**

```bash
git add docs README.md
git commit -m "Record ADR 0022 and document what a ready computer means"
```

---

## Self-Review

**Spec coverage.** Readiness on the existing poll → Task 3. Cached at 60 s → Task 3 Step 6. Stored per worker → Task 2.
Gating table → Task 1 (the rule) and Task 4 (the claim). Scheduler holds the run → Task 4. Assignee told once per
transition → Task 5. `/status` → Task 5 Step 5. Starts by itself on the first healthy poll → Task 4's second
assertion and Task 5's end-to-end test. ADR 0022 → Task 6. The spec's "worker reporting nothing counts as ready" is a
Global Constraint and is tested in Tasks 1, 2 and 3.

**Not in this plan, deliberately:** everything under T-2 (assignee, group intake, the ADR amendments) and the deferred
splitter. T-1 gates on the requester's workers, which is what Dispatch does today.

**Type consistency.** `Readiness`, `Readiness.Check(boolean ok, String detail)`, `Readiness.Blocker(String code, String detail)`
and `Readiness.READY` are defined in Task 1 and used unchanged in Tasks 2, 3, 4 and 5. `Workers.saveReadiness` /
`Workers.readiness` are defined in Task 2 and used in Tasks 3 and 4. `OutboxKind.WORKER_BLOCKED` and its payload keys
(`taskId`, `code`, `detail`) are defined in Task 5 and used only there. Blocker codes are the same three strings
(`claude`, `gh`, `clone`) in Task 1, the SQL in Task 4 and the message keys in Task 5.
