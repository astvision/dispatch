# W-2 Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `RunExecutor` becomes a `Coordinator` (claims runs, reads and writes the store, applies outcomes through `RunTransitions`) and a `JobRunner` (worktree, agent, delivery) that meet at a `Worker` interface, with the in-process worker for personal mode and no change in behaviour.

**Architecture:** The `Coordinator` reads everything a run needs from the store and the config **once**, at job construction, into an immutable `Job`; a `Worker` turns that `Job` into an immutable `JobResult`; the `Coordinator` turns the `JobResult` into exactly one transition. The two store writes that cannot wait for the end of a run — the worktree and the agent's process — go out through a `JobEvents` callback. Cancellation reaches the worker through the run's existing `ActiveRuns.ActiveRun` handle. `JobRunner` takes no `Database`, no `Projects` and no `Config`, so W-3 can put HTTP exactly at that seam.

**Tech Stack:** Java 25, JUnit 6, SQLite via the existing `Database`/`Tx`, Jackson 2.22.2 through `dispatch.Json` (no JSR-310 module registered), no framework (ADR 0002).

**Spec:** `docs/superpowers/specs/2026-09-22-team-workers-design.md` (milestone W-2; the Architecture, Protocol and Error-handling sections describe what W-3 puts behind this seam).

## Global Constraints

