# Verify Loop (VL) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An execution run tests its change with the project's `test` command, hands failures back to the building session, has a fresh reviewer check the diff against the approved plan, and delivers the draft PR with a Verification block — on by default, `loop: off` restores today's behaviour.

**Architecture:** A new `VerifyLoop` in `dispatch.core` runs between the agent's implementation and delivery inside `JobRunner.implement`, so the whole loop is one execution run (and runs on a team member's worker unchanged). `TestCommand` runs the test command inside the agent sandbox (`Confinement.wrap`). A new `RunKind.REVIEW` makes each agent start a fresh read-only reviewer with a review schema. The result's `Verification` travels in `JobResult`, is appended to the delivery commit body (which is the PR body) and rendered under the Telegram result.

**Tech Stack:** Java 25, no framework (ADR 0002), JUnit 5, Jackson 2.22 (`Json.MAPPER`, FAIL_ON_UNKNOWN_PROPERTIES), SQLite, Maven wrapper `./mvnw`.

**Spec:** `docs/superpowers/specs/2026-09-30-verify-loop-design.md`

## Global Constraints

- Config: instance `loop: on | off` (missing = `on`; anything else refused); project `loop: on | off` overrides it; project `test: "<command line>"` (missing = no test step).
- Round limits are constants: `VerifyLoop.FIX_ROUNDS = 3` (shared by test fixes and review fixes), `VerifyLoop.REVIEW_ROUNDS = 1`.
- Test step: `sh -c <test>` (Windows `cmd /c <test>`) in the worktree, inside `Confinement.wrap`, 10-minute limit, output to `<logBase>.test-<n>.log`, tail of at most 4096 bytes handed back.
- Reviewer: fresh session, read-only like PLAN, review schema `src/main/resources/review-schema.json`, diff from the run's start commit capped at 60 000 characters (with a truncation note). Model and effort: the execution's (a Job carries one phase's model — spec amended in Task 8).
- Budget: `limits.execute.budgetUsd` is the whole run's; each agent call gets what is left; the loop stops when less than 5% is left ("stopped: budget"). `null` budget = no budget checks.
- Time: `limits.execute.timeout` is the whole run's; a test step needs 12 min left (10 + 2), an agent step 5 min; otherwise the loop stops ("stopped: time").
- The draft PR is always delivered after the loop; a reviewer failure never blocks delivery; cancel still returns CANCELLED with no delivery.
- `loop: off`, and `DELIVER` runs, take exactly today's path.
- Telegram text is Mongolian (`messages_mn.properties`); the commit/PR block is English.
- Commits: one sentence describing the behaviour, ending with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`. Branch `verify-loop` (stacked on `agent-sandbox`); never push.

## Review Focus

1. **A team worker rebuilding the Job** (`WorkerLoop` copies every field by hand) and dropping `test`, `loop` or `reviewPrompt`: the loop would silently be off on every team member's computer. Pinned in Task 2 (`workerKeepsTheLoopSettingsOfTheJob`).
2. **A follow-up execution**: its reviewer must judge the follow-up instruction, not only the original plan. Pinned in Task 2 (`followUpReviewPromptCarriesTheInstruction`).
3. **A test command that prints megabytes** or **leaves a background child running** (Gradle daemon, a dev server): no out-of-memory, no hang reading a pipe. Pinned in Task 4 (`hugeOutputKeepsOnlyTheTail`, `aLeftoverChildDoesNotHangTheStep`).
4. **A reviewer answering with prose, a fenced block or broken JSON**: review is "failed" and the PR is still delivered. Pinned in Task 3 (`Review.parse` tests) and Task 5 (`aFailingReviewerNeverBlocksDelivery`).
5. **Cancel during a test step**: the test process tree is ended within about a second and the run ends CANCELLED without delivery. Pinned in Task 4 (`aStopRequestEndsTheTestTree`) and Task 6 (`cancelDuringTheLoopDeliversNothing`).

---

### Task 1: The `loop` and `test` settings

**Files:**
- Modify: `src/main/java/dispatch/config/Config.java` (record `Project`, ~line 191)
- Modify: `src/main/java/dispatch/config/ConfigLoader.java` (`load` ~line 64, `validateProjects` ~line 361-418, `ConfigFile` ~line 459)
- Modify: `src/main/resources/texts_en.properties`, `src/main/resources/texts_mn.properties`
- Test: `src/test/java/dispatch/config/ConfigLoaderTest.java`

**Interfaces:**
- Produces: `Config.Project` gains two LAST components `String test, String loop`; a convenience constructor with the 12 existing components passes `(null, null)`. After `ConfigLoader.load`, every project's `loop` is `"on"` or `"off"` (never null). `boolean Config.Project.loopOn()` returns `"on".equals(loop)`.

- [ ] **Step 1: Write the failing tests** (in `ConfigLoaderTest`; `VALID` is its minimal valid config with one project `autoland-management` under `projects:`, `write(yaml)` saves it, `ENV` holds the token — read the constant first to place the new keys correctly under the project item)

```java
    @Test
    void loopIsOnByDefaultAndAProjectMayTurnItOff() throws Exception {
        Config on = ConfigLoader.load(write(VALID), ENV);
        assertTrue(on.projects().getFirst().loopOn());
        assertNull(on.projects().getFirst().test());

        Config off = ConfigLoader.load(write("loop: off\n" + VALID), ENV);
        assertFalse(off.projects().getFirst().loopOn());
    }

    @Test
    void aProjectsLoopAndTestOverrideTheInstance() throws Exception {
        String yaml = "loop: off\n" + VALID.replace("- name: autoland-management\n",
                "- name: autoland-management\n    loop: on\n    test: \"./mvnw -q test\"\n");

        Config.Project project = ConfigLoader.load(write(yaml), ENV).projects().getFirst();

        assertTrue(project.loopOn());
        assertEquals("./mvnw -q test", project.test());
    }

    @Test
    void anUnknownLoopValueIsRefused() throws Exception {
        Path file = write("loop: sometimes\n" + VALID);

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(file, ENV));

        assertTrue(error.getMessage().contains("loop: on or off, not sometimes"), error.getMessage());
    }

    @Test
    void aBlankTestCommandIsRefused() throws Exception {
        Path file = write(VALID.replace("- name: autoland-management\n", "- name: autoland-management\n    test: \"  \"\n"));

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(file, ENV));

        assertTrue(error.getMessage().contains("test: a command line, not blank"), error.getMessage());
    }
```

If `VALID`'s project item is indented differently than `"- name: autoland-management\n"` followed by 4-space keys, adapt the two `replace(...)` strings to its real indentation (keep the inserted keys aligned with the item's other keys).

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -Dtest=ConfigLoaderTest test`
Expected: compilation FAIL (`cannot find symbol: method loopOn()`).

- [ ] **Step 3: Implement**

`Config.Project` — add the components, the convenience constructor and the helper:

```java
    public record Project(
            String name,
            String alias,
            String repo,
            String path,
            String baseBranch,
            String agent,
            String model,
            String effort,
            List<String> copyFiles,
            Limits limits,
            PhaseSettings plan,
            PhaseSettings execute,
            /** Run by Dispatch in the worktree after each execution (verify loop); null for no test step. */
            String test,
            /** "on" or "off" once loaded: the project's own setting, else the instance's (spec: verify loop). */
            String loop) {

        /** A project as it was before the verify loop: no test command, loop unresolved. */
        public Project(String name, String alias, String repo, String path, String baseBranch, String agent, String model,
                       String effort, List<String> copyFiles, Limits limits, PhaseSettings plan, PhaseSettings execute) {
            this(name, alias, repo, path, baseBranch, agent, model, effort, copyFiles, limits, plan, execute, null, null);
        }

        public boolean loopOn() {
            return "on".equals(loop);
        }
```
(keep every existing method of `Project` unchanged below).

`ConfigLoader.ConfigFile` — add `String loop` as its last component.

`ConfigLoader.load` — before `validateProjects`:

```java
        String loop = raw.loop() == null ? "on" : raw.loop();
        if (!loop.equals("on") && !loop.equals("off")) {
            errors.add(Text.of("config.loop", "loop", raw.loop()));
        }
```
and pass `loop` into `validateProjects(raw.projects(), agents, loop, errors)` (add the parameter).

