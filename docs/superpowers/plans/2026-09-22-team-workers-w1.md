# W-1 Privacy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Members see only the headline of each other's tasks, and only a task's requester acts on it (an admin may also cancel).

**Architecture:** The rules live in `TaskService`, which already builds every payload the bot sends and decides every action; the Renderer learns to draw the thinner payloads. A private message that Telegram refuses no longer falls back into the group with its content, only with a content-free notice.

**Tech Stack:** Java 25, JUnit 6, SQLite via the existing `Database`/`Tx`; Telegram texts in `src/main/resources/messages_mn.properties`.

**Spec:** `docs/superpowers/specs/2026-09-22-team-workers-design.md` (milestone W-1).

## Global Constraints

- A task's **headline** is: who gave it, project, title, priority, state (phase, run kind, failure reason category, age), PR link. Everything else — the plan, corrections, the agent's latest action and step count, the timeline's runs, cost, model — is the requester's only.
- The **viewer** is the member asking in their private chat (`who.ref()`, e.g. `telegram:100`); in a group chat there is no viewer (`null`), so a group chat sees headlines only.
- Only the requester approves, corrects, rejects, reprioritizes, follows up or retries. The requester **or an admin** (`Groups.isAdmin`) cancels.
- The rules apply to every task, with no mode switch: a personal bot has one member, so nothing changes there (ruling, see below).
- Refusals keep today's outbox kinds and add `"reason": "requester"` plus `"requester": <name>` to the payload; no new enum constants.
- Another group's task still answers as not found (unchanged), except that an admin may cancel any task.
- Bot copy is Mongolian, in `messages_mn.properties`, in the style of `task.correctionNotRequester`.
- Commit messages end with `Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z`.
- Verify with `./mvnw -q -B verify` (all tests); the UI is untouched.

Rulings made while planning (each with its cost if wrong):