- Behaviour is unchanged: every existing test passes with its assertions untouched.
- The only permitted test change in the whole milestone is `RunExecutorTest`'s construction block (the `executorUnderTest` field type at line ~619 and the `new RunExecutor(...)` call at lines ~600-614); every assertion, helper and test name in that file stays as it is.
- `JobRunner`'s constructor is exactly `JobRunner(Workspaces workspaces, Delivery delivery, Map<String, Agent> agents, Redactor redactor, AttachmentSource attachmentSource)`: no `Database`, no `Projects`, no `Config`, no `RunTransitions`.
- `Job` and `JobResult` are immutable records of plain JSON types and round-trip through `dispatch.Json.MAPPER`; `Duration`, `Instant` and `Path` are not allowed inside them (no JSR-310 module is registered, and a worker's paths mean nothing on the other machine).
- Cancel keeps today's path: `ActiveRuns.stop(taskId, CANCELLED)` → `ActiveRun.stop` → `RunHandle.cancel()` (SIGTERM, 10 s grace, SIGKILL), through the in-process worker.
- Exactly one outcome transition per run, made only by the `Coordinator` through `RunTransitions`; whatever goes wrong, the run never stays RUNNING.
- New classes stay in `dispatch.core`; `App` keeps wiring everything explicitly (ADR 0002).
- No new dependency, no new SQL, no schema change, no message property change.
- No ADR in W-2 (the workers ADR is 0021, in W-4); the only doc touched is `docs/ARCHITECTURE.md`.
- Commit messages end with `Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z`.
- Verify with `./mvnw -q -B verify`.

Rulings made while planning (each with its cost if wrong):

1. The `Coordinator` builds the agent's prompt; the `Job` carries the finished `prompt` string (and `RunCause` is left out, since only the prompt choice used it). The worker appends only its own attachments note, whose directory is machine-local. Cost if wrong: W-3 sends prose the worker cannot adapt; moving `Prompts` behind the seam later means four more `Job` fields (description, plan json, instruction, requester name) and moving `Prompts` out of `core`.
2. `Job.Project` carries the team's settings — `name`, `repo`, `path`, `baseBranch`, `agent`, `copyFiles` — and names the agent (`claude-code`), never its command. In W-3 the remote worker replaces `path` from its `worker.yaml` before handing the job to its own `JobRunner`, and answers "project crm is not set up on your computer" when it has no mapping. Cost if wrong: one field moves from `Job` into a `JobRunner` constructor parameter.
3. Model and effort are resolved per phase by the `Coordinator` (one `model` and one `effort` per job), not sent as both phases' settings. Cost if wrong: a job that ever covered two phases would need both pairs back.
4. Limits travel as `long timeoutMillis` and `BigDecimal budgetUsd`, not `Config.RunLimits`: `Duration` cannot round-trip through this project's Jackson. Cost if wrong: nothing behavioural; adding `JavaTimeModule` later would make it a rename.
5. Paths travel as `String` (`Job.worktree`, `Job.baseSha`, `JobEvents.worktreeCreated`). Cost if wrong: one `Path.of(...)` per use site to undo.
6. The plan JSON is parsed by the `Coordinator` (`Plan.parse`), not by the worker: a worker's answer is checked where it is trusted. Cost if wrong: in W-3 a bad plan costs one extra HTTP round trip before it fails, instead of failing on the worker.
7. Mid-run store writes are exactly two events (`worktreeCreated`, `agentStarted`); the building session id is generated and recorded by the `Coordinator` at job construction instead of a third event. Cost if wrong: a task whose first execution run fails in setup now keeps the `build_session_id` generated for it, and the next run starts that same id with `--session-id` — which already happens today when the agent fails to start.
8. `/status` keeps reading the agent's activity through `ActiveRun.attach(handle)`; there is no progress push in W-2. W-3 adds it: the remote worker posts its own `ActiveRun.activity()` every 10 s and the coordinator side stores it on its `ActiveRun`. Cost if wrong: W-3 adds one setter to `ActiveRun`; the 10 s ticker belongs to the worker loop, not to `JobRunner`, either way.
9. Cancellation is handed to the worker as the existing `ActiveRuns.ActiveRun`, not a new interface. Cost if wrong: extracting a narrower `CancelHandle` interface from `ActiveRun` later is a mechanical rename.
10. `JobRunner implements Worker` directly; there is no separate `InProcessWorker` class. Cost if wrong: none — W-3's remote worker is simply the second implementation, on the coordinator side.
11. A worker that throws is the worker breaking: the `Coordinator` logs `run.crashed` and fails the run as `INTERNAL`, exactly as today's crash path, and `JobRunner` never catches `RuntimeException` itself. Cost if wrong: W-3's remote worker must map transport errors to a `JobResult` (lease expiry → `INTERRUPTED`) instead of letting them surface as `INTERNAL`.
12. Everything new stays in `dispatch.core` next to `RunTransitions` and `ActiveRuns`. Cost if wrong: W-3 moves five files into a `dispatch.worker` package — a package rename with no logic change.
13. A task's files stay the worker's own business: the `Job` lists them (`fileRef`, `name`, `size`) and the runner's `AttachmentSource` downloads them into its own `attachments/<task>/`, so in W-3 that source becomes `/api/worker/attachment` instead of Telegram. Cost if wrong: if the team machine ever had to push the bytes with the job, `Job` would stop being a small JSON document.

## File Structure

- Create `src/main/java/dispatch/core/Job.java` — the immutable job, with the nested `Job.Project`.
- Create `src/main/java/dispatch/core/JobResult.java` — the immutable outcome, with `JobResult.Outcome`.
- Create `src/main/java/dispatch/core/Worker.java` — the seam W-3 puts HTTP behind.
- Create `src/main/java/dispatch/core/JobEvents.java` — the two store writes a running job needs.
- Create `src/main/java/dispatch/core/JobRunner.java` — the machine work; `implements Worker`.
- Create `src/main/java/dispatch/core/Coordinator.java` — claim, job construction, outcome transitions.
- Delete `src/main/java/dispatch/core/RunExecutor.java` (Task 5).
- Modify `src/main/java/dispatch/App.java` — wire `JobRunner` + `Coordinator` instead of `RunExecutor` (lines ~14, ~105-106, ~133).
- Create tests `src/test/java/dispatch/core/JobJsonTest.java`, `src/test/java/dispatch/core/JobRunnerTest.java`, `src/test/java/dispatch/core/CoordinatorTest.java`.
- Modify `src/test/java/dispatch/core/RunExecutorTest.java` — construction only (lines ~600-614 and ~619).
- Modify `docs/ARCHITECTURE.md` — lines 91, 104, 256 and the Background execution section.

---

### Task 1: `Job` and `JobResult` survive JSON

**Files:**
- Create: `src/main/java/dispatch/core/Job.java`, `src/main/java/dispatch/core/JobResult.java`
- Test: `src/test/java/dispatch/core/JobJsonTest.java`

**Interfaces:**
- Consumes: `dispatch.Json.MAPPER`, `dispatch.Json.write(Object)`; `dispatch.domain.{Attachment, FailureReason, RunKind}`; `dispatch.agent.AgentResult`.
- Produces:
  - `public record Job(long taskId, int seq, RunKind kind, Job.Project project, String baseBranch, String baseSha, String worktree, String prUrl, UUID sessionId, boolean resume, String prompt, String model, String effort, long timeoutMillis, BigDecimal budgetUsd, List<Attachment> attachments, String commitSubject, List<String> commitTrailers, String deliverySummary)`
  - `public record Job.Project(String name, String repo, String path, String baseBranch, String agent, List<String> copyFiles)`
  - `public record JobResult(JobResult.Outcome outcome, AgentResult agent, List<String> files, String prUrl, FailureReason failureReason, String failureDetail)` with `enum Outcome { SUCCEEDED, FAILED, CANCELLED }`
  - `public static JobResult succeeded(AgentResult agent)`, `public static JobResult delivered(AgentResult agent, List<String> files, String prUrl)`, `public static JobResult failed(FailureReason reason, String detail, AgentResult agent)`, `public static JobResult cancelled(AgentResult agent)`

- [ ] **Step 1: Write the failing test** — create `src/test/java/dispatch/core/JobJsonTest.java`:

```java
package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.domain.Attachment;
import dispatch.domain.FailureReason;
import dispatch.domain.RunKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** W-3 sends a job to a worker and its result back over HTTP, so both must survive the project's mapper unchanged. */
class JobJsonTest {

    @Test
    void aJobSurvivesJsonUnchanged() throws Exception {
        Job job = new Job(7, 2, RunKind.EXECUTE,
                new Job.Project("alm", "git@github.com:acme/alm.git", "/home/ann/work/alm", "main", "claude-code", List.of(".env")),
                "main", "6f3030a", "/var/lib/dispatch/worktrees/7", "https://github.com/acme/alm/pull/9",
                UUID.fromString("11111111-2222-3333-4444-555555555555"), true, "Implement the approved plan", "opus", "low",
                1_800_000L, new BigDecimal("2.50"), List.of(new Attachment("photo-id", "1-photo.jpg", 3L)),
                "dispatch #7: Fix the login timeout", List.of("Requested-by: Bold", "Approved-by: Ali"), null);

        assertEquals(job, Json.MAPPER.readValue(Json.write(job), Job.class));
    }

    @Test
    void aDeliveryJobSurvivesJsonUnchanged() throws Exception {
        Job job = new Job(7, 3, RunKind.DELIVER,
                new Job.Project("alm", "git@github.com:acme/alm.git", null, "main", "claude-code", List.of()),
                "main", "6f3030a", "/var/lib/dispatch/worktrees/7", null, null, false, null, null, null, 0L, null, List.of(),
                "dispatch #7: Fix the login timeout", List.of("Requested-by: Bold"), "Raised AUTH_TIMEOUT_SECONDS to 30");

        assertEquals(job, Json.MAPPER.readValue(Json.write(job), Job.class));
    }

    @Test
    void everyJobResultSurvivesJsonUnchanged() throws Exception {
        AgentResult agent = new AgentResult(AgentOutcome.SUCCEEDED, 0, "fake-session", "{\"understanding\":\"the login times out\"}",
                "Raised AUTH_TIMEOUT_SECONDS", new BigDecimal("0.073173"), 12, List.of("Bash: git push"), null,
                "claude-sonnet-5", "opus");

        for (JobResult result : List.of(JobResult.succeeded(agent),
                JobResult.delivered(agent, List.of("README.md"), "https://github.com/acme/alm/pull/9"),
                JobResult.failed(FailureReason.DELIVERY, "git push failed: repository not found", agent),
                JobResult.cancelled(null))) {
            assertEquals(result, Json.MAPPER.readValue(Json.write(result), JobResult.class), result.toString());
        }
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -B test -Dtest=JobJsonTest`
Expected: FAIL — compilation errors, `cannot find symbol: class Job` and `cannot find symbol: class JobResult`.

- [ ] **Step 3: Write the records.** Create `src/main/java/dispatch/core/Job.java`:

```java
package dispatch.core;

import dispatch.domain.Attachment;
import dispatch.domain.RunKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One run as the machine work needs it: everything the Coordinator read from the store and the config when the run was
 * claimed, so a worker touches neither. Plain JSON types only — W-3 sends this over HTTP (no Duration, Instant or Path).
 *
 * @param project         the project as the team configured it; a remote worker replaces {@code path} with its own clone
 * @param baseBranch      the branch the task was given with, which its delivery targets
 * @param baseSha         where the task's branch started; null before its first planning run made the worktree
 * @param worktree        the task's worktree as recorded; null before its first planning run
 * @param prUrl           the task's pull request, null until something was delivered
 * @param sessionId       the agent session: the planning session for PLAN, the building session for EXECUTE, null for DELIVER
 * @param resume          whether an earlier run of this kind already started that session's agent
 * @param prompt          what the agent is told, without the attachments note the worker adds; null for DELIVER
 * @param model           this phase's model, null for the agent's default
 * @param effort          this phase's effort, null for the agent's default
 * @param timeoutMillis   how long the agent may run; 0 for DELIVER, which runs none
 * @param budgetUsd       this phase's budget; null for DELIVER
 * @param attachments     the task's files the agent may read; empty for DELIVER
 * @param commitSubject   the delivery commit's subject
 * @param commitTrailers  the delivery commit's trailers: who asked and who approved
 * @param deliverySummary DELIVER only: the failed run's summary, which becomes the commit body
 */
public record Job(
        long taskId,
        int seq,
        RunKind kind,
        Project project,
        String baseBranch,
        String baseSha,
        String worktree,
        String prUrl,
        UUID sessionId,
        boolean resume,
        String prompt,
        String model,
        String effort,
        long timeoutMillis,
        BigDecimal budgetUsd,
        List<Attachment> attachments,
        String commitSubject,
        List<String> commitTrailers,
        String deliverySummary) {

    public Job {
        attachments = List.copyOf(attachments);
        commitTrailers = List.copyOf(commitTrailers);
    }

    /** @param path the clone the run works from, null when the worker keeps its own under {@code repos/<name>} */
    public record Project(String name, String repo, String path, String baseBranch, String agent, List<String> copyFiles) {

        public Project {
            copyFiles = List.copyOf(copyFiles);
        }
    }
}
```

Create `src/main/java/dispatch/core/JobResult.java`:

```java
package dispatch.core;

import dispatch.agent.AgentResult;
import dispatch.domain.FailureReason;
import java.util.List;

/**
 * How one job ended. The Coordinator turns it into exactly one transition, so it carries everything
 * {@link RunTransitions} needs. Plain JSON types only — W-3 sends this back over HTTP.
 *
 * @param agent         the agent's own result, null when no agent reported (a setup failure, a delivery run)
 * @param files         paths the delivery commit changed; empty unless the job delivered
 * @param prUrl         the task's pull request after the delivery, null when nothing has been delivered
 * @param failureReason null unless the outcome is FAILED
 * @param failureDetail null unless the outcome is FAILED
 */
public record JobResult(
        Outcome outcome,
        AgentResult agent,
        List<String> files,
        String prUrl,
        FailureReason failureReason,
        String failureDetail) {

    public enum Outcome {
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    public JobResult {
        files = List.copyOf(files);
    }

    /** An agent that finished its work; a planning run's plan is in {@code agent.structuredOutput()}. */
    public static JobResult succeeded(AgentResult agent) {
        return new JobResult(Outcome.SUCCEEDED, agent, List.of(), null, null, null);
    }

    /** @param agent null for a delivery run, which has no agent */
    public static JobResult delivered(AgentResult agent, List<String> files, String prUrl) {
        return new JobResult(Outcome.SUCCEEDED, agent, files, prUrl, null, null);
    }

    /** @param agent null when the run failed before its agent reported */
    public static JobResult failed(FailureReason reason, String detail, AgentResult agent) {
        return new JobResult(Outcome.FAILED, agent, List.of(), null, reason, detail);
    }

    /** @param agent null when the run was stopped before its agent reported */
    public static JobResult cancelled(AgentResult agent) {
        return new JobResult(Outcome.CANCELLED, agent, List.of(), null, null, null);
    }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -B test -Dtest=JobJsonTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/core/Job.java src/main/java/dispatch/core/JobResult.java \
  src/test/java/dispatch/core/JobJsonTest.java
git commit -m "Describe a run as a Job and its outcome as a JobResult

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 2: `JobRunner` does the worktree, the attachments and the agent

**Files:**
- Create: `src/main/java/dispatch/core/Worker.java`, `src/main/java/dispatch/core/JobEvents.java`, `src/main/java/dispatch/core/JobRunner.java`
- Test: `src/test/java/dispatch/core/JobRunnerTest.java`

**Interfaces:**
- Consumes: `Job`, `JobResult` (Task 1); `ActiveRuns.ActiveRun.stopReason()/attach(RunHandle)/stop(StopReason)`; `Workspaces.createWorktree(Config.Project, long)`, `Workspaces.recreateWorktree(Config.Project, long)`, `Workspaces.copyFiles(Config.Project, Path)`, `Workspaces.attachmentsDir(long)`, `Workspaces.runLogBase(long, int)`; `Delivery.head(Path)`; `Agent.start(RunRequest)`; `Prompts.attachments(Path, List<Attachment>)`; `AttachmentSource.download(String, Path)`; `Redactor.redact(String)`.
- Produces:
  - `public interface Worker { JobResult run(Job job, JobEvents events, ActiveRuns.ActiveRun control); }`
  - `public interface JobEvents { void worktreeCreated(String worktree, String baseSha); void agentStarted(long pid, Instant processStart); }`
  - `public final class JobRunner implements Worker` with `public JobRunner(Workspaces workspaces, Delivery delivery, Map<String, Agent> agents, Redactor redactor, AttachmentSource attachmentSource)`

- [ ] **Step 1: Write the failing test** — create `src/test/java/dispatch/core/JobRunnerTest.java`:

```java
package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.Redactor;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.domain.FailureReason;
import dispatch.domain.RunKind;
import dispatch.testing.FakeClaude;
import dispatch.testing.FakeGh;
import dispatch.testing.GitFixture;
import dispatch.workspace.Delivery;
import dispatch.workspace.Gh;
import dispatch.workspace.Git;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The machine half of a run, with real git worktrees and the fake claude script. The runner is built without a
 * Database on purpose: everything it needs arrives in the Job, which is what W-3 puts HTTP in front of.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake claude and gh CLIs are POSIX shell scripts")
class JobRunnerTest {

    private static final long TASK = 7;
    private static final UUID SESSION = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path dir;

    private GitFixture repos;
    private JobRunner runner;
    private Recorder events;
    private ActiveRuns.ActiveRun control;

    @BeforeEach
    void setUp() throws IOException {
        repos = GitFixture.create(dir, "alm");
        Git git = new Git("git", null, Duration.ofSeconds(30));
        Workspaces workspaces = new Workspaces(repos.stateDir, git);
        Delivery delivery = new Delivery(git, new Gh(FakeGh.install(dir.resolve("gh")).toString(), null, Duration.ofSeconds(30)),
                "Dispatch (backend)", "dispatch-backend@example.com");
        ClaudeCodeAgent agent = new ClaudeCodeAgent(FakeClaude.install(Files.createDirectories(dir.resolve("bin"))).toString(),
                FakeClaude.environment(), Duration.ofSeconds(1));
        runner = new JobRunner(workspaces, delivery, Map.of("claude-code", agent), Redactor.patternsOnly(),
                (fileRef, target) -> {
                    throw new IllegalStateException("file " + fileRef + " is gone");
                });
        events = new Recorder();
        control = new ActiveRuns().register(TASK, 1);
    }

    @Test
    void aPlanJobRunsTheAgentInANewWorktreeAndReportsWhatTheStoreMustRecord() throws Exception {
        JobResult result = runner.run(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null), events, control);

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome());
        assertTrue(result.agent().structuredOutput().contains("understanding"), result.agent().structuredOutput());
        Path worktree = repos.stateDir.resolve("worktrees/" + TASK);
        assertEquals(worktree.toString(), events.worktree, "the Coordinator must record the worktree while the run goes on");
        assertEquals(GitFixture.sh(repos.seed, "git", "rev-parse", "HEAD"), events.baseSha);
        assertTrue(events.pid > 0, "the agent's process is recorded for orphan detection");
        assertEquals("Plan this: fix the login timeout", Files.readString(worktree.resolve("fake-claude.prompt")));
        assertEquals(SESSION.toString(), valueAfter(Files.readAllLines(worktree.resolve("fake-claude.args")), "--session-id"));
        assertTrue(Files.exists(repos.stateDir.resolve("runs/" + TASK + "/1.jsonl")));
    }

    @Test
    void anExecuteJobCopiesTheProjectsLocalFilesIntoTheExistingWorktree() throws Exception {
        Files.writeString(repos.repo("alm").resolve(".env"), "DB_PASSWORD=local-only\n");
        runner.run(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null), events, control);
        Path worktree = Path.of(events.worktree);
        assertFalse(Files.exists(worktree.resolve(".env")), "planning never sees local secrets");

        runner.run(job(RunKind.EXECUTE, 2, "Implement the approved plan", events.worktree, events.baseSha, List.of(".env")),
                new Recorder(), new ActiveRuns().register(TASK, 2));

        assertEquals("DB_PASSWORD=local-only\n", Files.readString(worktree.resolve(".env")));
        assertTrue(Files.readString(worktree.resolve("fake-claude.prompt")).contains("Implement the approved plan"));
    }

    @Test
    void aJobWhoseTaskHasNoWorktreeFailsAsSetupWithoutStartingAnAgent() {
        JobResult result = runner.run(job(RunKind.EXECUTE, 2, "Implement the approved plan", null, null, null), events, control);

        assertEquals(JobResult.Outcome.FAILED, result.outcome());
        assertEquals(FailureReason.SETUP, result.failureReason());
        assertEquals("the task has no worktree", result.failureDetail());
        assertNull(result.agent());
        assertEquals(0, events.pid);
    }

    @Test
    void anAttachmentThatCannotBeDownloadedFailsTheJobBeforeItsAgent() throws Exception {
        Job job = new Job(TASK, 1, RunKind.PLAN, project(List.of()), "main", null, null, null, SESSION, false,
                "Plan this: fix the login timeout", null, null, Duration.ofSeconds(30).toMillis(), new BigDecimal("2"),
                List.of(new dispatch.domain.Attachment("gone-id", "1-photo.jpg", 3L)), "dispatch #7: Fix the login timeout",
                List.of("Requested-by: Bold"), null);

        JobResult result = runner.run(job, events, control);

        assertEquals(FailureReason.SETUP, result.failureReason());
        assertEquals("cannot download 1-photo.jpg: file gone-id is gone", result.failureDetail());
        assertFalse(Files.exists(repos.stateDir.resolve("worktrees/" + TASK + "/fake-claude.args")));
    }

    @Test
    void aStoppedJobNeverTouchesGitOrStartsAnAgent() {
        control.stop(ActiveRuns.StopReason.INTERRUPTED);

        JobResult result = runner.run(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null), events, control);

        assertEquals(FailureReason.INTERRUPTED, result.failureReason());
        assertEquals("Dispatch stopped while the run was active", result.failureDetail());
        assertNull(events.worktree);
        assertFalse(Files.exists(repos.stateDir.resolve("worktrees/" + TASK)));
    }

    @Test
    void aCancelThroughTheControlHandleStopsTheAgentAndItsChildren() throws Exception {
        Job job = job(RunKind.PLAN, 1, "SCENARIO:sleep", null, null, null);
        AtomicReference<JobResult> result = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> result.set(runner.run(job, events, control)));
        Path child = repos.stateDir.resolve("worktrees/" + TASK + "/fake-claude.child");
        awaitFile(child);

        control.stop(ActiveRuns.StopReason.CANCELLED);
        worker.join(Duration.ofSeconds(15));

        assertFalse(worker.isAlive());
        assertEquals(JobResult.Outcome.CANCELLED, result.get().outcome());
        assertTrue(FakeClaude.childEnds(Long.parseLong(Files.readString(child).strip())), "the cancelled run's children must be gone");
    }

    @Test
    void aTimeoutStopsTheAgentAndSaysHowLongItRan() throws Exception {
        Job job = new Job(TASK, 1, RunKind.PLAN, project(List.of()), "main", null, null, null, SESSION, false, "SCENARIO:sleep",
                null, null, 700L, new BigDecimal("2"), List.of(), "dispatch #7: Fix the login timeout",
                List.of("Requested-by: Bold"), null);

        JobResult result = runner.run(job, events, control);

        assertEquals(FailureReason.TIMEOUT, result.failureReason());
        assertEquals("stopped after 700ms", result.failureDetail());
    }

    private Job job(RunKind kind, int seq, String prompt, String worktree, String baseSha, List<String> copyFiles) {
        return new Job(TASK, seq, kind, project(copyFiles == null ? List.of() : copyFiles), "main", baseSha, worktree, null,
                SESSION, false, prompt, null, null, Duration.ofSeconds(30).toMillis(), new BigDecimal("2"), List.of(),
                "dispatch #7: Fix the login timeout", List.of("Requested-by: Bold", "Approved-by: Bold"), null);
    }

    private Job.Project project(List<String> copyFiles) {
        // path null: the clone GitFixture made under the state directory's repos/, as Dispatch's own projects use.
        return new Job.Project("alm", repos.origin.toString(), null, "main", "claude-code", copyFiles);
    }

    private static String valueAfter(List<String> args, String flag) {
        int index = args.indexOf(flag);
        if (index < 0 || index + 1 >= args.size()) {
            throw new AssertionError(flag + " missing in " + args);
        }
        return args.get(index + 1);
    }

    private static void awaitFile(Path file) throws Exception {
        Instant deadline = Instant.now().plusSeconds(15);
        while (!Files.exists(file) || Files.readString(file).isBlank()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("timed out waiting for " + file);
            }
            Thread.sleep(20);
        }
    }

    /** What the Coordinator would write to the store while the job runs. */
    private static final class Recorder implements JobEvents {

        private String worktree;
        private String baseSha;
        private long pid;
        private Instant processStart;

        @Override
        public void worktreeCreated(String worktree, String baseSha) {
            this.worktree = worktree;
            this.baseSha = baseSha;
        }

        @Override
        public void agentStarted(long pid, Instant processStart) {
            this.pid = pid;
            this.processStart = processStart;
        }
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -B test -Dtest=JobRunnerTest`
Expected: FAIL — compilation errors, `cannot find symbol: class JobRunner` and `cannot find symbol: class JobEvents`.

- [ ] **Step 3: Write the seam.** Create `src/main/java/dispatch/core/Worker.java`:

```java
package dispatch.core;

/**
 * Where a run's machine work happens: the worktree, the agent and the delivery. Personal mode runs {@link JobRunner} in
 * this process; in a team, a member's own computer does the same work behind HTTP (W-3). A worker is given everything in
 * the {@link Job} and gives everything back in the {@link JobResult}: it reads no store and no config of its own.
 */
public interface Worker {

    /**
     * Carries one job to its end on the calling thread.
     *
     * @param events  the two store writes the Coordinator must make while the job is still running
     * @param control the run's stop handle: the worker stops its agent as soon as this has a stop reason
     * @throws RuntimeException only when the worker itself broke; the Coordinator then fails the run as INTERNAL
     */
    JobResult run(Job job, JobEvents events, ActiveRuns.ActiveRun control);
}
```

Create `src/main/java/dispatch/core/JobEvents.java`:

```java
package dispatch.core;

import java.time.Instant;

/** What the Coordinator has to record while a job is still running, because the next run depends on it. */
public interface JobEvents {

    /** The task's worktree now exists: recorded before anything runs in it, so a later run continues there. */
    void worktreeCreated(String worktree, String baseSha);

    /**
     * The agent's process started: recorded so a restart recognises its orphan, and so the next run of this kind knows
     * the session was started and resumes it.
     */
    void agentStarted(long pid, Instant processStart);
}
```

Create `src/main/java/dispatch/core/JobRunner.java`:

```java
package dispatch.core;

import dispatch.Log;
import dispatch.OwnerOnly;
import dispatch.Redactor;
import dispatch.agent.Agent;
import dispatch.agent.AgentResult;
import dispatch.agent.AgentStartException;
import dispatch.agent.RunHandle;
import dispatch.agent.RunRequest;
import dispatch.config.Config;
import dispatch.domain.Attachment;
import dispatch.domain.FailureReason;
import dispatch.workspace.Delivery;
import dispatch.workspace.WorkspaceException;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The machine work of one run, in this process: the worktree, the task's files, the agent under its timeout, and (from
 * the next task) the delivery. It reads no store and no config — everything arrives in the {@link Job} and leaves in the
 * {@link JobResult} — so W-3 can put HTTP between it and the {@link Coordinator}.
 */
