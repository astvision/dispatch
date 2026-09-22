# W-3 Remote workers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In a team, every member's tasks run on that member's own computer: the team machine offers each claimed run to one of the requester's paired workers over HTTP, the worker runs it with today's `JobRunner` and reports back, and a team config without `workers` is refused.

**Architecture:** W-2's `Worker` seam gets its second implementation. `RemoteWorkers` (on the team machine) is a `Worker` that parks the `Job` as an *offer* for the requester's member, blocks the Coordinator's thread until one of that member's computers reports a `JobResult`, and keeps a 60 s *lease* that the worker renews with progress. `WorkerApi` is a second `com.sun.net.httpserver` server on `127.0.0.1:<workers.port>` — no cookies, no pages, no shared state with `dispatch ui` — that turns the six routes into `RemoteWorkers` and `WorkerKeys` calls. On the member's computer `dispatch worker run` is a poll loop around the same `JobRunner`, with its own `ActiveRuns` for cancel and its own worktrees, clones, `claude` and `gh`. The store learns three things: a member's workers (SHA-256 only), the task's worker, and that a run's agent session started (which is no longer "a process exists on this machine").

**Tech Stack:** Java 25, JUnit 6, SQLite via the existing `Database`/`Tx`, Jackson 2.22.2 through `dispatch.Json` (no JSR-310 module registered), `java.net.http.HttpClient` and `com.sun.net.httpserver.HttpServer` from the JDK, no framework and no new dependency (ADR 0002).

**Spec:** `docs/superpowers/specs/2026-09-22-team-workers-design.md` (milestone W-3: Configuration, Protocol, Security, Error handling, Testing).

## Global Constraints

- The worker endpoints are their own server on `127.0.0.1:<workers.port>`, with their own authentication; they share nothing with `dispatch ui` but the jar and `ApiException`.
- Six routes, all `POST`, all JSON in and out except the attachment body: `/api/worker/pair`, `/api/worker/next`, `/api/worker/progress`, `/api/worker/attachment`, `/api/worker/result`, `/api/worker/projects`.
- Every route but `/api/worker/pair` needs the header `Authorization: Bearer <worker key>`; the key names the member whose jobs that worker may take.
- A pairing code is 8 characters from the alphabet `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no I, O, 0 or 1), valid for 10 minutes, usable once.
- A worker key is 32 random bytes as base64url without padding; the team machine stores only its lowercase-hex SHA-256 and compares with `MessageDigest.isEqual`.
- The key is never logged, never stored in clear, and appears in exactly one answer: `/api/worker/pair`.
- `/api/worker/next` long-polls for up to 25 s; a worker posts progress every 10 s; a lease expires 60 s after the last progress; a worker counts as connected while its last request is under 60 s old.
- Statuses and error codes, body `{"error": "<code>", "message": "<text>"}`: 400 `invalid`, 401 `unauthorized`, 401 `pairing_code`, 403 `host`, 403 `not_your_run`, 404 `not_found`, 405 `method`, 409 `lease_expired`, 413 `too_large`, 500 `internal`.
- `workers.publicUrl` must be `https://`; plain `http://` is accepted only for host `127.0.0.1`. `workers.port` is 1–65535. Request bodies are at most 64 KiB.
- The `Host` header must be `127.0.0.1:<port>`, `localhost:<port>` or the host (with its port, if any) of `workers.publicUrl`; anything else is 403 `host`.
- `Job` and `JobResult` are unchanged from W-2 and travel as they are; `/api/worker/next` answers `{"job": null}` or `{"job": <Job>}`.
- A task always goes back to the worker that holds its worktree (`task.worker_id`); while that worker is offline the task waits and is never claimed.
- Exactly one outcome transition per run, made only by the `Coordinator` (W-2): a lease that expires returns `JobResult.failed(INTERRUPTED, …)` to the Coordinator, it does not write the store itself.
- No behaviour changes in personal mode: a config without `workers` runs `JobRunner` in this process, exactly as today.
- Commit messages end with `Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z`.
- Verify with `./mvnw -q -B verify`.

Rulings made while planning (each with its cost if wrong):

1. **Team mode is a config with more than one distinct member** across all groups; such a config must have `workers`. A single-member config may still declare `workers` and then also runs everything remotely. Cost if wrong: a two-person team that wants its tasks on the team machine cannot have that — which is the spec's intent — and the trigger would become an explicit `mode:` key.
2. **`workers` present means every job is remote**: in team mode `App` builds no `JobRunner` at all, so the team machine never needs `claude` or `gh` for runs. Cost if wrong: a mixed instance (some members local) needs a per-member choice in `Job` construction instead of one `Worker`.
3. **Pairing is a seventh-less, sixth route `/api/worker/pair`, and the only one without a Bearer key** — the code is the credential. The spec's "five routes" counts the authenticated protocol; pairing has to reach the same server. Cost if wrong: pairing moves onto `dispatch ui` and W-4's setup has to run there.
4. **An unknown key and a revoked key both answer 401 with the same message** ("this worker key is not valid any more: run `dispatch worker pair` again"). The spec's wording for the revoked case is what the member sees; telling the two apart would let anyone probe which keys once existed. Cost if wrong: one more error code and a revoked-key branch in `WorkerKeys.authenticate`.
5. **Whether a run may be claimed is decided in SQL**, from `worker.last_seen_at` and `task.worker_id`: `Runs.claimNext` takes a nullable `workerSeenSince` and, when it is set, claims only runs whose requester has a worker that reported since then — and, once the task has a worker, only that one. Cost if wrong: the gate moves into the Scheduler as a supplied set of blocked requesters, which cannot express the per-task affinity and would fail a follow-up that must wait.
6. **A blocked offer is watched by the Coordinator's own thread**, not by a sweeper: `RemoteWorkers.run` waits on a `CompletableFuture` in 200 ms slices and gives up itself when the offer or its lease expired. Cost if wrong: a ticker thread in `App` and an `expireLeases()` call, plus the shutdown ordering that goes with it.
7. **An offer nobody takes within 60 s fails as `INTERRUPTED`** ("no worker took this run"). It can only happen when a worker disappears between the claim and its next poll, because ruling 5 stops the claim otherwise. Cost if wrong: the offer waits forever and the run holds a concurrency slot.
8. **`RemoteWorkers` throws `dispatch.ui.ApiException`** so the HTTP status and code live with the rule that produced them. Cost if wrong: move `ApiException` up to `dispatch` and re-import it in four files.
9. **The pid/session split is a new `run.agent_started_at` column**, written on every agent start; `pid`/`pid_start` stay for agents this machine started, so `Recovery` keeps killing exactly its own orphans. A remote worker kills its own at `dispatch worker run` startup, from a file per run under its state directory. Cost if wrong: `Runs.agentStartedBefore` keys off `worker_id IS NOT NULL OR pid IS NOT NULL` instead, which cannot tell "no agent ran" from "a local agent ran".
10. **The worker-side loop keeps its own `ActiveRuns`** and uses the existing public `register(taskId, seq)`; no new constructor and no narrower handle. The coordinator side gets one setter, `ActiveRun.reportActivity(AgentActivity)`, fed by progress every 10 s (W-2 ruling 8). Cost if wrong: extracting a `CancelHandle` interface later is a mechanical rename.
11. **The team machine keeps no copy of an attachment**: `/api/worker/attachment` downloads from Telegram into an owner-only file under the state directory, streams it and deletes it in a `finally`. Cost if wrong: a large file is fetched once per worker instead of once, which is the current behaviour anyway.
12. **`/api/worker/projects` is also the worker's startup call**: it answers the team name, the delivery author and the member's projects, so `worker.yaml` holds only local paths and per-project overrides, as the spec's example shows. Cost if wrong: `authorName`/`authorEmail` move into `Job` and `worker.yaml` grows the fields W-4 would have to write.
13. **`/worker revoke` takes the number the list shows** (`/worker revoke 3`): a member may revoke their own, an admin anyone's. Cost if wrong: revoking by name needs a name-uniqueness rule per member.
14. **Splitting a message into topics still runs the agent on the team machine** (`Splitter`, ADR 0013); moving it behind the worker protocol is not in W-3. Cost if wrong: a team machine still needs `claude` installed for ✂️, which contradicts "no claude on the team machine" until a later milestone moves it.
15. **Everything new lives in `dispatch.worker`** (W-2 ruling 12's cost, paid here); `dispatch.core` keeps only the `ActiveRun` setter and the `JobEvents` change. Cost if wrong: one package move.
16. **A worker's name is not unique**: two computers may be called `laptop`, and the list shows the id. Cost if wrong: a uniqueness check per member in `WorkerKeys.pair`.

## File Structure

- Modify `src/main/java/dispatch/config/Config.java` — add `Workers workers` and `Config.Workers`.
- Modify `src/main/java/dispatch/config/ConfigLoader.java` — read and validate `workers`.
- Create `src/main/resources/db/014-agent-started.sql`, `src/main/resources/db/015-workers.sql`; register both in `src/main/java/dispatch/store/Database.java`.
- Modify `src/main/java/dispatch/store/Runs.java` — `recordAgentStarted`, `agentStartedBefore`, `claimNext`'s worker gate.
- Modify `src/main/java/dispatch/store/Tasks.java` — `recordWorker`, `workerOf`.
- Create `src/main/java/dispatch/store/Workers.java` — SQL for `worker` and `pairing_code`.
- Modify `src/main/java/dispatch/core/JobEvents.java`, `JobRunner.java`, `Coordinator.java`, `RunTransitions.java`, `Recovery.java`, `ActiveRuns.java`, `Scheduler.java`, `TaskService.java`.
- Create `src/main/java/dispatch/worker/WorkerKeys.java`, `RemoteWorkers.java`, `WorkerApi.java`, `WorkerConfig.java`, `WorkerConfigLoader.java`, `WorkerClient.java`, `WorkerLoop.java`, `LocalAgents.java`, `WorkerCommand.java`.
- Modify `src/main/java/dispatch/domain/OutboxKind.java`, `src/main/java/dispatch/telegram/Renderer.java`, `UpdateHandler.java`, `src/main/resources/messages_mn.properties`.
- Modify `src/main/java/dispatch/cli/Cli.java`, `src/main/java/dispatch/cli/Locations.java`, `src/main/java/dispatch/Main.java`, `src/main/java/dispatch/App.java`.
- Tests: create `src/test/java/dispatch/worker/{WorkerKeysTest,RemoteWorkersTest,WorkerApiTest,WorkerProtocolTest,WorkerConfigLoaderTest,WorkerLoopTest}.java` and `src/test/java/dispatch/TeamWorkersTest.java`; modify `src/test/java/dispatch/{AppTest}.java`, `src/test/java/dispatch/config/ConfigLoaderTest.java`, `src/test/java/dispatch/core/{JobRunnerTest,CoordinatorTest,RecoveryTest,SchedulerTest,RunExecutorTest}.java`, `src/test/java/dispatch/telegram/{UpdateHandlerTest,RendererTest}.java`, `src/test/java/dispatch/cli/CliTest.java`.
- Modify `docs/superpowers/specs/2026-09-22-team-workers-design.md` — the Protocol line about `/api/worker/next` (Task 7 only).

---

### Task 1: The `workers` block, and a team config without it is refused

**Files:**
- Modify: `src/main/java/dispatch/config/Config.java`, `src/main/java/dispatch/config/ConfigLoader.java`
- Test: `src/test/java/dispatch/config/ConfigLoaderTest.java`

**Interfaces:**
- Consumes: `ConfigLoader.load(Path, Map<String,String>)`, `ConfigException`, `Config.Telegram`, `Config.Member`.
- Produces:
  - `public record Config.Workers(String publicUrl, int port)` with `@JsonCreator static Workers fromYaml(String publicUrl, Integer port)`
  - `Config` gains a `Workers workers` component, between `delivery` and `secrets`
  - `public boolean Config.isTeam()`

- [ ] **Step 1: Write the failing tests** — add to `src/test/java/dispatch/config/ConfigLoaderTest.java`:

```java
    @Test
    void aTeamConfigNeedsAWorkersBlock() throws Exception {
        Path file = write(twoMembers(""));

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(file, env()));

        assertTrue(error.getMessage().contains("workers: required when more than one member is configured"), error.getMessage());
    }

    @Test
    void aTeamConfigWithWorkersIsLoaded() throws Exception {
        Path file = write(twoMembers("""
                workers:
                  publicUrl: https://team.example.com
                  port: 7880
                """));

        Config config = ConfigLoader.load(file, env());

        assertTrue(config.isTeam());
        assertEquals("https://team.example.com", config.workers().publicUrl());
        assertEquals(7880, config.workers().port());
    }

    @Test
    void aPersonalConfigNeedsNoWorkersAndIsNotATeam() throws Exception {
        Config config = ConfigLoader.load(write(""), env());

        assertNull(config.workers());
        assertFalse(config.isTeam(), "one member is always the requester, so nothing has to run elsewhere");
    }

    @Test
    void everyBadWorkersValueIsReported() throws Exception {
        Path file = write(twoMembers("""
                workers:
                  publicUrl: http://team.example.com
                  port: 70000
                """));

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(file, env()));

        assertTrue(error.getMessage().contains("workers.publicUrl: must start with https://"), error.getMessage());
        assertTrue(error.getMessage().contains("workers.port: must be from 1 to 65535, got 70000"), error.getMessage());
    }

    @Test
    void plainHttpIsAcceptedOnlyOnTheLoopbackAddress() throws Exception {
        Config config = ConfigLoader.load(write(twoMembers("""
                workers:
                  publicUrl: http://127.0.0.1:7880
                  port: 7880
                """)), env());

        assertEquals("http://127.0.0.1:7880", config.workers().publicUrl(), "tests pair over loopback without TLS");
    }

    @Test
    void workersWithoutAPortIsReportedAsAPortProblem() throws Exception {
        Path file = write(twoMembers("""
                workers:
                  publicUrl: https://team.example.com
                """));

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(file, env()));

        assertTrue(error.getMessage().contains("workers.port: must be from 1 to 65535, got 0"), error.getMessage());
    }
```

Add the helper next to the file's existing YAML helpers (keep the file's own `write(String extra)` / `env()` names if they differ — this task adds no new style):

```java
    /** The valid config with a second member, which makes it a team. */
    private static String twoMembers(String extra) {
        return """
                telegram:
                  groups:
                    - name: backend
                      chatId: -100
                      members:
                        - id: 100
                          name: Bold
                        - id: 200
                          name: Ali
                      projects: [alm]
                """ + extra;
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -B test -Dtest=ConfigLoaderTest`
Expected: FAIL — compilation errors, `cannot find symbol: method workers()` and `cannot find symbol: method isTeam()` on `Config`.

- [ ] **Step 3: Add the block and its rules.** In `src/main/java/dispatch/config/Config.java` add `Workers workers` to the record's components, after `Delivery delivery` and before `Secrets secrets`:

```java
public record Config(
        String team,
        Path stateDir,
        Telegram telegram,
        Scheduler scheduler,
        Worktrees worktrees,
        Limits limits,
        Map<String, Agent> agents,
        List<Project> projects,
        Delivery delivery,
        Workers workers,
        Secrets secrets) {

    /**
     * A team: more than one person across the groups, so tasks belong to different people and must run on different
     * computers (ADR 0020). A personal bot's only member is always the requester.
     */
    public boolean isTeam() {
        return telegram.groups().stream().flatMap(group -> group.members().stream())
                .map(Member::id).distinct().count() > 1;
    }
```

and, beside the other nested records:

```java
    /**
     * Where members' computers reach this machine (spec: Configuration). Null in personal mode, where runs happen in this
     * process.
     *
     * @param publicUrl the owner's tunnel or reverse proxy, e.g. https://team.example.com
     * @param port      Dispatch listens on 127.0.0.1:port; the proxy forwards to it
     */
    public record Workers(String publicUrl, int port) {

        @JsonCreator
        static Workers fromYaml(@JsonProperty("publicUrl") String publicUrl, @JsonProperty("port") Integer port) {
            // 0 rather than a mapping error, so a missing port is reported with everything else that is wrong.
            return new Workers(publicUrl, port == null ? 0 : port);
        }
    }
```

In `src/main/java/dispatch/config/ConfigLoader.java` add the field to the YAML shape:

```java
    record ConfigFile(
            String team,
            String stateDir,
            Config.Telegram telegram,
            Config.Delivery delivery,
            Config.Scheduler scheduler,
            Config.Worktrees worktrees,
            Config.Limits limits,
            Map<String, Config.Agent> agents,
            List<Config.Project> projects,
            Config.Workers workers) {
    }
```

validate it after `validateDelivery` in `load`:

```java
        Config.Delivery delivery = validateDelivery(raw.delivery(), errors);
        Config.Workers workers = validateWorkers(raw.workers(), telegram, errors);
```

and pass it when building the result:

```java
        return new Config(raw.team(), stateDir, telegram, raw.scheduler(), worktrees, raw.limits(), Map.copyOf(agents),
                projects, delivery, workers, new Config.Secrets(token, ghToken));
```

Add the rules themselves next to `validateDelivery`:

```java
    /**
     * In a team every member's tasks run on their own computer, so the team machine must be reachable by their workers
     * (spec: Configuration). A personal Dispatch needs none and runs its jobs in this process.
     */
    private static Config.Workers validateWorkers(Config.Workers workers, Config.Telegram telegram, List<String> errors) {
        boolean team = telegram.groups().stream().flatMap(group -> group.members().stream())
                .map(Config.Member::id).distinct().count() > 1;
        if (workers == null) {
            if (team) {
                errors.add("workers: required when more than one member is configured; each member's tasks run on their own "
                        + "computer (publicUrl and port, see deploy/example.yaml)");
            }
            return null;
        }
        if (isBlank(workers.publicUrl())) {
            errors.add("workers.publicUrl: required, the https URL members' workers reach this machine on");
        } else if (!isWorkerUrl(workers.publicUrl())) {
            errors.add("workers.publicUrl: must start with https:// (plain http only for 127.0.0.1), got '"
                    + workers.publicUrl() + "'");
        }
        if (workers.port() < 1 || workers.port() > 65535) {
            errors.add("workers.port: must be from 1 to 65535, got " + workers.port());
        }
        return workers;
    }

    /** Worker keys travel on every request: https everywhere, except loopback, which tests pair over. */
    public static boolean isWorkerUrl(String url) {
        try {
            URI uri = new URI(url);
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return uri.getHost() != null;
            }
            return "http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(uri.getHost());
        } catch (URISyntaxException e) {
            return false;
        }
    }
```

with `import java.net.URI;` and `import java.net.URISyntaxException;`.

Every other `new Config(...)` — `AppTest`, and whichever other tests build one — gets `null` for `workers` in that position. Find them with `grep -rn "new Config(" src`.

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -B verify`
Expected: PASS, including the six new `ConfigLoaderTest` cases.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/config/Config.java src/main/java/dispatch/config/ConfigLoader.java \
  src/test/java/dispatch/config/ConfigLoaderTest.java src/test/java/dispatch/AppTest.java
git commit -m "Take a workers block and refuse a team config without one

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 2: "The agent session started" stops meaning "a process exists here"

**Files:**
- Create: `src/main/resources/db/014-agent-started.sql`
- Modify: `src/main/java/dispatch/store/Database.java`, `src/main/java/dispatch/store/Runs.java`, `src/main/java/dispatch/core/JobEvents.java`, `src/main/java/dispatch/core/JobRunner.java`, `src/main/java/dispatch/core/Coordinator.java`, `src/main/java/dispatch/core/RunTransitions.java`, `src/main/java/dispatch/core/Recovery.java`
- Test: `src/test/java/dispatch/core/RecoveryTest.java`, `src/test/java/dispatch/core/JobRunnerTest.java`, `src/test/java/dispatch/core/CoordinatorTest.java`

**Interfaces:**
- Consumes: `Runs.withStatus(Tx, RunStatus)`, `ProcessTrees.findSame(long, Instant)`, `RunTransitions.failed(...)`.
- Produces:
  - `void JobEvents.agentStarted(Long pid, Instant processStart)` (was `(long pid, Instant processStart)`)
  - `public void RunTransitions.agentStarted(long taskId, int seq, Long pid, Instant processStart)` (replaces `recordProcess`)
  - `public static void Runs.recordAgentStarted(Tx tx, long taskId, int seq, Instant at, Long pid, Instant pidStart)` (replaces `recordProcess`)
  - `Runs.agentStartedBefore` now reads `agent_started_at IS NOT NULL`

- [ ] **Step 1: Write the failing tests.** In `src/test/java/dispatch/core/RecoveryTest.java` add:

```java
    @Test
    void aRunOfARemoteWorkerHasNoProcessHereAndIsOnlyFailed() {
        Runs.NewRun queued = run();
        db.transaction(tx -> Runs.recordAgentStarted(tx, queued.taskId(), queued.seq(), clock.instant(), null, null));

        new Recovery(db, transitions, Duration.ofSeconds(1)).run();

        Map<String, String> row = SqlRows.single(dbFile, "SELECT status, failure_reason FROM run WHERE task_id = ?",
                queued.taskId());
        assertEquals("FAILED", row.get("status"));
        assertEquals("INTERRUPTED", row.get("failure_reason"),
                "the member retries; the worker's own startup deals with the agent it left behind");
    }
