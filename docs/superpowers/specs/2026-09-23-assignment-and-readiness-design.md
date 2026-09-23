# Assigned tasks and worker readiness

Status: approved design, 2026-09-23. Next: implementation plan for milestone T-1.

## Goal

A task can be given to a teammate who has not set their computer up yet, and nothing is lost and nothing starts until
their machine can actually do the work.

Two things follow from that. A task needs an **assignee** as well as a requester, because the person who asks and the
person whose machine runs it are no longer the same. And the team machine needs to know whether a member's computer is
**ready** — not merely connected — so it can hold the task and say precisely what is missing.

In scope: the `assignee` on a task; giving a task in the group by mentioning the bot; worker readiness reported on the
poll the worker already makes; holding a run until the assignee's machine is ready, with a message that names the
blocker.

Out of scope: moving the splitter off the team machine (its own spec — see Deferred); reading group messages the bot is
not mentioned in; reassigning a task by hand; any change to personal mode, which has one member who is always both
requester and assignee.

## Decisions

| Question | Decision | Why |
|---|---|---|
| Who owns an assigned task | Requester and assignee are separate people; the assignee's computer runs it | The person who asked is rarely the person who will do it |
| Who approves the plan | The assignee | It runs as them, on their clones, with their credentials — ADR 0006's consent belongs to whoever bears the risk |
| What the requester sees | The headline, the outcome and the pull request; not the plan, corrections or cost | It is the assignee's task on the assignee's machine (ADR 0020); the requester keeps cancel |
| How a task reaches the group | `@bot @dev …`, with privacy mode left on | Telegram already delivers messages that mention the bot; reading all group chat would need a model on the server, which is what this design is removing |
| How readiness is known | The worker reports it on the `/api/worker/next` poll it already makes | Always as fresh as the heartbeat, no new endpoint, no tokens |
| What an unready worker causes | The task is held and the assignee is told the actual blocker, once | "Waiting for your computer" does not tell someone their Claude Code is logged out |
| When the assignee leaves the team | The task falls back to the requester, who is told | The work is not lost and no admin has to notice |

These are recorded as ADR 0022, "A task has an assignee, and a worker proves it is ready".

## Architecture

### Readiness (T-1)

The worker already long-polls `/api/worker/next`. Its request body gains a `readiness` object:

```json
{"claude":   {"ok": true,  "detail": "2.1.280"},
 "gh":       {"ok": false, "detail": "not logged in"},
 "projects": {"alm": {"ok": true}, "life": {"ok": false, "detail": "clone missing"}}}
```

`WorkerChecks` already knows how to produce every one of those — it is what `dispatch check` runs on a worker today.
The worker re-runs them on an interval (60 s) and caches the result; spawning `claude --version` on a two-second poll
would be absurd. The team machine stores the last report on the `worker` row with the time it arrived.

Not every blocker holds every run:

| Blocker | Holds |
|---|---|
| `claude` cannot run | every run: planning and execution both need it |
| `gh` not authenticated | only runs that deliver (EXECUTE, DELIVER); planning never touches GitHub |
| a project's clone missing | only that project's runs |

A readiness report counts only while the worker was seen within `Workers.SEEN_WITHIN`. Older than that and the worker
is offline, which is the case Dispatch already handles and words differently.

The scheduler skips a run whose assignee has no ready worker for that project. The assignee — not the requester — is told once per transition,
naming the blocker and the command that fixes it; `/status` shows the same. Nothing nags. A worker's poll already wakes
the scheduler (`RemoteWorkers` → `schedulerSignal`), so the first healthy poll starts the held run by itself.

**What this check cannot prove:** `claude --version` shows the binary runs, not that its session is signed in. A worker
whose login expired reports ready and fails when the agent starts, which is reported as a failed run as it is today.
Proving the session is live needs an actual model call, which would spend tokens on every worker every minute.

### Assignment (T-2)

`task` gains `assignee_ref` and `assignee_name`. A migration backfills both from the requester for every existing row,
after which they are required. Where nobody is named — every private task, and all of personal mode — the assignee is
the requester, so today's behaviour is not a special case but the ordinary one.

| | Requester | Assignee | Admin |
|---|---|---|---|
| Approve, correct, reject | | ✓ | |
| Follow up, retry | | ✓ | |
| Change priority | | ✓ | |
| Cancel | ✓ | ✓ | ✓ |
| Runs on their computer | | ✓ | |
| Sees the plan, corrections, cost | | ✓ | |
| Sees the headline, outcome and PR | ✓ | ✓ | ✓ |