`ConfigLoader.validateProjects` — for each project, next to the existing checks (`at` is the project's error prefix there):

```java
            String projectLoop = project.loop() == null ? instanceLoop : project.loop();
            if (!projectLoop.equals("on") && !projectLoop.equals("off")) {
                errors.add(Text.of("config.loop", at + ".loop", project.loop()));
            }
            if (project.test() != null && project.test().isBlank()) {
                errors.add(Text.of("config.testBlank", at + ".test"));
            }
```
and build the normalized project with the two new arguments `project.test(), projectLoop`.

`texts_en.properties`:
```
config.loop={0}: on or off, not {1}
config.testBlank={0}: a command line, not blank
```
`texts_mn.properties`:
```
config.loop={0}: on эсвэл off байна, {1} биш
config.testBlank={0}: командын мөр байна, хоосон биш
```

- [ ] **Step 4: Run the tests, then the full suite**

Run: `./mvnw -Dtest=ConfigLoaderTest test` — Expected: PASS.
Run: `./mvnw test 2>&1 | grep -E "Tests run:|FAIL|ERROR" | tail -5` — Expected: 0 failures (every other `new Config.Project(` uses the 12-arg constructor).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/config src/main/resources/texts_en.properties src/main/resources/texts_mn.properties src/test/java/dispatch/config/ConfigLoaderTest.java
git commit -m "A project may name a test command and the verify loop is on unless the instance or the project says loop: off

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: The Job carries the loop settings and the review prompt

**Files:**
- Modify: `src/main/java/dispatch/core/Job.java` (record `Job`, record `Job.Project` ~line 78)
- Modify: `src/main/java/dispatch/core/Prompts.java` (add `review`)
- Modify: `src/main/java/dispatch/core/Coordinator.java:124-146` (`executeJob`, `newJob`)
- Modify: `src/main/java/dispatch/worker/WorkerLoop.java:212-217`
- Test: `src/test/java/dispatch/core/JobJsonTest.java`, `src/test/java/dispatch/core/CoordinatorTest.java`, `src/test/java/dispatch/worker/WorkerLoopTest.java`

**Interfaces:**
- Consumes: `Config.Project.test()`, `Config.Project.loopOn()` (Task 1).
- Produces:
  - `Job.Project` gains LAST components `@JsonInclude(NON_NULL) String test, @JsonInclude(NON_NULL) Boolean loop`; the old 6-arg constructor passes `(null, null)`. `boolean Job.Project.loopOn()` = `Boolean.TRUE.equals(loop)` (null — a job from an older team machine — means off, i.e. today's behaviour).
  - `Job` gains a LAST component `@JsonInclude(NON_NULL) String reviewPrompt` (null unless an EXECUTE job with the loop on); the old constructor (20 args) passes `null`.
  - `static String Prompts.review(Task task, String planJson, String instruction)` — `instruction` null for the first execution, the follow-up/retry instruction otherwise. It ends with the line `The change to review:` so the worker appends the diff after it.

- [ ] **Step 1: Write the failing tests**

`JobJsonTest`:

```java
    @Test
    void aJobWithTheLoopSurvivesJsonUnchanged() throws Exception {
        Job job = new Job(7, 2, RunKind.EXECUTE,
                new Job.Project("alm", "git@github.com:acme/alm.git", "/home/ann/work/alm", "main", "claude-code", List.of(),
                        "./mvnw -q test", true),
                "main", "6f3030a", "/var/lib/dispatch/worktrees/7", null, UUID.fromString("11111111-2222-3333-4444-555555555555"),
                false, "Implement the approved plan", "opus", "low", 1_800_000L, new BigDecimal("2.50"), List.of(),
                "dispatch #7: Fix the login timeout", List.of("Requested-by: Bold"), null, null, "Review this change");

        assertEquals(job, Json.MAPPER.readValue(Json.write(job), Job.class));
    }

    @Test
    void aJobFromAnOlderTeamMachineHasTheLoopOff() throws Exception {
        Job old = new Job(7, 2, RunKind.EXECUTE,
                new Job.Project("alm", "git@github.com:acme/alm.git", null, "main", "claude-code", List.of()),
                "main", "6f3030a", "/w/7", null, UUID.randomUUID(), false, "p", null, null, 1L, null, List.of(), "s", List.of(),
                null, null);

        Job read = Json.MAPPER.readValue(Json.write(old), Job.class);

        assertFalse(read.project().loopOn());
        assertNull(read.reviewPrompt());
        assertFalse(Json.write(old).contains("\"loop\""), "an older worker must still read a job without the loop");
    }
```

`CoordinatorTest` — read the class first: it builds a `Coordinator` with a recording `Worker` and a project list, drives a task to an approved execution, and inspects the `Job` the worker received (look for an existing test that asserts on `job.prompt()` or `job.model()` of an EXECUTE job and copy its setup). Add two tests:

```java
    @Test
    void anExecutionJobCarriesTheLoopTheTestCommandAndAReviewPrompt() {
        // Setup as in the existing EXECUTE-job test, with the project built by the 14-arg Config.Project
        // constructor: test "./mvnw -q test", loop "on".
        Job job = /* the EXECUTE job the recording worker received */;

        assertTrue(job.project().loopOn());
        assertEquals("./mvnw -q test", job.project().test());
        assertTrue(job.reviewPrompt().contains("<plan>"), job.reviewPrompt());
        assertTrue(job.reviewPrompt().strip().endsWith("The change to review:"), job.reviewPrompt());
    }

    @Test
    void followUpReviewPromptCarriesTheInstruction() {
        // Setup: a completed task, then a follow-up "Also log the timeout" (as the existing follow-up test does).
        Job job = /* the follow-up EXECUTE job */;

        assertTrue(job.reviewPrompt().contains("Also log the timeout"), job.reviewPrompt());
    }

    @Test
    void loopOffSendsNoReviewPrompt() {
        // Same as the first test with loop "off".
        assertNull(job.reviewPrompt());
        assertFalse(job.project().loopOn());
    }
```
Replace each `/* … */` with the two or three lines the existing EXECUTE-job test in `CoordinatorTest` uses to obtain the job; if no such test exists, report NEEDS_CONTEXT instead of inventing a harness.

`WorkerLoopTest` — read it first; it hands a `Job` from a fake team to the loop and captures the `Job` the local runner receives (with the member's own `path`). Add:

```java
    @Test
    void workerKeepsTheLoopSettingsOfTheJob() {
        // The team's job: the existing test's job, with Job.Project(…, "./mvnw -q test", true) and reviewPrompt "Review it".
        Job ran = /* the job the local runner received, as the existing path-replacement test captures it */;

        assertEquals("./mvnw -q test", ran.project().test());
        assertTrue(ran.project().loopOn());
        assertEquals("Review it", ran.reviewPrompt());
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -Dtest='JobJsonTest,CoordinatorTest,WorkerLoopTest' test`
Expected: compilation FAIL (the new constructors do not exist).

- [ ] **Step 3: Implement**

`Job.Project`:

```java
    public record Project(String name, String repo, String path, String baseBranch, String agent, List<String> copyFiles,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String test,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Boolean loop) {

        public Project {
            copyFiles = copyFiles == null ? List.of() : List.copyOf(copyFiles);
        }

        /** A project as a team machine from before the verify loop sends it: no test, loop off. */
        public Project(String name, String repo, String path, String baseBranch, String agent, List<String> copyFiles) {
            this(name, repo, path, baseBranch, agent, copyFiles, null, null);
        }

        /** Null — a job from an older team machine — keeps today's behaviour. */
        public boolean loopOn() {
            return Boolean.TRUE.equals(loop);
        }
    }
```

`Job`: add `@JsonInclude(JsonInclude.Include.NON_NULL) String reviewPrompt` after `branch`, document it (`@param reviewPrompt the reviewer's prompt without the diff; null unless an EXECUTE job with the verify loop on`), and add a constructor with the 20 previous components that passes `null`.

`Prompts.review`:

```java
    /**
     * The verify loop's reviewer: a fresh, read-only session that judges the change against what was approved. The worker
     * appends the diff after the last line.
     *
     * @param instruction a follow-up's or retry's instruction; null for the first execution of the approved plan
     */
    static String review(Task task, String planJson, String instruction) {
        String asked = instruction == null ? "" : """

                Since then the team asked for this, which the change must also do:
                <instruction>
                %s
                </instruction>
                """.formatted(instruction);
        return """
                You review a change another agent made in this repository for task #%d from %s. You change nothing: read \
                the code and the diff, and answer only through the structured output.

                <task>
                %s
                </task>

                The approved plan:
                <plan>
                %s
                </plan>
                %s
                Report a finding as "blocking" only when the change is wrong, unsafe, breaks something, or misses part of \
                the plan; everything else is "minor". Answer "ok" when nothing is blocking. At most 20 findings.

                The change to review:
                """.formatted(task.id(), task.requester().name(), task.description(), planJson, asked);
    }
```

`Coordinator.executeJob`: compute the review prompt when the project's loop is on and pass the project's settings:

```java
    private Job executeJob(Task task, Run run, Config.Project project) {
        Config.RunLimits limits = executeLimits.apply(project);
        boolean resume = agentStartedBefore(task.id(), RunKind.EXECUTE, run.seq());
        String reviewPrompt = project.loopOn() ? Prompts.review(task, task.planJson(), run.instruction()) : null;
        return newJob(task, run, project, buildSession(task), resume, executePrompt(task, run, resume), project.executeModel(),
                project.executeEffort(), limits.timeout().toMillis(), limits.budgetUsd(), attachments(task.id()), null,
                reviewPrompt);
    }
```
Add the `reviewPrompt` parameter to `newJob` (planJob and deliverJob pass `null`), build `Job.Project` with `project.test(), project.loopOn()` as its last two arguments, and pass `reviewPrompt` as the Job's last argument. Check what `run.instruction()` holds for the first execution of an approved plan (it may be the plan JSON itself — read `Prompts.execute`'s caller `executePrompt`); if it is not null for the first execution, pass `null` in that case so the reviewer does not see the plan twice.

`WorkerLoop` (~line 212): copy the two new project fields and the review prompt:

```java
        Job.Project project = new Job.Project(job.project().name(), job.project().repo(), mine.path(),
                job.project().baseBranch(), job.project().agent(), job.project().copyFiles(), job.project().test(),
                job.project().loop());
        return Optional.of(new Job(job.taskId(), job.seq(), job.kind(), project, job.baseBranch(), job.baseSha(),
                job.worktree(), job.prUrl(), job.sessionId(), job.resume(), job.prompt(), model, effort,
                job.timeoutMillis(), job.budgetUsd(), job.attachments(), job.commitSubject(), job.commitTrailers(),
                job.deliverySummary(), job.branch(), job.reviewPrompt()));
```

- [ ] **Step 4: Run the tests, then the full suite**

Run: `./mvnw -Dtest='JobJsonTest,CoordinatorTest,WorkerLoopTest' test` — Expected: PASS.
Run: `./mvnw test 2>&1 | grep -E "Tests run:|FAIL|ERROR" | tail -5` — Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/core/Job.java src/main/java/dispatch/core/Prompts.java src/main/java/dispatch/core/Coordinator.java src/main/java/dispatch/worker/WorkerLoop.java src/test/java
git commit -m "An execution job carries its project's test command, whether the verify loop is on, and the reviewer's prompt, and a team worker passes them on

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: The reviewer run kind and its answer

**Files:**
- Create: `src/main/resources/review-schema.json`
- Create: `src/main/java/dispatch/core/Review.java`
- Modify: `src/main/java/dispatch/domain/RunKind.java` (add `REVIEW`)
- Modify: `src/main/java/dispatch/agent/Schemas.java` (add `REVIEW`)
- Modify: `src/main/java/dispatch/agent/claude/ClaudeCodeAgent.java` (`permissionMode`, `commandLine`)
- Modify: `src/main/java/dispatch/agent/codex/CodexAgent.java` (structured kinds, schema file)
- Modify: `src/main/java/dispatch/agent/gemini/GeminiAgent.java` (structured kinds, answer instruction)
- Modify: every other exhaustive `switch` over `RunKind` the compiler reports (e.g. `JobRunner.run`, `Coordinator.job`): `REVIEW` is never a task's run → the same `IllegalStateException` branch as `SPLIT, ASSISTANT`.
- Test: `src/test/java/dispatch/core/ReviewTest.java`, `ClaudeCodeAgentTest`, `CodexAgentTest`, `GeminiAgentTest`

**Interfaces:**
- Produces:
  - `RunKind.REVIEW` — started by agents like PLAN (read-only, structured answer), never stored as a task's run.
  - `Schemas.REVIEW` (the schema JSON as a one-line string).
  - `record Review(String verdict, List<Review.Finding> findings)` with `record Finding(String severity, String file, int line, String text)`, `static Review parse(String json)` (throws `IllegalArgumentException` with a reason on anything not matching), `List<Finding> blocking()`, `List<Finding> minor()`.

- [ ] **Step 1: Write the schema**

`src/main/resources/review-schema.json`:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["verdict", "findings"],
  "properties": {
    "verdict": {"type": "string", "enum": ["ok", "changes"]},
    "findings": {
      "type": "array",
      "maxItems": 20,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["severity", "file", "line", "text"],
        "properties": {
          "severity": {"type": "string", "enum": ["blocking", "minor"]},
          "file": {"type": "string"},
          "line": {"type": "integer"},
          "text": {"type": "string", "maxLength": 500}
        }
      }
    }
  }
}
```

- [ ] **Step 2: Write the failing tests**

`src/test/java/dispatch/core/ReviewTest.java`:

```java
package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewTest {

    @Test
    void readsVerdictAndSplitsBlockingFromMinor() {
        Review review = Review.parse("""
                {"verdict":"changes","findings":[
                  {"severity":"blocking","file":"src/A.java","line":12,"text":"NPE when x is null"},
                  {"severity":"minor","file":"src/B.java","line":0,"text":"name"}]}""");

        assertEquals("changes", review.verdict());
        assertEquals(List.of(new Review.Finding("blocking", "src/A.java", 12, "NPE when x is null")), review.blocking());
        assertEquals(1, review.minor().size());
    }

    @Test
    void okWithoutFindings() {
        Review review = Review.parse("{\"verdict\":\"ok\",\"findings\":[]}");

        assertTrue(review.blocking().isEmpty());
    }

    @Test
    void anAnswerInsideAJsonFenceIsRead() {
        // Gemini answers in text; its parser already unwraps fences for plans, but the loop must not depend on it.
        Review review = Review.parse("```json\n{\"verdict\":\"ok\",\"findings\":[]}\n```");

        assertEquals("ok", review.verdict());
    }

    @Test
    void anythingElseIsRefusedWithAReason() {
        assertThrows(IllegalArgumentException.class, () -> Review.parse(null));
        assertThrows(IllegalArgumentException.class, () -> Review.parse("Looks good to me!"));
        assertThrows(IllegalArgumentException.class, () -> Review.parse("{\"verdict\":\"maybe\",\"findings\":[]}"));
        assertThrows(IllegalArgumentException.class,
                () -> Review.parse("{\"verdict\":\"ok\",\"findings\":[{\"severity\":\"huge\",\"file\":\"a\",\"line\":1,\"text\":\"t\"}]}"));
    }
}
```

`ClaudeCodeAgentTest` (its `agent` and `workdir`/`dir`/`SESSION` fields exist; add imports as needed):

```java
    @Test
    void reviewRunReadsOnlyInAFreshSessionAndAnswersWithTheReviewSchema() throws Exception {
        UUID reviewSession = UUID.randomUUID();
        RunRequest request = new RunRequest(RunKind.REVIEW, workdir, "Review this change", reviewSession, false, List.of(),
                new BigDecimal("1"), "sonnet", null, dir.resolve("runs/1/1.review"));

        agent.start(request).await();

        List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));
        assertTrue(args.containsAll(List.of("--permission-mode", "plan")), args.toString());
        assertEquals("Read,Bash", args.get(args.indexOf("--tools") + 1));
        assertEquals(Schemas.REVIEW, args.get(args.indexOf("--json-schema") + 1));
        assertEquals(reviewSession.toString(), args.get(args.indexOf("--session-id") + 1));
        assertFalse(args.contains("--resume"));
    }
