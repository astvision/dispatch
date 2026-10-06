# Usage limit (UL): a run that hits Claude's limit waits for the reset

Status: approved design, 2026-10-01; built 2026-10-06 (ADR 0040). Differences from the text below: the reason is
`USAGE_LIMIT` on every hit (036 widened the CHECK), the resume prompt names the limit from the previous run, and the
hold's agent is always `claude-code`.

## Goal

When Claude Code's usage limit cuts a run off, Dispatch says so plainly, with the reset time, instead of "агент алдаа
гаргалаа", and holds that machine's Claude runs until the reset instead of starting each queued task only for it to fail.
The run that hit the limit goes back into the queue and continues its session after the reset, as `/retry` would.

Today a limit is an ordinary `AGENT` failure: the requester sees the generic reason with Claude's text in the detail, the
scheduler keeps starting queued tasks that fail the same way, and nothing resumes without a `/retry` per task.

In scope: detecting the limit in Claude Code's stream; `AgentOutcome.LIMITED` and `FailureReason.USAGE_LIMIT`; re-queueing
the hit run; a hold per machine and agent (`agent_limit`) that `Runs.claimNext` and `RemoteWorkers` respect; the requester's
messages and a `/status` line; ADR 0040.

Out of scope:
- **Codex and Gemini CLI.** Their limits arrive as free text (Codex: "try again at Oct 21st, 2026 1:56 PM"), and neither
  has run a live task here yet. Added once one does.
- **A warning before the limit** (`allowed_warning`). Claude Code warns in its own terminal; add one if hits keep
  surprising.
- **Lifting a hold by hand** ("start now" after switching account or enabling extra usage).
- **Re-queueing inside the verify loop.** A limit there stops the loop and delivers, as a failed fix does today.
- **Mini App and `dispatch ui` labels.** They show the raw reason, `USAGE_LIMIT`, as they show `AGENT` today.

## Success criteria

1. A planning or execution run that Claude rejects for its limit ends with run status FAILED, reason `USAGE_LIMIT`; the
   task stays in its phase, a new run of the same kind is queued, and the requester gets one message with the limit and
   its reset time.
2. Until the reset, no PLAN or EXECUTE run of a Claude Code project starts on the held machine; DELIVER runs, Codex and
   Gemini projects, and other machines are not held.
3. Within a few seconds after the reset the queued runs start by themselves, the re-queued one continuing its session.
4. A limit inside the verify loop delivers the draft PR marked `⏹ stopped: usage limit`, and holds the machine.
5. The third limit in a row on one task fails the task (`USAGE_LIMIT`, with the `/retry` hint) instead of re-queueing.
6. A run that is not limited behaves exactly as today.

## What Claude Code reports

Claude Code's stream-json output carries `rate_limit_event` lines (seen in every run log of the live bot; the values
below were checked in Claude Code 2.1.286):

```json
{"type":"rate_limit_event","rate_limit_info":{"status":"allowed_warning","resetsAt":1790600400,
 "rateLimitType":"seven_day","utilization":0.91,"isUsingOverage":false,
 "unifiedWindows":{"five_hour":{"utilization":0.11,"resetsAt":1790336400},"seven_day":{...}}},
 "uuid":"…","session_id":"…"}
```

- `status`: `allowed`, `allowed_warning` or `rejected`.
- `resetsAt`: epoch seconds when the limiting window resets.
- `rateLimitType`: `five_hour`, `seven_day`, `seven_day_opus`, `seven_day_sonnet`, `overage`, …
- `isUsingOverage`: true when a rejected window is covered by extra usage, so the run goes on.

## Detection

`StreamParser` remembers the last `rate_limit_event` whose `status` is `rejected` and whose `isUsingOverage` is not true.
When the run ends:

- it did not succeed, and a rejection with a `resetsAt` in the future was seen: `AgentOutcome.LIMITED`, with
  `AgentResult.limit = UsageLimit(Instant resetsAt, String type)`;
