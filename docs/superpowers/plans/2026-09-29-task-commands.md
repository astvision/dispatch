# Task commands Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every task command runs through one module, `TaskCommands`. It asks TaskAccess, changes the task, writes the task's **news**, and returns what happened: done, a task given, unchanged, or refused with one reason and its one wording. Each channel (Telegram text and buttons, the assistant, group additions, the Mini App, the desk) writes its own **reply**.

**Architecture:**
- **Commands and results are values.** A command is a value of the sealed `TaskCommand` type. `TaskCommands.run(tx, who, command)` returns a sealed `CommandResult`. `check(...)` returns what `run` would refuse, and writes nothing.
- **Refusals.** `Refusal` is the one refusal vocabulary: TaskAccess's reasons plus the commands' own. A refusal carries a `Text`, raised in core, that every channel shows.
- **Ownership and wiring.** News stays in the outbox, in the caller's transaction (ADR 0010). `TaskService` keeps drafts and the view payloads. It builds `TaskCommands` from its own collaborators and exposes it through `commands()`, so no channel's constructor changes.

**Tech Stack:**
- Plain Java 21 language level; CI builds on JDK 25. No framework (ADR 0002).
- SQLite through `Tx` (ADR 0003), Jackson, JUnit 5.
- React/TypeScript in `ui/`, which this plan does not touch.

**Spec:**
- `docs/adr/0031-task-commands-write-news-and-channels-write-replies.md`: the decision, the rejected alternatives and the consequences. Read it first.
- `CONTEXT.md`: the glossary, including News, Reply and Refusal.
- `docs/adr/0027-one-module-decides-what-a-member-may-see-and-do-with-a-task.md`: TaskAccess.

## Global Constraints

**Language and dependencies**
- `core` never imports `telegram` (docs/ARCHITECTURE.md, Components).

**Words**
- A `Text` key is always a literal where it is named, never built. `TextTest` scans the source for the keys the code names and checks both bundles have them.
- Every new `texts_en.properties` key gets a `texts_mn.properties` twin. Apostrophes are doubled for `MessageFormat` (`{1}''s`).
- Telegram speaks Mongolian: render a `Text` there with `Language.MN`. A page renders it in the request's language: `ApiException` takes the `Text` itself.
- Every refusal's words fit a Telegram button notice: 200 characters or fewer, in both languages.

**News and refusals**
- News goes into the outbox in the caller's transaction. Waking the outbox and the scheduler, and `ActiveRuns.stop`, happen only in `tx.afterCommit`.
- A refused command writes nothing: no `task`, `run`, `task_event`, `plan_answer` or `outbox` row. The only row a Telegram refusal leaves is the channel's own reply.
- All access goes through one connection and one lock (`Database`). So a conditional update that fails after the verdict allowed the command in the same transaction is a bug: throw `IllegalStateException`, and let the transaction roll back.

**Pages**
- The web contracts stay:
  - the routes;
  - the JSON `result` values: `CANCELLED`, `RETRIED`, `APPROVED`, `REJECTED`, `CORRECTED`, `QUEUED`/`NEW_TASK` for follow-ups, the task itself for answers, and `{"taskId"}` for the desk's give;
  - the error code `not_a_member`, which `ui/src/App.tsx:172` reads.

  HTTP status comes from `Refusal.kind()`, and the code from `Refusal.code()`.

**Workflow**
- Work test-first. Make one commit per task, in the repo's style: an imperative sentence and no prefix. End every commit with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01EnUvhys3zP42GsbKq3t4ha
  ```
- Comments say why, at the density of the surrounding code. The codebase writes javadoc that names its ADRs.
- Add no abstraction this plan does not name.
- Focused test runs: `./mvnw -q test -Dtest=ClassName`. At the end of every task, run the full suite with `./mvnw -q verify` (about 1100 tests); it must be green.
- Never push. Never touch the deployed service.
- A repo hook asks for `graphify query "…"` before grepping. The graph is from 2026-09-25 and stale, so use it only to orient, then read the source.

## Review Focus

1. **A refusal writes nothing, on every channel.**
   - A refused `/cancel 5` leaves exactly one row: the channel's `REFUSED` reply under the command, with no `TASK_CANCELLED`.
   - A refused tap in the Mini App leaves no row at all.
   - Tests in Task 1 (module) and Task 2 (channels).
2. **Who hears a cancel.**
   - A team task cancelled by an admin: the group's line, a line in the requester's topic, and the admin's own reply in Telegram (none from the desk).
   - A personal bot's task: exactly one line, since its chat *is* the requester's.
   - Tests in Task 1 and Task 2.
3. **Answering before the question message reaches Telegram** (a Mini App answer, or an assistant proposal tapped at once):
   - one question message and then an edit, never a second message;
   - if the question never gets through, its redraw is dropped (FAILED) and nothing crashes.
   - Tests in Task 3 (`OutboxSenderTest`).
4. **A merged task's follow-up whose project is unavailable, or whose requester has left the project's group:**
   - refused with words: 409 or 404 on a page, a reply in Telegram;
   - nothing written.
   - Tests in Task 3 and Task 4.
5. **Upgrading with rows of the retired reply kinds in the outbox:**
   - migration 027 fails the pending ones;
   - a later reply to an already-sent one (Telegram hands back its message ref) is treated as a reply to an unknown message, not a crash.
   - Tests in Task 6.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/dispatch/core/Refusal.java` (new) | The one refusal vocabulary, moved out of `TaskAccess`, plus the commands' own reasons, with `kind()` and `code()` |
| `src/main/java/dispatch/core/TaskCommand.java` (new) | The sealed command values; grows per task |
| `src/main/java/dispatch/core/CommandResult.java` (new) | Done, Created, Unchanged, Refused |
| `src/main/java/dispatch/core/Origin.java` (new, Task 3) | Where a task comes from; `Origin.page()` for pages |
| `src/main/java/dispatch/core/TaskCommands.java` (new) | `run`/`check`; each command's rules, effect and news; refusal words; giving a task; plan questions |
| `src/main/java/dispatch/core/TaskService.java` | Keeps drafts and the view payloads; builds `TaskCommands` and exposes it; loses every command |
| `src/main/java/dispatch/core/TaskAccess.java` | Loses the nested `Refusal` |
| `src/main/java/dispatch/core/AssistantActions.java` | Proposals checked, and taps run, through `TaskCommands` |
| `src/main/java/dispatch/core/GroupAdditions.java` | Applies an addition as a Correct or a FollowUp; hands back a refusal's words |
| `src/main/java/dispatch/telegram/UpdateHandler.java` | Telegram's replies: refusal lines, button notices, the admin's cancel line, the "type your answer" prompt, the chat views |
| `src/main/java/dispatch/telegram/Renderer.java` | Renders `REFUSED`, and assistant notes carrying words |
| `src/main/java/dispatch/telegram/OutboxSender.java`, `store/Outbox.java` | `edit_of`: a redraw that names its original's row |
| `src/main/java/dispatch/ui/TasksApi.java`, `ui/DeskTasksApi.java` | One mapping from a result to an HTTP answer |
| `src/main/resources/db/026-outbox-edit-of.sql`, `027-retired-reply-kinds.sql` (new) | The migrations |
| `src/test/java/dispatch/core/TaskCommandsTest.java` (new) | The commands at their interface: effects, news, the refusal table, invariants |

The files deleted along the way: `ApproveResult`, `AnswerResult`, `CancelResult`, `CorrectResult`, `CreateResult`, `FollowUpResult`, `PriorityResult`, `RejectResult` and `RetryResult`, all in `core/`, and `src/test/java/dispatch/core/GiveTest.java`, which folds into `TaskCommandsTest`.

---

### Task 1: One refusal vocabulary, and the command module with cancel and retry

The module exists and is tested here; no channel uses it yet. Everything else keeps working unchanged.

**Files:**
- Create: `src/main/java/dispatch/core/Refusal.java`, `CommandResult.java`, `TaskCommand.java`, `TaskCommands.java`
- Create: `src/test/java/dispatch/core/TaskCommandsTest.java`
- Modify: `src/main/java/dispatch/core/TaskAccess.java:59-82` (remove the nested `Refusal`)
- Modify: every `TaskAccess.Refusal` user: `core/TaskService.java`, `core/AssistantActions.java`, `core/Merges.java`, `ui/TasksApi.java`, `src/test/java/dispatch/core/TaskAccessTest.java`
- Modify: `src/main/java/dispatch/core/TaskService.java:95-107` (build and expose `TaskCommands`)
- Modify: `src/main/resources/texts_en.properties`, `texts_mn.properties`

**Interfaces:**
- Consumes: `TaskAccess.of(tx, memberRef, taskId)`, `Verdict.refusal(Action)`, `Verdict.task()`; `Outbox.enqueue`, `Outbox.enqueueForRequester`; `GroupAcks.react`; `ActiveRuns.stop`.
- Produces, for every later task:
  - `enum Refusal { NOT_MEMBER, NOT_FOUND, NOT_REQUESTER, WRONG_PHASE, STALE_PLAN, OPEN_QUESTIONS, OUT_OF_ORDER, ALREADY_ANSWERED, NOT_FAILED, NOT_EXECUTED, MERGED, EMPTY, UNKNOWN_PROJECT, PROJECT_UNAVAILABLE; Kind kind(); String code(); enum Kind { INVALID, FORBIDDEN, NOT_FOUND, CONFLICT } }`
  - `sealed interface CommandResult { record Done(long taskId, boolean toldActor); record Created(long taskId); record Unchanged(long taskId); record Refused(Refusal reason, Text words); }`
  - `sealed interface TaskCommand { record Cancel(long taskId); record Retry(long taskId); }`, which grows in Tasks 3 and 5
  - `TaskCommands.run(Tx, Requester, TaskCommand) -> CommandResult`
  - `TaskCommands.check(Tx, Requester, TaskCommand) -> Optional<CommandResult.Refused>`
  - `TaskService.commands() -> TaskCommands`

- [ ] **Step 1: Move `Refusal` out of `TaskAccess`**

Create `src/main/java/dispatch/core/Refusal.java`:

```java
package dispatch.core;

/**
 * Why a task command did nothing (ADR 0027, ADR 0031): {@link TaskAccess} gives the first eleven, the commands the last
 * three. A channel words a refusal with the words the command returns; {@link #kind()} is what it may act on without
 * knowing the refusal, such as an HTTP status, and {@link #code()} is what a page and the log receive.
 */
public enum Refusal {
    /** In no group; for cancelling, no admin either. */
    NOT_MEMBER(Kind.FORBIDDEN, "not_a_member"),
    /** No such task, or one this member may not see, so its existence does not leak. */
    NOT_FOUND(Kind.NOT_FOUND, "not_found"),
    /** Someone else's task, seen as its headline (ADR 0020). */
    NOT_REQUESTER(Kind.FORBIDDEN, "not_yours"),
    WRONG_PHASE(Kind.CONFLICT, "wrong_state"),
    /** A tap or reply on a plan a newer one replaced. */
    STALE_PLAN(Kind.CONFLICT, "stale"),
    /** A plan that asks questions is never approved: their answers make the next plan (G-1d). */
    OPEN_QUESTIONS(Kind.CONFLICT, "open_questions"),
    /** A later question while an earlier one is open. */
    OUT_OF_ORDER(Kind.CONFLICT, "out_of_order"),
    /** The question has its answer already, or no question is open. */
    ALREADY_ANSWERED(Kind.CONFLICT, "already_answered"),
    /** Only a failed task's failed step is retried (ADR 0008). */
    NOT_FAILED(Kind.CONFLICT, "wrong_state"),
    /** A follow-up continues an execution, and this task never started one (ADR 0006). */
    NOT_EXECUTED(Kind.CONFLICT, "wrong_state"),
    /** Its pull request is merged already. */
    MERGED(Kind.CONFLICT, "merged"),
    /** No words where words are the command: a blank correction, follow-up, answer or task, or an option the question lacks. */
    EMPTY(Kind.INVALID, "invalid"),
    /** Not one of the projects of the requester's groups. */
    UNKNOWN_PROJECT(Kind.NOT_FOUND, "unknown_project"),
    /** The project cannot take tasks now, e.g. while it is still being cloned. */
    PROJECT_UNAVAILABLE(Kind.CONFLICT, "project_unavailable");

    /** What sort of no it is: a page answers each with its own HTTP status. */
    public enum Kind {
        INVALID,
        FORBIDDEN,
        NOT_FOUND,
        CONFLICT
    }

    private final Kind kind;
    private final String code;

    Refusal(Kind kind, String code) {
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
```

Delete the nested `public enum Refusal { … }` (`TaskAccess.java:59-82`). Then replace the qualified name everywhere:

```bash
grep -rl 'TaskAccess\.Refusal' src/main/java src/test/java | xargs sed -i 's/TaskAccess\.Refusal/Refusal/g'
```

`TaskAccess.java` uses the unqualified `Refusal` already, and it is in the same package. Check that no file imports `dispatch.core.TaskAccess.Refusal`: `grep -rn 'TaskAccess.Refusal' src` must print nothing.

- [ ] **Step 2: Run the access tests: a pure move**

Run: `./mvnw -q test -Dtest='TaskAccessTest,TaskLifecycleTest,TasksApiTest,AssistantActionsTest'`
Expected: PASS.

- [ ] **Step 3: Write the failing test for cancel and retry**

Create `src/test/java/dispatch/core/TaskCommandsTest.java`:

```java
package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.Language;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.domain.ClaimedRun;
import dispatch.domain.FailureReason;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The task commands at their interface (ADR 0031): what each does, the news it writes, and what it refuses in which words.
 * The rules of who may do what are TaskAccessTest's (ADR 0027); these cases prove the commands ask it and relay it.
 */
class TaskCommandsTest {

    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");
    private static final Requester STRANGER = new Requester("telegram:999", "Nobody");
    /** An admin in no group: may cancel any task (ADR 0020), and do nothing else. */
    private static final Requester ADMIN = new Requester("telegram:400", "Tuya");
    private static final String CHAT = "telegram:-100";
    private static final Plan PLAN = new Plan("Make the auth timeout configurable", List.of("AuthClient.java:14 hard-codes 30s"),
            List.of("Read auth.timeout"), List.of(), List.of());

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TestClock clock;
    private TaskService tasks;
    private TaskCommands commands;
    private RunTransitions transitions;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-29T10:00:00Z"));
        Config.Project alm = new Config.Project("autoland-management", "alm", "https://github.com/acme/alm.git", null, "main",
                "claude-code", null, null, List.of(), null, null, null);
        Groups groups = new Groups(new Config.Telegram(List.of(400L), List.of(
                new Config.Group("backend", -100L, List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")),
                        List.of("autoland-management")))));
        tasks = new TaskService(groups, new Projects(List.of(alm), project -> Optional.empty()), new ActiveRuns(), clock,
                () -> { }, () -> { });
        commands = tasks.commands();
        transitions = new RunTransitions(db, clock, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void theRequesterCancelsTheirTaskAndTheGroupAndTheirTopicBothHearIt() {
        long id = given(BOLD, "5");

        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Cancel(id)));

        assertEquals("CANCELLED", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals("CANCELLED", row("SELECT status FROM run WHERE task_id = ?", id).get("status"));
        assertEquals(List.of(CHAT, BOLD.ref()), chatsOf("TASK_CANCELLED"), "the group's line, then the requester's own");
    }

    @Test
    void anAdminsCancelReachesTheRequesterAndLeavesTheAdminsReplyToTheChannel() {
        long id = given(BOLD, "5");

        assertEquals(new CommandResult.Done(id, false), run(ADMIN, new TaskCommand.Cancel(id)));

        assertEquals(List.of(CHAT, BOLD.ref()), chatsOf("TASK_CANCELLED"));
    }

    @Test
    void aRetryQueuesTheFailedStepAgainAndTellsTheRequester() {
        long id = failedExecution("5");

        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Retry(id)));

        Map<String, String> retried = row("SELECT kind, cause, status FROM run WHERE task_id = ? ORDER BY seq DESC LIMIT 1", id);
        assertEquals(Map.of("kind", "EXECUTE", "cause", "RETRY", "status", "QUEUED"), retried);
        assertEquals(List.of(BOLD.ref()), chatsOf("RETRY_QUEUED"));
    }

    @Test
    void aRefusedCommandWritesNothingAndSaysWhyInBothLanguages() {
        long open = given(BOLD, "5");
        long failed = failedExecution("6");
        record Case(Requester who, TaskCommand command, Refusal reason) {
        }
        List<Case> cases = List.of(
                new Case(STRANGER, new TaskCommand.Cancel(open), Refusal.NOT_MEMBER),
                new Case(BOLD, new TaskCommand.Cancel(404), Refusal.NOT_FOUND),
                new Case(ALI, new TaskCommand.Cancel(open), Refusal.NOT_REQUESTER),
                new Case(ALI, new TaskCommand.Retry(failed), Refusal.NOT_REQUESTER),
                new Case(BOLD, new TaskCommand.Retry(open), Refusal.NOT_FAILED),
                new Case(ADMIN, new TaskCommand.Retry(failed), Refusal.NOT_MEMBER));
        for (Case refused : cases) {
            Map<String, String> before = counts();
            CommandResult result = run(refused.who(), refused.command());
            CommandResult.Refused refusal = assertInstanceOf(CommandResult.Refused.class, result, refused.toString());
            assertEquals(refused.reason(), refusal.reason(), refused.toString());
            assertEquals(before, counts(), refused + " wrote something");
            for (Language language : Language.values()) {
                String words = refusal.words().render(language);
                assertFalse(words.isBlank(), refused + " in " + language);
                assertTrue(words.length() <= 200, refused + " in " + language + " is too long for a button's notice: " + words);
            }
        }
    }

    @Test
    void aRefusalNamesTheTaskAndWhatStandsInTheWay() {
        long id = given(BOLD, "5");
        run(BOLD, new TaskCommand.Cancel(id));

        CommandResult.Refused again = assertInstanceOf(CommandResult.Refused.class, run(BOLD, new TaskCommand.Cancel(id)));
        assertEquals(Refusal.WRONG_PHASE, again.reason());
        assertTrue(again.words().render(Language.EN).contains("#" + id), again.words().render(Language.EN));
        assertTrue(again.words().render(Language.EN).contains("cancelled"), again.words().render(Language.EN));

        long other = given(BOLD, "6");
        CommandResult.Refused notYours = assertInstanceOf(CommandResult.Refused.class, run(ALI, new TaskCommand.Cancel(other)));
        assertTrue(notYours.words().render(Language.MN).contains("Bold"), "names who gave it (ADR 0020 shows the headline)");
    }

    @Test
    void checkRefusesAsRunWouldAndWritesNothing() {
        long id = given(BOLD, "5");
        Map<String, String> before = counts();

        Optional<CommandResult.Refused> checked = db.transactionReturning(tx -> commands.check(tx, ALI, new TaskCommand.Cancel(id)));
        assertEquals(Optional.of(run(ALI, new TaskCommand.Cancel(id))), checked);
        assertEquals(Optional.empty(), db.transactionReturning(tx -> commands.check(tx, BOLD, new TaskCommand.Cancel(id))));
        assertEquals(before, counts());
    }

    private CommandResult run(Requester who, TaskCommand command) {
        return db.transactionReturning(tx -> commands.run(tx, who, command));
    }

    private long given(Requester who, String messageId) {
        String origin = who.ref() + "/" + messageId;
        assertEquals(CreateResult.CREATED,
                db.transactionReturning(tx -> tasks.create(tx, who, "alm", "Fix login timeout", Priority.NORMAL, origin)));
        return Long.parseLong(row("SELECT id FROM task WHERE origin_ref = ?", origin).get("id"));
    }

    /** A task of BOLD's whose execution failed; claims its own runs even when an earlier task's run is queued ahead. */
    private long failedExecution(String messageId) {
        long id = given(BOLD, messageId);
        ClaimedRun plan = claimFor(id);
        transitions.planSucceeded(id, plan.seq(), PLAN, planResult());
        assertEquals(ApproveResult.APPROVED, db.transactionReturning(tx -> tasks.approve(tx, BOLD, id, plan.seq())));
        ClaimedRun execution = claimFor(id);
        transitions.failed(id, execution.seq(), FailureReason.AGENT, "boom", executionResult());
        return id;
    }

    private ClaimedRun claimFor(long taskId) {
        ClaimedRun claimed = db.transactionReturning(tx -> Runs.claimNext(tx, 10, clock.instant())).orElseThrow();
        while (claimed.taskId() != taskId) {
            claimed = db.transactionReturning(tx -> Runs.claimNext(tx, 10, clock.instant())).orElseThrow();
        }
        return claimed;
    }

    private static AgentResult planResult() {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", PLAN.toJson(), null, new BigDecimal("0.10"), 9, List.of(),
                null, null, null);
    }

    private static AgentResult executionResult() {
        return new AgentResult(AgentOutcome.SUCCEEDED, 0, "session-1", null, "Made the auth timeout configurable.",
                new BigDecimal("0.42"), 12, List.of(), null, null, null);
    }

    private List<String> chatsOf(String kind) {
        return SqlRows.query(dbFile, "SELECT chat_ref FROM outbox WHERE kind = ? ORDER BY id", kind).stream()
                .map(found -> found.get("chat_ref")).toList();
    }

    /** What a refused command must leave as it was. */
    private Map<String, String> counts() {
        return row("""
                SELECT (SELECT count(*) FROM task) AS tasks, (SELECT count(*) FROM run) AS runs,
                       (SELECT count(*) FROM task_event) AS events, (SELECT count(*) FROM plan_answer) AS answers,
                       (SELECT count(*) FROM outbox) AS outbox""");
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
```

- [ ] **Step 4: Run the test to see it fail**

Run: `./mvnw -q test -Dtest=TaskCommandsTest`
Expected: a COMPILATION ERROR: `TaskCommands`, `TaskCommand`, `CommandResult` and `TaskService.commands()` do not exist.

- [ ] **Step 5: Add the result and command types**

`src/main/java/dispatch/core/CommandResult.java`:

```java
package dispatch.core;

import dispatch.Text;

/** What a task command did (ADR 0031); the channel that ran it words its reply from this. */
public sealed interface CommandResult {

    /**
     * It took effect, and its news is written.
     *
     * @param toldActor the news already reached the actor, in their task's topic: false only for an admin who cancelled
     *                  someone else's task, whom the channel answers where they acted
     */
    record Done(long taskId, boolean toldActor) implements CommandResult {
    }

    /** A new task: one given, or a merged task's follow-up. A task given again from the same origin is this, and writes nothing. */
    record Created(long taskId) implements CommandResult {
    }

    /** Nothing to change: the priority it already had. Nothing written. */
    record Unchanged(long taskId) implements CommandResult {
    }

    /** Nothing written. {@code words} is this refusal's one wording, which every channel shows (ADR 0031). */
    record Refused(Refusal reason, Text words) implements CommandResult {
    }
}
```

`src/main/java/dispatch/core/TaskCommand.java`:

```java
package dispatch.core;

/** What a member asks of a task (ADR 0031): a value a channel builds, stores or checks, and {@link TaskCommands} runs. */
public sealed interface TaskCommand {

    record Cancel(long taskId) implements TaskCommand {
    }

    record Retry(long taskId) implements TaskCommand {
    }
}
```

- [ ] **Step 6: Add the refusal and phase words**

Append to `src/main/resources/texts_en.properties`:

```properties
# A task command's refusal (ADR 0031): one wording, shown in the chat, on a button's notice and on a page alike.
refused.notMember=you are not in a group of this Dispatch
refused.notFound=no task #{0} here
refused.notRequester=#{0} was given by {1}: only they may do that
refused.cancelNotRequester=#{0} was given by {1}: only they, or an admin, may cancel it
refused.wrongPhase=#{0} can''t take that now: it is {1}
refused.notFailed=#{0} can''t be retried: it is {1}. Only a failed task is retried
# The phase a refusal names.
phase.planning=planning
phase.awaitingApproval=awaiting approval
phase.executing=being carried out
phase.completed=completed
phase.failed=failed
phase.rejected=rejected
phase.cancelled=cancelled
```

Append to `src/main/resources/texts_mn.properties`:

```properties
# A task command's refusal (ADR 0031): one wording, shown in the chat, on a button's notice and on a page alike.
refused.notMember=та энэ Dispatch-ийн бүлэгт байхгүй байна
refused.notFound=#{0} дугаартай даалгавар олдсонгүй
refused.notRequester=#{0}: үүнийг зөвхөн даалгаврыг өгсөн {1} хийнэ
refused.cancelNotRequester=#{0}: даалгаврыг зөвхөн өгсөн {1} эсвэл админ цуцална
refused.wrongPhase=#{0}: одоо боломжгүй, төлөв нь «{1}»
refused.notFailed=#{0} даалгаврыг дахин оролдох боломжгүй: төлөв нь «{1}». Зөвхөн амжилтгүй болсон даалгаврыг дахин оролдоно
# The phase a refusal names.
phase.planning=төлөвлөж байна
phase.awaitingApproval=зөвшөөрөл хүлээж байна
phase.executing=хэрэгжүүлж байна
phase.completed=дууссан
phase.failed=амжилтгүй
phase.rejected=татгалзсан
phase.cancelled=цуцалсан
```

- [ ] **Step 7: Write `TaskCommands` with cancel and retry**

`src/main/java/dispatch/core/TaskCommands.java`. The cancel and retry bodies are `TaskService.cancel`/`retry` (`TaskService.java:789-863`) without their `refuse(...)` replies, and with the requester's news:

```java
package dispatch.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.Text;
import dispatch.domain.FailureReason;
import dispatch.domain.GroupReaction;
import dispatch.domain.OutboxKind;
import dispatch.domain.Phase;
import dispatch.domain.Requester;
import dispatch.domain.Run;
import dispatch.domain.RunCause;
import dispatch.domain.RunKind;
import dispatch.domain.Task;
import dispatch.store.Events;
import dispatch.store.Outbox;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * The task commands (ADR 0031). Each asks {@link TaskAccess} what the member may do (ADR 0027), changes the task, writes
 * the task's news in the caller's transaction, and says what happened. None writes a reply: the channel that ran it
 * answers whoever acted. A refused command writes nothing at all.
 */
public final class TaskCommands {

    private final ActiveRuns activeRuns;
    private final TaskAccess access;
    private final Clock clock;
    private final Runnable wakeScheduler;
    private final Runnable wakeOutbox;

    TaskCommands(Groups groups, ActiveRuns activeRuns, Clock clock, Runnable wakeScheduler, Runnable wakeOutbox) {
        this.activeRuns = activeRuns;
        this.access = new TaskAccess(groups);
        this.clock = clock;
        this.wakeScheduler = wakeScheduler;
        this.wakeOutbox = wakeOutbox;
    }

    /** Does {@code command} for {@code who} in the caller's transaction: a refusal writes nothing. */
    public CommandResult run(Tx tx, Requester who, TaskCommand command) {
        Optional<CommandResult.Refused> refused = check(tx, who, command);
        CommandResult result = refused.isPresent() ? refused.get() : carryOut(tx, who, command);
        // One line per command, whichever channel ran it; never its text, which may hold anything.
        tx.afterCommit(() -> Log.info("task.command", "command", command.getClass().getSimpleName(), "task", taskId(command),
                "actor", who.ref(), "result", name(result)));
        return result;
    }

    /**
     * What {@link #run} would refuse now, writing nothing; empty when it would act. For offering an action (a proposal, a
     * prompt), never for guarding one: {@link #run} checks again.
     */
    public Optional<CommandResult.Refused> check(Tx tx, Requester who, TaskCommand command) {
        return switch (command) {
            case TaskCommand.Cancel cancel -> refusal(tx, who, command, cancel.taskId(), TaskAccess.Action.CANCEL);
            case TaskCommand.Retry retry -> refusal(tx, who, command, retry.taskId(), TaskAccess.Action.RETRY);
        };
    }

    private CommandResult carryOut(Tx tx, Requester who, TaskCommand command) {
        return switch (command) {
            case TaskCommand.Cancel cancel -> cancel(tx, who, task(tx, cancel.taskId()));
            case TaskCommand.Retry retry -> retry(tx, who, task(tx, retry.taskId()));
        };
    }

    private Optional<CommandResult.Refused> refusal(Tx tx, Requester who, TaskCommand command, long taskId, TaskAccess.Action action) {
        TaskAccess.Verdict verdict = access.of(tx, who.ref(), taskId);
        return verdict.refusal(action).map(refusal -> refused(command, refusal, verdict.task()));
    }

    /**
     * Cancels the task; an admin may cancel any task, even outside their own groups. A running agent is stopped after
     * commit and its run ends as CANCELLED. The group hears it, and so does the requester in the task's own topic, whoever
     * cancelled it (ADR 0031).
     */
    private CommandResult cancel(Tx tx, Requester who, Task task) {
        Instant now = clock.instant();
        changePhase(tx, task, task.phase(), Phase.CANCELLED, now);
        Runs.cancelQueued(tx, task.id(), now);
        Events.record(tx, task.id(), null, who.ref(), task.phase(), Phase.CANCELLED, "cancelled", now);
        ObjectNode cancelled = Json.object().put("taskId", task.id()).put("by", who.name());
        Outbox.enqueue(tx, task.id(), OutboxKind.TASK_CANCELLED, task.chatRef(), task.groupOriginRef(), cancelled, now);
        if (task.hasGroupChat()) {
            // No fallback to the group: the group has its own line already. A personal bot's task chat is the requester's.
            Outbox.enqueue(tx, task.id(), OutboxKind.TASK_CANCELLED, task.requester().ref(), task.privateOriginRef(), cancelled, now);
        }
        GroupAcks.react(tx, task, GroupReaction.ENDED, now);
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(() -> activeRuns.stop(task.id(), ActiveRuns.StopReason.CANCELLED));
        logTransition(tx, task.id(), task.phase(), Phase.CANCELLED, who.ref());
        return new CommandResult.Done(task.id(), isRequester(who, task));
    }

    /** Repeats the failed step: a plan, an execution, or only the delivery of work already done (ADR 0008). */
    private CommandResult retry(Tx tx, Requester who, Task task) {
        Instant now = clock.instant();
        // Task access saw the latest run fail: that run is the step to repeat.
        Run step = Runs.latest(tx, task.id()).orElseThrow();
        RunKind kind;
        String instruction;
        if (step.kind() == RunKind.PLAN) {
            kind = RunKind.PLAN;
            instruction = step.instruction();
        } else if (step.kind() == RunKind.DELIVER || step.failureReason() == FailureReason.DELIVERY) {
            // The agent's work is done and waits in the worktree; only its summary is needed, as the commit message.
            kind = RunKind.DELIVER;
            instruction = step.kind() == RunKind.DELIVER ? step.instruction() : Runs.output(tx, task.id(), step.seq()).orElse("");
        } else {
            kind = RunKind.EXECUTE;
            instruction = step.instruction();
        }
        Phase to = kind == RunKind.PLAN ? Phase.PLANNING : Phase.EXECUTING;
        changePhase(tx, task, Phase.FAILED, to, now);
        int seq = Runs.nextSeq(tx, task.id());
        Runs.insert(tx, new Runs.NewRun(task.id(), seq, kind, RunCause.RETRY, instruction, who), now);
        Events.record(tx, task.id(), seq, who.ref(), Phase.FAILED, to, "retry of run " + step.seq(), now);
        Outbox.enqueueForRequester(tx, task, OutboxKind.RETRY_QUEUED,
                Json.object().put("taskId", task.id()).put("by", who.name()).put("kind", kind.name()), now);
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(wakeScheduler);
        logTransition(tx, task.id(), Phase.FAILED, to, who.ref());
        return new CommandResult.Done(task.id(), isRequester(who, task));
    }

    /** A refusal and its one wording (ADR 0031), naming the task as far as its headline allows (ADR 0020). */
    static CommandResult.Refused refused(TaskCommand command, Refusal refusal, Task task) {
        return new CommandResult.Refused(refusal, words(command, refusal, task));
    }

    private static Text words(TaskCommand command, Refusal refusal, Task task) {
        long taskId = taskId(command);
        return switch (refusal) {
            case NOT_MEMBER -> Text.of("refused.notMember");
            case NOT_FOUND -> Text.of("refused.notFound", taskId);
            case NOT_REQUESTER -> command instanceof TaskCommand.Cancel
                    ? Text.of("refused.cancelNotRequester", taskId, task.requester().name())
                    : Text.of("refused.notRequester", taskId, task.requester().name());
            case WRONG_PHASE -> Text.of("refused.wrongPhase", taskId, phase(task.phase()));
            case NOT_FAILED -> Text.of("refused.notFailed", taskId, phase(task.phase()));
            // Refusals no command of this module gives yet; each gets its words with the command that first gives it.
            case STALE_PLAN, OPEN_QUESTIONS, OUT_OF_ORDER, ALREADY_ANSWERED, NOT_EXECUTED, MERGED, EMPTY, UNKNOWN_PROJECT,
                 PROJECT_UNAVAILABLE -> throw new IllegalStateException(command + " is never refused as " + refusal);
        };
    }

    private static Text phase(Phase phase) {
        return switch (phase) {
            case PLANNING -> Text.of("phase.planning");
            case AWAITING_APPROVAL -> Text.of("phase.awaitingApproval");
            case EXECUTING -> Text.of("phase.executing");
            case COMPLETED -> Text.of("phase.completed");
            case FAILED -> Text.of("phase.failed");
            case REJECTED -> Text.of("phase.rejected");
            case CANCELLED -> Text.of("phase.cancelled");
        };
    }

    private static long taskId(TaskCommand command) {
        return switch (command) {
            case TaskCommand.Cancel cancel -> cancel.taskId();
            case TaskCommand.Retry retry -> retry.taskId();
        };
    }

    private static String name(CommandResult result) {
        return switch (result) {
            case CommandResult.Done done -> "DONE";
            case CommandResult.Created created -> "CREATED #" + created.taskId();
            case CommandResult.Unchanged unchanged -> "UNCHANGED";
            case CommandResult.Refused refused -> "REFUSED " + refused.reason();
        };
    }

    private static Task task(Tx tx, long taskId) {
        return Tasks.find(tx, taskId).orElseThrow(() -> new IllegalStateException("task #" + taskId + " was allowed, yet is gone"));
    }

    /**
     * The verdict allowed this in the same transaction, over the one connection and its lock, so the phase cannot have
     * moved since: if the update finds it moved, the verdict and the store disagree, and nothing of it may be kept.
     */
    private static void changePhase(Tx tx, Task task, Phase from, Phase to, Instant now) {
        if (!Tasks.changePhase(tx, task.id(), from, to, now)) {
            throw new IllegalStateException("task #" + task.id() + " was allowed " + from + " -> " + to + ", yet its phase moved");
        }
    }

    private static boolean isRequester(Requester who, Task task) {
        return task.requester().ref().equals(who.ref());
    }

    private static void logTransition(Tx tx, long taskId, Phase from, Phase to, String actor) {
        tx.afterCommit(() -> Log.info("task.transition", "task", taskId, "from", from, "to", to, "actor", actor));
    }
}
```