```

`CodexAgentTest` and `GeminiAgentTest`: read each class's existing PLAN test (it asserts the read-only sandbox / approval mode and the schema file or answer instruction) and add the same test for `RunKind.REVIEW`, asserting:
- Codex: `sandbox_mode="read-only"` and that the file passed to `--output-schema` contains `"verdict"` (the review schema without limits, via `Schemas.withoutLimits(Schemas.REVIEW)`).
- Gemini: `--approval-mode default` and that the `-p` argument contains `"verdict"` (the review schema).

- [ ] **Step 3: Run to verify they fail**

Run: `./mvnw -Dtest='ReviewTest,ClaudeCodeAgentTest,CodexAgentTest,GeminiAgentTest' test`
Expected: compilation FAIL (`REVIEW`, `Review`, `Schemas.REVIEW` missing).

- [ ] **Step 4: Implement**

`RunKind`: add `REVIEW` with a comment `/** The verify loop's reviewer inside an execution run; never a task's run of its own. */`.

`Schemas`: `public static final String REVIEW = Json.read(resource("/review-schema.json")).toString();`

`Review.java`:

```java
package dispatch.core;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The verify loop reviewer's answer (review-schema.json), checked here because not every agent enforces a schema. */
public record Review(String verdict, List<Finding> findings) {

    private static final Set<String> VERDICTS = Set.of("ok", "changes");
    private static final Set<String> SEVERITIES = Set.of("blocking", "minor");

    public Review {
        findings = List.copyOf(findings);
    }

    /** @param line 0 when the finding has no line */
    public record Finding(String severity, String file, int line, String text) {
    }

    public List<Finding> blocking() {
        return findings.stream().filter(finding -> finding.severity().equals("blocking")).toList();
    }

    public List<Finding> minor() {
        return findings.stream().filter(finding -> finding.severity().equals("minor")).toList();
    }

    /** @throws IllegalArgumentException with the reason when {@code answer} is not a review */
    public static Review parse(String answer) {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("the reviewer gave no answer");
        }
        String json = unfenced(answer.strip());
        JsonNode node;
        try {
            node = Json.read(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the reviewer's answer is not JSON: " + e.getMessage(), e);
        }
        String verdict = node.path("verdict").asText("");
        if (!VERDICTS.contains(verdict)) {
            throw new IllegalArgumentException("unknown verdict: " + verdict);
        }
        List<Finding> findings = new ArrayList<>();
        for (JsonNode item : node.path("findings")) {
            String severity = item.path("severity").asText("");
            if (!SEVERITIES.contains(severity)) {
                throw new IllegalArgumentException("unknown severity: " + severity);
            }
            findings.add(new Finding(severity, item.path("file").asText(""), item.path("line").asInt(0), item.path("text").asText("")));
        }
        return new Review(verdict, findings);
    }

    private static String unfenced(String answer) {
        if (!answer.startsWith("```")) {
            return answer;
        }
        int start = answer.indexOf('\n');
        int end = answer.lastIndexOf("```");
        return start < 0 || end <= start ? answer : answer.substring(start + 1, end).strip();
    }
}
```
(If `Json.read` throws a checked or differently named exception, catch that instead — read `dispatch/Json.java`.)

`ClaudeCodeAgent`:
- `private static final String REVIEW_SCHEMA = Schemas.REVIEW;`
- `permissionMode`: `case PLAN, SPLIT, REVIEW -> "plan";`
- `commandLine`'s kind switch: `case REVIEW -> args.addAll(List.of("--tools", "Read,Bash", "--json-schema", REVIEW_SCHEMA));` with the comment `// The verify loop's reviewer: read-only like a plan, its own schema (spec: verify loop).`

`CodexAgent`: where it decides the kind is structured (`case PLAN -> true;` ~line 67) add `REVIEW`; the read-only sandbox applies to it as to PLAN; `writeSchema` takes the schema to write — `PLAN_SCHEMA` for PLAN, a new `REVIEW_SCHEMA = Schemas.withoutLimits(Schemas.REVIEW)` for REVIEW; the error message says "cannot write the " + kind + " schema".

`GeminiAgent`: where PLAN is structured (~line 51) add `REVIEW`; the approval mode for REVIEW is PLAN's (`default`); the `-p` instruction for REVIEW is a new constant `REVIEW_ANSWER = "Answer with the review only: one JSON object, with no other text, that matches this JSON Schema: " + Schemas.REVIEW`.

Fix every other exhaustive switch over `RunKind` the compiler flags: add `REVIEW` to the existing `SPLIT, ASSISTANT` branch that throws "is never a task's run".

- [ ] **Step 5: Run the tests, then the full suite**

Run: `./mvnw -Dtest='ReviewTest,ClaudeCodeAgentTest,CodexAgentTest,GeminiAgentTest' test` — Expected: PASS.
Run: `./mvnw test 2>&1 | grep -E "Tests run:|FAIL|ERROR" | tail -5` — Expected: 0 failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/review-schema.json src/main/java src/test/java
git commit -m "Each agent can start a fresh read-only reviewer that answers with a verdict and blocking or minor findings, and Dispatch refuses any answer that is not one

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: TestCommand — the project's tests, sandboxed

