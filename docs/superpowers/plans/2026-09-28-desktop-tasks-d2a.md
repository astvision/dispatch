# D-2a: Tasks on the Desktop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The owner follows and acts on every task from `dispatch ui`: a Даалгавар list with a side panel, a page per task
with its log, and the strip's running / waiting-on-you / today's-spend lamps, reached through the running bot's desk port.

**Architecture:** `dispatch run` opens a `DeskServer` (D-1's `UiServer` with a token `DeskAuth`) on 127.0.0.1 and writes
its port and a per-start token into an owner-only `<stateDir>/desk.json`. `dispatch ui` passes `/api/tasks/*` and
`/api/live` on to it through a `DeskProxy` hooked into `UiServer` as a `Forward`, so the page's session guards
everything and the token never reaches the browser. The bot answers with the Mini App's `TasksApi` in an owner mode
(every task in full, actions as the owner's member, ADR 0020 unchanged) and a `LiveApi` summary.

**Tech Stack:** Java 25 (JDK HttpServer and HttpClient, Jackson, SQLite), React 19 + antd 6 + Vite, vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-28-desktop-tasks-design.md` (D-2a milestone). D-1's spec and plan
(`2026-09-28-desktop-ui-design.md`, `2026-09-28-desktop-ui-d1.md`) set the conventions this plan follows.

## Global Constraints

- No new dependency, Java or npm.
- The desk port binds 127.0.0.1 only; its token is 32 random bytes (64 hex characters), new at every start, compared in
  constant time (`MessageDigest.isEqual`); `desk.json` is created owner-only (`OwnerOnly.createFile`) and moved into
  place atomically; the desk port sends no CORS headers and serves no pages.
- `dispatch ui` never touches the queue; the desk token never reaches the browser.
- ADR 0020 unchanged: a plan's decisions (approve, correct, answer, reject) and a retry are the requester's; a cancel is
  the requester's or an admin's. The owner sees every task in full; members' views do not change.
- Every word a page shows goes through `t()` with its key in both `ui/src/i18n/en.ts` and `mn.ts`; every server message
  a page can show is `Text.of(key, …)` with the key in `texts_en.properties` and `texts_mn.properties`, apostrophes
  doubled (D-1, ADR 0029).
- The board look (D-1): colours from `ui/src/board.ts`; a lamp always carries its words.
- Reading: `/api/live` and each page's own data every 5 seconds while the tab is in view; nothing while it is hidden.
- ADR number 0030 (0028 is reserved for X-4, 0029 is D-1's).
- Tests: `./mvnw -q -o test -Dtest=A,B` (commas between classes); `cd ui && npx vitest run && npx tsc --noEmit`.
- Secret hygiene: scan the diff before every push, one `/usr/bin/grep -E` pattern per call, `"$HOME"` for the home path.

## Review Focus

1. **A crashed bot's stale `desk.json`** (its port closed, or taken by another program): the desktop says the bot is not
   running and keeps working; the next start replaces the file. Task 4 pins the closed-port case.
2. **A team with two admins and no choice made, or a remembered choice that is no longer an admin:** the desk refuses
   (`choose_member`, `not_owner`), the page asks once which admin you are, and the choice survives a reload. Tasks 2 and
   7 pin it.
3. **The owner's view leaking into the Mini App:** a member still sees a teammate's task as its headline, with `mine`
   meaning their own. Task 1 pins it.
4. **An action on a view that went stale** (a replaced plan, a task that already started or finished): the bot's
   refusal in the page's language, and the panel reads the task again. Tasks 1 and 8 pin it.
5. **The bot restarting while the desktop polls:** the lamps go out and come back, with no pile of error messages.
   Task 6 pins it.

---

### Task 1: The owner's view of every task, and a correction by text

**Files:**
- Modify: `src/main/java/dispatch/core/TaskAccess.java` (the owner viewer)
- Modify: `src/main/java/dispatch/core/TaskService.java` (`mine` apart from full sight; a history limit; branch in the
  timeline)
- Modify: `src/main/java/dispatch/domain/Task.java` (`branch()`)
- Modify: `src/main/java/dispatch/ui/TasksApi.java` (owner mode, `/api/tasks/correct`)
- Modify: `src/main/resources/texts_en.properties`, `texts_mn.properties` (`refusal.correctionEmpty`)
- Test: `src/test/java/dispatch/ui/TasksApiTest.java`

**Interfaces:**
- Produces: `TaskAccess.owner(String memberRef): Viewer`; `TaskAccess.Viewer(String ref, Set<String> projects,
  boolean owner)` (the two-argument constructor stays); `Task.branch(): String` ("dispatch/<id>");
  `TaskService.historyPayload(Tx, Viewer, int limit)`; `new TasksApi(Database, TaskService, Groups, boolean owner)`;
  the route `/api/tasks/correct` `{taskId, planSeq, text}` → `{result: "CORRECTED"}`; timeline payloads carry `branch`
  and `baseBranch`.

- [ ] **Step 1: Write the failing tests** (in `TasksApiTest`; make `groups` a field so the owner-mode api can be built)

```java
    @Test
    void theDesksOwnerSeesEveryTaskInFullAndTellsTheirOwnApart() {
        long theirs = planned(ALI, noQuestions());
        long mine = create(BOLD, "Fix the login timeout");
        TasksApi desk = new TasksApi(db, tasks, groups, true);

        JsonNode listed = desk.list(BOLD_CALLER, Json.object().put("scope", "group"));
        JsonNode timeline = desk.timeline(BOLD_CALLER, Json.object().put("taskId", theirs));
        JsonNode detail = desk.detail(BOLD_CALLER, Json.object().put("taskId", theirs));

        assertFalse(item(listed, theirs).path("mine").asBoolean(), "Ali's task is not Bold's: " + listed);
        assertTrue(item(listed, mine).path("mine").asBoolean());
        assertFalse(timeline.path("headline").asBoolean(false), "the owner reads a member's task in full: " + timeline);
        assertEquals("0.1", timeline.path("costUsd").asText());
        assertEquals("dispatch/" + theirs, timeline.path("branch").asText());
        assertEquals("[\"cancel\"]", detail.path("actions").toString(), "seen in full, still decided by Ali alone");
        assertEquals(0, detail.path("plan").path("current").asInt());
    }

    @Test
    void aMemberStillSeesATeammatesTaskAsItsHeadline() {
        long theirs = planned(ALI, noQuestions());

        JsonNode timeline = api.timeline(BOLD_CALLER, Json.object().put("taskId", theirs));
        JsonNode listed = api.list(BOLD_CALLER, Json.object().put("scope", "group"));

        assertTrue(timeline.path("headline").asBoolean(), "ADR 0020 holds for members: " + timeline);
        assertFalse(item(listed, theirs).path("mine").asBoolean());
    }

    @Test
    void aPlanIsCorrectedByTextOnlyByItsRequesterAndOnlyTheLatestOne() {
        long taskId = planned(ALI, noQuestions());
        TasksApi desk = new TasksApi(db, tasks, groups, true);

        ApiException notYours = assertThrows(ApiException.class, () -> desk.correct(BOLD_CALLER,
                Json.object().put("taskId", taskId).put("planSeq", 1).put("text", "use 60 s")));
        ApiException stale = assertThrows(ApiException.class, () -> desk.correct(ALI_CALLER,
                Json.object().put("taskId", taskId).put("planSeq", 7).put("text", "use 60 s")));
        ApiException empty = assertThrows(ApiException.class, () -> desk.correct(ALI_CALLER,
                Json.object().put("taskId", taskId).put("planSeq", 1).put("text", "  ")));
        JsonNode corrected = desk.correct(ALI_CALLER, Json.object().put("taskId", taskId).put("planSeq", 1).put("text", "use 60 s"));

        assertEquals("not_yours", notYours.code());
        assertEquals("stale", stale.code());
        assertEquals("invalid", empty.code());
        assertEquals("CORRECTED", corrected.path("result").asText());
        assertEquals("PLANNING", phase(taskId));
    }
```

- [ ] **Step 2: Run** `./mvnw -q -o test -Dtest=TasksApiTest` — expect compilation errors (no four-argument
  constructor, no `correct`), then FAIL.
- [ ] **Step 3: The owner viewer** (`TaskAccess.java`)

```java
    public record Viewer(String ref, Set<String> projects, boolean owner) {

        public Viewer {
            projects = Set.copyOf(projects);
        }

        /** A member or a group chat: their own tasks in full, their projects' others as headlines. */
        public Viewer(String ref, Set<String> projects) {
            this(ref, projects, false);
        }

        public Sight sees(Task task) {
            if (ref != null && task.requester().ref().equals(ref)) {
                return Sight.FULL;
            }
            if (!projects.contains(task.project())) {
                return Sight.NONE;
            }
            // The machine's owner on the desktop (D-2): every task of the instance in full. ADR 0020 hides a teammate's
            // task from members, not from whoever runs the machine and could read its database.
            return owner ? Sight.FULL : Sight.HEADLINE;
        }
    }

    /** The owner on the desktop (D-2): every task of the instance's projects in full, acting as {@code memberRef}. */
    public Viewer owner(String memberRef) {
        Set<String> all = groups.all().stream().flatMap(group -> group.projects().stream()).collect(Collectors.toSet());
        return new Viewer(groups.isMember(memberRef) ? memberRef : null, all, true);
    }
```

- [ ] **Step 4: `mine` apart from full sight, a history limit, the branch** (`TaskService.java`, `Task.java`)

In `TaskService`, next to `isOwn`:

```java
    /** Whether {@code viewer} gave the task: what "mine" means on a list, apart from how much of it they see (D-2). */
    private static boolean isMine(TaskAccess.Viewer viewer, Task task) {
        return task != null && viewer.ref() != null && task.requester().ref().equals(viewer.ref());
    }
```

In `statusPayload` (running and queued items, and awaiting items) and in `historyPayload`, write
`.put("mine", isMine(viewer, task))` where they now write `.put("mine", own)`; every other use of `own` (steps, the
question, costs, runs) stays on full sight. For a member the two are the same, so the Mini App does not change.

```java
    /** The content of a history message; also what the Mini App's task list reads (spec: Task pages). */
    public ObjectNode historyPayload(Tx tx, TaskAccess.Viewer viewer) {
        return historyPayload(tx, viewer, HISTORY_SIZE);
    }

    /** @param limit how many of the most recently finished tasks; the desktop lists a month's (D-2) */
    public ObjectNode historyPayload(Tx tx, TaskAccess.Viewer viewer, int limit) {
        List<Task> finished = Tasks.finished(tx, viewer.projects(), viewer.ref(), limit);
        // … the rest of today's method, unchanged …
    }
```

In `timelinePayload`, after `.put("prUrl", task.prUrl())`: `.put("baseBranch", task.baseBranch()).put("branch", task.branch())`.

In `Task.java`:

```java
    /** The branch its work is done on, cut from {@link #baseBranch} (ADR 0007). */
    public String branch() {
        return "dispatch/" + id;
    }
```

- [ ] **Step 5: `TasksApi`'s owner mode and the correction**

```java
    private final boolean owner;

    public TasksApi(Database db, TaskService tasks, Groups groups) {
        this(db, tasks, groups, false);
    }

    /**
     * @param owner the desk port's view (D-2): every task of the instance in full, acted on as the caller's own member, so
     *              ADR 0020 decides what they may do exactly as in the chat
     */
    public TasksApi(Database db, TaskService tasks, Groups groups, boolean owner) {
        this.db = db;
        this.tasks = tasks;
        this.access = new TaskAccess(groups);
        this.owner = owner;
    }

    private TaskAccess.Viewer viewer(Caller caller) {
        return owner ? access.owner(caller.ref()) : access.member(caller.ref());
    }
```

`routes()` gains `"/api/tasks/correct", this::correct` (`Map.of` takes up to ten pairs; this makes nine). `list` and
`timeline` use `viewer(caller)` in place of `access.member(caller.ref())`. `list` in owner mode reads a month of
history: `tasks.historyPayload(tx, viewer, owner ? 200 : 10)` — keep 10 behind the existing call for the Mini App by
calling the two-argument overload when `!owner`. `ownTask` decides by the viewer's sight:

```java
    private ObjectNode ownTask(Tx tx, Caller caller, long taskId) {
        TaskAccess.Verdict verdict = access.of(tx, caller.ref(), taskId);
        TaskAccess.Sight sight = verdict.task() == null ? TaskAccess.Sight.NONE : viewer(caller).sees(verdict.task());
        if (sight == TaskAccess.Sight.NONE) {
            throw notFound(taskId);
        }
        if (sight == TaskAccess.Sight.HEADLINE) {
            throw new ApiException(403, "not_yours", NOT_YOURS);
        }
        ObjectNode task = tasks.timelinePayload(tx, viewer(caller), taskId).orElseThrow(() -> notFound(taskId));
        task.remove("runs");
        tasks.currentPlan(tx, taskId).ifPresent(plan -> task.set("plan",
                plan.put("current", verdict.allows(TaskAccess.Action.ANSWER) ? verdict.currentQuestion() : 0)));
        return task;
    }

    /**
     * The requester's reply to a plan, written on the desktop: {@link TaskService#correct}, as the chat's reply to a plan.
     * Refused here and only here, as {@link #cancel} is, so no refusal lands in the chat.
     */
    ObjectNode correct(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        int planSeq = number(body, "planSeq");
        String text = body.path("text").asText("");
        CorrectResult result = db.transactionReturning(tx -> {
            Optional<TaskAccess.Refusal> refused = access.of(tx, caller.ref(), taskId).refusal(TaskAccess.Action.CORRECT, planSeq);
            if (refused.isPresent()) {
                throw correctRefused(refused.get(), taskId);
            }
            if (text.isBlank()) {
                throw new ApiException(400, "invalid", Text.of("refusal.correctionEmpty"));
            }
            CorrectResult corrected = tasks.correct(tx, requester(caller), taskId, planSeq, text, null, caller.ref());
            if (corrected != CorrectResult.CORRECTED) {
                throw new IllegalStateException("task #" + taskId + " was allowed a correction but came back " + corrected);
            }
            return corrected;
        });
        return Json.object().put("result", result.name());
    }

    private static ApiException correctRefused(TaskAccess.Refusal refusal, long taskId) {
        return switch (refusal) {
            case NOT_MEMBER -> notMember();
            case NOT_FOUND -> notFound(taskId);
            case NOT_REQUESTER -> new ApiException(403, "not_yours", NOT_YOURS);
            case STALE_PLAN -> stale();
            case WRONG_PHASE -> new ApiException(409, "wrong_state", Text.of("refusal.notWaiting"));
            default -> throw new IllegalStateException("task access never refuses a correction as " + refusal);
        };
    }
```

Bundles: `refusal.correctionEmpty=a correction needs a few words` / `refusal.correctionEmpty=засварт хэдэн үг бичнэ үү`.

- [ ] **Step 6: Run** `./mvnw -q -o test -Dtest=TasksApiTest,TaskServiceTest,TextTest,MiniAppServerTest` — PASS.
- [ ] **Step 7: Commit** — "Let the desk see every task in full and correct a plan by text".

### Task 2: `desk.json` and the desk's authentication

**Files:**
- Create: `src/main/java/dispatch/ui/DeskFile.java`, `src/main/java/dispatch/ui/DeskAuth.java`
- Modify: the bundles (`refusal.deskToken`, `refusal.chooseMember`, `refusal.notOwner`)
- Test: `src/test/java/dispatch/ui/DeskFileTest.java`, `src/test/java/dispatch/ui/DeskAuthTest.java`

**Interfaces:**
- Produces: `record DeskFile(int port, String token, String version, String name)` with `static Path in(Path stateDir)`,
  `void write(Path stateDir) throws IOException`, `static Optional<DeskFile> read(Path stateDir)`,
  `static void delete(Path stateDir)`, `static String newToken()`; `final class DeskAuth implements UiServer.Auth` with
  `DeskAuth(int port, String token, Groups groups)` and `static final String MEMBER_HEADER = "X-Dispatch-Member"`.

- [ ] **Step 1: Write the failing tests**

```java
class DeskFileTest {

    @TempDir
    Path state;

    @Test
    void aWrittenFileIsReadBackAndOnlyItsOwnerCanReadIt() throws IOException {
        DeskFile written = new DeskFile(41234, DeskFile.newToken(), "0.3.0", "acme");

        written.write(state);

        assertEquals(Optional.of(written), DeskFile.read(state));
        assertEquals(64, written.token().length());
        assertEquals(Optional.empty(), OwnerOnly.othersAccess(DeskFile.in(state)));
    }

    @Test
    void aMissingOrBrokenFileIsNoBot() throws IOException {
        assertEquals(Optional.empty(), DeskFile.read(state));
        Files.writeString(DeskFile.in(state), "{\"port\":");
        assertEquals(Optional.empty(), DeskFile.read(state));
    }

    @Test
    void deletingIsQuietWhenThereIsNothingToDelete() throws IOException {
        new DeskFile(1, DeskFile.newToken(), "0.3.0", "acme").write(state);
        DeskFile.delete(state);
        DeskFile.delete(state);
        assertEquals(Optional.empty(), DeskFile.read(state));
    }
}
```

`DeskAuthTest` builds a `Groups` with admins 100 and 300 and members 100, 200 and 300, and a second `Groups` for a
personal bot (member 100, no admins); it drives `caller(exchange)` with a small fake `HttpExchange` that returns the
given headers (write it in the test: extend `com.sun.net.httpserver.HttpExchange`, returning a `Headers` object from
`getRequestHeaders()`; every other method throws `UnsupportedOperationException`):

```java
    @Test
    void noTokenOrAWrongOneIsRefused() {
        DeskAuth auth = new DeskAuth(4000, TOKEN, team);
        assertEquals("desk_token", assertThrows(ApiException.class, () -> auth.caller(exchange(Map.of()))).code());
        assertEquals("desk_token", assertThrows(ApiException.class,
                () -> auth.caller(exchange(Map.of("Authorization", "desk " + "0".repeat(64))))).code());
    }

    @Test
    void aTeamWithSeveralAdminsIsAskedWhichOneThisIs() {
        DeskAuth auth = new DeskAuth(4000, TOKEN, team);
        assertEquals("choose_member", assertThrows(ApiException.class,
                () -> auth.caller(exchange(Map.of("Authorization", "desk " + TOKEN)))).code());
        Caller chosen = auth.caller(exchange(Map.of("Authorization", "desk " + TOKEN, DeskAuth.MEMBER_HEADER, "telegram:300")));
        assertEquals(new Caller("telegram:300", "Saraa", true), chosen);
    }

    @Test
    void aMemberWhoIsNoAdminMayNotBeChosen() {
        DeskAuth auth = new DeskAuth(4000, TOKEN, team);
        assertEquals("not_owner", assertThrows(ApiException.class, () -> auth.caller(
                exchange(Map.of("Authorization", "desk " + TOKEN, DeskAuth.MEMBER_HEADER, "telegram:200")))).code());
    }

    @Test
    void aPersonalBotIsItsOneMember() {
        DeskAuth auth = new DeskAuth(4000, TOKEN, personal);
        assertEquals("telegram:100", auth.caller(exchange(Map.of("Authorization", "desk " + TOKEN))).ref());
    }

    @Test
    void anotherHostIsRefusedAndNoPageIsServed() {
        DeskAuth auth = new DeskAuth(4000, TOKEN, team);
        assertEquals(Optional.empty(), auth.hostRefusal("127.0.0.1:4000"));
        assertTrue(auth.hostRefusal("localhost:4000").isPresent());
        assertTrue(auth.hostRefusal("evil.example:4000").isPresent());
        assertTrue(auth.pageRefusal(exchange(Map.of())).isPresent());
    }
```

- [ ] **Step 2: Run** `./mvnw -q -o test -Dtest=DeskFileTest,DeskAuthTest` — FAIL (no such classes).
- [ ] **Step 3: Write `DeskFile`**

```java
package dispatch.ui;

/**
 * {@code <stateDir>/desk.json}: where the running bot's desk port listens and the token it answers (D-2). Owner-only, and
 * written whole under another name before it is moved into place, so {@code dispatch ui} never reads half of it.
 */
public record DeskFile(int port, String token, String version, String name) {

    static final String FILE = "desk.json";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static Path in(Path stateDir) {
        return stateDir.resolve(FILE);
    }

    public void write(Path stateDir) throws IOException {
        OwnerOnly.createDirectories(stateDir);
        Path draft = stateDir.resolve(FILE + ".new");
        Files.deleteIfExists(draft);
        OwnerOnly.createFile(draft);
        Files.writeString(draft, Json.write(this));
        Files.move(draft, in(stateDir), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Empty when there is none, or it cannot be read: either way there is no bot to ask. */
    public static Optional<DeskFile> read(Path stateDir) {
        try {
            return Optional.of(Json.MAPPER.readValue(Files.readString(in(stateDir)), DeskFile.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** At a clean stop; a crash leaves the file, which the next start replaces. */
    public static void delete(Path stateDir) {
        try {
            Files.deleteIfExists(in(stateDir));
        } catch (IOException e) {
            Log.warn("desk.file_not_deleted", "file", in(stateDir), "error", e.getMessage());
        }
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
```

- [ ] **Step 4: Write `DeskAuth`**

```java
package dispatch.ui;

/**
 * The desk port's authentication (D-2): the token from desk.json on every request, and the member the owner acts as — a
 * personal bot's one member, a team's only admin, or the admin the page names when there are several.
 */
final class DeskAuth implements UiServer.Auth {

    static final String MEMBER_HEADER = "X-Dispatch-Member";

    private final int port;
    private final byte[] token;
    private final Groups groups;

    DeskAuth(int port, String token, Groups groups) {
        this.port = port;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.groups = groups;
    }

    @Override
    public Optional<String> hostRefusal(String host) {
        return ("127.0.0.1:" + port).equals(host) ? Optional.empty() : Optional.of("this port answers 127.0.0.1:" + port + " only");
    }

    @Override
    public void headers(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
    }

    @Override
    public Optional<String> pageRefusal(HttpExchange exchange) {
        return Optional.of("the desk port serves no pages");
    }

    @Override
    public UiServer.Caller caller(HttpExchange exchange) {
        String given = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] offered = given != null && given.startsWith("desk ") ? given.substring(5).getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (!MessageDigest.isEqual(offered, token)) {
            throw new ApiException(401, "desk_token", Text.of("refusal.deskToken"));
        }
        String named = exchange.getRequestHeaders().getFirst(MEMBER_HEADER);
        String ref = named != null && !named.isBlank() ? named.strip()
                : onlyCandidate().orElseThrow(() -> new ApiException(409, "choose_member", Text.of("refusal.chooseMember")));
        if (!groups.mayManage(ref)) {
            throw new ApiException(403, "not_owner", Text.of("refusal.notOwner", ref));
        }
        return new UiServer.Caller(ref, groups.memberName(ref).orElse(ref), true);
    }

    /** The one member the owner can be: a personal bot's member, or a team's only admin. */
    private Optional<String> onlyCandidate() {
        List<String> candidates = groups.isPersonal()
                ? groups.all().stream().flatMap(group -> group.members().stream()).map(member -> "telegram:" + member.id())
                        .distinct().toList()
                : groups.admins();
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }
}
```

Bundles: `refusal.deskToken=this call needs the desk token` / `refusal.deskToken=энэ хүсэлтэд desk token хэрэгтэй`;
`refusal.chooseMember=choose which admin you are` / `refusal.chooseMember=аль админ болохоо сонгоно уу`;
`refusal.notOwner={0} may not act for this Dispatch from the desktop` /
`refusal.notOwner={0} desktop-оос энэ Dispatch-ийг удирдах эрхгүй`.

- [ ] **Step 5: Run** `./mvnw -q -o test -Dtest=DeskFileTest,DeskAuthTest,TextTest` — PASS.
- [ ] **Step 6: Commit** — "Name the desk port and its token in an owner-only desk.json, and check both".

### Task 3: The live summary, the desk server, and the bot opening it

**Files:**
- Modify: `src/main/java/dispatch/store/Runs.java` (`spentSince`)
- Create: `src/main/java/dispatch/ui/LiveApi.java`, `src/main/java/dispatch/ui/DeskServer.java`
- Modify: `src/main/java/dispatch/App.java` (start at start, close at stop, `deskPort()`)
- Test: `src/test/java/dispatch/ui/DeskServerTest.java`, `src/test/java/dispatch/AppTest.java`

**Interfaces:**
- Consumes: `DeskFile`, `DeskAuth` (Task 2); `new TasksApi(db, tasks, groups, true)`, `TaskAccess.owner` (Task 1).
- Produces: `Runs.spentSince(Tx, Instant): BigDecimal`; `GET /api/live` →
  `{version, name, running, queued, waitingOnYou: [{taskId, title}], waitingOnOthers, todayUsd, monthUsd}`;
  `DeskServer.start(Path stateDir, Database db, TaskService tasks, Groups groups, Clock clock, String version,
  String name): DeskServer` with `port()` and `close()`; `App.deskPort(): int` (0 when it could not open).

- [ ] **Step 1: Write the failing tests** (`DeskServerTest`: the `TasksApiTest` fixture — Bold admin, Ali member, project
  alm, a `TestClock` at `2026-09-28T10:00:00Z` in UTC — with its own `planned` helper; calls go over real HTTP)

```java
    @Test
    void theFileNamesThePortAndACallWithoutTheTokenIsRefused() throws Exception {
        DeskFile file = DeskFile.read(state).orElseThrow();
        HttpResponse<String> refused = http.send(HttpRequest.newBuilder(uri("/api/live")).build(), BodyHandlers.ofString());

        assertEquals(desk.port(), file.port());
        assertEquals(401, refused.statusCode());
    }

    @Test
    void theLiveSummaryCountsWhatWaitsOnTheOwnerAndTodaysSpend() throws Exception {
        planned(ALI, noQuestions());
        planned(BOLD, noQuestions());

        JsonNode live = Json.read(get("/api/live").body());

        assertEquals(1, live.path("waitingOnYou").size(), live.toString());
        assertEquals(1, live.path("waitingOnOthers").asInt());
        assertEquals("0.20", live.path("todayUsd").asText());
        assertEquals("0.20", live.path("monthUsd").asText());
        assertEquals("acme", live.path("name").asText());
        assertEquals("0.3.0", live.path("version").asText());
    }

    @Test
    void theTasksAreServedInTheOwnersView() throws Exception {
        long alis = planned(ALI, noQuestions());

        JsonNode listed = Json.read(post("/api/tasks/list", "{\"scope\":\"group\"}").body());

        assertEquals(alis, listed.path("tasks").get(0).path("taskId").asLong());
        assertEquals(false, listed.path("tasks").get(0).path("mine").asBoolean());
    }

    @Test
    void closingRemovesTheFile() {
        desk.close();
        assertEquals(Optional.empty(), DeskFile.read(state));
    }
```

(`get` and `post` send `Authorization: desk <token from the file>`; the fixture's groups have one admin, so no member
header is needed.) In `AppTest`:

```java
    @Test
    void aRunningBotOpensItsDeskPortAndAStoppedOneClosesIt() {
        app = start();
        DeskFile desk = DeskFile.read(config.stateDir()).orElseThrow(() -> new AssertionError("no desk.json"));

        assertEquals(app.deskPort(), desk.port());
        app.stop();
        app = null;
        assertEquals(Optional.empty(), DeskFile.read(config.stateDir()));
    }
```

- [ ] **Step 2: Run** `./mvnw -q -o test -Dtest=DeskServerTest,AppTest` — FAIL.
- [ ] **Step 3: `Runs.spentSince`**

```java
    /** What the runs started at or after {@code since} reported costing; a run with no cost (Codex, Gemini CLI) adds nothing. */
    public static BigDecimal spentSince(Tx tx, Instant since) {
        return tx.list("SELECT cost_usd FROM run WHERE cost_usd IS NOT NULL AND started_at >= ?", row -> row.decimal("cost_usd"), since)
                .stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
```

- [ ] **Step 4: `LiveApi`**

```java
package dispatch.ui;

/** GET /api/live on the desk port (D-2): what the strip's lamps and the Overview count, in the owner's view. */
final class LiveApi {

    record Waiting(long taskId, String title) {
    }

    record Live(String version, String name, int running, int queued, List<Waiting> waitingOnYou, int waitingOnOthers,
                String todayUsd, String monthUsd) {
    }

    private final Database db;
    private final TaskService tasks;
    private final TaskAccess access;
    private final Clock clock;
    private final String version;
    private final String name;

    LiveApi(Database db, TaskService tasks, Groups groups, Clock clock, String version, String name) {
        this.db = db;
        this.tasks = tasks;
        this.access = new TaskAccess(groups);
        this.clock = clock;
        this.version = version;
        this.name = name;
    }

    Live get(UiServer.Caller caller) {
        TaskAccess.Viewer viewer = access.owner(caller.ref());
        ZonedDateTime now = clock.instant().atZone(clock.getZone());
        Instant today = now.truncatedTo(ChronoUnit.DAYS).toInstant();
        Instant month = now.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
        return db.transactionReturning(tx -> {
            ObjectNode status = tasks.statusPayload(tx, viewer);
            List<Waiting> mine = new ArrayList<>();
            int others = 0;
            for (JsonNode item : status.withArray("awaitingApproval")) {
                if (item.path("mine").asBoolean()) {
                    mine.add(new Waiting(item.path("taskId").asLong(), item.path("title").asText()));
                } else {
                    others++;
                }
            }
            return new Live(version, name, status.withArray("running").size(), status.withArray("queued").size(), mine, others,
                    usd(Runs.spentSince(tx, today)), usd(Runs.spentSince(tx, month)));
        });
    }

    private static String usd(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
```

- [ ] **Step 5: `DeskServer`**

```java
package dispatch.ui;

/**
 * The running bot's desk port (D-2): its task routes for {@code dispatch ui}, on 127.0.0.1, answering only the token it
 * writes into desk.json at every start.
 */
public final class DeskServer implements AutoCloseable {

    private final UiServer server;
    private final Path stateDir;

    private DeskServer(UiServer server, Path stateDir) {
        this.server = server;
        this.stateDir = stateDir;
    }

    public static DeskServer start(Path stateDir, Database db, TaskService tasks, Groups groups, Clock clock, String version,
                                   String name) throws IOException {
        String token = DeskFile.newToken();
        TasksApi tasksApi = new TasksApi(db, tasks, groups, true);
        LiveApi live = new LiveApi(db, tasks, groups, clock, version, name);
        UiServer server = UiServer.start(0, "/desk-serves-no-pages", port -> new DeskAuth(port, token, groups),
                Map.of("/api/live", live::get), tasksApi.routes());
        try {
            new DeskFile(server.port(), token, version, name).write(stateDir);
        } catch (IOException e) {
            server.close();
            throw e;
        }
        return new DeskServer(server, stateDir);
    }

    public int port() {
        return server.port();
    }

    @Override
    public void close() {
        server.close();
        DeskFile.delete(stateDir);
    }
}
```

- [ ] **Step 6: The bot opens it** (`App.java`). After the Mini App's server is built:

```java
        DeskServer desk = null;
        try {
            desk = DeskServer.start(stateDir, db, tasks, groups, clock, version(), config.team());
        } catch (java.io.IOException e) {
            // The bot's own work never waits on the desktop: without a desk port the desktop says the bot is not running.
            Log.error("desk.unavailable", e, "state_dir", stateDir);
        }
```

`App`'s constructor takes `DeskServer desk` beside `miniApp`; `stop()` closes it beside `miniApp.close()`;
`deskPort()` answers `desk == null ? 0 : desk.port()`; `version()` is
`Optional.ofNullable(App.class.getPackage().getImplementationVersion()).orElse("dev")`, as `UiCommand` reads it.

- [ ] **Step 7: Run** `./mvnw -q -o test -Dtest=DeskServerTest,AppTest,RunsTest` — PASS (add a `RunsTest` case for
  `spentSince` if `RunsTest` exists; otherwise `DeskServerTest` covers it).
- [ ] **Step 8: Commit** — "Open the bot's desk port with the tasks and a live summary for the desktop".

### Task 4: `dispatch ui` passes task calls on to the bot

**Files:**
- Modify: `src/main/java/dispatch/ui/UiServer.java` (the `Forward` seam)
- Create: `src/main/java/dispatch/ui/DeskProxy.java`
- Modify: `src/main/java/dispatch/ui/UiCommand.java` (wire it)
- Modify: the bundles (`desk.botNotRunning`)
- Test: `src/test/java/dispatch/ui/UiServerTest.java`, `src/test/java/dispatch/ui/DeskProxyTest.java`

**Interfaces:**
- Consumes: `DeskFile`, `DeskAuth.MEMBER_HEADER` (Task 2); `DeskServer` (Task 3, in the test).
- Produces: `UiServer.Forward` (`boolean handles(String path)`; `Forwarded forward(String path, String method,
  byte[] body, Language language, String member)`), `record UiServer.Forwarded(int status, byte[] json)`, a
  `UiServer.start(int, String, IntFunction<Auth>, Map, Map, Forward)` overload; `DeskProxy(Supplier<Path> stateDir)`;
  the 503 answer `{error: "bot_not_running", message}`.

- [ ] **Step 1: Write the failing tests**

`UiServerTest`: a server with an `Auth` that lets everything in and a `Forward` that handles `/api/tasks/` by answering
`Forwarded(418, "{\"passed\":true}")` with the method, member header and language it was given recorded:

```java
    @Test
    void aForwardedCallIsAnsweredAsTheOtherServerAnsweredIt() throws Exception {
        HttpResponse<String> answer = http.send(HttpRequest.newBuilder(uri("/api/tasks/list"))
                .header("Accept-Language", "mn").header("X-Dispatch-Member", "telegram:300")
                .POST(BodyPublishers.ofString("{\"scope\":\"group\"}")).build(), BodyHandlers.ofString());

        assertEquals(418, answer.statusCode());
        assertEquals("{\"passed\":true}", answer.body());
        assertEquals(List.of("POST", "telegram:300", "MN", "{\"scope\":\"group\"}"), forwarded);
    }

    @Test
    void aPathTheForwardDoesNotHandleIsStillThisServersOwn() throws Exception {
        assertEquals(404, http.send(HttpRequest.newBuilder(uri("/api/nothing")).build(), BodyHandlers.ofString()).statusCode());
    }
```

`DeskProxyTest` (a real `DeskServer` over the `DeskServerTest` fixture; the proxy's state folder is the desk's):

```java
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
```

- [ ] **Step 2: Run** `./mvnw -q -o test -Dtest=UiServerTest,DeskProxyTest` — FAIL.
- [ ] **Step 3: The seam in `UiServer`**

```java
    /** A call this server passes on to another as it came (D-2: `dispatch ui` passes task calls to the bot's desk port). */
    public interface Forward {

        boolean handles(String path);

        /** @param member the page's {@link DeskAuth#MEMBER_HEADER}, or null */
        Forwarded forward(String path, String method, byte[] body, Language language, String member);
    }

    /** The other server's status and JSON body, sent back unchanged. */
    public record Forwarded(int status, byte[] json) {
    }
```

A `private final Forward forward;` (null when none); the existing `start` overloads pass null; a new overload takes it.
At the top of `api(...)`, after the caller is known (so the page's session still guards it):

```java
        if (forward != null && forward.handles(path)) {
            byte[] body = reading ? new byte[0] : exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                json(exchange, 413, error("too_large", Text.of("refusal.tooLarge", MAX_BODY / 1024).render(language)));
                return;
            }
            Forwarded answer = forward.forward(path, exchange.getRequestMethod(), body, language,
                    exchange.getRequestHeaders().getFirst(DeskAuth.MEMBER_HEADER));
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            send(exchange, answer.status(), answer.json());
            return;
        }
```

- [ ] **Step 4: `DeskProxy`**

```java
package dispatch.ui;

/** {@code dispatch ui}'s side of the desk port (D-2): task calls go on to the running bot, with the token from desk.json. */
final class DeskProxy implements UiServer.Forward {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Text NOT_RUNNING = Text.of("desk.botNotRunning");

    private final Supplier<Path> stateDir;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /** @param stateDir read on every call: the file is found again after the bot restarts on another port */
    DeskProxy(Supplier<Path> stateDir) {
        this.stateDir = stateDir;
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/api/tasks/") || path.equals("/api/live");
    }

    @Override
    public UiServer.Forwarded forward(String path, String method, byte[] body, Language language, String member) {
        Optional<DeskFile> desk = DeskFile.read(stateDir.get());
        if (desk.isEmpty()) {
            return notRunning(language);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + desk.get().port() + path))
                .timeout(TIMEOUT)
                .header("Authorization", "desk " + desk.get().token())
                .header("Accept-Language", language.name().toLowerCase(Locale.ROOT));
        if (member != null && !member.isBlank()) {
            request.header(DeskAuth.MEMBER_HEADER, member);
        }
        if (method.equals("GET")) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        }
        try {
            HttpResponse<byte[]> answer = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new UiServer.Forwarded(answer.statusCode(), answer.body());
        } catch (IOException e) {
            // Refused, reset or timed out: a stopped bot, or one that crashed and left its file.
            return notRunning(language);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return notRunning(language);
        }
    }

    private static UiServer.Forwarded notRunning(Language language) {
        return new UiServer.Forwarded(503, Json.write(Map.of("error", "bot_not_running", "message", NOT_RUNNING.render(language)))
                .getBytes(StandardCharsets.UTF_8));
    }
}
```

Bundles: `desk.botNotRunning=The bot is not running` / `desk.botNotRunning=Бот ажиллахгүй байна`.

- [ ] **Step 5: Wire it in `UiCommand.start`**: the state folder as `OverviewApi` finds it —

```java
        Supplier<Path> stateDir = () -> {
            try {
                return RunCommand.prepare(configFile, processEnvironment).config().stateDir();
            } catch (CliException | ConfigException e) {
                return locations.stateDir();
            }
        };
        server = UiServer.start(options.port(), resourceRoot, UiAuth::new, management.get(false), postRoutes, new DeskProxy(stateDir));
```

- [ ] **Step 6: Run** `./mvnw -q -o test -Dtest=UiServerTest,DeskProxyTest,UiCommandTest,TextTest` — PASS.
- [ ] **Step 7: Commit** — "Pass the desktop's task calls on to the running bot".

### Task 5: The instance's name on the overview

**Files:** Modify `src/main/java/dispatch/ui/OverviewApi.java`, `ui/src/api.ts` (`Overview.name`); test
`src/test/java/dispatch/ui/OverviewApiTest.java`.

- [ ] **Step 1: Test first:** a configured instance's overview has `name` = the config's `team`; before setup it is null.
- [ ] **Step 2: Run** `./mvnw -q -o test -Dtest=OverviewApiTest` — FAIL.
- [ ] **Step 3: Implement:** `Overview` gains `String name` (after `version`), read with the state folder from the same
  `RunCommand.prepare` (null when there is no config or it does not load); `api.ts`'s `Overview` gains `name: string | null`.
- [ ] **Step 4: Run** `./mvnw -q -o test -Dtest=OverviewApiTest` — PASS; `cd ui && npx tsc --noEmit` — PASS.
- [ ] **Step 5: Commit** — "Name the instance on the overview".

### Task 6: The desktop reads the bot: the lamps, the name, the version notice

**Files:**
- Create: `ui/src/desktop/usePolling.ts`, `ui/src/desktop/member.ts`
- Modify: `ui/src/api.ts` (`Live`, `getLive`, `correctPlan`, the member header), `ui/src/desktop/status.tsx` (the live
  reading), `ui/src/desktop/Shell.tsx` (lamps, name, version notice, the rail's Даалгавар), the dictionaries
- Test: `ui/src/desktop/usePolling.test.ts`, `ui/src/desktop/Shell.test.tsx`

**Interfaces:**
- Produces: `usePolling<T>(read: (signal: AbortSignal) => Promise<T>, intervalMs?: number, key?: unknown):
  { data: T | null; error: ApiError | null; reload: () => void }`; `chosenMember(): string | null`,
  `chooseMember(ref: string | null): void`; `useDesktopStatus()` gains `live: Live | null` and `botRunning: boolean | null`
  (null until the first answer); `DESKTOP_PAGES` gains `{ key: "/tasks", label: "nav.tasks" }` after Тойм.

- [ ] **Step 1: Write the failing tests**

`usePolling.test.ts` (fake timers; `document.visibilityState` stubbed through `Object.defineProperty`):

```ts
test("reads now and again after each answer while in view, and not while hidden", async () => {
  vi.useFakeTimers();
  const read = vi.fn().mockResolvedValue(1);
  renderHook(() => usePolling(read, 5000));
  await vi.advanceTimersByTimeAsync(0);
  expect(read).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(5000);
  expect(read).toHaveBeenCalledTimes(2);
  setVisibility("hidden");
  await vi.advanceTimersByTimeAsync(20_000);
  expect(read).toHaveBeenCalledTimes(2);
  setVisibility("visible");
  await vi.advanceTimersByTimeAsync(0);
  expect(read).toHaveBeenCalledTimes(3);
});

test("a failure keeps the last answer and is replaced by the next success", async () => {
  vi.useFakeTimers();
  const read = vi.fn()
    .mockResolvedValueOnce(1)
    .mockRejectedValueOnce(new ApiError("bot_not_running", "The bot is not running"))
    .mockResolvedValueOnce(2);
  const { result } = renderHook(() => usePolling(read, 5000));
  await vi.advanceTimersByTimeAsync(0);
  expect(result.current.data).toBe(1);
  await vi.advanceTimersByTimeAsync(5000);
  expect(result.current.data).toBe(1);
  expect(result.current.error?.code).toBe("bot_not_running");
  await vi.advanceTimersByTimeAsync(5000);
  expect(result.current.data).toBe(2);
  expect(result.current.error).toBeNull();
});
```

`Shell.test.tsx` gains:

```ts
test("the strip counts what runs, what waits on you and today's spend, beside the instance's name", async () => {
  vi.mocked(api.getOverview).mockResolvedValue({ ...running, name: "acme" });
  vi.mocked(api.getLive).mockResolvedValue({ version: "0.2.0", name: "acme", running: 1, queued: 0,
    waitingOnYou: [{ taskId: 14, title: "Fix the login timeout" }], waitingOnOthers: 0, todayUsd: "3.40", monthUsd: "41.20" });
  renderShell();
  expect(await screen.findByText("1 running")).toBeInTheDocument();
  expect(screen.getByText("1 waiting on you")).toBeInTheDocument();
  expect(screen.getByText("Today $3.40")).toBeInTheDocument();
  expect(screen.getByText("acme")).toBeInTheDocument();
});

test("without the bot the task lamps are gone and nothing piles up", async () => {
  vi.mocked(api.getLive).mockRejectedValue(new api.ApiError("bot_not_running", "The bot is not running"));
  renderShell();
  await screen.findByText("Service running");
  expect(screen.queryByText(/waiting on you/)).not.toBeInTheDocument();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

test("a bot of another version says a restart runs the new one", async () => {
  vi.mocked(api.getLive).mockResolvedValue({ ...live, version: "0.1.9" });
  renderShell();
  expect(await screen.findByText("Restart the service to run 0.2.0")).toBeInTheDocument();
});
```

- [ ] **Step 2: Run** `cd ui && npx vitest run src/desktop` — FAIL.
- [ ] **Step 3: `usePolling.ts`**

```ts
import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../api";

/**
 * Reads now and again {@code intervalMs} after each answer while the page is in view (D-2); a hidden tab reads nothing
 * until it is shown. A failure keeps the last answer, so a restarting bot does not blank a page.
 */
export function usePolling<T>(read: (signal: AbortSignal) => Promise<T>, intervalMs = 5000, key?: unknown) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [nonce, setNonce] = useState(0);
  const latest = useRef(read);
  latest.current = read;

  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = async () => {
      if (document.visibilityState === "hidden") return;
      try {
        const answer = await latest.current(controller.signal);
        if (controller.signal.aborted) return;
        setData(answer);
        setError(null);
      } catch (e) {
        if (controller.signal.aborted) return;
        setError(e instanceof ApiError ? e : new ApiError("unknown", String(e)));
      }
      timer = setTimeout(() => void tick(), intervalMs);
    };
    const shown = () => {
      if (document.visibilityState === "visible") {
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
  }, [intervalMs, key, nonce]);

  return { data, error, reload: useCallback(() => setNonce((n) => n + 1), []) };
}
```

(`latest.current = read` in render follows the codebase's existing tolerance for a ref kept current; if lint objects,
move it into a `useEffect`.)

- [ ] **Step 4: `member.ts` and the API**

```ts
const KEY = "dispatch.member";
let remembered: string | null = null;

/** The admin this browser acts as on a team's desktop (D-2), once chosen; kept in memory too when storage is blocked. */
export function chosenMember(): string | null {
  try {
    return window.localStorage.getItem(KEY) ?? remembered;
  } catch {
    return remembered;
  }
}

export function chooseMember(ref: string | null) {
  remembered = ref;
  try {
    if (ref) window.localStorage.setItem(KEY, ref);
    else window.localStorage.removeItem(KEY);
  } catch {
    // Remembered for this visit only.
  }
}
```

`api.ts`: `send` adds `...(chosenMember() ? { "X-Dispatch-Member": chosenMember() } : {})` to the headers; and

```ts
/** What the strip's lamps and the Overview count, from the running bot's desk port (D-2). */
export interface Live {
  version: string;
  name: string;
  running: number;
  queued: number;
  waitingOnYou: { taskId: number; title: string }[];
  waitingOnOthers: number;
  todayUsd: string;
  monthUsd: string;
}

export const getLive = (signal?: AbortSignal) => get<Live>("/api/live", signal);
export const correctPlan = (taskId: number, planSeq: number, text: string) =>
  post<{ result: string }>("/api/tasks/correct", { taskId, planSeq, text });
```

- [ ] **Step 5: The live reading and the lamps.** `DesktopProviders` also runs `usePolling(getLive)`; `useDesktopStatus()`
  answers `live` (null while the bot does not answer) and `botRunning` (`true` after an answer, `false` after
  `bot_not_running`, null before either). `Shell`'s strip: the name is `overview?.name ?? "Dispatch"`; with `live`, three
  lamps after the checks lamp — `strip.running` (green), `strip.waitingOnYou` (amber when above zero, quiet otherwise),
  `strip.today` (quiet); when `live.version !== overview.version`, a quiet lamp `strip.newVersion` with
  `overview.version`. A `choose_member` or `not_owner` error is Task 7's; every other error of the live reading only puts
  the lamps out. Keys: `nav.tasks` "Tasks"/"Даалгавар"; `strip.running` "{count} running"/"Явж байна {count}";
  `strip.waitingOnYou` "{count} waiting on you"/"Таныг хүлээж {count}"; `strip.today` "Today ${usd}"/"Өнөөдөр ${usd}";
  `strip.newVersion` "Restart the service to run {version}"/"Сервисийг дахин эхлүүлбэл {version} ажиллана".
- [ ] **Step 6: Run** `cd ui && npx vitest run && npx tsc --noEmit` — PASS (the old Shell tests mock `getLive` with a
  pending promise, as they do `getOverview`).
- [ ] **Step 7: Commit** — "Show what runs, what waits on you and today's spend in the strip".

### Task 7: Which admin you are

**Files:** Create `ui/src/desktop/MemberChoice.tsx`, `ui/src/desktop/MemberChoice.test.tsx`; modify
`ui/src/desktop/status.tsx`, `ui/src/desktop/Shell.tsx`, the dictionaries.

**Interfaces:**
- Consumes: `chosenMember`, `chooseMember` (Task 6); `getConfig()` (`admins`, groups' members).
- Produces: `<MemberChoice open onChosen>`; `useDesktopStatus()` gains `needsMember: boolean`.

- [ ] **Step 1: Tests first:** when the live reading answers `choose_member`, a dialog "Which admin are you?" lists the
  config's admins by name (Bold, Saraa); choosing Saraa stores `telegram:300`, closes the dialog and reads again, and the
  next request carries `X-Dispatch-Member: telegram:300`; a `not_owner` answer clears the stored choice and asks again; a
  personal bot never asks.
- [ ] **Step 2: Run** `cd ui && npx vitest run src/desktop` — FAIL.
- [ ] **Step 3: Implement:** `status.tsx` sets `needsMember` on `choose_member`, and on `not_owner` calls
  `chooseMember(null)` then sets it; `Shell` renders `<MemberChoice open={needsMember} onChosen={reload}>`, an antd
  `Modal` (no close button: the desk cannot answer without a choice) whose options come from `getConfig()`: each admin
  id with the first name the groups give it. Keys: `member.title` "Which admin are you?"/"Та аль админ бэ?";
  `member.hint` "The desktop acts as you: your own plans are yours to decide."/"Desktop таны нэрийн өмнөөс ажиллана:
  өөрийн төлөвлөгөөгөө та шийднэ.".
- [ ] **Step 4: Run** `cd ui && npx vitest run && npx tsc --noEmit` — PASS.
- [ ] **Step 5: Commit** — "Ask once which admin the desktop acts as".

### Task 8: Даалгавар: the list and its side panel

**Files:**
- Create: `ui/src/desktop/tasks/groups.ts`, `ui/src/desktop/tasks/groups.test.ts`, `ui/src/desktop/tasks/TaskView.tsx`,
  `ui/src/desktop/tasks/TaskView.test.tsx`, `ui/src/desktop/tasks/TasksPage.tsx`, `ui/src/desktop/tasks/TasksPage.test.tsx`
- Modify: `ui/src/App.tsx` (the `/tasks` route), the dictionaries

**Interfaces:**
- Consumes: `listTasks("group")`, `getTaskDetail`, `approvePlan`, `rejectPlan`, `answerQuestion`, `cancelTask`,
  `retryTask`, `correctPlan`; `usePolling` (Task 6).
- Produces: `groupOf(row: TaskRow): TaskGroup`, `GROUP_ORDER`, `Filter`,
  `grouped(rows: TaskRow[], filter: Filter, now: Date): [TaskGroup, TaskRow[]][]`;
  `<TaskView taskId onChanged? onDetails? layout?: "panel" | "page">`; `<TasksPage navigate>`.

- [ ] **Step 1: Write the failing tests**

```ts
// groups.test.ts
const row = (over: Partial<TaskRow>): TaskRow => ({ taskId: 1, project: "crm", title: "Fix it", state: "running",
  priority: "NORMAL", requester: "Bold", mine: true, actions: [], ...over });

test("a task waits on you only when it is yours; finished ones older than 30 days are left out", () => {
  const now = new Date("2026-09-28T12:00:00Z");
  const rows = [
    row({ taskId: 14, state: "awaitingApproval", mine: true }),
    row({ taskId: 15, state: "awaitingApproval", mine: false, requester: "Ali" }),
    row({ taskId: 13, state: "running" }),
    row({ taskId: 12, state: "queued" }),
    row({ taskId: 11, state: "finished", completedAt: "2026-09-20T10:00:00Z" }),
    row({ taskId: 3, state: "finished", completedAt: "2026-07-01T10:00:00Z" }),
  ];
  expect(grouped(rows, { project: null, person: null, text: "" }, now).map(([group, list]) => [group, list.map((r) => r.taskId)]))
    .toEqual([["waitingOnYou", [14]], ["running", [13]], ["queued", [12]], ["waitingOnOthers", [15]], ["finished", [11]]]);
});

test("the search finds a task by its number or its words, and the filters by project and person", () => {
  const rows = [row({ taskId: 14, title: "Fix the login timeout" }), row({ taskId: 13, title: "PDF export", project: "alm", requester: "Ali" })];
  const found = (filter: Filter) => grouped(rows, filter, new Date()).flatMap(([, list]) => list.map((r) => r.taskId));
  expect(found({ project: null, person: null, text: "#14" })).toEqual([14]);
  expect(found({ project: null, person: null, text: "pdf" })).toEqual([13]);
  expect(found({ project: "alm", person: null, text: "" })).toEqual([13]);
  expect(found({ project: null, person: "Ali", text: "" })).toEqual([13]);
});
```

`TaskView.test.tsx` (api mocked): your own waiting plan shows its steps and Батлах, Засвар бичих, Татгалзах; approving
sends `approvePlan(14, 1)` and calls `onChanged`; a correction sends `correctPlan(14, 1, "use 60 s")`; an open question
shows its options, "your own answer" and "you decide", and an option sends `answerQuestion(14, 1, 1, { option: 0 })`;
someone else's running task shows only Цуцлах (from its `actions`); a `stale` refusal shows the bot's message and reads
the task again. `TasksPage.test.tsx`: the groups render in order with their Mongolian titles; a row opens the panel with
the task's title; the panel's Дэлгэрэнгүй calls `navigate("/tasks/14")`; with the bot not running the page says so and
links to the Overview.

- [ ] **Step 2: Run** `cd ui && npx vitest run src/desktop/tasks` — FAIL.
- [ ] **Step 3: `groups.ts`**

```ts
import type { TaskRow } from "../../api";

export type TaskGroup = "waitingOnYou" | "running" | "queued" | "waitingOnOthers" | "finished";
export const GROUP_ORDER: TaskGroup[] = ["waitingOnYou", "running", "queued", "waitingOnOthers", "finished"];

export interface Filter {
  project: string | null;
  person: string | null;
  text: string;
}

const MONTH_MS = 30 * 24 * 60 * 60 * 1000;

export function groupOf(row: TaskRow): TaskGroup {
  if (row.state === "awaitingApproval") return row.mine ? "waitingOnYou" : "waitingOnOthers";
  if (row.state === "running") return "running";
  if (row.state === "queued") return "queued";
  return "finished";
}

function matches(row: TaskRow, filter: Filter) {
  if (filter.project && row.project !== filter.project) return false;
  if (filter.person && row.requester !== filter.person) return false;
  const text = filter.text.trim().toLowerCase();
  if (!text) return true;
  if (/^#?\d+$/.test(text)) return row.taskId === Number(text.replace("#", ""));
  return row.title.toLowerCase().includes(text);
}

/** The groups in page order, each with its matching rows; finished ones are the last 30 days'. Empty groups are left out. */
export function grouped(rows: TaskRow[], filter: Filter, now: Date): [TaskGroup, TaskRow[]][] {
  const recent = (row: TaskRow) => row.state !== "finished" || !row.completedAt
    || now.getTime() - new Date(row.completedAt).getTime() <= MONTH_MS;
  return GROUP_ORDER
    .map((group): [TaskGroup, TaskRow[]] => [group, rows.filter((row) => groupOf(row) === group && recent(row) && matches(row, filter))])
    .filter(([, list]) => list.length > 0);
}
```

- [ ] **Step 4: `TaskView.tsx`** — one component for the panel and the page. It reads `getTaskDetail(taskId)` with
  `usePolling` (key `taskId`); shows the title, "project · person · priority · cost"; the plan (`understanding`, numbered
  `steps`, `risks`); the current question (when `plan.current > 0`): its text, one button per option, a text box with
  Илгээх, and "Та шийд"; the actions from `detail.actions`: `approve` → Батлах (primary), `correct` → Засвар бичих (opens a
  text box with Илгээх), `reject` → Татгалзах (danger, asks first with a Popconfirm), `cancel` → Даалгаврыг цуцлах (asks
  first), `retry` → Дахин оролдох; a finished task's `prUrl` as a link. After an action it calls `reload()` and
  `onChanged?.()`; a refusal shows `error.message` in an Alert and reloads. In `layout="panel"` it ends with
  Дэлгэрэнгүй → `onDetails?.()`. Keys (`tasks.*`): plan "Plan"/"Төлөвлөгөө", risks "Risks"/"Эрсдэл", question
  "Question {index} of {count}"/"Асуулт {index}/{count}", yourAnswer "Your own answer"/"Өөрийн хариулт", send
  "Send"/"Илгээх", youDecide "You decide"/"Та шийд", approve "Approve"/"Батлах", correct "Write a correction"/"Засвар
  бичих", correction "What should change"/"Юуг өөрчлөх вэ", reject "Reject"/"Татгалзах", rejectAsk "Reject this
  plan?"/"Энэ төлөвлөгөөнөөс татгалзах уу?", cancel "Cancel the task"/"Даалгаврыг цуцлах", cancelAsk "Cancel #{id}?"/"#{id}
  даалгаврыг цуцлах уу?", retry "Retry"/"Дахин оролдох", pullRequest "Pull request"/"Pull request", details
  "Details"/"Дэлгэрэнгүй", noPlan "No plan yet"/"Төлөвлөгөө хараахан алга", priority.URGENT "urgent"/"🔴 яаралтай",
  priority.NORMAL "normal"/"🟡 энгийн", priority.LOW "low"/"🟢 бага".
- [ ] **Step 5: `TasksPage.tsx`** — the page title and Даалгавар's filters (project and person `Select`s, a search
  `Input`), then `grouped(...)` as sections: each a heading (`tasks.group.<group>`) and rows of number (mono), title,
  project, person, a state lamp with its word (`tasks.state.*`: running "Running"/"Явж байна", queued
  "Queued"/"Дараалалд", approve "To approve"/"Батлах", answer "To answer"/"Хариулах", completed "Done"/"Дууссан", failed
  "Failed"/"Амжилтгүй", rejected "Rejected"/"Татгалзсан", cancelled "Cancelled"/"Цуцалсан") and the elapsed time or
  age. It reads `listTasks("group")` with `usePolling`. A row opens a `Drawer` (the Projects page's `useNarrow`, 520 px,
  full width at ≤640 px) holding `<TaskView layout="panel" onDetails={() => navigate("/tasks/" + id)}>`. With
  `bot_not_running`, a `Result` says `tasks.botNotRunning` ("The bot is not running: tasks show while it runs."/"Бот
  ажиллахгүй байна: даалгавар бот ажиллаж байхад харагдана.") with a button to `/`. Group keys: waitingOnYou "Waiting on
  you"/"Таныг хүлээж", running "Running"/"Явж байна", queued "Queued"/"Дараалалд", waitingOnOthers "Waiting on someone
  else"/"Бусдыг хүлээж", finished "Finished, last 30 days"/"Дууссан, сүүлийн 30 хоног". Filters: allProjects "All
  projects"/"Бүх төсөл", everyone "Everyone"/"Бүгд", search "#14 or any words"/"#14 эсвэл дурын үг", none "No tasks
  here"/"Энд даалгавар алга".
- [ ] **Step 6: Route it** in `App.tsx`: `/tasks` renders `<TasksPage navigate={navigate}>`.
- [ ] **Step 7: Run** `cd ui && npx vitest run && npx tsc --noEmit` — PASS.
- [ ] **Step 8: Commit** — "List the tasks by what they need, and open one in a side panel".

### Task 9: A task's own page, with its log

**Files:**
- Create: `ui/src/desktop/tasks/TaskPage.tsx`, `ui/src/desktop/tasks/TaskPage.test.tsx`
- Modify: `ui/src/manage/LogsPage.tsx` (export the rows; task numbers link), `ui/src/App.tsx` (`/tasks/<id>`, the rail's
  selection), the dictionaries

**Interfaces:**
- Consumes: `TaskView` (Task 8); `taskTimeline`; `getLogs({ task })` (D-1's endpoint, served by `dispatch ui` itself).
- Produces: `<TaskPage taskId navigate>`; `export function LogRows({ lines, onTask? })` from `LogsPage.tsx`.

- [ ] **Step 1: Tests first:** `/tasks/14` shows TaskView on the left and beside it the facts (project, person,
  priority, branch `dispatch/14` from the timeline, the pull request), each run's kind (`tasks.kind.PLAN`
  "Planning"/"Төлөвлөх", `EXECUTE` "Execution"/"Хэрэгжүүлэх", `DELIVER` "Delivery"/"Хүргэх"), model is not in the payload
  so it is not shown, its duration and cost, and the task's log rows from `getLogs({ lines: 200, task: 14 })`; with the
  bot not running the log still shows and the left side says the bot is not running; Back calls `navigate("/tasks")`;
  on the Logs page a row's `#14` is a link that calls `navigate("/tasks/14")`.
- [ ] **Step 2: Run** `cd ui && npx vitest run src/desktop/tasks src/manage/LogsPage.test.tsx` — FAIL.
- [ ] **Step 3: Implement:** move LogsPage's entry rendering into an exported `LogRows({ lines, onTask })` (LogsPage uses
  it unchanged, passing `onTask` for the link); `TaskPage` lays out a two-column grid (one column at ≤900 px): TaskView
  (`layout="page"`), then panels for the facts, the runs (from `taskTimeline(id).runs`), the timeline and `LogRows`.
  `App.tsx`: a path `/tasks/<digits>` renders `<TaskPage taskId navigate>` with the rail's `/tasks` selected; LogsPage
  receives `navigate`. Keys: back "Tasks"/"Даалгавар", facts "Facts"/"Мэдээлэл", project "Project"/"Төсөл", requester
  "Given by"/"Өгсөн", branch "Branch"/"Салбар", runs "Runs"/"Ажиллагаа", timeline "Timeline"/"Явц", log "Log"/"Лог",
  cost "Cost"/"Зардал", noCost "no cost reported"/"зардал мэдэгдээгүй".
- [ ] **Step 4: Run** `cd ui && npx vitest run && npx tsc --noEmit` — PASS.
- [ ] **Step 5: Commit** — "Give each task a page of its own, with its log".

### Task 10: Browser tests, the decision, and the docs

**Files:** Create `ui/e2e/tasks.spec.ts`, `docs/adr/0030-the-desktop-reaches-the-bot-through-a-desk-port.md`; modify
`ui/e2e/language.spec.ts` (add `/tasks` to the phone-width paths), `SECURITY.md`, `docs/ARCHITECTURE.md`,
`README.en.md`, `README.md`.

- [ ] **Step 1: The browser test** (the e2e runs `dispatch ui` alone, so the bot is never running):

```ts
import { expect, test } from "@playwright/test";

test("without the bot the tasks page says so in either language, and the rest still works", async ({ page }) => {
  await page.goto("/tasks");
  await expect(page.getByText("The bot is not running: tasks show while it runs.")).toBeVisible();
  await page.getByRole("button", { name: "Монгол" }).click();
  await expect(page.getByText("Бот ажиллахгүй байна: даалгавар бот ажиллаж байхад харагдана.")).toBeVisible();
  await expect(page.getByText(/Таныг хүлээж/)).toHaveCount(0);
  await page.getByRole("menuitem", { name: "Тойм" }).click();
  await expect(page.getByRole("heading", { name: "Тойм" })).toBeVisible();
});
```

- [ ] **Step 2: Run** `(cd ui && npm run build) && ./mvnw -q -o -Pui package -DskipTests && (cd ui && npm run e2e)`; fix
  until PASS.
- [ ] **Step 3: Screenshots:** the Даалгавар page with its panel and a task's page, at 1280 and 390, in Mongolian, from
  the built pages with a mocked API (D-1's way); fix what reads badly; send the owner the Mongolian ones.
- [ ] **Step 4: Docs:** ADR 0030 (the desk port: why not the database, why not the bot serving the desktop; the token
  file; the owner's view and ADR 0020 unchanged; consequences: a second listener in the bot, the desktop needs the bot
  for tasks); SECURITY.md (the desk port, its token file, what reading it allows); ARCHITECTURE (the `ui` component row,
  the decisions table, a D-2a milestone row); README.en.md (the Tasks page in "Manage it in the browser") and README.md
  (one line).
- [ ] **Step 5: Commit** — "Test the tasks page in a browser, and write the desk port down".

### Task 11: Push, watch, review, merge

- [ ] **Step 1:** Scan the diff (`git diff main > ../d2a.diff`; the Global Constraints' patterns, `"$HOME"` included).
- [ ] **Step 2:** Push `d2a-tasks`, open a draft PR against `main`, watch the run on the three OSes to the end, fix red.
- [ ] **Step 3:** A fresh reviewer on the whole branch with the spec and this plan; fix Critical and Important
  test-first.
- [ ] **Step 4:** Merge into local `main` with `--no-ff`; the whole suite and `npm test` on the merge; ask the owner
  before pushing `main` or deploying (the bot gains a listener, and the desktop needs the new bot for tasks).