```

Use the file's existing helpers for making a RUNNING run (the ones `orphanIsKilled…` uses) instead of the `run()` sketched above, and rename the two calls the file already makes:
`transitions.recordProcess(run.taskId(), run.seq(), orphan.pid(), start)` becomes `transitions.agentStarted(run.taskId(), run.seq(), orphan.pid(), start)`, and
`Runs.recordProcess(tx, run.taskId(), run.seq(), orphan.pid(), realStart.minusSeconds(60))` becomes
`Runs.recordAgentStarted(tx, run.taskId(), run.seq(), clock.instant(), orphan.pid(), realStart.minusSeconds(60))`.

In `src/test/java/dispatch/core/CoordinatorTest.java` extend `whatTheWorkerReportsWhileItRunsIsRecordedAtOnce` and add one case:

```java
    @Test
    void whatTheWorkerReportsWhileItRunsIsRecordedAtOnce() {
        long id = queue("Fix the login timeout");
        Worker worker = (job, events, control) -> {
            events.worktreeCreated("/var/lib/dispatch/worktrees/" + id, "abc123");
            events.agentStarted(4242L, Instant.parse("2026-09-17T10:00:01Z"));
            return JobResult.succeeded(agentResult(PLAN_JSON));
        };

        coordinator(projects(List.of(ALM)), worker).execute(claim());

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("/var/lib/dispatch/worktrees/" + id, task.get("worktree"));
        assertEquals("abc123", task.get("base_sha"));
        Map<String, String> run = row("SELECT pid, agent_started_at FROM run WHERE task_id = ?", id);
        assertEquals("4242", run.get("pid"));
        assertNotNull(run.get("agent_started_at"));
    }

    @Test
    void aRemoteWorkersAgentIsRecordedAsStartedWithoutAProcess() {
        long id = queue("Fix the login timeout");
        Worker worker = (job, events, control) -> {
            events.agentStarted(null, null);
            return JobResult.failed(FailureReason.AGENT, "model overloaded", null);
        };

        coordinator(projects(List.of(ALM)), worker).execute(claim());

        Map<String, String> run = row("SELECT pid, pid_start, agent_started_at FROM run WHERE task_id = ?", id);
        assertNull(run.get("pid"), "the process is on the member's computer, not here");
        assertNull(run.get("pid_start"));
        assertNotNull(run.get("agent_started_at"), "the next run of this kind must know the session was started");
    }
```

In `src/test/java/dispatch/core/JobRunnerTest.java` change the `Recorder`'s event so the null case is explicit:

```java
        @Override
        public void agentStarted(Long pid, Instant processStart) {
            this.pid = pid == null ? 0 : pid;
            this.processStart = processStart;
        }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -B test -Dtest='RecoveryTest+CoordinatorTest+JobRunnerTest'`
Expected: FAIL — compilation errors, `cannot find symbol: method recordAgentStarted`, `cannot find symbol: method agentStarted`, and `agentStarted(long,Instant) in JobEvents cannot be applied to (<null>,<null>)`.

- [ ] **Step 3: Split the fact from the process.** Create `src/main/resources/db/014-agent-started.sql`:

```sql
-- W-3: "this run's agent session started" is its own fact. pid stays for an agent this machine started, so startup
-- recovery kills exactly its own orphans; a remote worker's run has none and its own startup cleans up after it.
ALTER TABLE run ADD COLUMN agent_started_at TEXT;
UPDATE run SET agent_started_at = pid_start WHERE pid IS NOT NULL;
```

Register it in `src/main/java/dispatch/store/Database.java`:

```java
    private static final List<String> MIGRATIONS = List.of("db/001-init.sql", "db/002-execution.sql", "db/003-private-messages.sql", "db/004-priority.sql", "db/005-drafts.sql", "db/006-topics.sql",
            "db/007-outbox-edits.sql", "db/008-split-drafts.sql", "db/009-join-requests.sql",
            "db/010-build-session.sql", "db/011-run-model.sql", "db/012-run-cause.sql",
            "db/013-attachments.sql", "db/014-agent-started.sql");
```

In `src/main/java/dispatch/store/Runs.java` replace `recordProcess` and the resume question:

```java
    /**
     * Records that this run's agent session started.
     *
     * @param at      when the store learned it, which is what the next run of this kind reads
     * @param pid     the agent's process, null when it runs on a member's own computer
     * @param pidStart when that process started; null with a null pid
     */
    public static void recordAgentStarted(Tx tx, long taskId, int seq, Instant at, Long pid, Instant pidStart) {
        tx.update("UPDATE run SET agent_started_at = ?, pid = ?, pid_start = ? WHERE task_id = ? AND seq = ?",
                at, pid, pidStart, taskId, seq);
    }
```

```java
    /**
     * Whether a run of {@code kind} before {@code seq} got as far as starting its agent, and so started the phase's agent
     * session: a run that failed during setup never did, and resuming its session would fail. Keyed off the session, not
     * off a process id: a remote worker's agent runs on the member's computer and leaves no pid here.
     */
    public static boolean agentStartedBefore(Tx tx, long taskId, RunKind kind, int seq) {
        return tx.one("SELECT 1 AS found FROM run WHERE task_id = ? AND kind = ? AND seq < ? AND agent_started_at IS NOT NULL"
                + " LIMIT 1", row -> true, taskId, kind, seq).isPresent();
    }
```

In `src/main/java/dispatch/core/JobEvents.java`:

```java
    /**
     * The run's agent session started: recorded at once, so the next run of this kind resumes it instead of starting over.
     *
     * @param pid          the agent's process, null for a remote worker — the process is on the member's computer and
     *                     startup recovery here only kills this machine's own orphans (ADR 0008)
     * @param processStart when that process started; null with a null pid
     */
    void agentStarted(Long pid, Instant processStart);
```

In `src/main/java/dispatch/core/RunTransitions.java` replace `recordProcess`:

```java
    /** @param pid null when the agent runs on a member's own computer */
    public void agentStarted(long taskId, int seq, Long pid, Instant processStart) {
        db.transaction(tx -> Runs.recordAgentStarted(tx, taskId, seq, clock.instant(), pid, processStart));
    }
```

In `src/main/java/dispatch/core/Coordinator.java`, in `events(...)`:

```java
            @Override
            public void agentStarted(Long pid, Instant processStart) {
                transitions.agentStarted(claimed.taskId(), claimed.seq(), pid, processStart);
            }
```

`src/main/java/dispatch/core/JobRunner.java` needs no change (`handle.process().pid()` boxes to `Long`); add the note to `Recovery`'s class javadoc in `src/main/java/dispatch/core/Recovery.java`:

```java
/**
 * Startup cleanup after a crash (ADR 0008): runs still marked RUNNING belong to a previous process. Their agents are
 * killed if still alive, and the runs fail as interrupted so members can decide to retry. Queued runs are untouched.
 * A run without a pid had its agent on a member's own computer: only the run is failed here, and that worker kills what
 * it left behind when it next starts.
 */
```

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -B verify`
Expected: PASS, including `RunExecutorTest` (its `pid`/`pid_start` assertions still hold: a locally started agent still records both).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/014-agent-started.sql src/main/java/dispatch/store/Database.java \
  src/main/java/dispatch/store/Runs.java src/main/java/dispatch/core/JobEvents.java \
  src/main/java/dispatch/core/RunTransitions.java src/main/java/dispatch/core/Coordinator.java \
  src/main/java/dispatch/core/Recovery.java src/test/java/dispatch/core/RecoveryTest.java \
  src/test/java/dispatch/core/CoordinatorTest.java src/test/java/dispatch/core/JobRunnerTest.java
git commit -m "Record that a run's agent session started apart from its local process

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 3: Worker keys and the one-time codes that make them

**Files:**
- Create: `src/main/resources/db/015-workers.sql`, `src/main/java/dispatch/store/Workers.java`, `src/main/java/dispatch/worker/WorkerKeys.java`
- Modify: `src/main/java/dispatch/store/Database.java`, `src/main/java/dispatch/store/Tasks.java`
- Test: `src/test/java/dispatch/worker/WorkerKeysTest.java`

**Interfaces:**
- Consumes: `Database.transaction/transactionReturning`, `Tx.insert/update/one/list`, `dispatch.domain.Requester`.
- Produces:
  - `public record Workers.Paired(long id, String memberRef, String name, String keySha256, Instant createdAt, Instant lastSeenAt)`
  - `Workers.insert/active/ofMember/find/touch/revoke/revokeMembersExcept/insertCode/useCode/deleteExpiredCodes`, `Workers.SEEN_WITHIN`
  - `public static void Tasks.recordWorker(Tx tx, long id, long workerId, Instant now)`, `public static Optional<Long> Tasks.workerOf(Tx tx, long id)`
  - `public final class WorkerKeys` with `newCode(Requester)`, `of(String)`, `pair(String, String)`, `authenticate(String)`, `revoke(long, String, boolean)`, `revokeWorkersOfFormerMembers(Set<String>)`, `record NewKey(long workerId, String memberRef, String key)`, `CODE_LENGTH`, `CODE_LIFETIME`

- [ ] **Step 1: Write the failing test** — create `src/test/java/dispatch/worker/WorkerKeysTest.java`:

```java
package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Workers;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pairing and the keys it makes: a code works once, a key is only ever stored as its hash, and revoking is final. */
class WorkerKeysTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private final TestClock clock = new TestClock(Instant.parse("2026-09-23T10:00:00Z"));
    private WorkerKeys keys;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        keys = new WorkerKeys(db, clock);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aCodePairsOneComputerAndNeverASecond() {
        String code = keys.newCode(BOLD);

        WorkerKeys.NewKey paired = keys.pair(code, "ann-laptop").orElseThrow();

        assertEquals("telegram:100", paired.memberRef());
        assertTrue(keys.pair(code, "another-laptop").isEmpty(), "a pairing code is used once");
        assertEquals(1, keys.of("telegram:100").size());
    }

    @Test
    void aCodeIsEightReadableCharactersAndDiesAfterTenMinutes() {
        String code = keys.newCode(BOLD);

        assertEquals(8, code.length(), code);
        assertTrue(code.matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}"), "no I, O, 0 or 1 to mistype: " + code);
        clock.advance(WorkerKeys.CODE_LIFETIME.plusSeconds(1));
        assertTrue(keys.pair(code, "ann-laptop").isEmpty(), "ten minutes is all a code gets");
    }

    @Test
    void neitherTheKeyNorTheCodeIsEverStored() {
        String code = keys.newCode(BOLD);
        WorkerKeys.NewKey paired = keys.pair(code, "ann-laptop").orElseThrow();

        String workerRow = SqlRows.single(dbFile, "SELECT * FROM worker WHERE id = ?", paired.workerId()).toString();
        String codeRow = SqlRows.single(dbFile, "SELECT * FROM pairing_code LIMIT 1").toString();
        assertFalse(workerRow.contains(paired.key()), workerRow);
        assertFalse(codeRow.contains(code), codeRow);
        assertEquals(64, SqlRows.single(dbFile, "SELECT key_sha256 FROM worker WHERE id = ?", paired.workerId())
                .get("key_sha256").length(), "a SHA-256 in lowercase hex");
    }

    @Test
    void aKeyNamesItsMemberAndAnythingElseIsRefused() {
        WorkerKeys.NewKey paired = keys.pair(keys.newCode(BOLD), "ann-laptop").orElseThrow();

        assertEquals("telegram:100", keys.authenticate(paired.key()).orElseThrow().memberRef());
        assertTrue(keys.authenticate(null).isEmpty());
        assertTrue(keys.authenticate("").isEmpty());
        assertTrue(keys.authenticate("not-a-key").isEmpty());
        // The compare is MessageDigest.isEqual over the hashes, so where a wrong key differs changes nothing.
        String firstCharDiffers = (paired.key().charAt(0) == 'A' ? 'B' : 'A') + paired.key().substring(1);
        String lastCharDiffers = paired.key().substring(0, paired.key().length() - 1)
                + (paired.key().endsWith("A") ? "B" : "A");
        assertTrue(keys.authenticate(firstCharDiffers).isEmpty(), firstCharDiffers);
        assertTrue(keys.authenticate(lastCharDiffers).isEmpty(), lastCharDiffers);
    }

    @Test
    void twoWorkersOfTheSameMemberGetDifferentKeys() {
        WorkerKeys.NewKey first = keys.pair(keys.newCode(BOLD), "laptop").orElseThrow();
        WorkerKeys.NewKey second = keys.pair(keys.newCode(BOLD), "laptop").orElseThrow();

        assertNotEquals(first.key(), second.key());
        assertEquals(List.of("laptop", "laptop"), keys.of("telegram:100").stream().map(Workers.Paired::name).toList());
    }

    @Test
    void onlyTheOwnerOrAnAdminRevokesAndARevokedKeyIsDead() {
        WorkerKeys.NewKey paired = keys.pair(keys.newCode(BOLD), "ann-laptop").orElseThrow();

        assertFalse(keys.revoke(paired.workerId(), ALI.ref(), false), "another member cannot revoke it");
        assertTrue(keys.authenticate(paired.key()).isPresent());
        assertTrue(keys.revoke(paired.workerId(), ALI.ref(), true), "an admin may revoke anyone's");
        assertTrue(keys.authenticate(paired.key()).isEmpty());
        assertFalse(keys.revoke(paired.workerId(), BOLD.ref(), false), "and it is revoked only once");
        assertTrue(keys.of(BOLD.ref()).isEmpty());
    }

    @Test
    void removingAMemberRevokesTheirWorkers() {
        WorkerKeys.NewKey bold = keys.pair(keys.newCode(BOLD), "bold-laptop").orElseThrow();
        WorkerKeys.NewKey ali = keys.pair(keys.newCode(ALI), "ali-laptop").orElseThrow();

        keys.revokeWorkersOfFormerMembers(Set.of(BOLD.ref()));

        assertTrue(keys.authenticate(bold.key()).isPresent());
        assertTrue(keys.authenticate(ali.key()).isEmpty(), "Ali is no longer a member");
    }

    @Test
    void usingAKeyRecordsWhenItWasLastSeen() {
        WorkerKeys.NewKey paired = keys.pair(keys.newCode(BOLD), "ann-laptop").orElseThrow();
        clock.advance(java.time.Duration.ofMinutes(5));

        keys.authenticate(paired.key());

        Optional<Workers.Paired> worker = keys.of(BOLD.ref()).stream().findFirst();
        assertEquals(Instant.parse("2026-09-23T10:05:00Z"), worker.orElseThrow().lastSeenAt());
    }

    @Test
    void anExpiredCodeIsSweptAway() {
        keys.newCode(BOLD);
        clock.advance(WorkerKeys.CODE_LIFETIME.plusSeconds(1));

        keys.newCode(ALI);

        Map<String, String> count = SqlRows.single(dbFile, "SELECT count(*) AS n FROM pairing_code");
        assertEquals("1", count.get("n"), "a new code sweeps the dead ones");
    }
}
```

(`TestClock.advance(Duration)` already exists; nothing in `dispatch.testing` changes.)

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -B test -Dtest=WorkerKeysTest`
Expected: FAIL — compilation errors, `package dispatch.worker does not exist` and `cannot find symbol: class Workers` in `dispatch.store`.

- [ ] **Step 3: Write the store and the keys.** Create `src/main/resources/db/015-workers.sql`:

```sql
-- W-3: a member's own computers. Only the SHA-256 of a worker key or a pairing code is ever stored, so this file
-- cannot be used to reach anyone's machine.
CREATE TABLE worker (
    id           INTEGER PRIMARY KEY,
    member_ref   TEXT NOT NULL,
    name         TEXT NOT NULL,
    key_sha256   TEXT NOT NULL UNIQUE,
    created_at   TEXT NOT NULL,
    last_seen_at TEXT,
    revoked_at   TEXT
);

CREATE INDEX worker_member ON worker (member_ref, revoked_at);

-- One-time codes from /worker, exchanged for a key within ten minutes.
CREATE TABLE pairing_code (
    code_sha256 TEXT PRIMARY KEY,
    member_ref  TEXT NOT NULL,
    member_name TEXT NOT NULL,
    created_at  TEXT NOT NULL,
    expires_at  TEXT NOT NULL,
    used_at     TEXT
);

-- A task's worktree and agent session live on one computer, so every later run of it goes back to that worker.
ALTER TABLE task ADD COLUMN worker_id INTEGER REFERENCES worker (id);
```

Register it in `Database.MIGRATIONS` after `db/014-agent-started.sql`.

Create `src/main/java/dispatch/store/Workers.java`:

```java
package dispatch.store;

import dispatch.domain.Requester;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** SQL for members' computers and the one-time codes that pair them. Keys and codes are stored only as SHA-256. */
public final class Workers {

    /** A worker counts as connected while its last request is younger than this: one lease, one long poll and a margin. */
    public static final Duration SEEN_WITHIN = Duration.ofSeconds(60);

    private static final String COLUMNS = "id, member_ref, name, key_sha256, created_at, last_seen_at";

    private Workers() {
    }

    /** @param lastSeenAt null until the worker's first request */
    public record Paired(long id, String memberRef, String name, String keySha256, Instant createdAt, Instant lastSeenAt) {
    }

    public static long insert(Tx tx, String memberRef, String name, String keySha256, Instant now) {
        return tx.insert("INSERT INTO worker (member_ref, name, key_sha256, created_at) VALUES (?, ?, ?, ?)",
                memberRef, name, keySha256, now);
    }

    /** Every worker that may still be used, for the key check. */
    public static List<Paired> active(Tx tx) {
        return tx.list("SELECT " + COLUMNS + " FROM worker WHERE revoked_at IS NULL ORDER BY id", Workers::map);
    }

    public static List<Paired> ofMember(Tx tx, String memberRef) {
        return tx.list("SELECT " + COLUMNS + " FROM worker WHERE member_ref = ? AND revoked_at IS NULL ORDER BY id",
                Workers::map, memberRef);
    }

    public static Optional<Paired> find(Tx tx, long id) {
        return tx.one("SELECT " + COLUMNS + " FROM worker WHERE id = ? AND revoked_at IS NULL", Workers::map, id);
    }

    public static void touch(Tx tx, long id, Instant now) {
        tx.update("UPDATE worker SET last_seen_at = ? WHERE id = ?", now, id);
    }

    /** @return false when it does not exist or was revoked already */
    public static boolean revoke(Tx tx, long id, Instant now) {
        return tx.update("UPDATE worker SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", now, id) == 1;
    }

    /** Revokes every worker of someone who is no longer a member; {@code memberRefs} is never empty in a valid config. */
    public static int revokeMembersExcept(Tx tx, Set<String> memberRefs, Instant now) {
        if (memberRefs.isEmpty()) {
            return 0;
        }
        Object[] params = new Object[memberRefs.size() + 1];
        params[0] = now;
        int i = 1;
        for (String ref : memberRefs) {
            params[i++] = ref;
        }
        return tx.update("UPDATE worker SET revoked_at = ? WHERE revoked_at IS NULL AND member_ref NOT IN ("
                + Tx.placeholders(memberRefs.size()) + ")", params);
    }

    /** Whether {@code memberRef} has a worker that reported since {@code since}. */
    public static boolean hasConnected(Tx tx, String memberRef, Instant since) {
        return tx.one("SELECT 1 AS found FROM worker WHERE member_ref = ? AND revoked_at IS NULL AND last_seen_at > ? LIMIT 1",
                row -> true, memberRef, since).isPresent();
    }

    public static void insertCode(Tx tx, String codeSha256, Requester member, Instant now, Instant expiresAt) {
        tx.update("INSERT INTO pairing_code (code_sha256, member_ref, member_name, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                codeSha256, member.ref(), member.name(), now, expiresAt);
    }

    /**
     * Claims a code: the member it belongs to, or empty when it is unknown, used or expired. The claim is the conditional
     * update itself, so two workers racing on one code cannot both get a key.
     */
    public static Optional<Requester> useCode(Tx tx, String codeSha256, Instant now) {
        if (tx.update("UPDATE pairing_code SET used_at = ? WHERE code_sha256 = ? AND used_at IS NULL AND expires_at > ?",
                now, codeSha256, now) != 1) {
            return Optional.empty();
        }
        return tx.one("SELECT member_ref, member_name FROM pairing_code WHERE code_sha256 = ?",
                row -> new Requester(row.string("member_ref"), row.string("member_name")), codeSha256);
    }

    public static int deleteExpiredCodes(Tx tx, Instant now) {
        return tx.update("DELETE FROM pairing_code WHERE expires_at <= ?", now);
    }

    private static Paired map(Row row) throws SQLException {
        return new Paired(row.longValue("id"), row.string("member_ref"), row.string("name"), row.string("key_sha256"),
                row.instant("created_at"), row.instant("last_seen_at"));
    }
}
```

Add to `src/main/java/dispatch/store/Tasks.java`:

```java
    /** The computer that made this task's worktree: every later run of the task goes back to it. */
    public static void recordWorker(Tx tx, long id, long workerId, Instant now) {
        tx.update("UPDATE task SET worker_id = ?, updated_at = ? WHERE id = ?", workerId, now, id);
    }

    public static Optional<Long> workerOf(Tx tx, long id) {
        return tx.one("SELECT worker_id FROM task WHERE id = ?", row -> row.longOrNull("worker_id"), id)
                .filter(workerId -> workerId != null);
    }
```

Create `src/main/java/dispatch/worker/WorkerKeys.java`:

```java
package dispatch.worker;

import dispatch.Log;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Workers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Who may reach the worker endpoints. A member asks the bot for a one-time code, their computer exchanges it for a
 * random 256-bit key, and from then on that key names the member. The team machine keeps only SHA-256 of both: a copy of
 * its database gives nobody access to anyone's computer, and the key itself is written to exactly one HTTP answer and to
 * no log.
 */
public final class WorkerKeys {

    public static final int CODE_LENGTH = 8;
    public static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    /** No I, O, 0 or 1: the member reads this code off one screen and types it on another. */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int KEY_BYTES = 32;

    private final Database db;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public WorkerKeys(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** A fresh one-time code for {@code member}; only its hash is kept, so it can never be shown again. */
    public String newCode(Requester member) {
        Instant now = clock.instant();
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        }
        String text = code.toString();
        db.transaction(tx -> {
            Workers.deleteExpiredCodes(tx, now);
            Workers.insertCode(tx, sha256(text), member, now, now.plus(CODE_LIFETIME));
        });
        Log.info("worker.code_issued", "member", member.ref(), "expires_in_minutes", CODE_LIFETIME.toMinutes());
        return text;
    }

    public List<Workers.Paired> of(String memberRef) {
        return db.transactionReturning(tx -> Workers.ofMember(tx, memberRef));
    }

    /** What a worker is told once, when it pairs. */
    public record NewKey(long workerId, String memberRef, String key) {

        @Override
        public String toString() {
            // Never let a key reach a log line through a record's own toString.
            return "NewKey[workerId=" + workerId + ", memberRef=" + memberRef + ", key=***]";
        }
    }