public final class JobRunner implements Worker {

    private final Workspaces workspaces;
    private final Delivery delivery;
    private final Map<String, Agent> agents;
    private final Redactor redactor;
    private final AttachmentSource attachmentSource;

    /**
     * @param redactor         masks secrets in the agent's summary before it becomes a commit message and pull request
     * @param attachmentSource downloads the files sent with a task
     */
    public JobRunner(Workspaces workspaces, Delivery delivery, Map<String, Agent> agents, Redactor redactor,
                     AttachmentSource attachmentSource) {
        this.workspaces = workspaces;
        this.delivery = delivery;
        this.agents = Map.copyOf(agents);
        this.redactor = redactor;
        this.attachmentSource = attachmentSource;
    }

    @Override
    public JobResult run(Job job, JobEvents events, ActiveRuns.ActiveRun control) {
        return switch (job.kind()) {
            case PLAN -> plan(job, events, control);
            case EXECUTE -> implement(job, events, control);
            case DELIVER -> throw new UnsupportedOperationException("delivery runs are added in the next task");
            case SPLIT -> throw new IllegalStateException("a split is never a task's run");
        };
    }

    private JobResult plan(Job job, JobEvents events, ActiveRuns.ActiveRun control) {
        if (control.stopReason() != null) {
            return stopped(job, control.stopReason(), null);
        }
        Path worktree;
        TaskFiles files;
        try {
            worktree = job.worktree() == null ? createWorktree(job, events) : existingWorktree(job);
            if (control.stopReason() != null) {
                return stopped(job, control.stopReason(), null);
            }
            files = attachments(job);
        } catch (WorkspaceException e) {
            return JobResult.failed(FailureReason.SETUP, e.getMessage(), null);
        }
        return runAgent(job, events, control, request(job, worktree, files));
    }