In `TaskService`, add a field `private final TaskCommands commands;`. In the nine-argument constructor (`TaskService.java:95-107`), after `this.access = new TaskAccess(groups);`, add:

```java
        this.commands = new TaskCommands(groups, activeRuns, clock, wakeScheduler, wakeOutbox);
```

and the accessor:

```java
    /** The task commands (ADR 0031): what a channel runs to act on a task. */
    public TaskCommands commands() {
        return commands;
    }
```

- [ ] **Step 8: Run the new test**

Run: `./mvnw -q test -Dtest=TaskCommandsTest`
Expected: PASS, all six tests.

- [ ] **Step 9: Run the whole suite, then commit**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS. No channel uses the module yet; `TextTest` finds the new keys in both bundles.

```bash
git add -A src/main/java/dispatch/core src/main/java/dispatch/ui/TasksApi.java src/main/resources/texts_en.properties \
        src/main/resources/texts_mn.properties src/test/java/dispatch/core
git commit -m "Give task commands one module, one refusal vocabulary and one wording, starting with cancel and retry

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01EnUvhys3zP42GsbKq3t4ha"
```

---

### Task 2: Cancel and retry through every channel

Telegram, the pages and the assistant run cancel and retry through `TaskCommands`, and each writes its own reply. `TaskService.cancel`/`retry` and their result enums go.

**Files:**
- Modify: `src/main/java/dispatch/domain/OutboxKind.java` (add `REFUSED`)
- Modify: `src/main/java/dispatch/telegram/Renderer.java` (render `REFUSED`; assistant notes carrying words, at the `assistant.note.` line near `:642`)
- Modify: `src/main/java/dispatch/telegram/UpdateHandler.java`:
  - `/cancel` and `/retry` at `:366-383`;
  - `onAssistantButton` at `:924-947`;
  - new `reply`, `refuse` and `notice` helpers beside `answer` at `:1277`.
- Modify: `src/main/java/dispatch/ui/TasksApi.java` (`cancel`, `retry`, plus the new `answered` and `refused` helpers; the pre-check and assert go)
- Modify: `src/main/java/dispatch/core/AssistantActions.java` (cancel and retry proposals and taps; the `Tapped` result)
- Modify: `src/main/java/dispatch/core/TaskService.java` (delete `cancel`, `retry`)
- Delete: `src/main/java/dispatch/core/CancelResult.java`, `RetryResult.java`
- Tests: `src/test/java/dispatch/telegram/RendererTest.java`, `UpdateHandlerTest.java`, `src/test/java/dispatch/ui/TasksApiTest.java`, `src/test/java/dispatch/core/AssistantActionsTest.java`, `TaskLifecycleTest.java`, plus every other test calling `tasks.cancel(`/`tasks.retry(` (find them with `grep -rln 'tasks\.\(cancel\|retry\)(' src/test`)

**Interfaces:**
- Consumes: Task 1's `TaskCommands.run/check`, `CommandResult`, `Refusal.kind()/code()`, `TaskService.commands()`.
- Produces:
  - `OutboxKind.REFUSED`: payload `{"text": String, "hint": String?}`, a reply under the actor's message.
  - `UpdateHandler.reply(Tx, Requester, TaskCommand, CommandResult, String origin, String chatRef)`.
  - `UpdateHandler.notice(Tx, String callbackId, CommandResult, String doneKey)`.
  - `TasksApi.answered(CommandResult, String doneResult) -> ObjectNode`, and `TasksApi.refused(CommandResult.Refused) -> ApiException`, both package-private static.
  - `AssistantActions.Tapped(Outcome outcome, Optional<Text> refusal)`, returned by `AssistantActions.run`.

- [ ] **Step 1: Write the failing Renderer test for `REFUSED`**

In `RendererTest`, add a `REFUSED` sample to `samplePayload` (the exhaustive switch near `:1073`):

```java
            case REFUSED -> Json.object().put("text", "#5 can't be retried: it is completed").put("hint", "/retry 5");
```

and a test:

```java
    @Test
    void aRefusalIsItsWordsEscapedWithItsCommandHintAsCode() {
        Renderer.Rendered rendered = renderer.render(OutboxKind.REFUSED,
                Json.object().put("text", "#5 <b> is Bold's & Ali's").put("hint", "/retry 5"));
        assertEquals("#5 &lt;b&gt; is Bold's &amp; Ali's\n<code>/retry 5</code>", rendered.html());
        assertEquals("#5", renderer.render(OutboxKind.REFUSED, Json.object().put("text", "#5")).html());
    }
```

Run: `./mvnw -q test -Dtest=RendererTest`
Expected: COMPILATION ERROR, because `OutboxKind.REFUSED` does not exist.

- [ ] **Step 2: Add `REFUSED` and render it**

In `OutboxKind`, after `TASK_NOT_FOUND`:

```java
    /**
     * A task command's refusal, under the message that asked for it (ADR 0031): its words, worded where it was refused, and
     * the command to type instead when there is one.
     */
    REFUSED,
```

In `Renderer.render`'s switch, next to `TASK_NOT_FOUND`:

```java
            case REFUSED -> plain(escape(payload.path("text").asText())
                    + (payload.hasNonNull("hint") ? "\n<code>" + escape(payload.path("hint").asText()) + "</code>" : ""));
```

Run: `./mvnw -q test -Dtest=RendererTest`
Expected: PASS.

- [ ] **Step 3: Write the failing channel tests**

In `UpdateHandlerTest`, replace the `/cancel` and `/retry` refusal tests: every test that asserts a `CANCEL_REFUSED`, `RETRY_REFUSED` or `TASK_NOT_FOUND` row after `/cancel` or `/retry`. Read the file's existing `/cancel` tests for its helpers (`message(...)`, `handle(...)`, the outbox queries), and write these three in the same style:

- `aRefusedCancelIsAnsweredUnderTheCommandInItsWords`: Ali sends `/cancel <Bold's task>` privately. Exactly one new outbox row: kind `REFUSED`, `chat_ref` Ali's chat, `reply_to_ref` the command's message. Its `payload.text` equals `Text.of("refused.cancelNotRequester", id, "Bold").render(Language.MN)`. No `TASK_CANCELLED` row.
- `anAdminsCancelOfSomeoneElsesTaskGetsItsOwnLine`: an admin (add one to the fixture's `Config.Telegram` admins if it has none) sends `/cancel <Bold's task>`. `TASK_CANCELLED` rows go to the group, to Bold, and to the admin's chat under the command.
- `theRequestersOwnCancelGetsNoSecondLine`: Bold sends `/cancel <own task>`. `TASK_CANCELLED` rows go to the group and to Bold, and to nobody else.

In `TasksApiTest`, turn the cancel/retry refusal tests into one test over the refusals a page can meet. Each refusal is answered with its `Refusal.kind()` status and `code()`, and its words in the request's language, and the outbox count is unchanged:

```java
    @Test
    void aRefusedCancelOrRetryIsAnsweredWithItsWordsAndNeverInTheChat() {
        long bolds = given(BOLD, "5");
        long before = outboxCount();
        ApiException notYours = assertThrows(ApiException.class, () -> api.cancel(caller(ALI), body(bolds)));
        assertEquals(403, notYours.status());
        assertEquals("not_yours", notYours.code());
        assertEquals(Text.of("refused.cancelNotRequester", bolds, "Bold"), notYours.text());
        ApiException notFailed = assertThrows(ApiException.class, () -> api.retry(caller(BOLD), body(bolds)));
        assertEquals(409, notFailed.status());
        assertEquals("wrong_state", notFailed.code());
        assertEquals(before, outboxCount(), "a page's refusal never reaches the chat");
    }
```

Adapt the helper names (`given`, `caller`, `body`, `outboxCount`) and `ApiException`'s accessors to the ones `TasksApiTest` and `ApiException.java` actually have; read them first.

Run: `./mvnw -q test -Dtest='UpdateHandlerTest,TasksApiTest'`
Expected: FAIL. The channels still call `TaskService.cancel/retry`, which write `CANCEL_REFUSED` rows, and the pages answer the old codes and texts.

- [ ] **Step 4: Telegram replies from the channel**

In `UpdateHandler`:

1. Add a field `private final TaskCommands commands;`. Assign it in the constructor that assigns `this.tasks`: `this.commands = tasks.commands();`.
2. Replace the `/cancel` and `/retry` lambdas (`:371-372`, `:380-381`):

```java
                taskId(command.args()).ifPresentOrElse(
                        id -> reply(tx, who, new TaskCommand.Cancel(id), commands.run(tx, who, new TaskCommand.Cancel(id)), origin, chatRef),
                        () -> enqueue(tx, OutboxKind.TASK_USAGE, chatRef, origin, Json.object().put("command", "cancel")));
```

and the same for `retry` with `TaskCommand.Retry`.

3. Add the helpers, next to `answer(...)`:

```java
    /**
     * Answers a task command where it was given (ADR 0031). A refusal is said in its words, under the message. An admin who
     * cancelled someone else's task hears that it is done, since the news went to the task's requester and group. The
     * requester's own success needs no reply: the news reached their task's topic.
     */
    private void reply(Tx tx, Requester who, TaskCommand command, CommandResult result, String origin, String chatRef) {
        switch (result) {
            case CommandResult.Refused refused -> refuse(tx, who, command, refused, origin, chatRef);
            case CommandResult.Done done when !done.toldActor() && command instanceof TaskCommand.Cancel ->
                    enqueue(tx, OutboxKind.TASK_CANCELLED, chatRef, origin, Json.object().put("taskId", done.taskId()).put("by", who.name()));
            case CommandResult.Done done -> { }
            case CommandResult.Created created -> { }
            case CommandResult.Unchanged unchanged -> { }
        }
    }

    /**
     * A refusal's words under the message that asked. In a group, someone in no group is only logged: a busy group's chatter
     * would otherwise be answered with a refusal line each time (G-1b).
     */
    private void refuse(Tx tx, Requester who, TaskCommand command, CommandResult.Refused refused, String origin, String chatRef) {
        if (refused.reason() == Refusal.NOT_MEMBER && groups.isGroupChat(chatRef)) {
            tx.afterCommit(() -> Log.warn("member.not_allowed", "requester", who.ref(), "name", who.name(), "chat", chatRef,
                    "answered", false));
            return;
        }
        enqueue(tx, OutboxKind.REFUSED, chatRef, origin, Json.object().put("text", refused.words().render(Language.MN)));
    }

    /** A button's notice for a task command (ADR 0031): its refusal's words, or the words of {@code doneKey} once done. */
    private void notice(Tx tx, String callbackId, CommandResult result, String doneKey) {
        String text = result instanceof CommandResult.Refused refused ? refused.words().render(Language.MN) : renderer.text(doneKey);
        tx.afterCommit(() -> bestEffort("answerCallbackQuery", () -> api.answerCallbackQuery(callbackId, text)));
    }
```

Add the imports: `dispatch.Language`, `dispatch.core.CommandResult`, `dispatch.core.Refusal`, `dispatch.core.TaskCommand`, `dispatch.core.TaskCommands`.

- [ ] **Step 5: The pages answer from the result**

In `TasksApi`:

1. Add a field `private final TaskCommands commands;`, assigned `tasks.commands()` in the four-argument constructor.
2. Replace `cancel` and `retry` (`:117-177`), including `cancelRefused` and `retryRefused`:

```java
    /** {@link TaskCommand.Cancel}, exactly as /cancel in the chat; a refusal is answered here and never in the chat (ADR 0031). */
    ObjectNode cancel(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        return answered(db.transactionReturning(tx -> commands.run(tx, requester(caller), new TaskCommand.Cancel(taskId))), "CANCELLED");
    }

    /** {@link TaskCommand.Retry}: the requester alone, and only after the task failed. */
    ObjectNode retry(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        return answered(db.transactionReturning(tx -> commands.run(tx, requester(caller), new TaskCommand.Retry(taskId))), "RETRIED");
    }

    /**
     * A task command's one answer to a page (ADR 0031): {@code {"result": done}}, a new task's number, or its refusal's
     * words with the status its kind calls for. The desk's give answers through {@link #refused} too.
     */
    static ObjectNode answered(CommandResult result, String done) {
        return switch (result) {
            case CommandResult.Refused refused -> throw refused(refused);
            case CommandResult.Created created -> Json.object().put("result", "NEW_TASK").put("taskId", created.taskId());
            case CommandResult.Unchanged unchanged -> Json.object().put("result", "UNCHANGED");
            case CommandResult.Done finished -> Json.object().put("result", done);
        };
    }

    static ApiException refused(CommandResult.Refused refused) {
        int status = switch (refused.reason().kind()) {
            case INVALID -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
        };
        return new ApiException(status, refused.reason().code(), refused.words());
    }
```

`answer`, `correct` and `followUp` still use `access` and the old results until Task 4, and `approve`/`reject` until Task 5; leave them.

- [ ] **Step 6: The assistant checks and taps through the module**

In `AssistantActions`:

1. Add a field `private final TaskCommands commands;`, assigned `tasks.commands()`.
2. Replace the `Outcome` javadoc's first line, and add the result a tap returns:

```java
    /** How a tap ended, and a refusal's words when the task refused it (ADR 0031): the channel shows those. */
    public record Tapped(Outcome outcome, Optional<Text> refusal) {
    }
```

3. `run` returns `Tapped`: `carryOut` returns `Tapped`, `run` records `tapped.outcome().name()`, and returns `new Tapped(Outcome.USED, Optional.empty())` when the action was taken before. The `draft`, `answer`, `approve` and `reject` arms wrap their current enum as `new Tapped(<outcome>, Optional.empty())`.
4. Replace the `cancel` and `retry` arms of `carryOut` (`:120-127`) with:

```java
            case "cancel" -> tapped(commands.run(tx, who, new TaskCommand.Cancel(taskId)));
            case "retry" -> tapped(commands.run(tx, who, new TaskCommand.Retry(taskId)));
```

and add:

```java
    /** A task command's result as a tap records it (ADR 0031): what refuses the member is not allowed, the rest moved on. */
    private static Tapped tapped(CommandResult result) {
        return switch (result) {
            case CommandResult.Refused refused -> new Tapped(switch (refused.reason().kind()) {
                case FORBIDDEN, NOT_FOUND -> Outcome.NOT_ALLOWED;
                case INVALID, CONFLICT -> Outcome.STALE;
            }, Optional.of(refused.words()));
            case CommandResult.Done done -> new Tapped(Outcome.DONE, Optional.empty());
            case CommandResult.Created created -> new Tapped(Outcome.DONE, Optional.empty());
            case CommandResult.Unchanged unchanged -> new Tapped(Outcome.DONE, Optional.empty());
        };
    }
```

5. In `check`, the `cancel` and `retry` proposals ask the module:

```java
            case "cancel" -> offered(tx, who, new TaskCommand.Cancel(taskId), payload);
            case "retry" -> offered(tx, who, new TaskCommand.Retry(taskId), payload);
```

```java
    /** A button only for what would run now; otherwise a note in the refusal's own words (ADR 0031). */
    private Checked offered(Tx tx, Requester who, TaskCommand command, ObjectNode payload) {
        return commands.check(tx, who, command)
                .map(refused -> new Checked(false, payload.put("words", refused.words().render(Language.MN))))
                .orElseGet(() -> new Checked(true, payload));
    }
```

`allowed(...)` is now unused: delete it.

6. In `Renderer`, where an assistant reply renders its notes (`:642`), a note with `words` shows them, and one without keeps its `assistant.note.*` key:

```java
            html.append("\n").append(note.hasNonNull("words")
                    ? "ℹ️ " + escape(note.path("words").asText())
                    : format("assistant.note." + note.path("reason").asText(), taskId(note)));
```

7. In `UpdateHandler.onAssistantButton` (`:929-935`), use the `Tapped`:

```java
        AssistantActions.Tapped tapped = assistantActions.run(tx, who, actionId, messageRef, Refs.chat(chatId));
        String notice = tapped.refusal().map(words -> words.render(Language.MN)).orElseGet(() -> renderer.text(switch (tapped.outcome()) {
            case DONE -> "callback.assistantDone";
            case USED -> "callback.assistantUsed";
            case STALE -> "callback.wrongState";
            case NOT_ALLOWED -> "callback.notAllowed";
        }));
        tx.afterCommit(() -> bestEffort("answerCallbackQuery", () -> api.answerCallbackQuery(callback.path("id").asText(), notice)));
```

Keep the rest of the method, and read the outcome as `tapped.outcome()`.

- [ ] **Step 7: Delete `TaskService.cancel`/`retry` and their results**

1. Delete `TaskService.cancel` (`:789-819`) and `retry` (`:821-863`), and the files `CancelResult.java` and `RetryResult.java`.
2. Every remaining caller in the tests becomes a command:

```java
db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Cancel(id)));
```

Find them with `grep -rn 'tasks\.\(cancel\|retry\)(\|CancelResult\|RetryResult' src`.

3. Rule cases that only proved who may cancel or retry now live in `TaskCommandsTest`'s table, as ADR 0031 says. Delete them from `TaskLifecycleTest`:
   - `cancelIsRefusedForFinishedAndUnknownTasksAndNonMembers`;
   - the cancel and retry halves of `anotherMemberOfTheGroupCannotCancelRetryOrFollowUpSomeoneElsesTask`, keeping its follow-up half until Task 4;
   - their retry twins.

   Rewrite `cancelFromAPrivateChatIsAnnouncedInTheGroupAndAnsweredThere` in `TaskCommandsTest`. The Task 1 test `theRequesterCancelsTheirTaskAndTheGroupAndTheirTopicBothHearIt` covers it, so just delete the old one.
4. In `AssistantActionsTest`, taps assert `tapped.outcome()`. The "answers in the chat itself" expectations for cancel and retry become: a refused tap returns `NOT_ALLOWED` or `STALE` with `refusal` present, and writes no outbox row.

- [ ] **Step 8: Run the whole suite, then commit**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS.

```bash
git add -A
git commit -m "Cancel and retry through the task commands; each channel answers in its own medium

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01EnUvhys3zP42GsbKq3t4ha"
```

---

### Task 3: Correct, follow up, answer and give in the module

The module gains the commands that carry words: corrections, follow-ups (a merged task's becomes a new task through Give's checks), answers to a plan's questions, and giving a task.

A question's redraw now names the question's outbox row (`edit_of`). The sender therefore waits for the original, or drops the redraw if the original was never sent.

Channels move in Task 4. Until then, `TaskService.create`, `give` and `choosePriority` insert through the module.

**Files:**
- Create: `src/main/java/dispatch/core/Origin.java`
- Create: `src/main/resources/db/026-outbox-edit-of.sql`
- Modify: `src/main/java/dispatch/core/TaskCommand.java` (add `Correct`, `FollowUp`, `Answer` with `Choice`, and `Give`)
- Modify: `src/main/java/dispatch/core/TaskCommands.java`:
  - the constructor gains `projects`, `taskTopics`, `requiresWorker`;
  - the new commands;
  - `insertTask`, moved from `TaskService`;
  - `enqueueQuestion`, `questionPayload` and `answersText`, moved from `TaskService`.
- Modify: `src/main/java/dispatch/core/TaskService.java`:
  - it builds `TaskCommands` with the new arguments;
  - `create`, `give` and `choosePriority` call `commands.insertTask(...)`, and its own `insertTask` goes;
  - `enqueueQuestion` and `questionPayload` go.
- Modify: `src/main/java/dispatch/core/RunTransitions.java:86` (`TaskService.enqueueQuestion` → `TaskCommands.enqueueQuestion`)
- Modify: `src/main/java/dispatch/store/Outbox.java` (`edit_of`: `enqueueEditOf`, `questionRow`, `original`, and `Message.editOf`)
- Modify: `src/main/java/dispatch/telegram/OutboxSender.java` (a redraw of a row)
- Modify: `src/main/java/dispatch/store/Database.java:38-44` (add the migration)
- Modify: `src/main/resources/texts_en.properties`, `texts_mn.properties`
- Tests: `src/test/java/dispatch/core/TaskCommandsTest.java`, `src/test/java/dispatch/telegram/OutboxSenderTest.java`

**Interfaces:**
- Consumes: Tasks 1 and 2.
- Produces, for Tasks 4 and 5:
  - `record Origin(String ref) { static Origin page(); static final String PAGE = "desk:"; }`
  - `TaskCommand.Correct(long taskId, OptionalInt planSeq, String text)`
  - `TaskCommand.FollowUp(long taskId, String text, Origin origin)`
  - `TaskCommand.Answer(long taskId, int planSeq, int question, Choice choice)`, with `Choice.Written(String)`, `Choice.Option(int index)` (0-based) and `Choice.YouDecide()`
  - `TaskCommand.Give(String project, String text, Priority priority, Origin origin)`
  - `static void TaskCommands.enqueueQuestion(Tx, Task, int planSeq, List<PlanQuestion>, int index, Instant)`
  - `long TaskCommands.insertTask(Tx, Requester, Config.Project, String description, Priority, String originRef, Instant)`, package-private, used by `TaskService` until Task 5
  - `Outbox.enqueueEditOf(Tx, Long taskId, OutboxKind, String chatRef, long editOf, JsonNode, Instant)`
  - `Outbox.questionRow(Tx, long taskId, int planSeq, int index) -> Optional<Long>`

- [ ] **Step 1: Write the failing tests**

Add to `TaskCommandsTest`. Extend its helpers with `awaitingApproval(String messageId, Plan plan)`, copied from `TaskLifecycleTest` and using `claimFor`, and a `PLAN_WITH_TWO_QUESTIONS` constant, copied from `TaskLifecycleTest:52`. Then add these tests:

```java
    @Test
    void aCorrectionPlansAgainAndTellsTheRequester() {
        long id = awaitingApproval("5", PLAN);

        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Correct(id, OptionalInt.of(1), " Keep the default ")));

        Map<String, String> planning = row("SELECT kind, cause, instruction FROM run WHERE task_id = ? ORDER BY seq DESC LIMIT 1", id);
        assertEquals(Map.of("kind", "PLAN", "cause", "CORRECTION", "instruction", "Keep the default"), planning);
        assertEquals(List.of(BOLD.ref()), chatsOf("CORRECTION_QUEUED"));
    }

    @Test
    void aCorrectionWithoutAPlanNumberCorrectsTheOneThatWaits() {
        long id = awaitingApproval("5", PLAN);
        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Correct(id, OptionalInt.empty(), "more")));
    }

    @Test
    void aFollowUpContinuesAFinishedTask() {
        long id = completed("5");

        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.FollowUp(id, "Also log it", new Origin("telegram:100/9"))));

        assertEquals("EXECUTING", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals(List.of(BOLD.ref()), chatsOf("FOLLOW_UP_QUEUED"));
    }

    @Test
    void aMergedTasksFollowUpBecomesANewTaskGivenFromItsOrigin() {
        long id = merged("5");

        CommandResult.Created created = assertInstanceOf(CommandResult.Created.class,
                run(BOLD, new TaskCommand.FollowUp(id, "Also log it", new Origin("telegram:100/9"))));

        Map<String, String> task = row("SELECT origin_ref, description FROM task WHERE id = ?", created.taskId());
        assertEquals("telegram:100/9", task.get("origin_ref"));
        assertTrue(task.get("description").startsWith("Also log it\n\n↩️ #" + id + " "), task.get("description"));
        assertEquals(List.of(BOLD.ref()), chatsOf("FOLLOW_UP_NEW_TASK"));
        assertEquals(created, run(BOLD, new TaskCommand.FollowUp(id, "Also log it", new Origin("telegram:100/9"))),
                "the same origin gives the same task, and writes nothing more");
        assertEquals(List.of(BOLD.ref()), chatsOf("FOLLOW_UP_NEW_TASK"));
    }

    @Test
    void aMergedTasksFollowUpIsRefusedAsGivingATaskIsWhenItsProjectIsUnavailable() {
        long id = merged("5");
        unavailable.set("repos/alm is being cloned");
        Map<String, String> before = counts();

        CommandResult.Refused refused = assertInstanceOf(CommandResult.Refused.class,
                run(BOLD, new TaskCommand.FollowUp(id, "Also log it", Origin.page())));

        assertEquals(Refusal.PROJECT_UNAVAILABLE, refused.reason());
        assertTrue(refused.words().render(Language.EN).contains("repos/alm is being cloned"), refused.words().render(Language.EN));
        assertEquals(before, counts());
    }

    @Test
    void answersGoInOrderAndTheLastSendsThemAllAsOneCorrection() {
        long id = awaitingApproval("5", PLAN_WITH_TWO_QUESTIONS);

        assertEquals(Refusal.OUT_OF_ORDER, refusalOf(run(BOLD, new TaskCommand.Answer(id, 1, 2, new TaskCommand.Choice.Option(0)))));
        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Answer(id, 1, 1, new TaskCommand.Choice.Option(1))));
        assertEquals(Refusal.ALREADY_ANSWERED, refusalOf(run(BOLD, new TaskCommand.Answer(id, 1, 1, new TaskCommand.Choice.Written("x")))));
        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Answer(id, 1, 2, new TaskCommand.Choice.YouDecide())));

        String correction = row("SELECT instruction FROM run WHERE task_id = ? ORDER BY seq DESC LIMIT 1", id).get("instruction");
        assertTrue(correction.contains("1. Which environments? → prod"), correction);
        assertTrue(correction.contains("2. Keep the old default? → " + Text.of("answer.youDecide").render(Language.MN)), correction);
    }

    @Test
    void anAnswerRedrawsTheQuestionByItsOwnRowEvenBeforeItIsSent() {
        long id = awaitingApproval("5", PLAN_WITH_TWO_QUESTIONS);
        String question = row("SELECT id FROM outbox WHERE kind = 'PLAN_QUESTION' AND task_id = ?", id).get("id");

        run(BOLD, new TaskCommand.Answer(id, 1, 1, new TaskCommand.Choice.Written("staging")));

        Map<String, String> redraw = row("SELECT edit_of, edit_ref FROM outbox WHERE kind = 'PLAN_QUESTION' AND edit_of IS NOT NULL");
        assertEquals(question, redraw.get("edit_of"), "names the question's row, which Telegram has not got yet");
        assertEquals(null, redraw.get("edit_ref"));
    }

    @Test
    void emptyWordsAreRefusedOnlyOnceWhoAndWhenAllowIt() {
        long id = awaitingApproval("5", PLAN_WITH_TWO_QUESTIONS);
        assertEquals(Refusal.EMPTY, refusalOf(run(BOLD, new TaskCommand.Correct(id, OptionalInt.of(1), "  "))));
        assertEquals(Refusal.EMPTY, refusalOf(run(BOLD, new TaskCommand.Answer(id, 1, 1, new TaskCommand.Choice.Option(7)))));
        assertEquals(Refusal.NOT_MEMBER, refusalOf(run(STRANGER, new TaskCommand.Correct(id, OptionalInt.of(1), "  "))),
                "a stranger never learns their words were blank");
        assertEquals(Refusal.STALE_PLAN, refusalOf(run(BOLD, new TaskCommand.Correct(id, OptionalInt.of(9), ""))));
    }

    @Test
    void givingATaskIsRefusedInOrderAndGivenOncePerOrigin() {
        assertEquals(Refusal.NOT_MEMBER, refusalOf(run(STRANGER, new TaskCommand.Give("alm", "Fix it", Priority.NORMAL, Origin.page()))));
        assertEquals(Refusal.UNKNOWN_PROJECT, refusalOf(run(BOLD, new TaskCommand.Give("nope", "Fix it", Priority.NORMAL, Origin.page()))));
        assertEquals(Refusal.EMPTY, refusalOf(run(BOLD, new TaskCommand.Give("alm", " ", Priority.NORMAL, Origin.page()))));
        Origin origin = Origin.page();
        CommandResult.Created created = assertInstanceOf(CommandResult.Created.class,
                run(BOLD, new TaskCommand.Give("alm", "Fix it", Priority.URGENT, origin)));
        assertEquals(created, run(BOLD, new TaskCommand.Give("alm", "Fix it", Priority.URGENT, origin)));
        assertEquals(List.of(BOLD.ref()), chatsOf("TASK_GIVEN_ON_DESK"), "a task from a page tells the chat it was given there");
    }

    private static Refusal refusalOf(CommandResult result) {
        return assertInstanceOf(CommandResult.Refused.class, result).reason();
    }