1. No team/personal switch: the rules apply everywhere, since a personal bot's only member is always the requester. Cost: a hand-edited personal config with several members also gets the privacy rules — which is what they would want.
2. A private message refused by Telegram goes to the group as a notice without its content. Cost: a requester who blocked the bot loses that plan's text in Telegram until they re-open the bot and ask for a correction or retry.
3. Group statistics keep counts, time to PR and first-time approval, but drop cost; the people view shows cost only on the viewer's own row. Cost: a team lead can no longer see others' spend in Telegram (the spec's "Headline + cost" option was not chosen).
4. The privacy decision is ADR 0020 (it amends ADR 0011); the workers ADR becomes 0021.

## File Structure

- Modify `src/main/java/dispatch/core/TaskService.java` — the rules: `cancel`, `retry`, `followUp`, `statusPayload`, `history`, `timeline`, `statsPayload`.
- Modify `src/main/java/dispatch/core/Statistics.java` — `people(...)` takes the viewer.
- Modify `src/main/java/dispatch/telegram/UpdateHandler.java` — pass the viewer to `history` and `timeline`.
- Modify `src/main/java/dispatch/telegram/Renderer.java` — the new refusal reasons, headline timeline, cost-less lines, the content-free fallback.
- Modify `src/main/resources/messages_mn.properties` — new keys.
- Tests: `src/test/java/dispatch/core/{TaskLifecycleTest,StatusAndHistoryTest,StatsTest}.java`, `src/test/java/dispatch/telegram/{RendererTest,UpdateHandlerTest}.java`, and any other test that now fails because a non-requester acted (fix the test's actor, never the rule).
- Docs: `README.md`, `SECURITY.md`, `docs/ARCHITECTURE.md`, new `docs/adr/0020-members-see-only-the-headline-of-each-others-tasks.md`, the two specs.

---

### Task 1: Only the requester acts on a task; an admin may also cancel

**Files:**
- Modify: `src/main/java/dispatch/core/TaskService.java` (`cancel` ~564, `retry` ~599, `followUp` ~654, `visibleTask` ~686)
- Modify: `src/main/java/dispatch/telegram/Renderer.java` (`CANCEL_REFUSED`, `RETRY_REFUSED`, `FOLLOW_UP_REFUSED` cases ~108-116)
- Modify: `src/main/resources/messages_mn.properties`
- Test: `src/test/java/dispatch/core/TaskLifecycleTest.java`, `src/test/java/dispatch/telegram/RendererTest.java`

**Interfaces:**
- Consumes: `Groups.isAdmin(String ref)`, `Groups(Config.Telegram)` (admins by Telegram id).
- Produces: `CANCEL_REFUSED`, `RETRY_REFUSED`, `FOLLOW_UP_REFUSED` payloads may carry `"reason": "requester"` and `"requester": <name>`; message keys `task.cancelNotRequester`, `task.retryNotRequester`, `task.followUpNotRequester`.

- [ ] **Step 1: Write the failing tests** in `TaskLifecycleTest`.

Change the existing cancel tests whose actor is `ALI` on `BOLD`'s task (`cancellingQueuedTaskCancelsItsRun`, `cancelFromAPrivateChatIsAnnouncedInTheGroupAndAnsweredThere`, `cancellingRunningTaskStopsTheActiveRunOnlyAfterCommit`, `cancelIsRefusedForFinishedAndUnknownTasksAndNonMembers`, `runFinishingAfterCancelLeavesTaskCancelled`, and any other) so that `BOLD` cancels; adjust the `"by"` assertion to `"Bold"` and the private chat refs from `telegram:200` to `telegram:100`. Then add:

```java
    @Test
    void anotherMemberOfTheGroupCannotCancelRetryOrFollowUpSomeoneElsesTask() {
        long active = create(BOLD, "alm", "Fix login timeout", "90");
        long failed = failedExecution("91");

        assertEquals(CancelResult.REFUSED, db.transactionReturning(tx -> tasks.cancel(tx, ALI, active, "telegram:200/1", "telegram:200")));
        assertEquals(RetryResult.REFUSED, db.transactionReturning(tx -> tasks.retry(tx, ALI, failed, "telegram:200/2", "telegram:200")));
        assertEquals(FollowUpResult.REFUSED,
                db.transactionReturning(tx -> tasks.followUp(tx, ALI, failed, "Also this", "telegram:200/3", "telegram:200")));

        assertEquals("PLANNING", row("SELECT phase FROM task WHERE id = ?", active).get("phase"));
        assertEquals("FAILED", row("SELECT phase FROM task WHERE id = ?", failed).get("phase"));
        for (String[] refusal : List.of(new String[] {"CANCEL_REFUSED", "telegram:200/1"}, new String[] {"RETRY_REFUSED", "telegram:200/2"},
                new String[] {"FOLLOW_UP_REFUSED", "telegram:200/3"})) {
            JsonNode payload = Json.read(row("SELECT payload FROM outbox WHERE kind = ? AND reply_to_ref = ?", refusal[0], refusal[1])
                    .get("payload"));
            assertEquals("requester", payload.get("reason").asText(), refusal[0]);
            assertEquals("Bold", payload.get("requester").asText(), refusal[0]);
        }
    }

    @Test
    void anAdminCanCancelAnyTaskEvenOutsideTheirGroups() {
        Groups withAdmin = new Groups(new Config.Telegram(List.of(300L), List.of(
                new Config.Group("backend", -100L, List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")),
                        List.of("autoland-management", "crm")),
                new Config.Group("mobile", -300L, List.of(new Config.Member(300, "Sara")), List.of("life")))));
        tasks = new TaskService(withAdmin, projects, activeRuns, clock, schedulerWakes::incrementAndGet, outboxWakes::incrementAndGet);
        long id = create(BOLD, "alm", "Fix login timeout", "92");
        Requester sara = new Requester("telegram:300", "Sara");

        assertEquals(CancelResult.CANCELLED, db.transactionReturning(tx -> tasks.cancel(tx, sara, id, "telegram:300/1", "telegram:300")));
        assertEquals("CANCELLED", row("SELECT phase FROM task WHERE id = ?", id).get("phase"));
    }
```

If `projects` is a local variable in `setUp`, make it a field. If there is no `failedExecution(String messageId)` helper, add one that creates a task for `BOLD` on `alm`, claims and succeeds its plan, approves it, claims the execution and calls `transitions.failed(id, 2, FailureReason.AGENT, "boom", result)` — reuse the file's existing helpers (`awaitingApproval`, `claim`, the `AgentResult` builder) rather than writing new ones. Keep `cancelOfATaskOutsideTheMembersGroupsLooksLikeAnUnknownTask` as it is: it must still pass.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -B test -Dtest=TaskLifecycleTest`
Expected: the two new tests FAIL (Ali's cancel is `CANCELLED`, Sara's is `NOT_FOUND`).

- [ ] **Step 3: Implement the rules** in `TaskService`.

In `cancel`, replace the lookup and add the requester check before the phase check:

```java
        Optional<Task> found = groups.isAdmin(who.ref()) ? Tasks.find(tx, taskId) : visibleTask(tx, who, taskId);
        if (found.isEmpty()) {
            enqueue(tx, null, OutboxKind.TASK_NOT_FOUND, chatRef, originRef, Json.object().put("taskId", taskId), now);
            return CancelResult.NOT_FOUND;
        }
        Task task = found.get();
        if (!isRequester(task, who) && !groups.isAdmin(who.ref())) {
            enqueue(tx, taskId, OutboxKind.CANCEL_REFUSED, chatRef, originRef, notRequester(task), now);
            return CancelResult.REFUSED;
        }
```

In `retry`, right after `Task task = found.get();`:

```java
        if (!isRequester(task, who)) {
            enqueue(tx, taskId, OutboxKind.RETRY_REFUSED, chatRef, originRef, notRequester(task), now);
            return RetryResult.REFUSED;
        }
```

In `followUp`, right after `Task task = found.get();`:

```java
        if (!isRequester(task, who)) {
            enqueue(tx, taskId, OutboxKind.FOLLOW_UP_REFUSED, chatRef, originRef, notRequester(task), now);
            return FollowUpResult.REFUSED;
        }
```

Add the helpers next to `visibleTask`:

```java
    private static boolean isRequester(Task task, Requester who) {
        return task.requester().ref().equals(who.ref());
    }

    /** Why a member may see a task but not act on it: it is someone else's (ADR 0020). */
    private static ObjectNode notRequester(Task task) {
        return Json.object().put("taskId", task.id()).put("reason", "requester").put("requester", task.requester().name());
    }
```

Update the Javadoc of `cancel` ("Cancels the member's own task; an admin may cancel any task…"), `retry` ("Only the requester may retry it; another group's task is answered as not found.") and `followUp` ("The requester's reply…"), and use the existing `isRequester` in `approve`, `correct`, `reject` and `changePriority` only if it makes those lines simpler — do not change their behaviour.

- [ ] **Step 4: Render the refusals.** In `Renderer.render`:

```java
            case CANCEL_REFUSED -> plain(payload.path("reason").asText().equals("requester")
                    ? format("task.cancelNotRequester", taskId(payload), escape(payload.path("requester").asText()))
                    : format("task.cancelRefused", taskId(payload), text("phase." + payload.path("phase").asText())));
            case RETRY_REFUSED -> plain(payload.path("reason").asText().equals("requester")
                    ? format("task.retryNotRequester", taskId(payload), escape(payload.path("requester").asText()))
                    : format("task.retryRefused", taskId(payload), text("phase." + payload.path("phase").asText())));
            case FOLLOW_UP_REFUSED -> plain(switch (payload.path("reason").asText()) {
                case "notExecuted" -> format("task.followUpNotExecuted", taskId(payload));
                case "requester" -> format("task.followUpNotRequester", taskId(payload), escape(payload.path("requester").asText()));
                default -> format("task.followUpRefused", taskId(payload), text("phase." + payload.path("phase").asText()));
            });
```

Add to `messages_mn.properties`, next to `task.correctionNotRequester`:

```properties
task.cancelNotRequester=#{0}: даалгаврыг зөвхөн өгсөн {1} эсвэл админ цуцална.
task.retryNotRequester=#{0}: даалгаврыг зөвхөн өгсөн {1} дахин оролдоно.
task.followUpNotRequester=#{0}: даалгаварт зөвхөн өгсөн {1} нэмэлт хүсэлт өгнө.
```

Add one `RendererTest` case that renders each of the three kinds with `Json.object().put("taskId", 7).put("reason", "requester").put("requester", "Bold")` and asserts the HTML contains `#7` and `Bold`.

- [ ] **Step 5: Run the whole suite and fix tests whose actor was a non-requester**

Run: `./mvnw -q -B verify`
Expected: PASS. Where an existing test (e.g. in `UpdateHandlerTest`, `RunExecutorTest`, `PersonalGroupTest`) fails because another member cancelled, retried or followed up, change that test's actor to the requester; if the test's point was that another member may act, rewrite it to assert the refusal instead. Never weaken the rule.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dispatch/core/TaskService.java src/main/java/dispatch/telegram/Renderer.java \
  src/main/resources/messages_mn.properties src/test/java
git commit -m "Let only the requester act on a task, and an admin cancel it

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 2: Others' tasks show only their headline in /status, /history and /stats

**Files:**
- Modify: `src/main/java/dispatch/core/TaskService.java` (`statusPayload` ~702, `history` ~737, `timeline` ~753, `statsPayload` ~801)
- Modify: `src/main/java/dispatch/core/Statistics.java` (`people`)
- Modify: `src/main/java/dispatch/telegram/UpdateHandler.java` (~180-182)
- Modify: `src/main/java/dispatch/telegram/Renderer.java` (`history`, `timeline`, `stats`)
- Modify: `src/main/resources/messages_mn.properties`
- Test: `src/test/java/dispatch/core/StatusAndHistoryTest.java`, `src/test/java/dispatch/core/StatsTest.java`, `src/test/java/dispatch/telegram/RendererTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces (new signatures; update every caller):
  - `public void history(Tx tx, Set<String> visibleProjects, String viewerRef, String originRef, String chatRef)`
  - `public void timeline(Tx tx, Set<String> visibleProjects, String viewerRef, long taskId, String originRef, String chatRef)`
  - `static ArrayNode Statistics.people(List<Task> tasks, List<Runs.Cost> runs, String viewerRef)`
  - `TASK_TIMELINE` payload gains `"headline": true` for a task that is not the viewer's, and then has no `runs` and a null `costUsd`.
  - Message keys `history.lineNoCost`, `stats.pullRequestsNoCost`, `stats.personNoCost`.

- [ ] **Step 1: Write the failing tests.**

In `StatusAndHistoryTest`, the existing `statusShowsRunningWorkWithTheAgentsLatestActionThenQueuedThenAwaitingApproval` asks from the group chat; change its `tasks.status(...)` call to Bold's private chat (`tasks.status(tx, LIFE, BOLD.ref(), "telegram:100/99", "telegram:100")`, and the `reply_to_ref` assertion to `"telegram:100/99"`), and replace its `"no priority buttons in a group chat"` assertion with `assertEquals(3, payload.get("mine").size())`. Update the other callers of `history` and `timeline` in this file to the new signatures, passing `BOLD.ref()` where the test reads cost or runs. Then add (Bold and Ali must share the `life` project for this, so add Ali as a second member of the `mobile` group in `setUp` and check no existing assertion depends on Ali not being there):

```java
    @Test
    void anotherMembersTaskShowsOnlyItsHeadline() {
        long alis = createFor(ALI, "life", "Ali's work", "90");
        claim();
        activeRuns.register(alis, 1).attach(new ActivityHandle(new AgentActivity(3, "Bash: cat secrets")));
        long done = completedFor(ALI, "Ali's finished work", "91");

        db.transaction(tx -> tasks.status(tx, LIFE, BOLD.ref(), "telegram:100/92", "telegram:100"));
        db.transaction(tx -> tasks.history(tx, LIFE, BOLD.ref(), "telegram:100/93", "telegram:100"));
        db.transaction(tx -> tasks.timeline(tx, LIFE, BOLD.ref(), done, "telegram:100/94", "telegram:100"));
        db.transaction(tx -> tasks.status(tx, LIFE, null, CHAT + "/95", CHAT));

        JsonNode running = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = ?", "telegram:100/92").get("payload"))
                .get("running").get(0);
        assertEquals(alis, running.get("taskId").asLong());
        assertEquals("Ali's work", running.get("title").asText());
        assertTrue(running.path("lastAction").isMissingNode(), "the agent's actions are Ali's");
        assertTrue(running.path("steps").isMissingNode());
        JsonNode listed = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = ?", "telegram:100/93").get("payload"))
                .get("tasks").get(0);
        assertEquals("https://github.com/acme/life/pull/1", listed.get("prUrl").asText(), "the PR link is part of the headline");
        assertTrue(listed.get("costUsd").isNull(), "cost is Ali's");
        JsonNode timeline = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = ?", "telegram:100/94").get("payload"));
        assertTrue(timeline.get("headline").asBoolean());
        assertEquals("COMPLETED", timeline.get("phase").asText());
        assertEquals("Ali", timeline.get("requester").asText());
        assertTrue(timeline.path("runs").isMissingNode(), "corrections and runs are Ali's");
        assertTrue(timeline.get("costUsd").isNull());
        JsonNode group = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = ?", CHAT + "/95").get("payload"));
        assertTrue(group.get("running").get(0).path("lastAction").isMissingNode(), "a group chat sees headlines only");
    }

    @Test
    void theRequesterStillSeesTheirOwnTaskInFull() {
        long mine = create("My work", "96");
        claim();
        activeRuns.register(mine, 1).attach(new ActivityHandle(new AgentActivity(2, "Read README.md")));

        db.transaction(tx -> tasks.status(tx, LIFE, BOLD.ref(), "telegram:100/97", "telegram:100"));
        db.transaction(tx -> tasks.timeline(tx, LIFE, BOLD.ref(), mine, "telegram:100/98", "telegram:100"));

        JsonNode running = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = ?", "telegram:100/97").get("payload"))
                .get("running").get(0);
        assertEquals("Read README.md", running.get("lastAction").asText());
        JsonNode timeline = Json.read(row("SELECT payload FROM outbox WHERE reply_to_ref = ?", "telegram:100/98").get("payload"));
        assertTrue(timeline.path("headline").isMissingNode() || !timeline.get("headline").asBoolean());
        assertEquals(1, timeline.get("runs").size());
    }
```

Add `completedFor(Requester who, String text, String messageId)` by generalizing the existing `completed(...)` helper (which then calls `completedFor(BOLD, …)`), approving as `who`.

In `StatsTest`, change `peopleViewListsEveryRequesterOfTheViewersGroupsMostActiveFirst` to assert `people.get(0).get("costUsd").isNull()` (Sara's cost, seen by Bold), and add:

```java
    @Test
    void costIsShownOnlyForTheViewersOwnTasks() {
        delivered(ALI, "alm", Duration.ofMinutes(5), false, "0.20", "0.30");
        delivered(BOLD, "alm", Duration.ofMinutes(5), false, "0.10", "0.10");

        JsonNode group = payload(BOLD.ref(), BOLDS_GROUPS, "group:backend", "all");
        JsonNode people = payload(BOLD.ref(), BOLDS_GROUPS, "people", "all").get("people");
        JsonNode mine = payload(BOLD.ref(), BOLDS_GROUPS, "me", "all");

        assertEquals(2, group.get("summary").get("tasks").asInt());
        assertTrue(group.get("summary").get("costUsd").isNull(), "the group's total includes Ali's cost");
        assertTrue(group.get("summary").get("averageCostUsd").isNull());
        for (JsonNode person : people) {
            assertEquals(person.get("name").asText().equals("Bold"), !person.get("costUsd").isNull(), person.toString());
        }
        assertEquals("0.20", mine.get("summary").get("costUsd").asText());
    }
```

In `RendererTest`, add cases: a `TASK_TIMELINE` payload with `"headline": true` renders the header and outcome but no `timeline.total` text; a `HISTORY` task with `costUsd` null renders no `—` cost; `STATS` with a null `summary.costUsd` and a person with null `costUsd` render without a cost. Build the expected strings from `renderer.text(...)`/the properties values, as the file's existing tests do.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -B test -Dtest='StatusAndHistoryTest,StatsTest,RendererTest'`
Expected: compile errors for the new signatures, then — once the signatures exist — FAIL on `lastAction`, `costUsd`, `headline`.

- [ ] **Step 3: Implement in `TaskService`.**

Add next to `visibleTask`:

```java
    /** Whether {@code viewerRef} gave the task; a group chat (null viewer) owns nothing, so it sees headlines only (ADR 0020). */
    private static boolean ownedBy(Task task, String viewerRef) {
        return task != null && viewerRef != null && task.requester().ref().equals(viewerRef);
    }
```

In `statusPayload`, only the viewer's own running task carries the agent's activity:

```java
            if (isRunning) {
                item.put("startedAt", text(run.startedAt()));
                if (ownedBy(active.get(run.taskId()), viewerRef)) {
                    activeRuns.activity(run.taskId()).ifPresent(activity ->
                            item.put("steps", activity.steps()).put("lastAction", activity.lastAction()));
                }
            }
```

`history` takes `String viewerRef` after `visibleProjects`, and puts the cost only for the viewer's tasks:

```java
            BigDecimal cost = ownedBy(task, viewerRef) ? costs.get(task.id()) : null;
```

`timeline` takes `String viewerRef` after `visibleProjects`; after building the headline fields, a task that is not the viewer's stops there:

```java
        if (!ownedBy(task, viewerRef)) {
            payload.put("headline", true).putNull("costUsd");
            enqueue(tx, null, OutboxKind.TASK_TIMELINE, chatRef, originRef, payload, clock.instant());
            return;
        }
```

In `statsPayload`, after `payload.set("summary", Statistics.summary(given, runs));`:

```java
        if (!view.equals("me")) {
            // A group's total holds other members' costs (ADR 0020).
            ((ObjectNode) payload.get("summary")).putNull("costUsd").putNull("averageCostUsd");
        }
        payload.set("people", view.equals("people") ? Statistics.people(given, runs, viewerRef) : Json.MAPPER.createArrayNode());
```

(replacing the existing `people` line). In `Statistics.people`, add the `viewerRef` parameter and put `costUsd` only for the viewer's row; group by `task.requester().ref()` as today, so the row's ref is the map key:

```java
        byRequester.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, List<Task>>>comparingInt(entry -> entry.getValue().size()).reversed()
                        .thenComparing(entry -> entry.getValue().getLast().requester().name()))
                .forEach(entry -> {
                    List<Task> given = entry.getValue();
                    ObjectNode person = people.addObject().put("name", given.getLast().requester().name()).put("tasks", given.size())
                            .put("completed", count(given, Phase.COMPLETED));
                    if (entry.getKey().equals(viewerRef)) {
                        BigDecimal cost = given.stream().map(task -> costs.getOrDefault(task.id(), BigDecimal.ZERO))
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                        person.put("costUsd", money(cost));
                    } else {
                        person.putNull("costUsd");
                    }
                });
```

Update the Javadocs of `history`, `timeline`, `statsPayload` and `Statistics.people` to say what the viewer sees.

- [ ] **Step 4: Pass the viewer from `UpdateHandler`.** Replace the `history` case:

```java
            case "history" -> {
                String viewer = privateChat ? who.ref() : null;
                taskId(command.args()).ifPresentOrElse(
                        id -> tasks.timeline(tx, visible, viewer, id, origin, chatRef),
                        () -> tasks.history(tx, visible, viewer, origin, chatRef));
            }
```

Fix any other caller the compiler names.

- [ ] **Step 5: Render the thinner payloads.**

In `Renderer.timeline`, right after the header block is added:

```java
        if (payload.path("headline").asBoolean()) {
            // Someone else's task: its runs and cost are theirs (ADR 0020).
            blocks.add("\n" + outcome(payload));
            return plain(joinWithin(blocks, "\n"));
        }
```

In `Renderer.history`, choose the line by whether there is a cost:

```java
            String line = task.hasNonNull("costUsd")
                    ? format("history.line", icons, taskId(task), escape(task.path("project").asText()),
                            escape(task.path("requester").asText()), shortDateTime(task.path("createdAt").asText()), money(task.path("costUsd")))
                    : format("history.lineNoCost", icons, taskId(task), escape(task.path("project").asText()),
                            escape(task.path("requester").asText()), shortDateTime(task.path("createdAt").asText()));
            StringBuilder block = new StringBuilder("\n").append(line)
                    .append("\n").append(escapeWithin(task.path("title").asText(), TITLE_LIMIT));
```

In `Renderer.stats`, the pull-request line and each person line likewise:

```java
            html.append("\n").append(summary.hasNonNull("costUsd")
                    ? format("stats.pullRequests", summary.path("pullRequests").asInt(), money(summary.path("costUsd")),
                            money(summary.path("averageCostUsd")))
                    : format("stats.pullRequestsNoCost", summary.path("pullRequests").asInt()));
```

```java
            blocks.add(separator + (person.hasNonNull("costUsd")
                    ? format("stats.person", escape(person.path("name").asText()), person.path("tasks").asInt(),
                            person.path("completed").asInt(), money(person.path("costUsd")))
                    : format("stats.personNoCost", escape(person.path("name").asText()), person.path("tasks").asInt(),
                            person.path("completed").asInt())));
```

Add to `messages_mn.properties`, next to their siblings:

```properties
history.lineNoCost={0} <b>#{1}</b> {2} · {3} · {4}
stats.pullRequestsNoCost=Pull request {0}
stats.personNoCost=<b>{0}</b> · {1} даалгавар · ✅ {2}
```

- [ ] **Step 6: Run the whole suite**

Run: `./mvnw -q -B verify`
Expected: PASS. Fix remaining callers and tests that read cost or activity from someone else's task by making the viewer the requester (or asserting the headline), never by loosening the rule.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dispatch src/main/resources/messages_mn.properties src/test/java
git commit -m "Show only the headline of other members' tasks in status, history and stats

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 3: A refused private message goes to the group without its content

**Files:**
- Modify: `src/main/java/dispatch/telegram/Renderer.java` (`render(OutboxKind, JsonNode, boolean)` ~71-134, `plan(...)`)
- Modify: `src/main/resources/messages_mn.properties` (`fallback.hint` → `fallback.private`)
- Test: `src/test/java/dispatch/telegram/RendererTest.java` (the two fallback cases ~464 and ~476)

**Interfaces:**
- Consumes: the outbox fallback as it is (`Outbox.enqueueForRequester`, `OutboxSender` re-addressing with `fell_back = 1`); every kind sent with a fallback (`PLAN_READY`, `TASK_COMPLETED`, `TASK_FAILED`, `EXECUTION_QUEUED`) carries `taskId` — confirm in `RunTransitions` and `TaskService.approve`.
- Produces: `render(kind, payload, true)` returns a message with no buttons and no document, whose text is `fallback.private` with the task id and the bot's username.

- [ ] **Step 1: Write the failing test.** Replace the two fallback tests in `RendererTest` with:

```java
    @Test
    void aPrivateMessageThatFellBackToTheGroupShowsNoneOfItsContent() {
        ObjectNode plan = Json.object().put("taskId", 42).put("planSeq", 1).put("project", "alm")
                .put("planJson", new Plan("Rotate the signing key in secrets.env", List.of(), List.of("Edit secrets.env"),
                        List.of(), List.of()).toJson());
        ObjectNode completed = Json.object().put("taskId", 43).put("project", "alm").put("prUrl", "https://github.com/acme/alm/pull/9")
                .put("summary", "Changed the password check");

        for (var message : List.of(renderer.render(OutboxKind.PLAN_READY, plan, true),
                renderer.render(OutboxKind.TASK_COMPLETED, completed, true))) {
            assertFalse(message.html().contains("secrets.env"), message.html());
            assertFalse(message.html().contains("password"), message.html());
            assertFalse(message.html().contains("pull/9"), message.html());
            assertTrue(message.keyboard().isEmpty(), "no Approve button in the group");
            assertNull(message.document());
            assertTrue(message.html().contains("dispatch_backend_bot"), message.html());
        }
    }
```

Use the payload field names `RunTransitions` actually writes for `PLAN_READY` and `TASK_COMPLETED` (read lines ~70-115 there, or copy `samplePayload(...)` from this test file and add the sensitive strings to its fields); the point is that text from the plan, summary and PR link never appears. Match the bot username to the one this test class passes to its `Renderer`.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -B test -Dtest=RendererTest`
Expected: FAIL — the plan text and PR link are in the HTML.

- [ ] **Step 3: Implement.** At the top of `render(OutboxKind kind, JsonNode payload, boolean fellBack)`:

```java
        if (fellBack) {
            // Meant for the requester's private chat: the group learns only that it could not be delivered (ADR 0020).
            return plain(format("fallback.private", taskId(payload), escape(botUsername)));
        }
```

Then remove the now-dead hint handling: the `hint` variable, the final `hint == null ? … : …` expression (return `rendered`), and `plan(...)`'s hint parameter with its uses. Replace `fallback.hint` in `messages_mn.properties` with:

```properties
fallback.private=🔒 #{0}: мэдээлэл таны хувийн чатад ирэх ёстой байсан тул энд харуулахгүй. @{1}-г нээгээд Start дарвал дараагийнх нь тэнд ирнэ.
```

Update the Javadoc of `render(..., boolean fellBack)` and of `Outbox.enqueueForRequester`/`OutboxSender` where they say the message itself goes to the group.

- [ ] **Step 4: Run the whole suite**

Run: `./mvnw -q -B verify`
Expected: PASS (`OutboxSenderTest` checks addressing only; fix it only if it asserted the old hint text).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch src/main/resources/messages_mn.properties src/test/java
git commit -m "Keep a refused private message's content out of the group

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 4: Documentation and ADR 0020

**Files:**
- Create: `docs/adr/0020-members-see-only-the-headline-of-each-others-tasks.md`
- Modify: `README.md` (line ~199, "Use it"), `SECURITY.md` (line ~13), `docs/ARCHITECTURE.md` (lines ~205, ~207, ~218, ~418), `docs/adr/0011-task-details-go-to-the-requester-privately.md` (a "Superseded in part by ADR 0020" note), `docs/superpowers/specs/2026-09-22-team-workers-design.md` (ADR numbers; "in team mode" → every task), `docs/superpowers/specs/2026-09-22-mini-app-design.md` (admin Tasks shows headlines of others' tasks)

**Interfaces:**
- Consumes: the behaviour of Tasks 1–3.
- Produces: docs only.

- [ ] **Step 1: Write ADR 0020** in the format of the existing ADRs (read `docs/adr/0011-task-details-go-to-the-requester-privately.md` and one recent ADR such as 0018 first and copy their heading and section layout). Content:
  - Context: members of a team will soon pay for and run their own tasks (team workers spec); plans, corrections, the agent's actions and cost are theirs.
  - Decision: another member's task shows only its headline (who, project, title, priority, state, PR link) in `/status`, `/history`, `/history N`, `/stats` and group lines; the group chat sees headlines only; only the requester approves, corrects, rejects, reprioritizes, follows up or retries; the requester or an admin cancels; a private message Telegram refuses goes to the group as a content-free notice. No mode switch: a personal bot's single member is always the requester.
  - Consequences: supersedes ADR 0011's "any member can still cancel any task" and "show the whole team's work"; a team lead no longer sees others' spend in Telegram; a requester who blocked the bot loses that message's text.

- [ ] **Step 2: Update the docs.** Change only what is now wrong:
  - `README.md` ~199: "Only the requester can approve, correct, reject, reprioritize, follow up on or retry their task; the requester or an admin can cancel it. Other members see only a task's headline: who, project, title, state and pull request, not its plan, the agent's actions or its cost." Also check the table rows for `/status`, `/history`, `/stats` and the "In a group" paragraph, and say what a group sees where they now say more.
  - `SECURITY.md` ~13: requester-only actions, admin cancel, headline-only visibility, and that a message Telegram refuses privately is not posted to the group.
  - `docs/ARCHITECTURE.md` ~205, ~207, ~218, ~418: follow-up and retry by the requester; cancel by the requester or an admin; the visibility rule.
  - ADR 0011: add a line under its status: "Amended by ADR 0020: members see only each other's headlines; only the requester (or an admin, to cancel) acts."
  - Team workers spec: "recorded as ADR 0020" → "ADR 0021"; W-4's docs list ADR 0021; the Security bullet "In team mode, for another member's task" → "For another member's task (ADR 0020, built in W-1)".
  - Mini App spec, "Task pages": admins' Tasks list shows others' tasks as headlines; Cancel is the only action on them.

- [ ] **Step 3: Check nothing else still says the old rule**

Run: `grep -rn -i "any member\|any member of the project's group\|whole team's work" README.md SECURITY.md CONTEXT.md docs/ARCHITECTURE.md docs/adr`
Expected: only ADR 0011's original text (now marked amended) and nothing that describes current behaviour.

- [ ] **Step 4: Commit**

```bash
git add README.md SECURITY.md docs
git commit -m "Record ADR 0020: members see only the headline of each other's tasks

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```