    /** Exchanges a pairing code for a new key; empty when the code is unknown, used or older than ten minutes. */
    public Optional<NewKey> pair(String code, String name) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        // Upper case in ROOT: the alphabet is ASCII, and a member may well type the code back in lower case.
        String normalized = code.strip().toUpperCase(java.util.Locale.ROOT);
        Optional<Requester> member = db.transactionReturning(tx -> Workers.useCode(tx, sha256(normalized), now));
        if (member.isEmpty()) {
            Log.warn("worker.pairing_refused", "reason", "unknown, used or expired code");
            return Optional.empty();
        }
        byte[] secret = new byte[KEY_BYTES];
        random.nextBytes(secret);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        long id = db.transactionReturning(tx -> Workers.insert(tx, member.get().ref(), name, sha256(key), now));
        Log.info("worker.paired", "worker", id, "member", member.get().ref(), "name", name);
        return Optional.of(new NewKey(id, member.get().ref(), key));
    }

    /**
     * The worker a key belongs to; empty for no key, an unknown key and a revoked one alike, so nobody can learn which
     * keys once existed. The compare is constant-time over the hashes.
     */
    public Optional<Workers.Paired> authenticate(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        byte[] presented = sha256Bytes(key);
        // ponytail: a linear scan over a team's handful of workers; index by hash if a team ever has hundreds.
        Optional<Workers.Paired> found = db.transactionReturning(Workers::active).stream()
                .filter(worker -> MessageDigest.isEqual(presented, HexFormat.of().parseHex(worker.keySha256())))
                .findFirst();
        found.ifPresent(worker -> db.transaction(tx -> Workers.touch(tx, worker.id(), clock.instant())));
        return found;
    }

    /** @param admin an admin may revoke anyone's worker; everybody else only their own */
    public boolean revoke(long workerId, String memberRef, boolean admin) {
        Optional<Workers.Paired> worker = db.transactionReturning(tx -> Workers.find(tx, workerId));
        if (worker.isEmpty() || (!admin && !worker.get().memberRef().equals(memberRef))) {
            return false;
        }
        boolean revoked = db.transactionReturning(tx -> Workers.revoke(tx, workerId, clock.instant()));
        if (revoked) {
            Log.info("worker.revoked", "worker", workerId, "member", worker.get().memberRef(), "by", memberRef);
        }
        return revoked;
    }

    /** Someone taken out of the config takes their computers' access with them. */
    public void revokeWorkersOfFormerMembers(Set<String> memberRefs) {
        int revoked = db.transactionReturning(tx -> Workers.revokeMembersExcept(tx, memberRefs, clock.instant()));
        if (revoked > 0) {
            Log.warn("worker.revoked_former_members", "workers", revoked);
        }
    }

    static String sha256(String value) {
        return HexFormat.of().formatHex(sha256Bytes(value));
    }

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -B test -Dtest=WorkerKeysTest`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/015-workers.sql src/main/java/dispatch/store/Database.java \
  src/main/java/dispatch/store/Workers.java src/main/java/dispatch/store/Tasks.java \
  src/main/java/dispatch/worker/WorkerKeys.java src/test/java/dispatch/worker/WorkerKeysTest.java
git commit -m "Pair a member's computer with a one-time code and store only the key's hash

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 4: `/worker` in the bot: a code, the list, and revoking

**Files:**
- Modify: `src/main/java/dispatch/domain/OutboxKind.java`, `src/main/java/dispatch/telegram/UpdateHandler.java`, `src/main/java/dispatch/telegram/Renderer.java`, `src/main/resources/messages_mn.properties`
- Test: `src/test/java/dispatch/telegram/UpdateHandlerTest.java`, `src/test/java/dispatch/telegram/RendererTest.java`

**Interfaces:**
- Consumes: `WorkerKeys.newCode/of/revoke`, `Groups.isMember/isAdmin`, `Outbox.enqueue`, `Renderer.format/text/plain/escape/age`.
- Produces:
  - `OutboxKind.WORKER_PAIRING`, `OutboxKind.WORKER_REVOKED`, `OutboxKind.WORKER_WAITING`
  - `UpdateHandler(Database, TaskService, Membership, Groups, Projects, BotApi, Renderer, Redactor, String botUsername, Clock, Runnable wakeOutbox, WorkerKeys workers, String workerUrl)` — the two new parameters are null in personal mode
  - `WORKER_PAIRING` payload `{"personal": bool, "code": String, "minutes": int, "url": String, "workers": [{"id": int, "name": String, "lastSeenAt": String|null}]}`; `WORKER_REVOKED` payload `{"workerId": int, "found": bool}`; `WORKER_WAITING` payload `{"taskId": long}`
  - message keys `worker.pairing`, `worker.personal`, `worker.none`, `worker.list`, `worker.item`, `worker.itemNeverSeen`, `worker.revokeHint`, `worker.revoked`, `worker.revokeNotFound`, `worker.waiting`, `status.waitingForWorker`

- [ ] **Step 1: Write the failing tests** — add to `src/test/java/dispatch/telegram/UpdateHandlerTest.java`:

```java
    @Test
    void workerGivesTheMemberAPairingCodeAndListsTheirComputers() {
        WorkerKeys keys = new WorkerKeys(db, clock);
        long existing = keys.pair(keys.newCode(BOLD), "ann-laptop").orElseThrow().workerId();
        handlerWithWorkers(keys).handle(privateCommand(1, 100, "Bold", "/worker"));

        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'WORKER_PAIRING'").get("payload"));
        assertEquals(8, payload.get("code").asText().length());
        assertEquals("https://team.example.com", payload.get("url").asText());
        assertEquals(existing, payload.get("workers").get(0).get("id").asLong());
        assertEquals("ann-laptop", payload.get("workers").get(0).get("name").asText());
    }

    @Test
    void workerRevokeTakesOneComputerAwayAndOnlyItsOwnerOrAnAdminMay() {
        WorkerKeys keys = new WorkerKeys(db, clock);
        WorkerKeys.NewKey bold = keys.pair(keys.newCode(BOLD), "ann-laptop").orElseThrow();
        UpdateHandler handler = handlerWithWorkers(keys);

        handler.handle(privateCommand(1, 200, "Ali", "/worker revoke " + bold.workerId()));
        assertTrue(keys.authenticate(bold.key()).isPresent(), "another member cannot revoke it");
        assertEquals("false", Json.read(row("SELECT payload FROM outbox WHERE kind = 'WORKER_REVOKED'").get("payload"))
                .get("found").asText());

        handler.handle(privateCommand(2, 100, "Bold", "/worker revoke " + bold.workerId()));
        assertTrue(keys.authenticate(bold.key()).isEmpty());
    }

    @Test
    void workerIsRefusedToSomeoneWhoIsNotAMember() {
        handlerWithWorkers(new WorkerKeys(db, clock)).handle(privateCommand(1, 999, "Stranger", "/worker"));

        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'WORKER_PAIRING'").get("n"));
        assertEquals("1", row("SELECT count(*) AS n FROM outbox WHERE kind = 'NOT_ALLOWED'").get("n"));
        assertEquals("0", row("SELECT count(*) AS n FROM pairing_code").get("n"), "no code is even made");
    }

    @Test
    void workerInAPersonalDispatchSaysThereIsNothingToPair() {
        handler.handle(privateCommand(1, 100, "Bold", "/worker"));

        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = 'WORKER_PAIRING'").get("payload"));
        assertTrue(payload.get("personal").asBoolean(), payload.toString());
        assertEquals("0", row("SELECT count(*) AS n FROM task").get("n"), "and it is not taken for a task");
    }
```

with, beside the file's other helpers:

```java
    private UpdateHandler handlerWithWorkers(WorkerKeys keys) {
        return new UpdateHandler(db, tasks, membership, groups, projects, api, renderer, redactor, BOT, clock,
                outboxWakes::incrementAndGet, keys, "https://team.example.com");
    }
```

(reuse whatever names the file already has for `tasks`, `membership`, `groups`, `projects`, `api`, `renderer`, `redactor`, `BOT` and its private-message helper; `privateCommand(updateId, fromId, name, text)` is the file's existing private-message builder.)

Add to `src/test/java/dispatch/telegram/RendererTest.java`:

```java
    @Test
    void thePairingMessageShowsTheCodeTheCommandAndTheMembersComputers() {
        ObjectNode payload = Json.object().put("personal", false).put("code", "ABCD2345").put("minutes", 10)
                .put("url", "https://team.example.com");
        ArrayNode workers = payload.putArray("workers");
        workers.addObject().put("id", 3).put("name", "ann-laptop").put("lastSeenAt", "2026-09-23T09:58:00Z");
        workers.addObject().put("id", 4).put("name", "desktop").putNull("lastSeenAt");

        String html = renderer.render(OutboxKind.WORKER_PAIRING, payload).html();

        assertTrue(html.contains("ABCD2345"), html);
        assertTrue(html.contains("dispatch worker pair https://team.example.com ABCD2345"), html);
        assertTrue(html.contains("#3"), html);
        assertTrue(html.contains("ann-laptop"), html);
        assertTrue(html.contains("#4"), html);
    }

    @Test
    void aTaskWaitingForItsRequestersComputerSaysSo() {
        String html = renderer.render(OutboxKind.WORKER_WAITING, Json.object().put("taskId", 7)).html();

        assertTrue(html.contains("#7"), html);
        assertEquals(html, renderer.render(OutboxKind.WORKER_WAITING, Json.object().put("taskId", 7)).html());
    }
```

(the file's `renderer` is built with a fixed clock; `2026-09-23T09:58:00Z` must be before it — use an instant two minutes before that clock's `instant()`.)

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -B test -Dtest='UpdateHandlerTest+RendererTest'`
Expected: FAIL — compilation errors, `cannot find symbol: variable WORKER_PAIRING` and the eleven-argument `UpdateHandler` constructor not matching the thirteen arguments given.

- [ ] **Step 3: Add the command.** In `src/main/java/dispatch/domain/OutboxKind.java`, after `JOIN_DENIED`:

```java
    /** To a member privately: a one-time pairing code and the computers they have paired (W-3). */
    WORKER_PAIRING,
    WORKER_REVOKED,
    /** To the requester, once: their task waits because none of their computers is connected. */
    WORKER_WAITING
```

In `src/main/resources/messages_mn.properties`:

```properties
worker.pairing=💻 <b>Таны компьютерийг холбох код:</b> <code>{0}</code>\nЭнэ код {1} минут хүчинтэй, нэг удаа ажиллана.\nКомпьютер дээрээ ажиллуулна уу:\n<code>dispatch worker pair {2} {0}</code>
worker.personal=Энэ Dispatch дээр даалгаврууд энэ компьютер дээр ажилладаг тул нэмэлт компьютер холбох шаардлагагүй.
worker.none=Холбогдсон компьютер алга.
worker.list=<b>Таны компьютерууд:</b>
worker.item=#{0} {1} · сүүлд {2}
worker.itemNeverSeen=#{0} {1} · хараахан холбогдоогүй
worker.revokeHint=Салгах: /worker revoke ДУГААР
worker.revoked=✅ #{0} компьютерийн эрхийг цуцаллаа.
worker.revokeNotFound=#{0} дугаартай компьютер олдсонгүй.
worker.waiting=⏳ <b>#{0}</b>: таны компьютер холбогдоогүй байна. Холбогдмогц эхлэнэ.
status.waitingForWorker=компьютер хүлээж байна
command.worker=Компьютерээ холбох
```

(the file is ASCII with `\uXXXX` escapes, as `ResourceBundle` reads `.properties` in ISO-8859-1; keep that. The plain text of the keys is: "Your computer's pairing code: … valid for {1} minutes, works once. Run on your computer:", "On this Dispatch tasks run on this computer, so there is nothing to pair.", "No connected computer.", "Your computers:", "#{0} {1} · last {2}", "#{0} {1} · never connected", "Disconnect: /worker revoke NUMBER", "✅ computer #{0} is revoked.", "no computer #{0}", "⏳ #{0}: your computer is not connected. It starts as soon as it is.", "waiting for a computer", "Pair your computer".)

In `src/main/java/dispatch/telegram/Renderer.java` add the three cases to `render`, before `case JOIN_REQUEST`:

```java
            case WORKER_PAIRING -> workerPairing(payload);
            case WORKER_REVOKED -> plain(payload.path("found").asBoolean()
                    ? format("worker.revoked", String.valueOf(payload.path("workerId").asInt()))
                    : format("worker.revokeNotFound", String.valueOf(payload.path("workerId").asInt())));
            case WORKER_WAITING -> plain(format("worker.waiting", taskId(payload)));
```

and the method beside `joinRequest`:

```java
    /** The member's one-time code, the exact command to run with it, and the computers they already paired. */
    private Rendered workerPairing(JsonNode payload) {
        if (payload.path("personal").asBoolean()) {
            return plain(text("worker.personal"));
        }
        StringBuilder html = new StringBuilder(format("worker.pairing", escape(payload.path("code").asText()),
                String.valueOf(payload.path("minutes").asInt()), escape(payload.path("url").asText())));
        JsonNode workers = payload.path("workers");
        if (workers.isEmpty()) {
            return plain(html.append("\n\n").append(text("worker.none")).toString());
        }
        html.append("\n\n").append(text("worker.list"));
        for (JsonNode worker : workers) {
            String id = String.valueOf(worker.path("id").asInt());
            String name = escape(worker.path("name").asText());
            html.append("\n").append(worker.hasNonNull("lastSeenAt")
                    ? format("worker.item", id, name, age(Instant.parse(worker.get("lastSeenAt").asText())))
                    : format("worker.itemNeverSeen", id, name));
        }
        return plain(html.append("\n\n").append(text("worker.revokeHint")).toString());
    }
```

In `src/main/java/dispatch/telegram/UpdateHandler.java` add the two fields and constructor parameters:

```java
    private final WorkerKeys workers;
    private final String workerUrl;

    /**
     * @param redactor  masks messages this handler edits directly, as the outbox sender does for everything it sends
     * @param workers   null in personal mode, where a member has nothing to pair
     * @param workerUrl the URL members' computers reach this machine on; null in personal mode
     */
    public UpdateHandler(Database db, TaskService tasks, Membership membership, Groups groups, Projects projects, BotApi api,
                         Renderer renderer, Redactor redactor, String botUsername, Clock clock, Runnable wakeOutbox,
                         WorkerKeys workers, String workerUrl) {
```

(assign both at the end of the constructor body; keep the existing eleven-argument constructor as an overload that passes `null, null`, so tests that do not care stay as they are.)

Add `"worker"` to `COMMANDS`, and the case after `"retry"`:

```java
            case "worker" -> {
                if (!privateChat) {
                    privateOnly(tx, chatRef, origin);
                    return;
                }
                worker(tx, who, command.args(), origin, chatRef);
            }
```

and the method beside `projectList`:

```java
    /** /worker gives a one-time pairing code and lists the member's computers; /worker revoke N takes one away. */
    private void worker(Tx tx, Requester who, String args, String origin, String chatRef) {
        if (!groups.isMember(who.ref())) {
            enqueue(tx, OutboxKind.NOT_ALLOWED, chatRef, origin, Json.object().put("name", who.name()));
            return;
        }
        if (workers == null) {
            enqueue(tx, OutboxKind.WORKER_PAIRING, chatRef, origin, Json.object().put("personal", true));
            return;
        }
        String[] words = args.strip().split("\\s+");
        if (words.length >= 2 && words[0].equals("revoke")) {
            revokeWorker(tx, who, words[1], origin, chatRef);
            return;
        }
        ObjectNode payload = Json.object().put("personal", false)
                .put("code", workers.newCode(who))
                .put("minutes", (int) WorkerKeys.CODE_LIFETIME.toMinutes())
                .put("url", workerUrl);
        ArrayNode list = payload.putArray("workers");
        for (dispatch.store.Workers.Paired paired : workers.of(who.ref())) {
            ObjectNode item = list.addObject().put("id", paired.id()).put("name", paired.name());
            item.put("lastSeenAt", paired.lastSeenAt() == null ? null : paired.lastSeenAt().toString());
        }
        enqueue(tx, OutboxKind.WORKER_PAIRING, chatRef, origin, payload);
    }

    private void revokeWorker(Tx tx, Requester who, String number, String origin, String chatRef) {
        long workerId;
        try {
            workerId = Long.parseLong(number);
        } catch (NumberFormatException e) {
            workerId = -1;
        }
        boolean found = workerId > 0 && workers.revoke(workerId, who.ref(), groups.isAdmin(who.ref()));
        enqueue(tx, OutboxKind.WORKER_REVOKED, chatRef, origin,
                Json.object().put("workerId", workerId).put("found", found));
    }
```

Finally add `worker` to the private-chat command menu in `src/main/java/dispatch/App.java`:

```java
            api.setPrivateChatCommands(commands(renderer, "task", "status", "history", "stats", "cancel", "retry", "worker", "projects", "help"));
```

- [ ] **Step 4: Run them to see them pass**

Run: `./mvnw -q -B test -Dtest='UpdateHandlerTest+RendererTest'`
Expected: PASS, including the four new handler tests and the two renderer tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/domain/OutboxKind.java src/main/java/dispatch/telegram/UpdateHandler.java \
  src/main/java/dispatch/telegram/Renderer.java src/main/resources/messages_mn.properties \
  src/main/java/dispatch/App.java src/test/java/dispatch/telegram/UpdateHandlerTest.java \
  src/test/java/dispatch/telegram/RendererTest.java
git commit -m "Give a member a pairing code and let them revoke a computer from Telegram

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 5: `RemoteWorkers`: a job waits for its requester's computer

**Files:**
- Create: `src/main/java/dispatch/worker/RemoteWorkers.java`
- Modify: `src/main/java/dispatch/core/ActiveRuns.java`
- Test: `src/test/java/dispatch/worker/RemoteWorkersTest.java`

**Interfaces:**
- Consumes: `dispatch.core.{Worker, Job, JobResult, JobEvents, ActiveRuns, Coordinator}`; `Tasks.find/recordWorker/workerOf`; `Workers.Paired`; `dispatch.ui.ApiException`.
- Produces:
  - `public synchronized void ActiveRuns.ActiveRun.reportActivity(AgentActivity activity)`; `activity()` answers the reported one when no local handle is attached
  - `public final class RemoteWorkers implements Worker` with
    `RemoteWorkers(Database db, Clock clock, Runnable wakeScheduler)`,
    `JobResult run(Job, JobEvents, ActiveRuns.ActiveRun)`,
    `Optional<Job> next(Workers.Paired worker) throws InterruptedException`,
    `boolean progress(Workers.Paired worker, Progress progress)`,
    `void result(Workers.Paired worker, long taskId, int seq, JobResult result)`,
    `Job leased(Workers.Paired worker, long taskId)`,
    `record Progress(long taskId, int seq, String worktree, String baseSha, boolean agentStarted, Integer steps, String lastAction)`,
    `LEASE`, `LONG_POLL`

- [ ] **Step 1: Write the failing test** — create `src/test/java/dispatch/worker/RemoteWorkersTest.java`:

```java
package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.Coordinator;
import dispatch.core.Groups;
import dispatch.core.Job;
import dispatch.core.JobResult;
import dispatch.core.Projects;
import dispatch.core.RunTransitions;
import dispatch.core.TaskService;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.store.Workers;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import dispatch.ui.ApiException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The team machine's half of the protocol, with a fake clock and no HTTP: a job waits for one of its requester's
 * computers, that computer holds a lease it renews with progress, and whatever ends the lease ends the run exactly once.
 */
class RemoteWorkersTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");
    private static final Config.Project ALM = new Config.Project("alm", null, "git@github.com:acme/alm.git", null, "main",
            "claude-code", null, null, List.of(), null, null, null);
    private static final String PLAN_JSON = new Plan("The login times out", List.of("AUTH_TIMEOUT_SECONDS is 5"),
            List.of("Raise the timeout"), List.of(), List.of()).toJson();

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private ActiveRuns activeRuns;
    private TaskService tasks;
    private WorkerKeys keys;
    private RemoteWorkers remote;
    private final TestClock clock = new TestClock(Instant.parse("2026-09-23T10:00:00Z"));

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        activeRuns = new ActiveRuns();
        Groups groups = new Groups(List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm"))));
        tasks = new TaskService(groups, new Projects(List.of(ALM), project -> Optional.empty()), activeRuns, clock,
                () -> { }, () -> { });
        keys = new WorkerKeys(db, clock);
        remote = new RemoteWorkers(db, clock, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aJobGoesOnlyToItsRequestersOwnComputer() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        Workers.Paired ann = pair(BOLD, "ann-laptop");
        Workers.Paired bobs = pair(ALI, "bob-laptop");
        Thread run = coordinate();

        assertTrue(remote.next(bobs).isEmpty(), "Ali's computer never sees Bold's work");
        Job job = remote.next(ann).orElseThrow();

        assertEquals(id, job.taskId());
        assertTrue(job.prompt().contains("Fix the login timeout"), "the prompt arrives finished: " + job.prompt());
        remote.result(ann, id, 1, JobResult.succeeded(agentResult()));
        run.join(Duration.ofSeconds(10));
        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals(ann.id(), Long.parseLong(row("SELECT worker_id FROM task WHERE id = ?", id).get("worker_id")),
                "the task now belongs to that computer");
    }

    @Test
    void aTaskGoesBackToTheComputerThatHoldsItsWorktree() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        Workers.Paired first = pair(BOLD, "ann-laptop");
        Workers.Paired second = pair(BOLD, "ann-desktop");
        Thread run = coordinate();
        remote.next(first).orElseThrow();
        remote.result(first, id, 1, JobResult.succeeded(agentResult()));
        run.join(Duration.ofSeconds(10));

        approveAndQueueExecution(id);
        Thread execution = coordinate();

        assertTrue(remote.next(second).isEmpty(), "the other computer has neither the worktree nor the session");
        assertEquals(id, remote.next(first).orElseThrow().taskId());
        remote.result(first, id, 2, JobResult.cancelled(null));
        execution.join(Duration.ofSeconds(10));
    }

    @Test
    void sixtySecondsWithoutProgressEndsTheRunAsInterrupted() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        Workers.Paired ann = pair(BOLD, "ann-laptop");
        Thread run = coordinate();
        remote.next(ann).orElseThrow();

        clock.advance(RemoteWorkers.LEASE.plusSeconds(1));
        run.join(Duration.ofSeconds(10));

        Map<String, String> task = row("SELECT phase, failure_reason FROM task WHERE id = ?", id);
        assertEquals("FAILED", task.get("phase"));
        assertEquals("INTERRUPTED", task.get("failure_reason"));
        assertEquals("FAILED", row("SELECT status FROM run WHERE task_id = ?", id).get("status"));
    }

    @Test
    void aResultAfterTheLeaseExpiredIsRefusedWithConflict() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        Workers.Paired ann = pair(BOLD, "ann-laptop");
        Thread run = coordinate();
        remote.next(ann).orElseThrow();
        clock.advance(RemoteWorkers.LEASE.plusSeconds(1));
        run.join(Duration.ofSeconds(10));

        ApiException refused = assertThrows(ApiException.class,
                () -> remote.result(ann, id, 1, JobResult.succeeded(agentResult())));

        assertEquals(409, refused.status());
        assertEquals("lease_expired", refused.code());
        assertEquals("INTERRUPTED", row("SELECT failure_reason FROM task WHERE id = ?", id).get("failure_reason"),
                "the one transition the run got stands");
    }

    @Test
    void progressRenewsTheLeaseRecordsTheWorktreeAndFeedsStatus() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        Workers.Paired ann = pair(BOLD, "ann-laptop");
        Thread run = coordinate();
        remote.next(ann).orElseThrow();

        clock.advance(Duration.ofSeconds(50));
        assertFalse(remote.progress(ann, new RemoteWorkers.Progress(id, 1, "/home/ann/work/alm-7", "6f3030a", true, 12,
                "Bash: git status")));
        clock.advance(Duration.ofSeconds(50));
        assertFalse(remote.progress(ann, new RemoteWorkers.Progress(id, 1, null, null, false, 14, "Edit: README.md")));

        assertEquals("/home/ann/work/alm-7", row("SELECT worktree FROM task WHERE id = ?", id).get("worktree"));
        assertEquals("6f3030a", row("SELECT base_sha FROM task WHERE id = ?", id).get("base_sha"));
        Map<String, String> runRow = row("SELECT pid, agent_started_at FROM run WHERE task_id = ?", id);
        assertEquals(null, runRow.get("pid"), "the process is on Ann's computer");
        assertTrue(runRow.get("agent_started_at") != null);
        assertEquals("Edit: README.md", activeRuns.activity(id).orElseThrow().lastAction());
        assertEquals(14, activeRuns.activity(id).orElseThrow().steps());
        remote.result(ann, id, 1, JobResult.succeeded(agentResult()));
        run.join(Duration.ofSeconds(10));
    }

    @Test
    void aCancelReachesTheWorkerThroughItsNextProgress() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        Workers.Paired ann = pair(BOLD, "ann-laptop");
        Thread run = coordinate();
        remote.next(ann).orElseThrow();

        db.transaction(tx -> tasks.cancel(tx, BOLD, id, "telegram:100/9", "telegram:100"));

        assertTrue(remote.progress(ann, new RemoteWorkers.Progress(id, 1, null, null, false, 3, "Bash: ls")));
        remote.result(ann, id, 1, JobResult.cancelled(null));
        run.join(Duration.ofSeconds(10));
        assertEquals("CANCELLED", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals("CANCELLED", row("SELECT status FROM run WHERE task_id = ?", id).get("status"));
    }

    @Test
    void anotherMembersComputerCannotReportOnThisRun() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        Workers.Paired ann = pair(BOLD, "ann-laptop");
        Workers.Paired bobs = pair(ALI, "bob-laptop");
        Thread run = coordinate();
        remote.next(ann).orElseThrow();

        ApiException refused = assertThrows(ApiException.class,
                () -> remote.result(bobs, id, 1, JobResult.succeeded(agentResult())));
        ApiException refusedProgress = assertThrows(ApiException.class,
                () -> remote.progress(bobs, new RemoteWorkers.Progress(id, 1, null, null, false, 1, "Bash: ls")));

        assertEquals(403, refused.status());
        assertEquals("not_your_run", refused.code());
        assertEquals(403, refusedProgress.status());
        remote.result(ann, id, 1, JobResult.cancelled(null));
        run.join(Duration.ofSeconds(10));
    }

    @Test
    void anOfferNoComputerTakesEndsAsInterrupted() throws Exception {
        long id = queue(BOLD, "Fix the login timeout");
        pair(BOLD, "ann-laptop");
        Thread run = coordinate();

        clock.advance(RemoteWorkers.LEASE.plusSeconds(1));
        run.join(Duration.ofSeconds(10));

        assertEquals("INTERRUPTED", row("SELECT failure_reason FROM task WHERE id = ?", id).get("failure_reason"));
    }

    private Thread coordinate() {
        Coordinator coordinator = new Coordinator(db, new Projects(List.of(ALM), project -> Optional.empty()),
                new RunTransitions(db, clock, () -> { }), activeRuns,
                project -> new Config.RunLimits(Duration.ofMinutes(30), new BigDecimal("2")),
                project -> new Config.RunLimits(Duration.ofMinutes(60), new BigDecimal("10")), remote, () -> { });
        ClaimedRun claimed = db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
        Thread thread = Thread.ofVirtual().start(() -> coordinator.execute(claimed));
        awaitOffer();
        return thread;
    }

    /** The Coordinator reads the store before it offers the job; the test must not poll before that happened. */
    private void awaitOffer() {
        Instant deadline = Instant.now().plusSeconds(10);
        while (!remote.hasOffers()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("the coordinator never offered the job");
            }
            Thread.onSpinWait();
        }
    }

    private Workers.Paired pair(Requester member, String name) {
        long id = keys.pair(keys.newCode(member), name).orElseThrow().workerId();
        return db.transactionReturning(tx -> Workers.find(tx, id)).orElseThrow();
    }

    private long queue(Requester who, String description) {
        db.transaction(tx -> tasks.create(tx, who, "alm", description, Priority.NORMAL, who.ref() + "/" + System.nanoTime()));
        return Long.parseLong(row("SELECT max(id) AS id FROM task").get("id"));
    }

    private void approveAndQueueExecution(long taskId) {
        db.transaction(tx -> tasks.approve(tx, BOLD, taskId, 1));
    }

    private static AgentResult agentResult() {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "fake-session", PLAN_JSON, "done", new BigDecimal("0.01"), 2,
                List.of(), null, "claude-sonnet-5", null);
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
```

`TaskService.create(Tx, Requester, String, String, Priority, String)` and `TaskService.approve(Tx, Requester, long, int)` are used exactly as `CoordinatorTest` and `TaskLifecycleTest` call them.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -B test -Dtest=RemoteWorkersTest`
Expected: FAIL — compilation errors, `cannot find symbol: class RemoteWorkers` and `cannot find symbol: method reportActivity`.

- [ ] **Step 3: Let a job wait for a computer.** In `src/main/java/dispatch/core/ActiveRuns.java`, inside `ActiveRun`:

```java
        private RunHandle handle;
        private AgentActivity reported;
        private StopReason stopReason;
```

```java
        /** What a remote worker's agent is doing, from its progress every 10 s; a local run reads its handle instead. */
        public synchronized void reportActivity(AgentActivity activity) {
            this.reported = activity;
        }