**Files:**
- Create: `src/main/java/dispatch/core/TestRunner.java`
- Create: `src/main/java/dispatch/core/TestCommand.java`
- Test: `src/test/java/dispatch/core/TestCommandTest.java`

**Interfaces:**
- Consumes: `dispatch.agent.sandbox.Confinement.wrap(List<String>, RunRequest, List<String>)`; `dispatch.ProcessTrees.terminate(ProcessHandle, Duration)`.
- Produces:
  - `interface TestRunner { TestRun run(String commandLine, Path dir, Path log, Duration timeout, BooleanSupplier stopRequested); record TestRun(int exitCode, boolean timedOut, boolean stopped, String tail) { boolean passed() } }` — `passed()` = `exitCode == 0 && !timedOut && !stopped`.
  - `final class TestCommand implements TestRunner` — `new TestCommand(Confinement confinement)`; `static final int TAIL_BYTES = 4096`.

- [ ] **Step 1: Write the failing tests**

```java
package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.sandbox.Confinement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the commands are POSIX shell")
class TestCommandTest {

    @TempDir
    Path dir;
    private final TestCommand tests = new TestCommand(Confinement.none("test"));

    @Test
    void aPassingCommandPasses() throws Exception {
        TestRunner.TestRun run = tests.run("echo all good", dir, dir.resolve("t.log"), Duration.ofSeconds(10), () -> false);

        assertTrue(run.passed());
        assertEquals("all good", run.tail().strip());
        assertEquals("all good", Files.readString(dir.resolve("t.log")).strip());
    }

    @Test
    void aFailingCommandKeepsItsExitCodeAndOutput() {
        TestRunner.TestRun run = tests.run("echo boom >&2; exit 3", dir, dir.resolve("t.log"), Duration.ofSeconds(10), () -> false);

        assertFalse(run.passed());
        assertEquals(3, run.exitCode());
        assertTrue(run.tail().contains("boom"), run.tail());
    }

    @Test
    void hugeOutputKeepsOnlyTheTail() {
        TestRunner.TestRun run = tests.run("yes line | head -n 200000; echo LAST", dir, dir.resolve("t.log"),
                Duration.ofSeconds(30), () -> false);

        assertTrue(run.passed());
        assertTrue(run.tail().length() <= TestCommand.TAIL_BYTES, "tail " + run.tail().length());
        assertTrue(run.tail().strip().endsWith("LAST"));
    }

    @Test
    void aTimeoutEndsTheCommandAndItsChildren() {
        Instant start = Instant.now();

        TestRunner.TestRun run = tests.run("sleep 300 & sleep 300", dir, dir.resolve("t.log"), Duration.ofSeconds(1), () -> false);

        assertTrue(run.timedOut());
        assertFalse(run.passed());
        assertTrue(Duration.between(start, Instant.now()).toSeconds() < 20);
    }

    @Test
    void aStopRequestEndsTheTestTree() {
        AtomicBoolean stop = new AtomicBoolean();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            stop.set(true);
        });
        Instant start = Instant.now();

        TestRunner.TestRun run = tests.run("sleep 300", dir, dir.resolve("t.log"), Duration.ofMinutes(5), stop::get);

        assertTrue(run.stopped());
        assertTrue(Duration.between(start, Instant.now()).toSeconds() < 15);
    }

    @Test
    void aLeftoverChildDoesNotHangTheStep() {
        Instant start = Instant.now();

        // The shell exits at once; its background child keeps running (like a Gradle daemon) and must not hold the step.
        TestRunner.TestRun run = tests.run("(sleep 30 &) ; echo done", dir, dir.resolve("t.log"), Duration.ofMinutes(1), () -> false);

        assertTrue(run.passed());
        assertTrue(Duration.between(start, Instant.now()).toSeconds() < 10);
    }

    @Test
    void aCommandThatCannotStartFailsWithTheReason() {
        TestRunner.TestRun run = tests.run("echo x", dir.resolve("missing-dir"), dir.resolve("t.log"), Duration.ofSeconds(5), () -> false);

        assertFalse(run.passed());
        assertTrue(run.tail().contains("cannot run"), run.tail());
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -Dtest=TestCommandTest test`
Expected: compilation FAIL (`TestCommand` missing).

- [ ] **Step 3: Implement**

`TestRunner.java`:

```java
package dispatch.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Runs a project's test command for the verify loop; a fake stands in for it in VerifyLoop's tests. */
public interface TestRunner {

    /**
     * @param log           where the whole output goes
     * @param stopRequested polled while the command runs; true ends the command's whole process tree
     */
    TestRun run(String commandLine, Path dir, Path log, Duration timeout, BooleanSupplier stopRequested);

    /** @param tail the output's last {@link TestCommand#TAIL_BYTES} bytes, or why the command could not run */
    record TestRun(int exitCode, boolean timedOut, boolean stopped, String tail) {

        public boolean passed() {
            return exitCode == 0 && !timedOut && !stopped;
        }
    }
}
```

`TestCommand.java`:

```java
package dispatch.core;

import dispatch.Log;
import dispatch.ProcessTrees;
import dispatch.agent.RunRequest;
import dispatch.agent.sandbox.Confinement;
import dispatch.domain.RunKind;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The project's test command, in the worktree and inside this machine's sandbox like an agent (ADR 0032). The output
 * goes to a file, never a pipe: a child left running (a Gradle daemon) would keep a pipe open and hold the step.
 */
public final class TestCommand implements TestRunner {

    public static final int TAIL_BYTES = 4096;
    private static final Duration POLL = Duration.ofMillis(200);
    private static final Duration GRACE = Duration.ofSeconds(5);

    private final Confinement confinement;

    public TestCommand(Confinement confinement) {
        this.confinement = confinement;
    }

    @Override
    public TestRun run(String commandLine, Path dir, Path log, Duration timeout, BooleanSupplier stopRequested) {
        List<String> shell = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")
                ? List.of("cmd", "/c", commandLine)
                : List.of("sh", "-c", commandLine);
        // Sandboxed like the execution itself: writable worktree and caches, nothing of the agent's own state.
        RunRequest asRun = new RunRequest(RunKind.EXECUTE, dir, "", null, false, List.of(), null, null, null,
                Path.of(log.toString().replaceFirst("\\.log$", "")));
        Process process;
        try {
            Files.createDirectories(log.getParent());
            process = new ProcessBuilder(confinement.wrap(shell, asRun, List.of()))
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
        } catch (IOException | RuntimeException e) {
            return new TestRun(-1, false, false, "cannot run the test command: " + e);
        }
        process.getOutputStream().close();
        Log.info("verify.test_started", "pid", process.pid(), "dir", dir);
        Instant deadline = Instant.now().plus(timeout);
        try {
            while (!process.waitFor(POLL.toMillis(), TimeUnit.MILLISECONDS)) {
                if (stopRequested.getAsBoolean()) {
                    ProcessTrees.terminate(process.toHandle(), GRACE);
                    return new TestRun(-1, false, true, tail(log));
                }
                if (Instant.now().isAfter(deadline)) {
                    ProcessTrees.terminate(process.toHandle(), GRACE);
                    return new TestRun(-1, true, false, tail(log) + "\n(stopped after " + timeout.toMinutes() + " min)");
                }
            }
        } catch (InterruptedException e) {
            ProcessTrees.terminate(process.toHandle(), GRACE);
            Thread.currentThread().interrupt();
            return new TestRun(-1, false, true, tail(log));
        }
        return new TestRun(process.exitValue(), false, false, tail(log));
    }

    /** The last {@link #TAIL_BYTES} bytes of the log, read from its end so a huge log is never loaded. */
    static String tail(Path log) {
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long start = Math.max(0, file.length() - TAIL_BYTES);
            byte[] bytes = new byte[(int) (file.length() - start)];
            file.seek(start);
            file.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(output unavailable: " + e.getMessage() + ")";
        }
    }
}
```
Note `process.getOutputStream().close()` throws `IOException` — wrap it in a try/catch that ignores the failure with a comment ("the command reads no input"). If `ProcessTrees.terminate` does not wait for the tree to end, the timing assertions still hold (they allow 15–20 s).

- [ ] **Step 4: Run the tests, then the full suite**

Run: `./mvnw -Dtest=TestCommandTest test` — Expected: 7 PASS.
Run: `./mvnw test 2>&1 | grep -E "Tests run:|FAIL|ERROR" | tail -5` — Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/core/TestRunner.java src/main/java/dispatch/core/TestCommand.java src/test/java/dispatch/core/TestCommandTest.java
git commit -m "Dispatch can run a project's test command in the sandbox with a time limit, ending its whole process tree on timeout or cancel and keeping only the output's tail

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: VerifyLoop and Verification

**Files:**
- Create: `src/main/java/dispatch/core/Verification.java`
- Create: `src/main/java/dispatch/core/VerifyLoop.java`
- Modify: `src/main/java/dispatch/core/Prompts.java` (add `testFailure`, `reviewFindings`)
- Test: `src/test/java/dispatch/core/VerifyLoopTest.java`, `src/test/java/dispatch/core/VerificationTest.java`

