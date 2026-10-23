# Data Reports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A request for numbers from a project's prod data is recognized as a report. Its plan explains the query, and ZB approves it. A read-only run on prod then returns one self-contained HTML report.

**Architecture:**
- A project's optional `data` block names an SSH host, a Mongo address and a read-only URI.
- For PLAN and REPORT runs on such a project, `JobRunner` opens an `ssh -L` tunnel. `ClaudeCodeAgent` then loads a pinned `mongodb-mcp-server` named `dispatch_data`, pointed at the tunnel, at one of two levels:
  - SCHEMA for plan runs. Only 7 metadata tools; this enforces the gate.
  - FULL for report runs.
- `StreamParser` fails a run in three cases:
  - its data tools aren't the expected set;
  - the data server didn't connect;
  - a plan run that touched data tries to answer directly.
- The plan schema's `result` gains `report`. Approving one queues a new `REPORT` run kind, which ends the way an answered task does: `ANSWER_READY` with an `.html` document.

**Tech Stack:**
- Java 21 and Maven.
- SQLite migrations.
- Claude Code 2.1.309 headless (`--mcp-config` inline, `--strict-mcp-config`).
- `mongodb-mcp-server@2.1.2` through `npx`.
- OpenSSH client, and `mongosh` for `dispatch check`.

**Spec:** `docs/superpowers/specs/2026-10-23-data-reports-design.md`

## Probe results (2026-10-23, Claude Code 2.1.309, mongodb-mcp-server 2.1.2, mongo:7.0)

These were run against a throwaway container before this plan was written. They settle the spec's "probe first" question: **MCP, not a wrapper.**

1. MCP tools can be called in `--permission-mode plan --permission-prompts none --tools Read,Bash`: `list-collections`, `count` and `aggregate` all returned results.
2. `MDB_MCP_DISABLED_TOOLS` removes tools from the init event's `tools` list. Disabling only `aggregate` is **not** enough, because `export` and `explain` can still run a pipeline and `find` returns raw values. The SCHEMA set below was verified to leave exactly 7 tools. `collection-schema` returns field types only, with no values.
3. `MDB_MCP_MAX_TIME_MS` is passed as `maxTimeMS` to `find`, `count` and the read path of `aggregate`.
4. **Default `maxDocumentsPerQuery` is 100.** A report run needs more rows (daily counts over 3 years is about 1,100), so FULL sets it to 5000.
5. On a cold npx cache, the pinned server's first download outlasted Claude Code's 30 s MCP connect timeout. Runs set `MCP_TIMEOUT=120000`, and `dispatch check` warms the cache.
6. `MDB_MCP_READ_ONLY=true` removed every write tool; no insert or update tool was listed.

## Global Constraints

- **Server:** pinned to `npx -y mongodb-mcp-server@2.1.2`, named `dispatch_data`, so its tools are `mcp__dispatch_data__<tool>`.
- **SCHEMA tools allowed, exactly:** `list-databases`, `list-collections`, `collection-indexes`, `collection-schema`, `collection-storage-size`, `count`, `db-stats`.
- **SCHEMA `MDB_MCP_DISABLED_TOOLS`:** `aggregate,aggregate-db,export,explain,find,connect,disconnect,list-connections,mongodb-logs,atlas-local-connect-deployment,atlas-local-list-deployments,search-knowledge,list-knowledge-sources`
- **FULL tools allowed:** the SCHEMA set plus `find`, `aggregate`, `explain`.
- **FULL `MDB_MCP_DISABLED_TOOLS`:** `aggregate-db,export,connect,disconnect,list-connections,mongodb-logs,atlas-local-connect-deployment,atlas-local-list-deployments,search-knowledge,list-knowledge-sources`
- **Both levels:**
  - `MDB_MCP_READ_ONLY=true`
  - `MDB_MCP_MAX_TIME_MS=60000`
  - `MDB_MCP_TELEMETRY=disabled`
  - the connection string as `MDB_MCP_CONNECTION_STRING=${DISPATCH_DATA_URI}`, with the value in the run environment as `DISPATCH_DATA_URI`, never on the command line
