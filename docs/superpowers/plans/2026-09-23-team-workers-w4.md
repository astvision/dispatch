# W-4 Worker setup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A member sets their own computer up in one command — `dispatch worker init` pairs it, maps the team's projects to clones it already has (or clones them), checks Claude Code and `gh`, writes `worker.yaml` and `worker.env` and offers to keep it running as the `dispatch-worker` service. `dispatch check` then covers both sides, the browser can set a team up again, the worker cleans up after itself, and the decision is recorded as ADR 0021.

**Architecture:** Nothing new crosses the network. `dispatch worker init` is `dispatch init`'s shape (a `Terminal`, questions, a summary, then one atomic write) over W-3's existing `WorkerClient.pair` and `/api/worker/projects`, and it reuses `ProjectProbe` to check a clone's origin. The background service is the existing `Service` triple with one new axis, `Service.Kind`, so the same systemd unit / launchd plist / Task Scheduler XML writer produces `dispatch-worker` running `worker run` instead of `dispatch` running `run`. `dispatch check` grows a second entry point, `WorkerChecks`, which produces the same `Checks.Finding` records from `worker.yaml`; on the team machine `Checks` stops asking for `gh`, downgrades a missing `claude` to a warning (splitting still runs there, ADR 0013) and probes the worker port and `publicUrl`. The browser setup loses W-3's refusal: `SetupApi.write` finally passes the team's group and a `workers` block through to `Setup.render`, which has taken both since W-3. On the member's machine a `WorkerSweeper` beside the `WorkerLoop` does for the worker's own `worktrees/` what `Sweeper` does on the team machine.

**Tech Stack:** Java 25, JUnit 6, SQLite via the existing `Database`/`Tx`, `java.net.http.HttpClient` and `com.sun.net.httpserver.HttpServer` from the JDK, JLine for the terminal, no framework and no new dependency (ADR 0002). The browser setup is React 19 + Ant Design 6 in `ui/`, tested with vitest and Testing Library with `../api` mocked through `vi.mock`.

**Spec:** `docs/superpowers/specs/2026-09-22-team-workers-design.md` (milestone W-4: Worker setup, Configuration, Security, Testing), plus the carried items from W-3's final review.

## Global Constraints

- Commands, exactly: `dispatch worker init [--config FILE] [--force]`, `dispatch worker pair URL CODE [--name NAME]`, `dispatch worker run [--config FILE] [--log-file FILE]`, `dispatch worker service install|start|stop|status|uninstall [--config FILE]`.
- The worker's background service is named `dispatch-worker.service` (systemd user unit), `io.dispatch.worker` (launchd label) and `DispatchWorker` (Task Scheduler task); it runs `worker run --config <worker.yaml> --log-file <stateDir>/dispatch-worker.log`.
- The team machine's service keeps today's names: `dispatch.service`, `io.dispatch.agent`, `Dispatch`, running `run --config <dispatch.yaml> --log-file <stateDir>/dispatch.log`.
- A member's files stay as W-3 named them: `worker.yaml` beside `dispatch.yaml` (`Locations.workerFile()`), `worker.env` beside it (`SecretsFile.beside`), holding only `DISPATCH_WORKER_KEY`, owner-only.
- `worker init` writes `worker.env` the moment pairing succeeds (the code is one-time); everything else is written only after the summary is confirmed, and `worker.yaml` is validated as a draft beside itself before it is moved into place, exactly as `Setup.write` does.
- The worker's own state directory defaults to `<stateDir>/worker` (W-3's `WorkerConfigLoader`); a project cloned by `worker init` lands in `<worker stateDir>/repos/<name>`.
- `dispatch check` runs the worker's checks when `worker.yaml` exists beside the config file, the team machine's checks when `dispatch.yaml` exists, and both when both do. A machine with only `worker.yaml` never reports "no config at …".
- Team-machine check texts, verbatim: `workers: 127.0.0.1:<port> answers as this Dispatch`; `workers: nothing listens on 127.0.0.1:<port> yet; it starts with dispatch run`; `workers: something other than Dispatch answers on 127.0.0.1:<port>; stop it or set another workers.port`; `workers: <publicUrl> reaches this Dispatch`; `workers: <publicUrl> does not reach this Dispatch; members' computers cannot connect until your tunnel or reverse proxy forwards it to 127.0.0.1:<port>`; `gh: not needed here; members' computers make the pull requests`.
- Worker-machine check texts, verbatim: `worker: <name>, team <url> (<worker.yaml>)`; `team: <url> answers`; `team: cannot reach <url> (<detail>)`; `pairing: no worker key in <worker.env>; run: dispatch worker init`; `pairing: paired with <team> as <name>`; `pairing: this computer's key is not valid any more; pair again: dispatch worker init`; `project <name>: the team has this project, but this computer does not; run: dispatch worker init`; `project <name>: no git clone at <path>; run: dispatch worker init`; `project <name>: <path> has origin <origin>, but the team's project is <repo>`; `project <name>: this computer has it, but the team does not; remove it from <worker.yaml>`.
- A worker probe is a `POST` of `{}` to `<base>/api/worker/projects` with no `Authorization`: only Dispatch answers `401` with body `{"error":"unauthorized",…}`.
- The worker sweeps its own `worktrees/` every hour, removing a worktree untouched for 7 days — never one whose task is running here now, and never one with uncommitted changes or commits that are not on origin.
- Per-worker capacity in the claim gate is one run: a task already pinned to a computer waits while that computer runs any run.
- ADR 0021 is `docs/adr/0021-team-members-tasks-run-on-their-own-computers.md`; the five code comments that cite ADR 0020 for *where tasks run* move to it (ADR 0020 keeps the privacy citations).
- No behaviour changes in personal mode: no `workers` block means no worker checks, no worker service and today's `dispatch check`.
- Commit messages end with `Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z`.
- Verify with `./mvnw -q -B verify`; for Task 8 also `cd ui && npx vitest run` and `./mvnw -q -B -Pui verify`.

Rulings made while planning (each with its cost if wrong):

1. **`dispatch ui` gets the team's `workers` fields, not a member's worker onboarding.** The spec's "the same steps in `dispatch ui`" stays unbuilt: the setup page belongs to the machine that is being set up as a team machine, and a member pairing a laptop has no `dispatch.yaml` for it to write. Cost if wrong: a member must use the terminal (`dispatch worker init`), and a later milestone adds a worker branch to the setup page and its own `/api/setup/worker/*` routes.
2. **`worker init` asks for a typed path, not a folder browser.** The folder browser is `dispatch ui`'s (`Folders`, `FolderBrowser.tsx`); `dispatch init` itself asks for a typed folder at the terminal and this matches it. Cost if wrong: a member on a strange machine types a path instead of browsing, exactly as `dispatch init` already makes them.
3. **`worker init` always pairs, unless a key is already there and the member keeps it.** With `worker.env` present it offers "This computer is already paired with X. Pair again?" (default no). Cost if wrong: re-running init to add one project burns a pairing code and leaves an extra computer in `/worker`, revocable from Telegram.
4. **The key is written the moment pairing succeeds**, before the project questions, unlike `dispatch init`'s "nothing is written until you confirm". A one-time code cannot be re-used, so a member who quits at the project step would otherwise have to fetch a new code. The opening line says so. Cost if wrong: an abandoned `worker init` leaves a `worker.env` with a key for a computer that has no `worker.yaml`; `dispatch check` says `pairing: …` is fine and nothing else runs.
5. **A cloned project goes into the worker's own state directory** (`<worker stateDir>/repos/<name>`), with `worker.yaml` naming that absolute path, rather than teaching the worker W-3's `path: null` → `repos/<name>` fallback. Cost if wrong: a member who moves their state directory edits one path in `worker.yaml`.
6. **`Service` grows one axis, `Service.Kind`**, rather than a second set of service classes: the three OS writers differ only in the unit name, the description and the command's arguments. Cost if wrong: a future service kind that needs a different restart policy or trigger has to split the writers after all.
7. **The worker sweeps by file age, not by task state**: idleness is the newest of `worktrees/<id>` and `runs/<id>`'s last-modified time, because a worker holds no task phases. It therefore never discards a dirty worktree the way `Sweeper` does for a CANCELLED or REJECTED task — it keeps anything not clean and pushed. Cost if wrong: a member's disk keeps the worktree of a task they cancelled mid-change until they delete it themselves.
8. **The worker's idle window is fixed at 7 days**, the same as `worktrees.idleDays`' default, with no new `worker.yaml` key. Cost if wrong: one more optional field in `WorkerConfig` and its loader.
9. **On a team machine a missing `claude` is a WARN, not a FAIL, and `gh` is not checked at all.** The spec says the team machine no longer asks for either, but W-3 ruling 14 left splitting (✂️, ADR 0013) running there, so the warning names it. Cost if wrong: a team owner who never uses ✂️ sees one warning they do not care about.
10. **`publicUrl` is probed only once the local port answered as Dispatch.** Otherwise `dispatch check` before the first `dispatch run` would warn that a tunnel does not reach a Dispatch that is not running. Cost if wrong: a broken tunnel in front of a stopped Dispatch is reported as "nothing listens yet" instead of naming the tunnel.
11. **Per-worker capacity is one run, decided in the same SQL as W-3 ruling 5.** A task pinned to a computer waits while that computer runs anything, whatever `maxConcurrentRuns` that computer's own `worker.yaml` says — the team machine never learns that number. Cost if wrong: a member who sets `maxConcurrentRuns: 2` gets no second *pinned* run in parallel (a new task, pinned to nobody, still goes to their other computer).
12. **`RemoteWorkers` gets `stopPolling()`, called first thing in `WorkerApi.close()`**, rather than the API interrupting its own handler threads: the parked poll is waiting on `RemoteWorkers`' own lock, so only it can end that wait cleanly. Cost if wrong: shutdown keeps tearing a parked 25 s poll's socket down after one second, which is noisy but harmless.
13. **`dispatch check`'s worker side uses the pairing key for one real call** (`/api/worker/projects`), so "paired", "revoked" and "unreachable" are told apart by the team machine itself rather than guessed from the files. Cost if wrong: `dispatch check` on a laptop with no network reports the team as unreachable, which is true but says nothing about the key.

## File Structure

- Modify `src/main/java/dispatch/store/Runs.java` — the claim gate's per-worker capacity clause.
- Modify `src/main/java/dispatch/worker/RemoteWorkers.java` — `stopPolling()`; the `result()` comment.
- Modify `src/main/java/dispatch/worker/WorkerApi.java` — `close()` wakes the parked polls.
- Modify `src/main/java/dispatch/worker/WorkerClient.java` — `readAndDelete` deletes on a throw too.
- Modify `src/main/java/dispatch/worker/WorkerLoop.java` — the injectable-interval constructor becomes public; the sweeper's thread.
- Modify `src/main/java/dispatch/worker/WorkerCommand.java` — `service(...)`, `workerSpec(...)`, the sweeper and `--log-file`.
- Create `src/main/java/dispatch/worker/WorkerInitCommand.java`, `src/main/java/dispatch/worker/WorkerSweeper.java`, `src/main/java/dispatch/worker/WorkerChecks.java`.
- Modify `src/main/java/dispatch/cli/Cli.java` — `worker init`, `worker service`, `worker run --log-file`; usage.
- Modify `src/main/java/dispatch/cli/Service.java` (`Kind`), `SystemdService.java`, `LaunchdService.java`, `WindowsTaskService.java`, `ServiceCommand.java`.
- Modify `src/main/java/dispatch/cli/Checks.java` — team-mode agent/gh, the `workers` probes.
- Modify `src/main/java/dispatch/cli/CheckCommand.java` — run the worker's checks when `worker.yaml` is there.
- Modify `src/main/java/dispatch/cli/Setup.java` — `ghLoggedIn`.
- Modify `src/main/java/dispatch/cli/ProjectProbe.java` — `sameRepo`.
- Modify `src/main/java/dispatch/core/ActiveRuns.java` — `isActive(long)`.
- Modify `src/main/java/dispatch/workspace/Workspaces.java` — `repoOf(Path)`, `localOnlyState(Path)`, `removeWorktree(Path, long)`, one lock per clone.
- Modify `src/main/java/dispatch/Main.java` — the two new invocations and the shared log-file redirect.
- Modify `src/main/java/dispatch/ui/SetupApi.java` — the team's group, team name and `workers` block; the refusal goes.
- Modify `ui/src/api.ts`, `ui/src/setup/SetupPage.tsx`, `ui/src/setup/PeopleStep.tsx`, `ui/src/setup/SummaryStep.tsx`.
- Tests: create `src/test/java/dispatch/worker/{WorkerInitCommandTest,WorkerSweeperTest,WorkerChecksTest}.java`; move `src/test/java/dispatch/cli/ScriptedTerminal.java` to `src/test/java/dispatch/testing/ScriptedTerminal.java` (public, same behaviour) and fix its users; modify `src/test/java/dispatch/core/SchedulerTest.java`, `src/test/java/dispatch/worker/{RemoteWorkersTest,WorkerApiTest,WorkerApiFixture}.java`, `src/test/java/dispatch/cli/{ChecksTest,CheckCommandTest,CliTest,ServiceTest}.java`, `src/test/java/dispatch/ui/SetupApiTest.java`, `src/test/java/dispatch/TeamWorkersTest.java`.
- UI tests: modify `ui/src/setup/PeopleStep.test.tsx`, `ui/src/setup/SummaryStep.test.tsx`.
- Docs: `README.md`, `SECURITY.md`, `docs/ARCHITECTURE.md`, new `docs/adr/0021-team-members-tasks-run-on-their-own-computers.md`, `docs/superpowers/specs/2026-09-22-team-workers-design.md`.

---

### Task 1: A task waits for the computer it is pinned to, even when the member has another

**Files:**
- Modify: `src/main/java/dispatch/store/Runs.java` (`claimNext` ~61-124)
- Test: `src/test/java/dispatch/core/SchedulerTest.java`

**Interfaces:**
- Consumes: `Tasks.recordWorker(Tx, long taskId, long workerId, Instant)`, `Workers.touch(Tx, long, Instant)`, `Workers.SEEN_WITHIN`.
- Produces: no signature change — `Runs.claimNext(Tx, int maxConcurrentRuns, Instant now, Instant workerSeenSince)` keeps its shape and gains one clause.

- [ ] **Step 1: Write the failing tests** — add to `src/test/java/dispatch/core/SchedulerTest.java`, next to `aSecondTaskWaitsForCapacityAndIsClaimedOnceTheFirstRunFinishes`:

```java
    @Test
    void aTaskPinnedToABusyComputerWaitsEvenWhenTheMemberHasAnotherFreeOne() {
        long running = queuedTaskOf("telegram:100");
        long pinned = queuedTaskOf("telegram:100");
        long laptop = pair("telegram:100", "ann-laptop");
        long desktop = pair("telegram:100", "ann-desktop");
        db.transaction(tx -> Tasks.recordWorker(tx, pinned, laptop, clock.instant()));
        db.transaction(tx -> Workers.touch(tx, laptop, clock.instant()));
        db.transaction(tx -> Workers.touch(tx, desktop, clock.instant()));

        assertEquals(running, claim().orElseThrow().taskId());
        // The laptop polled first and took it, exactly as RemoteWorkers.recordAndReturn records.
        db.transaction(tx -> Tasks.recordWorker(tx, running, laptop, clock.instant()));

        assertTrue(claim().isEmpty(), "the only computer that may take it is already running one");
        assertEquals("QUEUED", SqlRows.single(dbFile, "SELECT status FROM run WHERE task_id = ?", pinned).get("status"));

        db.transaction(tx -> tx.update("UPDATE run SET status = 'SUCCEEDED' WHERE task_id = ?", running));

        assertEquals(pinned, claim().orElseThrow().taskId(), "the laptop is free again");
    }

    @Test
    void anUnpinnedTaskStillGoesToTheOtherComputerWhileOneIsBusy() {
        long pinnedToLaptop = queuedTaskOf("telegram:100");
        long fresh = queuedTaskOf("telegram:100");
        long laptop = pair("telegram:100", "ann-laptop");
        long desktop = pair("telegram:100", "ann-desktop");
        db.transaction(tx -> Tasks.recordWorker(tx, pinnedToLaptop, laptop, clock.instant()));
        db.transaction(tx -> Workers.touch(tx, laptop, clock.instant()));
        db.transaction(tx -> Workers.touch(tx, desktop, clock.instant()));

        assertEquals(pinnedToLaptop, claim().orElseThrow().taskId());

        assertEquals(fresh, claim().orElseThrow().taskId(), "it is pinned to nobody, and the desktop is free");
    }
```

Add the helper next to the file's `pair` and `queuedTaskOf` helpers, and use it in the two tests above only (leave the existing tests' inline calls alone):

```java
    /** One claim in team mode, with the worker window the Scheduler itself passes. */
    private Optional<ClaimedRun> claim() {
        return db.transactionReturning(tx -> Runs.claimNext(tx, 2, clock.instant(), clock.instant().minus(Workers.SEEN_WITHIN)));
    }
```