**Interfaces:**
- Consumes: `TestRunner`, `TestRunner.TestRun` (Task 4); `Review`, `Review.Finding` (Task 3); `dispatch.agent.AgentResult`, `AgentOutcome`.
- Produces:
  - `record Verification(Tests tests, int testRuns, String testTail, ReviewState review, List<Review.Finding> findings, String reviewError, String stoppedBy)` with `enum Tests { PASSED, FAILING, NO_COMMAND, NOT_RUN }`, `enum ReviewState { OK, FINDINGS, FAILED, NOT_RUN }`, and `String block()` — the English plain-text block for the commit/PR body.
  - `final class VerifyLoop` with constants `FIX_ROUNDS = 3`, `REVIEW_ROUNDS = 1`, `TEST_TIMEOUT = Duration.ofMinutes(10)`, `TEST_STEP_NEEDS = Duration.ofMinutes(12)`, `AGENT_STEP_NEEDS = Duration.ofMinutes(5)`, `DIFF_LIMIT = 60_000`, `BUDGET_FLOOR = new BigDecimal("0.05")`;
    - `interface Agents { AgentResult fix(String prompt, BigDecimal budgetUsd, Duration timeout); AgentResult review(String prompt, BigDecimal budgetUsd, Duration timeout); String diff(); }`
    - `record Setup(String testCommand, Path worktree, Path logBase, String reviewPrompt, Instant deadline, BigDecimal budgetUsd, BigDecimal spentUsd)` (`budgetUsd` null = unlimited; `spentUsd` = what the implementation already cost, never null)
    - `record Outcome(Verification verification, List<AgentResult> runs)` — `runs` are the loop's own agent calls (fixes, review), in order.
    - `new VerifyLoop(TestRunner tests, Clock clock)`; `Outcome run(Setup setup, Agents agents, BooleanSupplier stopRequested)`.
  - `static String Prompts.testFailure(String commandLine, String tail)`, `static String Prompts.reviewFindings(List<Review.Finding> blocking)`.

- [ ] **Step 1: Write the failing tests**

`VerificationTest.java`:

```java
package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VerificationTest {

    @Test
    void passingTestsAndACleanReview() {
        Verification v = new Verification(Verification.Tests.PASSED, 2, "ok", Verification.ReviewState.OK, List.of(), null, null);

        assertEquals("""
                Verification
                - Tests: pass (run 2)
                - Review: ok""", v.block());
    }

    @Test
    void failingTestsShowTheTailAndLeftoverFindings() {
        Verification v = new Verification(Verification.Tests.FAILING, 4, "BUILD FAILURE\nFooTest", Verification.ReviewState.FINDINGS,
                List.of(new Review.Finding("minor", "src/A.java", 3, "rename x")), null, "budget");

        String block = v.block();

        assertTrue(block.contains("- Tests: failing after 4 runs"), block);
        assertTrue(block.contains("    BUILD FAILURE\n    FooTest"), block);
        assertTrue(block.contains("- Review: 1 finding left\n  - minor src/A.java:3 rename x"), block);
        assertTrue(block.contains("- Stopped early: budget"), block);
    }

    @Test
    void noTestCommandAndAFailedReviewer() {
        Verification v = new Verification(Verification.Tests.NO_COMMAND, 0, null, Verification.ReviewState.FAILED, List.of(),
                "the reviewer's answer is not JSON", null);

        assertTrue(v.block().contains("- Tests: no test command configured"));
        assertTrue(v.block().contains("- Review: failed (the reviewer's answer is not JSON)"));
    }
}
```

`VerifyLoopTest.java` — fakes for the test runner and the agents, a fixed clock:

```java
package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerifyLoopTest {

    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final Deque<Boolean> testResults = new ArrayDeque<>();
    private final List<String> calls = new ArrayList<>();
    private String reviewAnswer = "{\"verdict\":\"ok\",\"findings\":[]}";
    private AgentOutcome fixOutcome = AgentOutcome.SUCCEEDED;

    private final TestRunner tests = (command, dir, log, timeout, stop) -> {
        calls.add("test");
        boolean pass = testResults.isEmpty() || testResults.pop();
        return new TestRunner.TestRun(pass ? 0 : 1, false, false, pass ? "ok" : "FooTest failed");
    };

    private final VerifyLoop.Agents agents = new VerifyLoop.Agents() {
        @Override
        public AgentResult fix(String prompt, BigDecimal budgetUsd, Duration timeout) {
            calls.add("fix");
            return result(fixOutcome, null, new BigDecimal("0.10"));
        }

        @Override
        public AgentResult review(String prompt, BigDecimal budgetUsd, Duration timeout) {
            calls.add("review");
            return result(AgentOutcome.SUCCEEDED, reviewAnswer, new BigDecimal("0.05"));
        }

        @Override
        public String diff() {
            return "diff --git a/x b/x";
        }
    };

    private VerifyLoop.Outcome run(String testCommand, Duration timeLeft, BigDecimal budget, BigDecimal spent) {
        VerifyLoop.Setup setup = new VerifyLoop.Setup(testCommand, Path.of("/w/7"), Path.of("/s/runs/7/2"), "Review this:",
                NOW.plus(timeLeft), budget, spent);
        return new VerifyLoop(tests, clock).run(setup, agents, () -> false);
    }

    private VerifyLoop.Outcome run() {
        return run("./mvnw -q test", Duration.ofMinutes(60), new BigDecimal("10"), new BigDecimal("1"));
    }

    @Test
    void greenAtOnceThenACleanReview() {
        VerifyLoop.Outcome outcome = run();

        assertEquals(List.of("test", "review"), calls);
        assertEquals(Verification.Tests.PASSED, outcome.verification().tests());
        assertEquals(1, outcome.verification().testRuns());
        assertEquals(Verification.ReviewState.OK, outcome.verification().review());
        assertEquals(1, outcome.runs().size());
    }

    @Test
    void redThenGreenAfterOneFix() {
        testResults.addAll(List.of(false, true));

        VerifyLoop.Outcome outcome = run();

        assertEquals(List.of("test", "fix", "test", "review"), calls);
        assertEquals(Verification.Tests.PASSED, outcome.verification().tests());
        assertEquals(2, outcome.verification().testRuns());
    }

    @Test
    void stillRedAfterThreeFixesIsDeliveredAsFailing() {
        testResults.addAll(List.of(false, false, false, false));

        VerifyLoop.Outcome outcome = run();

        assertEquals(List.of("test", "fix", "test", "fix", "test", "fix", "test", "review"), calls);
        assertEquals(Verification.Tests.FAILING, outcome.verification().tests());
        assertEquals("FooTest failed", outcome.verification().testTail());
    }

    @Test
    void blockingFindingsAreFixedAndRetested() {
        reviewAnswer = "{\"verdict\":\"changes\",\"findings\":[{\"severity\":\"blocking\",\"file\":\"A.java\",\"line\":1,\"text\":\"NPE\"},"
                + "{\"severity\":\"minor\",\"file\":\"B.java\",\"line\":2,\"text\":\"name\"}]}";

        VerifyLoop.Outcome outcome = run();

        assertEquals(List.of("test", "review", "fix", "test"), calls);
        // Only the minor finding is left: the blocking one was fixed and the tests pass again.
        assertEquals(Verification.ReviewState.FINDINGS, outcome.verification().review());
        assertEquals(List.of(new Review.Finding("minor", "B.java", 2, "name")), outcome.verification().findings());
    }

    @Test
    void aFailingReviewerNeverBlocksDelivery() {
        reviewAnswer = "Looks fine to me";

        VerifyLoop.Outcome outcome = run();

        assertEquals(Verification.ReviewState.FAILED, outcome.verification().review());
        assertTrue(outcome.verification().reviewError().contains("not JSON"), outcome.verification().reviewError());
    }

    @Test
    void noTestCommandGoesStraightToReview() {
        VerifyLoop.Outcome outcome = run(null, Duration.ofMinutes(60), new BigDecimal("10"), BigDecimal.ONE);

        assertEquals(List.of("review"), calls);
        assertEquals(Verification.Tests.NO_COMMAND, outcome.verification().tests());
    }

    @Test
    void tooLittleTimeLeftStopsBeforeTheTests() {
        VerifyLoop.Outcome outcome = run("./mvnw -q test", Duration.ofMinutes(11), new BigDecimal("10"), BigDecimal.ONE);

        assertEquals(List.of(), calls);
        assertEquals(Verification.Tests.NOT_RUN, outcome.verification().tests());
        assertEquals("time", outcome.verification().stoppedBy());
    }

    @Test
    void anAlmostSpentBudgetStopsBeforeTheNextAgentCall() {
        testResults.add(false);

        // 9.60 of 10 spent: 0.40 left is under 5% of 10 (0.50).
        VerifyLoop.Outcome outcome = run("./mvnw -q test", Duration.ofMinutes(60), new BigDecimal("10"), new BigDecimal("9.60"));

        assertEquals(List.of("test"), calls);
        assertEquals(Verification.Tests.FAILING, outcome.verification().tests());
        assertEquals("budget", outcome.verification().stoppedBy());
    }

    @Test
    void aFixThatFailsStopsTheLoopWithTheError() {
        testResults.add(false);
        fixOutcome = AgentOutcome.FAILED;

        VerifyLoop.Outcome outcome = run();

        assertEquals(List.of("test", "fix"), calls);
        assertTrue(outcome.verification().stoppedBy().startsWith("fix failed"), outcome.verification().stoppedBy());
    }

    private static AgentResult result(AgentOutcome outcome, String structured, BigDecimal cost) {
        return new AgentResult(outcome, outcome == AgentOutcome.SUCCEEDED ? 0 : 1, "s", structured, "done", cost, 1, List.of(),
                outcome == AgentOutcome.SUCCEEDED ? null : "agent broke", null, null);
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -Dtest='VerifyLoopTest,VerificationTest' test`
Expected: compilation FAIL.

- [ ] **Step 3: Implement**

`Prompts` additions:

```java
    /** The verify loop hands a red test run back to the building session. */
    static String testFailure(String commandLine, String tail) {
        return """
                Dispatch ran the project's tests after your change and they failed:

                $ %s
                <output>
                %s
                </output>

                Find the cause and fix it in this repository. If a test is wrong rather than the code, fix the test and \
                say why. Do not commit. End with a short summary of what you changed.
                """.formatted(commandLine, tail);
    }

    /** The verify loop hands the reviewer's blocking findings back to the building session. */
    static String reviewFindings(List<Review.Finding> blocking) {
        StringBuilder list = new StringBuilder();
        for (Review.Finding finding : blocking) {
            list.append("- ").append(finding.file()).append(finding.line() > 0 ? ":" + finding.line() : "")
                    .append(": ").append(finding.text()).append('\n');
        }
        return """
                A reviewer checked your change against the approved plan and found problems that must be fixed:

                %s
                Fix them in this repository. Do not commit. End with a short summary of what you changed.
                """.formatted(list);
    }
```