- **FULL only:** `MDB_MCP_MAX_DOCUMENTS_PER_QUERY=5000`.
- **Run environment, whenever a data server is loaded:** `MCP_TIMEOUT=120000`.
- **Tunnel:** `ssh -N -o BatchMode=yes -o ExitOnForwardFailure=yes -o ConnectTimeout=15 -L 127.0.0.1:<port>:<mongo> <ssh>`. Ready when `127.0.0.1:<port>` accepts a TCP connection. Give up after 20 s.
- **URI:** the configured URI's host list is replaced by `127.0.0.1:<port>`, and `readPreference=secondaryPreferred` is added unless already present. `mongodb+srv://` is refused at config load, because SRV can't go through one forwarded port.
- **Where `data` is allowed:** only on a personal instance (`!config.isTeam()`), only for projects whose agent is `claude-code`, and not on Windows.
- **Telegram text:** new text is Mongolian, in `messages_mn.properties`. English text goes in `texts_en.properties` only where an existing key has an English twin.
- **Public repo:** tests and docs use made-up names (`webapp`, `visits`). No client names, hosts or data. Fake URIs are split across string concatenation (`"mongodb://ro:" + "pw@h/db"`) so secret scanners stay quiet.
- **Numbering:** ADR 0043; migration `038-report-run.sql`.
- **Commits:** sentence-style subjects like the existing history. Every commit ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01Hc1SC65FjYWUVhrj6y4hgP`.
- **Verify command:** `./mvnw -q verify`. Expect BUILD SUCCESS. The live test is opt-in through `DISPATCH_LIVE_DATA=1`.

## Review Focus

1. **SSH asks for something** (a password, host key or passphrase). BatchMode makes ssh fail instead of waiting, and the run fails naming the SSH host, with no hang. Test: Task 2 `aTunnelWhoseSshNeverForwardsFailsAfterTheWaitAndIsKilled`.
2. **Two report tasks at once.** Each gets its own port and tunnel, and closing one leaves the other working. Test: Task 2 `twoTunnelsGetTwoPorts`.
3. **Cancelling or timing out a plan or report run.** The tunnel is closed, and no `ssh` is left running. Test: Task 6 `aCancelledReportRunClosesItsTunnel`.
4. **Retrying a failed REPORT run.** `TaskCommands.retry` must queue a REPORT run again. Its `else` branch would make it EXECUTE, and that would build code from a report plan. Test: Task 5 `aFailedReportIsRetriedAsAReport`.
5. **A `report` result on a project with no `data`** (agent drift, or a follow-up after the block was removed). It's rejected as an invalid plan, not turned into a REPORT run that has no data. Test: Task 4 `aReportForAProjectWithoutDataIsAnInvalidPlan`.

---

### Task 1: Config: a project's `data` block

**Files:**
- Create: `src/main/java/dispatch/config/DataSource.java`
- Modify:
  - `src/main/java/dispatch/config/Config.java` (`Project` record: new last component `DataSource data`, plus a 16-arg constructor keeping today's callers)
  - `src/main/java/dispatch/config/ConfigLoader.java` (`validateProjects`: validate and resolve `data`)
  - `src/main/resources/texts_en.properties`, `texts_mn.properties` (config error texts, following `config.*` keys)
- Test:
  - `src/test/java/dispatch/config/DataSourceTest.java`
  - `src/test/java/dispatch/config/ConfigLoaderTest.java`

**Interfaces:**
- Produces:
  - `public record DataSource(String ssh, String mongo, String database, String uri)`. `uri` is the resolved secret value, never `${…}`. `toString()` masks `uri` as `[redacted]`.
  - `public String connectionString(int localPort)`: the URI with its hosts replaced by `127.0.0.1:<localPort>`. It keeps the path (`/database` from the URI, or `/` + `database` when the URI has none) and the query, and appends `readPreference=secondaryPreferred` when no `readPreference` is present.
  - `Config.Project#data()`, which is null when the project has none.

- [ ] **Step 1: Write the failing tests**

`DataSourceTest`:
- `rewritesHostsAndKeepsCredentialsAndQuery`:
  - input: user `ro` with an encoded `@` in its password, hosts `db1:27017,db2:27017`, path `/webapp`, query
    `authSource=admin`; port `40001`
  - expected: the same credentials, host `127.0.0.1:40001`, path `/webapp`, query
    `authSource=admin&readPreference=secondaryPreferred`
- `addsTheDatabaseWhenTheUriHasNoPath`:
  - input: user `ro`, host `db1`, no path, with database `webapp`
  - expected: the same credentials, host `127.0.0.1:40001`, path `/webapp`, query `readPreference=secondaryPreferred`
- `keepsAGivenReadPreference`: input `…/webapp?readPreference=primary`, which is kept unchanged.
- `toStringNeverShowsTheUri`: `assertFalse(source.toString().contains("pw"))`.