    private JobResult implement(Job job, JobEvents events, ActiveRuns.ActiveRun control) {
        if (control.stopReason() != null) {
            return stopped(job, control.stopReason(), null);
        }
        Path worktree;
        TaskFiles files;
        try {
            worktree = existingWorktree(job);
            files = attachments(job);
            // Local-only files (e.g. .env) the build and tests need; never part of planning runs.
            workspaces.copyFiles(config(job.project()), worktree);
        } catch (WorkspaceException e) {
            return JobResult.failed(FailureReason.SETUP, e.getMessage(), null);
        }
        if (control.stopReason() != null) {
            return stopped(job, control.stopReason(), null);
        }
        // The next task delivers what the agent changed from here.
        return runAgent(job, events, control, request(job, worktree, files));
    }

    private RunRequest request(Job job, Path worktree, TaskFiles files) {
        return new RunRequest(job.kind(), worktree, job.prompt() + files.note(), job.sessionId(), job.resume(), files.dirs(),
                job.budgetUsd(), job.model(), job.effort(), workspaces.runLogBase(job.taskId(), job.seq()));
    }

    /** No copyFiles here: planning needs no local secrets, and whatever the agent reads may be quoted in the group. */
    private Path createWorktree(Job job, JobEvents events) {
        Workspaces.PreparedWorktree worktree = workspaces.createWorktree(config(job.project()), job.taskId());
        events.worktreeCreated(worktree.path().toString(), worktree.baseSha());
        return worktree.path();
    }

    /** Later runs continue in the worktree the first planning run created; one the idle sweep removed is added back. */
    private Path existingWorktree(Job job) {
        if (job.worktree() == null) {
            throw new WorkspaceException("the task has no worktree");
        }
        Path worktree = Path.of(job.worktree());
        if (Files.isDirectory(worktree)) {
            return worktree;
        }
        try {
            Path recreated = workspaces.recreateWorktree(config(job.project()), job.taskId());
            Log.info("worktree.recreated", "task", job.taskId(), "worktree", recreated);
            return recreated;
        } catch (WorkspaceException e) {
            throw new WorkspaceException("worktree " + worktree + " is missing and could not be recreated: " + e.getMessage(), e);
        }
    }

    /** A task's downloaded files: the directories the agent may read, and what its prompt says about them. */
    private record TaskFiles(List<Path> dirs, String note) {

        static final TaskFiles NONE = new TaskFiles(List.of(), "");
    }

    /**
     * Downloads the job's files that are not there yet. Each file lands under a temporary name first, so one cut short is
     * fetched again by the next run.
     */
    private TaskFiles attachments(Job job) {
        List<Attachment> files = job.attachments();
        if (files.isEmpty()) {
            return TaskFiles.NONE;
        }
        Path dir = workspaces.attachmentsDir(job.taskId());
        for (Attachment file : files) {
            Path target = dir.resolve(file.name());
            if (file.tooLarge() || Files.exists(target)) {
                continue;
            }
            try {
                OwnerOnly.createDirectories(dir);
                Path partial = dir.resolve(file.name() + ".part");
                attachmentSource.download(file.fileRef(), partial);
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                throw new WorkspaceException("cannot download " + file.name() + ": " + e.getMessage(), e);
            }
        }
        return new TaskFiles(List.of(dir), Prompts.attachments(dir, files));
    }

    /** Starts the agent, waits for it under the job's timeout, and turns how it ended into the job's result. */
    private JobResult runAgent(Job job, JobEvents events, ActiveRuns.ActiveRun control, RunRequest request) {
        RunHandle handle;
        try {
            handle = agents.get(job.project().agent()).start(request);
        } catch (AgentStartException e) {
            return JobResult.failed(FailureReason.AGENT, e.getMessage(), null);
        }
        events.agentStarted(handle.process().pid(), handle.processStart());
        control.attach(handle);

        Duration timeout = Duration.ofMillis(job.timeoutMillis());
        Thread watchdog = Thread.ofVirtual().name("run-timeout-" + job.taskId() + "." + job.seq()).start(() -> {
            try {
                Thread.sleep(timeout);
                control.stop(ActiveRuns.StopReason.TIMEOUT);
            } catch (InterruptedException e) {
                // The run ended before the timeout; nothing to stop.
            }
        });
        AgentResult result;
        try {
            result = handle.await();
        } catch (InterruptedException e) {
            handle.cancel();
            Thread.currentThread().interrupt();
            return JobResult.failed(FailureReason.INTERRUPTED, "run thread was interrupted", null);
        } finally {
            watchdog.interrupt();
        }
        ActiveRuns.StopReason stopReason = control.stopReason();
        if (stopReason != null) {
            return stopped(job, stopReason, result);
        }
        return switch (result.outcome()) {
            case SUCCEEDED -> JobResult.succeeded(result);
            case BUDGET_EXCEEDED -> JobResult.failed(FailureReason.BUDGET, result.error(), result);
            case FAILED -> JobResult.failed(FailureReason.AGENT, result.error(), result);
        };
    }