`Verification.java`:

```java
package dispatch.core;

import java.util.List;

/**
 * What the verify loop found before delivery; written into the delivery commit (the PR body) and the Telegram result.
 *
 * @param testRuns    how many times the test command ran
 * @param testTail    the last run's output tail; null when the tests never ran
 * @param reviewError why the reviewer gave no usable answer; null otherwise
 * @param stoppedBy   "budget", "time", or "fix failed: <reason>" when the loop stopped early; null otherwise
 */
public record Verification(Tests tests, int testRuns, String testTail, ReviewState review, List<Review.Finding> findings,
                           String reviewError, String stoppedBy) {

    public enum Tests { PASSED, FAILING, NO_COMMAND, NOT_RUN }

    public enum ReviewState { OK, FINDINGS, FAILED, NOT_RUN }

    public Verification {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /** The English block for the commit body; the tail is indented so Markdown shows it as code. */
    public String block() {
        StringBuilder out = new StringBuilder("Verification\n");
        out.append(switch (tests) {
            case PASSED -> "- Tests: pass (run " + testRuns + ")";
            case FAILING -> "- Tests: failing after " + testRuns + " runs\n\n" + indented(testTail) + "\n";
            case NO_COMMAND -> "- Tests: no test command configured";
            case NOT_RUN -> "- Tests: not run";
        });
        out.append('\n').append(switch (review) {
            case OK -> "- Review: ok";
            case FINDINGS -> "- Review: " + findings.size() + (findings.size() == 1 ? " finding" : " findings") + " left" + listed();
            case FAILED -> "- Review: failed (" + reviewError + ")";
            case NOT_RUN -> "- Review: not run";
        });
        if (stoppedBy != null) {
            out.append("\n- Stopped early: ").append(stoppedBy);
        }
        return out.toString();
    }

    private String listed() {
        StringBuilder out = new StringBuilder();
        for (Review.Finding finding : findings) {
            out.append("\n  - ").append(finding.severity()).append(' ').append(finding.file())
                    .append(finding.line() > 0 ? ":" + finding.line() : "").append(' ').append(finding.text());
        }
        return out.toString();
    }

    private static String indented(String text) {
        return text == null ? "" : text.strip().lines().map(line -> "    " + line).reduce((a, b) -> a + "\n" + b).orElse("");
    }
}
```

`VerifyLoop.java` — the algorithm (write it with these exact rules; the test expectations above pin them):

```java
package dispatch.core;

import dispatch.Log;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Tests the building session's change, hands failures back, has a fresh reviewer check it, and says what it found
 * (spec: verify loop). It never delivers and never fails the run: whatever happens, the run delivers what the worktree holds.
 */
public final class VerifyLoop {

    public static final int FIX_ROUNDS = 3;
    public static final int REVIEW_ROUNDS = 1;
    public static final Duration TEST_TIMEOUT = Duration.ofMinutes(10);
    static final Duration TEST_STEP_NEEDS = Duration.ofMinutes(12);
    static final Duration AGENT_STEP_NEEDS = Duration.ofMinutes(5);
    public static final int DIFF_LIMIT = 60_000;
    static final BigDecimal BUDGET_FLOOR = new BigDecimal("0.05");

    public interface Agents {
        /** Resumes the building session; a result whose outcome is not SUCCEEDED ends the loop. */
        AgentResult fix(String prompt, BigDecimal budgetUsd, Duration timeout);

        /** Starts a fresh read-only reviewer. */
        AgentResult review(String prompt, BigDecimal budgetUsd, Duration timeout);

        /** The change since the run's start commit, as git shows it. */
        String diff();
    }

    /** @param budgetUsd null for no budget; @param spentUsd what the run has already cost */
    public record Setup(String testCommand, Path worktree, Path logBase, String reviewPrompt, Instant deadline,
                        BigDecimal budgetUsd, BigDecimal spentUsd) {
    }

    public record Outcome(Verification verification, List<AgentResult> runs) {
    }

    private final TestRunner tests;
    private final Clock clock;

    public VerifyLoop(TestRunner tests, Clock clock) {
        this.tests = tests;
        this.clock = clock;
    }

    public Outcome run(Setup setup, Agents agents, BooleanSupplier stopRequested) {
        return new Pass(setup, agents, stopRequested).run();
    }

    /** One loop's state: kept in an object so each step reads as the spec's list. */
    private final class Pass {
        // fields: setup, agents, stop, List<AgentResult> runs, BigDecimal spent, int fixesLeft = FIX_ROUNDS,
        // int testRuns, Verification.Tests testState (NO_COMMAND if testCommand == null else NOT_RUN), String tail,
        // Verification.ReviewState reviewState = NOT_RUN, List<Review.Finding> left = List.of(), String reviewError,
        // String stoppedBy
        ...
    }
}
```

`Pass.run()` does, in order (every "stop" sets `stoppedBy` and jumps to building the Outcome):

1. `testAndFix()`:
   - if `testCommand == null` → return.
   - loop: if `stop` → return; if time left (`deadline - clock.instant()`) < `TEST_STEP_NEEDS` → stoppedBy = `"time"`, return. Run `tests.run(testCommand, worktree, Path.of(logBase + ".test-" + (testRuns + 1) + ".log"), TEST_TIMEOUT, stop)`; `testRuns++`; `tail = run.tail()`; if `run.stopped()` → return; state = PASSED or FAILING. If PASSED or `fixesLeft == 0` → return.
   - otherwise `fix(Prompts.testFailure(testCommand, tail))` (below); if the fix stopped the loop → return; repeat.
2. If `stoppedBy == null`, `stop` is false and `reviewPrompt != null`: `review()`:
   - agent-step guard (below) → stop if it fails.
   - `AgentResult r = agents.review(reviewPrompt + "\n" + capped(agents.diff()), budgetLeft(), timeLeft())`; add to `runs`; add cost.
   - if `r.outcome() != SUCCEEDED` → reviewState FAILED, reviewError = `r.error()`; return.
   - `Review parsed = Review.parse(r.structuredOutput())`, catching `IllegalArgumentException e` → FAILED, reviewError = `e.getMessage()`, return.
   - if `parsed.blocking().isEmpty()` → reviewState = `parsed.minor().isEmpty() ? OK : FINDINGS`, left = `parsed.minor()`; return.
   - else: if `fixesLeft == 0` → reviewState FINDINGS, left = `parsed.findings()`; return. Otherwise `fix(Prompts.reviewFindings(parsed.blocking()))`; if the fix stopped the loop → reviewState FINDINGS, left = `parsed.findings()`, return; else reviewState = `parsed.minor().isEmpty() ? OK : FINDINGS`, left = `parsed.minor()`, then `testAndFix()` again (re-test after the review fix, within the fixes left).
3. Return `new Outcome(new Verification(testState, testRuns, tail, reviewState, left, reviewError, stoppedBy), List.copyOf(runs))`.

`fix(prompt)`: agent-step guard; `fixesLeft--`; `AgentResult r = agents.fix(prompt, budgetLeft(), timeLeft())`; add to `runs`; add its cost; if `r.outcome() != SUCCEEDED` → stoppedBy = `"fix failed: " + r.error()`.

Agent-step guard: if `stop` → stop (stoppedBy stays null; the caller sees the stop request); if time left < `AGENT_STEP_NEEDS` → stoppedBy `"time"`; if `budgetUsd != null` and `budgetUsd - spent < budgetUsd * BUDGET_FLOOR` → stoppedBy `"budget"`.

`budgetLeft()`: `budgetUsd == null ? null : budgetUsd.subtract(spent)`. `timeLeft()`: `Duration.between(clock.instant(), deadline)`. `capped(diff)`: `diff.length() <= DIFF_LIMIT ? diff : diff.substring(0, DIFF_LIMIT) + "\n… (the diff is longer; the rest was cut at " + DIFF_LIMIT + " characters)"`. Cost: add `r.costUsd()` when not null. Log `verify.step` with the step name and outcome at each step (Log.info).

- [ ] **Step 4: Run the tests, then the full suite**

Run: `./mvnw -Dtest='VerifyLoopTest,VerificationTest' test` — Expected: PASS (12 tests).
Run: `./mvnw test 2>&1 | grep -E "Tests run:|FAIL|ERROR" | tail -5` — Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/core/Verification.java src/main/java/dispatch/core/VerifyLoop.java src/main/java/dispatch/core/Prompts.java src/test/java/dispatch/core/VerifyLoopTest.java src/test/java/dispatch/core/VerificationTest.java
git commit -m "The verify loop tests a change, hands failures and a reviewer's blocking findings back for up to three fixes, stops on time or budget, and says what it found

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: JobRunner runs the loop before delivery

**Files:**
- Modify: `src/main/java/dispatch/core/JobRunner.java` (constructor, `implement`, `deliver`, `runAgent`)
- Modify: `src/main/java/dispatch/core/JobResult.java` (add `verification`)
- Modify: `src/main/java/dispatch/workspace/Delivery.java` (add `diff`)
- Modify: `src/main/java/dispatch/App.java` (~line 143, `new JobRunner(`), `src/main/java/dispatch/worker/WorkerCommand.java` (where it builds `WorkerLoop`/`JobRunner`)
- Test: `src/test/java/dispatch/core/JobRunnerTest.java`, `src/test/java/dispatch/core/JobJsonTest.java`, `src/test/java/dispatch/workspace/DeliveryTest.java`