```

For this fixture:
- `Projects` takes the unavailable reason from an `AtomicReference<String> unavailable` field, which is `null` by default: `project -> Optional.ofNullable(unavailable.get())`.
- `completed(messageId)` runs a failed-execution task's execution to success through `transitions` (copy the success path from `TaskLifecycleTest`).
- `merged(messageId)` is `completed` plus a `Tasks.merged(tx, id, clock.instant())` in a transaction.

Extend `aRefusedCommandWritesNothingAndSaysWhyInBothLanguages`'s cases with at least:
- `STALE_PLAN` (correct with plan 9);
- `OPEN_QUESTIONS` (approve, arriving in Task 5; for now correct cases only);
- `OUT_OF_ORDER`;
- `ALREADY_ANSWERED`;
- `NOT_EXECUTED` (a follow-up on a task that failed while planning);
- `EMPTY`;
- `UNKNOWN_PROJECT`;
- `PROJECT_UNAVAILABLE`.

Every refusal a command gives must appear there: add this test, which fails until each refusal has a case:

```java
    @Test
    void everyRefusalHasACaseInTheTable() {
        // The table above names each refusal a command gives; one missing here is one no test words.
        assertEquals(java.util.EnumSet.allOf(Refusal.class), refusalsCovered());
    }
```

Here `refusalsCovered()` returns the set of `reason`s the table's cases use. Put the cases in a field, so both tests read them. `MERGED` is Merges' refusal and no command's, so leave it out: compare against `EnumSet.complementOf(EnumSet.of(Refusal.MERGED))`.

In `OutboxSenderTest`, following its existing fixture (read how it enqueues rows and fakes `BotApi`):

- `aRedrawWaitsForTheMessageItRedrawsAndThenEditsIt`:
  1. Enqueue a `PLAN_QUESTION` row, then an `enqueueEditOf` row naming it.
  2. Make the first send fail temporarily (the fake answers a 429 or 500 once), then deliver twice.
  3. Expect exactly one `sendMessage` and one `editMessageText` with the sent message's id, both rows `SENT`.
- `aRedrawOfAMessageThatWasNeverSentIsDropped`: mark the original `FAILED`, then deliver. The redraw row becomes `FAILED` and nothing is sent.

Run: `./mvnw -q test -Dtest='TaskCommandsTest,OutboxSenderTest'`
Expected: COMPILATION ERROR, because the new commands, `Origin` and `enqueueEditOf` do not exist.

- [ ] **Step 2: The migration and the outbox**

`src/main/resources/db/026-outbox-edit-of.sql`:

```sql
-- ADR 0031: a redraw may name its original's outbox row instead of its Telegram message, so an answer given before the
-- question reached the chat redraws it once it is sent, instead of the question being sent a second time.
ALTER TABLE outbox ADD COLUMN edit_of INTEGER REFERENCES outbox (id);
```

Add `"db/026-outbox-edit-of.sql"` to the end of `Database.MIGRATIONS`.

In `Outbox`:

```java
    /**
     * Redraws the message the outbox row {@code editOf} sends, once it is sent (ADR 0031): the sender waits for it, and
     * drops this redraw if it never will be.
     */
    public static long enqueueEditOf(Tx tx, Long taskId, OutboxKind kind, String chatRef, long editOf, JsonNode payload,
                                     Instant now) {
        return tx.insert("""
                        INSERT INTO outbox (task_id, kind, chat_ref, edit_of, payload, status, next_attempt_at, created_at)
                        VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)""",
                taskId, kind, chatRef, editOf, Json.write(payload), now, now);
    }

    /** The outbox row that asks question {@code index} of plan {@code planSeq}, sent or on its way; empty if it never went out. */
    public static Optional<Long> questionRow(Tx tx, long taskId, int planSeq, int index) {
        return tx.one("""
                        SELECT id FROM outbox
                        WHERE task_id = ? AND kind = 'PLAN_QUESTION' AND edit_ref IS NULL AND edit_of IS NULL AND status <> 'FAILED'
                          AND json_extract(payload, '$.planSeq') = ? AND json_extract(payload, '$.index') = ?
                        ORDER BY id LIMIT 1""",
                row -> row.longValue("id"), taskId, planSeq, index);
    }

    /** Where a redraw's original stands: PENDING, SENT or FAILED, and what Telegram called it once sent. */
    public record Original(String status, String sentRef) {
    }

    public static Optional<Original> original(Tx tx, long id) {
        return tx.one("SELECT status, sent_ref FROM outbox WHERE id = ?", row -> new Original(row.string("status"), row.string("sent_ref")), id);
    }
```

Add `Long editOf` to `Message`, right after `editRef`, with the javadoc `@param editOf the outbox row whose message this one redraws, once that is sent; null otherwise`. In `nextDue`, select `edit_of` and read it with `row.longOrNull("edit_of")`.

`sentQuestion` also skips redraws, so its `WHERE` gains `AND edit_of IS NULL`.

- [ ] **Step 3: The sender redraws a row's message**

In `OutboxSender.deliver`, after the render step and before the `editRef` branch:

```java
        if (message.editOf() != null) {
            redrawRow(message, attempts, rendered);
            return;
        }
```

Change `edit(message, attempts, rendered)` to `edit(message, attempts, rendered, String editRef)`, using `Refs.messageId(editRef)`. The existing caller passes `message.editRef()`. Then add:

```java
    /** How long a redraw waits before it looks again for the message it redraws. */
    private static final Duration WAIT_FOR_ORIGINAL = Duration.ofSeconds(5);

    /**
     * A redraw of a message that may not be sent yet (ADR 0031): it edits it once it is, waits while it is on its way, and
     * is dropped if it never will be, since there is nothing to redraw.
     */
    private void redrawRow(Outbox.Message message, int attempts, Renderer.Rendered rendered) {
        Outbox.Original original = db.transactionReturning(tx -> Outbox.original(tx, message.editOf())).orElseThrow();
        switch (original.status()) {
            case "SENT" -> edit(message, attempts, rendered, original.sentRef());
            case "PENDING" -> db.transaction(tx -> Outbox.retryLater(tx, message.id(), message.attempts(),
                    clock.instant().plus(WAIT_FOR_ORIGINAL), "waiting for the message it redraws"));
            default -> {
                db.transaction(tx -> Outbox.markFailed(tx, message.id(), attempts, "the message it redraws was never sent"));
                Log.warn("outbox.redraw_dropped", "id", message.id(), "kind", message.kind(), "original", message.editOf());
            }
        }
    }
```

A waiting redraw keeps its attempt count. It fails as soon as its original does, and the original gives up within the sender's 24 hours.

- [ ] **Step 4: The new commands**

`src/main/java/dispatch/core/Origin.java`:

```java
package dispatch.core;

import java.util.Objects;
import java.util.UUID;

/**
 * Where a task comes from: the message that gave it, or a page. Unique per task, so giving the same one twice gives one
 * task (ADR 0010). It names a source; it never says where a reply goes.
 */
public record Origin(String ref) {

    /** What a page's origin starts with: no Telegram message, so nothing in the chat to reply to (D-2b). */
    public static final String PAGE = "desk:";

    public Origin {
        Objects.requireNonNull(ref, "ref");
    }

    /** A page has no message a repeat could name again: a new origin every time. */
    public static Origin page() {
        return new Origin(PAGE + UUID.randomUUID());
    }
}
```

In `TaskCommand`, add:

```java
    /** A correction of plan {@code planSeq}, or of whichever plan waits now when it names none (a topic message, an addition). */
    record Correct(long taskId, OptionalInt planSeq, String text) implements TaskCommand {
    }

    /** More work on a finished task (ADR 0006); a merged task's becomes a new task, given from {@code origin}. */
    record FollowUp(long taskId, String text, Origin origin) implements TaskCommand {
    }

    /** An answer to question {@code question} (1-based) of plan {@code planSeq} (G-1d). */
    record Answer(long taskId, int planSeq, int question, Choice choice) implements TaskCommand {
    }

    /** What an answer says: the member's words, one of the question's options, or the agent's own choice. */
    sealed interface Choice {

        record Written(String text) implements Choice {
        }

        /** 0-based, as the buttons carry it. */
        record Option(int index) implements Choice {
        }

        record YouDecide() implements Choice {
        }
    }

    /** A task on a project the requester's groups have (ADR 0012), given once per origin. */
    record Give(String project, String text, Priority priority, Origin origin) implements TaskCommand {
    }
```

(Import `java.util.OptionalInt` and `dispatch.domain.Priority`.)

Add to `texts_en.properties`:

```properties
refused.stalePlan=#{0}: a newer plan replaced this one, or it no longer waits for you
refused.openQuestions=#{0}: its plan still has open questions; answer them, and the agent plans again with the answers
refused.outOfOrder=#{0}: answer the questions in order; an earlier one is still open
refused.answered=#{0}: that question already has its answer
refused.notExecuted=#{0} never got as far as carrying out its plan, so it takes no follow-up; retry it, or give a new task
refused.emptyCorrection=a correction needs a few words
refused.emptyFollowUp=a follow-up needs a few words
refused.emptyAnswer=the answer is empty, or that option is not one of the question''s
refused.emptyTask=a task needs a few words
refused.unknownProject={0} is not one of your projects
refused.projectUnavailable=the project {0} cannot take tasks now: {1}
# What the agent reads when the requester lets it decide a question (G-1d): in Mongolian, as the chat asks.
answer.youDecide=Choose the most plausible solution yourself, and note it as an assumption.
```

Add to `texts_mn.properties`:

```properties
refused.stalePlan=#{0}: энэ төлөвлөгөөг шинэ нь сольсон, эсвэл таныг хүлээхээ больсон
refused.openQuestions=#{0}: төлөвлөгөөнд хариулаагүй асуулт байна; хариулбал агент хариултыг тусгаж дахин төлөвлөнө
refused.outOfOrder=#{0}: асуултуудад дарааллаар нь хариулна уу; өмнөх асуулт хариултгүй байна
refused.answered=#{0}: энэ асуултад аль хэдийн хариулсан
refused.notExecuted=#{0} даалгавар хэрэгжүүлэлт хүртлээ яваагүй тул нэмэлт хүсэлт өгөх боломжгүй; дахин оролдох эсвэл шинэ даалгавар өгнө үү
refused.emptyCorrection=засварт хэдэн үг бичнэ үү
refused.emptyFollowUp=дараагийн алхамд хэдэн үг бичнэ үү
refused.emptyAnswer=хариулт хоосон байна, эсвэл тэр сонголт асуултад байхгүй
refused.emptyTask=даалгаварт хэдэн үг бичнэ үү
refused.unknownProject={0} таны төслүүдийн нэг биш
refused.projectUnavailable=«{0}» төсөл одоогоор ажиллах боломжгүй: {1}
# What the agent reads when the requester lets it decide a question (G-1d): in Mongolian, as the chat asks.
answer.youDecide=Та хамгийн боломжит шийдлийг сонгож, таамаглал болгон тэмдэглэ.
```

`answer.youDecide`'s Mongolian is exactly `messages_mn`'s `plan.youDecide`, so the agent reads what it always read.

In `TaskCommands`:

1. Constructor. It becomes `TaskCommands(Groups, Projects, ActiveRuns, Clock, Runnable wakeScheduler, Runnable wakeOutbox, boolean taskTopics, boolean requiresWorker)`, storing `groups`, `projects`, `taskTopics` and `requiresWorker` too. `TaskService` passes its own values.

2. The `check` and `carryOut` arms:

```java
            case TaskCommand.Correct correct -> {
                TaskAccess.Verdict verdict = access.of(tx, who.ref(), correct.taskId());
                int planSeq = correct.planSeq().orElse(verdict.planSeq());
                Optional<Refusal> refused = verdict.refusal(TaskAccess.Action.CORRECT, planSeq);
                if (refused.isEmpty() && isBlank(correct.text())) {
                    refused = Optional.of(Refusal.EMPTY);
                }
                yield refused.map(refusal -> refused(command, refusal, verdict.task()));
            }
            case TaskCommand.FollowUp followUp -> {
                TaskAccess.Verdict verdict = access.of(tx, who.ref(), followUp.taskId());
                Optional<Refusal> refused = verdict.refusal(TaskAccess.Action.FOLLOW_UP);
                if (refused.isEmpty() && isBlank(followUp.text())) {
                    refused = Optional.of(Refusal.EMPTY);
                }
                if (refused.isPresent()) {
                    yield refused.map(refusal -> refused(command, refusal, verdict.task()));
                }
                // A merged task's branch is gone: more work is a new task, refused exactly as giving one is.
                yield verdict.task().mergedAt() == null ? Optional.empty() : check(tx, who, newTaskFrom(verdict.task(), followUp));
            }
            case TaskCommand.Answer answer -> {
                TaskAccess.Verdict verdict = access.of(tx, who.ref(), answer.taskId());
                Optional<Refusal> refused = verdict.answerRefusal(answer.planSeq(), answer.question());
                if (refused.isEmpty() && answerText(verdict.task(), answer).isEmpty()) {
                    refused = Optional.of(Refusal.EMPTY);
                }
                yield refused.map(refusal -> refused(command, refusal, verdict.task()));
            }
            case TaskCommand.Give give -> giveRefusal(tx, who, give);
```

```java
            case TaskCommand.Correct correct -> {
                Task task = task(tx, correct.taskId());
                yield correct(tx, who, task, correct.planSeq().orElse(access.of(tx, who.ref(), task).planSeq()), correct.text().strip());
            }
            case TaskCommand.FollowUp followUp -> followUp(tx, who, task(tx, followUp.taskId()), followUp);
            case TaskCommand.Answer answer -> {
                Task task = task(tx, answer.taskId());
                yield answer(tx, who, task, answer, answerText(task, answer).orElseThrow());
            }
            case TaskCommand.Give give -> give(tx, who, give, true);