- otherwise exactly as today. A rejection without `resetsAt`, or with one already past, stays an `AGENT` failure: Dispatch
  never guesses a reset. A run that succeeded despite a rejection (overage, or Claude retried) is a success.

`Assistant` and `Splitter` treat `LIMITED` as `FAILED`, as today: the assistant falls back to proposing the message as a
task, which then waits in the held queue.

## The hit run

`JobRunner`:

- A PLAN run, or an EXECUTE run's implementation call, that comes back `LIMITED` gives a FAILED `JobResult` with
  `failureReason = USAGE_LIMIT`, `failureDetail = "<type> · resets <ISO instant>"`, and the new field
  `JobResult.limit` (`@JsonInclude(NON_NULL)`).
- A fix or review call of the verify loop that comes back `LIMITED` stops the loop with `stoppedBy = "usage limit"`; the
  run delivers as today, and its `JobResult` (SUCCEEDED, or a delivery failure) still carries `limit`.

`Coordinator.apply`: a FAILED result with reason `USAGE_LIMIT` goes to `RunTransitions.limited`; any other result with a
`limit` goes through its usual transition and also writes the hold. `RunTransitions.limited`, in one transaction:

1. Finishes the run FAILED with reason `USAGE_LIMIT` and its detail.
2. Writes the hold (below).
3. **Runaway guard:** when this is the task's third run in a row to end with `USAGE_LIMIT`, it is an ordinary failure
   from here: the task goes to FAILED and the requester gets today's failure message, with the `/retry` hint.
4. Otherwise the task keeps its phase (PLANNING or EXECUTING; it never passes through FAILED), and a run is queued with
   the next seq, the same kind, the same instruction, the hit run's `requested_by`, and cause `RETRY`, so an execution
   resumes its session and is told it was cut short (`Prompts.retry`). An event records "usage limit: run N queued".
   The requester gets `LIMIT_REQUEUED`; the group gets nothing, since the task did not fail.

## The hold

Migration `036-agent-limit.sql` (035 is `task_ci`, the CI watch):

```sql
CREATE TABLE agent_limit (
    machine   INTEGER NOT NULL,  -- 0: this machine (personal mode); else worker.id
    agent     TEXT    NOT NULL,  -- 'claude-code'
    resets_at TEXT    NOT NULL,
    type      TEXT    NOT NULL,
    PRIMARY KEY (machine, agent)
);
```

- The machine is the one that ran the agent: 0 in personal mode, `task.worker_id` in team mode (recorded when the
  worker took the run). A new hit replaces the row. A row whose `resets_at` has passed holds nothing; nothing deletes it.
- `Runs.claimNext` skips a PLAN or EXECUTE run whose project runs on Claude Code while its machine is held:
  - personal mode: while machine 0 is held;
  - team mode: the worker gate's `EXISTS (… worker w …)` also requires no unexpired hold on `w` for the run's agent,
    beside the `worker_agent.ok = 0` check it has today. A member's other free computer may still take an unpinned run;
    a pinned one waits for its own computer.
- `RemoteWorkers.matches` does not hand a Claude Code job to a held computer, so an unpinned job claimed for the member's
  free computer cannot be taken by the held one.
- `Workers.blockerOf` reports a held computer as blocker `limit` with the reset as its detail, so `reportBlocked` tells
  the requester of each task it holds, once, through the existing `blocked_reason` dedup.
- The scheduler's 5-second idle poll starts held runs within seconds after `resets_at`; no timer. The table survives a
  restart, and the re-queued run is an ordinary queued run.

## Messages

Mongolian, in `messages_mn.properties`. The limit's words: `five_hour` "5 цагийн", any `seven_day*` "7 хоногийн",
anything else "хэрэглээний". The reset as `HH:mm` when it is today in the bot's zone, otherwise `MM-dd HH:mm`.