**Interfaces:**
- Consumes: `VerifyLoop`, `VerifyLoop.Setup/Agents/Outcome`, `Verification`, `TestRunner`, `TestCommand` (Tasks 4–5); `Job.project().loopOn()`, `Job.project().test()`, `Job.reviewPrompt()` (Task 2); `RunKind.REVIEW` (Task 3).
- Produces:
  - `JobResult` gains a LAST component `@JsonInclude(NON_NULL) Verification verification`; the old 6-arg constructor passes null; `static JobResult delivered(AgentResult agent, List<String> files, String prUrl, Verification verification)`.
  - `new JobRunner(Workspaces, Delivery, Map<String, Agent>, Redactor, AttachmentSource, TestRunner tests, Clock clock)`; the old 5-arg constructor passes `new TestCommand(Confinement.none("no sandbox configured"))` and `Clock.systemUTC()`.
  - `String Delivery.diff(Path worktree, String startSha)` — tracked and untracked changes since `startSha` (`git add --intent-to-add --all` then `git diff <startSha>`).

- [ ] **Step 1: Write the failing tests**

`JobRunnerTest` — read it first: it builds a `JobRunner` with a fake `Agent`, a real `Workspaces`/`Delivery` against a temp clone with a fake `gh`, and asserts on the `JobResult` of EXECUTE jobs. Add, using its existing helpers for "an EXECUTE job" and "an agent that edits a file and succeeds" (construct the Job with `Job.Project(…, test, loop)` and `reviewPrompt`):

```java
    @Test
    void loopOnRunsTheTestsAndTheReviewerAndPutsTheVerificationOnTheCommit() throws Exception {
        // Agent fake: EXECUTE edits a file and succeeds; REVIEW answers {"verdict":"ok","findings":[]}.
        // Test runner fake: passes. Job: loop true, test "./mvnw -q test", reviewPrompt "Review this:".
        JobResult result = /* runner.run(executeJob, events, control) */;

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome());
        assertEquals(Verification.Tests.PASSED, result.verification().tests());
        assertEquals(Verification.ReviewState.OK, result.verification().review());
        // The delivery commit's body ends with the block (read it with git log -1 --format=%B in the clone).
        assertTrue(lastCommitBody().contains("Verification\n- Tests: pass (run 1)"), lastCommitBody());
    }

    @Test
    void loopOffIsTodaysPath() throws Exception {
        // Same job with loop false: the test runner and REVIEW must never be called.
        JobResult result = /* … */;

        assertNull(result.verification());
        assertEquals(List.of(RunKind.EXECUTE), agentKindsStarted);
    }

    @Test
    void theLoopsAgentCallsAddUpInTheRunsCost() throws Exception {
        // Tests fail once then pass; the fake agent reports 1.00 per EXECUTE call and 0.20 for REVIEW.
        JobResult result = /* … */;

        assertEquals(new BigDecimal("2.20"), result.agent().costUsd());
    }

    @Test
    void cancelDuringTheLoopDeliversNothing() throws Exception {
        // The test runner fake calls control.stop(StopReason.CANCELLED) and returns TestRun(-1,false,true,"").
        JobResult result = /* … */;

        assertEquals(JobResult.Outcome.CANCELLED, result.outcome());
        assertNull(result.prUrl());
    }
```
Fill each `/* … */` with the existing test's lines that build the runner and run a job; give the new constructor a lambda `TestRunner` and `Clock.systemUTC()`. `agentKindsStarted` and `lastCommitBody()` are small helpers you add (record `request.kind()` in the fake agent; run `git log -1 --format=%B` in the worktree the way the existing tests run git). If `JobRunnerTest` has no fake agent that can answer per kind, extend its fake minimally.

`JobJsonTest`:

```java
    @Test
    void aResultWithItsVerificationSurvivesJsonUnchanged() throws Exception {
        JobResult result = JobResult.delivered(null, List.of("A.java"), "https://github.com/acme/alm/pull/9",
                new Verification(Verification.Tests.FAILING, 4, "FooTest", Verification.ReviewState.FINDINGS,
                        List.of(new Review.Finding("minor", "A.java", 3, "name")), null, "budget"));

        assertEquals(result, Json.MAPPER.readValue(Json.write(result), JobResult.class));
    }
```

`DeliveryTest` (it has a temp clone helper): a new untracked file and an edit both appear in `delivery.diff(worktree, startSha)`.

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -Dtest='JobRunnerTest,JobJsonTest,DeliveryTest' test`
Expected: compilation FAIL.

- [ ] **Step 3: Implement**

`JobResult`: add `@JsonInclude(JsonInclude.Include.NON_NULL) Verification verification` last, keep a 6-arg constructor passing `null`, add the 4-arg `delivered` factory (the 3-arg one passes `null`).

`Delivery.diff`:

```java
    /** The change since {@code startSha}, new files included, for the verify loop's reviewer. */
    public String diff(Path worktree, String startSha) {
        // Marks new files as intended so git diff shows them; delivery adds everything anyway.
        git.run(worktree, "add", "--intent-to-add", "--all");
        return git.output(worktree, "diff", startSha);
    }
```
(Use the `Git` methods `Delivery` already calls for `add`/`status`/`rev-parse` — read `Delivery.head` and match its style and exception handling.)

`JobRunner`:
- New fields `TestRunner tests`, `Clock clock`; the 7-arg constructor; the 5-arg one delegating as in Interfaces.
- `runAgent(job, events, control, request)` becomes `runAgent(job, events, control, request, Duration timeout)`; the watchdog sleeps `timeout` instead of `Duration.ofMillis(job.timeoutMillis())`. `plan` passes `Duration.ofMillis(job.timeoutMillis())`.
- `implement`:

```java
        Instant deadline = clock.instant().plusMillis(job.timeoutMillis());
        JobResult result = runAgent(job, events, control, request(job, worktree, files), Duration.ofMillis(job.timeoutMillis()));
        if (result.outcome() != JobResult.Outcome.SUCCEEDED) {
            return result;
        }
        if (!job.project().loopOn()) {
            return deliver(job, worktree, startSha, result.agent(), null);
        }
        VerifyLoop.Outcome verified = new VerifyLoop(tests, clock).run(
                new VerifyLoop.Setup(job.project().test(), worktree, workspaces.runLogBase(job.taskId(), job.seq()),
                        job.reviewPrompt(), deadline, job.budgetUsd(), cost(result.agent())),
                loopAgents(job, events, control, worktree, files, startSha),
                () -> control.stopReason() != null);
        if (control.stopReason() != null) {
            return stopped(job, control.stopReason(), result.agent());
        }
        return deliver(job, worktree, startSha, combined(result.agent(), verified.runs()), verified.verification());
```

- `loopAgents(...)` returns a `VerifyLoop.Agents`:
  - `fix(prompt, budget, timeout)`: `call(job, events, control, new RunRequest(RunKind.EXECUTE, worktree, prompt, job.sessionId(), true, files.dirs(), budget, job.model(), job.effort(), Path.of(logBase + ".fix-" + n)), timeout)` with `n` counting fixes from 1.
  - `review(prompt, budget, timeout)`: `call(..., new RunRequest(RunKind.REVIEW, worktree, prompt, UUID.randomUUID(), false, files.dirs(), budget, job.model(), job.effort(), Path.of(logBase + ".review")), timeout)`.
  - `diff()`: `delivery.diff(worktree, startSha)`.
- `call(...)`: like `runAgent`, but returns the `AgentResult` itself (never a JobResult): an unknown agent type or `AgentStartException` becomes `new AgentResult(AgentOutcome.FAILED, -1, null, null, null, null, null, List.of(), <reason>, null, null)`; an interruption cancels the handle, re-sets the interrupt flag and returns a FAILED result. Refactor so `runAgent` and `call` share the start/await/watchdog code (runAgent = call + mapping to JobResult) — no duplicated block.
- `combined(AgentResult implemented, List<AgentResult> loop)`: the implementation's result with `costUsd` = sum of every non-null cost (null only if all are null), `turns` = sum of non-null turns, `denials` = all denials in order; every other field from `implemented`. Build it with the 12-arg `AgentResult` constructor (keep `implemented.sandbox()`).
- `cost(AgentResult)`: `costUsd == null ? BigDecimal.ZERO : costUsd`.
- `deliver(job, worktree, startSha, result, verification)`: the commit body is `summary` + (`verification == null ? "" : "\n\n" + verification.block()`); returns `JobResult.delivered(result, files, prUrl, verification)`.

`Coordinator` line ~210: pass `result.verification()` on to `transitions.completed(...)` — that is Task 7's signature; in THIS task leave Coordinator unchanged (the verification reaches the PR through the commit already).

`App.java` (`new JobRunner(workspaces, delivery, agents, redactor, api::downloadFile)`) and `WorkerCommand` (where `WorkerLoop`/`JobRunner` are built): pass `new TestCommand(<the same Confinement given to Agents.create>)` and `Clock.systemUTC()` (App already has a `clock`). Keep the Confinement in a local variable so both use the same one.

- [ ] **Step 4: Run the tests, then the full suite**

Run: `./mvnw -Dtest='JobRunnerTest,JobJsonTest,DeliveryTest,VerifyLoopTest' test` — Expected: PASS.
Run: `./mvnw test 2>&1 | grep -E "Tests run:|FAIL|ERROR" | tail -5` — Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "An execution with the verify loop on tests and reviews its change before delivery, within the run's own time and budget, and writes what it verified into the delivery commit

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: The Verification block in Telegram

**Files:**
- Modify: `src/main/java/dispatch/core/Coordinator.java:210` (pass the verification)
- Modify: `src/main/java/dispatch/core/RunTransitions.java` (`completed` ~line 105)
- Modify: `src/main/java/dispatch/telegram/Renderer.java` (`completed` ~line 694)
- Modify: `src/main/resources/messages_mn.properties`
- Test: `src/test/java/dispatch/core/TaskLifecycleTest.java`, `src/test/java/dispatch/telegram/RendererTest.java`

**Interfaces:**
- Consumes: `JobResult.verification()`, `Verification` (Tasks 5–6).
- Produces: `RunTransitions.completed(long taskId, int seq, AgentResult result, String summary, List<String> files, String prUrl, Verification verification)`; the old 6-arg `completed` passes `null`. Payload field `verification` (the record as JSON via `Json.MAPPER.valueToTree`), absent when null.

- [ ] **Step 1: Write the failing tests**

`TaskLifecycleTest` (helpers: `executing("20")` gives a task whose execution run seq 2 is running; `executionResult(denials)`; `row(sql, …)`; `Json.read`):

```java
    @Test
    void aCompletedRunCarriesItsVerificationToTheRequester() {
        long id = executing("20");
        Verification verification = new Verification(Verification.Tests.FAILING, 4, "FooTest failed",
                Verification.ReviewState.OK, List.of(), null, null);

        transitions.completed(id, 2, executionResult(List.of()), "Made it configurable.", List.of("A.java"),
                "https://github.com/acme/alm/pull/7", verification);

        JsonNode payload = Json.read(row("SELECT * FROM outbox WHERE kind = 'TASK_COMPLETED'").get("payload"));
        assertEquals("FAILING", payload.path("verification").path("tests").asText());
        assertEquals(4, payload.path("verification").path("testRuns").asInt());
    }