    /** @param result null when the run was stopped before its agent reported */
    private static JobResult stopped(Job job, ActiveRuns.StopReason reason, AgentResult result) {
        return switch (reason) {
            case CANCELLED -> JobResult.cancelled(result);
            case TIMEOUT -> JobResult.failed(FailureReason.TIMEOUT,
                    "stopped after " + format(Duration.ofMillis(job.timeoutMillis())), result);
            case INTERRUPTED -> JobResult.failed(FailureReason.INTERRUPTED, "Dispatch stopped while the run was active", result);
        };
    }

    /** The job's project as the workspace helpers take it; only the fields they read are filled. */
    private static Config.Project config(Job.Project project) {
        return new Config.Project(project.name(), null, project.repo(), project.path(), project.baseBranch(), project.agent(),
                null, null, project.copyFiles(), null, null, null);
    }

    private static String format(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds > 0 && seconds % 3600 == 0) {
            return seconds / 3600 + "h";
        }
        if (seconds > 0 && seconds % 60 == 0) {
            return seconds / 60 + "m";
        }
        return seconds > 0 ? seconds + "s" : duration.toMillis() + "ms";
    }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -B test -Dtest=JobRunnerTest`
Expected: PASS (7 tests). `RunExecutor` is untouched and still compiles.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/core/Worker.java src/main/java/dispatch/core/JobEvents.java \
  src/main/java/dispatch/core/JobRunner.java src/test/java/dispatch/core/JobRunnerTest.java
git commit -m "Run a job's worktree, files and agent behind a Worker interface

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 3: `JobRunner` delivers

**Files:**
- Modify: `src/main/java/dispatch/core/JobRunner.java` (`run`, `implement`)
- Test: `src/test/java/dispatch/core/JobRunnerTest.java`

**Interfaces:**
- Consumes: `Delivery.head(Path)`, `Delivery.deliver(Path, long, String, String, Delivery.Commit, String)`, `Delivery.redeliver(Path, long, String, String, Delivery.Commit, String)`, `Delivery.Commit(String subject, String body, List<String> trailers)`, `Delivery.Result(List<String> files, String commitSha, String prUrl)`; `Job.commitSubject()`, `Job.commitTrailers()`, `Job.deliverySummary()`, `Job.baseSha()`, `Job.prUrl()`.
- Produces: `JobRunner.run` now answers `RunKind.DELIVER`; a successful EXECUTE job returns `JobResult.delivered(agent, files, prUrl)`; a failed delivery returns `JobResult.failed(FailureReason.DELIVERY, gitError, agent)`.

- [ ] **Step 1: Write the failing tests** — add to `JobRunnerTest`:

```java
    @Test
    void anExecuteJobDeliversTheAgentsChangesWithTheJobsSubjectAndTrailers() throws Exception {
        runner.run(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null), events, control);

        JobResult result = runner.run(job(RunKind.EXECUTE, 2, "Implement the approved plan", events.worktree, events.baseSha, null),
                new Recorder(), new ActiveRuns().register(TASK, 2));

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome());
        assertEquals(List.of("README.md"), result.files());
        assertEquals(FakeGh.PR_URL, result.prUrl());
        String branch = "refs/heads/dispatch/" + TASK;
        assertEquals("dispatch #7: Fix the login timeout", origin("log", "-1", "--format=%s", branch));
        String body = origin("log", "-1", "--format=%b", branch);
        assertTrue(body.contains("AUTH_TIMEOUT_SECONDS"), body);
        assertTrue(body.endsWith("Requested-by: Bold\nApproved-by: Bold"), body);
    }

    @Test
    void aDeliveryJobCommitsTheFailedRunsSummaryWithoutStartingAnAgent() throws Exception {
        runner.run(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null), events, control);
        Path worktree = Path.of(events.worktree);
        Files.writeString(worktree.resolve("README.md"), "v2\n");
        Files.delete(worktree.resolve("fake-claude.prompt"));

        Job job = new Job(TASK, 3, RunKind.DELIVER, project(List.of()), "main", events.baseSha, events.worktree, null, null,
                false, null, null, null, 0L, null, List.of(), "dispatch #7: Fix the login timeout",
                List.of("Requested-by: Bold", "Approved-by: Bold"), "Raised AUTH_TIMEOUT_SECONDS to 30");
        JobResult result = runner.run(job, new Recorder(), new ActiveRuns().register(TASK, 3));

        assertEquals(List.of("README.md"), result.files());
        assertEquals(FakeGh.PR_URL, result.prUrl());
        assertNull(result.agent(), "a delivery run has no agent result");
        assertFalse(Files.exists(worktree.resolve("fake-claude.prompt")), "a delivery run never starts the agent");
        assertEquals("Raised AUTH_TIMEOUT_SECONDS to 30",
                origin("log", "-1", "--format=%b", "refs/heads/dispatch/" + TASK).lines().findFirst().orElseThrow());
    }

    @Test
    void aPushThatFailsComesBackAsDeliveryAndKeepsTheAgentsResult() throws Exception {
        runner.run(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null), events, control);
        GitFixture.sh(repos.repo("alm"), "git", "remote", "set-url", "origin", dir.resolve("missing.git").toString());

        JobResult result = runner.run(job(RunKind.EXECUTE, 2, "Implement the approved plan", events.worktree, events.baseSha, null),
                new Recorder(), new ActiveRuns().register(TASK, 2));

        assertEquals(FailureReason.DELIVERY, result.failureReason());
        assertTrue(result.failureDetail().contains("git push"), result.failureDetail());
        assertTrue(result.agent().summary().contains("AUTH_TIMEOUT_SECONDS"), "the summary is kept, so a retry delivers it");
    }

    private String origin(String... args) {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "--git-dir";
        command[2] = repos.origin.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        return GitFixture.sh(dir, command);
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -B test -Dtest=JobRunnerTest`
Expected: FAIL — the execute job returns `files = []` and `prUrl = null` and nothing was pushed (`origin log refs/heads/dispatch/7` fails with "unknown revision"), the failed-push job comes back SUCCEEDED instead of DELIVERY, and the delivery job throws `UnsupportedOperationException: delivery runs are added in the next task`.

- [ ] **Step 3: Deliver.** In `JobRunner.run`, replace the DELIVER line:

```java
            case DELIVER -> deliverAgain(job, control);
```

In `implement`, remember the commit the run starts from and deliver what the agent changed — replace everything from `Path worktree;` to the method's closing brace with:

```java
        Path worktree;
        TaskFiles files;
        String startSha;
        try {
            worktree = existingWorktree(job);
            files = attachments(job);
            // Local-only files (e.g. .env) the build and tests need; never part of planning runs.
            workspaces.copyFiles(config(job.project()), worktree);
            startSha = delivery.head(worktree);
        } catch (WorkspaceException e) {
            return JobResult.failed(FailureReason.SETUP, e.getMessage(), null);
        }
        if (control.stopReason() != null) {
            return stopped(job, control.stopReason(), null);
        }
        JobResult result = runAgent(job, events, control, request(job, worktree, files));
        if (result.outcome() != JobResult.Outcome.SUCCEEDED) {
            return result;
        }
        return deliver(job, worktree, startSha, result.agent());
    }
```

Add the two delivery methods after `implement`:

```java
    private JobResult deliver(Job job, Path worktree, String startSha, AgentResult result) {
        Delivery.Result delivered;
        try {
            delivered = delivery.deliver(worktree, job.taskId(), job.baseBranch(), startSha, commit(job, result.summary()),
                    job.prUrl());
        } catch (WorkspaceException e) {
            return JobResult.failed(FailureReason.DELIVERY, e.getMessage(), result);
        }
        return JobResult.delivered(result, delivered.files(), delivered.prUrl());
    }

    /** Delivers a failed delivery's work again, without the agent: the commit body is that run's summary. */
    private JobResult deliverAgain(Job job, ActiveRuns.ActiveRun control) {
        if (control.stopReason() != null) {
            return stopped(job, control.stopReason(), null);
        }
        Path worktree;
        try {
            worktree = existingWorktree(job);
        } catch (WorkspaceException e) {
            return JobResult.failed(FailureReason.SETUP, e.getMessage(), null);
        }
        Delivery.Result delivered;
        try {
            delivered = delivery.redeliver(worktree, job.taskId(), job.baseBranch(), job.baseSha(),
                    commit(job, job.deliverySummary()), job.prUrl());
        } catch (WorkspaceException e) {
            return JobResult.failed(FailureReason.DELIVERY, e.getMessage(), null);
        }
        return JobResult.delivered(null, delivered.files(), delivered.prUrl());
    }

    /** The task's delivery commit: the job's subject and trailers, and the summary with secrets masked as its body. */
    private Delivery.Commit commit(Job job, String summary) {
        String body = summary == null ? "" : redactor.redact(summary).strip();
        return new Delivery.Commit(job.commitSubject(), body, job.commitTrailers());
    }