Add `import java.util.Optional;` if the file lacks it.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -B test -Dtest=SchedulerTest`
Expected: `aTaskPinnedToABusyComputerWaitsEvenWhenTheMemberHasAnotherFreeOne` FAILS at `assertTrue(claim().isEmpty())` — two live computers beat one RUNNING run, so the pinned task is claimed and would then be offered to a laptop that never polls. `anUnpinnedTaskStillGoesToTheOtherComputerWhileOneIsBusy` passes already; it is the guard against over-blocking.

- [ ] **Step 3: Add the per-worker clause.** In `src/main/java/dispatch/store/Runs.java`, extend `workerGate` and its parameters:

```java
        // Capacity is one run per live worker (dispatch worker pair's own maxConcurrentRuns default): a member's queued
        // run may be claimed only while fewer of their runs are RUNNING than they have live computers right now, so a
        // second task waits here instead of being claimed and then failed by the lease with nobody to pick it up.
        // The last clause is that same rule for one computer: once a task is pinned (its worktree and session live
        // there), no other computer may take it, so the member's *other* free computers do not make it claimable —
        // only this one being free does.
        String workerGate = workerSeenSince == null ? "" : """
                          AND EXISTS (SELECT 1 FROM worker w
                                      WHERE w.member_ref = t.requester_ref AND w.revoked_at IS NULL
                                        AND w.last_seen_at > ?
                                        AND (t.worker_id IS NULL OR t.worker_id = w.id))
                          AND (SELECT count(*) FROM worker live
                               WHERE live.member_ref = t.requester_ref AND live.revoked_at IS NULL AND live.last_seen_at > ?)
                              > (SELECT count(*) FROM run member_run JOIN task member_task ON member_task.id = member_run.task_id
                                 WHERE member_task.requester_ref = t.requester_ref AND member_run.status = ?)
                          AND (t.worker_id IS NULL OR NOT EXISTS (
                                SELECT 1 FROM run pinned_run JOIN task pinned_task ON pinned_task.id = pinned_run.task_id
                                WHERE pinned_run.status = ? AND pinned_task.worker_id = t.worker_id))
                """;
        List<Object> params = new ArrayList<>(List.of(RunStatus.QUEUED, RunKind.PLAN, RunStatus.RUNNING, RunKind.EXECUTE,
                RunKind.DELIVER));
        if (workerSeenSince != null) {
            params.add(workerSeenSince);
            params.add(workerSeenSince);
            params.add(RunStatus.RUNNING);
            params.add(RunStatus.RUNNING);
        }
```

Extend the `@param workerSeenSince` javadoc's last sentence:

```java
     * @param workerSeenSince in team mode, a run starts only when one of its requester's computers reported since then —
     *                        and, once the task has a worktree, only that computer, and only while that computer is not
     *                        running another run — and fewer of that requester's runs are RUNNING than they have live
     *                        computers, so a task never gets claimed and then failed by the lease for want of a computer
     *                        free to pick it up; null in personal mode, where the run happens in this process
```

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -B test -Dtest=SchedulerTest+RunsClaimTest+CoordinatorTest`
Expected: PASS — the new test now waits for the laptop, `anUnpinnedTaskStillGoesToTheOtherComputerWhileOneIsBusy` still claims both, and personal mode (`workerSeenSince == null`) is untouched.

- [ ] **Step 5: Commit**