`ConfigLoaderTest`. The YAML carries `data: {ssh: webapp-prod, mongo: "127.0.0.1:27017", database: webapp, uri: "${WEBAPP_RO_URI}"}`, and env holds `WEBAPP_RO_URI`.
- `aDataBlockResolvesItsUriFromTheEnvironment`: `project.data().uri()` equals the env value.
- `aDataUriNamingAMissingVariableIsRefused`: a load error that names `WEBAPP_RO_URI`.
- `aLiteralDataUriIsRefused` (`uri: "mongodb://…"`): only `${NAME}` is accepted, so a secret never sits in the YAML.
- `anSrvDataUriIsRefused`
- `aDataBlockOnATeamInstanceIsRefused`
- `aDataBlockOnACodexProjectIsRefused`
- `aDataBlockMissingSshMongoOrDatabaseIsRefused`

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest='DataSourceTest,ConfigLoaderTest'`
Expected: compile failure, because `DataSource` doesn't exist.

- [ ] **Step 3: Implement**

- Add `DataSource` (Jackson reads the four keys), `Project.data`, and validation in `ConfigLoader.validateProjects`.
- The team check needs the telegram config, which `validateProjects` doesn't see today. Pass `Config.isTeam(raw.telegram())` in as a boolean.
- The Windows check uses `System.getProperty("os.name")` containing `Windows`.
- Resolve `${NAME}` (pattern `\$\{([A-Z0-9_]+)\}`, whole value only) from the `env` map that `load` already receives.

- [ ] **Step 4: Run them to see them pass**

Run: the same command. Expected: PASS.

- [ ] **Step 5: Commit**

`git commit -m "A project can name its prod Mongo for reports: an SSH host, the address behind it and a read-only URI from dispatch.env"`

---

### Task 2: The tunnel

**Files:**
- Create: `src/main/java/dispatch/data/Tunnel.java`
- Test: `src/test/java/dispatch/data/TunnelTest.java`, with `@EnabledOnOs({OS.LINUX, OS.MAC})` and fake ssh scripts written into `@TempDir`

**Interfaces:**
- Consumes: `DataSource` (Task 1)
- Produces:
  - `public final class Tunnel implements AutoCloseable`
  - `public static Tunnel open(List<String> ssh, DataSource source, Duration wait)`. `ssh` is the client command and its fixed options, normally `List.of("ssh")`; the live test passes `List.of("ssh", "-F", <config>)`. It throws `TunnelException` (a new `RuntimeException`) whose message names `source.ssh()` and the last stderr line
  - `public int port()`
  - `public void close()`, which destroys ssh and its descendants and waits up to 5 s
  - `static List<String> command(List<String> ssh, int port, DataSource source)`, package-private for tests

- [ ] **Step 1: Write the failing tests**

- `theCommandIsBatchModeWithExitOnForwardFailure`: `command(List.of("ssh"), 40001, source)` equals the Global Constraints command, with `source.ssh()` last.
- `aTunnelOpensOnceItsPortAccepts`: a fake ssh script parses `-L 127.0.0.1:PORT:…` and execs `python3 -m http.server PORT --bind 127.0.0.1`. Then `open` returns, `new Socket("127.0.0.1", t.port())` connects, and after `close()` the connection is refused.
- `aTunnelWhoseSshExitsFailsWithItsError`: the fake prints `Permission denied (publickey).` to stderr and exits 255. Expect a `TunnelException` whose message contains `webapp-prod` and `Permission denied`.
- `aTunnelWhoseSshNeverForwardsFailsAfterTheWaitAndIsKilled`: the fake sleeps 60 s and `wait` is 1 s. Expect a `TunnelException`, and the script's process is no longer alive (get its pid with `ProcessHandle.descendants` before the wait ends, or have the script write `$$` to a file).
- `twoTunnelsGetTwoPorts`: two opens give different ports, and closing the first leaves the second accepting.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest=TunnelTest`. Expected: compile failure.

- [ ] **Step 3: Implement `Tunnel`**