```

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -B test -Dtest=JobRunnerTest`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/core/JobRunner.java src/test/java/dispatch/core/JobRunnerTest.java
git commit -m "Let the job runner deliver a run's changes and redeliver a failed one

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 4: `Coordinator` builds the job and applies its outcome

**Files:**
- Create: `src/main/java/dispatch/core/Coordinator.java`
- Test: `src/test/java/dispatch/core/CoordinatorTest.java`

**Interfaces:**
- Consumes: `Database.transaction/transactionReturning`; `Tasks.find(Tx, long)`, `Runs.find(Tx, long, int)`, `Runs.forTask(Tx, long)`, `Runs.agentStartedBefore(Tx, long, RunKind, int)`, `Attachments.forTask(Tx, long)`; `Projects.byName(String)`; `RunTransitions.recordWorktree/recordProcess/recordBuildSession/planSucceeded/completed/failed/cancelled`; `Prompts.plan/correction/execute/retry/followUp`; `Plan.parse(String)`; `ActiveRuns.register/unregister`; `Config.RunLimits.timeout()/budgetUsd()`; `Config.Project.planModel()/planEffort()/executeModel()/executeEffort()`; `Worker.run(Job, JobEvents, ActiveRuns.ActiveRun)`.
- Produces:
  - `public final class Coordinator`
  - `public Coordinator(Database db, Projects projects, RunTransitions transitions, ActiveRuns activeRuns, Function<Config.Project, Config.RunLimits> planLimits, Function<Config.Project, Config.RunLimits> executeLimits, Worker worker, Runnable wakeScheduler)`
  - `public void execute(ClaimedRun claimed)`

- [ ] **Step 1: Write the failing test** — create `src/test/java/dispatch/core/CoordinatorTest.java`:

```java
package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
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

/** The store side of a run: what the Coordinator puts into a job, and what it does with the job's result. */
class CoordinatorTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Config.Project ALM = new Config.Project("alm", null, "git@github.com:acme/alm.git", "/home/bold/alm",
            "main", "claude-code", null, "high", List.of(".env"), null, new Config.PhaseSettings("opus", null),
            new Config.PhaseSettings(null, "low"));
    private static final String PLAN_JSON = new Plan("The login times out", List.of("AUTH_TIMEOUT_SECONDS is 5"),
            List.of("Raise the timeout"), List.of(), List.of()).toJson();

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private ActiveRuns activeRuns;
    private TaskService tasks;
    private final TestClock clock = new TestClock(Instant.parse("2026-09-17T10:00:00Z"));
    private final AtomicReference<Job> given = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        activeRuns = new ActiveRuns();
        Groups groups = new Groups(List.of(new Config.Group("backend", -100L, List.of(new Config.Member(100, "Bold")),
                List.of("alm"))));
        tasks = new TaskService(groups, projects(List.of(ALM)), activeRuns, clock, () -> { }, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aPlanJobCarriesEverythingTheWorkerNeedsAndItsResultBecomesThePlan() {
        long id = queue("Fix the login timeout");

        coordinator(projects(List.of(ALM)), remember(JobResult.succeeded(agentResult(PLAN_JSON)))).execute(claim());

        Job job = given.get();
        assertEquals(id, job.taskId());
        assertEquals(1, job.seq());
        assertEquals(RunKind.PLAN, job.kind());
        assertEquals("alm", job.project().name());
        assertEquals("/home/bold/alm", job.project().path(), "the worker is told where the clone is");
        assertEquals(List.of(".env"), job.project().copyFiles());
        assertEquals("main", job.baseBranch());
        assertNull(job.worktree(), "the first run makes it");
        assertEquals("opus", job.model(), "planning's own model");
        assertEquals("high", job.effort(), "the project's effort, as planning sets none of its own");
        assertEquals(Duration.ofMinutes(30).toMillis(), job.timeoutMillis());
        assertEquals(new BigDecimal("2"), job.budgetUsd());
        assertFalse(job.resume(), "no earlier planning run started the session");
        assertTrue(job.prompt().contains("Fix the login timeout"), job.prompt());
        assertEquals("dispatch #" + id + ": Fix the login timeout", job.commitSubject());
        assertEquals(List.of("Requested-by: Bold"), job.commitTrailers());
        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals("1", row("SELECT count(*) AS n FROM outbox WHERE kind = 'PLAN_READY'").get("n"));
    }

    @Test
    void whatTheWorkerReportsWhileItRunsIsRecordedAtOnce() {
        long id = queue("Fix the login timeout");
        Worker worker = (job, events, control) -> {
            events.worktreeCreated("/var/lib/dispatch/worktrees/" + id, "abc123");
            events.agentStarted(4242, Instant.parse("2026-09-17T10:00:01Z"));
            return JobResult.succeeded(agentResult(PLAN_JSON));
        };

        coordinator(projects(List.of(ALM)), worker).execute(claim());

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("/var/lib/dispatch/worktrees/" + id, task.get("worktree"));
        assertEquals("abc123", task.get("base_sha"));
        assertEquals("4242", row("SELECT pid FROM run WHERE task_id = ?", id).get("pid"));
    }

    @Test
    void aProjectThatIsGoneFailsTheRunBeforeAnyWorkerSeesIt() {
        long id = queue("Fix the login timeout");

        coordinator(projects(List.of()), remember(JobResult.succeeded(agentResult(PLAN_JSON)))).execute(claim());

        assertNull(given.get(), "no job is given out for a project that is no longer configured");
        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("FAILED", task.get("phase"));
        assertEquals("SETUP", task.get("failure_reason"));
        assertEquals("project alm is no longer configured", task.get("failure_detail"));
    }

    @Test
    void aFailedJobResultFailsTheRunWithItsReasonAndDetail() {
        long id = queue("Fix the login timeout");

        coordinator(projects(List.of(ALM)), remember(JobResult.failed(FailureReason.DELIVERY, "git push failed", null)))
                .execute(claim());

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("FAILED", task.get("phase"));
        assertEquals("DELIVERY", task.get("failure_reason"));
        assertEquals("git push failed", task.get("failure_detail"));
        assertEquals("FAILED", row("SELECT status FROM run WHERE task_id = ?", id).get("status"));
    }

    @Test
    void aWorkerThatBreaksFailsTheRunAsInternal() {
        long id = queue("Fix the login timeout");
        Worker worker = (job, events, control) -> {
            throw new IllegalStateException("worker broke");
        };

        coordinator(projects(List.of(ALM)), worker).execute(claim());

        Map<String, String> task = row("SELECT * FROM task WHERE id = ?", id);
        assertEquals("FAILED", task.get("phase"));
        assertEquals("INTERNAL", task.get("failure_reason"));
        assertEquals("Dispatch error: worker broke", task.get("failure_detail"));
        assertTrue(activeRuns.activity(id).isEmpty(), "the run is unregistered whatever happened");
    }

    @Test
    void aCancelDuringTheJobReachesTheWorkersControlHandle() {
        long id = queue("Fix the login timeout");
        Worker worker = (job, events, control) -> {
            db.transaction(tx -> tasks.cancel(tx, BOLD, id, "telegram:100/9", "telegram:100"));
            return control.stopReason() == ActiveRuns.StopReason.CANCELLED
                    ? JobResult.cancelled(null)
                    : JobResult.succeeded(agentResult(PLAN_JSON));
        };

        coordinator(projects(List.of(ALM)), worker).execute(claim());

        assertEquals("CANCELLED", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals("CANCELLED", row("SELECT status FROM run WHERE task_id = ?", id).get("status"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'TASK_FAILED'").get("n"));
    }

    private Coordinator coordinator(Projects projects, Worker worker) {
        return new Coordinator(db, projects, new RunTransitions(db, clock, () -> { }), activeRuns,
                project -> new Config.RunLimits(Duration.ofMinutes(30), new BigDecimal("2")),
                project -> new Config.RunLimits(Duration.ofMinutes(60), new BigDecimal("10")), worker, () -> { });
    }

    private Worker remember(JobResult result) {
        return (job, events, control) -> {
            given.set(job);
            return result;
        };
    }

    private static Projects projects(List<Config.Project> configured) {
        return new Projects(configured, project -> Optional.empty());
    }

    private static AgentResult agentResult(String structuredOutput) {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "fake-session", structuredOutput, "done", new BigDecimal("0.01"), 2,
                List.of(), null, "claude-sonnet-5", null);
    }

    private long queue(String description) {
        db.transaction(tx -> tasks.create(tx, BOLD, "alm", description, Priority.NORMAL, BOLD.ref() + "/" + System.nanoTime()));
        return Long.parseLong(row("SELECT max(id) AS id FROM task").get("id"));
    }

    private ClaimedRun claim() {
        return db.transactionReturning(tx -> Runs.claimNext(tx, 5, clock.instant())).orElseThrow();
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -B test -Dtest=CoordinatorTest`
Expected: FAIL — compilation error, `cannot find symbol: class Coordinator`.