**Giving one in the group.** `@dispatch_task_bot @ali fix the login timeout on staging`. Telegram delivers it because
the bot is mentioned, with privacy mode left on. Dispatch resolves the named member and creates a draft **in the
requester's private chat**, exactly as a private task does, with the assignee already set; the requester picks project
and priority with the buttons that already exist. The group gets its usual one-line announcement. No second intake path
is built: the draft machinery is reused whole.

Members are configured with an id and a name but no username, so `@ali` cannot be resolved today. Two changes fix it
together: record `username` when someone joins (`Membership.requestJoin` already receives it), and accept Telegram's
`text_mention` entity, which carries the user id outright when a name is tapped rather than typed.

**When the assignee leaves.** Removing a member already revokes their workers' keys (W-4). A task still waiting for
them falls back to its requester — assignee becomes requester, the requester is told, and it runs on their machine.

### What this costs in ADRs

Four amendments, which is the weight of T-2 rather than an aside:

- **0011** — the plan, corrections and full result go to the *assignee*; the requester gets the outcome.
- **0012** — a task may also be given in the group by mentioning the bot, when it names an assignee.
- **0020** — the *assignee* approves, corrects, rejects, follows up, retries and sets priority; the requester keeps cancel, and sees
  the headline of their own request as any other member would.
- **0021** — a run is given to a worker of its *assignee*.

## Security

- Nothing new is exposed. The group intake uses messages Telegram already delivers to the bot; privacy mode stays on,
  so the bot still never receives group messages that do not mention it.
- Readiness carries a Claude Code version, a `gh` auth yes/no and project names — no paths, no tokens, no repository
  contents. It travels on the worker's existing authenticated request.
- The assignee, not the requester, consents to code running on the assignee's machine. A member cannot cause an agent
  to run on a teammate's computer without that teammate approving the plan first.
- A task assigned to someone reveals to them that it exists and who asked; it reveals nothing about anyone else's tasks.

## Testing

| Check | Where |
|---|---|
| `claude` broken holds every run; `gh` holds only delivering runs; a missing clone holds one project | new `ReadinessTest` |
| Readiness rides the poll and survives the round trip | `WorkerProtocolTest` |
| The scheduler skips an unready assignee and picks a ready one | `SchedulerTest` |
| A held task starts by itself on the first healthy poll, and the member was told once | `TeamWorkersTest`, end to end |
| The assignee approves and the requester cannot; the requester can still cancel | `TaskLifecycleTest` |
| `@bot @dev …` makes a draft with the assignee set; an unknown or non-member name is refused in one line | `UpdateHandlerTest` |
| Every existing task gets an assignee | migration test |
| A departed assignee's waiting task falls back to its requester | `TeamWorkersTest` |

Live, before T-2 is called done: two members' computers, one assigning to the other, with the assignee's Claude
deliberately logged out first so the blocker message is seen rather than assumed.

## Milestones

Each is built test-first on its own branch with a stacked draft pull request, and stops for review before the next.

| Milestone | Delivers | Done when |
|---|---|---|
| T-1 Readiness | `readiness` on the worker poll, stored per worker; the gating table; the scheduler holding a run; the blocker message and `/status`; ADR 0022 | A worker with `claude` broken holds its member's runs and says so; fixing it starts them with no further action |
| T-2 Assignment | `assignee` on a task with its migration; the rights table; `@bot @dev …` intake; username capture and `text_mention`; the fallback when an assignee leaves; ADR amendments 0011, 0012, 0020, 0021 | A task assigned in the group reaches the assignee, is approved by them, runs on their machine, and its requester sees the outcome and nothing more |

## Deferred

- **Moving the splitter to a worker.** ✂️ is the last thing that spends tokens on the team machine (ADR 0013). Moving
  it does not fit `Job`, which is shaped around a run — it would need a second method on `Worker` and a union payload on
  `/api/worker/next`, a protocol W-3 has only just stabilised. It is worth its own spec, not a rider on this one. A
  cheaper alternative to weigh there: splitting on numbered lists, bullets and blank lines, which needs no model at all.
- **Reading group messages the bot is not mentioned in.** It would need a model classifying every message the team
  writes, on the team machine — the largest token cost in the system, to replace a mention that costs nothing.
- **Reassigning a task by hand.** Nothing here moves a task between members except the fallback when one leaves.