| When | To | Text |
|---|---|---|
| Re-queued (`LIMIT_REQUEUED`) | requester | `⏸ <b>#12</b>: Claude Code-ийн 5 цагийн хязгаарт хүрлээ. 15:00-д өөрөө үргэлжилнэ; Claude-ийн бусад даалгавар ч хүлээнэ.` + `<code>/cancel 12</code> — болих` |
| Third hit in a row | requester, group | today's `TASK_FAILED` / `TASK_FAILED_SHORT` with `failure.USAGE_LIMIT=Claude Code-ийн хэрэглээний хязгаарт хүрлээ` |
| Team: a queued task held by its computer | requester | `blocked.limit=⏸ <b>#13</b>: таны компьютер дээрх Claude Code хязгаарт хүрсэн, 15:00-д өөрөө эхэлнэ.`; `/status` line `status.blocked.limit=Claude Code хязгаарт хүрсэн` |
| `/status` while machine 0 is held | asker | first line `⏸ Claude Code хязгаарт хүрсэн · 15:00 хүртэл` |
| Verify loop cut off | PR body, result | `⏹ stopped: usage limit` (the existing `verify.stopped`) |

## Components

- `dispatch.agent.UsageLimit` (record `resetsAt`, `type`); `AgentOutcome.LIMITED`; `AgentResult.limit`.
- `StreamParser`: the rejection rule above.
- `JobRunner`, `VerifyLoop`: `LIMITED` as above; `JobResult.limit`.
- `FailureReason.USAGE_LIMIT`.
- `dispatch.store.AgentLimits`: `hold(tx, machine, agent, limit)`, `heldUntil(tx, machine, agent, now)`.
- `Runs.claimNext`, `RemoteWorkers.matches`, `Workers.blockerOf`: the hold.
- `Coordinator.apply`, `RunTransitions.limited`: the transition and the guard.
- `OutboxKind.LIMIT_REQUEUED`; `Renderer`: the messages and the `/status` line; `TaskService`'s status payload carries
  machine 0's hold.

## Errors and compatibility

- No `resetsAt`, or one already past: an `AGENT` failure, no hold.
- `/cancel` on a task waiting for the reset cancels its queued run, as today.
- `resetsAt` is an absolute instant from Anthropic; only the machine that schedules compares it with its own clock.
- `JobResult.limit` is sent only when a limit hit. A team machine from before this version refuses the unknown field
  then, as it refuses the verify loop's: upgrade a team machine and its workers together.
- `USAGE_LIMIT` is a new value in the run's TEXT column; the only migration is the new table.

## Testing

Test-first, with fakes, as the verify loop:

- `StreamParser`: rejected → `LIMITED` with reset and type; rejected with overage → not limited; rejected without
  `resetsAt` → `FAILED`; `allowed_warning` → no change; rejected then success → `SUCCEEDED`.
- `Runs.claimNext`: personal held, not held, expired; a Codex project and a DELIVER run not held; team: the held computer
  skipped, the member's other free computer takes the run, a run pinned to the held computer waits.
- `RemoteWorkers.matches`: no Claude Code job for a held computer; a Codex job still is.
- `RunTransitions.limited`: phase unchanged, RETRY run queued with the same kind and instruction, hold written,
  `LIMIT_REQUEUED` sent; the third hit in a row fails the task.
- `VerifyLoop`: a `LIMITED` fix or review stops with "usage limit", delivers, and the result carries the limit.
- `JobResult` JSON round trip with and without `limit`.
- `Renderer`: `LIMIT_REQUEUED` today and another day, `blocked.limit`, the `/status` line, all escaped.
- Live: a real limit cannot be produced on demand. The next one the live bot meets is checked against this spec, and its
  sanitized `rate_limit_event` replaces the fixture.

## Documentation

- ADR 0040 "A run that hits the agent's usage limit waits for the reset".
- ARCHITECTURE.md: the hold in "Flows", `LIMITED` in "Agent boundary"; CONTEXT.md: **Usage limit**, **Hold**.
- README (mn, en): what a requester sees when the limit hits.