```

3. `taskId(command)` gains its arms: `Correct`, `FollowUp` and `Answer` give their `taskId`, and `Give` gives 0.

4. The bodies. `correct` is `TaskService.correct`'s effect (`:584-595`) with its news through `enqueueForRequester`. `followUp` is `TaskService.followUp` (`:874-912`). `answer` is `TaskService.answer` (`:613-646`) with the redraw by row.

```java
    /** The requester's correction: the task is planned again in its planning session, with it as the run's instruction. */
    private CommandResult correct(Tx tx, Requester who, Task task, int planSeq, String text) {
        Instant now = clock.instant();
        changePhase(tx, task, Phase.AWAITING_APPROVAL, Phase.PLANNING, now);
        Runs.insert(tx, new Runs.NewRun(task.id(), Runs.nextSeq(tx, task.id()), RunKind.PLAN, RunCause.CORRECTION, text, who), now);
        Events.record(tx, task.id(), null, who.ref(), Phase.AWAITING_APPROVAL, Phase.PLANNING, "correction of plan " + planSeq, now);
        Outbox.enqueueForRequester(tx, task, OutboxKind.CORRECTION_QUEUED, Json.object().put("taskId", task.id()).put("by", who.name()), now);
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(wakeScheduler);
        logTransition(tx, task.id(), Phase.AWAITING_APPROVAL, Phase.PLANNING, who.ref());
        return new CommandResult.Done(task.id(), isRequester(who, task));
    }

    /**
     * More work on a finished task, in its building session and branch, without a new plan (ADR 0006). A merged task's
     * branch is gone, so its follow-up is a new task, planned from the base the merge moved and given from the follow-up's
     * origin; the old task's topic hears where it went.
     */
    private CommandResult followUp(Tx tx, Requester who, Task task, TaskCommand.FollowUp followUp) {
        Instant now = clock.instant();
        if (task.mergedAt() != null) {
            Optional<Task> replayed = Tasks.findByOrigin(tx, followUp.origin().ref());
            if (replayed.isPresent()) {
                return new CommandResult.Created(replayed.get().id());
            }
            CommandResult given = give(tx, who, newTaskFrom(task, followUp), false);
            if (given instanceof CommandResult.Created created) {
                Outbox.enqueueForRequester(tx, task, OutboxKind.FOLLOW_UP_NEW_TASK,
                        Json.object().put("taskId", task.id()).put("newTaskId", created.taskId()), now);
                tx.afterCommit(wakeOutbox);
            }
            return given;
        }
        changePhase(tx, task, task.phase(), Phase.EXECUTING, now);
        int seq = Runs.nextSeq(tx, task.id());
        Runs.insert(tx, new Runs.NewRun(task.id(), seq, RunKind.EXECUTE, RunCause.FOLLOW_UP, followUp.text().strip(), who), now);
        Events.record(tx, task.id(), seq, who.ref(), task.phase(), Phase.EXECUTING, "follow-up", now);
        Outbox.enqueueForRequester(tx, task, OutboxKind.FOLLOW_UP_QUEUED, Json.object().put("taskId", task.id()).put("by", who.name()), now);
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(wakeScheduler);
        logTransition(tx, task.id(), task.phase(), Phase.EXECUTING, who.ref());
        return new CommandResult.Done(task.id(), isRequester(who, task));
    }

    /** A merged task's follow-up as the task it becomes; the last line points back without words, so it never sways the plan's language. */
    private static TaskCommand.Give newTaskFrom(Task merged, TaskCommand.FollowUp followUp) {
        return new TaskCommand.Give(merged.project(), followUp.text().strip() + "\n\n↩️ #" + merged.id() + " " + merged.prUrl(),
                merged.priority(), followUp.origin());
    }

    /**
     * An answer: the question message is redrawn with it and the next open question is sent; the last answer sends them all
     * to the agent as one correction, exactly as a typed one (G-1d).
     */
    private CommandResult answer(Tx tx, Requester who, Task task, TaskCommand.Answer answer, String text) {
        Instant now = clock.instant();
        int planSeq = answer.planSeq();
        int index = answer.question();
        List<PlanQuestion> questions = Plan.parse(task.planJson()).questionItems();
        if (!PlanAnswers.record(tx, task.id(), planSeq, index, text, who.ref(), Outbox.sentQuestion(tx, task.id(), planSeq, index).orElse(null), now)) {
            throw new IllegalStateException("task #" + task.id() + " was allowed an answer to question " + index + ", yet it had one");
        }
        // The question's own row, not its Telegram message: an answer given before the question reached the chat redraws it
        // once it is sent, instead of the question going out a second time (ADR 0031).
        Outbox.questionRow(tx, task.id(), planSeq, index).ifPresent(row -> Outbox.enqueueEditOf(tx, task.id(), OutboxKind.PLAN_QUESTION,
                task.requester().ref(), row, questionPayload(task.id(), planSeq, questions, index).put("answer", text), now));
        tx.afterCommit(wakeOutbox);
        Map<Integer, String> answers = PlanAnswers.of(tx, task.id(), planSeq);
        for (int next = 1; next <= questions.size(); next++) {
            if (!answers.containsKey(next)) {
                enqueueQuestion(tx, task, planSeq, questions, next, now);
                return new CommandResult.Done(task.id(), isRequester(who, task));
            }
        }
        return correct(tx, who, task, planSeq, answersText(questions, answers));
    }

    /** What an answer says, or empty when it says nothing: blank words, or an option the question does not have. */
    private static Optional<String> answerText(Task task, TaskCommand.Answer answer) {
        return switch (answer.choice()) {
            case TaskCommand.Choice.Written written -> Optional.ofNullable(written.text()).map(String::strip).filter(text -> !text.isEmpty());
            case TaskCommand.Choice.YouDecide decide -> Optional.of(Text.of("answer.youDecide").render(Language.MN));
            case TaskCommand.Choice.Option option -> {
                List<PlanQuestion> questions = Plan.parse(task.planJson()).questionItems();
                List<String> options = questions.get(answer.question() - 1).options();
                yield option.index() >= 0 && option.index() < options.size() ? Optional.of(options.get(option.index())) : Optional.empty();
            }
        };
    }
```

Move `enqueueQuestion`, `questionPayload` and `answersText` from `TaskService` (`:683-719`) into `TaskCommands` unchanged. `enqueueQuestion` stays `static` and package-private. Point `RunTransitions:86` at `TaskCommands.enqueueQuestion`.

5. Giving a task. The checks are `TaskService.create`'s (`:388-426`), ordered who, then where, then what. The insert is `TaskService.insertTask` (`:467-491`), moved here unchanged:

```java
    /** Why giving would be refused: who, then the project, then the words. A repeated origin is no refusal: it gives the same task. */
    private Optional<CommandResult.Refused> giveRefusal(Tx tx, Requester who, TaskCommand.Give give) {
        if (Tasks.existsWithOrigin(tx, give.origin().ref())) {
            return Optional.empty();
        }
        if (!groups.isMember(who.ref())) {
            return Optional.of(new CommandResult.Refused(Refusal.NOT_MEMBER, Text.of("refused.notMember")));
        }
        Optional<Config.Project> project = memberProject(who, give.project());
        if (project.isEmpty()) {
            return Optional.of(new CommandResult.Refused(Refusal.UNKNOWN_PROJECT, Text.of("refused.unknownProject", nonNull(give.project()))));
        }
        Optional<String> unavailable = projects.unavailableReason(project.get());
        if (unavailable.isPresent()) {
            return Optional.of(new CommandResult.Refused(Refusal.PROJECT_UNAVAILABLE,
                    Text.of("refused.projectUnavailable", project.get().name(), unavailable.get())));
        }
        return isBlank(give.text()) ? Optional.of(new CommandResult.Refused(Refusal.EMPTY, Text.of("refused.emptyTask"))) : Optional.empty();
    }

    /**
     * Gives the task, or finds the one its origin gave before. A task from a page tells its requester's chat so, since no
     * message of theirs there gave it (D-2b); a merged task's follow-up says where it went itself.
     */
    private CommandResult give(Tx tx, Requester who, TaskCommand.Give give, boolean fromPageSaysSo) {
        Optional<Task> replayed = Tasks.findByOrigin(tx, give.origin().ref());
        if (replayed.isPresent()) {
            tx.afterCommit(() -> Log.info("task.duplicate_ignored", "origin", give.origin().ref()));
            return new CommandResult.Created(replayed.get().id());
        }
        Instant now = clock.instant();
        Config.Project project = memberProject(who, give.project()).orElseThrow();
        long id = insertTask(tx, who, project, give.text().strip(), give.priority(), give.origin().ref(), now);
        if (fromPageSaysSo && give.origin().ref().startsWith(Origin.PAGE)) {
            Task task = Tasks.find(tx, id).orElseThrow();
            Outbox.enqueueForRequester(tx, task, OutboxKind.TASK_GIVEN_ON_DESK, Json.object().put("taskId", id)
                    .put("project", task.project()).put("priority", give.priority().name()).put("title", task.title()), now);
            tx.afterCommit(wakeOutbox);
        }
        return new CommandResult.Created(id);
    }

    /** The project {@code key} names (its name or alias), if it is one of the requester's groups' projects. */
    private Optional<Config.Project> memberProject(Requester who, String key) {
        Set<String> mine = groups.projectsOfMember(who.ref());
        return key == null ? Optional.empty() : projects.find(key).filter(project -> mine.contains(project.name()));
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }
```

A merged follow-up's `check` goes through `check(tx, who, newTaskFrom(...))`, which lands in `giveRefusal`. An answer's closing correction calls `correct(...)` directly: the verdict allowed the answer, so the task awaits approval on this plan and the correction is allowed.

`insertTask` moves here as a package-private method with `TaskService`'s signature. It uses `groups.chatOfTask`, `taskTopics` and `requiresWorker` exactly as before, and its `enqueue(...)` calls become `Outbox.enqueue(...)` plus `tx.afterCommit(wakeOutbox)`.

In `TaskService`, delete `insertTask`, and have `create`, `give` and `choosePriority` call `commands.insertTask(...)`. Keep `TaskService.title` (`static`) and call it from `insertTask` as `TaskService.title(description)`.

6. `words(...)` gains the refusals these commands give. Replace the throwing arm:

```java
            case STALE_PLAN -> Text.of("refused.stalePlan", taskId);
            case OPEN_QUESTIONS -> Text.of("refused.openQuestions", taskId);
            case OUT_OF_ORDER -> Text.of("refused.outOfOrder", taskId);
            case ALREADY_ANSWERED -> Text.of("refused.answered", taskId);
            case NOT_EXECUTED -> Text.of("refused.notExecuted", taskId);
            case EMPTY -> switch (command) {
                case TaskCommand.Correct correct -> Text.of("refused.emptyCorrection");
                case TaskCommand.FollowUp followUp -> Text.of("refused.emptyFollowUp");
                case TaskCommand.Answer answer -> Text.of("refused.emptyAnswer");
                default -> Text.of("refused.emptyTask");
            };
            // Merges' own refusal, and giving's, which words its project where it is refused.
            case MERGED, UNKNOWN_PROJECT, PROJECT_UNAVAILABLE -> throw new IllegalStateException(command + " is never refused as " + refusal);
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw -q test -Dtest='TaskCommandsTest,OutboxSenderTest,TaskLifecycleTest,PlanQuestionsTest'`
Expected: PASS. `TaskService`'s own `correct`, `followUp` and `answer` still exist and still reply; Task 4 moves their channels.

- [ ] **Step 6: Run the whole suite, then commit**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS.

```bash
git add -A
git commit -m "Correct, follow up, answer and give in the task commands; a question's redraw waits for the question

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01EnUvhys3zP42GsbKq3t4ha"
```

---

### Task 4: Corrections, follow-ups, answers and additions through every channel

Telegram (topic messages, replies, question buttons and question replies), the pages, the assistant and group additions run these commands through the module. The "type your answer" prompt moves to Telegram. `TaskService` loses these commands and their results.

**Files:**
- Modify: `src/main/java/dispatch/telegram/UpdateHandler.java`:
  - topic routing (`:297-313`);
  - `replyToTask` (`:672-699`);
  - `replyToQuestion` (`:705-736`);
  - `onQuestionButton` (`:739-775`);
  - `onAdditionButton` (`:968-986`);
  - `reply`/`refuse`, which gain a command hint.
- Modify: `src/main/java/dispatch/ui/TasksApi.java` (`answer`, `correct`, `followUp`; drop `YOU_DECIDE`, `access` where unused, `Outbox`)
- Modify: `src/main/java/dispatch/core/AssistantActions.java` (answer and follow-up proposals and taps; the constructor loses `youDecide`)
- Modify: `src/main/java/dispatch/App.java` (`new AssistantActions(tasks, groups, projects, clock)`)
- Modify: `src/main/java/dispatch/core/GroupAdditions.java` (`apply` returns `Applied`)
- Modify: `src/main/java/dispatch/core/TaskService.java`: delete `correct`, `correctLatest`, `answer`, `chooseOption`, `askForAnswer`, `followUp`, `refuse`, `refusalPayload`, `notRequester`, `answerRefusal`, and the now-unused imports.
- Delete: `src/main/java/dispatch/core/CorrectResult.java`, `FollowUpResult.java`, `AnswerResult.java`
- Modify: `src/main/resources/messages_mn.properties`: remove `plan.youDecide`, which is now `answer.youDecide` in `texts_*`. `button.youDecide`, the button's label, stays.
- Tests: `UpdateHandlerTest`, `PlanQuestionsTest`, `GroupAdditionsTest`, `TasksApiTest`, `AssistantActionsTest`, `TaskLifecycleTest`, plus every test calling the deleted methods (`grep -rn 'tasks\.\(correct\|correctLatest\|answer\|chooseOption\|askForAnswer\|followUp\)(' src/test`).

**Interfaces:**
- Consumes: Task 3's commands and `Origin`; Task 2's `reply`, `notice`, `answered` and `Tapped`.
- Produces:
  - `GroupAdditions.Applied(Outcome outcome, Optional<Text> refusal)`, returned by `GroupAdditions.apply`.
  - `AssistantActions(TaskService, Groups, Projects, Clock)`.

- [ ] **Step 1: Write the failing channel tests**

In `UpdateHandlerTest` and `PlanQuestionsTest`, replace every assertion on `CORRECTION_REFUSED` and `FOLLOW_UP_REFUSED` rows, and on `CORRECTION_QUEUED`/`FOLLOW_UP_QUEUED` sent *under the actor's message*, with the new shape:
- **Refusals:** one `REFUSED` row under the actor's message, whose `payload.text` is the refusal's MN words.
- **Successes:** the news row goes to the requester (`chat_ref` the requester's ref, `reply_to_ref` the task's private origin), and no reply under the actor's message.

Add these tests:
- `aFollowUpToATaskThatNeverExecutedNamesTheRetryToType`: a reply to a planning-failed task's `TASK_FAILED` message leaves a `REFUSED` row with `hint` = `/retry <id>`.
- `aWrittenAnswerBeforeTheQuestionIsSentSendsNoSecondQuestion`: answer through `TasksApi.answer` while the question row is `PENDING`. Expect exactly one `PLAN_QUESTION` row without `edit_of`, and one with it.
- `theWriteYourAnswerButtonAsksOnceAndNotForAnotherPlansQuestion`: `q:<id>:1:1:w` enqueues one `PLAN_ANSWER_PROMPT`. A second tap's notice is `plan.answerPromptOpen`. A tap on `q:<id>:9:1:w` gets `refused.stalePlan`'s words as its notice.

In `GroupAdditionsTest`, `aTapTheTaskRefusesSaysWhyAndKeepsTheAdditionForLater` now expects:
- `apply` returns `Applied(REFUSED, Optional.of(words))` with no outbox row from the command;
- the Telegram test for the addition button expects a `REFUSED` row under the offer.

Run: `./mvnw -q test -Dtest='UpdateHandlerTest,PlanQuestionsTest,GroupAdditionsTest,TasksApiTest,AssistantActionsTest'`
Expected: FAIL.

- [ ] **Step 2: Telegram**

1. Topic routing (`:306-311`):

```java
            Task task = topicTask.get();
            TaskCommand more = task.phase() == Phase.COMPLETED || task.phase() == Phase.FAILED
                    ? new TaskCommand.FollowUp(task.id(), text(message), new Origin(origin))
                    : new TaskCommand.Correct(task.id(), OptionalInt.empty(), text(message));
            reply(tx, who, more, commands.run(tx, who, more), origin, chatRef);
```

2. `replyToTask`: a reply to a result is a `FollowUp(sent.taskId(), text(message), new Origin(origin))`. One to `PLAN_READY` is a `Correct(taskId, OptionalInt.of(planSeq), text(message))`. Each goes through `reply(...)`.
3. `replyToQuestion`: the switch goes. What remains:

```java
        JsonNode question = Json.read(sent.get().payload());
        TaskCommand answer = new TaskCommand.Answer(sent.get().taskId(), question.path("planSeq").asInt(), question.path("index").asInt(),
                new TaskCommand.Choice.Written(text(message)));
        // Every refusal is answered in words: a reply has no callback to answer, and silence would look like it was taken.
        reply(tx, who, answer, commands.run(tx, who, answer), origin, chatRef);
        return true;