- [ ] **Step 3: Write the Coordinator.** Create `src/main/java/dispatch/core/Coordinator.java`:

```java
package dispatch.core;

import dispatch.Log;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.domain.Attachment;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.InvalidPlanException;
import dispatch.domain.Plan;
import dispatch.domain.Run;
import dispatch.domain.RunCause;
import dispatch.domain.RunKind;
import dispatch.domain.Task;
import dispatch.store.Attachments;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Carries one claimed run to its end on the calling thread. It reads the store and the config into a {@link Job}, gives
 * that to a {@link Worker} — in this process {@link JobRunner}, in a team the requester's own computer (W-3) — and turns
 * the {@link JobResult} into exactly one outcome transition. Whatever goes wrong, the run never stays RUNNING.
 */
public final class Coordinator {

    private final Database db;
    private final Projects projects;
    private final RunTransitions transitions;
    private final ActiveRuns activeRuns;
    private final Function<Config.Project, Config.RunLimits> planLimits;
    private final Function<Config.Project, Config.RunLimits> executeLimits;
    private final Worker worker;
    private final Runnable wakeScheduler;

    public Coordinator(Database db, Projects projects, RunTransitions transitions, ActiveRuns activeRuns,
                       Function<Config.Project, Config.RunLimits> planLimits,
                       Function<Config.Project, Config.RunLimits> executeLimits, Worker worker, Runnable wakeScheduler) {
        this.db = db;
        this.projects = projects;
        this.transitions = transitions;
        this.activeRuns = activeRuns;
        this.planLimits = planLimits;
        this.executeLimits = executeLimits;
        this.worker = worker;
        this.wakeScheduler = wakeScheduler;
    }

    public void execute(ClaimedRun claimed) {
        ActiveRuns.ActiveRun active = activeRuns.register(claimed.taskId(), claimed.seq());
        try {
            job(claimed).ifPresent(job -> apply(job, worker.run(job, events(claimed), active)));
        } catch (RuntimeException e) {
            Log.error("run.crashed", e, "task", claimed.taskId(), "run", claimed.seq());
            failAfterCrash(claimed, e);
        } finally {
            activeRuns.unregister(active);
            wakeScheduler.run();
        }
    }

    /** The store writes a job cannot wait for: the next run needs both even when this one never finishes. */
    private JobEvents events(ClaimedRun claimed) {
        return new JobEvents() {

            @Override
            public void worktreeCreated(String worktree, String baseSha) {
                transitions.recordWorktree(claimed.taskId(), Path.of(worktree), baseSha);
            }

            @Override
            public void agentStarted(long pid, Instant processStart) {
                transitions.recordProcess(claimed.taskId(), claimed.seq(), pid, processStart);
            }
        };
    }

    /** Everything the worker needs, read here and only here; empty when the run already failed because its project is gone. */
    private Optional<Job> job(ClaimedRun claimed) {
        long taskId = claimed.taskId();
        int seq = claimed.seq();
        Task task = db.transactionReturning(tx -> Tasks.find(tx, taskId))
                .orElseThrow(() -> new IllegalStateException("claimed run for missing task " + taskId));
        Run run = db.transactionReturning(tx -> Runs.find(tx, taskId, seq))
                .orElseThrow(() -> new IllegalStateException("claimed run " + taskId + "." + seq + " is missing"));
        Optional<Config.Project> project = projects.byName(task.project());
        if (project.isEmpty()) {
            transitions.failed(taskId, seq, FailureReason.SETUP, "project " + task.project() + " is no longer configured", null);
            return Optional.empty();
        }
        return Optional.of(switch (claimed.kind()) {
            case PLAN -> planJob(task, run, project.get());
            case EXECUTE -> executeJob(task, run, project.get());
            case DELIVER -> deliverJob(task, run, project.get());
            case SPLIT -> throw new IllegalStateException("a split is never a task's run");
        });
    }

    private Job planJob(Task task, Run run, Config.Project project) {
        Config.RunLimits limits = planLimits.apply(project);
        // A task that already has a plan is being corrected: this run's instruction is the member's reply.
        String prompt = task.planJson() == null ? Prompts.plan(task) : Prompts.correction(task, run);
        return job(task, run, project, task.sessionId(), agentStartedBefore(task.id(), RunKind.PLAN, run.seq()), prompt,
                project.planModel(), project.planEffort(), limits.timeout().toMillis(), limits.budgetUsd(),
                attachments(task.id()), null);
    }

    private Job executeJob(Task task, Run run, Config.Project project) {
        Config.RunLimits limits = executeLimits.apply(project);
        boolean resume = agentStartedBefore(task.id(), RunKind.EXECUTE, run.seq());
        return job(task, run, project, buildSession(task), resume, executePrompt(task, run, resume), project.executeModel(),
                project.executeEffort(), limits.timeout().toMillis(), limits.budgetUsd(), attachments(task.id()), null);
    }

    /** A delivery run has no agent: it commits what the failed delivery left, with that run's summary as the body. */
    private Job deliverJob(Task task, Run run, Config.Project project) {
        return job(task, run, project, null, false, null, null, null, 0L, null, List.of(), run.instruction());
    }

    private Job job(Task task, Run run, Config.Project project, UUID sessionId, boolean resume, String prompt, String model,
                    String effort, long timeoutMillis, BigDecimal budgetUsd, List<Attachment> attachments,
                    String deliverySummary) {
        Job.Project on = new Job.Project(project.name(), project.repo(), project.path(), project.baseBranch(), project.agent(),
                project.copyFiles());
        return new Job(task.id(), run.seq(), run.kind(), on, task.baseBranch(), task.baseSha(),
                task.worktree() == null ? null : task.worktree().toString(), task.prUrl(), sessionId, resume, prompt, model,
                effort, timeoutMillis, budgetUsd, attachments, "dispatch #" + task.id() + ": " + task.title(), trailers(task),
                deliverySummary);
    }

    /**
     * A session that never ran (the first execution, or one whose earlier runs all failed before their agent started) gets
     * the approved plan; a resumed one is told only what this run adds.
     */
    private static String executePrompt(Task task, Run run, boolean resume) {
        if (!resume) {
            return Prompts.execute(task, task.planJson());
        }
        return switch (run.cause()) {
            case RETRY -> Prompts.retry(task, run.instruction());
            case FOLLOW_UP -> Prompts.followUp(task, run);
            default -> Prompts.execute(task, task.planJson());
        };
    }

    /** The delivery commit's trailers: who asked, and whoever approved the plan (later runs do not change that). */
    private List<String> trailers(Task task) {
        List<String> trailers = new ArrayList<>(List.of("Requested-by: " + task.requester().name()));
        db.transactionReturning(tx -> Runs.forTask(tx, task.id())).stream()
                .filter(run -> run.cause() == RunCause.APPROVAL && run.requestedByName() != null)
                .map(Run::requestedByName).findFirst()
                .ifPresent(approver -> trailers.add("Approved-by: " + approver));
        return trailers;
    }

    private List<Attachment> attachments(long taskId) {
        return db.transactionReturning(tx -> Attachments.forTask(tx, taskId));
    }

    private boolean agentStartedBefore(long taskId, RunKind kind, int seq) {
        return db.transactionReturning(tx -> Runs.agentStartedBefore(tx, taskId, kind, seq));
    }

    /** The first execution run starts the building session from the approved plan; later ones continue it (ADR 0017). */
    private UUID buildSession(Task task) {
        if (task.buildSessionId() != null) {
            return task.buildSessionId();
        }
        UUID session = UUID.randomUUID();
        transitions.recordBuildSession(task.id(), session);
        return session;
    }

    /** Exactly one transition per run. */
    private void apply(Job job, JobResult result) {
        switch (result.outcome()) {
            case CANCELLED -> transitions.cancelled(job.taskId(), job.seq(), result.agent());
            case FAILED -> transitions.failed(job.taskId(), job.seq(), result.failureReason(), result.failureDetail(),
                    result.agent());
            case SUCCEEDED -> succeeded(job, result);
        }
    }

    private void succeeded(Job job, JobResult result) {
        switch (job.kind()) {
            case PLAN -> finishPlan(job.taskId(), job.seq(), result.agent());
            case EXECUTE -> transitions.completed(job.taskId(), job.seq(), result.agent(), result.files(), result.prUrl());
            case DELIVER -> transitions.completed(job.taskId(), job.seq(), null, job.deliverySummary(), result.files(),
                    result.prUrl());
            case SPLIT -> throw new IllegalStateException("a split is never a task's run");
        }
    }

    /** The plan is parsed here, not by the worker: a worker's answer is checked before it becomes the task's plan. */
    private void finishPlan(long taskId, int seq, AgentResult result) {
        if (result.structuredOutput() == null) {
            transitions.failed(taskId, seq, FailureReason.AGENT, "agent finished without returning a plan", result);
            return;
        }
        try {
            transitions.planSucceeded(taskId, seq, Plan.parse(result.structuredOutput()), result);
        } catch (InvalidPlanException e) {
            transitions.failed(taskId, seq, FailureReason.AGENT, "plan did not match the schema: " + e.getMessage(), result);
        }
    }

    private void failAfterCrash(ClaimedRun claimed, RuntimeException cause) {
        try {
            transitions.failed(claimed.taskId(), claimed.seq(), FailureReason.INTERNAL,
                    "Dispatch error: " + cause.getMessage(), null);
        } catch (RuntimeException e) {
            // Storage itself is failing; the scheduler hits the same error and stops the process.
            Log.error("run.crash_not_recorded", e, "task", claimed.taskId(), "run", claimed.seq());
        }
    }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -B test -Dtest=CoordinatorTest`