```

`RendererTest` (helper `completedPayload(prUrl, files, denials)`):

```java
    @Test
    void aCompletedResultShowsItsVerification() {
        ObjectNode payload = completedPayload("https://github.com/acme/alm/pull/7", 2, List.of());
        payload.set("verification", Json.MAPPER.valueToTree(new Verification(Verification.Tests.FAILING, 4, "FooTest <boom>",
                Verification.ReviewState.FINDINGS, List.of(new Review.Finding("minor", "A.java", 3, "rename")), null, "time")));

        String html = renderer.render(OutboxKind.TASK_COMPLETED, payload).html();

        assertTrue(html.contains("🧪 ❌ Тест унасан хэвээр (4 удаа)"), html);
        assertTrue(html.contains("FooTest &lt;boom&gt;"), html);
        assertTrue(html.contains("🔍 ⚠️ 1 зөвлөмж үлдсэн"), html);
        assertTrue(html.contains("⏹ Эрт зогссон: time"), html);
    }

    @Test
    void aResultWithoutVerificationHasNoBlock() {
        String html = renderer.render(OutboxKind.TASK_COMPLETED, completedPayload("https://github.com/acme/alm/pull/7", 2, List.of())).html();

        assertFalse(html.contains("🧪"), html);
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -Dtest='TaskLifecycleTest,RendererTest' test` — Expected: compilation FAIL / assertion FAIL.

- [ ] **Step 3: Implement**

`RunTransitions.completed` — the 7-arg version holds today's body plus, after `putRunDetails(...)`:

```java
            if (verification != null) {
                payload.set("verification", Json.MAPPER.valueToTree(verification));
            }
```
and the 6-arg version delegates with `null`.

`Coordinator` (~line 210): `case EXECUTE -> transitions.completed(job.taskId(), job.seq(), result.agent(), <the summary it passes today>, result.files(), result.prUrl(), result.verification());` — keep its existing arguments, add the last one.

`Renderer.completed` — after the summary and before the denials, append `verificationBlock(payload.path("verification"))`:

```java
    /** The verify loop's findings under a result (spec: verify loop); nothing for a run without the loop. */
    private String verificationBlock(JsonNode verification) {
        if (verification.isMissingNode() || verification.isNull()) {
            return "";
        }
        StringBuilder html = new StringBuilder("\n\n");
        int runs = verification.path("testRuns").asInt();
        html.append(switch (verification.path("tests").asText()) {
            case "PASSED" -> format("verify.testsPassed", String.valueOf(runs));
            case "FAILING" -> format("verify.testsFailing", String.valueOf(runs))
                    + "\n<pre>" + escapeWithin(verification.path("testTail").asText(""), TEST_TAIL_LIMIT) + "</pre>";
            case "NO_COMMAND" -> text("verify.noTestCommand");
            default -> text("verify.testsNotRun");
        });
        html.append('\n').append(switch (verification.path("review").asText()) {
            case "OK" -> text("verify.reviewOk");
            case "FINDINGS" -> format("verify.reviewFindings", String.valueOf(verification.path("findings").size()));
            case "FAILED" -> format("verify.reviewFailed", escape(verification.path("reviewError").asText("")));
            default -> text("verify.reviewNotRun");
        });
        if (verification.hasNonNull("stoppedBy")) {
            html.append('\n').append(format("verify.stopped", escape(verification.path("stoppedBy").asText())));
        }
        return html.toString();
    }
```
with `private static final int TEST_TAIL_LIMIT = 600;` beside the other limits. Check that `escapeWithin` escapes HTML (it does for the summary) and keeps the text within the message limit logic the method already applies.

`messages_mn.properties` (beside `run.unsandboxed`):
```
verify.testsPassed=🧪 ✅ Тест амжилттай ({0} дахь удаа)
verify.testsFailing=🧪 ❌ Тест унасан хэвээр ({0} удаа)
verify.noTestCommand=🧪 ➖ Тестийн команд тохируулаагүй
verify.testsNotRun=🧪 ➖ Тест ажиллаагүй
verify.reviewOk=🔍 ✅ Review асуудалгүй
verify.reviewFindings=🔍 ⚠️ {0} зөвлөмж үлдсэн
verify.reviewFailed=🔍 ⚠️ Review амжилтгүй: {0}
verify.reviewNotRun=🔍 ➖ Review ажиллаагүй
verify.stopped=⏹ Эрт зогссон: {0}
```

- [ ] **Step 4: Run the tests, then the full suite**

Run: `./mvnw -Dtest='TaskLifecycleTest,RendererTest,CoordinatorTest' test` — Expected: PASS.
Run: `./mvnw test 2>&1 | grep -E "Tests run:|FAIL|ERROR" | tail -5` — Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/main/resources/messages_mn.properties src/test/java
git commit -m "The requester's result in Telegram says whether the tests pass, what the reviewer left, and whether the loop stopped early

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: ADR 0033 and documentation

**Files:**
- Create: `docs/adr/0033-an-execution-tests-and-reviews-its-change-before-delivery.md`
- Modify: `docs/ARCHITECTURE.md` (Decisions at a glance, Flows, Agent boundary REVIEW row)
- Modify: `CONTEXT.md` (terms **Verification**, **Review**)
- Modify: `README.md`, `README.en.md` (project settings: `test`, `loop`)
- Modify: `docs/superpowers/specs/2026-09-30-verify-loop-design.md` (as built)

- [ ] **Step 1: Write ADR 0033** in the style of ADR 0032 (read it first): decision (the loop inside the execution run; Dispatch runs `test`; fresh read-only reviewer; always deliver, marked), rejected alternatives (tests and review as their own queued runs/phases; the agent testing itself; the real Sandcastle tool; blocking delivery), consequences (longer, costlier executions — bounded by the run's own limits; `loop: off` restores the old path; team machine and workers upgrade together because the worker protocol refuses unknown fields; the reviewer uses the execution's model; the test command runs in the sandbox, so a test needing a hidden path fails there).

- [ ] **Step 2: Update the other documents**
- ARCHITECTURE.md: add ADR 0033 to "Decisions at a glance"; in "Flows" describe execute → VerifyLoop (test/fix rounds, review, fix, re-test) → deliver; in "Agent boundary" add a REVIEW row to each agent's table (Claude: `--permission-mode plan --tools Read,Bash --json-schema <review schema>`, fresh `--session-id`; Codex: `sandbox_mode="read-only" --output-schema <logBase>.schema.json` with the review schema; Gemini: `--approval-mode default`, `-p` asking for the review JSON).
- CONTEXT.md: **Verification** — what the verify loop found before delivery: whether the project's tests pass, what the reviewer left, and whether the loop stopped early; written into the delivery commit and the requester's result. _Avoid_: QA, check. **Review** — a fresh read-only agent session that judges an execution's change against the approved plan; blocking findings go back to the building session. _Avoid_: audit, inspection.
- README.en.md: under the project settings, one line: "`test: <command>` — Dispatch runs it after each execution and hands failures back to the agent; `loop: off` (instance or project) turns the test-and-review loop off." README.md: the Mongolian equivalent — "`test: <команд>` — гүйцэтгэл бүрийн дараа Dispatch ажиллуулж, унасан бол агентад буцааж засуулна; `loop: off` (instance эсвэл төсөл) тест ба review-ийн давталтыг унтраана."
- The spec, as built: the reviewer uses the execution's model and effort (a Job carries one phase's model); the Verification block goes into the delivery commit body, which is the PR body, and so also stays in the git history; `verify.*` Telegram keys.

- [ ] **Step 3: Commit**

```bash
git add docs CONTEXT.md README.md README.en.md
git commit -m "ADR 0033 records that an execution tests and reviews its change before delivery, with the architecture, glossary, README and the spec as built

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Live check (only with the user's go)

Runs after the `agent-sandbox` branch's open items (C1 flag removal, the residual fix round) are settled, since the test command runs in that sandbox. Deploying swaps the live jar and runs a real task that opens a draft PR: ask first. The deploy must also include the `mini-group-fixes` work noted in memory.

- [ ] **Step 1:** Add `test: ./mvnw -q test` to a test project in `~/.config/dispatch/dispatch.yaml` (the owner picks the project).
- [ ] **Step 2:** Build with `./mvnw -q -Pui -DskipTests package`; stop the services, back up and swap the jar, start them (as in the sandbox plan's Task 9).
- [ ] **Step 3:** The owner gives one small task that is likely to break a test on first try; approve the plan.
- [ ] **Step 4:** Expected: the Telegram result shows `🧪 ✅ Тест амжилттай (N дахь удаа)` and a review line; the PR body ends with the Verification block; `runs/<task>/<seq>.test-1.log` exists; the run's cost includes the fix and review calls.
- [ ] **Step 5:** Report to the owner; roll back with the backup jar if anything failed.