```

4. `onQuestionButton`: `questionRef` is only for the prompt now.

```java
        int seq = planSeq.get().intValue();
        int question = index.get().intValue();
        if (parts[4].equals("w")) {
            askForAnswer(tx, callbackId, who, taskId.get(), seq, question, Refs.message(chatId, message.path("message_id").asLong(), null));
            return;
        }
        TaskCommand.Choice choice = parts[4].equals("d") ? new TaskCommand.Choice.YouDecide() : new TaskCommand.Choice.Option(option.get().intValue());
        notice(tx, callbackId, commands.run(tx, who, new TaskCommand.Answer(taskId.get(), seq, question, choice)), "callback.answered");
```

and the prompt, moved from `TaskService.askForAnswer`:

```java
    /**
     * ✍️ under a question: the requester writes their own answer as a forced reply under it (G-1d), once. "You decide" stands
     * in for the words not written yet: it is never empty, so the check says only whether this question may be answered now.
     */
    private void askForAnswer(Tx tx, String callbackId, Requester who, long taskId, int planSeq, int index, String questionRef) {
        Optional<CommandResult.Refused> refused =
                commands.check(tx, who, new TaskCommand.Answer(taskId, planSeq, index, new TaskCommand.Choice.YouDecide()));
        if (refused.isPresent()) {
            notice(tx, callbackId, refused.get(), "callback.writeAnswer");
            return;
        }
        if (Outbox.hasAnswerPrompt(tx, taskId, planSeq, index)) {
            // The question is unanswered, so its prompt is still open: a second one would only clutter the chat.
            answer(tx, callbackId, "plan.answerPromptOpen");
            return;
        }
        Outbox.enqueue(tx, taskId, OutboxKind.PLAN_ANSWER_PROMPT, who.ref(), questionRef,
                Json.object().put("taskId", taskId).put("planSeq", planSeq).put("index", index).put("questionRef", questionRef),
                clock.instant());
        tx.afterCommit(wakeOutbox);
        answer(tx, callbackId, "callback.writeAnswer");
    }
```

5. `refuse` gains the command to type. Replace its last line:

```java
        ObjectNode payload = Json.object().put("text", refused.words().render(Language.MN));
        // A follow-up needs an execution to continue; the step there is to retry the task, typed as a command.
        if (refused.reason() == Refusal.NOT_EXECUTED && command instanceof TaskCommand.FollowUp followUp) {
            payload.put("hint", "/retry " + followUp.taskId());
        }
        enqueue(tx, OutboxKind.REFUSED, chatRef, origin, payload);
```

6. `onAdditionButton`: `GroupAdditions.Applied applied = additions.apply(...)`. Its notice switch reads `applied.outcome()`. A refusal's words go under the offer:

```java
        applied.refusal().ifPresent(words -> enqueue(tx, OutboxKind.REFUSED, Refs.chat(chatId), Refs.message(chatId, messageId, null),
                Json.object().put("text", words.render(Language.MN))));
```

- [ ] **Step 3: The pages**

In `TasksApi`, `answer`, `correct` and `followUp` become:

```java
    ObjectNode answer(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        int planSeq = number(body, "planSeq");
        int index = number(body, "index");
        TaskCommand.Choice choice = body.path("option").isIntegralNumber() ? new TaskCommand.Choice.Option(body.path("option").asInt())
                : body.path("decide").asBoolean(false) ? new TaskCommand.Choice.YouDecide()
                : new TaskCommand.Choice.Written(body.path("text").asText(""));
        return db.transactionReturning(tx -> {
            CommandResult result = commands.run(tx, requester(caller), new TaskCommand.Answer(taskId, planSeq, index, choice));
            if (result instanceof CommandResult.Refused refused) {
                throw refused(refused);
            }
            return ownTask(tx, caller, taskId).put("result", "ANSWERED");
        });
    }

    ObjectNode correct(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        int planSeq = number(body, "planSeq");
        String text = body.path("text").asText("");
        return answered(db.transactionReturning(tx -> commands.run(tx, requester(caller),
                new TaskCommand.Correct(taskId, OptionalInt.of(planSeq), text))), "CORRECTED");
    }

    /** A merged task's follow-up becomes a new task: {"result": "NEW_TASK", "taskId"}; otherwise {"result": "QUEUED"}. */
    ObjectNode followUp(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        String text = body.path("text").asText("");
        return answered(db.transactionReturning(tx -> commands.run(tx, requester(caller),
                new TaskCommand.FollowUp(taskId, text, Origin.page()))), "QUEUED");
    }
```

Delete `YOU_DECIDE`, `followUpRefused` and `correctRefused`, and the imports they leave unused. `NOT_YOURS` is still used by `ownTask`, and `NOT_ADMIN` by `list`. Keep `access` for `ownTask` and `viewer`.

- [ ] **Step 4: The assistant**

In `AssistantActions`:

1. The constructor becomes `AssistantActions(TaskService tasks, Groups groups, Projects projects, Clock clock)`, and the `youDecide` field goes. `App` creates it without `renderer.text("plan.youDecide")`.
2. `check`'s `answer` proposal keeps finding the named question (the `noQuestion` note) and keeps the `tooLong` note. Its refusals come from `commands.check(tx, who, new TaskCommand.Answer(taskId, verdict.planSeq(), index, choice))`, through `offered(...)`, where `choice` is built the way `carryOut` builds it:
   - `option` N (1-based in the proposal) → `Option(N - 1)`;
   - `decide` → `YouDecide`;
   - anything else → `Written(text)`.

   The payload keeps `planSeq`, `question`, and `option` or `answer`. For a `YouDecide`, `answer` is `Text.of("answer.youDecide").render(Language.MN)`.
3. `check`'s `followUp` proposal: `offered(tx, who, new TaskCommand.FollowUp(taskId, text, Origin.page()), payload)` after the `tooLong` check. `check` ignores the origin except to find a repeat, and a fresh one never repeats.
4. `carryOut`'s `answer` arm: `tapped(commands.run(tx, who, new TaskCommand.Answer(taskId, planSeq, index, choice)))`, with the same `choice`, and no `sentQuestion` lookup.
5. `carryOut`'s `followUp` arm: `tapped(commands.run(tx, who, new TaskCommand.FollowUp(taskId, action.path("text").asText(), new Origin(messageRef + "#a" + actionId))))`.
6. `reason(...)`, `decision(...)` for answers, and `answered(...)` go when nothing uses them. `approve` and `reject` still use `decision` until Task 5.

- [ ] **Step 5: Group additions**

In `GroupAdditions`:

```java
    /** How a tap ended, and a refusal's words when the task refused it (ADR 0031): the channel says them under the offer. */
    public record Applied(Outcome outcome, Optional<Text> refusal) {
    }
```

`apply` returns `Applied`. Its task arms become:

```java
        TaskCommand command = switch (task.phase()) {
            case AWAITING_APPROVAL -> new TaskCommand.Correct(task.id(), OptionalInt.empty(), instruction);
            // A merged task's follow-up becomes a new task: taken all the same.
            case COMPLETED, FAILED -> new TaskCommand.FollowUp(task.id(), instruction, new Origin(messageRef));
            case PLANNING, EXECUTING -> null;
            case REJECTED, CANCELLED -> null;
        };
        if (command == null) {
            return new Applied(task.phase().isActive() ? Outcome.BUSY : closed(tx, additionId), Optional.empty());
        }
        CommandResult result = commands.run(tx, who, command);
        if (result instanceof CommandResult.Refused refused) {
            // The button stays: the task may take it later, e.g. once it has something to follow up.
            return new Applied(Outcome.REFUSED, Optional.of(refused.words()));
        }
        Additions.markUsed(tx, additionId, clock.instant());
        return new Applied(Outcome.DONE, Optional.empty());
```

`PLANNING` and `EXECUTING` are active phases (`Phase.isActive()`), so they give `BUSY`; `REJECTED` and `CANCELLED` close. Every other early return wraps its `Outcome` as `new Applied(outcome, Optional.empty())`. `applied(...)` goes. `commands` is `tasks.commands()`. `apply`'s `chatRef` parameter is now unused: remove it, and adjust `UpdateHandler`'s call.

- [ ] **Step 6: Delete the old commands and results**

Delete them from `TaskService`: `correct`, `correctLatest`, `answer`, `chooseOption`, `askForAnswer`, `followUp`, `refuse`, `refusalPayload`, `notRequester` and `answerRefusal`. Delete the files `CorrectResult.java`, `FollowUpResult.java` and `AnswerResult.java`. Migrate the test callers to `tasks.commands().run(...)`.

Rule cases that only proved who may correct, follow up or answer, and in which order, move into `TaskCommandsTest`'s table if a refusal is not there yet; otherwise delete them. That covers the rest of `anotherMemberOfTheGroupCannot…`, `correctionOfASupersededPlan…`, `correctionFromNonMember…`, `aBlankReplyToAPlanThatMovedOn…` and `anAnswerOutOfOrder…`. Channel tests keep one case per outcome: a refusal's reply, and a success's news.

Remove `plan.youDecide` from `messages_mn.properties` once `grep -rn 'plan.youDecide' src/main` prints nothing.

- [ ] **Step 7: Run the whole suite, then commit**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS.

```bash
git add -A
git commit -m "Corrections, follow-ups, answers and additions through the task commands; Telegram asks for a written answer itself

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01EnUvhys3zP42GsbKq3t4ha"
```

---

### Task 5: Approve, reject, reprioritize, and one way to give a task

The module takes approve, reject and reprioritize. Drafts and the desk give tasks through `Give`, so `create`, `give` and `insertTask`'s last callers in `TaskService` go.

**Files:**
- Modify: `src/main/java/dispatch/core/TaskCommand.java` (add `Approve`, `Reject`, `Reprioritize`)
- Modify: `src/main/java/dispatch/core/TaskCommands.java` (the three commands; `insertTask` becomes `private`)
- Modify: `src/main/java/dispatch/core/TaskService.java`:
  - delete `approve`, `reject`, `changePriority`, `create`, `give`, `Given` and `DESK_ORIGIN`;
  - `choosePriority` gives through `Give`.
- Modify: `src/main/java/dispatch/telegram/UpdateHandler.java`:
  - the approve and reject buttons (`:838-862`);
  - `onPriorityButton` (`:1036-1065`).
- Modify: `src/main/java/dispatch/ui/TasksApi.java` (`approve`, `reject`), `ui/DeskTasksApi.java` (`give`)
- Modify: `src/main/java/dispatch/core/AssistantActions.java` (approve and reject proposals and taps)
- Delete: `src/main/java/dispatch/core/ApproveResult.java`, `RejectResult.java`, `PriorityResult.java`, `CreateResult.java`, `src/test/java/dispatch/core/GiveTest.java`
- Tests: `TaskCommandsTest` (its `given` helper now gives through `Give`; the `failedExecution` helper approves through `Approve`), `UpdateHandlerTest`, `TasksApiTest`, `DeskServerTest`, `AssistantActionsTest`, plus every test calling the deleted methods (`grep -rn 'tasks\.\(approve\|reject\|changePriority\|create\|give\)(\|CreateResult\|ApproveResult\|RejectResult\|PriorityResult\|DESK_ORIGIN' src/test`).

**Interfaces:**
- Consumes: Tasks 1-4.
- Produces:
  - `TaskCommand.Approve(long taskId, int planSeq)`
  - `TaskCommand.Reject(long taskId, int planSeq)`
  - `TaskCommand.Reprioritize(long taskId, Priority priority)`

- [ ] **Step 1: Write the failing tests**

In `TaskCommandsTest`:

```java
    @Test
    void anApprovalQueuesTheExecutionAndTellsTheRequester() {
        long id = awaitingApproval("5", PLAN);
        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Approve(id, 1)));
        assertEquals("EXECUTING", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
        assertEquals(List.of(BOLD.ref()), chatsOf("EXECUTION_QUEUED"));
    }

    @Test
    void aRejectionEndsTheTaskInItsChat() {
        long id = awaitingApproval("5", PLAN);
        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Reject(id, 1)));
        assertEquals(List.of(CHAT), chatsOf("TASK_REJECTED"));
    }

    @Test
    void theSamePriorityAgainChangesNothing() {
        long id = given(BOLD, "5");
        assertEquals(new CommandResult.Unchanged(id), run(BOLD, new TaskCommand.Reprioritize(id, Priority.NORMAL)));
        assertEquals(new CommandResult.Done(id, true), run(BOLD, new TaskCommand.Reprioritize(id, Priority.URGENT)));
        assertEquals("URGENT", row("SELECT priority FROM task WHERE id = ?", id).get("priority"));
    }
```

Add the table's remaining cases:
- `OPEN_QUESTIONS`: Approve on `PLAN_WITH_TWO_QUESTIONS`;
- `STALE_PLAN`: Reject with plan 9;
- `NOT_REQUESTER`: Reprioritize by Ali;
- `WRONG_PHASE`: Reprioritize a completed task.

The `given` helper becomes:

```java
    private long given(Requester who, String messageId) {
        return assertInstanceOf(CommandResult.Created.class, run(who,
                new TaskCommand.Give("alm", "Fix login timeout", Priority.NORMAL, new Origin(who.ref() + "/" + messageId)))).taskId();
    }
```

In `failedExecution` and `awaitingApproval`, approve with `run(BOLD, new TaskCommand.Approve(id, plan.seq()))`.

Fold `GiveTest`'s two cases into `TaskCommandsTest` (a page's task tells the chat, and the desk's refusals), then delete `GiveTest.java`.

In `UpdateHandlerTest`:
- approve and reject taps show `callback.approved`/`callback.rejected`, or a refusal's words, as their notice;
- a priority tap to the same priority shows `callback.priorityUnchanged` and does not redraw the status.

Run: `./mvnw -q test -Dtest=TaskCommandsTest`
Expected: COMPILATION ERROR.

- [ ] **Step 2: The commands**

In `TaskCommand`:

```java
    /** Approves plan {@code planSeq} and queues its execution (ADR 0006). */
    record Approve(long taskId, int planSeq) implements TaskCommand {
    }

    record Reject(long taskId, int planSeq) implements TaskCommand {
    }

    /** Moves an unfinished task up or down the queue; a running agent is not affected (ADR 0012). */
    record Reprioritize(long taskId, Priority priority) implements TaskCommand {
    }
```

In `TaskCommands.check`:

```java
            case TaskCommand.Approve approve -> planRefusal(tx, who, command, approve.taskId(), approve.planSeq(), TaskAccess.Action.APPROVE);
            case TaskCommand.Reject reject -> planRefusal(tx, who, command, reject.taskId(), reject.planSeq(), TaskAccess.Action.REJECT);
            case TaskCommand.Reprioritize reprioritize -> refusal(tx, who, command, reprioritize.taskId(), TaskAccess.Action.PRIORITY);
```

```java
    /** For a decision that names the plan it was shown: a plan a newer one replaced is stale (ADR 0027). */
    private Optional<CommandResult.Refused> planRefusal(Tx tx, Requester who, TaskCommand command, long taskId, int planSeq,
                                                        TaskAccess.Action action) {
        TaskAccess.Verdict verdict = access.of(tx, who.ref(), taskId);
        return verdict.refusal(action, planSeq).map(refusal -> refused(command, refusal, verdict.task()));
    }
```

In `carryOut`, and in `taskId(...)`:

```java
            case TaskCommand.Approve approve -> approve(tx, who, task(tx, approve.taskId()), approve.planSeq());
            case TaskCommand.Reject reject -> reject(tx, who, task(tx, reject.taskId()), reject.planSeq());
            case TaskCommand.Reprioritize reprioritize -> reprioritize(tx, who, task(tx, reprioritize.taskId()), reprioritize.priority());
