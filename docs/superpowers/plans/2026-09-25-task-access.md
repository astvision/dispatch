# Task access (ADR 0027) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One module in `core` decides what a member may see of a task and do with it, and why not; the commands, the task views, the assistant, the Mini App's routes and pages, and the plan's buttons ask it instead of working the rules out again.

**Architecture:** `TaskAccess` is built with the live `Groups`. `of(tx, memberRef, task)` reads what the rules need itself (plan questions and answers, the latest plan run, the latest run, whether an execution ever started) and returns a `Verdict`: how much the member sees (`FULL`, `HEADLINE`, `NONE`) and a `Refusal` per action that is not allowed. A `Viewer` (a member or a group chat) decides what lists hold. `TaskService`'s commands guard with the verdict and keep their result enums and reply payloads; its views take a `Viewer` and list each task's allowed `actions`; `AssistantActions`, `TasksApi`, `Renderer` and the Mini App read those answers. The conditional `UPDATE … WHERE phase = ?` stays the race guard.

**Tech Stack:** Java 25, plain Java with explicit wiring (ADR 0002), SQLite through sqlite-jdbc (ADR 0003), Jackson, JUnit 5 with `junit-jupiter-params` (already in `junit-jupiter`); React + TypeScript + Ant Design, vitest.

**Spec:** `docs/adr/0027-one-module-decides-what-a-member-may-see-and-do-with-a-task.md` (status: proposed; accepted in Task 5), with the glossary terms **Headline** and **Requester** in `CONTEXT.md`.

## Global Constraints

- Branch `task-access` from `main`. Commit per task. Do not push; the owner merges locally (see the milestone workflow). Deploying the jar is out of scope.
- No new dependency, Java or npm.
- No framework; `TaskAccess` holds only the live `Groups`, so each user constructs it (`new TaskAccess(groups)`) — no new constructor parameter for `TaskService`, `UpdateHandler`, `AssistantActions` or `TasksApi`.
- State changes stay conditional updates in the caller's transaction (ADR 0003): a verdict never replaces `Tasks.changePhase(… WHERE phase = ?)`.
- Telegram replies stay word for word: refusal payloads keep `reason` = `requester` | `phase` | `stale` | `notExecuted` (plus `requester`, `phase`), and `Renderer`'s texts do not change.
- Commands keep their result enums; the only new constant is `AnswerResult.OUT_OF_ORDER`.
- Existing log event names stay (`task.approve_not_allowed`, `task.approve_not_requester`, `task.reject_not_allowed`, `task.reject_not_requester`, `task.answer_refused`).
- User-facing text is Mongolian; this plan adds no new user-facing text. The one new API error message (`out_of_order`) is English like its neighbours.
- Tests run on real SQLite files; no mocks of the store.
- Java: `./mvnw -q test` (all) or `./mvnw -q test -Dtest=ClassName`. UI: `cd ui && npm run typecheck && npm test`.
- Commit messages: one imperative sentence in the repo's style, then a blank line and these two trailers:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_013bzgwsHrwA6N2BhRjVZ2Wz
  ```

## Review Focus

1. **A requester who left a project's group:** their own tasks there stay in `/status`, `/history`, `/history N`, the Mini App and `dispatch ask`; the group's other tasks disappear for them. (Test in Task 3: `aRequesterWhoLeftTheGroupStillSeesTheirOwnTasksButNoLongerTheGroupsOthers`, and `AskCommandTest.aMembersOwnTaskIsTheirsEvenOutsideTheirProjects`.)
2. **A group chat's `/status`:** headlines of its own projects, no priority buttons, empty `actions`. (Test in Task 3: `aGroupChatSeesHeadlinesWithNothingToDo`.)
3. **A blank reply (a sticker) under a plan that has moved on:** answered with the phase refusal instead of being ignored — an intended change of order. (Test in Task 2: `aBlankReplyToAPlanThatMovedOnIsAnsweredWithTheReason`.)
4. **A plan stored before questions had options** (questions as plain strings): verdicts count its questions without re-validating the plan, so `/status` never breaks on it. (Test in Task 1: `aPlanStoredBeforeQuestionsHadOptionsStillCountsItsQuestions`.)
5. **An admin's group view in the Mini App:** someone else's active task offers Cancel only, never Retry or priority. (Test in Task 4: `theGroupViewOffersAnAdminOnlyTheCancelOfSomeoneElsesTask`.)

## File map

| File | Change |
|---|---|
| `src/main/java/dispatch/core/TaskAccess.java` | **new**: `Sight`, `Action`, `Refusal`, `Viewer`, `Verdict`, `of`, `member`, `chat`, `decisions` |
| `src/test/java/dispatch/core/TaskAccessTest.java` | **new**: the rule table |
| `src/main/java/dispatch/core/TaskService.java` | commands guard with the verdict; views take a `Viewer` and list `actions` |
| `src/main/java/dispatch/core/AnswerResult.java` | `OUT_OF_ORDER` |
| `src/main/java/dispatch/store/Tasks.java`, `Runs.java` | lists select "the viewer's projects, or their own" |
| `src/main/java/dispatch/core/AssistantActions.java` | `check` asks the verdict; keeps only its proposal checks |
| `src/main/java/dispatch/core/Assistant.java`, `cli/AskCommand.java` | views through a `Viewer` |
| `src/main/java/dispatch/telegram/UpdateHandler.java` | viewers; `OUT_OF_ORDER` arms |
| `src/main/java/dispatch/telegram/Renderer.java` | plan buttons from `TaskAccess.decisions` |
| `src/main/java/dispatch/ui/TasksApi.java` | viewers; detail from the verdict; its own rule checks go |
| `ui/src/api.ts`, `ui/src/mini/TasksPage.tsx`, `ui/src/mini/TicketSheet.tsx` | buttons from `actions` and `plan.current` |
| tests | see each task |
| `docs/ARCHITECTURE.md`, `docs/adr/0012-…`, `docs/adr/0027-…` | document the module; accept the ADR |

---

### Task 1: TaskAccess and its rule table

**Files:**
- Create: `src/main/java/dispatch/core/TaskAccess.java`
- Create: `src/test/java/dispatch/core/TaskAccessTest.java`
- Modify: `docs/ARCHITECTURE.md` (the `core` row of the package table)

**Interfaces:**
- Consumes: `Groups.isMember/isAdmin/projectsOfMember/projectsOfChat`, `Tasks.find`, `Runs.latestSucceededPlanSeq/latest/agentStartedBefore`, `PlanAnswers.of`, `Json.read`.
- Produces (used by every later task):
  - `enum TaskAccess.Sight { FULL, HEADLINE, NONE }`
  - `enum TaskAccess.Action { APPROVE, CORRECT, ANSWER, REJECT, PRIORITY, CANCEL, RETRY, FOLLOW_UP }` with `String json()` → `approve correct answer reject priority cancel retry followUp`
  - `enum TaskAccess.Refusal { NOT_MEMBER, NOT_FOUND, NOT_REQUESTER, WRONG_PHASE, STALE_PLAN, OPEN_QUESTIONS, OUT_OF_ORDER, ALREADY_ANSWERED, NOT_FAILED, NOT_EXECUTED }`
  - `record TaskAccess.Viewer(String ref, Set<String> projects)` with `Sight sees(Task task)`
  - `record TaskAccess.Verdict(Task task, Sight sight, Map<Action, Refusal> refusals, int planSeq, int questions, Set<Integer> answered, int currentQuestion)` with `Optional<Refusal> refusal(Action)`, `boolean allows(Action)`, `List<Action> allowed()`, `Optional<Refusal> refusal(Action, int shownPlanSeq)`, `Optional<Refusal> answerRefusal(int shownPlanSeq, int index)`
  - `new TaskAccess(Groups)`, `Viewer member(String memberRef)`, `Viewer chat(String chatRef)`, `Verdict of(Tx, String memberRef, long taskId)`, `Verdict of(Tx, String memberRef, Task task)`, `static List<Action> decisions(int questions)`

- [ ] **Step 1: Branch, and commit the design this plan implements**

```bash
cd /opt/tools/dispatch
git switch -c task-access
git add CONTEXT.md docs/adr/0027-one-module-decides-what-a-member-may-see-and-do-with-a-task.md docs/superpowers/plans/2026-09-25-task-access.md
git commit -F- <<'EOF'
Record ADR 0027 and plan task access

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013bzgwsHrwA6N2BhRjVZ2Wz
EOF
```

- [ ] **Step 2: Write the failing rule table**

Create `src/test/java/dispatch/core/TaskAccessTest.java`:

```java
package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.core.TaskAccess.Action;
import dispatch.core.TaskAccess.Refusal;
import dispatch.core.TaskAccess.Sight;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.Plan;
import dispatch.domain.PlanQuestion;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.Task;
import dispatch.store.Database;
import dispatch.store.PlanAnswers;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Who may see and do what with a task (ADR 0027), as one table on a real SQLite file. The tests of the commands, the
 * channels and the pages prove only that they ask task access and relay its answer.
 */
class TaskAccessTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");
    /** An admin, and a member of the other group. */
    private static final Requester SARA = new Requester("telegram:300", "Sara");
    /** A member of the other group, and no admin. */
    private static final Requester TUYA = new Requester("telegram:500", "Tuya");
    /** An admin in no group. */
    private static final Requester ADMIN = new Requester("telegram:400", "Admin");
    private static final Requester STRANGER = new Requester("telegram:999", "Eve");
    private static final Plan PLAN = new Plan("Make the auth timeout configurable", List.of(), List.of("Read auth.timeout"),
            List.of(), List.of());
    private static final Plan QUESTIONS = new Plan("Make the auth timeout configurable", List.of(), List.of("Read auth.timeout"),
            List.of(), List.of(new PlanQuestion("Which environments?", List.of("staging", "prod")),
                    new PlanQuestion("Keep the old default?", List.of("yes", "no"))));
    private static final AgentResult RESULT = new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", null, "Done.",
            new BigDecimal("0.10"), 3, List.of(), null, null, null);

    /** How a row's task came to be; returns its id. */
    interface Given {
        long task(TaskAccessTest test);
    }

    /** Who looks at a row's task. */
    interface Looking {
        TaskAccess.Viewer viewer(TaskAccess access);
    }

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TestClock clock;
    private Groups groups;
    private TaskService tasks;
    private RunTransitions transitions;
    private TaskAccess access;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-25T10:00:00Z"));
        groups = new Groups(new Config.Telegram(List.of(300L, 400L), List.of(
                new Config.Group("backend", -100L, List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")),
                        List.of("alm", "crm")),
                new Config.Group("mobile", -300L, List.of(new Config.Member(300, "Sara"), new Config.Member(500, "Tuya")),
                        List.of("life")))));
        tasks = new TaskService(groups, new Projects(List.of(project("alm"), project("crm"), project("life")),
                project -> Optional.empty()), new ActiveRuns(), clock, () -> { }, () -> { });
        transitions = new RunTransitions(db, clock, () -> { });
        access = new TaskAccess(groups);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    static Stream<Arguments> actions() {
        return Stream.of(
                row("the requester approves a plan without questions", t -> t.awaiting(PLAN), BOLD, Action.APPROVE, null),
                row("the requester approves a plan that asks questions", t -> t.awaiting(QUESTIONS), BOLD, Action.APPROVE,
                        Refusal.OPEN_QUESTIONS),
                row("the requester approves while it is still planning", TaskAccessTest::planning, BOLD, Action.APPROVE,
                        Refusal.WRONG_PHASE),
                row("the requester approves again once it executes", TaskAccessTest::executing, BOLD, Action.APPROVE,
                        Refusal.WRONG_PHASE),
                row("a member of the group approves someone else's plan", t -> t.awaiting(PLAN), ALI, Action.APPROVE,
                        Refusal.NOT_REQUESTER),
                row("a member of another group approves it", t -> t.awaiting(PLAN), TUYA, Action.APPROVE, Refusal.NOT_FOUND),
                row("an admin of another group approves it", t -> t.awaiting(PLAN), SARA, Action.APPROVE, Refusal.NOT_FOUND),
                row("an admin in no group approves it", t -> t.awaiting(PLAN), ADMIN, Action.APPROVE, Refusal.NOT_MEMBER),
                row("a stranger approves it", t -> t.awaiting(PLAN), STRANGER, Action.APPROVE, Refusal.NOT_MEMBER),
                row("the requester corrects a waiting plan", t -> t.awaiting(PLAN), BOLD, Action.CORRECT, null),
                row("the requester corrects once it executes", TaskAccessTest::executing, BOLD, Action.CORRECT,
                        Refusal.WRONG_PHASE),
                row("the requester rejects a plan that asks questions", t -> t.awaiting(QUESTIONS), BOLD, Action.REJECT, null),
                row("the requester answers a plan's open question", t -> t.awaiting(QUESTIONS), BOLD, Action.ANSWER, null),
                row("the requester answers a plan that asks nothing", t -> t.awaiting(PLAN), BOLD, Action.ANSWER,
                        Refusal.ALREADY_ANSWERED),
                row("the requester reprioritizes an active task", TaskAccessTest::planning, BOLD, Action.PRIORITY, null),
                row("the requester reprioritizes a finished task", TaskAccessTest::rejected, BOLD, Action.PRIORITY,
                        Refusal.WRONG_PHASE),
                row("a member of the group reprioritizes someone else's task", TaskAccessTest::planning, ALI, Action.PRIORITY,
                        Refusal.NOT_REQUESTER),
                row("the requester cancels an active task", TaskAccessTest::planning, BOLD, Action.CANCEL, null),
                row("the requester cancels a finished task", TaskAccessTest::rejected, BOLD, Action.CANCEL, Refusal.WRONG_PHASE),
                row("a member of the group cancels someone else's task", TaskAccessTest::planning, ALI, Action.CANCEL,
                        Refusal.NOT_REQUESTER),
                row("a member of another group cancels it", TaskAccessTest::planning, TUYA, Action.CANCEL, Refusal.NOT_FOUND),
                row("an admin of another group cancels it without seeing it", TaskAccessTest::planning, SARA, Action.CANCEL, null),
                row("an admin in no group cancels it", TaskAccessTest::planning, ADMIN, Action.CANCEL, null),
                row("an admin cancels a finished task", TaskAccessTest::rejected, SARA, Action.CANCEL, Refusal.WRONG_PHASE),
                row("a stranger cancels it", TaskAccessTest::planning, STRANGER, Action.CANCEL, Refusal.NOT_MEMBER),
                row("the requester retries a failed execution", TaskAccessTest::failedExecution, BOLD, Action.RETRY, null),
                row("the requester retries a failed plan", TaskAccessTest::failedPlan, BOLD, Action.RETRY, null),
                row("the requester retries a completed task", TaskAccessTest::completed, BOLD, Action.RETRY, Refusal.NOT_FAILED),
                row("the requester retries a rejected task", TaskAccessTest::rejected, BOLD, Action.RETRY, Refusal.NOT_FAILED),
                row("a member of the group retries someone else's failed task", TaskAccessTest::failedExecution, ALI, Action.RETRY,
                        Refusal.NOT_REQUESTER),
                row("an admin of another group retries it", TaskAccessTest::failedExecution, SARA, Action.RETRY,
                        Refusal.NOT_FOUND),
                row("the requester follows up a completed execution", TaskAccessTest::completed, BOLD, Action.FOLLOW_UP, null),
                row("the requester follows up a failed execution", TaskAccessTest::failedExecution, BOLD, Action.FOLLOW_UP, null),
                row("the requester follows up a plan that failed", TaskAccessTest::failedPlan, BOLD, Action.FOLLOW_UP,
                        Refusal.NOT_EXECUTED),
                row("the requester follows up an active task", TaskAccessTest::executing, BOLD, Action.FOLLOW_UP,
                        Refusal.WRONG_PHASE),
                row("a requester who left the group approves their own plan", TaskAccessTest::afterLeavingTheGroup, BOLD,
                        Action.APPROVE, null),
                row("a requester who left the group cancels their own task", TaskAccessTest::afterLeavingTheGroup, BOLD,
                        Action.CANCEL, null),
                row("the member now in that group approves it", TaskAccessTest::afterLeavingTheGroup, ALI, Action.APPROVE,
                        Refusal.NOT_REQUESTER),
                row("a task that does not exist", t -> 999L, BOLD, Action.CANCEL, Refusal.NOT_FOUND));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("actions")
    void action(String rule, Given given, Requester who, Action action, Refusal expected) {
        long taskId = given.task(this);

        assertEquals(Optional.ofNullable(expected), verdict(who, taskId).refusal(action), rule);
    }

    static Stream<Arguments> sights() {
        return Stream.of(
                Arguments.of("the requester sees their own task in full", (Looking) a -> a.member(BOLD.ref()),
                        (Given) TaskAccessTest::planning, Sight.FULL),
                Arguments.of("a member of the group sees its headline", (Looking) a -> a.member(ALI.ref()),
                        (Given) TaskAccessTest::planning, Sight.HEADLINE),
                Arguments.of("a member of another group does not see it", (Looking) a -> a.member(TUYA.ref()),
                        (Given) TaskAccessTest::planning, Sight.NONE),
                Arguments.of("its group chat sees its headline", (Looking) a -> a.chat("telegram:-100"),
                        (Given) TaskAccessTest::planning, Sight.HEADLINE),
                Arguments.of("another group's chat does not see it", (Looking) a -> a.chat("telegram:-300"),
                        (Given) TaskAccessTest::planning, Sight.NONE),
                Arguments.of("a requester who left the group still sees their own task in full", (Looking) a -> a.member(BOLD.ref()),
                        (Given) TaskAccessTest::afterLeavingTheGroup, Sight.FULL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sights")
    void sight(String rule, Looking looking, Given given, Sight expected) {
        long taskId = given.task(this);
        // After the row's setup, which may have changed the groups.
        TaskAccess.Viewer viewer = looking.viewer(access);
        Task task = db.transactionReturning(tx -> Tasks.find(tx, taskId)).orElseThrow();

        assertEquals(expected, viewer.sees(task), rule);
    }

    static Stream<Arguments> answers() {
        return Stream.of(
                Arguments.of("the first question, first", false, 1, 1, null),
                Arguments.of("the second question while the first is open", false, 1, 2, Refusal.OUT_OF_ORDER),
                Arguments.of("a question of a plan a newer one replaced", false, 7, 1, Refusal.STALE_PLAN),
                Arguments.of("a question the plan does not ask", false, 1, 3, Refusal.STALE_PLAN),
                Arguments.of("the first question again", true, 1, 1, Refusal.ALREADY_ANSWERED),
                Arguments.of("the second question once the first has its answer", true, 1, 2, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("answers")
    void answer(String rule, boolean firstAnswered, int planSeq, int index, Refusal expected) {
        long taskId = awaiting(QUESTIONS);
        if (firstAnswered) {
            db.transaction(tx -> PlanAnswers.record(tx, taskId, 1, 1, "prod", BOLD.ref(), null, clock.instant()));
        }

        assertEquals(Optional.ofNullable(expected), verdict(BOLD, taskId).answerRefusal(planSeq, index), rule);
    }

    @Test
    void aTapOnAReplacedPlanIsStaleAfterWhoAndThePhase() {
        long plain = awaiting(PLAN);
        long asking = awaiting(QUESTIONS);
        long moved = executing();

        assertEquals(Optional.of(Refusal.STALE_PLAN), verdict(BOLD, plain).refusal(Action.APPROVE, 2));
        assertEquals(Optional.of(Refusal.STALE_PLAN), verdict(BOLD, asking).refusal(Action.APPROVE, 2), "stale before open questions");
        assertEquals(Optional.of(Refusal.OPEN_QUESTIONS), verdict(BOLD, asking).refusal(Action.APPROVE, 1));
        assertEquals(Optional.empty(), verdict(BOLD, plain).refusal(Action.REJECT, 1));
        assertEquals(Optional.of(Refusal.WRONG_PHASE), verdict(BOLD, moved).refusal(Action.APPROVE, 1), "the phase before the plan");
        assertEquals(Optional.of(Refusal.NOT_REQUESTER), verdict(ALI, plain).refusal(Action.APPROVE, 2), "who, before anything");
    }

    @Test
    void aVerdictListsWhatTheMemberMayDoInOneOrder() {
        long taskId = awaiting(PLAN);

        assertEquals(List.of(Action.APPROVE, Action.CORRECT, Action.REJECT, Action.PRIORITY, Action.CANCEL),
                verdict(BOLD, taskId).allowed());
        assertEquals(List.of(), verdict(ALI, taskId).allowed(), "someone else's task is its requester's to act on");
        assertEquals(1, verdict(BOLD, taskId).planSeq());
        assertEquals("followUp", Action.FOLLOW_UP.json());
    }

    @Test
    void aPlanOffersApproveOnlyWhenItAsksNothing() {
        assertEquals(List.of(Action.APPROVE, Action.REJECT), TaskAccess.decisions(0));
        assertEquals(List.of(Action.REJECT), TaskAccess.decisions(2));
    }

    @Test
    void aPlanStoredBeforeQuestionsHadOptionsStillCountsItsQuestions() {
        long taskId = awaiting(PLAN);
        // G-1d: an older plan's questions are plain strings.
        db.transaction(tx -> tx.update("UPDATE task SET plan_json = ? WHERE id = ?",
                "{\"understanding\":\"u\",\"findings\":[],\"steps\":[\"s\"],\"risks\":[],\"questions\":[\"Which environments?\"]}",
                taskId));

        TaskAccess.Verdict verdict = verdict(BOLD, taskId);

        assertEquals(Optional.of(Refusal.OPEN_QUESTIONS), verdict.refusal(Action.APPROVE));
        assertEquals(1, verdict.currentQuestion());
    }

    private static Arguments row(String rule, Given given, Requester who, Action action, Refusal expected) {
        return Arguments.of(rule, given, who, action, expected);
    }

    private TaskAccess.Verdict verdict(Requester who, long taskId) {
        return db.transactionReturning(tx -> access.of(tx, who.ref(), taskId));
    }

    private long planning() {
        return create("alm");
    }

    private long awaiting(Plan plan) {
        return awaiting("alm", plan);
    }

    private long awaiting(String project, Plan plan) {
        long id = create(project);
        ClaimedRun run = claim();
        transitions.planSucceeded(id, run.seq(), plan, new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", plan.toJson(), null,
                new BigDecimal("0.10"), 3, List.of(), null, null, null));
        return id;
    }

    /** Approved; its execution run (seq 2) runs, and its agent started. */
    private long executing() {
        long id = awaiting(PLAN);
        db.transaction(tx -> tasks.approve(tx, BOLD, id, 1));
        ClaimedRun run = claim();
        transitions.agentStarted(id, run.seq(), null, null);
        return id;
    }

    private long failedExecution() {
        long id = executing();
        transitions.failed(id, 2, FailureReason.AGENT, "boom", RESULT);
        return id;
    }

    private long completed() {
        long id = executing();
        transitions.completed(id, 2, RESULT, List.of("README.md"), "https://github.com/acme/alm/pull/7");
        return id;
    }

    /** Its first plan run failed, so no execution ever started. */
    private long failedPlan() {
        long id = create("alm");
        ClaimedRun run = claim();
        transitions.failed(id, run.seq(), FailureReason.AGENT, "boom", RESULT);
        return id;
    }

    private long rejected() {
        long id = awaiting(PLAN);
        db.transaction(tx -> tasks.reject(tx, BOLD, id, 1));
        return id;
    }

    /** Bold's crm plan waits for him after crm moved to a group he is not in; he is still in another group. */
    private long afterLeavingTheGroup() {
        long id = awaiting("crm", PLAN);
        groups.replace(new Config.Telegram(List.of(300L, 400L), List.of(
                new Config.Group("backend", -100L, List.of(new Config.Member(100, "Bold")), List.of("alm")),
                new Config.Group("sales", -200L, List.of(new Config.Member(200, "Ali")), List.of("crm")),
                new Config.Group("mobile", -300L, List.of(new Config.Member(300, "Sara"), new Config.Member(500, "Tuya")),
                        List.of("life")))));
        return id;
    }

    private long create(String project) {
        String origin = BOLD.ref() + "/" + System.nanoTime();
        db.transaction(tx -> tasks.create(tx, BOLD, project, "Fix the login timeout", Priority.NORMAL, origin));
        return Long.parseLong(SqlRows.single(dbFile, "SELECT id FROM task WHERE origin_ref = ?", origin).get("id"));
    }

    private ClaimedRun claim() {
        return db.transactionReturning(tx -> Runs.claimNext(tx, 10, clock.instant())).orElseThrow();
    }

    private static Config.Project project(String name) {
        return new Config.Project(name, null, "https://github.com/acme/" + name + ".git", null, "main", "claude-code", null, null,
                List.of(), null, null, null);
    }
}
```

- [ ] **Step 3: Run it to see it fail**

Run: `./mvnw -q test -Dtest=TaskAccessTest`
Expected: compilation FAILURE, `cannot find symbol … class TaskAccess`.

- [ ] **Step 4: Write TaskAccess**

Create `src/main/java/dispatch/core/TaskAccess.java`:

```java
package dispatch.core;

import dispatch.Json;
import dispatch.domain.Phase;
import dispatch.domain.RunKind;
import dispatch.domain.RunStatus;
import dispatch.domain.Task;
import dispatch.store.PlanAnswers;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a member may see of a task and do with it, and why not (ADR 0027): the rules of ADR 0012, 0020 and 0024 in one
 * place. Every command asks before it acts, and everything that shows a task or offers an action asks too, instead of
 * working the rules out again. It reads what the rules need itself, in the caller's transaction; the conditional
 * updates stay the guard against races (ADR 0003).
 */
public final class TaskAccess {

    /** How much of a task a viewer sees (ADR 0020). */
    public enum Sight {
        FULL,
        HEADLINE,
        NONE
    }

    /** What a member may do with a task; {@link #json()} is the name payloads and the Mini App use. */
    public enum Action {
        APPROVE("approve"),
        CORRECT("correct"),
        ANSWER("answer"),
        REJECT("reject"),
        PRIORITY("priority"),
        CANCEL("cancel"),
        RETRY("retry"),
        FOLLOW_UP("followUp");

        private final String json;

        Action(String json) {
            this.json = json;
        }

        public String json() {
            return json;
        }
    }

    /** Why an action may not be taken now. */
    public enum Refusal {
        /** In no group; for cancelling, no admin either. */
        NOT_MEMBER,
        /** No such task, or one this member may not see, so its existence does not leak. */
        NOT_FOUND,
        /** Someone else's task, seen as its headline (ADR 0020). */
        NOT_REQUESTER,
        WRONG_PHASE,
        /** A tap or reply on a plan a newer one replaced. */
        STALE_PLAN,
        /** A plan that asks questions is never approved: their answers make the next plan (G-1d). */
        OPEN_QUESTIONS,
        /** A later question while an earlier one is open. */
        OUT_OF_ORDER,
        /** The question has its answer already, or no question is open. */
        ALREADY_ANSWERED,
        /** Only a failed task's failed step is retried (ADR 0008). */
        NOT_FAILED,
        /** A follow-up continues an execution, and this task never started one (ADR 0006). */
        NOT_EXECUTED
    }

    /**
     * Who is looking at tasks, and which a list may hold for them: their groups' projects (ADR 0012), and their own tasks
     * wherever they are (ADR 0027). A group chat has no member and sees its own projects' tasks as headlines.
     *
     * @param ref      the member; null for a group chat
     * @param projects the projects whose tasks this viewer sees at least as a headline
     */
    public record Viewer(String ref, Set<String> projects) {

        public Viewer {
            projects = Set.copyOf(projects);
        }

        public Sight sees(Task task) {
            if (ref != null && task.requester().ref().equals(ref)) {
                return Sight.FULL;
            }
            return projects.contains(task.project()) ? Sight.HEADLINE : Sight.NONE;
        }
    }

    /**
     * One member's verdict on one task: how much of it they see, and why each action may not be taken now. An action
     * missing from {@code refusals} is allowed.
     *
     * @param task            null when there is no such task
     * @param planSeq         the plan waiting for a decision, 0 when none waits
     * @param questions       how many questions that plan asks
     * @param answered        the questions answered so far, by 1-based index
     * @param currentQuestion the first open question, 0 when none is open
     */
    public record Verdict(Task task, Sight sight, Map<Action, Refusal> refusals, int planSeq, int questions,
                          Set<Integer> answered, int currentQuestion) {

        public Verdict {
            refusals = Map.copyOf(refusals);
            answered = Set.copyOf(answered);
        }

        public Optional<Refusal> refusal(Action action) {
            return Optional.ofNullable(refusals.get(action));
        }

        public boolean allows(Action action) {
            return !refusals.containsKey(action);
        }

        /** What the member may do now, in {@link Action} order: what payloads list for the Mini App. */
        public List<Action> allowed() {
            return Arrays.stream(Action.values()).filter(this::allows).toList();
        }

        /**
         * For a tap or reply that names the plan it was shown (approve, correct, reject): a plan a newer one replaced is
         * stale. Who may act and the phase come first, as the chat has always answered them.
         */
        public Optional<Refusal> refusal(Action action, int shownPlanSeq) {
            Optional<Refusal> refused = refusal(action);
            if (refused.isPresent() && refused.get() != Refusal.OPEN_QUESTIONS) {
                return refused;
            }
            return shownPlanSeq != planSeq ? Optional.of(Refusal.STALE_PLAN) : refused;
        }

        /**
         * An answer to question {@code index} (1-based) of plan {@code shownPlanSeq}: on the plan that waits, once, and in
         * order, since answering sends the first open question and an earlier one would be sent twice.
         */
        public Optional<Refusal> answerRefusal(int shownPlanSeq, int index) {
            Optional<Refusal> refused = refusal(Action.ANSWER);
            if (refused.isPresent() && refused.get() != Refusal.ALREADY_ANSWERED) {
                return refused;
            }
            if (shownPlanSeq != planSeq || index < 1 || index > questions) {
                return Optional.of(Refusal.STALE_PLAN);
            }
            if (answered.contains(index)) {
                return Optional.of(Refusal.ALREADY_ANSWERED);
            }
            return index == currentQuestion ? Optional.empty() : Optional.of(Refusal.OUT_OF_ORDER);
        }
    }

    private final Groups groups;

    /** @param groups the running bot's groups, read on every call, so a join or a link applies at once */
    public TaskAccess(Groups groups) {
        this.groups = groups;
    }

    /** A member looking at tasks: their groups' projects, and their own tasks wherever they are. */
    public Viewer member(String memberRef) {
        return new Viewer(memberRef, groups.projectsOfMember(memberRef));
    }

    /** A group chat looking at tasks: its own projects', as headlines. */
    public Viewer chat(String chatRef) {
        return new Viewer(null, groups.projectsOfChat(chatRef));
    }

    public Verdict of(Tx tx, String memberRef, long taskId) {
        return of(tx, memberRef, Tasks.find(tx, taskId).orElse(null));
    }

    /** @param task null when there is no such task */
    public Verdict of(Tx tx, String memberRef, Task task) {
        Sight sight = task == null ? Sight.NONE : member(memberRef).sees(task);
        boolean member = groups.isMember(memberRef);
        boolean admin = groups.isAdmin(memberRef);
        // Only a plan waiting for a decision has questions and answers that matter. The questions are counted, not
        // re-validated: a plan stored before questions had options holds them as plain strings.
        boolean awaiting = task != null && task.phase() == Phase.AWAITING_APPROVAL;
        int planSeq = awaiting ? Runs.latestSucceededPlanSeq(tx, task.id()).orElse(0) : 0;
        int questions = awaiting && task.planJson() != null ? Json.read(task.planJson()).path("questions").size() : 0;
        Set<Integer> answered = planSeq > 0 ? PlanAnswers.of(tx, task.id(), planSeq).keySet() : Set.of();
        int current = firstOpen(questions, answered);
        Map<Action, Refusal> refusals = new EnumMap<>(Action.class);
        for (Action action : Action.values()) {
            refusal(tx, action, task, sight, member, admin, questions, current).ifPresent(refusal -> refusals.put(action, refusal));
        }
        return new Verdict(task, sight, refusals, planSeq, questions, answered, current);
    }

    /**
     * The decisions a plan offers its requester when it is sent: no Approve while it asks questions (G-1d), the same rule
     * {@link #of} applies when a button is pressed.
     */
    public static List<Action> decisions(int questions) {
        return questions == 0 ? List.of(Action.APPROVE, Action.REJECT) : List.of(Action.REJECT);
    }

    private static Optional<Refusal> refusal(Tx tx, Action action, Task task, Sight sight, boolean member, boolean admin,
                                             int questions, int current) {
        // ADR 0020: an admin may cancel any task, even one of a group they are not in and cannot see.
        boolean adminCancel = action == Action.CANCEL && admin;
        if (!member && !adminCancel) {
            return Optional.of(Refusal.NOT_MEMBER);
        }
        if (task == null || (sight == Sight.NONE && !adminCancel)) {
            return Optional.of(Refusal.NOT_FOUND);
        }
        if (sight == Sight.HEADLINE && !adminCancel) {
            return Optional.of(Refusal.NOT_REQUESTER);
        }
        return byPhase(tx, action, task, questions, current);
    }

    private static Optional<Refusal> byPhase(Tx tx, Action action, Task task, int questions, int current) {
        Phase phase = task.phase();
        return switch (action) {
            case APPROVE -> {
                if (phase != Phase.AWAITING_APPROVAL) {
                    yield Optional.of(Refusal.WRONG_PHASE);
                }
                yield decisions(questions).contains(Action.APPROVE) ? Optional.empty() : Optional.of(Refusal.OPEN_QUESTIONS);
            }
            case CORRECT, REJECT -> phase == Phase.AWAITING_APPROVAL ? Optional.empty() : Optional.of(Refusal.WRONG_PHASE);
            case ANSWER -> {
                if (phase != Phase.AWAITING_APPROVAL) {
                    yield Optional.of(Refusal.WRONG_PHASE);
                }
                yield current == 0 ? Optional.of(Refusal.ALREADY_ANSWERED) : Optional.empty();
            }
            case PRIORITY, CANCEL -> phase.isActive() ? Optional.empty() : Optional.of(Refusal.WRONG_PHASE);
            case RETRY -> {
                boolean failed = phase == Phase.FAILED
                        && Runs.latest(tx, task.id()).filter(run -> run.status() == RunStatus.FAILED).isPresent();
                yield failed ? Optional.empty() : Optional.of(Refusal.NOT_FAILED);
            }
            case FOLLOW_UP -> {
                if (phase != Phase.COMPLETED && phase != Phase.FAILED) {
                    yield Optional.of(Refusal.WRONG_PHASE);
                }
                boolean executed = Runs.agentStartedBefore(tx, task.id(), RunKind.EXECUTE, Integer.MAX_VALUE);
                yield executed ? Optional.empty() : Optional.of(Refusal.NOT_EXECUTED);
            }
        };
    }

    private static int firstOpen(int questions, Set<Integer> answered) {
        for (int index = 1; index <= questions; index++) {
            if (!answered.contains(index)) {
                return index;
            }
        }
        return 0;
    }
}
```

- [ ] **Step 5: Run it to see it pass**

Run: `./mvnw -q test -Dtest=TaskAccessTest`
Expected: PASS — 39 action rows, 6 sight rows, 6 answer rows and 4 tests.

- [ ] **Step 6: Document the module**

In `docs/ARCHITECTURE.md`, in the `core` row of the package table, after the sentence that ends `… / status / history / timeline / stats`.` insert:

```
`TaskAccess` decides what a member may see of a task and do with it, and why not (ADR 0027): every command asks it before it acts, and every view and channel asks it instead of working the rules out again.
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dispatch/core/TaskAccess.java src/test/java/dispatch/core/TaskAccessTest.java docs/ARCHITECTURE.md
git commit -F- <<'EOF'
Decide in one place what a member may see and do with a task

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013bzgwsHrwA6N2BhRjVZ2Wz
EOF
```

---

### Task 2: The commands ask task access

**Files:**
- Modify: `src/main/java/dispatch/core/TaskService.java` (`approve`, `correct`, `answer`, `askForAnswer`, `reject`, `changePriority`, `cancel`, `retry`, `followUp`; delete `questionRefusal`, `visibleTask`, `isRequester`)
- Modify: `src/main/java/dispatch/core/AnswerResult.java`
- Modify: `src/main/java/dispatch/telegram/UpdateHandler.java` (two `AnswerResult` switches)
- Modify: `src/main/java/dispatch/ui/TasksApi.java` (`answer`'s switch)
- Modify: `src/main/java/dispatch/core/AssistantActions.java` (`answered`'s switch)
- Test: `src/test/java/dispatch/core/TaskLifecycleTest.java`

**Interfaces:**
- Consumes: `TaskAccess.of`, `Verdict.refusal(Action)`, `Verdict.refusal(Action, int)`, `Verdict.answerRefusal(int, int)`, `Verdict.task()`.
- Produces: `AnswerResult.OUT_OF_ORDER`. Command signatures unchanged.

- [ ] **Step 1: Prune the rule cases and write the new command tests**

In `TaskLifecycleTest`:

Delete these test methods; each case is now a row of `TaskAccessTest`: `secondApprovalOfTheSamePlanIsRefused`, `planWithOpenQuestionsCannotBeApproved`, `onlyTheRequesterDecidesOnTheirPlan`, `cancelOfATaskOutsideTheMembersGroupsLooksLikeAnUnknownTask`, `anAdminCanCancelAnyTaskEvenOutsideTheirGroups`, `anAdminWhoIsNotAMemberOfAnyGroupCanStillCancel`.

Keep, unchanged, the tests that pin what a refusal *says*: `correctionOfASupersededPlanOrABusyTaskIsRefusedWithTheReason`, `correctionFromNonMemberIsToldNoAndBlankReplyIsIgnored`, `cancelIsRefusedForFinishedAndUnknownTasksAndNonMembers`, `anotherMemberOfTheGroupCannotCancelRetryOrFollowUpSomeoneElsesTask`.

Replace `rejectIsRefusedForStalePlansOtherPhasesUnknownTasksAndNonMembers`, `approveIsRefusedForStalePlansOtherPhasesUnknownTasksAndNonMembers` and `priorityIsTheRequestersToChangeAndOnlyWhileTheTaskIsActive` with one case each:

```java
    @Test
    void aStaleRejectionChangesNothing() {
        long awaiting = awaitingApproval("31");

        assertEquals(RejectResult.STALE_PLAN, db.transactionReturning(tx -> tasks.reject(tx, BOLD, awaiting, 2)));

        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", awaiting).get("phase"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'TASK_REJECTED'").get("n"));
    }

    @Test
    void aStaleApprovalChangesNothing() {
        long awaiting = awaitingApproval("73");

        assertEquals(ApproveResult.STALE_PLAN, db.transactionReturning(tx -> tasks.approve(tx, BOLD, awaiting, 2)));

        assertEquals("AWAITING_APPROVAL", row("SELECT phase FROM task WHERE id = ?", awaiting).get("phase"));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'EXECUTION_QUEUED'").get("n"));
    }

    @Test
    void someoneElsesPriorityChangeChangesNothing() {
        long active = create(BOLD, "alm", "Fix login timeout", "94");

        assertEquals(PriorityResult.NOT_REQUESTER, db.transactionReturning(tx -> tasks.changePriority(tx, ALI, active, Priority.LOW)));

        assertEquals("NORMAL", row("SELECT priority FROM task WHERE id = ?", active).get("priority"));
    }
```

Add a plan with two questions next to `PLAN_WITH_QUESTION`:

```java
    private static final Plan PLAN_WITH_TWO_QUESTIONS = new Plan("Make the auth timeout configurable", List.of(),
            List.of("Read auth.timeout"), List.of(), List.of(
                    new dispatch.domain.PlanQuestion("Which environments?", List.of("staging", "prod")),
                    new dispatch.domain.PlanQuestion("Keep the old default?", List.of("yes", "no"))));
```

And add:

```java
    @Test
    void anAnswerOutOfOrderIsRefusedAndChangesNothing() {
        long id = awaitingApproval("60", PLAN_WITH_TWO_QUESTIONS);

        assertEquals(AnswerResult.OUT_OF_ORDER,
                db.transactionReturning(tx -> tasks.answer(tx, BOLD, id, 1, 2, "yes", null, CHAT + "/61", CHAT)));

        assertEquals("0", row("SELECT count(*) AS n FROM plan_answer").get("n"));
        assertEquals("1", row("SELECT count(*) AS n FROM outbox WHERE kind = 'PLAN_QUESTION'").get("n"),
                "the first question is not sent a second time");
    }

    @Test
    void aBlankReplyToAPlanThatMovedOnIsAnsweredWithTheReason() {
        long id = awaitingApproval("89");
        db.transaction(tx -> tasks.correct(tx, BOLD, id, 1, "first correction", CHAT + "/90", CHAT));

        assertEquals(CorrectResult.REFUSED, db.transactionReturning(tx -> tasks.correct(tx, BOLD, id, 1, " ", CHAT + "/91", CHAT)));

        JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = ?", CHAT + "/91").get("payload"));
        assertEquals("phase", payload.get("reason").asText(), "a sticker under an old plan is told why, not ignored");
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest=TaskLifecycleTest`
Expected: compilation FAILURE, `cannot find symbol … OUT_OF_ORDER`.

- [ ] **Step 3: Add `OUT_OF_ORDER`**

In `AnswerResult.java`, after `ALREADY_ANSWERED,`:

```java
    /** A later question while an earlier one is still open: they are answered in order (ADR 0027). */
    OUT_OF_ORDER,
```

In `UpdateHandler.replyToQuestion`'s switch, add the arm `case OUT_OF_ORDER -> "stale";`. In `UpdateHandler.onQuestionButton`'s switch, add `case OUT_OF_ORDER -> "callback.stale";`. (The chat sends one question at a time, so it never meets this; the arms keep the switches exhaustive.)

In `TasksApi.answer`'s result switch, add:

```java
                case OUT_OF_ORDER -> throw new ApiException(409, "out_of_order",
                        "answer the questions in order: an earlier one is still open");
```

In `AssistantActions.answered`, change `case ALREADY_ANSWERED, STALE, EMPTY -> Outcome.STALE;` to `case ALREADY_ANSWERED, STALE, EMPTY, OUT_OF_ORDER -> Outcome.STALE;`.

- [ ] **Step 4: Guard every command with the verdict**

In `TaskService`, add the field and build it in the nine-argument constructor:

```java
    private final TaskAccess access;
```

```java
        this.access = new TaskAccess(groups);
```

Replace `approve` with:

```java
    public ApproveResult approve(Tx tx, Requester who, long taskId, int planSeq) {
        Instant now = clock.instant();
        TaskAccess.Verdict verdict = access.of(tx, who.ref(), taskId);
        Optional<TaskAccess.Refusal> refused = verdict.refusal(TaskAccess.Action.APPROVE, planSeq);
        if (refused.isPresent()) {
            return switch (refused.get()) {
                case NOT_MEMBER -> {
                    tx.afterCommit(() -> Log.warn("task.approve_not_allowed", "task", taskId, "requester", who.ref()));
                    yield ApproveResult.NOT_ALLOWED;
                }
                case NOT_FOUND -> ApproveResult.NOT_FOUND;
                case NOT_REQUESTER -> {
                    tx.afterCommit(() -> Log.info("task.approve_not_requester", "task", taskId, "requester", who.ref()));
                    yield ApproveResult.NOT_REQUESTER;
                }
                case STALE_PLAN -> ApproveResult.STALE_PLAN;
                case OPEN_QUESTIONS -> ApproveResult.OPEN_QUESTIONS;
                default -> ApproveResult.WRONG_STATE;
            };
        }
        Task task = verdict.task();
        if (!Tasks.changePhase(tx, taskId, Phase.AWAITING_APPROVAL, Phase.EXECUTING, now)) {
            return ApproveResult.WRONG_STATE;
        }
        // The run carries the plan it implements, so what was approved stays on record.
        Runs.insert(tx, new Runs.NewRun(taskId, Runs.nextSeq(tx, taskId), RunKind.EXECUTE, RunCause.APPROVAL, task.planJson(), who), now);
        Events.record(tx, taskId, null, who.ref(), Phase.AWAITING_APPROVAL, Phase.EXECUTING, "approved plan " + planSeq, now);
        Outbox.enqueueForRequester(tx, task, OutboxKind.EXECUTION_QUEUED,
                Json.object().put("taskId", taskId).put("by", who.name()), now);
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(wakeScheduler);
        logTransition(tx, taskId, Phase.AWAITING_APPROVAL, Phase.EXECUTING, who.ref());
        return ApproveResult.APPROVED;
    }
```

Replace `correct` with (a blank reply now comes after the verdict: see Review Focus 3):

```java
    public CorrectResult correct(Tx tx, Requester who, long taskId, int planSeq, String text, String originRef, String chatRef) {
        Instant now = clock.instant();
        TaskAccess.Verdict verdict = access.of(tx, who.ref(), taskId);
        Optional<TaskAccess.Refusal> refused = verdict.refusal(TaskAccess.Action.CORRECT, planSeq);
        if (refused.isPresent()) {
            refuse(tx, OutboxKind.CORRECTION_REFUSED, who, verdict, taskId, refused.get(), originRef, chatRef, now);
            return refused.get() == TaskAccess.Refusal.NOT_MEMBER ? CorrectResult.NOT_ALLOWED : CorrectResult.REFUSED;
        }
        if (text == null || text.isBlank()) {
            return CorrectResult.EMPTY;
        }
        if (!Tasks.changePhase(tx, taskId, Phase.AWAITING_APPROVAL, Phase.PLANNING, now)) {
            refuse(tx, OutboxKind.CORRECTION_REFUSED, who, verdict, taskId, TaskAccess.Refusal.STALE_PLAN, originRef, chatRef, now);
            return CorrectResult.REFUSED;
        }
        int seq = Runs.nextSeq(tx, taskId);
        Runs.insert(tx, new Runs.NewRun(taskId, seq, RunKind.PLAN, RunCause.CORRECTION, text.strip(), who), now);
        Events.record(tx, taskId, null, who.ref(), Phase.AWAITING_APPROVAL, Phase.PLANNING, "correction", now);
        enqueue(tx, taskId, OutboxKind.CORRECTION_QUEUED, chatRef, originRef,
                Json.object().put("taskId", taskId).put("by", who.name()), now);
        tx.afterCommit(wakeScheduler);
        logTransition(tx, taskId, Phase.AWAITING_APPROVAL, Phase.PLANNING, who.ref());
        return CorrectResult.CORRECTED;
    }
```

In `answer`, replace its first lines

```java
        Optional<AnswerResult> refused = questionRefusal(tx, who, taskId, planSeq, index);
```

with

```java
        TaskAccess.Verdict verdict = access.of(tx, who.ref(), taskId);
        Optional<AnswerResult> refused = answerRefusal(verdict, planSeq, index);
```

and, further down in `answer`, replace `Task task = Tasks.find(tx, taskId).orElseThrow();` with `Task task = verdict.task();`.

In `askForAnswer`, replace

```java
        Optional<AnswerResult> refused = questionRefusal(tx, who, taskId, planSeq, index);
```

with

```java
        Optional<AnswerResult> refused = answerRefusal(access.of(tx, who.ref(), taskId), planSeq, index);
```

Replace the whole `questionRefusal` method with:

```java
    /** Why question {@code index} of plan {@code planSeq} cannot be answered now, in the answer's terms; empty when it can. */
    private static Optional<AnswerResult> answerRefusal(TaskAccess.Verdict verdict, int planSeq, int index) {
        return verdict.answerRefusal(planSeq, index).map(refusal -> switch (refusal) {
            case NOT_MEMBER -> AnswerResult.NOT_ALLOWED;
            case NOT_FOUND -> AnswerResult.NOT_FOUND;
            case NOT_REQUESTER -> AnswerResult.NOT_REQUESTER;
            case ALREADY_ANSWERED -> AnswerResult.ALREADY_ANSWERED;
            case OUT_OF_ORDER -> AnswerResult.OUT_OF_ORDER;
            default -> AnswerResult.STALE;
        });
    }
```

Replace `reject` with:

```java
    public RejectResult reject(Tx tx, Requester who, long taskId, int planSeq) {
        Instant now = clock.instant();
        TaskAccess.Verdict verdict = access.of(tx, who.ref(), taskId);
        Optional<TaskAccess.Refusal> refused = verdict.refusal(TaskAccess.Action.REJECT, planSeq);
        if (refused.isPresent()) {
            return switch (refused.get()) {
                case NOT_MEMBER -> {
                    tx.afterCommit(() -> Log.warn("task.reject_not_allowed", "task", taskId, "requester", who.ref()));
                    yield RejectResult.NOT_ALLOWED;
                }
                case NOT_FOUND -> RejectResult.NOT_FOUND;
                case NOT_REQUESTER -> {
                    tx.afterCommit(() -> Log.info("task.reject_not_requester", "task", taskId, "requester", who.ref()));
                    yield RejectResult.NOT_REQUESTER;
                }
                case STALE_PLAN -> RejectResult.STALE_PLAN;
                default -> RejectResult.WRONG_STATE;
            };
        }
        Task task = verdict.task();
        if (!Tasks.changePhase(tx, taskId, Phase.AWAITING_APPROVAL, Phase.REJECTED, now)) {
            return RejectResult.WRONG_STATE;
        }
        Events.record(tx, taskId, null, who.ref(), Phase.AWAITING_APPROVAL, Phase.REJECTED, "rejected", now);
        enqueue(tx, taskId, OutboxKind.TASK_REJECTED, task.chatRef(), task.groupOriginRef(),
                Json.object().put("taskId", taskId).put("by", who.name()), now);
        GroupAcks.react(tx, task, GroupReaction.ENDED, now);
        logTransition(tx, taskId, Phase.AWAITING_APPROVAL, Phase.REJECTED, who.ref());
        return RejectResult.REJECTED;
    }
```

Replace `changePriority` with:

```java
    public PriorityResult changePriority(Tx tx, Requester who, long taskId, Priority priority) {
        Instant now = clock.instant();
        TaskAccess.Verdict verdict = access.of(tx, who.ref(), taskId);
        Optional<TaskAccess.Refusal> refused = verdict.refusal(TaskAccess.Action.PRIORITY);
        if (refused.isPresent()) {
            return switch (refused.get()) {
                case NOT_MEMBER -> PriorityResult.NOT_ALLOWED;
                case NOT_FOUND -> PriorityResult.NOT_FOUND;
                case NOT_REQUESTER -> PriorityResult.NOT_REQUESTER;
                default -> PriorityResult.FINISHED;
            };
        }
        Task task = verdict.task();
        if (task.priority() == priority) {
            return PriorityResult.UNCHANGED;
        }
        if (!Tasks.changePriority(tx, taskId, priority, now)) {
            return PriorityResult.FINISHED;
        }
        String change = "priority " + task.priority() + " -> " + priority;
        Events.record(tx, taskId, null, who.ref(), task.phase(), task.phase(), change, now);
        tx.afterCommit(() -> Log.info("task.priority_changed", "task", taskId, "from", task.priority(), "to", priority,
                "actor", who.ref()));
        return PriorityResult.CHANGED;
    }
```

Replace `cancel` with:

```java
    public CancelResult cancel(Tx tx, Requester who, long taskId, String originRef, String chatRef) {
        Instant now = clock.instant();
        TaskAccess.Verdict verdict = access.of(tx, who.ref(), taskId);
        Optional<TaskAccess.Refusal> refused = verdict.refusal(TaskAccess.Action.CANCEL);
        if (refused.isPresent()) {
            refuse(tx, OutboxKind.CANCEL_REFUSED, who, verdict, taskId, refused.get(), originRef, chatRef, now);
            return switch (refused.get()) {
                case NOT_MEMBER -> CancelResult.NOT_ALLOWED;
                case NOT_FOUND -> CancelResult.NOT_FOUND;
                default -> CancelResult.REFUSED;
            };
        }
        Task task = verdict.task();
        if (!Tasks.changePhase(tx, taskId, task.phase(), Phase.CANCELLED, now)) {
            refuse(tx, OutboxKind.CANCEL_REFUSED, who, verdict, taskId, TaskAccess.Refusal.WRONG_PHASE, originRef, chatRef, now);
            return CancelResult.REFUSED;
        }
        Runs.cancelQueued(tx, taskId, now);
        Events.record(tx, taskId, null, who.ref(), task.phase(), Phase.CANCELLED, "cancelled", now);
        ObjectNode cancelled = Json.object().put("taskId", taskId).put("by", who.name());
        enqueue(tx, taskId, OutboxKind.TASK_CANCELLED, task.chatRef(), task.groupOriginRef(), cancelled, now);
        if (!chatRef.equals(task.chatRef())) {
            // Sent from a private chat: answer there too, not only in the group.
            enqueue(tx, taskId, OutboxKind.TASK_CANCELLED, chatRef, originRef, cancelled, now);
        }
        GroupAcks.react(tx, task, GroupReaction.ENDED, now);
        tx.afterCommit(() -> activeRuns.stop(taskId, ActiveRuns.StopReason.CANCELLED));
        logTransition(tx, taskId, task.phase(), Phase.CANCELLED, who.ref());
        return CancelResult.CANCELLED;
    }
```

Replace `retry` with:

```java
    public RetryResult retry(Tx tx, Requester who, long taskId, String originRef, String chatRef) {
        Instant now = clock.instant();
        TaskAccess.Verdict verdict = access.of(tx, who.ref(), taskId);
        Optional<TaskAccess.Refusal> refused = verdict.refusal(TaskAccess.Action.RETRY);
        if (refused.isPresent()) {
            refuse(tx, OutboxKind.RETRY_REFUSED, who, verdict, taskId, refused.get(), originRef, chatRef, now);
            return switch (refused.get()) {
                case NOT_MEMBER -> RetryResult.NOT_ALLOWED;
                case NOT_FOUND -> RetryResult.NOT_FOUND;
                default -> RetryResult.REFUSED;
            };
        }
        // Task access saw the latest run fail: that run is the step to repeat.
        Run step = Runs.latest(tx, taskId).orElseThrow();
        RunKind kind;
        String instruction;
        if (step.kind() == RunKind.PLAN) {
            kind = RunKind.PLAN;
            instruction = step.instruction();
        } else if (step.kind() == RunKind.DELIVER || step.failureReason() == FailureReason.DELIVERY) {
            // The agent's work is done and waits in the worktree; only its summary is needed, as the commit message.
            kind = RunKind.DELIVER;
            instruction = step.kind() == RunKind.DELIVER ? step.instruction() : Runs.output(tx, taskId, step.seq()).orElse("");
        } else {
            kind = RunKind.EXECUTE;
            instruction = step.instruction();
        }
        Phase to = kind == RunKind.PLAN ? Phase.PLANNING : Phase.EXECUTING;
        if (!Tasks.changePhase(tx, taskId, Phase.FAILED, to, now)) {
            refuse(tx, OutboxKind.RETRY_REFUSED, who, verdict, taskId, TaskAccess.Refusal.NOT_FAILED, originRef, chatRef, now);
            return RetryResult.REFUSED;
        }
        int seq = Runs.nextSeq(tx, taskId);
        Runs.insert(tx, new Runs.NewRun(taskId, seq, kind, RunCause.RETRY, instruction, who), now);
        Events.record(tx, taskId, seq, who.ref(), Phase.FAILED, to, "retry of run " + step.seq(), now);
        enqueue(tx, taskId, OutboxKind.RETRY_QUEUED, chatRef, originRef,
                Json.object().put("taskId", taskId).put("by", who.name()).put("kind", kind.name()), now);
        tx.afterCommit(wakeScheduler);
        logTransition(tx, taskId, Phase.FAILED, to, who.ref());
        return RetryResult.RETRIED;
    }
```

Replace `followUp` with (as for `correct`, a blank follow-up now comes after the verdict):

```java
    public FollowUpResult followUp(Tx tx, Requester who, long taskId, String text, String originRef, String chatRef) {
        Instant now = clock.instant();
        TaskAccess.Verdict verdict = access.of(tx, who.ref(), taskId);
        Optional<TaskAccess.Refusal> refused = verdict.refusal(TaskAccess.Action.FOLLOW_UP);
        if (refused.isPresent()) {
            refuse(tx, OutboxKind.FOLLOW_UP_REFUSED, who, verdict, taskId, refused.get(), originRef, chatRef, now);
            return switch (refused.get()) {
                case NOT_MEMBER -> FollowUpResult.NOT_ALLOWED;
                case NOT_FOUND -> FollowUpResult.NOT_FOUND;
                default -> FollowUpResult.REFUSED;
            };
        }
        if (text == null || text.isBlank()) {
            return FollowUpResult.EMPTY;
        }
        Task task = verdict.task();
        if (!Tasks.changePhase(tx, taskId, task.phase(), Phase.EXECUTING, now)) {
            refuse(tx, OutboxKind.FOLLOW_UP_REFUSED, who, verdict, taskId, TaskAccess.Refusal.WRONG_PHASE, originRef, chatRef, now);
            return FollowUpResult.REFUSED;
        }
        int seq = Runs.nextSeq(tx, taskId);
        Runs.insert(tx, new Runs.NewRun(taskId, seq, RunKind.EXECUTE, RunCause.FOLLOW_UP, text.strip(), who), now);
        Events.record(tx, taskId, seq, who.ref(), task.phase(), Phase.EXECUTING, "follow-up", now);
        enqueue(tx, taskId, OutboxKind.FOLLOW_UP_QUEUED, chatRef, originRef, Json.object().put("taskId", taskId).put("by", who.name()), now);
        tx.afterCommit(wakeScheduler);
        logTransition(tx, taskId, task.phase(), Phase.EXECUTING, who.ref());
        return FollowUpResult.QUEUED;
    }
```

Delete `visibleTask` and `isRequester` (no callers remain; check with `grep -n 'visibleTask\|isRequester' src/main/java/dispatch/core/TaskService.java`). Keep `notRequester`. Add, next to `notRequester`:

```java
    /**
     * Answers a refused command where it was given, in the words the chat has always used: someone in no group is told they
     * may not use Dispatch, a task they may not see is not found, and anything else names its reason.
     */
    private void refuse(Tx tx, OutboxKind kind, Requester who, TaskAccess.Verdict verdict, long taskId, TaskAccess.Refusal refusal,
                        String originRef, String chatRef, Instant now) {
        switch (refusal) {
            case NOT_MEMBER -> notAllowed(tx, who, originRef, chatRef, now);
            case NOT_FOUND -> enqueue(tx, null, OutboxKind.TASK_NOT_FOUND, chatRef, originRef, Json.object().put("taskId", taskId), now);
            default -> enqueue(tx, taskId, kind, chatRef, originRef, refusalPayload(verdict.task(), refusal), now);
        }
    }

    /** What a refusal message carries; the renderer words it by {@code reason} and names the requester (ADR 0020). */
    private static ObjectNode refusalPayload(Task task, TaskAccess.Refusal refusal) {
        return switch (refusal) {
            case NOT_REQUESTER -> notRequester(task);
            case STALE_PLAN -> Json.object().put("taskId", task.id()).put("reason", "stale");
            case NOT_EXECUTED -> Json.object().put("taskId", task.id()).put("reason", "notExecuted").put("phase", task.phase().name());
            default -> Json.object().put("taskId", task.id()).put("reason", "phase").put("phase", task.phase().name());
        };
    }
```

- [ ] **Step 5: Run the command tests, then everything**

Run: `./mvnw -q test -Dtest='TaskLifecycleTest,TaskAccessTest,PlanQuestionsTest,UpdateHandlerTest,AssistantActionsTest,TasksApiTest,RunExecutorTest'`
Expected: PASS.

Run: `./mvnw -q test`
Expected: PASS. Any failure here is a behaviour change: stop and compare it with Review Focus 3 before changing a test.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dispatch/core/TaskService.java src/main/java/dispatch/core/AnswerResult.java \
        src/main/java/dispatch/telegram/UpdateHandler.java src/main/java/dispatch/ui/TasksApi.java \
        src/main/java/dispatch/core/AssistantActions.java src/test/java/dispatch/core/TaskLifecycleTest.java
git commit -F- <<'EOF'
Let every task command ask task access before it acts

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013bzgwsHrwA6N2BhRjVZ2Wz
EOF
```

---

### Task 3: Lists and task views see through task access

**Files:**
- Modify: `src/main/java/dispatch/store/Tasks.java` (`withPhase`, `finished`; new `visibleClause`)
- Modify: `src/main/java/dispatch/store/Runs.java` (`inProgress`)
- Modify: `src/main/java/dispatch/core/TaskService.java` (`status`, `statusPayload`, `history`, `historyPayload`, `timeline`, `timelinePayload`; delete `ownedBy`)
- Modify: `src/main/java/dispatch/telegram/UpdateHandler.java`, `src/main/java/dispatch/core/Assistant.java`, `src/main/java/dispatch/cli/AskCommand.java`, `src/main/java/dispatch/ui/TasksApi.java`
- Modify: `docs/ARCHITECTURE.md` ("Status, history and stats")
- Test: `src/test/java/dispatch/core/StatusAndHistoryTest.java`, `src/test/java/dispatch/core/TaskLifecycleTest.java`, `src/test/java/dispatch/cli/AskCommandTest.java`

**Interfaces:**
- Consumes: `TaskAccess.Viewer`, `TaskAccess.member/chat`, `TaskAccess.of(Tx, String, Task)`, `Verdict.allowed()`, `Action.json()`.
- Produces:
  - `TaskService.status(Tx, TaskAccess.Viewer, String originRef, String chatRef)`
  - `TaskService.statusPayload(Tx, TaskAccess.Viewer)` → every item has `"actions": [..]`; `"mine"` lists the tasks whose priority the viewer may change
  - `TaskService.history(Tx, TaskAccess.Viewer, String originRef, String chatRef)`, `historyPayload(Tx, TaskAccess.Viewer)` → items have `"actions"`
  - `TaskService.timeline(Tx, TaskAccess.Viewer, long taskId, String originRef, String chatRef)`, `timelinePayload(Tx, TaskAccess.Viewer, long taskId)` → has `"actions"`
  - `Tasks.withPhase(Tx, Phase, Set<String> projects, String requesterRef)`, `Tasks.finished(Tx, Set<String> projects, String requesterRef, int limit)`, `Runs.inProgress(Tx, Set<String> projects, String requesterRef)`

- [ ] **Step 1: Move the existing view tests to viewers, and add the new ones**

In `StatusAndHistoryTest`, promote `groups` to a field (`private Groups groups;`, and `groups = new Groups(…)` in `setUp`), then rewrite the 16 view calls:

```bash
sed -i -E 's/(tasks\.(status|history|timeline)\(tx, )LIFE, (BOLD\.ref\(\)|null), /\1new TaskAccess.Viewer(\3, LIFE), /' \
    src/test/java/dispatch/core/StatusAndHistoryTest.java
grep -c 'new TaskAccess.Viewer(' src/test/java/dispatch/core/StatusAndHistoryTest.java   # expect 16
```

In `TaskLifecycleTest.tasksStatusPayload()`, change the call to `tasks.statusPayload(tx, new TaskAccess.Viewer(BOLD.ref(), Set.of("autoland-management")))`.

Add to `StatusAndHistoryTest` (the order of `completed(…)` before `create(…)` matters: `claim()` takes the oldest queued run):

```java
    @Test
    void aRequesterWhoLeftTheGroupStillSeesTheirOwnTasksButNoLongerTheGroupsOthers() {
        long bolds = create("Add make help", "1");
        long alis = createFor(ALI, "life", "Rename the report", "2");
        groups.replace(new Config.Telegram(List.of(), List.of(
                new Config.Group("mobile", -100L, List.of(new Config.Member(200, "Ali")), List.of("life")),
                new Config.Group("backend", -200L, List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")),
                        List.of("alm")))));
        TaskAccess.Viewer bold = new TaskAccess(groups).member(BOLD.ref());

        JsonNode queued = db.transactionReturning(tx -> tasks.statusPayload(tx, bold)).path("queued");

        assertEquals(1, queued.size(), queued.toString());
        assertEquals(bolds, queued.get(0).path("taskId").asLong());
        assertTrue(db.transactionReturning(tx -> tasks.timelinePayload(tx, bold, bolds)).isPresent());
        assertTrue(db.transactionReturning(tx -> tasks.timelinePayload(tx, bold, alis)).isEmpty(),
                "the group's other tasks are no longer his to see");
    }

    @Test
    void aGroupChatSeesHeadlinesWithNothingToDo() {
        long id = create("Add make help", "1");

        JsonNode status = db.transactionReturning(tx -> tasks.statusPayload(tx, new TaskAccess.Viewer(null, LIFE)));

        assertEquals(id, status.path("queued").get(0).path("taskId").asLong());
        assertEquals("[]", status.path("queued").get(0).path("actions").toString(), "a chat acts on no task");
        assertEquals(0, status.path("mine").size(), "no priority buttons in a group");
    }

    @Test
    void eachListedTaskCarriesWhatTheViewerMayDoWithIt() {
        long done = completed("Rename the report", "2");
        create("Add make help", "1");

        JsonNode mine = db.transactionReturning(tx -> tasks.statusPayload(tx, new TaskAccess.Viewer(BOLD.ref(), LIFE)));
        JsonNode theirs = db.transactionReturning(tx -> tasks.statusPayload(tx, new TaskAccess.Viewer(ALI.ref(), LIFE)));
        JsonNode history = db.transactionReturning(tx -> tasks.historyPayload(tx, new TaskAccess.Viewer(BOLD.ref(), LIFE)));

        assertEquals("[\"priority\",\"cancel\"]", mine.path("queued").get(0).path("actions").toString());
        assertEquals("[]", theirs.path("queued").get(0).path("actions").toString(), "someone else's task is theirs to act on");
        assertEquals(done, history.path("tasks").get(0).path("taskId").asLong());
        assertEquals("[]", history.path("tasks").get(0).path("actions").toString(),
                "completed, and its execution never started an agent here: nothing to retry or follow up");
    }
```

In `AskCommandTest`, replace `aTaskOutsideTheMembersProjectsIsNotFound` with the two tests below, and in `tasksListsWhatIsActiveAndFinishedWithSomeoneElsesAsAHeadline` add, after the `openQuestions` assertion, `assertEquals("[\"correct\",\"answer\",\"reject\",\"priority\",\"cancel\"]", awaiting.path("actions").toString(), "the assistant reads what its member may do");`:

```java
    @Test
    void someoneElsesTaskOutsideTheMembersProjectsIsNotFound() {
        long taskId = create(BOLD, "Fix the login timeout");

        Answer answer = run(Map.of(AskCommand.MEMBER, ALI.ref(), AskCommand.PROJECTS, "other", AskCommand.DATABASE, dbFile.toString()),
                new Cli.Ask(taskId));

        assertEquals(1, answer.exitCode());
        assertEquals("not_found", answer.json().path("error").asText());
    }

    @Test
    void aMembersOwnTaskIsTheirsEvenOutsideTheirProjects() {
        long taskId = create(ALI, "Fix the login timeout");

        Answer answer = run(Map.of(AskCommand.MEMBER, ALI.ref(), AskCommand.PROJECTS, "other", AskCommand.DATABASE, dbFile.toString()),
                new Cli.Ask(taskId));

        assertEquals(0, answer.exitCode(), "ADR 0027: a requester's own task stays theirs after leaving its group");
        assertFalse(answer.json().path("headline").asBoolean(false));
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest='StatusAndHistoryTest,AskCommandTest'`
Expected: compilation FAILURE: `no suitable method found for status(Tx,TaskAccess.Viewer,…)`.

- [ ] **Step 3: Let the lists select the viewer's projects, or their own tasks**

In `Tasks.java`, replace `withPhase` and `finished` with:

```java
    /** Tasks in {@code phase} of {@code projects}, or given by {@code requesterRef}, the longest unchanged first. */
    public static List<Task> withPhase(Tx tx, Phase phase, Set<String> projects, String requesterRef) {
        List<Object> params = new ArrayList<>(List.of(phase));
        String visible = visibleClause("", projects, requesterRef, params);
        if (visible == null) {
            return List.of();
        }
        return tx.list("SELECT " + COLUMNS + " FROM task WHERE phase = ? AND " + visible + " ORDER BY updated_at, id",
                Tasks::map, params.toArray());
    }

    /** The {@code limit} most recently finished tasks of {@code projects}, or given by {@code requesterRef}, newest first. */
    public static List<Task> finished(Tx tx, Set<String> projects, String requesterRef, int limit) {
        List<Object> params = new ArrayList<>(List.of(Phase.COMPLETED, Phase.FAILED, Phase.REJECTED, Phase.CANCELLED));
        String visible = visibleClause("", projects, requesterRef, params);
        if (visible == null) {
            return List.of();
        }
        params.add(limit);
        return tx.list("SELECT " + COLUMNS + " FROM task WHERE phase IN (?, ?, ?, ?) AND " + visible
                + " ORDER BY completed_at DESC, id DESC LIMIT ?", Tasks::map, params.toArray());
    }

    /**
     * A viewer's list: tasks of their projects, and their own wherever they are (ADR 0027). Adds its parameters to
     * {@code params}; null when nothing can match, with no projects and no member.
     *
     * @param alias the task table's alias with its dot, e.g. {@code "t."}; empty when it has none
     */
    static String visibleClause(String alias, Set<String> projects, String requesterRef, List<Object> params) {
        List<String> terms = new ArrayList<>();
        if (!projects.isEmpty()) {
            terms.add(alias + "project IN (" + Tx.placeholders(projects.size()) + ")");
            params.addAll(projects);
        }
        if (requesterRef != null) {
            terms.add(alias + "requester_ref = ?");
            params.add(requesterRef);
        }
        return terms.isEmpty() ? null : "(" + String.join(" OR ", terms) + ")";
    }
```

In `Runs.java`, replace `inProgress` with:

```java
    /** Running and queued runs of {@code projects}' tasks, or of tasks given by {@code requesterRef}, oldest queued first. */
    public static List<InProgress> inProgress(Tx tx, Set<String> projects, String requesterRef) {
        List<Object> params = new ArrayList<>(List.of(RunStatus.RUNNING, RunStatus.QUEUED));
        String visible = Tasks.visibleClause("t.", projects, requesterRef, params);
        if (visible == null) {
            return List.of();
        }
        return tx.list("SELECT r.task_id, r.seq, r.kind, r.status, r.queued_at, r.started_at, t.project, t.title"
                        + " FROM run r JOIN task t ON t.id = r.task_id"
                        + " WHERE r.status IN (?, ?) AND " + visible
                        + " ORDER BY r.queued_at, r.task_id, r.seq",
                Runs::mapInProgress, params.toArray());
    }
```

- [ ] **Step 4: Let the views take a viewer and list each task's actions**

In `TaskService`, add the imports `java.util.Collection` and `java.util.HashMap`. Replace `status` and `statusPayload` with:

```java
    /**
     * Posts what Dispatch is doing on the tasks {@code viewer} sees: running runs with their agent's latest action, then
     * queued runs, then plans awaiting approval.
     */
    public void status(Tx tx, TaskAccess.Viewer viewer, String originRef, String chatRef) {
        enqueue(tx, null, OutboxKind.STATUS, chatRef, originRef, statusPayload(tx, viewer), clock.instant());
    }

    /**
     * The content of a status message, also used to update one in place after a priority change. Every task carries what
     * the viewer may do with it now ({@code actions}); {@code mine} lists the tasks whose priority they may change, which a
     * private chat offers as buttons.
     */
    public ObjectNode statusPayload(Tx tx, TaskAccess.Viewer viewer) {
        ObjectNode payload = Json.object();
        Map<Long, Task> active = new LinkedHashMap<>();
        for (Task task : Tasks.active(tx)) {
            if (viewer.sees(task) != TaskAccess.Sight.NONE) {
                active.put(task.id(), task);
            }
        }
        Map<Long, List<TaskAccess.Action>> actions = actionsOf(tx, viewer, active.values());
        ArrayNode running = payload.putArray("running");
        ArrayNode queued = payload.putArray("queued");
        Instant workerSeenSince = requiresWorker ? clock.instant().minus(Workers.SEEN_WITHIN) : null;
        for (Runs.InProgress run : Runs.inProgress(tx, viewer.projects(), viewer.ref())) {
            Task task = active.get(run.taskId());
            boolean own = isOwn(viewer, task);
            boolean isRunning = run.status() == RunStatus.RUNNING;
            ObjectNode item = (isRunning ? running : queued).addObject().put("taskId", run.taskId()).put("project", run.project())
                    .put("title", run.title()).put("kind", run.kind().name()).put("priority", priority(task))
                    // Who gave it: the name for an admin's Tasks page to show (ADR 0020 allows it), and a flag for the
                    // Mini App's My tasks page, which keeps only the viewer's own and cannot go by name — two members
                    // may share a first name.
                    .put("requester", requesterName(task))
                    .put("mine", own);
            putActions(item, actions.get(run.taskId()));
            if (isRunning) {
                item.put("startedAt", text(run.startedAt()));
                if (own) {
                    activeRuns.activity(run.taskId()).ifPresent(activity ->
                            item.put("steps", activity.steps()).put("lastAction", activity.lastAction()));
                }
            } else {
                item.put("queuedAt", text(run.queuedAt()));
                boolean waitingForWorker = requiresWorker && task != null && waitsForWorker(tx, task, workerSeenSince);
                if (waitingForWorker) {
                    item.put("waitingForWorker", true);
                }
                // An offline computer's last blocker is stale until the scheduler's next pass clears it; offline says more.
                String blocked = requiresWorker && !waitingForWorker ? Tasks.blockedReason(tx, run.taskId()) : null;
                if (blocked != null) {
                    item.put("blocked", blocked);
                }
            }
        }
        ArrayNode awaiting = payload.putArray("awaitingApproval");
        for (Task task : Tasks.withPhase(tx, Phase.AWAITING_APPROVAL, viewer.projects(), viewer.ref())) {
            boolean own = isOwn(viewer, task);
            ObjectNode item = awaiting.addObject().put("taskId", task.id()).put("project", task.project()).put("title", task.title())
                    .put("priority", task.priority().name()).put("requester", task.requester().name())
                    .put("mine", own).put("since", text(task.updatedAt()));
            putActions(item, actions.get(task.id()));
            if (own) {
                // What the Mini App's home shows on the requester's own waiting task: the question it waits on, if any.
                currentPlan(tx, task.id()).ifPresent(plan -> {
                    List<JsonNode> open = new ArrayList<>();
                    plan.withArray("questions").forEach(question -> {
                        if (question.path("answer").isNull()) {
                            open.add(question);
                        }
                    });
                    item.put("openQuestions", open.size());
                    if (!open.isEmpty()) {
                        item.put("question", open.getFirst().path("text").asText());
                    }
                });
            }
        }
        ArrayNode prioritized = payload.putArray("mine");
        // A private chat's priority buttons: the tasks whose priority task access lets this viewer change.
        active.values().stream().filter(task -> actions.getOrDefault(task.id(), List.of()).contains(TaskAccess.Action.PRIORITY))
                .forEach(task -> prioritized.addObject().put("taskId", task.id()).put("priority", task.priority().name()));
        return payload;
    }
```

Replace `history` and `historyPayload` with:

```java
    /**
     * Posts the most recently finished tasks {@code viewer} sees, newest first; the total cost is shown only on the viewer's
     * own tasks, everyone else's carry just the headline (ADR 0020).
     */
    public void history(Tx tx, TaskAccess.Viewer viewer, String originRef, String chatRef) {
        enqueue(tx, null, OutboxKind.HISTORY, chatRef, originRef, historyPayload(tx, viewer), clock.instant());
    }

    /** The content of a history message; also what the Mini App's task list reads (spec: Task pages). */
    public ObjectNode historyPayload(Tx tx, TaskAccess.Viewer viewer) {
        List<Task> finished = Tasks.finished(tx, viewer.projects(), viewer.ref(), HISTORY_SIZE);
        Map<Long, BigDecimal> costs = Runs.costs(tx, finished.stream().map(Task::id).toList());
        Map<Long, List<TaskAccess.Action>> actions = actionsOf(tx, viewer, finished);
        ObjectNode payload = Json.object();
        ArrayNode listed = payload.putArray("tasks");
        for (Task task : finished) {
            boolean own = isOwn(viewer, task);
            BigDecimal cost = own ? costs.get(task.id()) : null;
            ObjectNode item = listed.addObject().put("taskId", task.id()).put("project", task.project()).put("title", task.title())
                    .put("mine", own)
                    .put("phase", task.phase().name()).put("priority", task.priority().name())
                    .put("requester", task.requester().name()).put("createdAt", text(task.createdAt())).put("prUrl", task.prUrl()).put("failureReason", name(task.failureReason()))
                    .put("costUsd", cost == null ? null : cost.toPlainString()).put("completedAt", text(task.completedAt()));
            putActions(item, actions.get(task.id()));
        }
        return payload;
    }
```

Replace `timeline` and the head of `timelinePayload` (up to and including its headline return) with:

```java
    /**
     * Posts one task's timeline: its runs in order, how it ended, and what it cost. A task {@code viewer} does not see is
     * not found. Someone else's task stops at the headline: no runs, no cost (ADR 0020).
     */
    public void timeline(Tx tx, TaskAccess.Viewer viewer, long taskId, String originRef, String chatRef) {
        Optional<ObjectNode> payload = timelinePayload(tx, viewer, taskId);
        enqueue(tx, null, payload.isPresent() ? OutboxKind.TASK_TIMELINE : OutboxKind.TASK_NOT_FOUND, chatRef, originRef,
                payload.orElseGet(() -> Json.object().put("taskId", taskId)), clock.instant());
    }

    /**
     * One task's timeline, or empty when {@code viewer} does not see it — so a task's existence does not leak. Someone
     * else's task stops at the headline here too (ADR 0020).
     */
    public Optional<ObjectNode> timelinePayload(Tx tx, TaskAccess.Viewer viewer, long taskId) {
        Optional<Task> found = Tasks.find(tx, taskId).filter(task -> viewer.sees(task) != TaskAccess.Sight.NONE);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Task task = found.get();
        ObjectNode payload = Json.object().put("taskId", task.id()).put("project", task.project()).put("title", task.title())
                .put("requester", task.requester().name()).put("phase", task.phase().name()).put("priority", task.priority().name())
                .put("prUrl", task.prUrl())
                .put("failureReason", name(task.failureReason())).put("createdAt", text(task.createdAt()))
                .put("completedAt", text(task.completedAt()));
        putActions(payload, actionsOf(tx, viewer, List.of(task)).get(task.id()));
        if (!isOwn(viewer, task)) {
            return Optional.of(payload.put("headline", true).putNull("costUsd"));
        }
```

(the rest of `timelinePayload`, from `ArrayNode runs = payload.putArray("runs");` on, is unchanged). Replace `ownedBy` with these helpers:

```java
    /** Whether {@code viewer} gave the task and so sees all of it; a group chat owns nothing (ADR 0020). */
    private static boolean isOwn(TaskAccess.Viewer viewer, Task task) {
        return task != null && viewer.sees(task) == TaskAccess.Sight.FULL;
    }

    /** What {@code viewer} may do with each listed task now; nothing for a group chat, which acts on no task (ADR 0027). */
    private Map<Long, List<TaskAccess.Action>> actionsOf(Tx tx, TaskAccess.Viewer viewer, Collection<Task> listed) {
        Map<Long, List<TaskAccess.Action>> actions = new HashMap<>();
        if (viewer.ref() == null) {
            return actions;
        }
        // ponytail: one verdict per listed task (a few indexed reads each); batch the run reads if a list ever holds hundreds.
        for (Task task : listed) {
            actions.put(task.id(), access.of(tx, viewer.ref(), task).allowed());
        }
        return actions;
    }

    private static void putActions(ObjectNode item, List<TaskAccess.Action> actions) {
        ArrayNode listed = item.putArray("actions");
        if (actions != null) {
            actions.forEach(action -> listed.add(action.json()));
        }
    }
```

- [ ] **Step 5: Give every caller a viewer**

`UpdateHandler`: add the field `private final TaskAccess access;` and, in the 17-argument constructor, `this.access = new TaskAccess(groups);`. In `onChatMessage`, replace

```java
        Set<String> visible = privateChat ? groups.projectsOfMember(who.ref()) : groups.projectsOfChat(chatRef);
```

with

```java
        TaskAccess.Viewer viewer = privateChat ? access.member(who.ref()) : access.chat(chatRef);
        Set<String> visible = viewer.projects();
```

and its `status` and `history` cases with:

```java
            case "status" -> tasks.status(tx, viewer, origin, chatRef);
            case "history" -> taskId(command.args()).ifPresentOrElse(
                    id -> tasks.timeline(tx, viewer, id, origin, chatRef),
                    () -> tasks.history(tx, viewer, origin, chatRef));
```

In the priority button's redraw, replace

```java
        Set<String> visible = privateChat ? groups.projectsOfMember(who.ref()) : groups.projectsOfChat(Refs.chat(chatId));
        ObjectNode payload = tasks.statusPayload(tx, visible, privateChat ? who.ref() : null);
```

with

```java
        TaskAccess.Viewer viewer = privateChat ? access.member(who.ref()) : access.chat(Refs.chat(chatId));
        ObjectNode payload = tasks.statusPayload(tx, viewer);
```

Add `import dispatch.core.TaskAccess;` to `UpdateHandler`.

`Assistant`: change the call `snapshot(tx, visible, who.ref(), started)` to `snapshot(tx, new TaskAccess.Viewer(who.ref(), visible), started)`, and `snapshot` to:

```java
    private String snapshot(Tx tx, TaskAccess.Viewer viewer, Instant now) {
        ObjectNode status = tasks.statusPayload(tx, viewer);
        ObjectNode snapshot = Json.object().put("now", now.toString());
        for (String list : List.of("awaitingApproval", "running", "queued")) {
            ArrayNode mine = snapshot.putArray(list);
            status.withArray(list).forEach(item -> {
                if (item.path("mine").asBoolean(false)) {
                    mine.add(item);
                }
            });
        }
        ArrayNode listed = snapshot.putArray("projects");
        projects.all().stream().filter(project -> viewer.projects().contains(project.name()))
                .forEach(project -> listed.addObject().put("name", project.name()).put("alias", project.alias()));
        return snapshot.toString();
    }
```

`AskCommand`: add the imports `dispatch.config.Config` and `dispatch.core.TaskAccess`; replace the lines from `// Only the read-only payloads are used…` through the `TaskService tasks = …` statement with:

```java
        Optional<Long> memberId = telegramId(member);
        if (memberId.isEmpty()) {
            out.println(error("not_configured", "dispatch ask answers only inside the bot's assistant"));
            return 2;
        }
        // The member and their projects as the bot's groups have them: all task access needs to decide what they see and
        // may do. The config itself is not the assistant's to read.
        Groups asked = new Groups(List.of(new Config.Group("ask", null, List.of(new Config.Member(memberId.get(), "")),
                List.copyOf(visible))));
        TaskService tasks = new TaskService(asked, new Projects(List.of(), project -> Optional.empty()), new ActiveRuns(),
                Clock.systemUTC(), () -> { }, () -> { });
        TaskAccess.Viewer viewer = new TaskAccess.Viewer(member, visible);
```

then pass `viewer` in the three calls — `tasks.statusPayload(tx, viewer)`, `tasks.historyPayload(tx, viewer)`, `tasks.timelinePayload(tx, viewer, ask.taskId())` — change the not-found message to `"no task #" + ask.taskId() + " this member may see"`, and add:

```java
    private static Optional<Long> telegramId(String ref) {
        if (!ref.startsWith("telegram:")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(ref.substring("telegram:".length())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
```

`TasksApi`: add the field `private final TaskAccess access;`, set `this.access = new TaskAccess(groups);` in the constructor, and pass `access.member(caller.ref())` wherever it passed `visible, caller.ref()`: in `list` (`statusPayload(tx, viewer)`, `historyPayload(tx, viewer)`, with `TaskAccess.Viewer viewer = access.member(caller.ref());` replacing the `Set<String> visible` line), in `timeline` and in `ownTask` (`tasks.timelinePayload(tx, access.member(caller.ref()), taskId)`). Add `import dispatch.core.TaskAccess;`.

- [ ] **Step 6: Document what lists hold**

In `docs/ARCHITECTURE.md`, in the paragraph starting `**Status, history and stats**`, replace `a member privately those of all their groups;` with `a member privately those of all their groups and their own tasks wherever they are (ADR 0027);`.

- [ ] **Step 7: Run the view tests, then everything**

Run: `./mvnw -q test -Dtest='StatusAndHistoryTest,AskCommandTest,TasksApiTest,UpdateHandlerTest,AssistantTest,TaskLifecycleTest'`
Expected: PASS.

Run: `./mvnw -q test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dispatch/store/Tasks.java src/main/java/dispatch/store/Runs.java \
        src/main/java/dispatch/core/TaskService.java src/main/java/dispatch/telegram/UpdateHandler.java \
        src/main/java/dispatch/core/Assistant.java src/main/java/dispatch/cli/AskCommand.java \
        src/main/java/dispatch/ui/TasksApi.java docs/ARCHITECTURE.md \
        src/test/java/dispatch/core/StatusAndHistoryTest.java src/test/java/dispatch/core/TaskLifecycleTest.java \
        src/test/java/dispatch/cli/AskCommandTest.java
git commit -F- <<'EOF'
Show each member their own tasks wherever they are, with what they may do

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013bzgwsHrwA6N2BhRjVZ2Wz
EOF
```

---

### Task 4: The assistant, the Mini App's routes and the plan's buttons ask task access

**Files:**
- Modify: `src/main/java/dispatch/core/AssistantActions.java` (`check` and its helpers)
- Modify: `src/main/java/dispatch/ui/TasksApi.java` (`ownTask`, `answer`; delete `firstUnanswered`)
- Modify: `src/main/java/dispatch/telegram/Renderer.java` (`plan`'s buttons)
- Test: `src/test/java/dispatch/ui/TasksApiTest.java`

**Interfaces:**
- Consumes: `TaskAccess.of`, `Verdict.refusal/answerRefusal/planSeq/currentQuestion/sight/task`, `TaskAccess.decisions(int)`, `TaskService.timelinePayload(Tx, Viewer, long)` (now with `actions`).
- Produces: the Mini App's detail has `"actions"` and `"plan": {"current": N, …}`; list rows have `"actions"` (Task 3).

- [ ] **Step 1: Write the failing route tests**

Add to `TasksApiTest`:

```java
    @Test
    void theGroupViewOffersAnAdminOnlyTheCancelOfSomeoneElsesTask() {
        long alis = create(ALI, "Add the export button");
        long boldsOwn = create(BOLD, "Fix the login timeout");

        JsonNode listed = api.list(BOLD_CALLER, Json.object().put("scope", "group"));

        assertEquals("[\"cancel\"]", item(listed, alis).path("actions").toString(), "an admin stops it, never decides it");
        assertEquals("[\"priority\",\"cancel\"]", item(listed, boldsOwn).path("actions").toString());
    }

    @Test
    void theDetailSaysWhatTheRequesterMayDoAndWhichQuestionIsAsked() {
        long taskId = planned(ALI, twoQuestions());

        JsonNode detail = api.detail(ALI_CALLER, Json.object().put("taskId", taskId));
        JsonNode answered = api.answer(ALI_CALLER,
                Json.object().put("taskId", taskId).put("planSeq", 1).put("index", 1).put("option", 0));

        assertEquals("[\"correct\",\"answer\",\"reject\",\"priority\",\"cancel\"]", detail.path("actions").toString());
        assertEquals(1, detail.path("plan").path("current").asInt());
        assertEquals(2, answered.path("plan").path("current").asInt(), "the next question is the one to answer");
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest=TasksApiTest`
Expected: FAIL in `theDetailSaysWhatTheRequesterMayDoAndWhichQuestionIsAsked` (`plan.current` is missing). `theGroupViewOffersAnAdminOnlyTheCancelOfSomeoneElsesTask` already passes after Task 3; it stays as the page's pin.

- [ ] **Step 3: Let the Mini App's routes read the verdict**

In `TasksApi`, replace `ownTask` with:

```java
    /** The caller's own task with its plan, what they may do with it, and the question to answer now (ADR 0027). */
    private ObjectNode ownTask(Tx tx, Caller caller, long taskId) {
        TaskAccess.Verdict verdict = access.of(tx, caller.ref(), taskId);
        if (verdict.sight() == TaskAccess.Sight.NONE) {
            throw notFound(taskId);
        }
        if (verdict.sight() == TaskAccess.Sight.HEADLINE) {
            throw new ApiException(403, "not_yours", NOT_YOURS);
        }
        ObjectNode task = tasks.timelinePayload(tx, access.member(caller.ref()), taskId).orElseThrow(() -> notFound(taskId));
        task.remove("runs");
        tasks.currentPlan(tx, taskId).ifPresent(plan -> task.set("plan", plan.put("current", verdict.currentQuestion())));
        return task;
    }
```

In `answer`, delete the block from `ObjectNode task = ownTask(tx, caller, taskId);` through the `out_of_order` `if` (the command now refuses a stale plan and an answer out of order itself, and its result switch already maps both), so the lambda starts at `String questionRef = Outbox.sentQuestion(…)`. Update `answer`'s javadoc: `Questions are answered in order, as the chat asks them one at a time` stays true — the command enforces it now. Delete `firstUnanswered`.

- [ ] **Step 4: Let the assistant's proposals read the verdict**

In `AssistantActions`, add the field `private final TaskAccess access;` and, in the constructor, `this.access = new TaskAccess(groups);`. Replace `check`, `answer`, `decision` and `followUp` with:

```java
    /** One of the assistant's proposed actions, as its structured output gave it. */
    public Checked check(Tx tx, Requester who, JsonNode action) {
        String type = action.path("type").asText();
        if (type.equals("draft")) {
            return draft(who, action);
        }
        long taskId = action.path("task").asLong(0);
        ObjectNode payload = Json.object().put("type", type).put("taskId", taskId);
        TaskAccess.Verdict verdict = access.of(tx, who.ref(), taskId);
        if (verdict.sight() == TaskAccess.Sight.FULL) {
            payload.put("title", verdict.task().title());
        }
        return switch (type) {
            case "answer" -> answer(tx, verdict, action, payload);
            case "approve" -> decision(verdict, TaskAccess.Action.APPROVE, payload);
            case "reject" -> decision(verdict, TaskAccess.Action.REJECT, payload);
            case "cancel" -> allowed(verdict.refusal(TaskAccess.Action.CANCEL), payload);
            case "retry" -> allowed(verdict.refusal(TaskAccess.Action.RETRY), payload);
            case "followUp" -> followUp(verdict, action, payload);
            default -> note(payload, "unknown");
        };
    }
```

```java
    private Checked answer(Tx tx, TaskAccess.Verdict verdict, JsonNode action, ObjectNode payload) {
        Optional<TaskAccess.Refusal> refused = verdict.refusal(TaskAccess.Action.ANSWER);
        // "No question is open" is only a refusal once we know which question was named.
        if (refused.isPresent() && refused.get() != TaskAccess.Refusal.ALREADY_ANSWERED) {
            return note(payload, reason(refused.get()));
        }
        int index = action.path("question").asInt(0);
        JsonNode question = null;
        for (JsonNode candidate : tasks.currentPlan(tx, verdict.task().id()).orElseThrow().withArray("questions")) {
            if (candidate.path("index").asInt() == index) {
                question = candidate;
            }
        }
        if (question == null) {
            return note(payload, "noQuestion");
        }
        Optional<TaskAccess.Refusal> answerRefused = verdict.answerRefusal(verdict.planSeq(), index);
        if (answerRefused.isPresent()) {
            return note(payload, reason(answerRefused.get()));
        }
        payload.put("planSeq", verdict.planSeq()).put("question", index);
        JsonNode options = question.path("options");
        int option = action.path("option").asInt(0);
        if (option >= 1 && option <= options.size()) {
            return new Checked(true, payload.put("option", option - 1).put("answer", options.get(option - 1).asText()));
        }
        if (action.path("decide").asBoolean(false)) {
            return new Checked(true, payload.put("answer", youDecide));
        }
        String text = action.path("text").asText("").strip();
        return text.isEmpty() ? note(payload, "empty") : shown(text) ? new Checked(true, payload.put("answer", text)) : note(payload, "tooLong");
    }

    /** Approval needs a plan without questions: answering them makes the agent plan again, and that plan is approved. */
    private static Checked decision(TaskAccess.Verdict verdict, TaskAccess.Action action, ObjectNode payload) {
        Optional<TaskAccess.Refusal> refused = verdict.refusal(action);
        if (refused.isPresent()) {
            return note(payload, reason(refused.get()));
        }
        return new Checked(true, payload.put("planSeq", verdict.planSeq()));
    }

    /** A follow-up continues the task's building session, so the task must have got as far as execution. */
    private static Checked followUp(TaskAccess.Verdict verdict, JsonNode action, ObjectNode payload) {
        Optional<TaskAccess.Refusal> refused = verdict.refusal(TaskAccess.Action.FOLLOW_UP);
        if (refused.isPresent()) {
            return note(payload, reason(refused.get()));
        }
        String text = action.path("text").asText("").strip();
        if (text.isEmpty()) {
            return note(payload, "empty");
        }
        return shown(text) ? new Checked(true, payload.put("text", text)) : note(payload, "tooLong");
    }

    private static Checked allowed(Optional<TaskAccess.Refusal> refused, ObjectNode payload) {
        return refused.map(refusal -> note(payload, reason(refusal))).orElseGet(() -> new Checked(true, payload));
    }

    /** The note (assistant.note.*) the reply shows for a proposal task access refuses. */
    private static String reason(TaskAccess.Refusal refusal) {
        return switch (refusal) {
            case NOT_MEMBER, NOT_FOUND -> "notFound";
            case NOT_REQUESTER -> "notYours";
            case OPEN_QUESTIONS -> "openQuestions";
            case OUT_OF_ORDER -> "order";
            case ALREADY_ANSWERED -> "answered";
            case WRONG_PHASE, STALE_PLAN, NOT_FAILED, NOT_EXECUTED -> "phase";
        };
    }
```

Remove the imports this leaves unused (`dispatch.domain.Phase`, `dispatch.domain.RunKind`, `dispatch.domain.Task`, `dispatch.store.Runs`, `dispatch.store.Tasks`) — `./mvnw -q test` compiles regardless; keep the file clean.

- [ ] **Step 5: Let the plan's buttons read task access**

In `Renderer.plan`, keep `boolean openQuestions = …` (the questions section below still uses it) and replace the `List<List<Button>> buttons = …;` statement with:

```java
        // The buttons are task access's: no Approve while the plan asks questions (G-1d, ADR 0027); either way the answers
        // come back as a correction.
        List<Button> decisions = new ArrayList<>();
        for (TaskAccess.Action action : TaskAccess.decisions(plan.path("questions").size())) {
            decisions.add(action == TaskAccess.Action.APPROVE
                    ? new Button(text("button.approve"), "approve:" + planRef)
                    : new Button(text("button.reject"), "reject:" + planRef));
        }
        List<List<Button>> buttons = List.of(decisions);
```

Add `import dispatch.core.TaskAccess;` to `Renderer` (`telegram` may import `core`; `core` never imports `telegram`).

- [ ] **Step 6: Run the channel tests, then everything**

Run: `./mvnw -q test -Dtest='TasksApiTest,AssistantActionsTest,AssistantTest,RendererTest,PlanQuestionsTest,UpdateHandlerTest'`
Expected: PASS — `RendererTest`'s two plan tests and `AssistantActionsTest` pass unchanged: they are these channels' relay tests.

Run: `./mvnw -q test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dispatch/core/AssistantActions.java src/main/java/dispatch/ui/TasksApi.java \
        src/main/java/dispatch/telegram/Renderer.java src/test/java/dispatch/ui/TasksApiTest.java
git commit -F- <<'EOF'
Let the assistant, the Mini App's routes and the plan's buttons ask task access

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013bzgwsHrwA6N2BhRjVZ2Wz
EOF
```

---

### Task 5: The Mini App shows what the server allows; the ADRs catch up

**Files:**
- Modify: `ui/src/api.ts`, `ui/src/mini/TasksPage.tsx`, `ui/src/mini/TicketSheet.tsx`
- Modify: `ui/src/mini/fixtures.ts`
- Test: `ui/src/mini/TasksPage.test.tsx`, `ui/src/mini/HomePage.test.tsx` (and any test `npm run typecheck` points at)
- Modify: `docs/adr/0027-one-module-decides-what-a-member-may-see-and-do-with-a-task.md`, `docs/adr/0012-one-bot-for-several-groups-tasks-given-privately.md`

**Interfaces:**
- Consumes: `"actions"` on list rows and on the detail; `"plan": {"current": N}` on the detail (Tasks 3 and 4).
- Produces: `type TaskAction`, `TaskRow.actions`, `TaskDetail.actions`, `PlanView.current`.

- [ ] **Step 1: Give the fixtures what the server now sends, and write the failing page tests**

In `ui/src/mini/fixtures.ts`: add `actions: ["priority", "cancel"],` to `myRunningTask`; change `myFinishedTask`'s comment to `/** Bold's own task, completed: nothing to retry. */` and add `actions: ["followUp"],`; add `actions: ["cancel"],` to `someoneElsesTask`; add `actions: ["correct", "answer", "reject", "priority", "cancel"],` to `waitingOnQuestion` and `actions: ["approve", "correct", "reject", "priority", "cancel"],` to `waitingOnApproval`; add `current: 1,` to `planWithQuestions`; make `detailOf` return `{ …, plan, actions: task.actions }`; and add:

```ts
/** Bold's own task, failed, so it may be retried. */
export const myFailedTask: TaskRow = {
  taskId: 6, project: "alm", title: "Rename the export column", state: "finished", priority: "NORMAL", requester: "Bold",
  mine: true, phase: "FAILED", failureReason: "AGENT", completedAt: "2026-09-23T08:30:00Z", actions: ["retry", "followUp"],
};
```

In `TasksPage.test.tsx`, import `myFailedTask` and replace `it("retries a finished task of mine without asking", …)` with:

```tsx
  it("retries a failed task of mine without asking", async () => {
    vi.mocked(api.listTasks).mockResolvedValue({ tasks: [myFailedTask] });
    vi.mocked(api.retryTask).mockResolvedValue({ result: "RETRIED" });

    render(<TasksPage scope="me" />);
    fireEvent.click(await screen.findByRole("button", { name: "Дахин эхлүүлэх 6" }));

    await waitFor(() => expect(api.retryTask).toHaveBeenCalledWith(6));
  });

  it("offers only what the server allows: no retry on a task that did not fail", async () => {
    vi.mocked(api.listTasks).mockResolvedValue({ tasks: [myFinishedTask] });

    render(<TasksPage scope="me" />);

    expect(await screen.findByText(/Add the export button/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Дахин эхлүүлэх 2" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Цуцлах 2" })).not.toBeInTheDocument();
  });
```

In `HomePage.test.tsx`: in `answers the questions in the sheet one at a time…`, build `afterFirst` from `{ ...planWithQuestions, current: 2, questions: [ … ] }`; in `approves a plan with no open questions` and `rejects only after one more tap`, use `{ ...planWithQuestions, planSeq: 2, current: 0, questions: [] }`.

- [ ] **Step 2: Run the UI checks to see them fail**

Run: `cd ui && npm run typecheck`
Expected: FAIL — `Object literal may only specify known properties, and 'actions' does not exist in type 'TaskRow'` (and `current` in `PlanView`).

- [ ] **Step 3: Add the fields and render from them**

In `ui/src/api.ts`, before `export interface TaskRow`:

```ts
/** What the viewer may do with a task now, as the server's task access decided (ADR 0027). */
export type TaskAction = "approve" | "correct" | "answer" | "reject" | "priority" | "cancel" | "retry" | "followUp";
```

In `TaskRow`, after `mine: boolean;`:

```ts
  /** What the viewer may do with it now; a button whose action is not here is not shown (ADR 0027). */
  actions: TaskAction[];
```

In `PlanView`, after `planSeq: number;`:

```ts
  /** The question to answer now, 1-based; 0 when none is open. */
  current: number;
```

In `TaskDetail`, after `costUsd: string | null;`: `actions: TaskAction[];`

In `ui/src/mini/TasksPage.tsx`: delete `const CANCELLABLE: TaskState[] = …;`; change `{CANCELLABLE.includes(task.state) && (` to `{task.actions.includes("cancel") && (`; delete the comment `{/* Someone else's task is an admin's to stop, never to start again for them. */}` and change `{task.mine && task.state === "finished" && (` to `{task.actions.includes("retry") && (`. In the component's doc comment, replace `The two differ in which list they ask for and whether a row may be retried, not in how they look.` with `The two differ only in which list they ask for; what a row offers is the server's (ADR 0027).`

In `ui/src/mini/TicketSheet.tsx`, replace

```tsx
  const plan = detail?.plan;
  const awaiting = detail?.phase === "AWAITING_APPROVAL" && plan !== undefined;
  const open = plan?.questions.filter((question) => question.answer === null) ?? [];
  const current = awaiting ? open[0] : undefined;
```

with

```tsx
  const plan = detail?.plan;
  // What the sheet offers is the server's (ADR 0027): a decision while the plan waits for one, and the question to answer.
  const decides = detail?.actions.includes("reject") ?? false;
  const mayApprove = detail?.actions.includes("approve") ?? false;
  const current = plan?.current ?? 0;
```

the `Question` element's props `current={question === current} later={awaiting && question.answer === null && question !== current}` with `current={question.index === current} later={question.answer === null && current > 0 && question.index > current}`, and the actions block with:

```tsx
        <div className="sheet-actions">
          {decides && plan
            ? <Decide busy={busy} mayApprove={mayApprove}
                      onApprove={() => void act(() => approvePlan(task.taskId, plan.planSeq), () => onDecided(task.taskId, "approved"))}
                      onReject={() => void act(() => rejectPlan(task.taskId, plan.planSeq), () => onDecided(task.taskId, "rejected"))} />
            : <button type="button" className="sheet-reject" style={{ color: "var(--link)" }} onClick={onClose}>Хаах</button>}
        </div>
```

and `Decide` with:

```tsx
/** Approve, or reject after one more tap: rejecting ends the task, and Telegram's webview shows no confirm() dialog. */
function Decide({ busy, mayApprove, onApprove, onReject }: {
  busy: boolean;
  /** False while the plan asks questions: their answers make the agent plan again, and that plan is approved. */
  mayApprove: boolean;
  onApprove: () => void;
  onReject: () => void;
}) {
  const [asking, setAsking] = useState(false);
  if (asking) {
    return (
      <>
        <p className="note">Татгалзвал даалгавар энд дуусна.</p>
        <button type="button" className="ticket-go" style={{ marginTop: 0, background: "var(--danger)" }} disabled={busy}
                onClick={onReject}>
          Тийм, татгалзах
        </button>
        <button type="button" className="sheet-reject" style={{ color: "var(--link)" }} onClick={() => setAsking(false)}>Болих</button>
      </>
    );
  }
  return (
    <>
      {!mayApprove && <p className="note">Асуултад хариулсны дараа агент төлөвлөгөөгөө шинэчилнэ.</p>}
      <button type="button" className="ticket-go" style={{ marginTop: 0 }} disabled={busy || !mayApprove} onClick={onApprove}>
        Зөвшөөрөх
      </button>
      <button type="button" className="sheet-reject" disabled={busy} onClick={() => setAsking(true)}>Татгалзах</button>
    </>
  );
}
```

- [ ] **Step 4: Run the UI checks to see them pass**

Run: `cd ui && npm run typecheck && npm test && npm run build`
Expected: PASS. If `typecheck` names another `TaskRow` literal (e.g. in `MiniApp.test.tsx`), give it the `actions` the server would send for that row, as in the fixtures.

- [ ] **Step 5: Accept the ADR and amend ADR 0012**

In `docs/adr/0027-…`, change `Status: proposed` to `Status: accepted`. In `docs/adr/0012-one-bot-for-several-groups-tasks-given-privately.md`, after its last `Amended:` line add:

```
Amended by ADR 0027: a requester still sees and acts on their own tasks after they leave the project's group.
```

- [ ] **Step 6: Run everything once more, with the page in the jar**

Run: `./mvnw -q -Pui verify`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add ui/src/api.ts ui/src/mini/TasksPage.tsx ui/src/mini/TicketSheet.tsx ui/src/mini/fixtures.ts \
        ui/src/mini/TasksPage.test.tsx ui/src/mini/HomePage.test.tsx docs/adr/0027-one-module-decides-what-a-member-may-see-and-do-with-a-task.md \
        docs/adr/0012-one-bot-for-several-groups-tasks-given-privately.md
git status --short   # add any other test file typecheck made you touch, e.g. ui/src/mini/MiniApp.test.tsx
git commit -F- <<'EOF'
Let the Mini App offer only what the member may do

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013bzgwsHrwA6N2BhRjVZ2Wz
EOF
```

---

## Out of scope

- Returning the refusal from the commands in place of their result enums, and moving the replies to the member out of `TaskService` (candidate 02 of the architecture review). Until then a crafted or raced Mini App cancel or retry still queues a Telegram refusal before its 403.
- Drafts' "only the writer answers the prompt" rule, who may manage Dispatch, and join decisions.
- Teaching the assistant's `taskmanager` skill to read the new `actions` field; `AssistantActions.check` validates every proposal anyway.
