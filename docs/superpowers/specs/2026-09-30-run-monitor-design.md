# Run monitor (RM): watch and steer an execution from the Mini App

Status: approved design, 2026-09-30 (the clickable mockup ZB approved: https://claude.ai/artifact/V58JmXWanX7FgkC643u1pe).
Builds on the verify loop (ADR 0033).

## Goal

An execution runs on its own, as it does now, and the requester can watch it and step in from the Mini App while it
runs. The requester sees which step is running and how long each step took: implement, test, fix, review, deliver.
They can skip the step that is running now, deliver now, pause before the review, cancel, and continue the task's
agent session in their own terminal. The loop stays fully automatic unless someone touches it.

In scope:
- Each execution step recorded as it starts and ends, on this machine and on team workers.
- A Mini App view of a running task that refreshes itself.
- Four controls: ⏭ skip the current step, 📦 deliver now, ⏸ pause before review (with a 15-minute automatic
  continue), and ⛔ cancel (as today).
- `dispatch teleport N` and Telegram's `/teleport N`.

Out of scope:
- **Telegram messages that update step by step.** ZB chose the Mini App for monitoring; the chat keeps its result
  message.
- **Controls on planning runs.** A plan run is one agent call, so ⛔ cancel already covers it. The monitor still shows
  it as one step.
- **Skipping the implementation or the delivery.** Cancel is how the implementation stops; the delivery is short and
  is what the other controls lead to.
- **A live cost for a running agent call.** Claude reports cost only at the end of a call, so the monitor shows the
  cost of the calls that finished.
- **Pausing anywhere other than before the review.**
- **Teleport for Codex and Gemini tasks.** They are refused with "not supported yet".

## Success criteria

1. While a task executes, the Mini App sheet shows each step with its state and time, and the running step's latest
   agent action, updated at most 3 s behind (10 s for a team worker, whose progress interval it is).
2. ⏭ on a running test, fix or review ends that step within seconds. The step is marked skipped, the loop goes on to
   what follows, and the Verification says it was skipped by the requester.
3. 📦 ends the running step and delivers what the worktree holds; the Verification says the requester delivered early.
4. With ⏸ on, the run stops after its tests and waits. 🔍 runs the review, 📦 delivers without it, and after
   15 minutes without an answer the review starts by itself.
5. Nothing changes for a task nobody touches: the same steps, the same result, the same cost.
6. `dispatch teleport 15` on the computer that ran task #15 opens Claude in its worktree on its latest session. It is
   refused while a run of the task is active.
7. Only the task's requester can use the controls and teleport; other members see nothing more than today.

## Steps

A step is one part of a run: `IMPLEMENT`, `TEST`, `FIX`, `REVIEW`, `DELIVER` (an execution) or `PLAN` (a planning
run). A step has:
- `n`, its 1-based place in the run;
- `kind` and `round`: the test run, fix or review number, counting from 1;
- `startedAt` and `endedAt`;
- `outcome`: `PASSED`, `FAILED`, `OK`, `FINDINGS`, `SKIPPED`, `STOPPED` or `DONE`;
- `detail`: the test tail, the review's findings or the error, cut to what the Verification keeps.

A new table `run_step (task_id, seq, n, kind, round, started_at, ended_at, outcome, detail)` holds them, keyed by
`(task_id, seq, n)`. `JobEvents` gains `stepStarted(n, kind, round)` and `stepEnded(n, outcome, detail)`:
- `JobRunner` sends them around the implementation, around its delivery, and for a plan run.
- `VerifyLoop` sends them around each test, fix and review, through its `Agents` interface.

A team worker cannot write the team machine's store. Its progress, sent every 10 s and at once when a step starts or
ends, gains a `loopSteps` list: the whole run's steps so far. The team machine replaces the run's rows with that list.
An older team machine ignores the field and an older worker sends none, so a mixed team works; it just shows no steps.

## Controls

A run's controls live on the `ActiveRun` beside its stop reason. They are also stored as columns on `run`
(`pause_before_review`, `skip_step`, `deliver_now`, `pause_choice`) so that the team machine can hand them to a worker
in its progress reply.

- **⏭ skip** names the step it skips, by `n`, so a tap that arrives after that step ended does nothing. For a test it
  stops the test process. For a fix or review it cancels only that agent call, as a timed-out loop call is cancelled
  today, and the loop moves on:
  - a skipped fix counts as not having fixed anything, and the loop goes to the review, or ends;
  - a skipped test leaves the tests `NOT_RUN` for that round, and the loop goes on;
  - a skipped review is `SKIPPED` and is not re-run.
- **📦 deliver now** cancels the running test, fix or review, as ⏭ does. It skips everything else and delivers. The
  Verification says `stopped: delivered early by the requester`.
- **⏸ pause before review** is a switch that can be set until the review starts. When the tests are done and it is
  on, the loop waits. It checks the controls every second (every progress tick on a worker) for 🔍 review or
  📦 deliver, or until 15 minutes have passed, when it reviews. The run keeps its slot while it waits, because
  releasing it would need a new task phase. The run's timeout does not count the wait.
- **⛔ cancel** is unchanged.

A team worker learns its controls from the progress reply, which gains `skipStep`, `deliverNow`, `pauseBeforeReview`
and `pauseChoice` beside `cancel`. A worker reads only `cancel` today, so a worker too old to know the controls ignores
them. The team machine therefore offers them only when the worker has reported `loopSteps`.

Who may use them: `TaskAccess` gains `STEER`, allowed to the requester while the task is `EXECUTING`, with the same
refusals as cancel's.

## Verification

- `Verification.Tests` gains `SKIPPED` (the last test round was skipped).
- `ReviewState` gains `SKIPPED`.
- `stoppedBy` gains `delivered early by the requester`.

The English commit block and the Mongolian `verify.*` texts say these plainly.

## Mini App

- `GET /api/tasks/run?taskId=N` returns the task's latest run for the requester: its kind, when it started, its steps
  (with times and details), the running step's latest agent action, the cost of the finished calls, the budget, and
  the controls it offers now (`canSkip` with the step's `n`, `canDeliverNow`, `pauseBeforeReview`, `paused` with
  seconds left, `canCancel`).
- `POST /api/tasks/run/control` takes `{taskId, action: "skip" | "deliverNow" | "pause" | "unpause" | "review" |
  "deliver", step?}` and answers with the same view.
- The ticket sheet of a running task shows the header, the state line, a step rail and the controls, as in the mockup.
  It fetches every 2 s while open, and slower when the page is hidden. A finished task's sheet keeps its step rail.
- The teleport command, with a copy button, shows while paused and after the run.

## Teleport

- `dispatch teleport N [--plan]` reads the task from this machine's store. It refuses when:
  - the task is not the caller's instance's;
  - a run is active;
  - the project's agent is not Claude Code;
  - the worktree is missing.
- Otherwise it runs `claude --resume <session>` in the task's worktree and waits for it. The session is the build
  session when there is one, else the planning session; `--plan` picks the planning session.
- On a team worker the same command reads the task from the team machine, through a new `GET /api/worker/task` that
  only answers for tasks that worker ran.
- Telegram's `/teleport N`, private chat only and for the requester only, answers with the command. It also names the
  computer when a worker ran the task. The Mini App shows the same line.
- The task keeps its state. A later correction or follow-up resumes the same session, with whatever was done in the
  terminal.

## Slices

1. **Steps recorded, locally.** Migration `032-run-step.sql`, the `JobEvents` step events, and `VerifyLoop` and
   `JobRunner` sending them. The Coordinator stores them.
2. **Steps from team workers.** `loopSteps` in progress; the team machine stores them.
3. **Mini App monitor, read-only.** `/api/tasks/run` and the sheet's step rail, refreshing.
4. **Skip and deliver now.** Per-step cancel, the controls in `ActiveRun` and `run`, the progress reply, `STEER`, the
   Verification states, and the Mini App buttons.
5. **Pause before review.** The switch, the wait with its 15-minute automatic continue, and the paused view.
6. **Teleport.** `dispatch teleport`, `/teleport`, and the Mini App line.

Each slice is built test-first, on its own branch or commits, and deployed only from a build that contains `origin/main`.