        public synchronized Optional<AgentActivity> activity() {
            if (handle != null) {
                return Optional.of(handle.activity());
            }
            return Optional.ofNullable(reported);
        }
```

Create `src/main/java/dispatch/worker/RemoteWorkers.java`:

```java
package dispatch.worker;

import dispatch.Log;
import dispatch.agent.AgentActivity;
import dispatch.core.ActiveRuns;
import dispatch.core.Job;
import dispatch.core.JobEvents;
import dispatch.core.JobResult;
import dispatch.core.Worker;
import dispatch.domain.FailureReason;
import dispatch.store.Database;
import dispatch.store.Tasks;
import dispatch.store.Workers;
import dispatch.ui.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The {@link Worker} of a team: a job is offered to the requester's own computers and the calling thread waits for the
 * one that takes it. A worker holds the job under a lease it renews with progress every 10 s; when the lease expires the
 * run comes back as {@code INTERRUPTED}, exactly as a restart mid-run does today, and whatever that worker reports
 * afterwards is refused with 409 — it keeps its worktree, so /retry delivers it again.
 *
 * <p>Nothing here writes the store's run state: the Coordinator still makes the one transition per run.
 */
public final class RemoteWorkers implements Worker {

    /** Without progress for this long, the worker is gone. */
    public static final Duration LEASE = Duration.ofSeconds(60);
    /** How long /api/worker/next waits before answering "nothing". */
    public static final Duration LONG_POLL = Duration.ofSeconds(25);
    /** How often a waiting thread looks up from its future to check the clock. */
    private static final Duration TICK = Duration.ofMillis(200);

    private final Database db;
    private final Clock clock;
    private final Runnable wakeScheduler;
    private final Object lock = new Object();
    private final List<Offer> offers = new ArrayList<>();

    /** @param wakeScheduler called when a worker asks for work, so a run queued while it was away starts at once */
    public RemoteWorkers(Database db, Clock clock, Runnable wakeScheduler) {
        this.db = db;
        this.clock = clock;
        this.wakeScheduler = wakeScheduler;
    }

    /** One job waiting for, or running on, a member's computer. */
    private static final class Offer {

        private final Job job;
        private final JobEvents events;
        private final ActiveRuns.ActiveRun control;
        private final String memberRef;
        private final Long onlyWorker;
        private final Instant offeredAt;
        private final CompletableFuture<JobResult> answer = new CompletableFuture<>();
        private Long takenBy;
        private Instant leaseUntil;
        /** The lease ran out: this job is over and nothing this worker sends about it counts any more. */
        private boolean expired;
        private boolean worktreeRecorded;
        private boolean agentRecorded;

        private Offer(Job job, JobEvents events, ActiveRuns.ActiveRun control, String memberRef, Long onlyWorker,
                      Instant offeredAt) {
            this.job = job;
            this.events = events;
            this.control = control;
            this.memberRef = memberRef;
            this.onlyWorker = onlyWorker;
            this.offeredAt = offeredAt;
        }
    }