```

The bodies are `TaskService.approve` (`:552-564`), `reject` (`:745-754`) and `changePriority` (`:769-785`), from their first write on:

```java
    private CommandResult approve(Tx tx, Requester who, Task task, int planSeq) {
        Instant now = clock.instant();
        changePhase(tx, task, Phase.AWAITING_APPROVAL, Phase.EXECUTING, now);
        // The run carries the plan it implements, so what was approved stays on record.
        Runs.insert(tx, new Runs.NewRun(task.id(), Runs.nextSeq(tx, task.id()), RunKind.EXECUTE, RunCause.APPROVAL, task.planJson(), who), now);
        Events.record(tx, task.id(), null, who.ref(), Phase.AWAITING_APPROVAL, Phase.EXECUTING, "approved plan " + planSeq, now);
        Outbox.enqueueForRequester(tx, task, OutboxKind.EXECUTION_QUEUED, Json.object().put("taskId", task.id()).put("by", who.name()), now);
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(wakeScheduler);
        logTransition(tx, task.id(), Phase.AWAITING_APPROVAL, Phase.EXECUTING, who.ref());
        return new CommandResult.Done(task.id(), isRequester(who, task));
    }

    private CommandResult reject(Tx tx, Requester who, Task task, int planSeq) {
        Instant now = clock.instant();
        changePhase(tx, task, Phase.AWAITING_APPROVAL, Phase.REJECTED, now);
        Events.record(tx, task.id(), null, who.ref(), Phase.AWAITING_APPROVAL, Phase.REJECTED, "rejected plan " + planSeq, now);
        Outbox.enqueue(tx, task.id(), OutboxKind.TASK_REJECTED, task.chatRef(), task.groupOriginRef(),
                Json.object().put("taskId", task.id()).put("by", who.name()), now);
        GroupAcks.react(tx, task, GroupReaction.ENDED, now);
        tx.afterCommit(wakeOutbox);
        logTransition(tx, task.id(), Phase.AWAITING_APPROVAL, Phase.REJECTED, who.ref());
        return new CommandResult.Done(task.id(), isRequester(who, task));
    }

    private CommandResult reprioritize(Tx tx, Requester who, Task task, Priority priority) {
        if (task.priority() == priority) {
            return new CommandResult.Unchanged(task.id());
        }
        Instant now = clock.instant();
        if (!Tasks.changePriority(tx, task.id(), priority, now)) {
            throw new IllegalStateException("task #" + task.id() + " was allowed a new priority, yet it finished");
        }
        Events.record(tx, task.id(), null, who.ref(), task.phase(), task.phase(), "priority " + task.priority() + " -> " + priority, now);
        tx.afterCommit(() -> Log.info("task.priority_changed", "task", task.id(), "from", task.priority(), "to", priority, "actor", who.ref()));
        return new CommandResult.Done(task.id(), isRequester(who, task));
    }
```

Keep the original events' wording where tests read it: check `grep -rn 'approved plan\|"rejected"' src/test`, and match what they expect.

- [ ] **Step 3: One way to give a task**

`TaskService.choosePriority` (`:198-218`) gives through `Give`, after its own draft checks:

```java
        CommandResult given = commands.run(tx, who, new TaskCommand.Give(project.get().name(), draft.description(), priority,
                new Origin(draft.originRef())));
        if (!(given instanceof CommandResult.Created created)) {
            // The project stopped taking tasks, or the member left its groups, since the prompt offered it.
            return DraftChoice.PROJECT_UNAVAILABLE;
        }
        Drafts.created(tx, draftId, created.taskId(), clock.instant());
        Attachments.giveToTask(tx, draftId, created.taskId());
        return DraftChoice.CREATED;
```

Delete `TaskService.create`, `give`, `Given`, `DESK_ORIGIN` and `memberProject`. `TaskCommands.insertTask` becomes `private`.

`DeskTasksApi.give` becomes:

```java
    ObjectNode give(Caller caller, JsonNode body) {
        Priority priority = priority(body.path("priority").asText("NORMAL"));
        TaskCommand.Give give = new TaskCommand.Give(body.path("project").asText(""), body.path("text").asText(""), priority, Origin.page());
        CommandResult result = db.transactionReturning(tx -> tasks.commands().run(tx, new Requester(caller.ref(), caller.name()), give));
        if (result instanceof CommandResult.Refused refused) {
            throw TasksApi.refused(refused);
        }
        return Json.object().put("taskId", ((CommandResult.Created) result).taskId());
    }
```

`Give` returns only `Created` or `Refused`, so the cast holds. Name that in a comment on the cast line.

- [ ] **Step 4: The channels**

- **Telegram approve and reject** (`:843-862`):

  ```java
        TaskCommand decision = parts[0].equals("approve") ? new TaskCommand.Approve(taskId.get(), seq) : new TaskCommand.Reject(taskId.get(), seq);
        notice(tx, callbackId, commands.run(tx, who, decision), parts[0].equals("approve") ? "callback.approved" : "callback.rejected");
  ```
- **Telegram priority** (`:1045-1056`):

  ```java
        CommandResult result = commands.run(tx, who, new TaskCommand.Reprioritize(taskId, priority));
        notice(tx, callbackId, result, result instanceof CommandResult.Unchanged ? "callback.priorityUnchanged" : "callback.priorityChanged");
        if (!(result instanceof CommandResult.Done)) {
            return;
        }
  ```
- **The pages:**

  ```java
    ObjectNode approve(Caller caller, JsonNode body) {
        long taskId = taskId(body);
        int planSeq = number(body, "planSeq");
        return answered(db.transactionReturning(tx -> commands.run(tx, requester(caller), new TaskCommand.Approve(taskId, planSeq))), "APPROVED");
    }
  ```

  and `reject` with `TaskCommand.Reject` and `"REJECTED"`. `stale()`, `notFound(...)` and `notMember()` stay only as far as `timeline` and `ownTask` still use them; delete what is left unused.
- **The assistant:**
  - `check`'s `approve`/`reject` → `offered(tx, who, new TaskCommand.Approve(taskId, verdict.planSeq()), payload.put("planSeq", verdict.planSeq()))`, and the same with `Reject`;
  - `carryOut`'s arms → `tapped(commands.run(tx, who, new TaskCommand.Approve(taskId, planSeq)))`, and the same with `Reject`;
  - `decision(...)` and `reason(...)` go.

- [ ] **Step 5: Delete the rest, run the whole suite, then commit**

Delete `ApproveResult.java`, `RejectResult.java`, `PriorityResult.java` and `CreateResult.java`. Migrate the test callers: a `create` becomes a `Give` with the same origin, and read `Created.taskId()` instead of querying by origin.

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS.

```bash
git add -A
git commit -m "Approve, reject and reprioritize through the task commands; drafts and the desk give a task one way

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01EnUvhys3zP42GsbKq3t4ha"
```

---

### Task 6: The chat's views are Telegram's, the old reply kinds retire, and the docs say so

**Files:**
- Modify: `src/main/java/dispatch/telegram/UpdateHandler.java` (`/status`, `/history`, a timeline, `/stats` post their own replies)
- Modify: `src/main/java/dispatch/core/TaskService.java` (delete `status`, `history`, `timeline`, `stats`; the payload builders stay)
- Create: `src/main/resources/db/027-retired-reply-kinds.sql`; Modify: `store/Database.java`
- Modify: `src/main/java/dispatch/store/Row.java`, `store/Outbox.java` (`findSent` ignores rows of a kind this version no longer has)
- Modify: `src/main/java/dispatch/domain/OutboxKind.java`, `telegram/Renderer.java`, `src/test/java/dispatch/telegram/RendererTest.java` (remove `CANCEL_REFUSED`, `RETRY_REFUSED`, `FOLLOW_UP_REFUSED`, `CORRECTION_REFUSED`, `UNKNOWN_PROJECT`, `PROJECT_UNAVAILABLE`)
- Modify: `src/main/resources/messages_mn.properties`, `texts_en.properties`, `texts_mn.properties` (remove keys nothing names any more)
- Modify: `docs/adr/0031-…md` (`Status: accepted`), `docs/ARCHITECTURE.md` (the `core` and `telegram` rows of Components, and `Domain model` if it lists outbox kinds)

**Interfaces:**
- Consumes: Tasks 1-5.
- Produces: `Row.enumOrNull(String column, Class<E> type)`.

- [ ] **Step 1: Write the failing tests**

In `src/test/java/dispatch/store/DatabaseTest.java`, or wherever migrations are tested (find it with `grep -rln 'migrate()' src/test/java/dispatch/store`):

```java
    @Test
    void pendingRepliesOfRetiredKindsFailAtUpgradeAndSentOnesAreNoLongerFound() {
        // A state file one schema before the retirement, with a pending and a sent reply of a retired kind.
        …open at user_version 26, insert ('CORRECTION_REFUSED', 'PENDING') and ('CANCEL_REFUSED', 'SENT', sent_ref 'telegram:100/77')…
        db.migrate();
        assertEquals("FAILED", status of the pending one);
        assertEquals(Optional.empty(), db.transactionReturning(tx -> Outbox.findSent(tx, "telegram:100/77")),
                "a reply to a message of a kind this version no longer has is a reply to an unknown message");
    }
```

Build the version-26 state from what the existing migration tests do; read them first. If they have no way to stop at a version, insert the rows after `migrate()` with `PRAGMA user_version = 26`, then run `migrate()` again.

`UpdateHandlerTest`'s `/status`, `/history`, `/history N` and `/stats` tests stay as they are: the rows the chat gets do not change.

Run: `./mvnw -q test -Dtest=DatabaseTest`
Expected: FAIL.

- [ ] **Step 2: The chat's views move to Telegram**

In `UpdateHandler`'s command switch (`:362-365`, `:398-399`):

```java
            case "status" -> enqueue(tx, OutboxKind.STATUS, chatRef, origin, tasks.statusPayload(tx, viewer));
            case "history" -> taskId(command.args()).ifPresentOrElse(
                    id -> tasks.timelinePayload(tx, viewer, id).ifPresentOrElse(
                            payload -> enqueue(tx, OutboxKind.TASK_TIMELINE, chatRef, origin, payload),
                            () -> enqueue(tx, OutboxKind.TASK_NOT_FOUND, chatRef, origin, Json.object().put("taskId", id))),
                    () -> enqueue(tx, OutboxKind.HISTORY, chatRef, origin, tasks.historyPayload(tx, viewer)));
            case "stats" -> stats(tx, privateChat ? who.ref() : null,
                    privateChat ? groups.groupsOfMember(who.ref()) : groups.groupOfChat(chatRef).map(List::of).orElseThrow(), origin, chatRef);
```

```java
    /** This month's statistics: the viewer's own in a private chat, the group's in a group chat (ADR 0012). */
    private void stats(Tx tx, String viewerRef, List<String> groupNames, String origin, String chatRef) {
        String view = viewerRef != null ? "me" : "group:" + groupNames.getFirst();
        ObjectNode payload = tasks.statsPayload(tx, viewerRef, groupNames, view, "month")
                .orElseThrow(() -> new IllegalStateException("default statistics view " + view + " refused"));
        enqueue(tx, OutboxKind.STATS, chatRef, origin, payload);
    }
```

Delete `TaskService.status`, `history`, `timeline` and `stats`. The payload builders and `notAllowed` stay: drafts still answer in the chat themselves (ADR 0031). Migrate any test calling the deleted methods.

- [ ] **Step 3: Retire the old reply kinds**

`src/main/resources/db/027-retired-reply-kinds.sql`:

```sql
-- ADR 0031: a task command's refusal is one REFUSED reply, worded where it was refused, and giving a task refuses in its
-- words too. Replies of the old kinds still waiting to be sent can no longer be rendered.
UPDATE outbox SET status = 'FAILED', last_error = 'kind ' || kind || ' was removed in schema version 27'
WHERE status = 'PENDING'
  AND kind IN ('CANCEL_REFUSED', 'RETRY_REFUSED', 'FOLLOW_UP_REFUSED', 'CORRECTION_REFUSED', 'UNKNOWN_PROJECT', 'PROJECT_UNAVAILABLE');
```

Add it to `Database.MIGRATIONS`.

In `Row`:

```java
    /** The constant a column names, or null when it names none this version has, such as an outbox kind since retired. */
    public <E extends Enum<E>> E enumOrNull(String column, Class<E> type) throws SQLException {
        String value = resultSet.getString(column);
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
```

In `Outbox.findSent`, read the kind with `enumOrNull`, and return empty for a row whose kind is null. A reply to such a message is then a reply to an unknown message, as for any message the bot does not know:

```java
    public static Optional<Sent> findSent(Tx tx, String sentRef) {
        return tx.one("SELECT id, task_id, kind, payload FROM outbox WHERE sent_ref = ?",
                row -> new Sent(row.longValue("id"), row.longOrNull("task_id"), row.enumOrNull("kind", OutboxKind.class),
                        row.string("payload")),
                sentRef).filter(sent -> sent.kind() != null);
    }
```

Then remove the six constants from `OutboxKind`. Remove their `Renderer` cases and `RendererTest` samples. From `messages_mn.properties`, remove the keys only they named: check each with `grep -rn '"<key>"' src/main` before removing it. From `texts_en.properties` and `texts_mn.properties`, remove every `refusal.*` key that `grep -rn 'refusal\.<name>"' src/main` no longer finds.

- [ ] **Step 4: The docs**

- **`docs/adr/0031-…md`:** set `Status: accepted`.
- **`docs/ARCHITECTURE.md`, Components table:**
  - The `core` row's first sentences become: "`TaskCommands` (ADR 0031): the task commands (give, approve, reject, correct, answer, reprioritize, cancel, retry, follow up). Each asks `TaskAccess`, changes the task, writes its news and returns Done, Created, Unchanged or Refused with the refusal's words; the channel that ran it writes the reply. `TaskService`: drafts (draft, split, choose, discard, expire) and the views channels show (status, history, timeline, stats payloads)." Keep the rest of the row: `TaskAccess`, `Scheduler`, and so on.
  - The `telegram` row: `UpdateHandler` also "turns a command's result into its reply: a refusal's words under the message or as a button's notice, and the chat's own views".
- **The `outbox` row in Domain model:** if it lists kinds, add `edit_of` (`the outbox row whose message this one redraws once it is sent, ADR 0031`).

- [ ] **Step 5: Run the whole suite and the UI checks, then commit**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS.

Run: `cd ui && npm run typecheck && npm test`
Expected: PASS. No page changed, and this proves it.

```bash
git add -A
git commit -m "The chat's views post from Telegram, the old reply kinds retire, and ADR 0031 is accepted

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01EnUvhys3zP42GsbKq3t4ha"
```

---

## Self-review (done while writing)

**Spec coverage:** every ADR 0031 point has a task.

| ADR 0031 point | Task |
|---|---|
| News in commands; replies in channels | 1-5 |
| Confirmations are news; an admin's cancel reaches the requester | 1, 3 |
| Refusals only as replies | 2, 4 |
| One wording, and a Telegram hint | 1, 3, 4 |
| The scope, with create and give merged | 5 |
| Views and prompt to Telegram | 4, 6 |
| Drafts excluded | nothing touches their replies |
| Tests replaced | every task |
| Redraw by row | 3 |
| Merged follow-up refused like giving | 3, 4 |
| Merges untouched | only its import of `Refusal` changes |

**Types:**
- `CommandResult.Done(long, boolean)`, `Created(long)`, `Unchanged(long)`, `Refused(Refusal, Text)`.
- `TaskCommand.Cancel/Retry` (Task 1), `Correct/FollowUp/Answer/Give` (Task 3), `Approve/Reject/Reprioritize` (Task 5).
- `Origin(String)`.
- These match across tasks, and `check`, `carryOut` and `taskId` gain one arm per record in the task that adds it.

**Placeholders:** the channel-test steps name the exact rows and payloads to assert, and point to the existing fixtures to copy. The large test classes' helpers differ; implementers must read them rather than guess.