Expected: PASS (6 tests). `RunExecutor` still exists and is still what `App` uses, so the rest of the suite is untouched.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/core/Coordinator.java src/test/java/dispatch/core/CoordinatorTest.java
git commit -m "Add the Coordinator: claim a run, build its job, apply its result

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 5: Run everything through the in-process worker and delete `RunExecutor`

**Files:**
- Modify: `src/main/java/dispatch/App.java` (import at line ~14, wiring at lines ~105-106, scheduler lambda at line ~133)
- Delete: `src/main/java/dispatch/core/RunExecutor.java`
- Modify: `src/test/java/dispatch/core/RunExecutorTest.java` (construction only: lines ~600-614 and the field at ~619)

**Interfaces:**
- Consumes: `new JobRunner(workspaces, delivery, agents, redactor, api::downloadFile)`, `new Coordinator(db, projects, transitions, activeRuns, config::planLimits, config::executeLimits, worker, schedulerSignal::wake)`, `Coordinator.execute(ClaimedRun)`.
- Produces: `RunExecutor` no longer exists; nothing else in `main` changes.

- [ ] **Step 1: Point the existing regression suite at the new classes and take the old one away.** This task's red is the compile break that leaves: `RunExecutorTest` — the milestone's regression test, 25 tests over real SQLite, real git and the fake agent — must pass through `Coordinator` + `JobRunner` with every assertion untouched.

In `src/test/java/dispatch/core/RunExecutorTest.java` replace the construction (lines ~600-614) with:

```java
        executorUnderTest = new Coordinator(db, projects, transitions, activeRuns,
                project -> new Config.RunLimits(planTimeout, new BigDecimal("2")),
                project -> new Config.RunLimits(Duration.ofSeconds(30), new BigDecimal("10")),
                new JobRunner(workspaces, delivery, Map.of("claude-code", agent()), Redactor.patternsOnly(),
                        (fileRef, target) -> {
                            byte[] content = sentFiles.get(fileRef);
                            if (content == null) {
                                throw new IllegalStateException("file " + fileRef + " is gone");
                            }
                            try {
                                Files.write(target, content);
                            } catch (IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        }),
                schedulerWakes::incrementAndGet);
```

and the field at line ~619 with:

```java
    private Coordinator executorUnderTest;
```

Change nothing else in the file: every assertion, helper, test name and the class name stay as they are. Then take the old path away:

```bash
git rm src/main/java/dispatch/core/RunExecutor.java
```

- [ ] **Step 2: Run the suite to see it fail**

Run: `./mvnw -q -B verify`
Expected: FAIL — `App.java:[14,19] cannot find symbol: class RunExecutor` (and the same at the wiring on line ~105): nothing compiles until `App` uses the in-process worker.

- [ ] **Step 3: Wire the in-process worker in `App`.** In `src/main/java/dispatch/App.java` replace the import

```java
import dispatch.core.RunExecutor;
```

with

```java
import dispatch.core.Coordinator;
import dispatch.core.JobRunner;
```

(keep the import block alphabetical: `Coordinator` goes before `DraftExpiry`, `JobRunner` before `Membership`.) Replace lines ~105-106:

```java
        // Personal mode runs the job in this process; a team member's own computer runs the same JobRunner (W-3).
        JobRunner jobRunner = new JobRunner(workspaces, delivery, agents, redactor, api::downloadFile);
        Coordinator coordinator = new Coordinator(db, projects, transitions, activeRuns, config::planLimits,
                config::executeLimits, jobRunner, schedulerSignal::wake);
```

and the scheduler's starter at line ~133:

```java
                        .start(app[0].guarded(() -> coordinator.execute(run))),
```

- [ ] **Step 4: Run the whole suite**

Run: `./mvnw -q -B verify`
Expected: PASS, including all of `RunExecutorTest`, `AppTest`, `RecoveryTest`, `SchedulerTest`, `ActiveRunsTest`, `SplitTest` and `TaskLifecycleTest`. If a `RunExecutorTest` assertion fails, the split changed behaviour: fix `Coordinator`/`JobRunner`, never the assertion.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/App.java src/main/java/dispatch/core/RunExecutor.java src/test/java/dispatch/core/RunExecutorTest.java
git commit -m "Run every run through the in-process worker and drop RunExecutor

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 6: Document the runtime split

**Files:**
- Modify: `docs/ARCHITECTURE.md` (lines 91, 104, 256, and the Background execution section at ~294-300)

**Interfaces:**
- Consumes: the behaviour of Tasks 1-5.
- Produces: docs only. No ADR (the workers decision is ADR 0021, recorded in W-4), no README or SECURITY change: nothing a member sees changed.

- [ ] **Step 1: Update the components diagram and table.** In line 91 replace the word `RunExecutor` with `Coordinator` and change nothing else — both are 11 characters, so the box keeps its width:

```
│ core       TaskService   Scheduler -> Coordinator   Recovery   │
```

In the `core` row of the table (line 104), replace "`RunExecutor` drives one run" with:

"`Coordinator` drives one run: it reads the store and the config into an immutable `Job`, hands it to a `Worker` — in this process `JobRunner`, which does the worktree, the agent and the delivery and touches no store — and applies the returned `JobResult` through `RunTransitions`"

- [ ] **Step 2: Fix the agent boundary line.** Line 256: replace "The timeout is enforced by `RunExecutor`" with "The timeout is enforced by `JobRunner`".

- [ ] **Step 3: Describe the worker boundary** in "## Background execution", after the bullet list that ends with "A claim is a conditional update, `QUEUED → RUNNING`.":

````markdown
**The worker boundary.** A claimed run is carried by the `Coordinator`, which is the only part that reads or writes the
store. It reads the task, the run, the project, this phase's limits, the attachments and whether the session's agent
already ran into one immutable `Job`, and applies the returned `JobResult` as exactly one transition.

```java
interface Worker { JobResult run(Job job, JobEvents events, ActiveRuns.ActiveRun control); }
interface JobEvents {
    void worktreeCreated(String worktree, String baseSha);   // recorded at once: the next run continues there
    void agentStarted(long pid, Instant processStart);       // recorded at once: orphan detection, session resume
}
```

`JobRunner` is the worker in this process: worktree, attachments, agent under its timeout, delivery. It is built with no
`Database`, `Projects` or `Config`, so the same class runs a job on a team member's own computer with HTTP in between
(W-2 for the split, W-3 for the remote workers). Cancelling reaches it through `control`: `ActiveRuns.stop` sets the run's
stop reason, the runner stops its agent (SIGTERM, 10 s grace, SIGKILL) and answers `CANCELLED`. A worker that throws is
the worker breaking: the run is logged as `run.crashed` and fails as `INTERNAL`.
````

- [ ] **Step 4: Check nothing still names the old class**

Run: `grep -rn "RunExecutor" README.md CONTEXT.md SECURITY.md docs/ARCHITECTURE.md docs/adr`
Expected: no output (the only mentions were the three lines above; the milestone specs and plans under `docs/superpowers/` keep their historical text).

- [ ] **Step 5: Commit**

```bash
git add docs/ARCHITECTURE.md
git commit -m "Document the Coordinator, the Worker boundary and the in-process JobRunner

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```