```sh
git add -A && git commit -m "Wait for the computer a task is pinned to before claiming its run

A task with a worktree may only run on the computer that holds it, but the
claim gate counted the member's live computers, so a second computer being
free made the pinned task claimable — and its offer then expired unclaimed.
Capacity is now also per computer: a pinned task waits while its own
computer runs anything.

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 2: Shutdown ends a parked poll instead of cutting it off

**Files:**
- Modify: `src/main/java/dispatch/worker/RemoteWorkers.java` (`awaitMatch` ~203, `result` ~324, the class javadoc)
- Modify: `src/main/java/dispatch/worker/WorkerApi.java` (`close` ~147)
- Test: `src/test/java/dispatch/worker/RemoteWorkersTest.java`, `src/test/java/dispatch/worker/WorkerApiTest.java`

**Interfaces:**
- Consumes: `dispatch.store.Workers.Paired(long id, String memberRef, String name, String keySha256, Instant createdAt, Instant lastSeenAt)`.
- Produces: `public void RemoteWorkers.stopPolling()`.

- [ ] **Step 1: Write the failing tests** — add to `src/test/java/dispatch/worker/RemoteWorkersTest.java`:

```java
    @Test
    void stopPollingEndsAParkedLongPollAtOnceInsteadOfHoldingItForTwentyFiveSeconds() throws Exception {
        RemoteWorkers production = new RemoteWorkers(db, clock, () -> { }, RemoteWorkers.LONG_POLL, Duration.ofMillis(2));
        Workers.Paired bold = new Workers.Paired(1, BOLD.ref(), "ann-laptop", "sha", clock.instant(), clock.instant());
        CompletableFuture<Optional<Job>> parked = CompletableFuture.supplyAsync(() -> {
            try {
                return production.next(bold);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        production.stopPolling();

        // Without the wake this waits out the whole 25 s poll and times out here.
        assertTrue(parked.get(5, TimeUnit.SECONDS).isEmpty());
    }
```

`RemoteWorkersTest` already has `db`, `clock`, `keys`, `remote` and the `BOLD` requester, and imports `Optional` and `TimeUnit`; add `java.util.concurrent.CompletableFuture` and `dispatch.store.Workers`.

Add to `src/test/java/dispatch/worker/WorkerApiTest.java`:

```java
    @Test
    void closingTheServerEndsItsPollsAndHandsOutNoMoreJobs() throws Exception {
        pair();
        Workers.Paired bold = db.transactionReturning(tx -> Workers.ofMember(tx, BOLD.ref())).getFirst();
        offer(planJob());

        api.close();

        assertTrue(remote.next(bold).isEmpty(), "a stopped server hands out no more jobs");
        // Let the offer's own thread finish, as App.stop does, so this test leaves nothing parked.
        control.stop(ActiveRuns.StopReason.INTERRUPTED);
        assertNotNull(awaitResult());
    }
```

with `import dispatch.store.Workers;` and `import static org.junit.jupiter.api.Assertions.assertNotNull;` if they are missing.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -B test -Dtest=RemoteWorkersTest+WorkerApiTest`
Expected: FAIL — compilation error `cannot find symbol: method stopPolling()` on `RemoteWorkers`.

- [ ] **Step 3: Wake the parked polls.** In `src/main/java/dispatch/worker/RemoteWorkers.java` add the field beside `offers`:

```java
    /** Set by {@link #stopPolling}: no more jobs go out and every parked poll answers "nothing" at once. */
    private boolean closed;
```

Add the method next to `hasOffers()`:

```java
    /**
     * Ends every parked {@code /api/worker/next} now and refuses to hand out any more jobs. {@link WorkerApi#close}
     * calls this before it stops its server: a poll parked in {@link #awaitMatch} is waiting on this class's own lock,
     * so nothing else can end that wait cleanly, and without it the poll keeps its thread and socket for the rest of
     * its 25 s — well past the one second {@code HttpServer.stop} waits — and shutdown ends by tearing the request
     * down instead of answering it.
     */
    public void stopPolling() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }
```

In `awaitMatch`, check it first inside the loop:

```java
        long deadlineNanos = System.nanoTime() + longPoll.toNanos();
        synchronized (lock) {
            while (true) {
                if (closed) {
                    return null;
                }
                Optional<Offer> match = offers.stream().filter(offer -> matches(offer, worker)).findFirst();
```

- [ ] **Step 4: Call it from `close()`, and fix the comment that contradicts the invariant.** In `src/main/java/dispatch/worker/WorkerApi.java`, `close()` takes the workers with it:

```java
    @Override
    public void close() {
        // First: a poll parked in RemoteWorkers is waiting on that class's lock, not on this server, so stop(1) below
        // would only cut its socket. Woken here, it answers {"job": null} and the exchange ends normally.
        workers.stopPolling();
        server.stop(1);
        bodyReads.shutdown();
```

and add one sentence to its javadoc, after "…up to a second to finish before its socket is torn down (the JDK's own `stop(delay)` wait)":

```java
     * <p>A parked {@code /next} is woken through {@link RemoteWorkers#stopPolling} before that wait, so it answers
     * rather than being cut off — which is what makes a shutdown quiet for production's 25 s poll and not only for a
     * test's short one.
```

In `src/main/java/dispatch/worker/RemoteWorkers.java`, `result(...)`, replace the comment above `if (!offer.answer.complete(result))` — `held()` already excludes an expired offer under the same lock acquisition that sets `expired`, so a concurrent `givenUp()` can never have completed the future first:

```java
        // held() refused an already-expired offer under the same lock acquisition that set expired above, so no
        // givenUp() can have completed this future first: complete() returns false only if that invariant is ever
        // broken, and this branch is the guard that turns such a break into a 409 the worker can act on rather than
        // a silently dropped result.
        if (!offer.answer.complete(result)) {
```

- [ ] **Step 5: Run it to see it pass**

Run: `./mvnw -q -B test -Dtest=RemoteWorkersTest+WorkerApiTest+WorkerProtocolTest+AppTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```sh
git add -A && git commit -m "Wake parked worker polls when the worker API closes

A /api/worker/next parked in RemoteWorkers waits on that class's lock, so
HttpServer.stop could only tear its socket down after a second — quiet for a
test's short poll, noisy for production's 25 s one. close() now wakes them
first, and hands out no further jobs. Also corrects result()'s comment about
a race held() already rules out.

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 3: One service writer, two services

**Files:**
- Modify: `src/main/java/dispatch/cli/Service.java`, `SystemdService.java`, `LaunchdService.java`, `WindowsTaskService.java`, `ServiceCommand.java`
- Test: `src/test/java/dispatch/cli/ServiceTest.java`

**Interfaces:**
- Consumes: `Service.Spec(Path java, Path jar, Path configFile, Path logFile, String path, Path stateDir)`, `Executables.serviceJava(...)`.
- Produces:
  - `public enum Service.Kind` with `String systemdUnit()`, `String launchdLabel()`, `String windowsTask()`, `String label()`, `String logName()`, `String manageCommand()`, `List<String> command()`
  - `Service.Kind kind()` on the interface; `static Service forOs(String osName, Path home, Commands commands, String user, Kind kind)`; `static Service forThisMachine(Kind kind)`
  - `public static Service.Spec ServiceCommand.specFor(Path jar, Path configFile, Path stateDir, String logName, Map<String,String> processEnvironment)`
  - `public int ServiceCommand.run(String action, Supplier<Service.Spec> spec)`

- [ ] **Step 1: Write the failing tests** — add to `src/test/java/dispatch/cli/ServiceTest.java`:

```java
    @Test
    void theWorkerUnitRunsWorkerRunUnderItsOwnName() throws Exception {
        RecordingCommands commands = new RecordingCommands();
        Service service = Service.forOs("Linux", home, commands, "ann", Service.Kind.WORKER);

        service.install(new Service.Spec(Path.of("/usr/bin/java"), Path.of("/opt/dispatch.jar"),
                home.resolve(".config/dispatch/worker.yaml"), home.resolve("state/worker/dispatch-worker.log"),
                "/usr/bin:/bin", home.resolve("state/worker")));

        String unit = Files.readString(home.resolve(".config/systemd/user/dispatch-worker.service"));
        assertTrue(unit.contains("Description=Dispatch worker"), unit);
        assertTrue(unit.contains("-jar \"/opt/dispatch.jar\" worker run --config"), unit);
        assertTrue(unit.contains("dispatch-worker.log"), unit);
        assertEquals("systemd user service dispatch-worker.service", service.describe());
        assertTrue(commands.ran.stream().anyMatch(line -> line.contains("enable --now dispatch-worker.service")),
                commands.ran.toString());
    }

    @Test
    void theTeamUnitKeepsItsNameAndArguments() throws Exception {
        RecordingCommands commands = new RecordingCommands();
        Service service = Service.forOs("Linux", home, commands, "ann");

        service.install(new Service.Spec(Path.of("/usr/bin/java"), Path.of("/opt/dispatch.jar"),
                home.resolve(".config/dispatch/dispatch.yaml"), home.resolve("state/dispatch.log"), "/usr/bin:/bin",
                home.resolve("state")));

        String unit = Files.readString(home.resolve(".config/systemd/user/dispatch.service"));
        assertTrue(unit.contains("-jar \"/opt/dispatch.jar\" run --config"), unit);
        assertEquals("systemd user service dispatch.service", service.describe());
    }

    @Test
    void theWorkerLaunchdAgentAndWindowsTaskCarryTheWorkerArgumentsToo() throws Exception {
        Service launchd = Service.forOs("Mac OS X", home, new RecordingCommands(), "ann", Service.Kind.WORKER);
        launchd.install(new Service.Spec(Path.of("/usr/bin/java"), Path.of("/opt/dispatch.jar"),
                home.resolve("worker.yaml"), home.resolve("dispatch-worker.log"), "/usr/bin", home));

        String plist = Files.readString(home.resolve("Library/LaunchAgents/io.dispatch.worker.plist"));
        assertTrue(plist.contains("<string>io.dispatch.worker</string>"), plist);
        assertTrue(plist.contains("<string>worker</string>\n    <string>run</string>"), plist);

        RecordingCommands schtasks = new RecordingCommands();
        Service windows = Service.forOs("Windows 11", home, schtasks, "ACME\\ann", Service.Kind.WORKER);
        windows.install(new Service.Spec(Path.of("C:\\java\\bin\\java.exe"), Path.of("C:\\dispatch.jar"),
                home.resolve("worker.yaml"), home.resolve("dispatch-worker.log"), "C:\\bin", home));

        assertTrue(schtasks.ran.stream().anyMatch(line -> line.contains("/TN DispatchWorker")), schtasks.ran.toString());
    }
```

Reuse whatever the file already calls its fake `Commands` and its `home` `@TempDir`; the names above (`RecordingCommands`, `home`) stand in for the file's own. If `LaunchdService`'s `domain()` needs `id -u`, make the fake answer `501` for it, as the file's existing launchd tests already do.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -B test -Dtest=ServiceTest`
Expected: FAIL — compilation error `cannot find symbol: variable Kind` on `Service`.

- [ ] **Step 3: Add the kind.** In `src/main/java/dispatch/cli/Service.java`, inside the interface:

```java
    /**
     * What a service definition runs: the team's Dispatch (`dispatch run`), or a member's own computer working on
     * their tasks (`dispatch worker run`, ADR 0021). The two differ only in their name, their description and the
     * arguments they pass, so one writer per OS produces both.
     */
    enum Kind {
        DISPATCH("dispatch.service", "io.dispatch.agent", "Dispatch", "Dispatch", "dispatch.log", "dispatch service",
                List.of("run")),
        WORKER("dispatch-worker.service", "io.dispatch.worker", "DispatchWorker", "Dispatch worker",
                "dispatch-worker.log", "dispatch worker service", List.of("worker", "run"));

        private final String systemdUnit;
        private final String launchdLabel;
        private final String windowsTask;
        private final String label;
        private final String logName;
        private final String manageCommand;
        private final List<String> command;

        Kind(String systemdUnit, String launchdLabel, String windowsTask, String label, String logName,
             String manageCommand, List<String> command) {
            this.systemdUnit = systemdUnit;
            this.launchdLabel = launchdLabel;
            this.windowsTask = windowsTask;
            this.label = label;
            this.logName = logName;
            this.manageCommand = manageCommand;
            this.command = command;
        }

        public String systemdUnit() {
            return systemdUnit;
        }

        public String launchdLabel() {
            return launchdLabel;
        }

        public String windowsTask() {
            return windowsTask;
        }

        /** What it is called in a sentence, e.g. "Dispatch worker runs in the background as …". */
        public String label() {
            return label;
        }

        public String logName() {
            return logName;
        }

        /** The command that manages it, for the line setup prints. */
        public String manageCommand() {
            return manageCommand;
        }

        /** The dispatch arguments before --config and --log-file. */
        public List<String> command() {
            return command;
        }
    }

    Kind kind();
```

Replace the two factories:

```java
    /** @param user the current user: a login name, or DOMAIN\name on Windows */
    static Service forOs(String osName, Path home, Commands commands, String user) {
        return forOs(osName, home, commands, user, Kind.DISPATCH);
    }

    static Service forOs(String osName, Path home, Commands commands, String user, Kind kind) {
        if (osName.startsWith("Windows")) {
            return new WindowsTaskService(commands, user, kind);
        }
        if (osName.startsWith("Mac")) {
            return new LaunchdService(home, commands, kind);
        }
        return new SystemdService(home, commands, user, kind);
    }

    /** The service for this OS and user, as `dispatch service` and the web UI manage it. */
    static Service forThisMachine() {
        return forThisMachine(Kind.DISPATCH);
    }

    /** @param kind DISPATCH for the team's own instance, WORKER for this computer's `dispatch worker run` */
    static Service forThisMachine(Kind kind) {
        Path home = Path.of(System.getProperty("user.home"));
        String os = System.getProperty("os.name");
        String user = os.startsWith("Windows") && System.getenv("USERDOMAIN") != null
                ? System.getenv("USERDOMAIN") + "\\" + System.getProperty("user.name")
                : System.getProperty("user.name");
        Commands commands = commandLine -> {
            try {
                return Git.runProcess(commandLine, home, null, Duration.ofSeconds(60), String.join(" ", commandLine));
            } catch (WorkspaceException e) {
                return new Git.Result(127, "", e.getMessage());
            }
        };
        return forOs(os, home, commands, user, kind);
    }
```

In `src/main/java/dispatch/cli/SystemdService.java`, replace the constant with the kind:

```java
final class SystemdService implements Service {

    private final Path unitFile;
    private final Commands commands;
    private final String user;
    private final Kind kind;

    SystemdService(Path home, Commands commands, String user, Kind kind) {
        this.unitFile = home.resolve(".config").resolve("systemd").resolve("user").resolve(kind.systemdUnit());
        this.commands = commands;
        this.user = user;
        this.kind = kind;
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public String describe() {
        return "systemd user service " + kind.systemdUnit();
    }
```

and in `install`, the description and the arguments:

```java
                Description=%s
                After=network-online.target

                [Service]
                Type=simple
                ExecStart=%s -jar %s %s --config %s --log-file %s
```

formatted with `kind.label()` first and `String.join(" ", kind.command())` in the new slot:

```java
                """.formatted(kind.label(), quoted(spec.java()), quoted(spec.jar()), String.join(" ", kind.command()),
                quoted(spec.configFile()), quoted(spec.logFile()), quoted("PATH=" + spec.path())));
```

Replace every remaining `UNIT` in `install`, `start`, `stop`, `restart`, `status` and `uninstall` with `kind.systemdUnit()`.

In `src/main/java/dispatch/cli/LaunchdService.java`, the same: a `Kind kind` field instead of `LABEL`, `plist` from `kind.launchdLabel() + ".plist"`, `kind()` and `describe()` from it, and the arguments array built from the kind:

```java
    @Override
    public void install(Spec spec) {
        StringBuilder arguments = new StringBuilder();
        for (String argument : kind.command()) {
            arguments.append("    <string>").append(xml(argument)).append("</string>\n");
        }
        Service.write(plist, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <!-- Written by dispatch service install (ADR 0016). -->
                <plist version="1.0">
                <dict>
                  <key>Label</key><string>%s</string>
                  <key>ProgramArguments</key>
                  <array>
                    <string>%s</string>
                    <string>-jar</string>
                    <string>%s</string>
                %s    <string>--config</string>
                    <string>%s</string>
                    <string>--log-file</string>
                    <string>%s</string>
                  </array>
                  <key>EnvironmentVariables</key>
                  <dict>
                    <key>PATH</key><string>%s</string>
                  </dict>
                  <key>RunAtLoad</key><true/>
                  <key>KeepAlive</key>
                  <dict>
                    <key>SuccessfulExit</key><false/>
                  </dict>
                  <key>ThrottleInterval</key><integer>10</integer>
                </dict>
                </plist>
                """.formatted(kind.launchdLabel(), xml(spec.java()), xml(spec.jar()), arguments,
                xml(spec.configFile()), xml(spec.logFile()), xml(spec.path())));
        commands.run(List.of("launchctl", "bootout", domain() + "/" + kind.launchdLabel()));
        Service.required(commands, List.of("launchctl", "bootstrap", domain(), plist.toString()));
    }
```

Replace the remaining `LABEL` uses in `start`, `stop`, `restart`, `status` and `uninstall` with `kind.launchdLabel()`.

In `src/main/java/dispatch/cli/WindowsTaskService.java`, a `Kind kind` field instead of `TASK`, `kind()`, `describe()` from `kind.windowsTask()`, the arguments:

```java
        String arguments = "-jar " + quoted(spec.jar()) + " " + String.join(" ", kind.command())
                + " --config " + quoted(spec.configFile()) + " --log-file " + quoted(spec.logFile());
```

the task file named per kind so the two do not overwrite each other:

```java
        Path file = spec.stateDir().resolve(kind.windowsTask().toLowerCase(java.util.Locale.ROOT) + "-task.xml");
```

and `TASK` replaced with `kind.windowsTask()` in every `schtasks` call.

- [ ] **Step 4: Let `ServiceCommand` drive either kind.** In `src/main/java/dispatch/cli/ServiceCommand.java`:

```java
    /** The service for this OS and user, run with this process's Java and jar. */
    public static ServiceCommand forThisMachine(Terminal terminal) {
        return new ServiceCommand(terminal, Service.forThisMachine(), runningJar());
    }

    public int run(Cli.Service options, Map<String, String> processEnvironment) {
        return run(options.action(), () -> specFor(jar, options.configFile(), processEnvironment));
    }

    /** @param spec read only when the action needs it, so status/stop work on a config this process cannot load */
    public int run(String action, Supplier<Service.Spec> spec) {
        try {
            switch (action) {
                case "install" -> install(spec.get());
                case "start" -> {
                    service.start();
                    terminal.ok("started " + service.describe());
                }
                case "stop" -> {
                    service.stop();
                    terminal.ok("stopped " + service.describe());
                }
                case "status" -> status();
                case "uninstall" -> {
                    service.uninstall();
                    terminal.ok("removed " + service.describe());
                }
                default -> throw new CliException("unknown service action " + action);
            }
            return 0;
        } catch (CliException | ConfigException | UncheckedIOException e) {
            terminal.fail(e.getMessage());
            return 1;
        }
    }

    /** Checks the config and its secrets first, so a service never starts on a setup that cannot run. */
    void install(Path configFile, Map<String, String> processEnvironment) {
        install(specFor(jar, configFile, processEnvironment));
    }

    void install(Service.Spec spec) {
        terminal.during("Installing the " + service.describe(), () -> {
            service.install(spec);
            return null;
        });
        terminal.ok(service.kind().label() + " runs in the background as " + service.describe());
        terminal.say("  Logs:   " + spec.logFile());
        terminal.say("  Manage: " + service.kind().manageCommand() + " status | stop | start | uninstall");
        service.status().notes().forEach(terminal::warn);
    }
```

Add `import java.util.function.Supplier;`. Split `specFor` in two:

```java
    /** What the team's service runs: this process's Java, {@code jar}, and the config, whose secrets are checked first. */
    public static Service.Spec specFor(Path jar, Path configFile, Map<String, String> processEnvironment) {
        RunCommand.Prepared prepared;
        try {
            prepared = RunCommand.prepare(configFile, processEnvironment);
        } catch (ConfigException e) {
            throw new CliException(e.getMessage());
        }
        return specFor(jar, configFile, prepared.config().stateDir(), Service.Kind.DISPATCH.logName(), processEnvironment);
    }

    /**
     * The same spec without loading a {@code dispatch.yaml}: a worker's config is a {@code worker.yaml}, which its own
     * caller has already read (see {@code WorkerCommand.workerSpec}).
     */
    public static Service.Spec specFor(Path jar, Path configFile, Path stateDir, String logName,
                                       Map<String, String> processEnvironment) {
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new CliException("the service runs dispatch.jar, but this is not running from it; install Dispatch first");
        }
        Path java = Executables.serviceJava(Path.of(ProcessHandle.current().info().command().orElse("java")),
                System.getProperty("os.name"), processEnvironment, Path.of(System.getProperty("user.home")));
        String path = processEnvironment.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("PATH"))
                .map(Map.Entry::getValue).findFirst().orElse("");
        return new Service.Spec(java, jar.toAbsolutePath(), configFile.toAbsolutePath(), stateDir.resolve(logName),
                path, stateDir);
    }
```

- [ ] **Step 5: Run it to see it pass**

Run: `./mvnw -q -B test -Dtest=ServiceTest+InitCommandTest+SetupApiTest+UiCommandTest`
Expected: PASS — the team's unit, its texts and `SetupApi`'s `service.install(specFor(...))` are unchanged, and the worker's unit is written under its own names.

- [ ] **Step 6: Commit**

```sh
git add -A && git commit -m "Give Service a kind, so it can also install dispatch-worker

The three OS writers differed only in a name and the arguments, so one enum
(DISPATCH, WORKER) produces both: dispatch.service / io.dispatch.agent /
Dispatch running run, and dispatch-worker.service / io.dispatch.worker /
DispatchWorker running worker run with its own log file.

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 4: `dispatch worker init`

**Files:**
- Create: `src/main/java/dispatch/worker/WorkerInitCommand.java`
- Modify: `src/main/java/dispatch/cli/Cli.java`, `src/main/java/dispatch/cli/ProjectProbe.java`, `src/main/java/dispatch/cli/Setup.java`, `src/main/java/dispatch/worker/WorkerCommand.java`, `src/main/java/dispatch/worker/WorkerClient.java`, `src/main/java/dispatch/Main.java`
- Test: create `src/test/java/dispatch/worker/WorkerInitCommandTest.java`; modify `src/test/java/dispatch/cli/CliTest.java`

**Interfaces:**
- Consumes: `WorkerClient.pair(HttpClient, URI, String code, String name) -> Paired(long workerId, String key, String team)`, `WorkerClient.setup() -> Setup(String team, String authorName, String authorEmail, List<ProjectInfo> projects)`, `ProjectProbe.of(Path, Git)`, `SecretsFile.write(Path, Map)`, `Setup.findClaude(Map)`, `Setup.claudeVersion(String)`, `Terminal`, `Locations`, `ServiceCommand`.
- Produces:
  - `public record Cli.WorkerInit(Path workerFile, boolean force) implements Invocation`
  - `public record Cli.WorkerService(Path workerFile, String action) implements Invocation`
  - `Cli.WorkerRun` gains a `Path logFile` component
  - `public static boolean ProjectProbe.sameRepo(String a, String b)`
  - `public static boolean Setup.ghLoggedIn(String command)`
  - `public final class WorkerInitCommand` with `public WorkerInitCommand(Terminal, Locations, ServiceCommand)` and `public int run(Cli.WorkerInit options, Map<String,String> processEnvironment)`
  - `public static Service.Spec WorkerCommand.workerSpec(Path workerFile, Map<String,String>)` and `public static int WorkerCommand.service(Terminal, Cli.WorkerService, Map<String,String>)`

- [ ] **Step 1: Make the two test fixtures usable from here.**

`ScriptedTerminal` is package-private in `dispatch.cli` today: move it to `src/test/java/dispatch/testing/ScriptedTerminal.java`, make the class and its members `public`, and fix the imports in `InitCommandTest`, `InitCommandAdvancedTest`, `ProjectAddCommandTest`, `CheckCommandTest` and any other user (`import dispatch.testing.ScriptedTerminal;`). Do not change its behaviour.

`WorkerApiFixture.config(String)` hard-codes `git@github.com:acme/alm.git` as alm's repo, which `worker init` would try to clone and `ProjectProbe.sameRepo` would refuse. Make it a method a subclass can point at a real repository — `repos` is created before `config()` is called in `setUpFixture`, so an override sees it:

```java
    /** The repo /api/worker/projects reports for alm; a test that maps or clones it overrides this with a real one. */
    String almRepo() {
        return "git@github.com:acme/alm.git";
    }
```

and use `almRepo()` in place of the literal in `config(String publicUrl)`.

- [ ] **Step 2: Write the failing tests** — create `src/test/java/dispatch/worker/WorkerInitCommandTest.java`:

```java
package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.cli.Cli;
import dispatch.cli.Locations;
import dispatch.cli.SecretsFile;
import dispatch.testing.GitFixture;
import dispatch.testing.ScriptedTerminal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** dispatch worker init, against a real WorkerApi: the member's own computer, set up in one command. */
class WorkerInitCommandTest extends WorkerApiFixture {

    /** A command that exists on every OS and answers --version, as CheckCommandTest uses: the JVM running this test. */
    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    /** A real repository, so "a clone I already have" matches it and "clone it here" can actually clone it. */
    @Override
    String almRepo() {
        return repos.origin.toString();
    }

    @Test
    void itPairsMapsAClonePicksAModelAndWritesBothFiles() throws Exception {
        Path workerFile = dir.resolve("config/worker.yaml");
        ScriptedTerminal terminal = new ScriptedTerminal(
                "http://127.0.0.1:" + api.port(),          // Team URL
                "ann-laptop",                              // A name for this computer
                keys.newCode(BOLD),                        // Pairing code
                "A clone I already have",                  // alm: how it gets onto this computer
                repos.repo("alm").toString(),              // Folder of the clone
                "Opus",                                    // Model on this computer
                "",                                        // Effort: the team's
                JAVA,                                      // claude command
                JAVA,                                      // GitHub CLI command (not logged in: a warning, not a failure)
                "y");                                      // Write this setup?

        int status = init(terminal, workerFile);

        assertEquals(0, status, terminal.output());
        WorkerConfig config = WorkerConfigLoader.load(workerFile);
        assertEquals("ann-laptop", config.name());
        assertEquals("http://127.0.0.1:" + api.port(), config.team());
        assertEquals(repos.repo("alm").toString(), config.projects().get("alm").path());
        assertEquals("opus", config.projects().get("alm").model());
        String key = SecretsFile.read(SecretsFile.beside(workerFile)).get(WorkerCommand.KEY_VARIABLE);
        assertTrue(key != null && !key.isBlank(), "no worker key was written");
        assertFalse(terminal.output().contains(key), "the key must never be shown: " + terminal.output());
    }

    @Test
    void itClonesAProjectIntoItsOwnStateDirectoryWhenTheMemberHasNoClone() throws Exception {
        Path workerFile = dir.resolve("config/worker.yaml");
        ScriptedTerminal terminal = new ScriptedTerminal(
                "http://127.0.0.1:" + api.port(), "ann-laptop", keys.newCode(BOLD),
                "Clone it here",                           // alm: let the worker clone it
                "", "",                                    // model, effort: the team's
                JAVA, JAVA, "y");

        int status = init(terminal, workerFile);

        assertEquals(0, status, terminal.output());
        Path clone = dir.resolve("state/worker/repos/alm");
        assertTrue(Files.isDirectory(clone.resolve(".git")), "alm was not cloned: " + terminal.output());
        assertEquals(clone.toString(), WorkerConfigLoader.load(workerFile).projects().get("alm").path());
    }

    @Test
    void aCloneOfAnotherRepositoryIsRefusedUnlessTheMemberConfirmsIt() throws Exception {
        Path other = dir.resolve("elsewhere");
        GitFixture.sh(dir, "git", "init", "--quiet", "-b", "main", other.toString());
        Path workerFile = dir.resolve("config/worker.yaml");
        ScriptedTerminal terminal = new ScriptedTerminal(
                "http://127.0.0.1:" + api.port(), "ann-laptop", keys.newCode(BOLD),
                "A clone I already have", other.toString(),
                "n",                                       // Use it anyway? no
                "A clone I already have", repos.repo("alm").toString(), "", "",
                JAVA, JAVA, "y");

        int status = init(terminal, workerFile);

        assertEquals(0, status, terminal.output());
        assertTrue(terminal.output().contains("is not a clone of"), terminal.output());
        assertEquals(repos.repo("alm").toString(), WorkerConfigLoader.load(workerFile).projects().get("alm").path());
    }

    @Test
    void anExistingWorkerYamlIsKeptUnlessForced() throws Exception {
        Path workerFile = dir.resolve("config/worker.yaml");
        Files.createDirectories(workerFile.getParent());
        Files.writeString(workerFile, "team: https://team.example.com\nname: ann-laptop\n");
        ScriptedTerminal terminal = new ScriptedTerminal();

        int status = init(terminal, workerFile);

        assertEquals(1, status, terminal.output());
        assertTrue(terminal.output().contains("dispatch worker init --force"), terminal.output());
        assertEquals("team: https://team.example.com\nname: ann-laptop\n", Files.readString(workerFile));
    }

    /** No ServiceCommand: these tests must not install anything on the machine that runs them. */
    private int init(ScriptedTerminal terminal, Path workerFile) {
        return new WorkerInitCommand(terminal, new Locations(dir.resolve("config/dispatch.yaml"), dir.resolve("state")),
                null).run(new Cli.WorkerInit(workerFile, false), Map.of());
    }
}
```

Add to `src/test/java/dispatch/cli/CliTest.java`:

```java
    @Test
    void workerInitRunAndServiceAreParsed() {
        Locations defaults = new Locations(Path.of("/home/ann/.config/dispatch/dispatch.yaml"), Path.of("/state"));

        assertEquals(new Cli.WorkerInit(Path.of("/home/ann/.config/dispatch/worker.yaml"), false),
                Cli.parse(new String[] {"worker", "init"}, defaults));
        assertEquals(new Cli.WorkerInit(Path.of("/tmp/w.yaml"), true),
                Cli.parse(new String[] {"worker", "init", "--config", "/tmp/w.yaml", "--force"}, defaults));
        assertEquals(new Cli.WorkerRun(Path.of("/home/ann/.config/dispatch/worker.yaml"), Path.of("/tmp/w.log")),
                Cli.parse(new String[] {"worker", "run", "--log-file", "/tmp/w.log"}, defaults));
        assertEquals(new Cli.WorkerService(Path.of("/home/ann/.config/dispatch/worker.yaml"), "install"),
                Cli.parse(new String[] {"worker", "service", "install"}, defaults));
        assertTrue(assertThrows(CliException.class, () -> Cli.parse(new String[] {"worker", "service"}, defaults))
                .getMessage().contains("worker service needs one of"));
        assertTrue(assertThrows(CliException.class, () -> Cli.parse(new String[] {"worker", "nope"}, defaults))
                .getMessage().contains("unknown command 'worker nope'"));
    }
```

- [ ] **Step 3: Run them to see them fail**

Run: `./mvnw -q -B test -Dtest=WorkerInitCommandTest+CliTest`
Expected: FAIL — compilation errors: `cannot find symbol: class WorkerInitCommand`, `cannot find symbol: class WorkerInit` and `class WorkerService` on `Cli`.

- [ ] **Step 4: Parse the new commands.** In `src/main/java/dispatch/cli/Cli.java`:

```java
    public sealed interface Invocation permits Run, Init, Check, ProjectAdd, Service, Ui, WorkerInit, WorkerPair,
            WorkerRun, WorkerService, Help {
    }
```

```java
    /** @param force replaces an existing worker.yaml and pairs this computer again */
    public record WorkerInit(Path workerFile, boolean force) implements Invocation {
    }

    /** @param logFile where output goes instead of the terminal, as the dispatch-worker service runs it; null for the terminal */
    public record WorkerRun(Path workerFile, Path logFile) implements Invocation {
    }

    /** @param action one of install, start, stop, status, uninstall */
    public record WorkerService(Path workerFile, String action) implements Invocation {
    }
```

and in `parse`'s `case "worker"`:

```java
                yield switch (arguments.positional().getFirst()) {
                    case "init" -> {
                        arguments.allow(1, Set.of("config", "force"));
                        yield new WorkerInit(arguments.workerFile(defaults), arguments.switches().contains("force"));
                    }
                    case "pair" -> {
                        if (arguments.positional().size() < 3) {
                            throw new CliException("worker pair needs the team URL and the code from /worker");
                        }
                        arguments.allow(3, Set.of("config", "name"));
                        yield new WorkerPair(arguments.workerFile(defaults), arguments.positional().get(1),
                                arguments.positional().get(2), arguments.values().getOrDefault("name", hostName()));
                    }
                    case "run" -> {
                        arguments.allow(1, Set.of("config", "log-file"));
                        yield new WorkerRun(arguments.workerFile(defaults), arguments.values().containsKey("log-file")
                                ? Path.of(arguments.values().get("log-file")) : null);
                    }
                    case "service" -> {
                        if (arguments.positional().size() < 2 || !SERVICE_ACTIONS.contains(arguments.positional().get(1))) {
                            throw new CliException("worker service needs one of: " + String.join(", ", SERVICE_ACTIONS));
                        }
                        arguments.allow(2, Set.of("config"));
                        yield new WorkerService(arguments.workerFile(defaults), arguments.positional().get(1));
                    }
                    default -> throw new CliException("unknown command 'worker " + arguments.positional().getFirst() + "'");
                };
```

and update `worker needs one of: pair, run` to `worker needs one of: init, pair, run, service`. In `usage`, replace the two worker lines with:

```
                  worker init
                           set your computer up for your team's bot: pair it, map its
                           projects to your clones, and keep it running in the background
                  worker pair URL CODE [--name NAME]
                           connect this computer to a team; CODE comes from /worker in the bot
                  worker run
                           run your own tasks on this computer
                  worker service install|start|stop|status|uninstall
                           keep your worker running in the background
```

- [ ] **Step 5: Add the two small shared helpers.** In `src/main/java/dispatch/cli/ProjectProbe.java`:

```java
    /**
     * Whether two git URLs name the same repository: {@code git@github.com:acme/alm.git},
     * {@code https://github.com/acme/alm} and {@code ssh://git@github.com/acme/alm.git/} all match. Compared on host
     * and path only, because the transport, a user name in the URL and a trailing {@code .git} are the member's own
     * choice, not a different repository.
     */
    public static boolean sameRepo(String a, String b) {
        return a != null && b != null && !a.isBlank() && key(a).equals(key(b));
    }

    private static String key(String url) {
        String rest = url.strip().toLowerCase(java.util.Locale.ROOT).replaceFirst("^[a-z0-9+.-]+://", "");
        int at = rest.indexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        // scp syntax: host:owner/name -> host/owner/name; a :port stays a separator too, which is harmless here.
        rest = rest.replaceFirst(":", "/");
        return rest.replaceFirst("\\.git$", "").replaceFirst("/+$", "");
    }
```

In `src/main/java/dispatch/cli/Setup.java`, next to `claudeVersion`:

```java
    /** Whether the GitHub CLI can make pull requests here: {@code gh auth status} exits 0. */
    public static boolean ghLoggedIn(String command) {
        try {
            return Git.runProcess(List.of(command, "auth", "status"), Path.of(System.getProperty("user.home")), null,
                    COMMAND_TIMEOUT, command + " auth status").exitCode() == 0;
        } catch (WorkspaceException e) {
            return false;
        }
    }
```

- [ ] **Step 6: Write the command.** Create `src/main/java/dispatch/worker/WorkerInitCommand.java`:

```java
package dispatch.worker;

import dispatch.OwnerOnly;
import dispatch.cli.Cli;
import dispatch.cli.CliException;
import dispatch.cli.Locations;
import dispatch.cli.ProjectProbe;
import dispatch.cli.SecretsFile;
import dispatch.cli.ServiceCommand;
import dispatch.cli.Setup;
import dispatch.cli.Terminal;
import dispatch.config.ConfigException;
import dispatch.config.ConfigText;
import dispatch.workspace.Git;
import dispatch.workspace.WorkspaceException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * `dispatch worker init`: sets this computer up to run its owner's own tasks (ADR 0021). It pairs with the team, asks
 * the team machine which projects the member has, maps each to a clone here (one the member already has, or one it
 * clones), checks Claude Code and the GitHub CLI, and writes {@code worker.yaml} beside an owner-only
 * {@code worker.env}.
 *
 * <p>Unlike `dispatch init`, one thing is written before the summary: the pairing code is one-time, so the key it
 * buys is saved the moment it arrives rather than lost if the member stops halfway. The first line says so.
 */
public final class WorkerInitCommand {

    private static final int ATTEMPTS = 3;
    private static final Duration CLONE_TIMEOUT = Duration.ofMinutes(30);
    /** A worker's override of the team's choice; the first option keeps whatever the team configured. */
    private static final List<Terminal.Option<String>> MODELS = List.of(
            new Terminal.Option<>("The team's setting", "whatever the project says on the team machine", null),
            new Terminal.Option<>("Sonnet", "", "sonnet"),
            new Terminal.Option<>("Opus", "", "opus"),
            new Terminal.Option<>("Fable", "", "fable"));
    private static final List<Terminal.Option<String>> EFFORTS = List.of(
            new Terminal.Option<>("The team's setting", "", null),
            new Terminal.Option<>("Low", "fastest", "low"),
            new Terminal.Option<>("Medium", "", "medium"),
            new Terminal.Option<>("High", "", "high"),
            new Terminal.Option<>("Extra high", "", "xhigh"),
            new Terminal.Option<>("Max", "most thorough", "max"));

    private final Terminal terminal;
    private final Locations locations;
    private final ServiceCommand services;

    /** @param services installs the dispatch-worker service offered at the end; null when this build has no jar */
    public WorkerInitCommand(Terminal terminal, Locations locations, ServiceCommand services) {
        this.terminal = terminal;
        this.locations = locations;
        this.services = services;
    }

    public int run(Cli.WorkerInit options, Map<String, String> processEnvironment) {
        try {
            init(options.workerFile().toAbsolutePath(), options.force(), processEnvironment);
            return 0;
        } catch (CliException | ConfigException e) {
            terminal.fail(e.getMessage());
            return 1;
        }
    }

    private void init(Path workerFile, boolean force, Map<String, String> environment) {
        if (Files.exists(workerFile) && !force) {
            throw new CliException(workerFile + " already exists; edit it, or start over with: "
                    + "dispatch worker init --force");
        }
        Path envFile = SecretsFile.beside(workerFile);
        terminal.say("Dispatch worker setup. Your tasks will run here, with your own Claude Code login and clones.");
        terminal.say("Your key is saved as soon as you pair (the code works once); nothing else until you confirm the summary.");

        terminal.step("1/5 Your team");
        Paired paired = pair(workerFile, envFile);
        WorkerClient client = new WorkerClient(HttpClient.newHttpClient(), URI.create(paired.teamUrl()), paired.key());
        WorkerClient.Setup team;
        try {
            team = terminal.during("Asking " + paired.teamUrl() + " for your projects", client::setup);
        } catch (RuntimeException e) {
            // The key is saved, so this is worth retrying without a new code: say so instead of a stack trace.
            throw new CliException("cannot ask " + paired.teamUrl() + " for your projects (" + e.getMessage()
                    + "); your key is in " + envFile + ", so run dispatch worker init --force again when it answers");
        }
        terminal.ok("paired with " + team.team() + " as " + paired.name());

        terminal.step("2/5 Your projects");
        Path stateDir = locations.stateDir().resolve("worker");
        Map<String, WorkerConfig.Project> projects = projects(team.projects(), stateDir);

        terminal.step("3/5 Claude Code");
        String claude = claude(environment);

        terminal.step("4/5 GitHub CLI");
        String gh = gh();

        terminal.step("5/5 Summary");
        summary(team.team(), paired, projects, claude, gh, workerFile);
        if (!terminal.confirm("Write this setup?", true)) {
            throw new CliException("cancelled; " + workerFile + " was not written (your key stays in " + envFile + ")");
        }
        write(workerFile, render(paired, projects, claude, gh, stateDir));
        terminal.ok("wrote " + workerFile);
        offerService(workerFile, environment);
    }

    /** What pairing settled: where the team is, what this computer is called there, and the key it may use. */
    private record Paired(String teamUrl, String name, String key) {
    }

    /**
     * Exchanges a code for this computer's key and saves it at once. An existing key is offered for re-use first: a
     * member adding a project should not have to fetch a new code, nor leave a second computer in their /worker list.
     */
    private Paired pair(Path workerFile, Path envFile) {
        Optional<Paired> existing = existingPairing(workerFile, envFile);
        if (existing.isPresent() && !terminal.confirm("This computer is already paired with " + existing.get().teamUrl()
                + " as " + existing.get().name() + ". Pair again?", false)) {
            return existing.get();
        }
        String url = required("Team URL (your team owner has it, e.g. https://team.example.com)",
                existing.map(Paired::teamUrl).orElse(null));
        String name = required("A name for this computer, as your /worker list will show it",
                existing.map(Paired::name).orElse(Cli.hostName()));
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String code = required("Pairing code (send /worker to the bot in Telegram)", null);
            try {
                WorkerClient.Paired answer = terminal.during("Pairing with " + url,
                        () -> WorkerClient.pair(HttpClient.newHttpClient(), URI.create(url), code, name));
                saveKey(workerFile, envFile, answer.key());
                terminal.ok("this computer is #" + answer.workerId() + " in your /worker list");
                return new Paired(url, name, answer.key());
            } catch (RuntimeException e) {
                terminal.warn("pairing failed: " + e.getMessage());
            }
        }
        throw new CliException("could not pair after " + ATTEMPTS + " tries; check the URL and ask for a fresh code with /worker");
    }

    private Optional<Paired> existingPairing(Path workerFile, Path envFile) {
        if (!Files.exists(envFile) || !Files.exists(workerFile)) {
            return Optional.empty();
        }
        try {
            String key = SecretsFile.read(envFile).get(WorkerCommand.KEY_VARIABLE);
            WorkerConfig config = WorkerConfigLoader.load(workerFile);
            return key == null ? Optional.empty() : Optional.of(new Paired(config.team(), config.name(), key));
        } catch (IOException | ConfigException | CliException e) {
            // An unreadable or invalid pair of files is simply no pairing to offer; init writes fresh ones.
            return Optional.empty();
        }
    }

    private void saveKey(Path workerFile, Path envFile, String key) {
        try {
            // A worker machine never runs `dispatch init`, so the config directory usually does not exist yet.
            OwnerOnly.createDirectories(workerFile.getParent());
            Map<String, String> values = new LinkedHashMap<>(Files.exists(envFile) ? SecretsFile.read(envFile) : Map.of());
            values.put(WorkerCommand.KEY_VARIABLE, key);
            SecretsFile.write(envFile, values);
            terminal.ok("wrote " + envFile + " (only you can read it)");
        } catch (IOException e) {
            throw new CliException("cannot write " + envFile + ": " + e.getMessage());
        }
    }

    /** One entry per project the team says this member has; a project nobody maps here simply cannot run here. */
    private Map<String, WorkerConfig.Project> projects(List<WorkerClient.ProjectInfo> team, Path stateDir) {
        if (team.isEmpty()) {
            terminal.warn("your team has no projects for you yet; add them later in " + stateDir.getParent());
            return Map.of();
        }
        Git git = new Git("git", null, Duration.ofSeconds(30));
        Map<String, WorkerConfig.Project> projects = new LinkedHashMap<>();
        for (WorkerClient.ProjectInfo project : team) {
            terminal.say("");
            terminal.say("  " + project.name() + (project.repo() == null ? "" : " · " + project.repo())
                    + (project.baseBranch() == null ? "" : " · base " + project.baseBranch()));
            Optional<Path> path = folderFor(project, git, stateDir);
            if (path.isEmpty()) {
                terminal.warn(project.name() + " is not set up here; tasks for it will wait until you run "
                        + "dispatch worker init again");
                continue;
            }
            projects.put(project.name(), new WorkerConfig.Project(path.get().toString(),
                    terminal.choose("Model on this computer", MODELS, 0),
                    terminal.choose("Effort on this computer", EFFORTS, 0)));
            terminal.ok(project.name() + " → " + path.get());
        }
        return projects;
    }

    private Optional<Path> folderFor(WorkerClient.ProjectInfo project, Git git, Path stateDir) {
        List<Terminal.Option<String>> options = project.repo() == null
                ? List.of(new Terminal.Option<>("A clone I already have", "give its folder", "existing"),
                        new Terminal.Option<>("Skip it", "you can add it later", "skip"))
                : List.of(new Terminal.Option<>("A clone I already have", "give its folder", "existing"),
                        new Terminal.Option<>("Clone it here", "into " + stateDir.resolve("repos").resolve(project.name()), "clone"),
                        new Terminal.Option<>("Skip it", "you can add it later", "skip"));
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            switch (terminal.choose("How does " + project.name() + " get onto this computer?", options, 0)) {
                case "existing" -> {
                    Optional<Path> folder = existingClone(project, git);
                    if (folder.isPresent()) {
                        return folder;
                    }
                }
                case "clone" -> {
                    return Optional.of(clone(project, stateDir));
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Path> existingClone(WorkerClient.ProjectInfo project, Git git) {
        String folder = required("Folder of your " + project.name() + " clone", null);
        ProjectProbe probe;
        try {
            probe = terminal.during("Reading " + folder, () -> ProjectProbe.of(Path.of(folder), git));
        } catch (CliException | InvalidPathException e) {
            terminal.warn(e.getMessage());
            return Optional.empty();
        }
        if (probe.originHadCredentials()) {
            terminal.warn("origin's URL holds credentials; it is not checked against the team's repo");
        } else if (project.repo() != null && !ProjectProbe.sameRepo(probe.originUrl(), project.repo())) {
            terminal.warn(probe.folder() + " is not a clone of " + project.repo()
                    + " (its origin is " + probe.originUrl() + ")");
            if (!terminal.confirm("Use it anyway?", false)) {
                return Optional.empty();
            }
        }
        return Optional.of(probe.folder());
    }

    private Path clone(WorkerClient.ProjectInfo project, Path stateDir) {
        Path target = stateDir.resolve("repos").resolve(project.name());
        try {
            OwnerOnly.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new CliException("cannot create " + target.getParent() + ": " + e.getMessage());
        }
        try {
            terminal.during("Cloning " + project.repo() + " into " + target, () -> new Git("git", null, CLONE_TIMEOUT)
                    .run(target.getParent(), "clone", "--quiet", project.repo(), target.toString()));
        } catch (WorkspaceException e) {
            throw new CliException("cannot clone " + project.repo() + ": " + e.getMessage()
                    + "; clone it yourself and run dispatch worker init again");
        }
        return target;
    }

    private String claude(Map<String, String> environment) {
        Optional<Path> found = Setup.findClaude(environment);
        if (found.isEmpty()) {
            terminal.say("claude was not found; install Claude Code, or give the full path to claude.");
        }
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String command = required("claude command", found.map(Path::toString).orElse("claude"));
            Optional<String> version = terminal.during("Checking " + command, () -> Setup.claudeVersion(command));
            if (version.isPresent()) {
                terminal.ok(version.get());
                return command;
            }
            terminal.warn("cannot run " + command + " --version");
        }
        throw new CliException("Claude Code could not be run; install it and run dispatch worker init again");
    }

    /** Not fatal: a member can log in to gh later, and only delivery needs it. */
    private String gh() {
        String command = required("GitHub CLI command", "gh");
        if (Setup.ghLoggedIn(command)) {
            terminal.ok("gh: logged in");
        } else {
            terminal.warn("gh is not logged in or not installed (" + command
                    + "); pull requests will fail until you run: gh auth login");
        }
        return command;
    }

    private void summary(String team, Paired paired, Map<String, WorkerConfig.Project> projects, String claude,
                         String gh, Path workerFile) {
        terminal.say("  Team      " + team + " (" + paired.teamUrl() + ") as " + paired.name());
        terminal.say("  Projects  " + (projects.isEmpty() ? "none yet" : projects.entrySet().stream()
                .map(entry -> entry.getKey() + " → " + entry.getValue().path())
                .collect(Collectors.joining(", "))));
        terminal.say("  Claude    " + claude);
        terminal.say("  GitHub    " + gh);
        terminal.say("  Config    " + workerFile);
    }

    private String render(Paired paired, Map<String, WorkerConfig.Project> projects, String claude, String gh,
                          Path stateDir) {
        StringBuilder yaml = new StringBuilder()
                .append("# Written by dispatch worker init. Edit it freely: dispatch check says if something is wrong.\n")
                .append("team: ").append(ConfigText.quoted(paired.teamUrl())).append('\n')
                .append("name: ").append(ConfigText.quoted(paired.name())).append('\n')
                .append("maxConcurrentRuns: 1\n")
                .append("stateDir: ").append(ConfigText.quoted(stateDir.toString())).append('\n');
        if (!claude.equals("claude")) {
            yaml.append("claudeCommand: ").append(ConfigText.quoted(claude)).append('\n');
        }
        if (!gh.equals("gh")) {
            yaml.append("ghCommand: ").append(ConfigText.quoted(gh)).append('\n');
        }
        if (projects.isEmpty()) {
            yaml.append("# projects:\n#   crm:\n#     path: /absolute/path/to/your/clone\n");
            return yaml.toString();
        }
        yaml.append("projects:\n");
        projects.forEach((name, project) -> {
            yaml.append("  ").append(ConfigText.yaml(name)).append(":\n")
                    .append("    path: ").append(ConfigText.quoted(project.path())).append('\n');
            if (project.model() != null) {
                yaml.append("    model: ").append(ConfigText.yaml(project.model())).append('\n');
            }
            if (project.effort() != null) {
                yaml.append("    effort: ").append(ConfigText.yaml(project.effort())).append('\n');
            }
        });
        return yaml.toString();
    }

    /** Written as a draft beside the file and loaded once, so an invalid setup never replaces a working one. */
    private static void write(Path workerFile, String yaml) {
        Path draft = null;
        try {
            OwnerOnly.createDirectories(workerFile.getParent());
            draft = Files.createTempFile(workerFile.getParent(), ".worker-", ".yaml");
            Files.writeString(draft, yaml);
            WorkerConfigLoader.load(draft);
            Files.move(draft, workerFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (ConfigException e) {
            deleteQuietly(draft);
            throw new CliException("this setup does not make a valid worker.yaml: "
                    + e.getMessage().replace(String.valueOf(draft), workerFile.toString()));
        } catch (IOException e) {
            deleteQuietly(draft);
            throw new CliException("cannot write " + workerFile + ": " + e.getMessage());
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            if (file != null) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
            // best effort: the write already failed, and this cleanup must not hide that failure
        }
    }

    private void offerService(Path workerFile, Map<String, String> environment) {
        if (services != null && terminal.confirm("Keep your worker running in the background, also after a restart?", true)) {
            try {
                services.run("install", () -> WorkerCommand.workerSpec(workerFile, environment));
                return;
            } catch (CliException e) {
                terminal.warn("the background service could not be installed: " + e.getMessage());
            }
        }
        terminal.say("");
        terminal.say("Next: dispatch check, then dispatch worker run (or dispatch worker service install).");
    }

    private String required(String question, String defaultValue) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String answer = terminal.ask(question, defaultValue);
            if (!answer.isBlank()) {
                return answer.strip();
            }
        }
        throw new CliException(question + " is needed");
    }
}
```

`hostName()` is the default `dispatch worker pair --name` already uses: make `Cli`'s own `private static String hostName()` `public` (its javadoc already explains it) and call `Cli.hostName()` from `pair(...)` above rather than copying those eight lines.

Check `ConfigText.quoted`/`ConfigText.yaml` exist with those names (they are used by `Setup.render`); if `yaml(String)` is not public, use `quoted` for every value.

- [ ] **Step 7: Wire the worker's service and `--log-file`.** In `src/main/java/dispatch/worker/WorkerCommand.java`, add:

```java
    /** What the dispatch-worker service runs: this process's jar, `worker run`, and this worker's own log file. */
    public static Service.Spec workerSpec(Path workerFile, Map<String, String> environment) {
        WorkerConfig config = WorkerConfigLoader.load(workerFile);
        return ServiceCommand.specFor(ServiceCommand.runningJar(), workerFile, config.stateDir(),
                Service.Kind.WORKER.logName(), environment);
    }

    /** `dispatch worker service install | start | stop | status | uninstall` (ADR 0016, 0021). */
    public static int service(Terminal terminal, Cli.WorkerService options, Map<String, String> environment) {
        ServiceCommand command = new ServiceCommand(terminal, Service.forThisMachine(Service.Kind.WORKER),
                ServiceCommand.runningJar());
        return command.run(options.action(), () -> workerSpec(options.workerFile(), environment));
    }
```

with imports for `dispatch.cli.Service`, `dispatch.cli.ServiceCommand` and `dispatch.cli.Terminal`.

While this file is open, fix the carried nit in `src/main/java/dispatch/worker/WorkerClient.java`: `readAndDelete` must not leave the error body behind when the read itself throws.

```java
    private static String readAndDelete(Path target) {
        try {
            return Files.readString(target);
        } catch (IOException e) {
            return "";
        } finally {
            // Whatever happened above, these bytes are an error response sitting where the caller expects a file.
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // best effort: the attachment already failed, and this cleanup must not replace its message
            }
        }
    }
```

In `src/main/java/dispatch/Main.java`, add the two invocations and share the log-file redirect:

```java
            case Cli.WorkerInit init -> {
                JLineTerminal terminal = JLineTerminal.system();
                System.exit(new WorkerInitCommand(terminal, defaults, new ServiceCommand(terminal,
                        Service.forThisMachine(Service.Kind.WORKER), ServiceCommand.runningJar()))
                        .run(init, System.getenv()));
            }
            case Cli.WorkerPair pair -> System.exit(new WorkerCommand(System.out).pair(pair));
            case Cli.WorkerRun worker -> {
                redirect(worker.logFile());
                System.exit(new WorkerCommand(System.out).run(worker, System.getenv()));
            }
            case Cli.WorkerService service -> System.exit(WorkerCommand.service(JLineTerminal.system(), service,
                    System.getenv()));
```

and replace `run(Path, Path)`'s inline redirect with the shared method:

```java
    /** A background service has no terminal: everything that would be shown goes to the log file instead. */
    private static void redirect(Path logFile) {
        if (logFile == null) {
            return;
        }
        try {
            Files.createDirectories(logFile.toAbsolutePath().getParent());
            PrintStream log = new PrintStream(new FileOutputStream(logFile.toFile(), true), true, StandardCharsets.UTF_8);
            System.setOut(log);
            System.setErr(log);
        } catch (IOException e) {
            System.err.println("cannot write the log file " + logFile + ": " + e.getMessage());
            System.exit(2);
        }
    }
```

(`run` then starts with `redirect(logFile);`.)

- [ ] **Step 8: Run it to see it pass**

Run: `./mvnw -q -B test -Dtest=WorkerInitCommandTest+CliTest+WorkerCommandTest+InitCommandTest+ServiceTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```sh
git add -A && git commit -m "Add dispatch worker init

One command sets a member's computer up: pair with the team, fetch their
projects from /api/worker/projects, map each to a clone they already have
(checked against the project's repo with ProjectProbe) or clone it into the
worker's own state directory, pick a model and effort, check claude and gh,
then write worker.yaml and offer the dispatch-worker service. The key is
saved as soon as pairing succeeds, because the code works only once.

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 5: The worker cleans up after itself

**Files:**
- Create: `src/main/java/dispatch/worker/WorkerSweeper.java`
- Modify: `src/main/java/dispatch/workspace/Workspaces.java`, `src/main/java/dispatch/core/ActiveRuns.java`, `src/main/java/dispatch/worker/WorkerCommand.java`, `src/main/java/dispatch/worker/WorkerLoop.java`
- Test: create `src/test/java/dispatch/worker/WorkerSweeperTest.java`; modify `src/test/java/dispatch/TeamWorkersTest.java`

**Interfaces:**
- Consumes: `Workspaces.WorktreeState(List<String> uncommitted, boolean pushed)`, `dispatch.core.Signal`, `ActiveRuns`.
- Produces:
  - `public Path Workspaces.repoOf(Path worktree)`, `public Workspaces.WorktreeState Workspaces.localOnlyState(Path worktree)` and `public void Workspaces.removeWorktree(Path repo, long taskId)`
  - `public boolean ActiveRuns.isActive(long taskId)`
  - `public final class WorkerSweeper implements Runnable` with `public WorkerSweeper(Path stateDir, Workspaces workspaces, ActiveRuns activeRuns, Clock clock)`, the package-private `WorkerSweeper(Path, Workspaces, ActiveRuns, Clock, Duration idle, Duration interval)`, `public int sweep()` and `public void stop()`
  - `WorkerLoop`'s injectable-interval constructor becomes `public`

- [ ] **Step 1: Write the failing test** — create `src/test/java/dispatch/worker/WorkerSweeperTest.java`:

```java
package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.testing.GitFixture;
import dispatch.testing.TestClock;
import dispatch.workspace.Git;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The member's own machine tidying up: nothing on the team machine can reach these worktrees (ADR 0021). */
class WorkerSweeperTest {

    private static final Config.Project ALM = new Config.Project("alm", null, null, null, "main", "claude-code", null,
            null, List.of(), null, null, null);

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock(Instant.parse("2026-09-23T10:00:00Z"));
    private GitFixture repos;
    private Workspaces workspaces;
    private ActiveRuns activeRuns;

    @BeforeEach
    void setUp() throws IOException {
        repos = GitFixture.create(dir, "alm");
        workspaces = new Workspaces(repos.stateDir, new Git("git", null, Duration.ofSeconds(30)));
        workspaces.createDirectories();
        activeRuns = new ActiveRuns();
    }

    @Test
    void anIdleCleanWorktreeIsRemovedAndABusyOrDirtyOneIsKept() throws Exception {
        Path idle = workspaces.createWorktree(project(), 1).path();
        Path dirty = workspaces.createWorktree(project(), 2).path();
        Path running = workspaces.createWorktree(project(), 3).path();
        Files.writeString(dirty.resolve("notes.txt"), "work in progress\n");
        activeRuns.register(3, 1);
        for (Path worktree : List.of(idle, dirty, running)) {
            Files.setLastModifiedTime(worktree, FileTime.from(clock.instant().minus(Duration.ofDays(30))));
        }

        int removed = sweeper().sweep();

        assertEquals(1, removed);
        assertFalse(Files.exists(idle), "an idle, clean worktree goes");
        assertTrue(Files.exists(dirty), "an uncommitted change is never discarded here");
        assertTrue(Files.exists(running), "a run is working in it right now");
    }

    @Test
    void aWorktreeThatWasUsedRecentlyIsKept() throws Exception {
        Path recent = workspaces.createWorktree(project(), 4).path();
        Files.setLastModifiedTime(recent, FileTime.from(clock.instant().minus(Duration.ofDays(30))));
        // A run's own log directory is what says "something happened here", and it lives outside the worktree.
        Files.createDirectories(repos.stateDir.resolve("runs").resolve("4"));

        assertEquals(0, sweeper().sweep());
        assertTrue(Files.exists(recent));
    }

    private WorkerSweeper sweeper() {
        return new WorkerSweeper(repos.stateDir, workspaces, activeRuns, clock, Duration.ofDays(7), Duration.ofHours(1));
    }

    private Config.Project project() {
        return ALM;
    }
}
```

`Workspaces.repo(ALM)` resolves to `stateDir/repos/alm`, which `GitFixture.create(dir, "alm")` already cloned, so `createWorktree` works against a real clone.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -B test -Dtest=WorkerSweeperTest`
Expected: FAIL — compilation error `cannot find symbol: class WorkerSweeper`.

- [ ] **Step 3: Let `Workspaces` remove a worktree it was not told the project of.** In `src/main/java/dispatch/workspace/Workspaces.java`, key the locks on the clone instead of the project name and add the two methods:

```java
    private final Map<String, ReentrantLock> repoLocks = new ConcurrentHashMap<>();
```

```java
    /** One lock per clone: concurrent fetch/worktree add in one clone fail on git's own lock files (.git/config, refs). */
    // ponytail: in-process lock per clone, enough because one process owns each state directory (ADR 0005, 0021).
    private ReentrantLock lockFor(Path repo) {
        return repoLocks.computeIfAbsent(repo.toString(), path -> new ReentrantLock());
    }

    /**
     * The clone a worktree belongs to, asked of git itself: the worker's own sweeper walks {@code worktrees/} without
     * knowing which project each one came from.
     */
    public Path repoOf(Path worktree) {
        return Path.of(git.run(worktree, "rev-parse", "--path-format=absolute", "--git-common-dir")).getParent();
    }

    /**
     * What removing this worktree would lose, for a caller that does not know where the task's branch started: it asks
     * git which commits exist only here instead ({@code branch --remotes --contains HEAD}), rather than comparing with
     * a {@code baseSha} as {@link #state} does. A worktree straight off {@code origin/<base>} has nothing of its own,
     * a delivered one's branch is on origin, and anything else is kept.
     */
    public WorktreeState localOnlyState(Path worktree) {
        List<String> uncommitted = git.run(worktree, "status", "--porcelain").lines().filter(line -> !line.isBlank()).toList();
        return new WorktreeState(uncommitted, !git.run(worktree, "branch", "--remotes", "--contains", "HEAD").isBlank());
    }

    /** Removes the task's worktree, changes and all; its branch stays in the clone for {@link #recreateWorktree}. */
    public void removeWorktree(Config.Project project, long taskId) {
        removeWorktree(repo(project), taskId);
    }

    /** As above, for a caller that knows the clone but not the project (see {@link #repoOf}). */
    public void removeWorktree(Path repo, long taskId) {
        ReentrantLock lock = lockFor(repo);
        lock.lock();
        try {
            git.run(repo, "worktree", "remove", "--force", worktree(taskId).toString());
        } finally {
            lock.unlock();
        }
    }
```

and replace the two `repoLocks.computeIfAbsent(project.name(), …)` calls in `createWorktree` and `recreateWorktree` with `lockFor(repo)`.

In `src/main/java/dispatch/core/ActiveRuns.java`, next to `activity`:

```java
    /** Whether a run of this task is being carried right now in this process. */
    public boolean isActive(long taskId) {
        return byTask.containsKey(taskId);
    }
```

- [ ] **Step 4: Write the sweeper.** Create `src/main/java/dispatch/worker/WorkerSweeper.java`:

```java
package dispatch.worker;

import dispatch.Log;
import dispatch.core.ActiveRuns;
import dispatch.core.Signal;
import dispatch.workspace.WorkspaceException;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * Removes this computer's own worktrees once nothing has run in them for {@code idle}. The team machine's
 * {@code Sweeper} cannot: since W-3 the worktrees are here, on the member's own machine (ADR 0021), and it never sees
 * them.
 *
 * <p>This side holds no task state, so it decides idleness from the files themselves — the newer of the worktree's own
 * last-modified time and that of the run logs under {@code runs/&lt;task&gt;} — and, unlike the team machine's sweeper,
 * it never discards a worktree that still holds work: anything with uncommitted changes or commits that are not on
 * origin is kept and logged, whatever the task's phase turned out to be. The {@code dispatch/&lt;task&gt;} branch stays
 * in this computer's clone, so a later retry or follow-up recreates the worktree here.
 */
public final class WorkerSweeper implements Runnable {

    /** The same window as the team machine's worktrees.idleDays default; worker.yaml has no setting of its own. */
    public static final Duration IDLE = Duration.ofDays(7);
    private static final Duration INTERVAL = Duration.ofHours(1);

    private final Path stateDir;
    private final Workspaces workspaces;
    private final ActiveRuns activeRuns;
    private final Clock clock;
    private final Duration idle;
    private final Duration interval;
    private final Signal signal = new Signal();
    private volatile boolean stopped;

    public WorkerSweeper(Path stateDir, Workspaces workspaces, ActiveRuns activeRuns, Clock clock) {
        this(stateDir, workspaces, activeRuns, clock, IDLE, INTERVAL);
    }

    WorkerSweeper(Path stateDir, Workspaces workspaces, ActiveRuns activeRuns, Clock clock, Duration idle,
                  Duration interval) {
        this.stateDir = stateDir;
        this.workspaces = workspaces;
        this.activeRuns = activeRuns;
        this.clock = clock;
        this.idle = idle;
        this.interval = interval;
    }

    @Override
    public void run() {
        Log.info("worker_sweeper.started", "idle_days", idle.toDays());
        while (!stopped) {
            sweep();
            try {
                signal.await(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.info("worker_sweeper.stopped");
    }

    public void stop() {
        stopped = true;
        signal.wake();
    }

    /** One pass; returns how many worktrees it removed. */
    public int sweep() {
        Path worktrees = stateDir.resolve("worktrees");
        if (!Files.isDirectory(worktrees)) {
            return 0;
        }
        int removed = 0;
        for (Path worktree : listed(worktrees)) {
            if (stopped) {
                break;
            }
            Long taskId = taskIdOf(worktree);
            if (taskId == null || activeRuns.isActive(taskId) || !isIdle(taskId, worktree)) {
                continue;
            }
            try {
                removed += remove(taskId, worktree) ? 1 : 0;
            } catch (WorkspaceException e) {
                Log.warn("worker_sweeper.failed", "task", taskId, "error", e.getMessage());
            }
        }
        if (removed > 0) {
            Log.info("worker_sweeper.done", "removed", removed);
        }
        return removed;
    }

    private boolean remove(long taskId, Path worktree) {
        Workspaces.WorktreeState state = workspaces.localOnlyState(worktree);
        if (!state.disposable()) {
            Log.warn("worker_sweeper.kept", "task", taskId, "uncommitted", state.uncommitted().size(),
                    "pushed", state.pushed(), "worktree", worktree);
            return false;
        }
        workspaces.removeWorktree(workspaces.repoOf(worktree), taskId);
        Log.info("worker_sweeper.removed", "task", taskId, "worktree", worktree);
        return true;
    }

    /** Nothing has touched this task here for {@code idle}: neither its worktree nor its run logs. */
    private boolean isIdle(long taskId, Path worktree) {
        Instant cutoff = clock.instant().minus(idle);
        return lastTouched(worktree).isBefore(cutoff)
                && lastTouched(stateDir.resolve("runs").resolve(Long.toString(taskId))).isBefore(cutoff);
    }

    /**
     * {@link Instant#MIN} for a path that is not there (nothing ever happened), {@link Instant#MAX} for one that
     * cannot be read (never idle, so nothing is removed on a guess).
     */
    private static Instant lastTouched(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toInstant() : Instant.MIN;
        } catch (IOException e) {
            return Instant.MAX;
        }
    }

    private static Long taskIdOf(Path worktree) {
        try {
            return Files.isDirectory(worktree) ? Long.valueOf(worktree.getFileName().toString()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Path> listed(Path worktrees) {
        try (Stream<Path> entries = Files.list(worktrees)) {
            return entries.toList();
        } catch (IOException e) {
            Log.warn("worker_sweeper.unreadable", "dir", worktrees, "error", e.getMessage());
            return List.of();
        }
    }
}
```

- [ ] **Step 5: Start it with the loop, and open the loop's test constructor.** In `src/main/java/dispatch/worker/WorkerLoop.java` make the second constructor public:

```java
    /**
     * @param progressInterval overrides {@link #PROGRESS}; a real wall-clock duration, so a test need not pay a real
     *                         10 s wait per tick. Public because the end-to-end test drives a worker from another
     *                         package and would otherwise wait out a whole production interval per assertion.
     */
    public WorkerLoop(WorkerConfig config, WorkerClient client, Map<String, Agent> agentsByType, Workspaces workspaces,
                      Delivery delivery, Redactor redactor, ActiveRuns activeRuns, Duration progressInterval) {
```

In `src/main/java/dispatch/worker/WorkerCommand.run`, hoist the `ActiveRuns` so the sweeper shares it, and start and stop the sweeper with the loop:

```java
        ActiveRuns activeRuns = new ActiveRuns();
        WorkerLoop loop = new WorkerLoop(config, client, agents, workspaces, delivery,
                Redactor.fromEnvironment(environment), activeRuns);
        WorkerSweeper sweeper = new WorkerSweeper(config.stateDir(), workspaces, activeRuns, Clock.systemUTC());
        Thread sweeperThread = Thread.ofVirtual().name("worker-sweeper").start(sweeper);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sweeper.stop();
            loop.stop();
        }, "dispatch-worker-shutdown"));
        out.println("Running " + setup.team() + " tasks on this computer as " + config.name() + ". Ctrl+C to stop.");
        loop.run();
        sweeper.stop();
        sweeperThread.join(Duration.ofSeconds(10));
        return 0;
```

with `import java.time.Clock;`.

In `src/test/java/dispatch/TeamWorkersTest.java`, pass a short interval now that the constructor is public (line ~198):

```java
        WorkerLoop loop = new WorkerLoop(config, client, agents, workspaces, delivery, Redactor.patternsOnly(),
                new ActiveRuns(), Duration.ofMillis(200));
```

- [ ] **Step 6: Run it to see it pass**

Run: `./mvnw -q -B test -Dtest=WorkerSweeperTest+WorkerLoopTest+TeamWorkersTest+SweeperTest`
Expected: PASS, and `TeamWorkersTest` finishes noticeably sooner: it no longer waits out 10 s progress ticks.

- [ ] **Step 7: Commit**

```sh
git add -A && git commit -m "Sweep the worker's own worktrees

Since W-3 a member's worktrees live on their machine, where the team's
Sweeper cannot reach them, and nothing removed them. WorkerSweeper runs
beside the loop, removes a worktree untouched for 7 days, and keeps every
one that still holds work — it knows no task phases, so it never discards.
WorkerLoop's injectable-interval constructor is public, so the two-computer
test stops waiting out real 10 s ticks.

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 6: `dispatch check` on the team machine

**Files:**
- Modify: `src/main/java/dispatch/cli/Checks.java`
- Test: `src/test/java/dispatch/cli/ChecksTest.java`

**Interfaces:**
- Consumes: `Config.workers()`, `dispatch.worker.WorkerApi.PROJECTS`, `java.net.http.HttpClient`.
- Produces: no new public signature; `Checks.run(Path, Map, Consumer<Finding>)` gains the `workers` findings and drops `gh` in team mode.

- [ ] **Step 1: Write the failing test** — add to `src/test/java/dispatch/cli/ChecksTest.java`:

```java
    @Test
    void aTeamMachineChecksItsWorkerPortAndAsksForNoGh() throws IOException {
        int free = freePort();
        writeTeamConfig(free);

        List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

        assertTrue(findings.contains(new Checks.Finding(Checks.Level.OK, "gh",
                "gh: not needed here; members' computers make the pull requests")), findings.toString());
        assertTrue(findings.contains(new Checks.Finding(Checks.Level.OK, "workers",
                "workers: nothing listens on 127.0.0.1:" + free + " yet; it starts with dispatch run")),
                findings.toString());
        assertFalse(Checks.failed(findings), findings.toString());
    }

    @Test
    void aRunningTeamMachineRecognisesItsOwnWorkerApiAndItsPublicUrl() throws Exception {
        HttpServer dispatchLike = stubWorkerApi();
        try {
            writeTeamConfig(dispatchLike.getAddress().getPort());

            List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

            int port = dispatchLike.getAddress().getPort();
            assertTrue(findings.contains(new Checks.Finding(Checks.Level.OK, "workers",
                    "workers: 127.0.0.1:" + port + " answers as this Dispatch")), findings.toString());
            assertTrue(findings.contains(new Checks.Finding(Checks.Level.OK, "workers",
                    "workers: http://127.0.0.1:" + port + " reaches this Dispatch")), findings.toString());
        } finally {
            dispatchLike.stop(0);
        }
    }

    @Test
    void somethingElseOnTheWorkerPortIsAWarning() throws Exception {
        HttpServer other = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        other.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write("hi".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        other.start();
        try {
            writeTeamConfig(other.getAddress().getPort());

            List<Checks.Finding> findings = checks().run(config, Map.of(), finding -> { });

            assertTrue(findings.contains(new Checks.Finding(Checks.Level.WARN, "workers",
                    "workers: something other than Dispatch answers on 127.0.0.1:" + other.getAddress().getPort()
                            + "; stop it or set another workers.port")), findings.toString());
        } finally {
            other.stop(0);
        }
    }

    /** personal.yaml with a group chat, which makes it a team, and the workers block a team then needs. */
    private void writeTeamConfig(int port) throws IOException {
        writeConfig();
        Files.writeString(config, Files.readString(config)
                .replace("    - name: bold\n", "    - name: bold\n      chatId: -1001234567890\n")
                + "\nworkers:\n  publicUrl: 'http://127.0.0.1:" + port + "'\n  port: " + port + "\n");
    }

    /** What Dispatch answers an unauthenticated worker request; nothing else answers exactly this. */
    private static HttpServer stubWorkerApi() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/api/worker/projects", exchange -> {
            byte[] body = "{\"error\":\"unauthorized\",\"message\":\"no\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    private static int freePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }
```

Add `com.sun.net.httpserver.HttpServer`, `java.net.InetAddress` and `java.net.InetSocketAddress` to the file's imports. `writeConfig()` already substitutes the JVM for `claude` and `gh`, so the team-mode agent check stays OK and the `gh` line comes from the new branch.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -B test -Dtest=ChecksTest`
Expected: FAIL — no `workers` finding is produced at all, and `gh` is still probed, so the expected OK line is missing.

- [ ] **Step 3: Check the workers block.** In `src/main/java/dispatch/cli/Checks.java`, add the probe timeout, the client and the new branches:

```java
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    /** A probe only has to reach a server on this machine, or a proxy in front of it. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    private final Function<String, BotApi> bots;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();
```

In `run`, after the bot check:

```java
        Config config = prepared.config();
        checkBot(run, config.secrets().telegramBotToken());
        boolean team = config.workers() != null;
        config.agents().forEach((name, agent) -> checkAgent(run, name, agent.command(), configFile, team));
        Workspaces workspaces = new Workspaces(config.stateDir(), new Git("git", null, COMMAND_TIMEOUT));
        config.projects().forEach(project -> checkProject(run, project, workspaces));
        checkStateDir(run, config.stateDir());
        if (team) {
            // Tasks run on members' computers (ADR 0021), so nothing here opens a pull request.
            run.add(Level.OK, "gh", "gh: not needed here; members' computers make the pull requests");
            checkWorkers(run, config.workers());
        } else {
            checkGh(run, config.delivery().ghCommand(), config.secrets().ghToken() != null, configFile);
        }
        return run.findings;
```

Change `checkAgent`'s failure branch:

```java
    private static void checkAgent(Run run, String name, String command, Path configFile, boolean team) {
        Optional<Git.Result> version = command(List.of(command, "--version"), configFile);
        if (version.isEmpty() || version.get().exitCode() != 0) {
            // In team mode no task's agent runs here, but splitting a message with ✂️ still does (ADR 0013), so this
            // is worth saying and not worth failing on.
            run.add(team ? Level.WARN : Level.FAIL, name, name + ": cannot run " + command
                    + (team ? "; tasks run on members' computers, but splitting a message with ✂️ still runs here (ADR 0013)"
                            : "; install Claude Code or set agents." + name + ".command to its full path"));
            return;
        }
        run.add(Level.OK, name, name + ": " + version.get().stdout().strip().lines().findFirst().orElse(command));
    }
```

Add the new check and its probe:

```java
    /**
     * Whether members' computers can reach this machine: the loopback port Dispatch listens on, and the public URL
     * their workers are given. The public URL is only probed once the local port answered as Dispatch — otherwise a
     * check run before the first `dispatch run` would blame the tunnel for a Dispatch that is not running.
     */
    private void checkWorkers(Run run, Config.Workers workers) {
        String local = "http://127.0.0.1:" + workers.port();
        switch (probe(local)) {
            case DISPATCH -> {
                run.add(Level.OK, "workers", "workers: 127.0.0.1:" + workers.port() + " answers as this Dispatch");
                if (probe(workers.publicUrl()) == Answer.DISPATCH) {
                    run.add(Level.OK, "workers", "workers: " + workers.publicUrl() + " reaches this Dispatch");
                } else {
                    run.add(Level.WARN, "workers", "workers: " + workers.publicUrl()
                            + " does not reach this Dispatch; members' computers cannot connect until your tunnel or "
                            + "reverse proxy forwards it to 127.0.0.1:" + workers.port());
                }
            }
            case OTHER -> run.add(Level.WARN, "workers", "workers: something other than Dispatch answers on 127.0.0.1:"
                    + workers.port() + "; stop it or set another workers.port");
            case NONE -> run.add(Level.OK, "workers", "workers: nothing listens on 127.0.0.1:" + workers.port()
                    + " yet; it starts with dispatch run");
        }
    }

    /** What answered a worker request that carried no key. */
    private enum Answer { DISPATCH, OTHER, NONE }

    private Answer probe(String base) {
        HttpResponse<String> answer;
        try {
            answer = http.send(HttpRequest.newBuilder(URI.create(base + WorkerApi.PROJECTS)).timeout(PROBE_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | IllegalArgumentException e) {
            return Answer.NONE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Answer.NONE;
        }
        if (answer.statusCode() != 401) {
            return Answer.OTHER;
        }
        try {
            // Dispatch's own refusal; a proxy's 401 page is not JSON with this code, and no other server answers it.
            return Json.read(answer.body()).path("error").asText().equals("unauthorized") ? Answer.DISPATCH : Answer.OTHER;
        } catch (RuntimeException e) {
            return Answer.OTHER;
        }
    }
```

with imports for `dispatch.Json`, `dispatch.worker.WorkerApi`, `java.net.URI`, `java.net.http.HttpClient`, `java.net.http.HttpRequest` and `java.net.http.HttpResponse`.

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -B test -Dtest=ChecksTest+CheckCommandTest+OverviewApiTest`
Expected: PASS — a personal config's findings are unchanged (`gh` is still probed, a missing `claude` still FAILs), and the overview page shows the new lines as it shows every other finding.

- [ ] **Step 5: Commit**

```sh
git add -A && git commit -m "Check the workers block on the team machine

dispatch check now says whether anything answers on workers.port, whether it
is this Dispatch, and whether publicUrl reaches it; it stops asking for gh,
which only members' computers need, and a missing claude is a warning naming
✂️ splitting rather than a failure.

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 7: `dispatch check` on a member's computer

**Files:**
- Create: `src/main/java/dispatch/worker/WorkerChecks.java`
- Modify: `src/main/java/dispatch/cli/CheckCommand.java`
- Test: create `src/test/java/dispatch/worker/WorkerChecksTest.java`; modify `src/test/java/dispatch/cli/CheckCommandTest.java`

**Interfaces:**
- Consumes: `Checks.Finding`, `Checks.Level`, `WorkerConfigLoader.load(Path)`, `WorkerClient.setup()`, `WorkerClient.RevokedException`, `SecretsFile`, `ProjectProbe.of` / `ProjectProbe.sameRepo`, `Setup.claudeVersion`, `Setup.ghLoggedIn`.
- Produces: `public final class WorkerChecks` with `public static List<Checks.Finding> run(Path workerFile, Map<String,String> processEnvironment, Consumer<Checks.Finding> onEach)`.

- [ ] **Step 1: Write the failing test** — create `src/test/java/dispatch/worker/WorkerChecksTest.java`:

```java
package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.cli.Checks;
import dispatch.cli.SecretsFile;
import dispatch.testing.GitFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** dispatch check on a member's own computer, against a real WorkerApi. */
class WorkerChecksTest extends WorkerApiFixture {

    /** A command that exists on every OS and answers --version; `auth status` then fails, which is a warning. */
    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    /** A real repository, so a clone of it is recognised as this project's. */
    @Override
    String almRepo() {
        return repos.origin.toString();
    }

    @Test
    void aWorkingWorkerPassesAndNamesItsTeamPairingAndProjects() throws Exception {
        Path workerFile = writeWorker(pair(), repos.repo("alm").toString());

        List<Checks.Finding> findings = WorkerChecks.run(workerFile, Map.of(), finding -> { });

        assertFalse(Checks.failed(findings), findings.toString());
        assertTrue(findings.contains(new Checks.Finding(Checks.Level.OK, "team",
                "team: http://127.0.0.1:" + api.port() + " answers")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.area().equals("pairing")
                && f.message().startsWith("pairing: paired with ")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.area().equals("project alm") && f.level() == Checks.Level.OK),
                findings.toString());
    }

    @Test
    void aRevokedKeyIsAFailureThatSaysToPairAgain() throws Exception {
        String key = pair();
        Path workerFile = writeWorker(key, repos.repo("alm").toString());
        long workerId = db.transactionReturning(tx -> dispatch.store.Workers.ofMember(tx, BOLD.ref())).getFirst().id();
        keys.revoke(workerId, BOLD.ref(), false);

        List<Checks.Finding> findings = WorkerChecks.run(workerFile, Map.of(), finding -> { });

        assertTrue(findings.contains(new Checks.Finding(Checks.Level.FAIL, "pairing",
                "pairing: this computer's key is not valid any more; pair again: dispatch worker init")),
                findings.toString());
        assertTrue(Checks.failed(findings));
    }

    @Test
    void aProjectTheTeamHasButThisComputerDoesNotIsAFailure() throws Exception {
        Path workerFile = writeWorker(pair(), null);

        List<Checks.Finding> findings = WorkerChecks.run(workerFile, Map.of(), finding -> { });

        assertTrue(findings.contains(new Checks.Finding(Checks.Level.FAIL, "project alm",
                "project alm: the team has this project, but this computer does not; run: dispatch worker init")),
                findings.toString());
    }

    @Test
    void aCloneOfTheWrongRepositoryIsAWarning() throws Exception {
        Path other = dir.resolve("elsewhere");
        GitFixture.sh(dir, "git", "init", "--quiet", "-b", "main", other.toString());
        GitFixture.sh(other, "git", "remote", "add", "origin", "https://example.invalid/other.git");
        Path workerFile = writeWorker(pair(), other.toString());

        List<Checks.Finding> findings = WorkerChecks.run(workerFile, Map.of(), finding -> { });

        assertTrue(findings.stream().anyMatch(f -> f.area().equals("project alm") && f.level() == Checks.Level.WARN
                && f.message().contains("has origin https://example.invalid/other.git")), findings.toString());
    }

    @Test
    void anUnreachableTeamFailsOnceAndSkipsTheProjectList() throws Exception {
        Path workerFile = writeWorker(pair(), repos.repo("alm").toString());
        api.close();

        List<Checks.Finding> findings = WorkerChecks.run(workerFile, Map.of(), finding -> { });

        assertTrue(findings.stream().anyMatch(f -> f.area().equals("team") && f.level() == Checks.Level.FAIL
                && f.message().startsWith("team: cannot reach ")), findings.toString());
        assertEquals(0, findings.stream().filter(f -> f.area().startsWith("project ")).count(),
                "without the team's list there is nothing to compare against: " + findings);
    }

    /** worker.yaml and worker.env as `dispatch worker init` writes them; {@code clone} null maps no project at all. */
    private Path writeWorker(String key, String clone) throws Exception {
        Path workerFile = dir.resolve("config/worker.yaml");
        Files.createDirectories(workerFile.getParent());
        StringBuilder yaml = new StringBuilder("team: 'http://127.0.0.1:" + api.port() + "'\nname: 'ann-laptop'\n")
                .append("stateDir: '").append(dir.resolve("state/worker")).append("'\n")
                .append("claudeCommand: '").append(JAVA).append("'\n")
                .append("ghCommand: '").append(JAVA).append("'\n");
        if (clone != null) {
            yaml.append("projects:\n  alm:\n    path: '").append(clone).append("'\n");
        }
        Files.writeString(workerFile, yaml.toString());
        SecretsFile.write(SecretsFile.beside(workerFile), Map.of(WorkerCommand.KEY_VARIABLE, key));
        return workerFile;
    }
}
```

`WorkerApiFixture`'s config must have `alm` among Bold's projects for `/api/worker/projects` to list it; it already does (its `config()` and `groups()` carry `alm`). If `WorkerKeys.revoke`'s signature differs, use the one `WorkerKeysTest` uses.

Add to `src/test/java/dispatch/cli/CheckCommandTest.java`:

```java
    @Test
    void aComputerWithOnlyAWorkerYamlIsNotToldToRunDispatchInit() throws IOException {
        Files.writeString(config.resolveSibling("worker.yaml"),
                "team: 'https://team.example.invalid'\nname: 'ann-laptop'\n");

        int exit = check();

        assertEquals(1, exit, terminal.output());
        assertFalse(terminal.output().contains("create one with: dispatch init"), terminal.output());
        assertTrue(terminal.output().contains("worker: ann-laptop"), terminal.output());
        assertTrue(terminal.output().contains("FAIL pairing: no worker key"), terminal.output());
    }
```

using the file's own `check()` helper, `terminal` and `config` fields; no `dispatch.yaml` is written, so only the worker's side runs.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -B test -Dtest=WorkerChecksTest+CheckCommandTest`
Expected: FAIL — compilation error `cannot find symbol: class WorkerChecks`.

- [ ] **Step 3: Write the worker's checks.** Create `src/main/java/dispatch/worker/WorkerChecks.java`:

```java
package dispatch.worker;

import dispatch.Redactor;
import dispatch.cli.Checks;
import dispatch.cli.CliException;
import dispatch.cli.ProjectProbe;
import dispatch.cli.SecretsFile;
import dispatch.cli.Setup;
import dispatch.config.ConfigException;
import dispatch.workspace.Git;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Whether this computer can run its owner's tasks, before it tries: {@code worker.yaml}, the key in
 * {@code worker.env}, the team machine answering with that key, Claude Code, the GitHub CLI, and one clone per
 * project the team says the member has. Produces the same {@link Checks.Finding}s as the team machine's checks, so
 * `dispatch check` prints both the same way (ADR 0021).
 */
public final class WorkerChecks {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private WorkerChecks() {
    }

    /** @param onEach sees each finding as soon as it is known, as {@link Checks#run} does */
    public static List<Checks.Finding> run(Path workerFile, Map<String, String> processEnvironment,
                                           Consumer<Checks.Finding> onEach) {
        List<Checks.Finding> findings = new ArrayList<>();
        Redactor redactor = Redactor.fromEnvironment(processEnvironment);
        Consumer<Checks.Finding> add = finding -> {
            Checks.Finding redacted = new Checks.Finding(finding.level(), finding.area(),
                    redactor.redact(finding.message()));
            findings.add(redacted);
            onEach.accept(redacted);
        };
        WorkerConfig config;
        try {
            config = WorkerConfigLoader.load(workerFile);
        } catch (ConfigException | CliException e) {
            add.accept(new Checks.Finding(Checks.Level.FAIL, "worker", "worker: " + e.getMessage()));
            return findings;
        }
        add.accept(new Checks.Finding(Checks.Level.OK, "worker",
                "worker: " + config.name() + ", team " + config.team() + " (" + workerFile + ")"));
        Optional<WorkerClient.Setup> team = checkPairing(workerFile, config, processEnvironment, add);
        checkClaude(config, add);
        checkGh(config, add);
        checkProjects(config, team, add);
        return findings;
    }

    /**
     * The key, and what the team machine says about it. One real call decides all three cases: unreachable, revoked,
     * or paired — nothing about the files alone can tell those apart.
     */
    private static Optional<WorkerClient.Setup> checkPairing(Path workerFile, WorkerConfig config,
                                                             Map<String, String> processEnvironment,
                                                             Consumer<Checks.Finding> add) {
        Path envFile = SecretsFile.beside(workerFile);
        String key;
        try {
            key = SecretsFile.environment(workerFile, processEnvironment).get(WorkerCommand.KEY_VARIABLE);
        } catch (CliException e) {
            add.accept(new Checks.Finding(Checks.Level.FAIL, "pairing", "pairing: " + e.getMessage()));
            return Optional.empty();
        }
        if (key == null) {
            add.accept(new Checks.Finding(Checks.Level.FAIL, "pairing",
                    "pairing: no worker key in " + envFile + "; run: dispatch worker init"));
            return Optional.empty();
        }
        WorkerClient client = new WorkerClient(HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build(),
                URI.create(config.team()), key);
        try {
            WorkerClient.Setup setup = client.setup();
            add.accept(new Checks.Finding(Checks.Level.OK, "team", "team: " + config.team() + " answers"));
            add.accept(new Checks.Finding(Checks.Level.OK, "pairing",
                    "pairing: paired with " + setup.team() + " as " + config.name()));
            return Optional.of(setup);
        } catch (WorkerClient.RevokedException e) {
            add.accept(new Checks.Finding(Checks.Level.OK, "team", "team: " + config.team() + " answers"));
            add.accept(new Checks.Finding(Checks.Level.FAIL, "pairing",
                    "pairing: this computer's key is not valid any more; pair again: dispatch worker init"));
            return Optional.empty();
        } catch (RuntimeException e) {
            add.accept(new Checks.Finding(Checks.Level.FAIL, "team",
                    "team: cannot reach " + config.team() + " (" + e.getMessage() + ")"));
            return Optional.empty();
        }
    }

    private static void checkClaude(WorkerConfig config, Consumer<Checks.Finding> add) {
        Optional<String> version = Setup.claudeVersion(config.claudeCommand());
        add.accept(version
                .map(line -> new Checks.Finding(Checks.Level.OK, "claude", "claude: " + line))
                .orElseGet(() -> new Checks.Finding(Checks.Level.FAIL, "claude", "claude: cannot run "
                        + config.claudeCommand() + "; install Claude Code, or set claudeCommand to its full path")));
    }

    private static void checkGh(WorkerConfig config, Consumer<Checks.Finding> add) {
        add.accept(Setup.ghLoggedIn(config.ghCommand())
                ? new Checks.Finding(Checks.Level.OK, "gh", "gh: logged in")
                : new Checks.Finding(Checks.Level.WARN, "gh", "gh: not logged in or not installed ("
                        + config.ghCommand() + "); pull requests will fail until you run: gh auth login"));
    }

    /**
     * One finding per project: the team's list decides what must be here, this computer's config decides where. When
     * the team could not be reached there is no list to compare against, so only what is mapped here is checked.
     */
    private static void checkProjects(WorkerConfig config, Optional<WorkerClient.Setup> team,
                                      Consumer<Checks.Finding> add) {
        if (team.isEmpty()) {
            return;
        }
        Git git = new Git("git", null, Duration.ofSeconds(30));
        Set<String> known = new HashSet<>();
        for (WorkerClient.ProjectInfo project : team.get().projects()) {
            known.add(project.name());
            String area = "project " + project.name();
            WorkerConfig.Project mine = config.projects().get(project.name());
            if (mine == null) {
                add.accept(new Checks.Finding(Checks.Level.FAIL, area, area
                        + ": the team has this project, but this computer does not; run: dispatch worker init"));
                continue;
            }
            Path path = Path.of(mine.path());
            if (!Files.exists(path.resolve(".git"))) {
                add.accept(new Checks.Finding(Checks.Level.FAIL, area, area + ": no git clone at " + path
                        + "; run: dispatch worker init"));
                continue;
            }
            ProjectProbe probe;
            try {
                probe = ProjectProbe.of(path, git);
            } catch (CliException e) {
                add.accept(new Checks.Finding(Checks.Level.FAIL, area, area + ": " + e.getMessage()));
                continue;
            }
            if (project.repo() != null && !probe.originHadCredentials()
                    && !ProjectProbe.sameRepo(probe.originUrl(), project.repo())) {
                add.accept(new Checks.Finding(Checks.Level.WARN, area, area + ": " + path + " has origin "
                        + probe.originUrl() + ", but the team's project is " + project.repo()));
                continue;
            }
            add.accept(new Checks.Finding(Checks.Level.OK, area, area + ": " + path));
        }
        config.projects().keySet().stream().filter(name -> !known.contains(name)).forEach(name ->
                add.accept(new Checks.Finding(Checks.Level.WARN, "project " + name, "project " + name
                        + ": this computer has it, but the team does not; remove it from worker.yaml")));
    }
}
```

- [ ] **Step 4: Run both sides from `dispatch check`.** Replace `src/main/java/dispatch/cli/CheckCommand.java`'s `run`:

```java
    /**
     * Checks whichever sides this machine has: a member's computer (worker.yaml beside the config file), the team's
     * own instance (dispatch.yaml), or both. A machine that only has a worker is never told to run dispatch init.
     */
    public int run(Path configFile, Map<String, String> processEnvironment) {
        Path workerFile = configFile.resolveSibling("worker.yaml");
        List<Checks.Finding> findings = new ArrayList<>();
        if (Files.exists(workerFile)) {
            findings.addAll(WorkerChecks.run(workerFile, processEnvironment, this::show));
        }
        if (Files.exists(configFile) || findings.isEmpty()) {
            findings.addAll(checks.run(configFile, processEnvironment, this::show));
        }
        return Checks.failed(findings) ? 1 : 0;
    }

    private void show(Checks.Finding finding) {
        switch (finding.level()) {
            case OK -> terminal.ok(finding.message());
            case WARN -> terminal.warn(finding.message());
            case FAIL -> terminal.fail(finding.message());
        }
    }
```

with imports for `dispatch.worker.WorkerChecks`, `java.nio.file.Files`, `java.util.ArrayList` and `java.util.List`.

- [ ] **Step 5: Run it to see it pass**

Run: `./mvnw -q -B test -Dtest=WorkerChecksTest+CheckCommandTest+ChecksTest`
Expected: PASS — a machine with only `dispatch.yaml` behaves exactly as before.

- [ ] **Step 6: Commit**

```sh
git add -A && git commit -m "Cover a member's computer in dispatch check

With worker.yaml beside the config, dispatch check reports the worker's own
side: the config, the key, what the team machine says about it (paired,
revoked or unreachable), claude, gh, and one line per project — missing
here, not a clone, a clone of another repository, or fine. A computer with
only a worker is no longer told to run dispatch init.

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 8: A team can be set up in the browser again

**Files:**
- Modify: `src/main/java/dispatch/ui/SetupApi.java` (`write` ~265-315)
- Modify: `ui/src/api.ts`, `ui/src/setup/SetupPage.tsx`, `ui/src/setup/PeopleStep.tsx`, `ui/src/setup/SummaryStep.tsx`
- Test: `src/test/java/dispatch/ui/SetupApiTest.java`, `ui/src/setup/PeopleStep.test.tsx`, `ui/src/setup/SummaryStep.test.tsx`

**Interfaces:**
- Consumes: `Setup.Answers(String team, boolean shared, List<Config.Member> members, Setup.Chat chat, Config.Workers workers, String claude, List<ProjectAddCommand.Project> projects, String authorName, String authorEmail, Setup.Advanced advanced)`, `ConfigLoader.isWorkerUrl(String)`.
- Produces:
  - `/api/setup/write` accepts `{"teamName": string, "workers": {"publicUrl": string, "port": number}, …}` and writes a team config
  - `ui/src/api.ts`: `export interface WorkersChoice { publicUrl: string; port: number }` and `SetupPayload.workers?: WorkersChoice`
  - `Draft.workers?: WorkersChoice` in `ui/src/setup/SetupPage.tsx`

- [ ] **Step 1: Write the failing tests.** In `src/test/java/dispatch/ui/SetupApiTest.java`, replace `writeRefusesATeamSetupEarlyBeforeAnythingIsWritten` with:

```java
    @Test
    void aTeamSetupIsWrittenWithItsGroupAndItsWorkersBlock() throws Exception {
        call("/api/setup/team", "{\"team\":true}");
        call("/api/setup/token", "{\"token\":\"" + TOKEN + "\"}");
        confirmTwoTeamMembers();
        telegram.pushUpdate(botAddedTo(3, -1001234567890L, "Backend"));
        call("/api/setup/group/next", "{}");

        call("/api/setup/write", teamWrite("{\"publicUrl\":\"https://team.example.com\",\"port\":7880}"));

        Config written = ConfigLoader.load(config, Map.of("TELEGRAM_BOT_TOKEN", TOKEN));
        assertTrue(written.isTeam(), "a team config, with its group's chat");
        assertEquals(-1001234567890L, written.telegram().groups().getFirst().chatId());
        assertEquals("https://team.example.com", written.workers().publicUrl());
        assertEquals(7880, written.workers().port());
        assertEquals("backend", written.team(), "the page's team name, not the first member's");
    }

    @Test
    void aTeamWithAGroupNeedsAUsableWorkersBlock() throws Exception {
        call("/api/setup/team", "{\"team\":true}");
        call("/api/setup/token", "{\"token\":\"" + TOKEN + "\"}");
        confirmTwoTeamMembers();
        telegram.pushUpdate(botAddedTo(3, -1001234567890L, "Backend"));
        call("/api/setup/group/next", "{}");

        CliException missing = assertThrows(CliException.class, () -> call("/api/setup/write", teamWrite(null)));
        CliException wrong = assertThrows(CliException.class,
                () -> call("/api/setup/write", teamWrite("{\"publicUrl\":\"http://team.example.com\",\"port\":7880}")));

        assertTrue(missing.getMessage().contains("workers.publicUrl is needed"), missing.getMessage());
        assertTrue(wrong.getMessage().contains("must start with https://"), wrong.getMessage());
        assertFalse(Files.exists(config), "nothing was written");
        assertFalse(Files.exists(SecretsFile.beside(config)), "not even the secrets file");
    }

    @Test
    void aTeamWithoutAGroupNeedsNoWorkersBlock() throws Exception {
        call("/api/setup/team", "{\"team\":true}");
        call("/api/setup/token", "{\"token\":\"" + TOKEN + "\"}");
        confirmTwoTeamMembers();

        call("/api/setup/write", teamWrite(null));

        Config written = ConfigLoader.load(config, Map.of("TELEGRAM_BOT_TOKEN", TOKEN));
        assertNull(written.workers(), "no group chat, so nothing runs on members' computers yet");
    }

    /** @param workers the JSON of the workers block, or null to leave it out */
    private String teamWrite(String workers) {
        return "{\"teamName\":\"backend\",\"claude\":\"" + json(JAVA) + "\",\"authorName\":\"a\","
                + "\"authorEmail\":\"a@example.com\"," + (workers == null ? "" : "\"workers\":" + workers + ",")
                + "\"projects\":[{\"folder\":\"" + json(repos.repo("alm").toString())
                + "\",\"name\":\"alm\",\"baseBranch\":\"main\"}]}";
    }
```

with `import static org.junit.jupiter.api.Assertions.assertNull;`.

In `ui/src/setup/PeopleStep.test.tsx` add (the file already mocks `../api` and has `state`, `draft` and `props`):

```tsx
test("a team with a group is asked where members' computers reach this machine", async () => {
  vi.mocked(api.nextPerson).mockReturnValue(new Promise(() => {}));
  vi.mocked(api.nextGroup).mockResolvedValue({ group: { id: -100, title: "Backend" } });
  const update = vi.fn();
  const next = vi.fn();
  const team = { ...state, team: true, members: [{ id: 100, name: "Bold Bat" }] };

  render(<PeopleStep {...props} state={team} update={update} next={next} refresh={vi.fn()} />);
  fireEvent.click(screen.getByRole("button", { name: "Done adding teammates" }));

  expect(await screen.findByText("Group: Backend")).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText("Public URL"), { target: { value: " https://team.example.com " } });
  fireEvent.click(screen.getByRole("button", { name: "Next" }));

  expect(update).toHaveBeenCalledWith({
    teamName: "Backend",
    workers: { publicUrl: "https://team.example.com", port: 7880 },
  });
  expect(next).toHaveBeenCalled();
});

test("a public URL that is not https keeps the team from moving on", async () => {
  vi.mocked(api.nextPerson).mockReturnValue(new Promise(() => {}));
  vi.mocked(api.nextGroup).mockResolvedValue({ group: { id: -100, title: "Backend" } });
  const next = vi.fn();
  const team = { ...state, team: true, members: [{ id: 100, name: "Bold Bat" }] };

  render(<PeopleStep {...props} state={team} next={next} refresh={vi.fn()} />);
  fireEvent.click(screen.getByRole("button", { name: "Done adding teammates" }));
  fireEvent.change(await screen.findByLabelText("Public URL"), { target: { value: "team.example.com" } });

  expect(screen.getByText(/https:\/\//)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
  expect(next).not.toHaveBeenCalled();
});
```

In `ui/src/setup/SummaryStep.test.tsx` add:

```tsx
test("a team's summary shows the workers block and sends it", async () => {
  const write = vi.mocked(api.writeSetup).mockResolvedValue({ configFile: "/x/dispatch.yaml", secretsFile: "/x/dispatch.env" });
  const teamDraft = { ...draft, teamName: "backend", workers: { publicUrl: "https://team.example.com", port: 7880 } };

  render(<SummaryStep state={{ ...state, team: true }} draft={teamDraft} back={vi.fn()} onDone={vi.fn()} />);

  expect(screen.getByText("https://team.example.com (port 7880)")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Write this setup" }));

  await vi.waitFor(() => expect(write).toHaveBeenCalledWith(expect.objectContaining({
    teamName: "backend", workers: { publicUrl: "https://team.example.com", port: 7880 },
  })));
});
```

matching that file's own `state`, `draft` and mock setup.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -B test -Dtest=SetupApiTest` and `cd ui && npx vitest run`
Expected: FAIL — the Java tests hit `dispatch ui cannot set up a team yet`, and vitest cannot find the `Public URL` field.

- [ ] **Step 3: Let the server write a team.** In `src/main/java/dispatch/ui/SetupApi.java`, `write(...)`, replace the SHORTCUT block and the answers it builds:

```java
                if (!team && members.size() > 1) {
                    // Defense in depth alongside team()'s own guard: a personal config must never silently carry
                    // teammates added while this was a team setup.
                    throw new CliException(PERSONAL_BOT_ONE_MEMBER);
                }
                List<ProjectAddCommand.Project> projects = projects(body.path("projects"));
                // A team names itself; a personal bot is named after its only member, as dispatch init does.
                String name = Setup.teamName(team ? text(body, "teamName")
                        : members.getFirst().name().split("\\s+")[0]);
                // Only a group chat makes this a team config, and only then does ConfigLoader want workers: without
                // one, nobody announces and nothing runs on a member's computer yet (ADR 0021).
                Config.Workers workers = team && chat != null ? workers(body.path("workers")) : null;
                Setup.Answers answers = new Setup.Answers(name, team, List.copyOf(members), team ? chat : null, workers,
                        text(body, "claude"), projects, text(body, "authorName"), text(body, "authorEmail"),
                        advanced(body.path("advanced")));
```

and add the validator next to `advanced(...)`:

```java
    /**
     * Where members' computers reach this machine (ADR 0021). Checked here, with the page's own words, rather than
     * left to the config loader's file-shaped message after the file is already being written.
     */
    private static Config.Workers workers(JsonNode body) {
        String publicUrl = optionalText(body, "publicUrl");
        if (publicUrl == null) {
            throw new CliException("workers.publicUrl is needed: the https URL your teammates' computers reach this "
                    + "machine on, through your tunnel or reverse proxy");
        }
        if (!ConfigLoader.isWorkerUrl(publicUrl)) {
            throw new CliException("workers.publicUrl must start with https:// (plain http only for 127.0.0.1)");
        }
        JsonNode port = body.path("port");
        if (!port.isIntegralNumber() || port.asInt() < 1 || port.asInt() > 65535) {
            throw new CliException("workers.port must be a whole number from 1 to 65535");
        }
        return new Config.Workers(publicUrl, port.asInt());
    }
```

`ConfigLoader` is already imported. Keep the `PERSONAL_BOT_ONE_MEMBER` constant: both guards above still use it. This also settles W-3's last nit here — past the removed refusal `team` was always `false`, so `Setup.Answers` was handed a constant; it now carries the answer the page actually gave.

- [ ] **Step 4: Ask for it in the browser.** In `ui/src/api.ts`:

```ts
/** Where members' computers reach the team machine; only a team with a group chat needs it. */
export interface WorkersChoice {
  publicUrl: string;
  port: number;
}

export interface SetupPayload {
  teamName: string | null;
  claude: string;
  authorName: string;
  authorEmail: string;
  projects: ProjectChoice[];
  workers?: WorkersChoice;
  advanced?: SetupAdvanced;
}
```

In `ui/src/setup/SetupPage.tsx`, add to `Draft`:

```tsx
import type { ProjectChoice, SetupAdvanced, WorkersChoice } from "../api";
```

```tsx
  teamName: string;
  /** A team with a group chat: where its members' computers reach this machine. */
  workers?: WorkersChoice;
```

In `ui/src/setup/PeopleStep.tsx`, add the fields to the group phase. After `const teamName = …`:

```tsx
  const [publicUrl, setPublicUrl] = useState(draft.workers?.publicUrl ?? "");
  const [port, setPort] = useState(draft.workers?.port ?? 7880);
  // A group chat is what makes this a team whose members run their own tasks (ADR 0021); without one, nothing here.
  const needsWorkers = state.team && !!groupTitle;
  const urlOk = /^https:\/\/\S+$/.test(publicUrl.trim()) || /^http:\/\/127\.0\.0\.1(:\d+)?$/.test(publicUrl.trim());
```

Render them right after the group block, before the team name field:

```tsx
      {needsWorkers && (
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Typography.Paragraph type="secondary">
            Each teammate's tasks run on their own computer, which reaches this one through your tunnel or reverse proxy.
          </Typography.Paragraph>
          <label>
            <Typography.Text>Public URL</Typography.Text>
            <Input value={publicUrl} placeholder="https://team.example.com" aria-label="Public URL"
                   onChange={(e) => setPublicUrl(e.target.value)} />
          </label>
          {publicUrl.trim() !== "" && !urlOk && (
            <Typography.Text type="danger">Use an https:// URL (or http://127.0.0.1 for a local test).</Typography.Text>
          )}
          <label>
            <Typography.Text>Port</Typography.Text>
            <InputNumber min={1} max={65535} value={port} aria-label="Port"
                         onChange={(value) => setPort(value ?? 7880)} />
          </label>
        </Space>
      )}
```

and make Next carry them:

```tsx
        <Button type="primary" disabled={!you || (state.team && !teamName.trim()) || (needsWorkers && !urlOk)}
                onClick={() => {
                  if (state.team) {
                    update({
                      teamName: teamName.trim(),
                      ...(needsWorkers ? { workers: { publicUrl: publicUrl.trim(), port } } : {}),
                    });
                  }
                  next();
                }}>
          Next
        </Button>
```

Add `InputNumber` to the antd import.

In `ui/src/setup/SummaryStep.tsx`, show it and send it:

```tsx
        {state.team && draft.workers && (
          <Descriptions.Item label="Workers">{`${draft.workers.publicUrl} (port ${draft.workers.port})`}</Descriptions.Item>
        )}
```

```tsx
    const result = await writing.run(() => writeSetup({
      teamName: state.team ? draft.teamName : null,
      claude: draft.claude,
      authorName: draft.authorName,
      authorEmail: draft.authorEmail,
      projects: draft.projects,
      ...(draft.workers ? { workers: draft.workers } : {}),
      ...(draft.advanced ? { advanced: draft.advanced } : {}),
    }));
```

- [ ] **Step 5: Run it to see it pass**

Run: `cd ui && npx vitest run && npx tsc --noEmit`
Expected: PASS.

Run: `./mvnw -q -B -Pui verify`
Expected: PASS — the whole Java suite plus the bundled UI build.

- [ ] **Step 6: Commit**

```sh
git add -A && git commit -m "Set a team up in the browser again

W-3 made a workers block mandatory for a team config and left dispatch ui
refusing team setups because its page could not produce one. The page now
asks for the public URL and port beside the group it just found, and
SetupApi writes the group, the page's team name and the workers block
through to Setup.render. A team without a group chat still needs neither.

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

### Task 9: ADR 0021 and the documentation

**Files:**
- Create: `docs/adr/0021-team-members-tasks-run-on-their-own-computers.md`
- Modify: `README.md`, `SECURITY.md`, `docs/ARCHITECTURE.md`, `docs/superpowers/specs/2026-09-22-team-workers-design.md`
- Modify (citations only): `src/main/java/dispatch/config/Config.java`, `src/main/java/dispatch/App.java`, `src/main/java/dispatch/cli/InitCommand.java`, `src/main/java/dispatch/cli/Setup.java`, `src/test/java/dispatch/config/ConfigLoaderTest.java`

**Interfaces:**
- Consumes: nothing; documentation and comments only.
- Produces: ADR 0021, and five comments that cite it instead of ADR 0020.

- [ ] **Step 1: Write ADR 0021.** Read `docs/adr/0020-members-see-only-the-headline-of-each-others-tasks.md` and `docs/adr/0018-a-local-web-ui-served-by-dispatch-ui.md` first and follow their shape (a title, the decision in prose, then `## Consequences`). Create `docs/adr/0021-team-members-tasks-run-on-their-own-computers.md`:

```markdown
# Team members' tasks run on their own computers

A team keeps one bot, one group and one queue on the team machine, but nothing of a member's task runs there. The team machine coordinates: the bot, the store, the scheduler, the outbox and the worker API. Each member runs `dispatch worker run` on their own computer, which polls the team machine over HTTPS and does the machine work — the worktree, Claude Code, `git` and `gh` — with their own login, their own clones and their own credentials.

A worker connects out, so a laptop behind NAT needs no inbound address: the team's owner publishes one URL (`workers.publicUrl`) through a tunnel, a reverse proxy or a private network, and Dispatch itself listens only on `127.0.0.1:<workers.port>`. A member pairs their computer with a one-time code from `/worker` in the bot's private chat, which is exchanged for a 256-bit worker key bound to that member; the team machine keeps only its SHA-256, and `/worker revoke` ends it. `dispatch worker init` does the whole setup: pair, map each of the member's projects to a clone here or clone it, check Claude Code and the GitHub CLI, write `worker.yaml` and `worker.env`, and install the `dispatch-worker` background service.

The team machine therefore holds no Claude or GitHub credentials, no transcripts and no code. It holds the queue, the headlines, and the plan and result texts it relays to Telegram — which its owner can read in its database — plus each attachment until the worker that needs it fetches it.

A run is given only to a worker of its requester. A task that already has a worktree goes back to the computer that holds it, and waits while that computer is offline or busy. A lease expires 60 s after the last progress report: the run fails as `INTERRUPTED`, exactly as a restart mid-run does, and `/retry N` continues it on the same computer, worktree intact.

We chose this because every member pays for their own Claude usage and their code, sessions and credentials never leave their machine — which one shared machine with one team token could not give them. The rejected alternatives were workers that keep their own task state with the team machine as a relay (team views would be rebuilt from reports and could drift), workers talking through the bot (Telegram delivers a bot's updates to one reader only) and SSH to the team machine (every member would need an account there).

## Consequences

- A member with no computer connected has their tasks wait; they are told once, and `/status` shows it. There is no fallback to the team machine, because it has no credentials to run them with.
- The team machine still needs `claude` for splitting a message (✂️, ADR 0013), which has no requester's computer to run on; `dispatch check` warns rather than fails when it is missing there, and never asks for `gh`.
- A team config without a `workers` block is refused once a group has a chat: an existing team must add one before starting this version.
- Two computers for one member are allowed: the first to poll takes a new task, and a task's follow-up or retry goes to the one holding its worktree.
- Each computer sweeps its own worktrees after 7 days, keeping anything with uncommitted changes or unpushed commits; nothing on the team machine can reach them.
- Revoking a key stops that computer from taking or reporting any further work. It does not reach back into what that computer already has: its clones, worktrees and Claude sessions stay where they are, and its agent finishes the run it was carrying (its report is then refused).
```

- [ ] **Step 2: Retarget the five ADR 0020 citations.** These cite ADR 0020 for *where tasks run*, which is ADR 0021's decision; every other ADR 0020 citation (privacy) stays as it is. Change exactly:

- `src/main/java/dispatch/config/Config.java` (~line 28): `…so tasks belong to different people and run on their own computers (ADR 0020).` → `(ADR 0021).`
- `src/main/java/dispatch/App.java` (~line 94): `…each member's own computer does (ADR 0020).` → `(ADR 0021).`
- `src/main/java/dispatch/cli/InitCommand.java` (~line 226): `…once there is a group to announce to (ADR 0020): their tasks run there.` → `(ADR 0021)`.
- `src/main/java/dispatch/cli/Setup.java` (~line 69): `…a team's tasks run on members' own computers (ADR 0020)` → `(ADR 0021)`.
- `src/test/java/dispatch/config/ConfigLoaderTest.java` (~line 419): `…which makes it a team (ADR 0020)` → `(ADR 0021)`.

Confirm with `grep -rn "ADR 0020" src` that what is left is only the privacy citations in `TaskService`, `Statistics`, `Renderer` and `UpdateHandler`.

- [ ] **Step 3: Update the docs.** In `README.md`, extend the **Workers** bullet under "A bot for your team" (do not duplicate what W-3 wrote there) and add a member's section after it:

````markdown
- **Workers:** once a group has a chat, each member's tasks run on their own computer, not this machine's: `workers.publicUrl` and `workers.port` are then required (`dispatch init` and `dispatch ui` both ask for them; see `deploy/example.yaml`). Dispatch listens only on `127.0.0.1:<port>`; publish `publicUrl` in front of it with a tunnel, a reverse proxy or a private network. This machine needs no `claude` for tasks and no `gh` at all — only members' computers do. **Upgrading an existing team config:** add a `workers` block before starting this version, or Dispatch refuses to start. Send `/worker` in the bot's private chat for a pairing code, and again to list or revoke your computers.

### Your own computer in a team

Your tasks run where your Claude Code login, your clones and your `gh` are: on your own machine. Once:

```sh
dispatch worker init      # asks for the team URL and a code from /worker, then sets everything up
```

It pairs this computer, fetches the projects your team has for you, maps each one to a clone you already have (or clones it), asks for a model and effort per project if you want your own, checks `claude --version` and `gh auth status`, writes `worker.yaml` and an owner-only `worker.env`, and offers to keep it running in the background. After that:

```sh
dispatch check                    # the whole computer: pairing, the team, claude, gh, each project's clone
dispatch worker run               # run in this terminal instead of the background
dispatch worker service status    # install | start | stop | status | uninstall
```

Tasks you give the bot wait until this computer is connected, and continue on it after a restart. Nothing of your code, your Claude sessions or your credentials reaches the team machine — see SECURITY.md. Worktrees of tasks nothing has touched for a week are removed here automatically; anything with uncommitted changes or unpushed commits is kept.
````

In `SECURITY.md`, under **Boundaries**, add the worker key model:

```markdown
- In a team, a member's tasks run on that member's own computer (ADR 0021), so the boundary is each member's own OS account, as for a personal instance. **The team machine holds:** the bot token, the queue, the headlines, the plan and result texts it relays to Telegram — its owner can read those in its database — and each attachment until the worker fetches it, plus the SHA-256 of every worker key. **A member's computer holds:** their worker key (in `worker.env`, mode 600), their Claude Code login and transcripts, their clones, worktrees and `gh` credentials. Neither the key nor anything derived from it reaches the other members. **Revoking a key** (`/worker revoke`, or removing the member) stops that computer from taking any further work and refuses whatever it reports afterwards with 401. It does not reach back into that computer: its clones, worktrees, Claude sessions and any output already on disk stay there, and the run it was carrying finishes locally before its report is refused. Treat a lost laptop as a lost checkout of everything it was working on.
- A worker key travels on every worker request, so `workers.publicUrl` must be `https://` (plain HTTP only from `127.0.0.1`, for local tests). The worker endpoints are a separate server from `dispatch ui` and share no session with it; a worker only ever receives its own member's jobs, and may only report a run it holds the lease for.
```

In `docs/ARCHITECTURE.md`, five edits (the text below is the file's Markdown, to paste as it is):

````markdown
<!-- line ~408: "needs neither key" is about two settings, not a key; and W-4 is no longer a promise -->
A personal bot needs neither setting; its jobs run in this process. … A member sets their computer up with `dispatch worker init` and keeps it running as the `dispatch-worker` service (ADR 0021); `dispatch check` covers both sides, and the key model is in [SECURITY.md](../SECURITY.md).

<!-- the `cli` row of the package table: add to the list of commands -->
`worker init` (pair this computer, map its projects, install its service), `worker pair`, `worker run`, `worker service`

<!-- the `core` row, after "`Sweeper` removes idle worktrees" -->
— in a team those worktrees are on members' computers, where `dispatch.worker.WorkerSweeper` does the same job.

<!-- under "The worker boundary", after the `JobRunner` paragraph -->
In a team the same `JobRunner` runs on the member's computer inside `WorkerLoop`, and `RemoteWorkers` is the `Worker` on this side: it parks the job until one of the requester's computers takes it, keeps the 60 s lease, and answers with the `JobResult` that computer reports (ADR 0021).

<!-- two rows for the failure table -->
| A member's computer is offline | Their tasks stay queued; the requester is told once; they start when it connects. A task with a worktree waits for the computer that holds it, and while that computer is busy. |
| A worker stops reporting for 60 s | The run is FAILED `INTERRUPTED`; the worktree stays on that computer and `/retry N` continues it there. |
````

In the Milestones table, add the W-4 line to the team-workers row (or a `**M4** team workers (built)` row alongside the existing ones, in the file's own style), naming `dispatch worker init`, the `dispatch-worker` service and `dispatch check`.

In `docs/superpowers/specs/2026-09-22-team-workers-design.md`, mark W-4's row as delivered in the milestone table, and change the Worker setup section's step 5 to name the service as `dispatch-worker` (it already does) — otherwise leave the spec's text as the record of what was agreed.

- [ ] **Step 4: Check the docs against the code**

Run: `grep -rn "ADR 0020" src && grep -rn "W-4's" docs/ARCHITECTURE.md && grep -rn "worker init" README.md docs/ARCHITECTURE.md`
Expected: only privacy citations left for ADR 0020, no "W-4's" promise left in ARCHITECTURE, and `worker init` documented in both files.

Run: `./mvnw -q -B verify`
Expected: PASS — the comment changes compile and nothing else moved.

- [ ] **Step 5: Commit**

```sh
git add -A && git commit -m "Record ADR 0021 and document the worker setup

ADR 0021 states where a team's tasks run, how a computer pairs and what
revoking does and does not reach. README gains a member's guide to
dispatch worker init/run/check and extends the team owner's workers bullet;
SECURITY.md states the key model on both machines; ARCHITECTURE describes
the remote worker, the worker sweeper and the two new failure rows, and the
five code comments that cited ADR 0020 for this decision now cite 0021.

Claude-Session: https://claude.ai/code/session_015fG5eDykx622Y3Mp2bA28z"
```

---

## Done when

- `./mvnw -q -B verify` passes, and `cd ui && npx vitest run` plus `./mvnw -q -B -Pui verify` pass for Task 8.
- On the team machine: `dispatch check` reports the `workers` block, asks for no `gh`, and only warns about a missing `claude`; `dispatch ui` sets a team up again.
- On a member's computer: `dispatch worker init` pairs, maps or clones every project, writes `worker.yaml` and `worker.env`, installs `dispatch-worker`, and `dispatch check` reports that computer's own state.
- The live run the spec asks for: a team bot with two members' computers paired through a tunnel, each running a task with its own Claude login, with `dispatch worker service` keeping both alive.