- Get a free port with `new ServerSocket(0)`, then close it and use the port. (`ponytail:` a port taken between close and ssh binding makes ssh exit through ExitOnForwardFailure, so the run fails clearly; retry when it's seen.)
- Poll a connect every 200 ms until it succeeds, the process exits, or `wait` passes.
- Keep the last stderr lines with a reader thread. Don't log the command's URI; the URI is never on the ssh command anyway.

- [ ] **Step 4: Run them to see them pass**

Run: `./mvnw -q test -Dtest=TunnelTest`. Expected: PASS.

- [ ] **Step 5: Commit**

`git commit -m "Dispatch opens an SSH tunnel to a project's Mongo for one run and closes it after"`

---

### Task 3: The agent loads the data server at a level, and enforces it

**Files:**
- Create: `src/main/java/dispatch/agent/DataAccess.java`
- Modify:
  - `src/main/java/dispatch/agent/RunRequest.java` (new last component `DataAccess data`, null for none; `loading`, `as` and the existing short constructors pass or default it)
  - `src/main/java/dispatch/agent/claude/Loadout.java` (`of(...)` adds the `dispatch_data` server and its environment)
  - `src/main/java/dispatch/agent/claude/ClaudeCodeAgent.java` (passes `request.data()` to `StreamParser`)
  - `src/main/java/dispatch/agent/claude/StreamParser.java`
- Test:
  - `LoadoutTest`
  - `StreamParserTest`
  - `ClaudeCodeAgentTest` (all in `src/test/java/dispatch/agent/claude/`)

**Interfaces:**
- Produces:
  - `public record DataAccess(Level level, String connectionString)`, with `enum Level { SCHEMA, FULL }`. `toString()` masks `connectionString`.
  - `DataAccess.SERVER = "dispatch_data"` and `DataAccess.TOOL_PREFIX = "mcp__dispatch_data__"`.
  - `Set<String> allowedTools()`, `String disabledTools()` and `Map<String, String> serverEnvironment()` per level. The values are in Global Constraints.
- Consumes: `OwnerPlugins.Resolved.mcpConfig()`, which may be null, as a JSON string `{"mcpServers":{…}}`.

- [ ] **Step 1: Write the failing tests**

`LoadoutTest`:
- `aDataRunAddsTheDataServerBesideTheOwners`: an owner config with one server, plus `DataAccess(SCHEMA, "mongodb://…")`. The resulting `mcpConfig` has both servers. `dispatch_data.command` is `npx` and its args are `["-y","mongodb-mcp-server@2.1.2"]`. Its env contains `MDB_MCP_CONNECTION_STRING = "${DISPATCH_DATA_URI}"` and the SCHEMA disabled list. `environment()` contains `DISPATCH_DATA_URI` (the URI) and `MCP_TIMEOUT=120000`.
- `theUriNeverAppearsInTheMcpConfig`: `assertFalse(loadout.mcpConfig().contains("mongodb://"))`.
- `aFullRunRaisesTheDocumentCap`: FULL env has `MDB_MCP_MAX_DOCUMENTS_PER_QUERY=5000`, and its disabled list lacks `aggregate` and `find`.
- `aRunWithoutDataIsUnchanged`: `data == null` gives the same `mcpConfig` as today.

`StreamParserTest` (a parser built with a data level):
- `aDataServerWithAnUnexpectedToolStopsTheRun`: an init event listing `mcp__dispatch_data__aggregate` at SCHEMA. Then `stopReason()` is `"data_tools_unexpected"`, and `result(...)` is FAILED with an error naming `aggregate`.
- `aDataServerThatFailedToConnectFailsTheRun`: init has `mcp_servers: [{name: dispatch_data, status: failed}]`. The result is FAILED, with an error containing `could not reach the project's data`.
- `aPlanThatTouchedDataCannotAnswer`: a `tool_use` named `mcp__dispatch_data__count`, then a success result whose `structured_output.result` is `"answer"`. The result is FAILED, with an error containing `read the project's data, so it may not answer directly`.
- `aPlanThatTouchedNoDataMayAnswer`: the same without the data `tool_use`, which SUCCEEDS.
- `aReportResultAfterTouchingDataIsFine`: `result: "report"` SUCCEEDS.

`ClaudeCodeAgentTest`:
- `aDataRunPassesTheUriOnlyThroughTheEnvironment`: the command line contains no `mongodb://`, and the process environment has `DISPATCH_DATA_URI`. Use the existing fake-claude harness in that test class.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest='LoadoutTest,StreamParserTest,ClaudeCodeAgentTest'`. Expected: compile failure.

- [ ] **Step 3: Implement**

- `Loadout.of(request, owner, ownerSkills)` parses `owner.mcpConfig()`, or starts with `{"mcpServers":{}}`, and puts `dispatch_data`. It merges `owner.environment()` with the data variables.
- `StreamParser` takes `DataAccess.Level` (null for none) in a new constructor. The existing constructor passes null.
  - **On init:** compare the `tools` starting with `TOOL_PREFIX` against `allowedTools()`. Any extra tool is a stop. A `dispatch_data` server whose status isn't `connected` is a failure.
  - **On a top-level `tool_use`** whose name starts with the prefix: set `dataTouched`.
  - **In `result`:** apply the three rules above, after the existing wrong-mode handling.

- [ ] **Step 4: Run them to see them pass**

Run: the same command. Expected: PASS.

- [ ] **Step 5: Commit**

`git commit -m "A run given a project's data loads a pinned read-only Mongo server whose tools are checked, and a plan that read data cannot answer directly"`

---

### Task 4: The plan's third result, `report`

**Files:**
- Modify:
  - `src/main/java/dispatch/domain/Plan.java` (`Result.REPORT`; `of` requires non-empty `steps` for a report; new `public static boolean endsWithoutCode(String planJson)`, true for ANSWER or REPORT)
  - `src/main/resources/plan-schema.json`: `result.enum` is `["plan","answer","report"]`, and the description adds "report when the task asks for numbers or a report from the project's data and this run was told it can read that data".
  - `src/main/java/dispatch/core/Prompts.java`: add `static final String DATA_NOTE` (text below), plus `report(Task)` and `reportFollowUp(Task, Run)`.
  - `src/main/java/dispatch/core/JobRunner.java`: `request(...)` appends `Prompts.DATA_NOTE` to a PLAN job's prompt when `data != null`. The data itself is wired in Task 6; here, add `static String dataNote(RunKind kind, boolean hasData)` (the note for a PLAN with data, else empty), which Task 6 calls.
  - `src/main/java/dispatch/core/Coordinator.java`:
    - `planJob`: a task whose plan `endsWithoutCode` gets `answerFollowUp` when ANSWER, and `reportFollowUp` when REPORT.
    - `finishPlan`: a REPORT plan for a project whose `data()` is null fails as AGENT with `"plan did not match the schema: result report needs the project's data, which is not configured"`.
  - `src/main/java/dispatch/core/TaskAccess.java:259`, `TaskCommands.java:330`, `TaskService.java:750`: use `Plan.endsWithoutCode`.
- Test:
  - `src/test/java/dispatch/domain/PlanTest.java`
  - `src/test/java/dispatch/core/CoordinatorTest.java` (or the existing test that covers `finishPlan`)
  - `TaskAccessTest`
  - `JobRunnerTest`

**Interfaces:**
- Produces:
  - `Plan.Result.REPORT` (JSON `"report"`)
  - `Plan.endsWithoutCode(String)`
  - `Prompts.DATA_NOTE`, `Prompts.report(Task task)` and `Prompts.reportFollowUp(Task task, Run run)`

`DATA_NOTE`, which is exact copy:

```
This run can read the project's production MongoDB through the dispatch_data tools, but only its shape: collections,
indexes, field types and counts. If the task asks for numbers, statistics or a report drawn from that data, set result
to "report" and do not answer it yourself: in understanding say what is asked in one sentence; in findings say which
collections and fields hold it (and any gap or oddity you already see); in steps explain the query in a few short lines
— collection, filter and date range, grouping, what is counted — so the requester can approve it. The real query runs
only after approval. If the task asks about the code, answer or plan as usual without using the data tools. If it is
unclear whether the task wants numbers or a change, choose plan or answer, never report.
```

`Prompts.report(Task)` tells the resumed planning session the following:
- The requester approved the report plan above.
- Use the `dispatch:data-report` skill.
- Run what was approved, nothing broader. That includes the finer queries the data checks need. Filter early and use indexes.
- Return only `{"html": "<!doctype html>…"}` through the structured output, one complete self-contained document in the task's language.

`reportFollowUp` mirrors `answerFollowUp`. It quotes the previous plan's understanding and steps instead of `answer()`, and ends with `PLAN_FORMAT` + `DATA_NOTE`.

- [ ] **Step 1: Write the failing tests**

`PlanTest`:
- `aReportPlanParses`
- `aReportWithoutStepsIsInvalid`
- `endsWithoutCodeIsTrueForAnswerAndReportOnly`

`CoordinatorTest`:
- `aReportForAProjectWithoutDataIsAnInvalidPlan`: the task fails as AGENT, and the detail contains `needs the project's data`.
- `aReportsFollowUpIsPlannedWithTheReportFollowUpPrompt`

`TaskAccessTest`:
- `aReportedTaskAcceptsAFollowUp`

`JobRunnerTest`:
- `onlyAPlanWithDataGetsTheDataNote`

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest='PlanTest,CoordinatorTest,TaskAccessTest,JobRunnerTest'`. Expected: FAIL or compile failure.

- [ ] **Step 3: Implement** as listed under Files.

- [ ] **Step 4: Run them to see them pass**

Run: the same command. Expected: PASS.

- [ ] **Step 5: Commit**

`git commit -m "A plan can be a report: it explains the query to approve, and only a project with data may return one"`

---

### Task 5: The REPORT run kind, from approval to the HTML answer

**Files:**
- Create:
  - `src/main/resources/db/038-report-run.sql`: rebuild `run` and `run_step` the way `036-usage-limit-reason.sql` and `033-run-step-pause.sql` did, copying their current definitions. `run.kind` adds `'REPORT'` and `run_step.kind` adds `'REPORT'`.
  - `src/main/resources/report-schema.json`: `{"type":"object","required":["html"],"additionalProperties":false,"properties":{"html":{"type":"string","description":"The complete self-contained HTML report"}}}`
- Modify:
  - `src/main/java/dispatch/domain/RunKind.java` (`REPORT`, documented as "Runs an approved report plan's queries read-only and returns the report (spec: data reports)")
  - `RunStep.Kind` (`REPORT`)
  - `src/main/java/dispatch/agent/Schemas.java` (`REPORT`)
  - `TaskCommands.approve`: a task whose plan result is REPORT queues `RunKind.REPORT` (same phase EXECUTING, cause APPROVAL, `OutboxKind.EXECUTION_QUEUED`).
  - `TaskCommands.retry`: when the failed step's kind is REPORT, queue REPORT again.
  - `Coordinator`:
    - `claim` gets `case REPORT -> reportJob(task, run, project)`. It uses session `task.sessionId()`, resume `true`, prompt `Prompts.report(task)`, `planModel()`/`planEffort()` and `executeLimits` (timeout and budget), with no attachments re-sent, no review prompt and no picks.
    - `succeeded` gets `case REPORT -> finishReport(...)`. That parses `structuredOutput` as `{html}`, and if `html` is blank or doesn't start with `<!doctype html` or `<html` (case-insensitive, after `strip()`), it fails as AGENT with `"the report run returned no HTML document"`.
  - `RunTransitions`: new `public void reported(long taskId, int seq, String html, AgentResult result)`. Like `answered`, with `Tasks.completed(tx, id, null, now)` (EXECUTING to COMPLETED) and an `ANSWER_READY` payload of `answer = html`, plus `completedLine(task, null, 0, true)` and `GroupReaction.COMPLETED`.
  - Every other `switch` on `RunKind` that no longer compiles: `REPORT` goes with PLAN where the switch is about read-only runs, and is otherwise an `IllegalStateException` like SPLIT/ASSISTANT.
- Test:
  - `TaskCommandsTest`
  - `CoordinatorTest`
  - `RunTransitionsTest` (or `TaskLifecycleTest`, whichever covers `answered`)
  - `DatabaseTest` (migration applies on a copy of the latest schema)

**Interfaces:**
- Consumes: `Plan.Result.REPORT`, `Prompts.report` (Task 4)
- Produces:
  - `RunKind.REPORT`
  - `RunStep.Kind.REPORT`
  - `Schemas.REPORT`
  - `RunTransitions.reported(long, int, String, AgentResult)`

- [ ] **Step 1: Write the failing tests**

- `approvingAReportPlanQueuesAReportRun`: the queued run kind is REPORT and the phase is EXECUTING.
- `aFailedReportIsRetriedAsAReport`
- `aReportJobResumesThePlanningSession`: `job.sessionId() == task.sessionId()`, `job.resume()` is true, and the prompt contains `dispatch:data-report`.
- `aReportRunsHtmlCompletesTheTaskAsAnAnswer`: the task is COMPLETED, an `ANSWER_READY` outbox payload has `answer` starting with `<!doctype html`, and the group line has `answered: true`.
- `aReportRunWithoutHtmlFails`: `{"html":"just text"}` fails as AGENT.
- The migration test: existing runs survive, and inserting a REPORT run works.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest='TaskCommandsTest,CoordinatorTest,RunTransitionsTest,DatabaseTest'`. Expected: FAIL or compile failure.

- [ ] **Step 3: Implement** as listed under Files.

- [ ] **Step 4: Run them to see them pass**

Run: `./mvnw -q verify`. Expected: BUILD SUCCESS. Every switch is now exhaustive.

- [ ] **Step 5: Commit**

`git commit -m "Approving a report plan runs the report, and its HTML comes back the way an answer does"`

---

### Task 6: JobRunner opens the tunnel and runs PLAN and REPORT with data

**Files:**
- Create: `src/main/java/dispatch/data/DataSources.java`
- Modify:
  - `JobRunner`:
    - a new constructor parameter `DataSources dataSources`; existing constructors pass `DataSources.NONE`
    - `case REPORT -> report(job, events, control, steps)`
    - `plan(...)` and `report(...)` open a tunnel when `dataSources.forProject(job.project().name())` is present
    - `plugins(job)` also returns the skills plugin when the project has data
  - `SandboxPolicies.IN_WORKTREE` adds `RunKind.REPORT`.
  - `ClaudeCodeAgent`:
    - `permissionMode`: `REPORT -> "plan"`
    - `commandLine`: `case REPORT -> --tools "Read,Bash" + skill, --json-schema Schemas.REPORT`
    - `OWNER_KINDS` adds REPORT
  - `App.java:176`: builds `DataSources.of(config)` only when `!config.isTeam()`, otherwise `NONE`.
  - `worker/WorkerLoop.java:123`: passes `DataSources.NONE`.
- Test:
  - `JobRunnerTest`
  - `ClaudeCodeAgentTest`
  - `SandboxPoliciesTest`

**Interfaces:**
- Consumes:
  - `Tunnel.open(List<String>, DataSource, Duration)` (Task 2)
  - `DataAccess` and `RunRequest.data` (Task 3)
  - `DataSource.connectionString(int)` (Task 1)
- Produces:
  - `public interface DataSources { Optional<DataSource> forProject(String project); Tunnel open(DataSource source); DataSources NONE = …; static DataSources of(Config config) }`
  - `of(config)` uses `List.of("ssh")`; `of(config, List<String> ssh)` exists for the live test. `open` calls `Tunnel.open(ssh, source, Duration.ofSeconds(20))`. Tests pass a fake that records `open` and `close`.

`report(...)` mirrors `plan(...)`: existing worktree, no copyFiles, `steps.around(RunStep.Kind.REPORT, …)`. In both, the tunnel goes in try-with-resources around `runAgent`, and a `TunnelException` becomes `JobResult.failed(FailureReason.SETUP, e.getMessage(), null)`. The request gets `new DataAccess(kind == REPORT ? FULL : SCHEMA, source.connectionString(tunnel.port()))`.

- [ ] **Step 1: Write the failing tests**

- `aPlanOnADataProjectRunsWithSchemaAccessThroughATunnel`: the fake agent captures `request.data().level() == SCHEMA`, and the connection string contains `127.0.0.1:<fake port>`.
- `aReportRunGetsFullAccess`
- `aTunnelThatFailsFailsTheRunAsSetupWithoutStartingTheAgent`
- `aCancelledReportRunClosesItsTunnel`: the fake `DataSources` records `close()` after a run whose control was stopped.
- `aProjectWithoutDataOpensNoTunnel`
- `aReportRunIsPlanModeWithTheReportSchema` (ClaudeCodeAgentTest)
- `reportRunsWorkInTheWorktree` (SandboxPoliciesTest)

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest='JobRunnerTest,ClaudeCodeAgentTest,SandboxPoliciesTest'`. Expected: FAIL or compile failure.

- [ ] **Step 3: Implement** as listed under Files and Interfaces.

- [ ] **Step 4: Run them to see them pass**

Run: `./mvnw -q verify`. Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

`git commit -m "A personal bot's plan and report runs on a data project reach prod through a tunnel opened for the run"`

---

### Task 7: The report plan in Telegram

**Files:**
- Modify:
  - `src/main/java/dispatch/telegram/Renderer.java` (`plan(...)`): when `plan.result() == REPORT`, the title uses `plan.reportTitle` and the approve button uses `button.report`. Everything else, including steps, findings, edit/reject and questions, is unchanged.
  - `src/main/resources/messages_mn.properties`:
    - `plan.reportTitle=📊 <b>#{0} тайлан</b> · {1}`
    - `button.report=📊 Тайлан гаргах`
- Test: `src/test/java/dispatch/telegram/RendererTest.java`

- [ ] **Step 1: Write the failing tests**

- `aReportPlanSaysReportAndOffersToMakeIt`: the text contains `📊`, and the first button's text is `📊 Тайлан гаргах` with callback `Approve`.
- `aCodePlanKeepsItsStartButton`

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest=RendererTest`. Expected: FAIL.

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run them to see them pass**

Run: the same command. Expected: PASS.

- [ ] **Step 5: Commit**

`git commit -m "A report plan is shown as a report, with a button that makes it"`

---

### Task 8: The `data-report` skill

**Files:**
- Create: `src/main/resources/skills/dispatch/skills/data-report/SKILL.md`
- Modify: `src/main/resources/skills/dispatch/files.txt` (add the path)
- Test: the existing bundled-skills test (find it with `grep -rl files.txt src/test`). Add an assertion that `data-report/SKILL.md` is written to the plugin dir.

**Skill content.** Frontmatter `name: data-report`, description: "Use when building an HTML report from a project's data in a report run." The body covers:
- **Workflow:**
  1. Read the approved steps.
  2. Run the main query.
  3. Run the data checks.
  4. Write the HTML.
- **Data checks, before writing:**
  - Query at the finest useful grain (daily when the request is per month or year).
  - Find gaps: missing days or months.
  - Find zero or near-zero periods next to normal ones (below 10% of the median of the 3 neighbours on each side).
  - Find partial first and last periods; mark them "үргэлжилж буй" when the period is ongoing.
  - Find spikes above 5× that median.
  - Each finding goes in a warning box with its dates and its effect on totals ("the total for X is lower than the real figure").
- **Layout, in order:**
  1. Eyebrow with system name, then title, then period and source line.
  2. Warning box, only when there are findings.
  3. Hero total with average and peak.
  4. One card per requested period, with share bar, days and average.
  5. Bar chart by month, partial periods lighter, with tooltip on hover or tap.
  6. Period table.
  7. Detail table in `<details>`.
  8. Notes: what each number is and where it came from.
- **Style:**
  - CSS custom properties on `:root`.
  - Dark mode under `@media (prefers-color-scheme: dark)`.
  - System font stack.
  - `font-variant-numeric: tabular-nums`.
  - Max width 1040px, 16px gutter, readable at 360px.
  - Numbers formatted with thousands separators.
  - `lang` set to the task's language, Mongolian unless the task is in another.
- **Self-contained:** no external script, font or image. The chart is inline SVG built by a small inline script, or static SVG.
- **Privacy:** aggregates only. Never names, phone numbers, register numbers, emails or other raw personal fields.

The skill **must not** contain any real system's name, hostname or numbers.

- [ ] **Step 1: Write the failing test**, then **Step 2:** run it with `./mvnw -q test -Dtest=<that test>`. Expected: FAIL.
- [ ] **Step 3: Write the skill and add it to `files.txt`.**
- [ ] **Step 4: Run it.** Expected: PASS.
- [ ] **Step 5: Commit**

`git commit -m "Report runs follow a data-report skill: check the data, then one self-contained HTML page"`

---

### Task 9: `dispatch check` for a data block

**Files:**
- Modify: `src/main/java/dispatch/cli/Checks.java`. `checkProject` calls a new `checkData(Run run, Config.Project project, DataSources sources, String mongosh, String npx)` when `project.data() != null`.
- Modify: `texts_en.properties` and `texts_mn.properties` (`check.data*`)
- Test: `src/test/java/dispatch/cli/ChecksTest.java`

`checkData`:
1. Opens the tunnel. A failure is an error naming the SSH host.
2. Runs `mongosh --quiet <connectionString> --eval 'JSON.stringify(db.runCommand({connectionStatus:1,showPrivileges:true}).authInfo.authenticatedUserPrivileges.flatMap(p=>p.actions))'`. The URI is mongosh's last argument. That is acceptable because check runs interactively as the owner (`ponytail:` use `--file` if check ever runs unattended).
3. Is an **error** if the action list contains any of `insert`, `update`, `remove`, `createCollection`, `dropCollection`, `createIndex`, `dropDatabase`, `renameCollectionSameDB`.
4. Runs `npx -y mongodb-mcp-server@2.1.2 --version` with a 120 s timeout, to warm the cache. A failure here is a warning.
5. Closes the tunnel.

`mongosh` missing is a warning ("cannot verify the user is read-only"), not an error.

- [ ] **Step 1: Write the failing tests** (fake `DataSources`, plus fake `mongosh` and `npx` scripts printing canned JSON, `@EnabledOnOs({LINUX, MAC})`):
  - `aReadOnlyUserPasses`
  - `aUserThatCanInsertIsAnError`
  - `aTunnelThatFailsIsAnErrorNamingTheHost`
  - `withoutMongoshTheCheckWarns`
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit**

`git commit -m "dispatch check opens a data project's tunnel and refuses a Mongo user that can write"`

---

### Task 10: Live test, ADR 0043, docs

**Files:**
- Create: `src/test/java/dispatch/core/LiveDataReportTest.java` (`@EnabledIfEnvironmentVariable(named = "DISPATCH_LIVE_DATA", matches = "1")`, modelled on `LiveAnswersTest`)
- Create: `docs/adr/0043-a-report-is-queried-read-only-on-prod-after-approval.md`
- Modify:
  - `README.en.md` and `README.md` (a "Reports from prod data" section: config block, dispatch.env, check, flow)
  - `CONTEXT.md` (glossary: **Report**, **Data source**)
  - `SECURITY.md` (what reaches prod and how it's bounded)
  - `docs/superpowers/specs/2026-10-23-data-reports-design.md` (mark the probe settled: MCP, with the results above)

**Live test.** It needs `docker`, `/usr/sbin/sshd` and `claude` on PATH, and is skipped with `assumeTrue` otherwise. Steps:
1. Start `mongo:7.0 --auth` on a random port, create a root user, then a `read`-only user `ro` on db `webapp`.
2. Seed `visits` with one document per day, `{date, views}`, from 2024-01-01 to 2025-12-31, with **zero views from 2025-02-01 to 2025-02-28** (the planted gap).
3. Start a user-mode `sshd` on a random port: temp host key, `AuthorizedKeysFile` set to a temp `authorized_keys` holding a temp key, `-f` a temp config, `-D`.
4. Write a temp `ssh_config` with `Host live-data` (HostName 127.0.0.1, Port, IdentityFile, `StrictHostKeyChecking no`, `UserKnownHostsFile /dev/null`). Pass it as `DataSources.of(config, List.of("ssh", "-F", file))`.
5. **Plan run:** run PLAN through `JobRunner` with real `ClaudeCodeAgent` in the real sandbox and `DataSources` pointing at `live-data`, with the task "How many visits per year?". Assert:
   - result `REPORT`
   - steps mention `visits`
   - the run's stream log has no `aggregate` tool call
6. **Report run:** run REPORT. Assert:
   - SUCCEEDED
   - the HTML starts with `<!doctype html`
   - it contains `2024` and `2025`
   - it contains a warning naming February 2025 (`2025-02` or `2-р сар`)
7. **Bug-fix plan:** run PLAN for "Fix the null check in the visits loader" on the same project. Assert result `PLAN`.
8. **Write refusal:** with the `ro` user, `mongosh` `insertOne` fails with `Unauthorized`. With FULL `DataAccess`, the init tools have no write tool. Assert both.

- [ ] **Step 1:** Write the live test.
- [ ] **Step 2:** Run it with `DISPATCH_LIVE_DATA=1 ./mvnw -q test -Dtest=LiveDataReportTest`. Expected: PASS. Print the HTML path for ZB to open.
- [ ] **Step 3:** Write ADR 0043 (context, the decision, the probe results and the two read-only layers, consequences: personal only, pinned server version) and the docs.
- [ ] **Step 4:** Run `./mvnw -q verify`. Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit**

`git commit -m "ADR 0043 and the guides describe reports from prod data; a live test runs one through a real tunnel"`