    @Override
    public JobResult run(Job job, JobEvents events, ActiveRuns.ActiveRun control) {
        Offer offer = offer(job, events, control);
        try {
            while (true) {
                try {
                    return offer.answer.get(TICK.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    JobResult givenUp = givenUp(offer);
                    if (givenUp != null) {
                        return givenUp;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JobResult.failed(FailureReason.INTERRUPTED, "Dispatch stopped while the run was active", null);
        } catch (ExecutionException e) {
            throw new IllegalStateException("a job's answer is never completed with an error", e);
        } finally {
            synchronized (lock) {
                offers.remove(offer);
                lock.notifyAll();
            }
        }
    }

    private Offer offer(Job job, JobEvents events, ActiveRuns.ActiveRun control) {
        String memberRef = db.transactionReturning(tx -> Tasks.find(tx, job.taskId()))
                .orElseThrow(() -> new IllegalStateException("job for missing task " + job.taskId()))
                .requester().ref();
        Long onlyWorker = db.transactionReturning(tx -> Tasks.workerOf(tx, job.taskId())).orElse(null);
        Offer offer = new Offer(job, events, control, memberRef, onlyWorker, clock.instant());
        synchronized (lock) {
            offers.add(offer);
            lock.notifyAll();
        }
        Log.info("worker.job_offered", "task", job.taskId(), "run", job.seq(), "member", memberRef, "worker", onlyWorker);
        return offer;
    }

    /** Why this job is over although no worker reported; null while it may still run. */
    private JobResult givenUp(Offer offer) {
        Instant now = clock.instant();
        synchronized (lock) {
            if (offer.control.stopReason() == ActiveRuns.StopReason.INTERRUPTED) {
                return JobResult.failed(FailureReason.INTERRUPTED, "Dispatch stopped while the run was active", null);
            }
            if (offer.takenBy == null) {
                if (now.isBefore(offer.offeredAt.plus(LEASE))) {
                    return null;
                }
                Log.warn("worker.offer_expired", "task", offer.job.taskId(), "run", offer.job.seq(), "member", offer.memberRef);
                return JobResult.failed(FailureReason.INTERRUPTED, "no computer took this run", null);
            }
            if (now.isBefore(offer.leaseUntil)) {
                return null;
            }
            // Drop the lease first: the worker's late result must find nothing and get 409.
            Log.warn("worker.lease_expired", "task", offer.job.taskId(), "run", offer.job.seq(), "worker", offer.takenBy);
            offer.leaseUntil = now;
            offer.takenBy = null;
            offer.expired = true;
            return JobResult.failed(FailureReason.INTERRUPTED,
                    "your computer stopped reporting for " + LEASE.toSeconds() + "s", null);
        }
    }

    /** Whether any job is waiting or running; the team machine's own tests wait for this. */
    public boolean hasOffers() {
        synchronized (lock) {
            return !offers.isEmpty();
        }
    }

    /**
     * The next job for {@code worker}, waiting up to {@link #LONG_POLL}. A job is this worker's when its requester is the
     * worker's member and either the task has no computer yet or this is that computer.
     */
    public Optional<Job> next(Workers.Paired worker) throws InterruptedException {
        wakeScheduler.run();
        Instant deadline = clock.instant().plus(LONG_POLL);
        synchronized (lock) {
            while (true) {
                Optional<Offer> match = offers.stream().filter(offer -> matches(offer, worker)).findFirst();
                if (match.isPresent()) {
                    return Optional.of(take(match.get(), worker));
                }
                long left = Duration.between(clock.instant(), deadline).toMillis();
                if (left <= 0) {
                    return Optional.empty();
                }
                lock.wait(Math.min(left, TICK.toMillis()));
            }
        }
    }

    private static boolean matches(Offer offer, Workers.Paired worker) {
        return offer.takenBy == null && !offer.expired && offer.memberRef.equals(worker.memberRef())
                && (offer.onlyWorker == null || offer.onlyWorker == worker.id());
    }

    private Job take(Offer offer, Workers.Paired worker) {
        Instant now = clock.instant();
        offer.takenBy = worker.id();
        offer.leaseUntil = now.plus(LEASE);
        // Recorded at once: from here the task's worktree and session live on this computer and nowhere else.
        db.transaction(tx -> Tasks.recordWorker(tx, offer.job.taskId(), worker.id(), now));
        Log.info("worker.job_taken", "task", offer.job.taskId(), "run", offer.job.seq(), "worker", worker.id(),
                "name", worker.name());
        return offer.job;
    }

    /** What the worker is doing; renews the lease and answers whether the member cancelled the task. */
    public boolean progress(Workers.Paired worker, Progress progress) {
        Offer offer = held(worker, progress.taskId(), progress.seq());
        synchronized (lock) {
            offer.leaseUntil = clock.instant().plus(LEASE);
        }
        if (progress.worktree() != null && !offer.worktreeRecorded) {
            offer.events.worktreeCreated(progress.worktree(), progress.baseSha());
            offer.worktreeRecorded = true;
        }
        if (progress.agentStarted() && !offer.agentRecorded) {
            // No pid: that process runs on the member's computer and this machine kills only its own orphans.
            offer.events.agentStarted(null, null);
            offer.agentRecorded = true;
        }
        if (progress.steps() != null) {
            offer.control.reportActivity(new AgentActivity(progress.steps(), progress.lastAction()));
        }
        return offer.control.stopReason() == ActiveRuns.StopReason.CANCELLED;
    }

    /**
     * How the job ended. The Coordinator's thread wakes with this and makes the run's one transition.
     *
     * @param progress what a worker posts every 10 s and whenever the store must learn something at once; every field
     *                 but the run itself may be absent
     */
    public record Progress(long taskId, int seq, String worktree, String baseSha, boolean agentStarted, Integer steps,
                           String lastAction) {
    }

    public void result(Workers.Paired worker, long taskId, int seq, JobResult result) {
        Offer offer = held(worker, taskId, seq);
        synchronized (lock) {
            offer.takenBy = null;
            offer.leaseUntil = clock.instant();
        }
        offer.answer.complete(result);
        Log.info("worker.job_reported", "task", taskId, "run", seq, "worker", worker.id(), "outcome", result.outcome());
    }

    /** The job {@code worker} holds the lease for, so it can fetch that task's files. */
    public Job leased(Workers.Paired worker, long taskId) {
        return held(worker, taskId, -1).job;
    }

    /** @param seq -1 when the caller does not name a run */
    private Offer held(Workers.Paired worker, long taskId, int seq) {
        synchronized (lock) {
            Optional<Offer> found = offers.stream().filter(offer -> offer.job.taskId() == taskId).findFirst();
            if (found.isEmpty() || found.get().takenBy == null) {
                throw new ApiException(409, "lease_expired",
                        "this run's lease has expired; keep its worktree, the member can retry it");
            }
            Offer offer = found.get();
            if (offer.takenBy != worker.id()) {
                throw new ApiException(403, "not_your_run", "this run belongs to another computer");
            }
            if (seq >= 0 && offer.job.seq() != seq) {
                throw new ApiException(409, "lease_expired", "this computer holds run " + offer.job.seq() + " of task "
                        + taskId + ", not " + seq);
            }
            if (clock.instant().isAfter(offer.leaseUntil)) {
                throw new ApiException(409, "lease_expired",
                        "this run's lease has expired; keep its worktree, the member can retry it");
            }
            return offer;
        }
    }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -B test -Dtest='RemoteWorkersTest+ActiveRunsTest+RunExecutorTest'`
Expected: PASS (8 new tests; `ActiveRunsTest` and `RunExecutorTest` unchanged, since a local run still reads its handle).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/worker/RemoteWorkers.java src/main/java/dispatch/core/ActiveRuns.java \
  src/test/java/dispatch/worker/RemoteWorkersTest.java
git commit -m "Offer a claimed run to its requester's computer under a 60s lease

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 6: `WorkerApi`: the server, its key check and the two setup routes

**Files:**
- Create: `src/main/java/dispatch/worker/WorkerApi.java`
- Test: `src/test/java/dispatch/worker/WorkerApiTest.java`

**Interfaces:**
- Consumes: `com.sun.net.httpserver.HttpServer`, `dispatch.Json`, `dispatch.ui.ApiException`, `WorkerKeys.pair/authenticate`, `Groups.projectsOfMember`, `Config.projects/team/delivery/workers`.
- Produces:
  - `public final class WorkerApi implements AutoCloseable` with `public static WorkerApi start(Config config, Groups groups, WorkerKeys keys)`, `public int port()`, `public void close()`
  - route constants `PAIR`, `NEXT`, `PROGRESS`, `ATTACHMENT`, `RESULT`, `PROJECTS`
  - `/api/worker/pair` answers `{"workerId": long, "key": String, "team": String}`; `/api/worker/projects` answers `{"team": String, "authorName": String, "authorEmail": String, "projects": [{"name","repo","baseBranch","model","effort"}]}`

- [ ] **Step 1: Write the failing test** — create `src/test/java/dispatch/worker/WorkerApiTest.java`:

```java
package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.config.Config;
import dispatch.core.Groups;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.testing.TestClock;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Who may talk to the worker endpoints at all: the key check, the Host check, pairing and the setup route. */
class WorkerApiTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");

    @TempDir
    Path dir;

    private Database db;
    private WorkerKeys keys;
    private WorkerApi api;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(Instant.parse("2026-09-23T10:00:00Z"));

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
        keys = new WorkerKeys(db, clock);
        api = WorkerApi.start(config(), groups(), keys);
    }

    @AfterEach
    void tearDown() {
        api.close();
        db.close();
    }

    @Test
    void aCodePairsOnceOverHttpAndTheKeyIsTheOnlyThingReturned() throws Exception {
        String code = keys.newCode(BOLD);

        HttpResponse<String> answer = post(WorkerApi.PAIR, null, "{\"code\":\"" + code + "\",\"name\":\"ann-laptop\"}");

        assertEquals(200, answer.statusCode());
        JsonNode paired = Json.read(answer.body());
        assertEquals("backend", paired.get("team").asText());
        assertTrue(paired.get("workerId").asLong() > 0);
        assertEquals(43, paired.get("key").asText().length(), "32 random bytes as base64url without padding");
        HttpResponse<String> again = post(WorkerApi.PAIR, null, "{\"code\":\"" + code + "\",\"name\":\"ann-laptop\"}");
        assertEquals(401, again.statusCode());
        assertEquals("pairing_code", Json.read(again.body()).get("error").asText());
    }

    @Test
    void aLowercaseCodeStillPairs() throws Exception {
        String code = keys.newCode(BOLD);

        HttpResponse<String> answer = post(WorkerApi.PAIR, null,
                "{\"code\":\"" + code.toLowerCase() + "\",\"name\":\"ann-laptop\"}");

        assertEquals(200, answer.statusCode());
    }

    @Test
    void everyOtherRouteNeedsAKeyAndAWrongOneIsRefusedWhereverItDiffers() throws Exception {
        String key = pair(BOLD, "ann-laptop");

        assertEquals(200, post(WorkerApi.PROJECTS, key, "{}").statusCode());
        assertEquals(401, post(WorkerApi.PROJECTS, null, "{}").statusCode());
        assertEquals(401, post(WorkerApi.PROJECTS, "not-a-key", "{}").statusCode());
        // The compare is MessageDigest.isEqual over the hashes, so a near miss is refused like anything else.
        HttpResponse<String> firstCharDiffers = post(WorkerApi.PROJECTS, (key.charAt(0) == 'A' ? 'B' : 'A') + key.substring(1), "{}");
        HttpResponse<String> lastCharDiffers = post(WorkerApi.PROJECTS,
                key.substring(0, key.length() - 1) + (key.endsWith("A") ? "B" : "A"), "{}");
        assertEquals(401, firstCharDiffers.statusCode());
        assertEquals(401, lastCharDiffers.statusCode());
        assertEquals("unauthorized", Json.read(firstCharDiffers.body()).get("error").asText());
        assertEquals(Json.read(firstCharDiffers.body()), Json.read(lastCharDiffers.body()), "and refused the same way");
    }

    @Test
    void aRevokedKeyIsAsGoodAsAnUnknownOneAndSaysToPairAgain() throws Exception {
        String key = pair(BOLD, "ann-laptop");
        String stranger = pair(ALI, "bob-laptop");

        keys.revoke(keys.of(BOLD.ref()).getFirst().id(), BOLD.ref(), false);

        HttpResponse<String> revoked = post(WorkerApi.PROJECTS, key, "{}");
        assertEquals(401, revoked.statusCode());
        assertEquals("unauthorized", Json.read(revoked.body()).get("error").asText(),
                "a revoked key and an unknown one answer alike, so nobody can probe which keys existed");
        assertTrue(revoked.body().contains("dispatch worker pair"), revoked.body());
        assertEquals(200, post(WorkerApi.PROJECTS, stranger, "{}").statusCode(), "Ali's computer is untouched");
    }

    @Test
    void aRequestForAnotherHostIsRefused() throws Exception {
        String key = pair(BOLD, "ann-laptop");

        HttpResponse<String> answer = http.send(HttpRequest.newBuilder(uri(WorkerApi.PROJECTS))
                        .header("Authorization", "Bearer " + key)
                        .header("Host", "evil.example.com")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, answer.statusCode());
        assertEquals("host", Json.read(answer.body()).get("error").asText());
    }

    @Test
    void theKeyIsNeverLogged() throws Exception {
        PrintStream out = System.out;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        String key;
        try {
            System.setOut(new PrintStream(logged, true, StandardCharsets.UTF_8));
            key = pair(BOLD, "ann-laptop");
            post(WorkerApi.PROJECTS, key, "{}");
            post(WorkerApi.PROJECTS, key + "x", "{}");
        } finally {
            System.setOut(out);
        }

        String lines = logged.toString(StandardCharsets.UTF_8);
        assertFalse(lines.contains(key), lines);
        assertTrue(lines.contains("worker.paired"), lines);
    }

    @Test
    void theSetupRouteAnswersTheMembersProjectsAndTheTeamsCommitAuthor() throws Exception {
        String key = pair(BOLD, "ann-laptop");

        JsonNode answer = Json.read(post(WorkerApi.PROJECTS, key, "{}").body());

        assertEquals("backend", answer.get("team").asText());
        assertEquals("Dispatch (backend)", answer.get("authorName").asText());
        assertEquals(1, answer.get("projects").size());
        assertEquals("alm", answer.get("projects").get(0).get("name").asText());
        assertEquals("git@github.com:acme/alm.git", answer.get("projects").get(0).get("repo").asText());
        assertEquals("main", answer.get("projects").get(0).get("baseBranch").asText());
    }

    @Test
    void aGetAndAnOversizedBodyAreRefusedWithoutTouchingTheKey() throws Exception {
        String key = pair(BOLD, "ann-laptop");

        HttpResponse<String> get = http.send(HttpRequest.newBuilder(uri(WorkerApi.PROJECTS))
                .header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> huge = post(WorkerApi.PROJECTS, key, "{\"x\":\"" + "y".repeat(70_000) + "\"}");
        HttpResponse<String> unknown = post("/api/worker/nope", key, "{}");

        assertEquals(405, get.statusCode());
        assertEquals(413, huge.statusCode());
        assertEquals(404, unknown.statusCode());
    }

    private String pair(Requester member, String name) throws Exception {
        return Json.read(post(WorkerApi.PAIR, null, pairBody(keys.newCode(member), name)).body()).get("key").asText();
    }

    private static String pairBody(String code, String name) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}";
    }

    private HttpResponse<String> post(String path, String key, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + api.port() + path);
    }

    private Config config() {
        return new Config("backend", dir, new Config.Telegram(List.of(), List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm")))),
                new Config.Scheduler(2), Config.Worktrees.DEFAULT,
                new Config.Limits(new Config.RunLimits(Duration.ofMinutes(30), new java.math.BigDecimal("2")),
                        new Config.RunLimits(Duration.ofMinutes(60), new java.math.BigDecimal("10"))),
                Map.of("claude-code", new Config.Agent("claude")),
                List.of(new Config.Project("alm", null, "git@github.com:acme/alm.git", null, "main", "claude-code", "opus",
                        "high", List.of(), null, null, null)),
                new Config.Delivery("Dispatch (backend)", "dispatch-backend@example.com", "gh"),
                new Config.Workers("http://127.0.0.1:0", 0), new Config.Secrets("token", null));
    }

    private Groups groups() {
        return new Groups(List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm"))));
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -B test -Dtest=WorkerApiTest`
Expected: FAIL — compilation error, `cannot find symbol: class WorkerApi`.

- [ ] **Step 3: Write the server.** Create `src/main/java/dispatch/worker/WorkerApi.java`:

```java
package dispatch.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dispatch.Json;
import dispatch.Log;
import dispatch.config.Config;
import dispatch.core.Groups;
import dispatch.store.Workers;
import dispatch.ui.ApiException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Where members' computers reach this machine: a second HTTP server on 127.0.0.1:&lt;workers.port&gt;, behind the owner's
 * tunnel or reverse proxy. It shares nothing with {@code dispatch ui} but the jar — no cookies, no pages, no session:
 * every request but pairing carries a worker key, which names the member whose jobs that worker may take.
 */
public final class WorkerApi implements AutoCloseable {

    public static final String PAIR = "/api/worker/pair";
    public static final String NEXT = "/api/worker/next";
    public static final String PROGRESS = "/api/worker/progress";
    public static final String ATTACHMENT = "/api/worker/attachment";
    public static final String RESULT = "/api/worker/result";
    public static final String PROJECTS = "/api/worker/projects";

    private static final int MAX_BODY = 64 * 1024;
    private static final String WRONG_KEY =
            "this worker key is not valid any more: run dispatch worker pair again with a new code from /worker";

    private final HttpServer server;
    private final Config config;
    private final Groups groups;
    private final WorkerKeys keys;
    private final Set<String> hosts;

    private WorkerApi(HttpServer server, Config config, Groups groups, WorkerKeys keys) {
        this.server = server;
        this.config = config;
        this.groups = groups;
        this.keys = keys;
        this.hosts = allowedHosts(config.workers(), server.getAddress().getPort());
    }

    /** @param config must have a {@code workers} block; {@code port} 0 takes any free port, as tests do */
    public static WorkerApi start(Config config, Groups groups, WorkerKeys keys) throws IOException {
        HttpServer http = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), config.workers().port()), 0);
        WorkerApi api = new WorkerApi(http, config, groups, keys);
        http.createContext("/", api::handle);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        Log.info("worker_api.started", "port", api.port(), "public_url", config.workers().publicUrl());
        return api;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * A reverse proxy passes the name members use; a worker on the same machine uses the loopback address. Anything else
     * is a request that was not meant for this server.
     */
    private static Set<String> allowedHosts(Config.Workers workers, int port) {
        Set<String> hosts = new HashSet<>(Set.of("127.0.0.1:" + port, "localhost:" + port));
        URI url = URI.create(workers.publicUrl());
        if (url.getHost() != null) {
            hosts.add(url.getPort() < 0 ? url.getHost() : url.getHost() + ":" + url.getPort());
            hosts.add(url.getHost());
        }
        return Set.copyOf(hosts);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                respond(exchange);
            } catch (ApiException e) {
                json(exchange, e.status(), error(e.code(), e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                json(exchange, 503, error("stopping", "Dispatch is stopping"));
            } catch (RuntimeException e) {
                Log.error("worker_api.failed", e, "path", exchange.getRequestURI().getPath());
                json(exchange, 500, error("internal", "something went wrong on the team machine"));
            }
        }
    }

    private void respond(HttpExchange exchange) throws IOException, InterruptedException {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (!hosts.contains(hostOf(exchange))) {
            throw new ApiException(403, "host", "this request was not addressed to this Dispatch");
        }
        String path = exchange.getRequestURI().getPath();
        if (!Set.of(PAIR, NEXT, PROGRESS, ATTACHMENT, RESULT, PROJECTS).contains(path)) {
            throw new ApiException(404, "not_found", "no such worker API: " + path);
        }
        if (!exchange.getRequestMethod().equals("POST")) {
            throw new ApiException(405, "method", path + " only answers POST");
        }
        JsonNode body = body(exchange);
        if (path.equals(PAIR)) {
            json(exchange, 200, Json.write(pair(body)));
            return;
        }
        Workers.Paired worker = authenticate(exchange);
        route(exchange, path, worker, body);
    }

    /** The protocol routes join this switch in the next task. */
    private void route(HttpExchange exchange, String path, Workers.Paired worker, JsonNode body) throws IOException,
            InterruptedException {
        if (path.equals(PROJECTS)) {
            json(exchange, 200, Json.write(projects(worker)));
            return;
        }
        throw new ApiException(404, "not_found", "no such worker API: " + path);
    }

    private Workers.Paired authenticate(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String key = header != null && header.startsWith("Bearer ") ? header.substring("Bearer ".length()).strip() : null;
        // Unknown and revoked answer alike, so nobody can learn which keys ever existed.
        return keys.authenticate(key).orElseThrow(() -> new ApiException(401, "unauthorized", WRONG_KEY));
    }

    private ObjectNode pair(JsonNode body) {
        String code = body.path("code").asText("");
        String name = body.path("name").asText("").strip();
        if (name.isEmpty() || name.length() > 40) {
            throw new ApiException(400, "invalid", "name: required, at most 40 characters");
        }
        WorkerKeys.NewKey paired = keys.pair(code, name)
                .orElseThrow(() -> new ApiException(401, "pairing_code",
                        "this pairing code is unknown, used already or older than "
                                + WorkerKeys.CODE_LIFETIME.toMinutes() + " minutes; ask the bot for a new one with /worker"));
        return Json.object().put("workerId", paired.workerId()).put("key", paired.key()).put("team", config.team());
    }

    /** What a worker needs before it can run anything: the team's projects as configured, and the commit author. */
    private ObjectNode projects(Workers.Paired worker) {
        Set<String> mine = groups.projectsOfMember(worker.memberRef());
        ObjectNode answer = Json.object().put("team", config.team())
                .put("authorName", config.delivery().authorName())
                .put("authorEmail", config.delivery().authorEmail());
        ArrayNode projects = answer.putArray("projects");
        for (Config.Project project : config.projects()) {
            if (!mine.contains(project.name())) {
                continue;
            }
            projects.addObject().put("name", project.name()).put("repo", project.repo())
                    .put("baseBranch", project.baseBranch()).put("model", project.model()).put("effort", project.effort());
        }
        return answer;
    }

    private static String hostOf(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        return host == null ? "" : host.strip();
    }

    private static JsonNode body(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) {
            // Drain what is left (up to 1 MiB) so the JDK client sees the 413 instead of a reset connection.
            exchange.getRequestBody().readNBytes(1024 * 1024 - raw.length);
            throw new ApiException(413, "too_large", "the request is larger than " + MAX_BODY / 1024 + " KiB");
        }
        try {
            return raw.length == 0 ? Json.object() : Json.MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new ApiException(400, "invalid", "the request is not JSON");
        }
    }

    static String error(String code, String message) {
        return Json.write(Map.of("error", code, "message", message));
    }

    static void json(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
```

(`java.util.Optional` is imported here for the next task's `next` route; drop the import if this task's compile warns about it and add it back in Task 7.)

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -B test -Dtest=WorkerApiTest`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/worker/WorkerApi.java src/test/java/dispatch/worker/WorkerApiTest.java
git commit -m "Serve the worker endpoints on 127.0.0.1 behind a constant-time key check

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 7: `WorkerApi`: next, progress, result and attachments

**Files:**
- Modify: `src/main/java/dispatch/worker/WorkerApi.java`, `docs/superpowers/specs/2026-09-22-team-workers-design.md`
- Test: `src/test/java/dispatch/worker/WorkerProtocolTest.java`

**Interfaces:**
- Consumes: `RemoteWorkers.next/progress/result/leased`, `dispatch.core.AttachmentSource.download(String, Path)`, `dispatch.OwnerOnly.createDirectories`, `Job.attachments()`.
- Produces:
  - `public static WorkerApi start(Config config, Groups groups, WorkerKeys keys, RemoteWorkers workers, AttachmentSource attachments)`
  - `/api/worker/next` answers `{"job": null}` or `{"job": <Job>}`; `/api/worker/progress` takes `{"taskId","seq","worktree","baseSha","agentStarted","steps","lastAction"}` and answers `{"cancel": bool}`; `/api/worker/result` takes `{"taskId","seq","result": <JobResult>}` and answers `{"ok": true}`; `/api/worker/attachment` takes `{"taskId","fileRef"}` and answers the bytes as `application/octet-stream`

- [ ] **Step 1: Write the failing test** — create `src/test/java/dispatch/worker/WorkerProtocolTest.java`:

```java
package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.Job;
import dispatch.core.JobEvents;
import dispatch.core.JobResult;
import dispatch.domain.Attachment;
import dispatch.domain.RunKind;
import dispatch.store.Database;
import dispatch.store.Tasks;
import dispatch.testing.TestClock;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The five authenticated routes over real HTTP: a job out, progress and files in, a result back. */
class WorkerProtocolTest extends WorkerApiFixture {

    @Test
    void nextCarriesTheWholeJobIncludingItsFinishedPrompt() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));

        JsonNode answer = Json.read(post(WorkerApi.NEXT, key, "{}").body());

        Job job = Json.MAPPER.treeToValue(answer.get("job"), Job.class);
        assertEquals(7, job.taskId());
        assertEquals("Implement the approved plan", job.prompt(), "a finished prompt, not the inputs to build one");
        assertEquals("alm", job.project().name());
        assertEquals(List.of(new Attachment("photo-id", "1-photo.jpg", 3L)), job.attachments());
    }

    @Test
    void nextAnswersNothingWhenTheMemberHasNoWork() throws Exception {
        String key = pair();

        JsonNode answer = Json.read(post(WorkerApi.NEXT, key, "{}").body());

        assertTrue(answer.get("job").isNull(), answer.toString());
    }

    @Test
    void progressRenewsTheLeaseAndCarriesTheCancel() throws Exception {
        String key = pair();
        AtomicReference<String> worktree = new AtomicReference<>();
        offer(job("Implement the approved plan"), new JobEvents() {
            @Override
            public void worktreeCreated(String path, String baseSha) {
                worktree.set(path);
            }

            @Override
            public void agentStarted(Long pid, Instant processStart) {
            }
        });
        post(WorkerApi.NEXT, key, "{}");

        JsonNode answer = Json.read(post(WorkerApi.PROGRESS, key,
                "{\"taskId\":7,\"seq\":2,\"worktree\":\"/home/ann/alm-7\",\"baseSha\":\"6f3030a\",\"agentStarted\":true,"
                        + "\"steps\":9,\"lastAction\":\"Bash: git status\"}").body());

        assertFalse(answer.get("cancel").asBoolean());
        assertEquals("/home/ann/alm-7", worktree.get());
        control.stop(ActiveRuns.StopReason.CANCELLED);
        assertTrue(Json.read(post(WorkerApi.PROGRESS, key, "{\"taskId\":7,\"seq\":2}").body()).get("cancel").asBoolean());
    }

    @Test
    void aResultEndsTheJobAndALateOneGetsConflict() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));
        post(WorkerApi.NEXT, key, "{}");
        AgentResult agent = new AgentResult(AgentOutcome.SUCCEEDED, 0, "fake-session", null, "Raised the timeout",
                new BigDecimal("0.07"), 12, List.of(), null, "claude-sonnet-5", null);
        String body = "{\"taskId\":7,\"seq\":2,\"result\":"
                + Json.write(JobResult.delivered(agent, List.of("README.md"), "https://github.com/acme/alm/pull/9")) + "}";

        HttpResponse<String> first = post(WorkerApi.RESULT, key, body);
        HttpResponse<String> late = post(WorkerApi.RESULT, key, body);

        assertEquals(200, first.statusCode());
        assertEquals(JobResult.Outcome.SUCCEEDED, reported.get().outcome());
        assertEquals("https://github.com/acme/alm/pull/9", reported.get().prUrl());
        assertEquals(409, late.statusCode());
        assertEquals("lease_expired", Json.read(late.body()).get("error").asText());
    }

    @Test
    void anAttachmentIsFetchedOnceAndNoCopyIsKeptHere() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));
        post(WorkerApi.NEXT, key, "{}");

        HttpResponse<byte[]> answer = http.send(HttpRequest.newBuilder(uri(WorkerApi.ATTACHMENT))
                        .header("Authorization", "Bearer " + key)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"taskId\":7,\"fileRef\":\"photo-id\"}")).build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, answer.statusCode());
        assertArrayEquals("hi\n".getBytes(StandardCharsets.UTF_8), answer.body());
        assertEquals("application/octet-stream", answer.headers().firstValue("Content-Type").orElseThrow());
        try (var files = Files.walk(stateDir)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains("photo")),
                    "the team machine keeps no copy of a member's file");
        }
    }

    @Test
    void aFileTheJobNeverMentionedIsNotServed() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));
        post(WorkerApi.NEXT, key, "{}");

        HttpResponse<String> answer = post(WorkerApi.ATTACHMENT, key, "{\"taskId\":7,\"fileRef\":\"someone-elses-file\"}");

        assertEquals(404, answer.statusCode());
        assertEquals("not_found", Json.read(answer.body()).get("error").asText());
    }

    @Test
    void progressAndResultForARunThisComputerDoesNotHoldAreRefused() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));

        assertEquals(409, post(WorkerApi.PROGRESS, key, "{\"taskId\":7,\"seq\":2}").statusCode(),
                "nothing was leased to it yet");
        assertEquals(409, post(WorkerApi.ATTACHMENT, key, "{\"taskId\":7,\"fileRef\":\"photo-id\"}").statusCode());
    }

    private Job job(String prompt) {
        return new Job(7, 2, RunKind.EXECUTE,
                new Job.Project("alm", "git@github.com:acme/alm.git", "/srv/alm", "main", "claude-code", List.of(".env")),
                "main", "6f3030a", null, null, UUID.fromString("11111111-2222-3333-4444-555555555555"), false, prompt,
                "opus", "high", 1_800_000L, new BigDecimal("2.50"),
                List.of(new Attachment("photo-id", "1-photo.jpg", 3L)), "dispatch #7: Fix the login timeout",
                List.of("Requested-by: Bold"), null);
    }
}
```

and the fixture it extends, `src/test/java/dispatch/worker/WorkerApiFixture.java`, which holds what both HTTP tests need (the config, a migrated database with task 7 owned by Bold, a `WorkerApi` started with a `RemoteWorkers` and an `AttachmentSource` that writes `hi\n`, the `offer(...)` helper that runs `remote.run(job, events, control)` on a virtual thread and remembers its `JobResult` in `reported`, and the `pair`/`post`/`uri` helpers copied from `WorkerApiTest`). Move `WorkerApiTest`'s helpers into it and make `WorkerApiTest` extend it too, so neither file grows a second copy.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -B test -Dtest=WorkerProtocolTest`
Expected: FAIL — compilation error on the five-argument `WorkerApi.start`, and every route but `/api/worker/projects` answering 404 `not_found`.

- [ ] **Step 3: Serve the protocol.** In `src/main/java/dispatch/worker/WorkerApi.java` take the two collaborators:

```java
    private final RemoteWorkers workers;
    private final AttachmentSource attachments;
```

```java
    /**
     * @param workers     the jobs waiting for members' computers
     * @param attachments where a task's files come from — the channel they were sent in; the bytes pass through and no
     *                    copy stays on this machine
     */
    public static WorkerApi start(Config config, Groups groups, WorkerKeys keys, RemoteWorkers workers,
                                  AttachmentSource attachments) throws IOException {
```

and replace `route` with the real routing:

```java
    private void route(HttpExchange exchange, String path, Workers.Paired worker, JsonNode body) throws IOException,
            InterruptedException {
        switch (path) {
            case PROJECTS -> json(exchange, 200, Json.write(projects(worker)));
            case NEXT -> {
                Optional<Job> job = workers.next(worker);
                ObjectNode answer = Json.object();
                if (job.isPresent()) {
                    answer.set("job", Json.MAPPER.<JsonNode>valueToTree(job.get()));
                } else {
                    answer.putNull("job");
                }
                json(exchange, 200, Json.write(answer));
            }
            case PROGRESS -> {
                boolean cancel = workers.progress(worker, new RemoteWorkers.Progress(
                        required(body, "taskId").asLong(), required(body, "seq").asInt(),
                        text(body, "worktree"), text(body, "baseSha"), body.path("agentStarted").asBoolean(false),
                        body.hasNonNull("steps") ? body.get("steps").asInt() : null, text(body, "lastAction")));
                json(exchange, 200, Json.write(Json.object().put("cancel", cancel)));
            }
            case RESULT -> {
                JobResult result = Json.MAPPER.treeToValue(required(body, "result"), JobResult.class);
                workers.result(worker, required(body, "taskId").asLong(), required(body, "seq").asInt(), result);
                json(exchange, 200, Json.write(Json.object().put("ok", true)));
            }
            case ATTACHMENT -> attachment(exchange, worker, required(body, "taskId").asLong(), required(body, "fileRef").asText());
            default -> throw new ApiException(404, "not_found", "no such worker API: " + path);
        }
    }

    /**
     * One of the leased job's files, fetched from the channel and streamed straight through. The team machine writes it
     * owner-only while it is in flight and deletes it whatever happens: the bytes belong to the requester.
     */
    private void attachment(HttpExchange exchange, Workers.Paired worker, long taskId, String fileRef) throws IOException {
        Job job = workers.leased(worker, taskId);
        Attachment file = job.attachments().stream().filter(candidate -> candidate.fileRef().equals(fileRef)).findFirst()
                .orElseThrow(() -> new ApiException(404, "not_found", "task " + taskId + " has no such file"));
        Path dir = config.stateDir().resolve("outgoing");
        OwnerOnly.createDirectories(dir);
        Path copy = dir.resolve(taskId + "-" + WorkerKeys.sha256(file.fileRef()).substring(0, 16));
        try {
            attachments.download(file.fileRef(), copy);
            byte[] bytes = Files.readAllBytes(copy);
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            send(exchange, 200, bytes);
        } catch (RuntimeException e) {
            throw new ApiException(404, "not_found", "cannot fetch " + file.name() + ": " + e.getMessage());
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    private static JsonNode required(JsonNode body, String field) {
        if (!body.hasNonNull(field)) {
            throw new ApiException(400, "invalid", field + ": required");
        }
        return body.get(field);
    }

    private static String text(JsonNode body, String field) {
        return body.hasNonNull(field) ? body.get(field).asText() : null;
    }
```

with the imports `dispatch.OwnerOnly`, `dispatch.core.AttachmentSource`, `dispatch.core.Job`, `dispatch.core.JobResult`, `dispatch.domain.Attachment`, `java.nio.file.Files`, `java.nio.file.Path`, and remove the `unused()` stub from Task 6.

Then correct the spec's Protocol section, `docs/superpowers/specs/2026-09-22-team-workers-design.md` line 54 — the job carries a finished prompt, as W-2 ruling 1 decided and `nextCarriesTheWholeJobIncludingItsFinishedPrompt` now proves:

```markdown
1. `/api/worker/next`: a long poll of up to 25 s. Answers `{}` or a job: task number, run number and kind; project name, base branch and repo; model, effort, timeout and budget; session id and whether to resume; the finished prompt; the attachments to fetch.
```

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -B test -Dtest='WorkerProtocolTest+WorkerApiTest'`
Expected: PASS (7 protocol tests and the 8 from Task 6).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/worker/WorkerApi.java src/test/java/dispatch/worker/WorkerProtocolTest.java \
  src/test/java/dispatch/worker/WorkerApiFixture.java src/test/java/dispatch/worker/WorkerApiTest.java \
  docs/superpowers/specs/2026-09-22-team-workers-design.md
git commit -m "Hand a job, its files and its cancel to a worker over HTTP

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 8: In team mode a run waits for its requester's computer

**Files:**
- Modify: `src/main/java/dispatch/store/Runs.java`, `src/main/java/dispatch/core/Scheduler.java`, `src/main/java/dispatch/core/TaskService.java`, `src/main/java/dispatch/telegram/Renderer.java`, `src/main/java/dispatch/App.java`
- Test: `src/test/java/dispatch/core/SchedulerTest.java`, `src/test/java/dispatch/core/TaskLifecycleTest.java`, `src/test/java/dispatch/telegram/RendererTest.java`

**Interfaces:**
- Consumes: `Workers.hasConnected(Tx, String, Instant)`, `Workers.SEEN_WITHIN`, `WorkerKeys.revokeWorkersOfFormerMembers(Set)`, `RemoteWorkers`, `WorkerApi.start(...)`.
- Produces:
  - `public static Optional<ClaimedRun> Runs.claimNext(Tx tx, int maxConcurrentRuns, Instant now, Instant workerSeenSince)` — the three-argument form stays as an overload passing `null`
  - `Scheduler(Database, int, Signal, Clock, Consumer<ClaimedRun>, Duration idlePoll, Duration workerSeenWithin)` — `workerSeenWithin` null in personal mode; the six-argument constructor stays
  - `TaskService(..., boolean requiresWorker)` on both constructors, defaulting to `false`
  - `statusPayload`'s queued items carry `"waitingForWorker": true` when their requester has no connected computer

- [ ] **Step 1: Write the failing tests.** Add to `src/test/java/dispatch/core/SchedulerTest.java`:

```java
    @Test
    void inTeamModeARunWaitsUntilItsRequestersComputerIsConnected() {
        long id = queuedTaskOf("telegram:100");

        assertTrue(db.transactionReturning(tx -> Runs.claimNext(tx, 2, clock.instant(),
                clock.instant().minus(Workers.SEEN_WITHIN))).isEmpty(), "nobody is there to run it");

        long worker = pair("telegram:100", "ann-laptop");
        db.transaction(tx -> Workers.touch(tx, worker, clock.instant()));

        assertEquals(id, db.transactionReturning(tx -> Runs.claimNext(tx, 2, clock.instant(),
                clock.instant().minus(Workers.SEEN_WITHIN))).orElseThrow().taskId());
    }

    @Test
    void aRunWhoseComputerFellSilentIsNotClaimedAgain() {
        long id = queuedTaskOf("telegram:100");
        long worker = pair("telegram:100", "ann-laptop");
        db.transaction(tx -> Workers.touch(tx, worker, clock.instant().minus(Workers.SEEN_WITHIN).minusSeconds(1)));

        assertTrue(db.transactionReturning(tx -> Runs.claimNext(tx, 2, clock.instant(),
                clock.instant().minus(Workers.SEEN_WITHIN))).isEmpty(), "silent for over a minute is gone");
        assertEquals("QUEUED", SqlRows.single(dbFile, "SELECT status FROM run WHERE task_id = ?", id).get("status"));
    }

    @Test
    void aTaskWithAWorktreeGoesBackToItsOwnComputerOrWaits() {
        long id = queuedTaskOf("telegram:100");
        long first = pair("telegram:100", "ann-laptop");
        long second = pair("telegram:100", "ann-desktop");
        db.transaction(tx -> Tasks.recordWorker(tx, id, first, clock.instant()));
        db.transaction(tx -> Workers.touch(tx, second, clock.instant()));

        assertTrue(db.transactionReturning(tx -> Runs.claimNext(tx, 2, clock.instant(),
                clock.instant().minus(Workers.SEEN_WITHIN))).isEmpty(), "the other computer has no worktree for it");

        db.transaction(tx -> Workers.touch(tx, first, clock.instant()));
        assertEquals(id, db.transactionReturning(tx -> Runs.claimNext(tx, 2, clock.instant(),
                clock.instant().minus(Workers.SEEN_WITHIN))).orElseThrow().taskId());
    }

    @Test
    void personalModeClaimsWithoutAskingAboutComputers() {
        long id = queuedTaskOf("telegram:100");

        assertEquals(id, db.transactionReturning(tx -> Runs.claimNext(tx, 2, clock.instant(), null)).orElseThrow().taskId());
    }
```

Add `queuedTaskOf(String requesterRef)` and `pair(String memberRef, String name)` next to the file's own fixture helpers (the first inserts a task and its PLAN run with that requester; the second uses `WorkerKeys`).

Add to `src/test/java/dispatch/core/TaskLifecycleTest.java`:

```java
    @Test
    void aTaskGivenWhileNoComputerIsConnectedTellsItsRequesterOnce() {
        tasks = teamTaskService();

        long id = create(BOLD, "alm", "Fix login timeout", "95");

        assertEquals("1", row("SELECT count(*) AS n FROM outbox WHERE kind = 'WORKER_WAITING' AND task_id = ?", id).get("n"));
        JsonNode status = tasksStatusPayload();
        assertTrue(status.get("queued").get(0).get("waitingForWorker").asBoolean(), status.toString());
    }

    @Test
    void aTaskGivenWithAConnectedComputerSaysNothingAboutWaiting() {
        tasks = teamTaskService();
        WorkerKeys keys = new WorkerKeys(db, clock);
        long worker = keys.pair(keys.newCode(BOLD), "ann-laptop").orElseThrow().workerId();
        db.transaction(tx -> Workers.touch(tx, worker, clock.instant()));

        long id = create(BOLD, "alm", "Fix login timeout", "96");

        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'WORKER_WAITING' AND task_id = ?", id).get("n"));
    }
```

with `teamTaskService()` building today's `TaskService` with `requiresWorker = true`, and `tasksStatusPayload()` calling `db.transactionReturning(tx -> tasks.statusPayload(tx, Set.of("alm"), BOLD.ref()))`.

Add to `src/test/java/dispatch/telegram/RendererTest.java`:

```java
    @Test
    void aQueuedRunWaitingForItsComputerSaysSoOnItsStatusLine() {
        ObjectNode payload = Json.object();
        payload.putArray("running");
        payload.putArray("awaitingApproval");
        payload.putArray("mine");
        payload.putArray("queued").addObject().put("taskId", 7).put("project", "alm")
                .put("title", "Fix the login timeout").put("kind", "PLAN").put("priority", "NORMAL")
                .put("queuedAt", Instant.now().toString()).put("waitingForWorker", true);

        String html = renderer.render(OutboxKind.STATUS, payload).html();

        assertTrue(html.contains(messages.getString("status.waitingForWorker")), html);
    }
```

(use the file's own way of building a STATUS payload and its `messages` bundle if it already has one; the point of the case is only that the flag reaches the line.)

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -B test -Dtest='SchedulerTest+TaskLifecycleTest'`
Expected: FAIL — compilation errors, `claimNext(Tx,int,Instant,Instant)` not found and `TaskService` having no `requiresWorker` constructor.

- [ ] **Step 3: Decide it in SQL and wire it.** In `src/main/java/dispatch/store/Runs.java`:

```java
    public static Optional<ClaimedRun> claimNext(Tx tx, int maxConcurrentRuns, Instant now) {
        return claimNext(tx, maxConcurrentRuns, now, null);
    }

    /**
     * Claims the most urgent queued run that may start now, the oldest among equals: fewer than {@code maxConcurrentRuns}
     * runs are active, and an execution-type run also needs its project to have no other execution running (builds and
     * tests of the same project can clash on ports and test databases). Planning runs only need a free slot. A run that
     * cannot start yet never holds back the ones after it.
     *
     * @param workerSeenSince in team mode, a run starts only when one of its requester's computers reported since then —
     *                        and, once the task has a worktree, only that computer; null in personal mode, where the run
     *                        happens in this process
     */
    public static Optional<ClaimedRun> claimNext(Tx tx, int maxConcurrentRuns, Instant now, Instant workerSeenSince) {
        int running = tx.one("SELECT count(*) AS n FROM run WHERE status = ?", row -> row.intValue("n"), RunStatus.RUNNING)
                .orElse(0);
        if (running >= maxConcurrentRuns) {
            return Optional.empty();
        }
        String workerGate = workerSeenSince == null ? "" : """
                          AND EXISTS (SELECT 1 FROM worker w
                                      WHERE w.member_ref = t.requester_ref AND w.revoked_at IS NULL
                                        AND w.last_seen_at > ?
                                        AND (t.worker_id IS NULL OR t.worker_id = w.id))
                """;
        List<Object> params = new ArrayList<>(List.of(RunStatus.QUEUED, RunKind.PLAN, RunStatus.RUNNING, RunKind.EXECUTE,
                RunKind.DELIVER));
        if (workerSeenSince != null) {
            params.add(workerSeenSince);
        }
        params.add(Priority.URGENT);
        params.add(Priority.NORMAL);
        Optional<ClaimedRun> next = tx.one("""
                        SELECT r.task_id, r.seq, r.kind
                        FROM run r JOIN task t ON t.id = r.task_id
                        WHERE r.status = ?
                          AND (r.kind = ? OR NOT EXISTS (
                                SELECT 1 FROM run busy JOIN task busy_task ON busy_task.id = busy.task_id
                                WHERE busy.status = ? AND busy.kind IN (?, ?) AND busy_task.project = t.project))
                        """ + workerGate + """
                        ORDER BY CASE t.priority WHEN ? THEN 0 WHEN ? THEN 1 ELSE 2 END, r.queued_at, r.task_id, r.seq
                        LIMIT 1""",
                row -> new ClaimedRun(row.longValue("task_id"), row.intValue("seq"), row.enumValue("kind", RunKind.class)),
                params.toArray());
        next.ifPresent(run -> {
            tx.update("UPDATE run SET status = ?, started_at = ? WHERE task_id = ? AND seq = ? AND status = ?",
                    RunStatus.RUNNING, now, run.taskId(), run.seq(), RunStatus.QUEUED);
            tx.update("UPDATE task SET started_at = COALESCE(started_at, ?), updated_at = ? WHERE id = ?",
                    now, now, run.taskId());
        });
        return next;
    }
```

In `src/main/java/dispatch/core/Scheduler.java` add the field, the parameter and the overload, and use it in the loop:

```java
    private final Duration workerSeenWithin;

    public Scheduler(Database db, int maxConcurrentRuns, Signal signal, Clock clock, Consumer<ClaimedRun> starter,
                     Duration idlePoll) {
        this(db, maxConcurrentRuns, signal, clock, starter, idlePoll, null);
    }

    /** @param workerSeenWithin team mode: how recently a requester's computer must have reported; null in personal mode */
    public Scheduler(Database db, int maxConcurrentRuns, Signal signal, Clock clock, Consumer<ClaimedRun> starter,
                     Duration idlePoll, Duration workerSeenWithin) {
```

```java
            Instant now = clock.instant();
            Instant seenSince = workerSeenWithin == null ? null : now.minus(workerSeenWithin);
            Optional<ClaimedRun> next = db.transactionReturning(tx -> Runs.claimNext(tx, maxConcurrentRuns, now, seenSince));
```

In `src/main/java/dispatch/core/TaskService.java` add the field and constructor parameter (both constructors; the six-argument one passes `false`):

```java
    /** Team mode: a task runs on its requester's own computer, so it waits when none of theirs is connected. */
    private final boolean requiresWorker;
```

In `insertTask`, after the `TASK_QUEUED` block:

```java
        if (requiresWorker && !Workers.hasConnected(tx, who.ref(), now.minus(Workers.SEEN_WITHIN))) {
            // Said once, when the task is given; /status keeps showing it until a computer connects.
            enqueue(tx, id, OutboxKind.WORKER_WAITING, who.ref(), null, Json.object().put("taskId", id), now);
        }
```

and in `statusPayload`, in the `else` branch of the running/queued loop:

```java
            } else {
                item.put("queuedAt", text(run.queuedAt()));
                Task queuedTask = active.get(run.taskId());
                if (requiresWorker && queuedTask != null
                        && !Workers.hasConnected(tx, queuedTask.requester().ref(), clock.instant().minus(Workers.SEEN_WITHIN))) {
                    item.put("waitingForWorker", true);
                }
            }
```

In `src/main/java/dispatch/telegram/Renderer.java`, in `status(JsonNode)`'s queued loop (~line 404), append the note when the flag is set:

```java
            for (JsonNode run : queued) {
                blocks.add(icon(run) + format("status.queuedLine", taskId(run), escape(run.path("project").asText()),
                        text("kind." + run.path("kind").asText()), age(Instant.parse(run.path("queuedAt").asText())))
                        + (run.path("waitingForWorker").asBoolean() ? " · " + text("status.waitingForWorker") : "")
                        + "\n   " + escapeWithin(run.path("title").asText(), TITLE_LIMIT));
            }
```

In `src/main/java/dispatch/App.java` the order changes: the agents map and the worker choice move **above** `TaskService`, because `TaskService` now needs to know whether runs happen elsewhere. After `Groups groups = new Groups(config.telegram());` and before `Splitter[] splitter = new Splitter[1];`, move the `agents` line up and add the branch:

```java
        Map<String, Agent> agents = Map.of("claude-code",
                new ClaudeCodeAgent(config.agents().get("claude-code").command(), environment, Duration.ofSeconds(10)));
        WorkerKeys workerKeys = null;
        Worker worker;
        WorkerApi workerApi = null;
        if (config.workers() == null) {
            // Personal mode runs the job in this process, exactly as before.
            worker = new JobRunner(workspaces, delivery, agents, redactor, api::downloadFile);
        } else {
            workerKeys = new WorkerKeys(db, clock);
            // Someone taken out of the config takes their computers' access with them.
            workerKeys.revokeWorkersOfFormerMembers(memberRefs(groups));
            RemoteWorkers remoteWorkers = new RemoteWorkers(db, clock, schedulerSignal::wake);
            try {
                workerApi = WorkerApi.start(config, groups, workerKeys, remoteWorkers, api::downloadFile);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot listen on 127.0.0.1:" + config.workers().port()
                        + " for members' computers: " + e.getMessage(), e);
            }
            worker = remoteWorkers;
        }
        Coordinator coordinator = new Coordinator(db, projects, transitions, activeRuns, config::planLimits,
                config::executeLimits, worker, schedulerSignal::wake);
```

Delete the old `agents`, `jobRunner` and `coordinator` lines further down (~104-109) now that they live here.

```java
        Scheduler scheduler = new Scheduler(db, config.scheduler().maxConcurrentRuns(), schedulerSignal, clock,
                run -> Thread.ofVirtual().name("run-" + run.taskId() + "." + run.seq())
                        .start(app[0].guarded(() -> coordinator.execute(run))),
                Duration.ofSeconds(5), config.workers() == null ? null : dispatch.store.Workers.SEEN_WITHIN);
```

`TaskService` and the update handler then take what the branch decided:

```java
        TaskService tasks = new TaskService(groups, projects, activeRuns, clock,
                schedulerSignal::wake, outboxSignal::wake, taskTopics, draftId -> splitter[0].start(draftId),
                config.workers() != null);
```

```java
        UpdateHandler handler = new UpdateHandler(db, tasks, new Membership(groups, members, clock, outboxSignal::wake), groups, projects,
                api, renderer, redactor, botUsername, clock, outboxSignal::wake, workerKeys,
                config.workers() == null ? null : config.workers().publicUrl());
```

Keep the `WorkerApi` in the `App` instance and close it in `stop()` after `activeRuns.awaitIdle(...)`, so a worker reporting during shutdown still gets through; add a getter for the tests:

```java
    /** The port members' computers connect to; 0 in personal mode. Tests start with port 0 and ask afterwards. */
    public int workerPort() {
        return workerApi == null ? 0 : workerApi.port();
    }
```

and a small helper beside `registerCommandMenus`:

```java
    private static java.util.Set<String> memberRefs(Groups groups) {
        return groups.all().stream().flatMap(group -> group.members().stream())
                .map(member -> "telegram:" + member.id()).collect(java.util.stream.Collectors.toSet());
    }
```

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -B verify`
Expected: PASS. `SchedulerTest`, `RunExecutorTest` and `AppTest` still run in personal mode with `workerSeenSince = null`, so their behaviour is unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/store/Runs.java src/main/java/dispatch/core/Scheduler.java \
  src/main/java/dispatch/core/TaskService.java src/main/java/dispatch/telegram/Renderer.java \
  src/main/java/dispatch/App.java src/test/java/dispatch/core/SchedulerTest.java \
  src/test/java/dispatch/core/TaskLifecycleTest.java
git commit -m "Start a team's run only when its requester's own computer is there

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 9: The member's computer: `worker.yaml`, `dispatch worker pair`, `dispatch worker run`

**Files:**
- Create: `src/main/java/dispatch/worker/WorkerConfig.java`, `WorkerConfigLoader.java`, `WorkerClient.java`, `LocalAgents.java`, `WorkerLoop.java`, `WorkerCommand.java`
- Modify: `src/main/java/dispatch/cli/Cli.java`, `src/main/java/dispatch/cli/Locations.java`, `src/main/java/dispatch/cli/SecretsFile.java`, `src/main/java/dispatch/Main.java`
- Test: `src/test/java/dispatch/worker/WorkerConfigLoaderTest.java`, `src/test/java/dispatch/worker/WorkerLoopTest.java`, `src/test/java/dispatch/cli/CliTest.java`

**Interfaces:**
- Consumes: `WorkerApi` over HTTP; `JobRunner(Workspaces, Delivery, Map<String,Agent>, Redactor, AttachmentSource)`; `ActiveRuns.register/stop/activity/unregister`; `ProcessTrees.findSame/terminate`; `SecretsFile.read/write`; `Cli.parse`.
- Produces:
  - `public record WorkerConfig(String team, String name, int maxConcurrentRuns, String claudeCommand, String ghCommand, Path stateDir, Map<String, WorkerConfig.Project> projects)` with `record Project(String path, String model, String effort)`
  - `public static WorkerConfig WorkerConfigLoader.load(Path file)`
  - `public final class WorkerClient` with `pair(HttpClient, URI, String code, String name, Duration)`, `next()`, `progress(RemoteWorkers.Progress)`, `result(long, int, JobResult)`, `attachment(long, String, Path)`, `setup()`
  - `public final class WorkerLoop implements Runnable` with `WorkerLoop(WorkerConfig config, WorkerClient client, Map<String, Agent> agentsByType, Workspaces workspaces, Delivery delivery, Redactor redactor, ActiveRuns activeRuns)`, `run()`, `stop()`, `PROGRESS`
  - `SecretsFile.read` and `SecretsFile.write` become public
  - `Cli.WorkerPair(Path workerFile, String url, String code, String name)`, `Cli.WorkerRun(Path workerFile)`, `Locations.workerFile()`
  - `dispatch worker pair <url> <code> [--name NAME]` and `dispatch worker run`

- [ ] **Step 1: Write the failing tests** — create `src/test/java/dispatch/worker/WorkerConfigLoaderTest.java`:

```java
package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.config.ConfigException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** worker.yaml: where this computer's clones are, and nothing the team machine already knows. */
class WorkerConfigLoaderTest {

    @TempDir
    Path dir;

    @Test
    void aWorkerConfigMapsProjectsToLocalClones() throws Exception {
        WorkerConfig config = WorkerConfigLoader.load(write("""
                team: https://team.example.com
                name: ann-laptop
                maxConcurrentRuns: 2
                claudeCommand: /usr/local/bin/claude
                projects:
                  crm:
                    path: /home/ann/work/crm
                    model: opus
                """));

        assertEquals("https://team.example.com", config.team());
        assertEquals("ann-laptop", config.name());
        assertEquals(2, config.maxConcurrentRuns());
        assertEquals("/usr/local/bin/claude", config.claudeCommand());
        assertEquals("/home/ann/work/crm", config.projects().get("crm").path());
        assertEquals("opus", config.projects().get("crm").model());
    }

    @Test
    void whatIsLeftOutGetsAWorkingDefault() throws Exception {
        WorkerConfig config = WorkerConfigLoader.load(write("""
                team: https://team.example.com
                name: ann-laptop
                """));

        assertEquals(1, config.maxConcurrentRuns());
        assertEquals("claude", config.claudeCommand());
        assertEquals("gh", config.ghCommand());
        assertTrue(config.projects().isEmpty());
    }

    @Test
    void everyProblemIsReportedAtOnce() throws Exception {
        Path file = write("""
                team: ftp://team.example.com
                name: ""
                maxConcurrentRuns: 0
                projects:
                  crm:
                    path: work/crm
                """);

        ConfigException error = assertThrows(ConfigException.class, () -> WorkerConfigLoader.load(file));

        assertTrue(error.getMessage().contains("team: must start with https://"), error.getMessage());
        assertTrue(error.getMessage().contains("name: required"), error.getMessage());
        assertTrue(error.getMessage().contains("maxConcurrentRuns: at least 1"), error.getMessage());
        assertTrue(error.getMessage().contains("projects.crm.path: must be an absolute path"), error.getMessage());
    }

    private Path write(String yaml) throws Exception {
        Path file = dir.resolve("worker.yaml");
        Files.writeString(file, yaml);
        return file;
    }
}
```

and `src/test/java/dispatch/worker/WorkerLoopTest.java`, which runs a real loop against a real `WorkerApi`:

```java
package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.core.JobResult;
import dispatch.domain.FailureReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** The member's side: it takes the job, runs the real JobRunner and reports back. */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake claude and gh CLIs are POSIX shell scripts")
class WorkerLoopTest extends WorkerApiFixture {

    @Test
    void theLoopRunsAJobInItsOwnCloneAndReportsTheResult() throws Exception {
        startLoop("ann-laptop", repos.repo("alm"));

        offer(planJob());

        JobResult result = awaitResult();
        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome());
        assertTrue(result.agent().structuredOutput().contains("understanding"), result.agent().structuredOutput());
        assertTrue(worktreeOf("ann-laptop", 7).resolve("fake-claude.args").toFile().exists(),
                "the worktree is on this computer, not on the team machine");
    }

    @Test
    void aProjectThisComputerDoesNotHaveFailsWithWhatToDo() throws Exception {
        startLoopWithoutProjects("ann-laptop");

        offer(planJob());

        JobResult result = awaitResult();
        assertEquals(FailureReason.SETUP, result.failureReason());
        assertEquals("project alm is not set up on your computer: run dispatch worker init", result.failureDetail());
    }

    @Test
    void progressReachesTheTeamMachineWhileTheAgentRuns() throws Exception {
        startLoop("ann-laptop", repos.repo("alm"));

        offer(planJob());
        awaitResult();

        assertEquals("/", worktreeOf("ann-laptop", 7).toString().substring(0, 1));
        assertTrue(recordedWorktree.get().startsWith(workerStateDir("ann-laptop").toString()),
                "the team machine learned this computer's path: " + recordedWorktree.get());
        assertTrue(agentStartedWithoutAPid.get(), "the pid stays on this computer");
    }

    @Test
    void aCancelFromTheTeamMachineStopsTheAgent() throws Exception {
        startLoop("ann-laptop", repos.repo("alm"));
        offer(planJob("SCENARIO:sleep"));
        awaitAgentStarted();

        control.stop(dispatch.core.ActiveRuns.StopReason.CANCELLED);

        assertEquals(JobResult.Outcome.CANCELLED, awaitResult().outcome());
    }
}
```

Extend `WorkerApiFixture` (Task 7) with what these need: a `GitFixture`, `FakeClaude`/`FakeGh` installs, `startLoop(name, clonePath)` (pair a worker, build its `WorkerConfig`, `WorkerClient`, `Workspaces`, `Delivery` and `JobRunner`, and start `WorkerLoop` on a virtual thread), `startLoopWithoutProjects(name)`, `workerStateDir(name)`, `worktreeOf(name, taskId)`, `awaitResult()`, `awaitAgentStarted()` and the `recordedWorktree` / `agentStartedWithoutAPid` the fixture's `JobEvents` fills in.

Add to `src/test/java/dispatch/cli/CliTest.java`:

```java
    @Test
    void workerPairAndRunAreParsed() {
        Cli.WorkerPair pair = (Cli.WorkerPair) Cli.parse(
                new String[] {"worker", "pair", "https://team.example.com", "ABCD2345", "--name", "ann-laptop"}, defaults);
        Cli.WorkerRun run = (Cli.WorkerRun) Cli.parse(new String[] {"worker", "run"}, defaults);

        assertEquals("https://team.example.com", pair.url());
        assertEquals("ABCD2345", pair.code());
        assertEquals("ann-laptop", pair.name());
        assertEquals(defaults.workerFile(), run.workerFile());
    }

    @Test
    void workerNeedsAKnownSubcommandAndItsArguments() {
        assertEquals("worker needs one of: pair, run", assertThrows(CliException.class,
                () -> Cli.parse(new String[] {"worker"}, defaults)).getMessage());
        assertEquals("worker pair needs the team URL and the code from /worker", assertThrows(CliException.class,
                () -> Cli.parse(new String[] {"worker", "pair", "https://team.example.com"}, defaults)).getMessage());
        assertEquals("unknown command 'worker restart'", assertThrows(CliException.class,
                () -> Cli.parse(new String[] {"worker", "restart"}, defaults)).getMessage());
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -B test -Dtest='WorkerConfigLoaderTest+WorkerLoopTest+CliTest'`
Expected: FAIL — compilation errors, `cannot find symbol: class WorkerConfig`, `class WorkerLoop`, `class Cli.WorkerPair`.

- [ ] **Step 3: Write the member's side.** Create `src/main/java/dispatch/worker/WorkerConfig.java`:

```java
package dispatch.worker;

import java.nio.file.Path;
import java.util.Map;

/**
 * The member's own settings, in {@code worker.yaml} beside {@code dispatch.yaml}. It holds only what is true on this
 * computer: everything about the team's projects — repo, base branch, agent, the files to copy — comes from the team
 * machine with each job.
 *
 * @param team              the team's URL, as {@code dispatch worker pair} was given it
 * @param name              what the member's /worker list calls this computer
 * @param claudeCommand     the member's own Claude Code, which runs with the member's own login
 * @param stateDir          where this computer keeps its worktrees, run logs and attachments
 * @param projects          project name to what this computer knows about it; a project that is missing here cannot run
 */
public record WorkerConfig(String team, String name, int maxConcurrentRuns, String claudeCommand, String ghCommand,
                           Path stateDir, Map<String, Project> projects) {

    public WorkerConfig {
        projects = Map.copyOf(projects);
    }

    /**
     * @param path   an existing clone on this computer
     * @param model  overrides the team's model for this project here; null keeps the team's
     * @param effort likewise
     */
    public record Project(String path, String model, String effort) {
    }
}
```

Create `src/main/java/dispatch/worker/WorkerConfigLoader.java`:

```java
package dispatch.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dispatch.config.ConfigException;
import dispatch.config.ConfigLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Reads worker.yaml and fails with every problem listed, as the team machine's loader does. */
public final class WorkerConfigLoader {

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,40}");

    private WorkerConfigLoader() {
    }

    /** The YAML file's shape; the key is never in it — it lives in worker.env. */
    record WorkerFile(String team, String name, Integer maxConcurrentRuns, String claudeCommand, String ghCommand,
                      String stateDir, Map<String, Project> projects) {

        record Project(String path, String model, String effort) {
        }
    }

    public static WorkerConfig load(Path file) {
        WorkerFile raw = read(file);
        List<String> errors = new ArrayList<>();
        if (raw.team() == null || !ConfigLoader.isWorkerUrl(raw.team())) {
            errors.add("team: must start with https:// (plain http only for 127.0.0.1), got '" + raw.team() + "'");
        }
        if (raw.name() == null || !NAME.matcher(raw.name()).matches()) {
            errors.add("name: required; letters, digits, '.', '_' and '-', at most 40 characters");
        }
        int concurrent = raw.maxConcurrentRuns() == null ? 1 : raw.maxConcurrentRuns();
        if (concurrent < 1) {
            errors.add("maxConcurrentRuns: at least 1");
        }
        Path stateDir = raw.stateDir() == null
                ? dispatch.cli.Locations.current().stateDir()
                : Path.of(raw.stateDir());
        if (!stateDir.isAbsolute()) {
            errors.add("stateDir: must be an absolute path, got '" + raw.stateDir() + "'");
        }
        Map<String, WorkerConfig.Project> projects = new LinkedHashMap<>();
        (raw.projects() == null ? Map.<String, WorkerFile.Project>of() : raw.projects()).forEach((name, project) -> {
            if (project == null || project.path() == null || !Path.of(project.path()).isAbsolute()) {
                errors.add("projects." + name + ".path: must be an absolute path to a clone on this computer");
                return;
            }
            projects.put(name, new WorkerConfig.Project(project.path(), project.model(), project.effort()));
        });
        if (!errors.isEmpty()) {
            throw new ConfigException(file + " is invalid:\n  - " + String.join("\n  - ", errors));
        }
        return new WorkerConfig(raw.team(), raw.name(), concurrent,
                raw.claudeCommand() == null ? "claude" : raw.claudeCommand(),
                raw.ghCommand() == null ? "gh" : raw.ghCommand(), stateDir, projects);
    }

    private static WorkerFile read(Path file) {
        try {
            return YAML.readValue(file.toFile(), WorkerFile.class);
        } catch (JsonProcessingException e) {
            throw new ConfigException(file + ": " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new ConfigException("cannot read " + file + ": " + e.getMessage()
                    + " (run dispatch worker pair first)");
        }
    }
}
```

Create `src/main/java/dispatch/worker/WorkerClient.java`:

```java
package dispatch.worker;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.core.Job;
import dispatch.core.JobResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** This computer's side of the protocol: one HTTP call per route, with the worker key on every one but pairing. */
public final class WorkerClient {

    /** The long poll answers within 25 s; the read timeout only has to be longer than that. */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(40);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final URI team;
    private final String key;

    public WorkerClient(HttpClient http, URI team, String key) {
        this.http = http;
        this.team = team;
        this.key = key;
    }

    /** The team refused this key: it was revoked, or the team machine was set up again. */
    public static final class RevokedException extends RuntimeException {

        RevokedException(String message) {
            super(message);
        }
    }

    /** The run is no longer this computer's: its worktree stays, the member can retry it. */
    public static final class LeaseExpiredException extends RuntimeException {

        LeaseExpiredException(String message) {
            super(message);
        }
    }

    public record Paired(long workerId, String key, String team) {
    }

    /** @param name what the member's /worker list will call this computer */
    public static Paired pair(HttpClient http, URI team, String code, String name) {
        JsonNode answer = send(http, HttpRequest.newBuilder(team.resolve(WorkerApi.PAIR)).timeout(CALL_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(java.util.Map.of("code", code, "name", name)))));
        return new Paired(answer.get("workerId").asLong(), answer.get("key").asText(), answer.get("team").asText());
    }

    /** What this computer needs before it can run anything. */
    public record Setup(String team, String authorName, String authorEmail, List<ProjectInfo> projects) {
    }

    public record ProjectInfo(String name, String repo, String baseBranch, String model, String effort) {
    }

    public Setup setup() {
        JsonNode answer = call(WorkerApi.PROJECTS, "{}", CALL_TIMEOUT);
        List<ProjectInfo> projects = new ArrayList<>();
        answer.get("projects").forEach(project -> projects.add(new ProjectInfo(project.path("name").asText(),
                project.path("repo").asText(null), project.path("baseBranch").asText(null),
                project.path("model").asText(null), project.path("effort").asText(null))));
        return new Setup(answer.get("team").asText(), answer.get("authorName").asText(),
                answer.get("authorEmail").asText(), projects);
    }

    /** Waits up to 25 s for work; empty when the member has none. */
    public Optional<Job> next() {
        JsonNode answer = call(WorkerApi.NEXT, "{}", POLL_TIMEOUT);
        if (answer.get("job").isNull()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Json.MAPPER.treeToValue(answer.get("job"), Job.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("the team machine sent a job this version cannot read: " + e.getOriginalMessage(), e);
        }
    }

    /** @return true when the member cancelled the task and the agent must stop */
    public boolean progress(RemoteWorkers.Progress progress) {
        return call(WorkerApi.PROGRESS, Json.write(progress), CALL_TIMEOUT).get("cancel").asBoolean();
    }

    public void result(long taskId, int seq, JobResult result) {
        var body = Json.object().put("taskId", taskId).put("seq", seq);
        body.set("result", Json.MAPPER.valueToTree(result));
        call(WorkerApi.RESULT, Json.write(body), CALL_TIMEOUT);
    }

    public void attachment(long taskId, String fileRef, Path target) {
        HttpRequest request = authorized(HttpRequest.newBuilder(team.resolve(WorkerApi.ATTACHMENT)).timeout(CALL_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        Json.write(java.util.Map.of("taskId", taskId, "fileRef", fileRef))))).build();
        try {
            HttpResponse<Path> answer = http.send(request, HttpResponse.BodyHandlers.ofFile(target,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
            if (answer.statusCode() != 200) {
                throw new IllegalStateException("the team machine answered " + answer.statusCode());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while fetching a file", e);
        }
    }

    private JsonNode call(String path, String body, Duration timeout) {
        return send(http, authorized(HttpRequest.newBuilder(team.resolve(path)).timeout(timeout)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))));
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder request) {
        return request.header("Authorization", "Bearer " + key);
    }

    private static JsonNode send(HttpClient http, HttpRequest.Builder request) {
        HttpResponse<String> answer;
        try {
            answer = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("cannot reach the team machine: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while talking to the team machine", e);
        }
        if (answer.statusCode() == 200) {
            return Json.read(answer.body());
        }
        JsonNode error = Json.read(answer.body());
        String message = error.path("message").asText(answer.body());
        throw switch (answer.statusCode()) {
            case 401 -> new RevokedException(message);
            case 409 -> new LeaseExpiredException(message);
            default -> new IllegalStateException("the team machine answered " + answer.statusCode() + ": " + message);
        };
    }
}
```

Create `src/main/java/dispatch/worker/LocalAgents.java`:

```java
package dispatch.worker;

import dispatch.Log;
import dispatch.OwnerOnly;
import dispatch.ProcessTrees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * The agent processes this computer started, one small file per run. The team machine cannot clean them up — the process
 * is here — so a worker that was killed mid-run kills what it left behind the next time it starts (ADR 0008).
 */
public final class LocalAgents {

    private final Path dir;

    public LocalAgents(Path stateDir) {
        this.dir = stateDir.resolve("agents");
    }

    public void record(long taskId, int seq, long pid, Instant processStart) {
        try {
            OwnerOnly.createDirectories(dir);
            Files.writeString(file(taskId, seq), pid + " " + processStart);
        } catch (IOException e) {
            // Losing the note only costs an orphan check after a crash; the run itself is fine.
            Log.warn("worker.agent_not_recorded", "task", taskId, "run", seq, "error", e.getMessage());
        }
    }

    public void forget(long taskId, int seq) {
        try {
            Files.deleteIfExists(file(taskId, seq));
        } catch (IOException e) {
            Log.warn("worker.agent_note_kept", "task", taskId, "run", seq, "error", e.getMessage());
        }
    }

    /** Must run before the loop takes its first job, while no agent of this process exists. */
    public void killOrphans(Duration grace) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var notes = Files.list(dir)) {
            notes.forEach(note -> {
                try {
                    String[] pidAndStart = Files.readString(note).strip().split(" ", 2);
                    ProcessTrees.findSame(Long.parseLong(pidAndStart[0]), Instant.parse(pidAndStart[1]))
                            .ifPresent(orphan -> {
                                Log.warn("worker.orphan_killed", "note", note.getFileName(), "pid", orphan.pid());
                                ProcessTrees.terminate(orphan, grace);
                            });
                    Files.deleteIfExists(note);
                } catch (IOException | RuntimeException e) {
                    Log.warn("worker.orphan_check_failed", "note", note.getFileName(), "error", e.getMessage());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(long taskId, int seq) {
        return dir.resolve(taskId + "." + seq);
    }
}
```

Create `src/main/java/dispatch/worker/WorkerLoop.java`:

```java
package dispatch.worker;

import dispatch.Log;
import dispatch.Redactor;
import dispatch.agent.Agent;
import dispatch.core.ActiveRuns;
import dispatch.core.Job;
import dispatch.core.JobEvents;
import dispatch.core.JobResult;
import dispatch.core.JobRunner;
import dispatch.domain.FailureReason;
import dispatch.workspace.Delivery;
import dispatch.workspace.Workspaces;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code dispatch worker run}: asks the team machine for this member's next job, runs it with the same {@link JobRunner}
 * a personal Dispatch uses — the member's own clone, Claude Code login and {@code gh} — and reports back. Progress every
 * 10 s renews the run's lease and brings back the member's cancel.
 */
public final class WorkerLoop implements Runnable {

    /** The spec's progress interval: also how soon a cancel reaches the agent. */
    public static final Duration PROGRESS = Duration.ofSeconds(10);
    private static final Duration RETRY_AFTER_ERROR = Duration.ofSeconds(5);

    private final WorkerConfig config;
    private final WorkerClient client;
    private final Map<String, Agent> agentsByType;
    private final Workspaces workspaces;
    private final Delivery delivery;
    private final Redactor redactor;
    private final ActiveRuns activeRuns;
    private final LocalAgents agents;
    private final AtomicInteger running = new AtomicInteger();
    private volatile boolean stopped;

    public WorkerLoop(WorkerConfig config, WorkerClient client, Map<String, Agent> agentsByType, Workspaces workspaces,
                      Delivery delivery, Redactor redactor, ActiveRuns activeRuns) {
        this.config = config;
        this.client = client;
        this.agentsByType = Map.copyOf(agentsByType);
        this.workspaces = workspaces;
        this.delivery = delivery;
        this.redactor = redactor;
        this.activeRuns = activeRuns;
        this.agents = new LocalAgents(config.stateDir());
    }

    /** One runner per job, so its attachment source knows which task's files it may fetch. */
    private JobRunner runnerFor(Job job) {
        return new JobRunner(workspaces, delivery, agentsByType, redactor,
                (fileRef, target) -> client.attachment(job.taskId(), fileRef, target));
    }

    @Override
    public void run() {
        agents.killOrphans(Duration.ofSeconds(10));
        Log.info("worker.started", "team", config.team(), "name", config.name(),
                "max_concurrent_runs", config.maxConcurrentRuns());
        while (!stopped) {
            if (running.get() >= config.maxConcurrentRuns()) {
                sleep(Duration.ofMillis(200));
                continue;
            }
            try {
                Optional<Job> job = client.next();
                job.ifPresent(this::start);
            } catch (WorkerClient.RevokedException e) {
                Log.error("worker.key_revoked", null, "detail", e.getMessage());
                stopped = true;
            } catch (RuntimeException e) {
                Log.warn("worker.poll_failed", "error", e.getMessage());
                sleep(RETRY_AFTER_ERROR);
            }
        }
        Log.info("worker.stopped");
    }

    public void stop() {
        stopped = true;
        activeRuns.stopAll(ActiveRuns.StopReason.INTERRUPTED);
    }

    private void start(Job job) {
        running.incrementAndGet();
        Thread.ofVirtual().name("worker-run-" + job.taskId() + "." + job.seq()).start(() -> {
            try {
                carry(job);
            } finally {
                running.decrementAndGet();
            }
        });
    }

    private void carry(Job job) {
        ActiveRuns.ActiveRun control = activeRuns.register(job.taskId(), job.seq());
        Thread ticker = null;
        try {
            Optional<Job> local = withLocalClone(job);
            if (local.isEmpty()) {
                report(job, JobResult.failed(FailureReason.SETUP,
                        "project " + job.project().name() + " is not set up on your computer: run dispatch worker init", null));
                return;
            }
            ticker = Thread.ofVirtual().name("worker-progress-" + job.taskId()).start(() -> tick(job, control));
            report(job, runnerFor(job).run(local.get(), events(job), control));
        } catch (RuntimeException e) {
            Log.error("worker.run_failed", e, "task", job.taskId(), "run", job.seq());
            report(job, JobResult.failed(FailureReason.INTERNAL, "the worker broke: " + e.getMessage(), null));
        } finally {
            if (ticker != null) {
                ticker.interrupt();
            }
            agents.forget(job.taskId(), job.seq());
            activeRuns.unregister(control);
        }
    }

    /** The team's project as this computer has it: its own clone, and its own model and effort when it set any. */
    private Optional<Job> withLocalClone(Job job) {
        WorkerConfig.Project mine = config.projects().get(job.project().name());
        if (mine == null) {
            return Optional.empty();
        }
        Job.Project project = new Job.Project(job.project().name(), job.project().repo(), mine.path(),
                job.project().baseBranch(), job.project().agent(), job.project().copyFiles());
        return Optional.of(new Job(job.taskId(), job.seq(), job.kind(), project, job.baseBranch(), job.baseSha(),
                job.worktree(), job.prUrl(), job.sessionId(), job.resume(), job.prompt(),
                mine.model() == null ? job.model() : mine.model(), mine.effort() == null ? job.effort() : mine.effort(),
                job.timeoutMillis(), job.budgetUsd(), job.attachments(), job.commitSubject(), job.commitTrailers(),
                job.deliverySummary()));
    }

    /** The two store writes the team machine cannot wait for, sent the moment they happen. */
    private JobEvents events(Job job) {
        return new JobEvents() {

            @Override
            public void worktreeCreated(String worktree, String baseSha) {
                client.progress(new RemoteWorkers.Progress(job.taskId(), job.seq(), worktree, baseSha, false, null, null));
            }

            @Override
            public void agentStarted(Long pid, Instant processStart) {
                if (pid != null) {
                    agents.record(job.taskId(), job.seq(), pid, processStart);
                }
                client.progress(new RemoteWorkers.Progress(job.taskId(), job.seq(), null, null, true, null, null));
            }
        };
    }

    /** Every 10 s: what the agent is doing, and whatever the team machine answers about a cancel. */
    private void tick(Job job, ActiveRuns.ActiveRun control) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(PROGRESS);
                var activity = activeRuns.activity(job.taskId());
                boolean cancel = client.progress(new RemoteWorkers.Progress(job.taskId(), job.seq(), null, null, false,
                        activity.map(a -> a.steps()).orElse(null), activity.map(a -> a.lastAction()).orElse(null)));
                if (cancel) {
                    control.stop(ActiveRuns.StopReason.CANCELLED);
                    return;
                }
            } catch (InterruptedException e) {
                return;
            } catch (WorkerClient.LeaseExpiredException e) {
                // The team machine gave up on this run; stopping the agent frees the computer, the worktree stays.
                Log.warn("worker.lease_lost", "task", job.taskId(), "run", job.seq(), "detail", e.getMessage());
                control.stop(ActiveRuns.StopReason.INTERRUPTED);
                return;
            } catch (RuntimeException e) {
                Log.warn("worker.progress_failed", "task", job.taskId(), "error", e.getMessage());
            }
        }
    }

    private void report(Job job, JobResult result) {
        try {
            client.result(job.taskId(), job.seq(), result);
        } catch (WorkerClient.LeaseExpiredException e) {
            Log.warn("worker.result_refused", "task", job.taskId(), "run", job.seq(), "detail", e.getMessage());
        } catch (RuntimeException e) {
            Log.error("worker.result_not_sent", e, "task", job.taskId(), "run", job.seq());
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

Make `SecretsFile.read(Path)` and `SecretsFile.write(Path, Map)` public (they are package-private today and
`dispatch.worker` needs them for `worker.env`); their javadoc already says what they do, so only `static` gains `public`.

Create `src/main/java/dispatch/worker/WorkerCommand.java` with `pair` and `run`:

```java
package dispatch.worker;

import dispatch.Log;
import dispatch.OwnerOnly;
import dispatch.Redactor;
import dispatch.agent.Agent;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.cli.Cli;
import dispatch.cli.CliException;
import dispatch.cli.SecretsFile;
import dispatch.config.ConfigException;
import dispatch.core.ActiveRuns;
import dispatch.workspace.Delivery;
import dispatch.workspace.Gh;
import dispatch.workspace.Git;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** dispatch worker pair and dispatch worker run. */
public final class WorkerCommand {

    /** The key's name in worker.env, which only its owner may read (ADR 0009). */
    public static final String KEY_VARIABLE = "DISPATCH_WORKER_KEY";

    private final PrintStream out;

    public WorkerCommand(PrintStream out) {
        this.out = out;
    }

    /** Exchanges the code for a key, writes worker.env owner-only and starts worker.yaml if there is none yet. */
    public int pair(Cli.WorkerPair options) {
        WorkerClient.Paired paired;
        try {
            paired = WorkerClient.pair(HttpClient.newHttpClient(), URI.create(options.url()), options.code(), options.name());
        } catch (RuntimeException e) {
            out.println("pairing failed: " + e.getMessage());
            return 1;
        }
        Path env = SecretsFile.beside(options.workerFile());
        try {
            Map<String, String> values = new LinkedHashMap<>(
                    Files.exists(env) ? SecretsFile.read(env) : Map.of());
            values.put(KEY_VARIABLE, paired.key());
            SecretsFile.write(env, values);
            if (!Files.exists(options.workerFile())) {
                OwnerOnly.createFile(options.workerFile());
                Files.writeString(options.workerFile(), """
                        team: %s
                        name: %s
                        maxConcurrentRuns: 1
                        # projects:
                        #   crm:
                        #     path: /absolute/path/to/your/clone
                        """.formatted(options.url(), options.name()));
            }
        } catch (IOException e) {
            out.println("cannot write " + env + ": " + e.getMessage());
            return 1;
        }
        out.println("Paired with " + paired.team() + " as " + options.name() + " (#" + paired.workerId() + ").");
        out.println("Add your clones to " + options.workerFile() + ", then run: dispatch worker run");
        return 0;
    }

    public int run(Cli.WorkerRun options, Map<String, String> environment) throws InterruptedException {
        WorkerConfig config;
        String key;
        try {
            config = WorkerConfigLoader.load(options.workerFile());
            Map<String, String> secrets = SecretsFile.environment(options.workerFile(), environment);
            key = secrets.get(KEY_VARIABLE);
            if (key == null) {
                throw new CliException("no worker key in " + SecretsFile.beside(options.workerFile())
                        + "; run: dispatch worker pair <url> <code>");
            }
            environment = secrets;
        } catch (ConfigException | CliException e) {
            out.println(e.getMessage());
            return 2;
        }
        Log.useRedactor(Redactor.fromEnvironment(environment));
        WorkerClient client = new WorkerClient(HttpClient.newHttpClient(), URI.create(config.team()), key);
        WorkerClient.Setup setup;
        try {
            setup = client.setup();
        } catch (WorkerClient.RevokedException e) {
            out.println("paired key revoked: pair again with a new code from /worker");
            return 1;
        } catch (RuntimeException e) {
            out.println("cannot reach " + config.team() + ": " + e.getMessage());
            return 1;
        }
        Git git = new Git("git", environment.get("GH_TOKEN"), Duration.ofMinutes(5));
        Workspaces workspaces = new Workspaces(config.stateDir(), git);
        workspaces.createDirectories().ifPresent(warning -> Log.warn("state.permissions_too_open", "detail", warning));
        Delivery delivery = new Delivery(git, new Gh(config.ghCommand(), environment.get("GH_TOKEN"), Duration.ofMinutes(2)),
                setup.authorName(), setup.authorEmail());
        Map<String, Agent> agents = Map.of("claude-code",
                new ClaudeCodeAgent(config.claudeCommand(), environment, Duration.ofSeconds(10)));
        WorkerLoop loop = new WorkerLoop(config, client, agents, workspaces, delivery,
                Redactor.fromEnvironment(environment), new ActiveRuns());
        Runtime.getRuntime().addShutdownHook(new Thread(loop::stop, "dispatch-worker-shutdown"));
        out.println("Running " + setup.team() + " tasks on this computer as " + config.name() + ". Ctrl+C to stop.");
        loop.run();
        return 0;
    }
}
```

In `src/main/java/dispatch/cli/Locations.java` add:

```java
    /** The member's own worker settings, beside the config file. */
    public Path workerFile() {
        return configFile.resolveSibling("worker.yaml");
    }
```

In `src/main/java/dispatch/cli/Cli.java` add the two invocations, the parsing and the usage lines:

```java
    public sealed interface Invocation permits Run, Init, Check, ProjectAdd, Service, Ui, WorkerPair, WorkerRun, Help {
    }

    /** @param name what the member's /worker list calls this computer; this machine's host name when not given */
    public record WorkerPair(Path workerFile, String url, String code, String name) implements Invocation {
    }

    public record WorkerRun(Path workerFile) implements Invocation {
    }
```

```java
            case "worker" -> {
                if (arguments.positional().isEmpty()) {
                    throw new CliException("worker needs one of: pair, run");
                }
                yield switch (arguments.positional().getFirst()) {
                    case "pair" -> {
                        if (arguments.positional().size() < 3) {
                            throw new CliException("worker pair needs the team URL and the code from /worker");
                        }
                        arguments.allow(3, Set.of("config", "name"));
                        yield new WorkerPair(arguments.workerFile(defaults), arguments.positional().get(1),
                                arguments.positional().get(2), arguments.values().getOrDefault("name", hostName()));
                    }
                    case "run" -> {
                        arguments.allow(1, Set.of("config"));
                        yield new WorkerRun(arguments.workerFile(defaults));
                    }
                    default -> throw new CliException("unknown command 'worker " + arguments.positional().getFirst() + "'");
                };
            }
```

```java
    /** A name the member will recognise in /worker; anything the machine cannot tell us becomes "worker". */
    private static String hostName() {
        try {
            String host = java.net.InetAddress.getLocalHost().getHostName().replaceAll("[^A-Za-z0-9._-]", "-");
            return host.isBlank() ? "worker" : host.substring(0, Math.min(40, host.length()));
        } catch (java.net.UnknownHostException e) {
            return "worker";
        }
    }
```

and in `Arguments`:

```java
        Path workerFile(Locations defaults) {
            return values.containsKey("config") ? Path.of(values.get("config")) : defaults.workerFile();
        }
```

with the usage text gaining, after `ui`:

```
                  worker pair URL CODE [--name NAME]
                           connect this computer to a team; CODE comes from /worker in the bot
                  worker run
                           run your own tasks on this computer
```

In `src/main/java/dispatch/Main.java` add the two cases:

```java
            case Cli.WorkerPair pair -> System.exit(new WorkerCommand(System.out).pair(pair));
            case Cli.WorkerRun worker -> System.exit(new WorkerCommand(System.out).run(worker, System.getenv()));
```

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -B test -Dtest='WorkerConfigLoaderTest+WorkerLoopTest+CliTest'`
Expected: PASS (3 + 4 + 2 new tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/worker/WorkerConfig.java src/main/java/dispatch/worker/WorkerConfigLoader.java \
  src/main/java/dispatch/worker/WorkerClient.java src/main/java/dispatch/worker/LocalAgents.java \
  src/main/java/dispatch/worker/WorkerLoop.java src/main/java/dispatch/worker/WorkerCommand.java \
  src/main/java/dispatch/cli/Cli.java src/main/java/dispatch/cli/Locations.java \
  src/main/java/dispatch/cli/SecretsFile.java src/main/java/dispatch/Main.java \
  src/test/java/dispatch/worker/WorkerConfigLoaderTest.java src/test/java/dispatch/worker/WorkerLoopTest.java \
  src/test/java/dispatch/worker/WorkerApiFixture.java src/test/java/dispatch/cli/CliTest.java
git commit -m "Run a member's own tasks on their computer with dispatch worker run

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 10: Two members, two computers, end to end

**Files:**
- Create: `src/test/java/dispatch/TeamWorkersTest.java`
- Modify: whatever the run shows is wrong (no new production behaviour is planned here)

**Interfaces:**
- Consumes: `App.start(...)`, `App.workerPort()`, `FakeTelegram`, `FakeClaude`, `FakeGh`, `GitFixture`, `WorkerClient`, `WorkerLoop`, `WorkerConfig`.
- Produces: no production code; the milestone's "done when" evidence.

- [ ] **Step 1: Write the failing test** — create `src/test/java/dispatch/TeamWorkersTest.java`:

```java
package dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.agent.Agent;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.telegram.BotApi;
import dispatch.testing.FakeClaude;
import dispatch.testing.FakeGh;
import dispatch.testing.FakeTelegram;
import dispatch.testing.GitFixture;
import dispatch.testing.SqlRows;
import dispatch.worker.WorkerClient;
import dispatch.worker.WorkerConfig;
import dispatch.worker.WorkerLoop;
import dispatch.workspace.Delivery;
import dispatch.workspace.Gh;
import dispatch.workspace.Git;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A team of two, each with their own computer: the bot, the queue and the store on the team machine, the agents, clones
 * and environments on the members' own machines, and only headlines between them (spec: Testing, end to end).
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake claude and gh CLIs are POSIX shell scripts")
class TeamWorkersTest {

    private static final long GROUP = -1001234567890L;
    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path dir;

    private final List<Throwable> fatalErrors = new CopyOnWriteArrayList<>();
    private final List<WorkerLoop> loops = new ArrayList<>();
    private final List<Thread> loopThreads = new ArrayList<>();
    private FakeTelegram telegram;
    private GitFixture repos;
    private Path claude;
    private Path gh;
    private App app;

    @BeforeEach
    void setUp() throws IOException {
        telegram = FakeTelegram.start();
        repos = GitFixture.create(dir, "alm");
        claude = FakeClaude.install(Files.createDirectories(dir.resolve("bin")));
        gh = FakeGh.install(dir.resolve("bin"));
        app = App.start(teamConfig(), (group, member) -> {
            throw new AssertionError("no one joins in this test");
        }, new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(60)), FakeClaude.environment(),
                Clock.systemUTC(), fatalErrors::add);
    }

    @AfterEach
    void tearDown() {
        loops.forEach(WorkerLoop::stop);
        loopThreads.forEach(thread -> thread.interrupt());
        if (app != null) {
            app.stop();
        }
        telegram.close();
    }

    @Test
    void eachMembersTaskRunsOnTheirOwnComputerAndTheOtherSeesOnlyTheHeadline() throws Exception {
        startWorker(100, "Bold", "bold-laptop", "BOLD-TOKEN");
        startWorker(200, "Ali", "ali-laptop", "ALI-TOKEN");

        giveTask(10, 100, "Bold", "Fix the login timeout on staging");
        giveTask(20, 200, "Ali", "Fix the signup timeout on staging");

        JsonNode boldsPlan = awaitMessageTo(100, "The user reports that login");
        JsonNode alisPlan = awaitMessageTo(200, "The user reports that login");
        assertTrue(boldsPlan.get("text").asText().contains("#1"), boldsPlan.toString());
        assertTrue(alisPlan.get("text").asText().contains("#2"), alisPlan.toString());

        assertTrue(Files.exists(worktree("bold-laptop", 1)), "Bold's task ran in Bold's own state directory");
        assertTrue(Files.exists(worktree("ali-laptop", 2)), "Ali's task ran in Ali's own state directory");
        assertFalse(Files.exists(worktree("bold-laptop", 2)));
        assertFalse(Files.exists(worktree("ali-laptop", 1)));
        assertFalse(Files.exists(repos.stateDir.resolve("worktrees")),
                "the team machine runs no agent and makes no worktree");

        assertTrue(Files.readString(worktree("bold-laptop", 1).resolve("fake-claude.env")).contains("BOLD-TOKEN"),
                "the agent ran with Bold's own environment");
        assertTrue(Files.readString(worktree("ali-laptop", 2).resolve("fake-claude.env")).contains("ALI-TOKEN"));
        assertFalse(Files.readString(worktree("ali-laptop", 2).resolve("fake-claude.env")).contains("BOLD-TOKEN"),
                "neither token ever reaches the other member's computer");

        Path db = repos.stateDir.resolve("dispatch.db");
        assertEquals("0", SqlRows.single(db, "SELECT count(*) AS n FROM run WHERE pid IS NOT NULL").get("n"),
                "a remote run records no process on the team machine");
        assertEquals("2", SqlRows.single(db, "SELECT count(DISTINCT worker_id) AS n FROM task").get("n"),
                "each task belongs to its requester's computer");

        telegram.pushUpdate(privateCommand(30, 200, "Ali", "/status"));
        JsonNode status = awaitMessageTo(200, "#1");
        assertFalse(status.get("text").asText().contains("Fix the login timeout on staging")
                && status.get("text").asText().contains("Bash:"), "Ali sees Bold's headline, not his agent's actions");
        assertTrue(fatalErrors.isEmpty(), fatalErrors.toString());
    }

    @Test
    void aTaskGivenWhileTheComputerIsOffSaysSoAndStartsWhenItConnects() throws Exception {
        giveTask(10, 100, "Bold", "Fix the login timeout on staging");

        Path db = repos.stateDir.resolve("dispatch.db");
        awaitRow(db, "SELECT count(*) AS n FROM outbox WHERE kind = 'WORKER_WAITING' AND task_id = 1", "1");
        assertEquals("QUEUED", SqlRows.single(db, "SELECT status FROM run WHERE task_id = 1").get("status"),
                "nothing claims a run whose computer is not there");

        startWorker(100, "Bold", "bold-laptop", "BOLD-TOKEN");

        awaitMessageTo(100, "The user reports that login");
        assertTrue(Files.exists(worktree("bold-laptop", 1)));
        assertTrue(fatalErrors.isEmpty(), fatalErrors.toString());
    }

    private void startWorker(long memberId, String memberName, String name, String token) throws Exception {
        telegram.pushUpdate(privateCommand(memberId * 10 + 1, memberId, memberName, "/worker"));
        String code = codeFrom(awaitMessageTo(memberId, "dispatch worker pair"));
        URI team = URI.create("http://127.0.0.1:" + app.workerPort());
        WorkerClient.Paired paired = WorkerClient.pair(HttpClient.newHttpClient(), team, code, name);
        WorkerClient client = new WorkerClient(HttpClient.newHttpClient(), team, paired.key());
        WorkerClient.Setup setup = client.setup();

        Path stateDir = Files.createDirectories(dir.resolve("workers").resolve(name));
        Map<String, String> environment = new HashMap<>(FakeClaude.environment());
        environment.put("MEMBER_TOKEN", token);
        Git git = new Git("git", null, Duration.ofSeconds(30));
        Workspaces workspaces = new Workspaces(stateDir, git);
        workspaces.createDirectories();
        Files.createDirectories(stateDir.resolve("repos"));
        GitFixture.sh(dir, "git", "clone", "--quiet", repos.origin.toString(),
                stateDir.resolve("repos").resolve("alm").toString());
        WorkerConfig config = new WorkerConfig(team.toString(), name, 1, claude.toString(), gh.toString(), stateDir,
                Map.of("alm", new WorkerConfig.Project(stateDir.resolve("repos").resolve("alm").toString(), null, null)));
        Map<String, Agent> agents = Map.of("claude-code",
                new ClaudeCodeAgent(claude.toString(), environment, Duration.ofSeconds(10)));
        Delivery delivery = new Delivery(git, new Gh(gh.toString(), null, Duration.ofSeconds(30)), setup.authorName(),
                setup.authorEmail());
        WorkerLoop loop = new WorkerLoop(config, client, agents, workspaces, delivery, Redactor.patternsOnly(),
                new ActiveRuns());
        loops.add(loop);
        loopThreads.add(Thread.ofVirtual().name("worker-" + name).start(loop));
    }

    private Path worktree(String worker, long taskId) {
        return dir.resolve("workers").resolve(worker).resolve("worktrees").resolve(String.valueOf(taskId));
    }

    /** Waits for a single-column query to answer {@code expected}; the store is written by other threads. */
    private static void awaitRow(Path db, String sql, String expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            if (expected.equals(SqlRows.single(db, sql).values().iterator().next())) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(sql + " never answered " + expected);
    }

    private static String codeFrom(JsonNode message) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("dispatch worker pair \\S+ ([A-Z2-9]{8})").matcher(message.get("text").asText());
        if (!matcher.find()) {
            throw new AssertionError("no pairing code in " + message.get("text").asText());
        }
        return matcher.group(1);
    }

    private void giveTask(long updateId, long memberId, String name, String text) throws InterruptedException {
        telegram.pushUpdate(privateText(updateId, memberId, name, text));
        long prompt = awaitSentMessageId("DRAFT_PROMPT", updateId);
        telegram.pushUpdate(privateCallback(updateId + 1, memberId, name, "draft:" + draftId(updateId) + ":prio:NORMAL",
                prompt));
    }

    private Config teamConfig() {
        return new Config("backend", repos.stateDir,
                new Config.Telegram(List.of(), List.of(new Config.Group("backend", GROUP,
                        List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm")))),
                new Config.Scheduler(2), Config.Worktrees.DEFAULT,
                new Config.Limits(new Config.RunLimits(Duration.ofSeconds(60), new BigDecimal("2")),
                        new Config.RunLimits(Duration.ofSeconds(60), new BigDecimal("10"))),
                Map.of("claude-code", new Config.Agent(claude.toString())),
                List.of(new Config.Project("alm", null, repos.origin.toString(), null, "main", "claude-code", null, null,
                        List.of(), null, null, null)),
                new Config.Delivery("Dispatch (backend)", "dispatch-backend@example.com", gh.toString()),
                new Config.Workers("http://127.0.0.1:0", 0), new Config.Secrets(FakeTelegram.TOKEN, null));
    }
}
```

Copy `awaitMessageTo(chatId, fragment)`, `awaitSentMessageId`, `draftId`, `privateText`, `privateCommand` and
`privateCallback` from `AppTest` (a private message from a named member, the draft's id read from the outbox), keeping
them in this file: `AppTest` stays a personal-mode test and neither file should start sharing a base class for this.
The fake claude writes its whole environment to `fake-claude.env`, which is how each worker's own environment is checked.
`teamConfig()`'s `publicUrl` is `http://127.0.0.1:0` and the server takes any free port, which the Host allowlist covers
(`127.0.0.1:<real port>`) and the pairing text quotes verbatim, so the regex above finds the code either way.

- [ ] **Step 2: Run it to see what the whole path does**

Run: `./mvnw -q -B test -Dtest=TeamWorkersTest`
Expected: this is the first time bot, coordinator, HTTP and two worker loops run together, so treat the first run as the
finding, not as a verdict. It fails until every earlier task is in place; with them in place it may pass at once, which is
this task's evidence. The rule for whatever it does report: fix production code, never an assertion about where a task ran
or what a member sees.

- [ ] **Step 3: Fix what the run found.** No new behaviour is planned here. The likely findings and what each means:

1. A timeout waiting for a plan message: the scheduler is not claiming, because no worker reported within
   `Workers.SEEN_WITHIN` — check that `/api/worker/next` touches `last_seen_at` through `WorkerKeys.authenticate`, and
   that `App` passed `SEEN_WITHIN` to the `Scheduler`.
2. A task running on the wrong computer: `RemoteWorkers.matches` is ignoring `memberRef` or `task.worker_id`; fix
   `matches` or `take`, never the test.
3. `fake-claude.env` missing the marker: the worker built its `ClaudeCodeAgent` with the wrong environment map.
4. `fatalErrors` non-empty on shutdown: `App.stop()` closes the `WorkerApi` before `activeRuns.awaitIdle(...)`; it must
   close after, so a worker reporting during shutdown still gets through.

Make the smallest change each finding actually needs, and re-run.

- [ ] **Step 4: Run the whole suite**

Run: `./mvnw -q -B verify`
Expected: PASS, including `TeamWorkersTest`, `AppTest` (personal mode, unchanged), `RunExecutorTest`, `RecoveryTest`,
`SchedulerTest` and every `dispatch.worker` test.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/dispatch/TeamWorkersTest.java
git commit -m "Prove two members' tasks run on their own computers and stay private

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

## What W-3 does not do

`dispatch worker init`, the worker setup in `dispatch ui`, the `dispatch-worker` background service, `dispatch check`'s
worker coverage, and README, SECURITY.md, ARCHITECTURE.md and ADR 0021 are W-4. The only documentation this milestone
touches is the spec's Protocol line about `/api/worker/next` (Task 7), which the code there proves.
