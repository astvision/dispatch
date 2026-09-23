# Team workers: each member's tasks run on their own computer

Status: approved design, 2026-09-22. Next: implementation plan for milestone W-1.

## Goal

In a team, every member's tasks run on that member's own computer, with their own Claude Code login, their own clones and their own `git` and `gh`, so each member pays for their own usage and their Claude sessions, code and credentials never leave their machine. Members see only the headline of each other's tasks.

The team keeps one bot, one group, one queue: joining by admin approval, announcements, priorities, `/status`, `/history` and `/stats` work as today, from the team machine.

Out of scope: running a member's tasks on the team machine when their computer is off; sandboxing a worker on its own machine (it runs as its owner, as Dispatch does today); the Mini App (UI-3b), which comes after this.

## Decisions

| Question | Decision | Why |
|---|---|---|
| Where a member's tasks run | On a `dispatch worker` on the member's own computer | Their own token pays; sessions, code and credentials stay on their machine |
| Who coordinates | The team machine (`dispatch run`) keeps the bot, queue, store and Telegram; it runs no agent | Team views, priorities and joining keep working; one store, no drift |
| How a worker reaches the team machine | It connects out over HTTPS to a URL the owner provides (tunnel, reverse proxy or private network) | Laptops sit behind NAT; Dispatch runs no tunnel software |
| Members without a worker | None in team mode: their tasks wait for their computer | The team machine holds no Claude or GitHub credentials at all |
| Where the code comes from | The team config names each project and its `repo`; each worker maps it to a local clone or clones it | One truth for project names; members keep their own working clones |
| What others see | The headline only: who, project, title, state, PR link | Plans, corrections, the agent's actions, timelines, cost and model are the requester's |
| Who may act on a task | The requester; an admin may also cancel | A task runs with the requester's token and in their clone |
| How a worker proves who it is | A one-time code from `/worker`, exchanged for a worker key bound to the member | Nothing to type into a config; revocable from Telegram |

Rejected: workers that keep their own task state with the team machine as a relay (team views would be rebuilt from reports and could drift); workers talking through the bot (Telegram delivers a bot's updates to one reader only); SSH to the team machine (every member would need an account there).

These are recorded as ADR 0021, "Team members' tasks run on their own computers".

## Architecture

```
member's computer                                team machine
dispatch worker ──HTTPS long poll──────────────▶ dispatch run (team mode)
  ├─ JobRunner: Workspaces, ClaudeCodeAgent,       ├─ bot, TaskService, Scheduler, store, outbox
  │  Delivery (today's code)                       ├─ Coordinator: leases runs to their requester's worker
  ├─ member's claude login, ~/.claude sessions     ├─ WorkerApi on 127.0.0.1:<workers.port>
  ├─ member's clones, git, gh                      └─ no claude, no gh, no member credentials
  └─ worker.yaml, worker.env (key)
```

### The job

A job is one run: a plan, an execution, a follow-up or a delivery retry, the unit the scheduler claims today.

- `RunExecutor` is split. The **Coordinator** claims runs and applies outcomes through `RunTransitions`, as today. The **JobRunner** does the machine work: the worktree, the agent, delivery. They meet at a `Worker` interface.
- A personal ("Just me") Dispatch uses an in-process worker: the same JobRunner in the same process, with no HTTP. Personal mode behaves exactly as today.
- In team mode the Coordinator gives a queued run only to a worker of its requester. Priority order and "nothing running is interrupted" stay. Each worker runs one job at a time unless its config says more.

### Protocol

All requests are `POST` with JSON and `Authorization: Bearer <worker key>`.

1. `/api/worker/next`: a long poll of up to 25 s. Answers `{"job": null}` or `{"job": <job>}`: task number, run number and kind; project name, base branch and repo; model, effort, timeout and budget; session id and whether to resume; the finished prompt; the attachments to fetch.
2. `/api/worker/progress` every 10 s while a job runs: the agent's latest action (for the requester's `/status`). It renews the lease and answers `{"cancel": true}` when the run was cancelled.
3. `/api/worker/attachment`: fetches one of the job's attachments; the team machine deletes its copy after the result.
4. `/api/worker/result`: the outcome: plan text or result summary, PR URL, cost, turns, model, duration; or the failure reason and detail.
5. `/api/worker/projects`: the member's projects (name, repo, base branch, default model and effort), for worker setup.

A lease expires after 60 s without progress: the run fails as `INTERRUPTED`, as when the service restarts mid-run today, and `/retry N` continues it. A result for an expired lease is refused with 409; the worker keeps its worktree, so `/retry` delivers it again.

A follow-up or retry of a task goes to the worker that holds its worktree and session; if that worker is offline, it waits.

## Security

- **Pairing.** `/worker` in the bot's private chat gives a member a one-time 8-character code valid for 10 minutes; only its hash is stored. `dispatch worker pair <url> <code>` exchanges it for a random 256-bit worker key bound to that member. The worker stores the key in `worker.env` through `SecretsFile` (owner-only); the team machine stores only its SHA-256.
- **Revoking.** `/worker` lists the member's workers (name, last seen); `/worker revoke` removes one. An admin may revoke anyone's. Removing a member revokes their workers. A revoked key gets 401 and the worker stops with "paired key revoked: pair again".
- **Every request.** The key is compared in constant time and names the member. A worker receives only its member's jobs and reports only on a run it holds the lease for; anything else is 403. `workers.publicUrl` must be `https://`; plain HTTP is accepted only from `127.0.0.1`, for local tests.
- **What the team machine holds.** The bot token, the queue, headlines, and the plan and result texts it relays to Telegram, plus attachments until they are fetched. Not: members' Claude or GitHub credentials, Claude transcripts, code, raw agent output. SECURITY.md says plainly that the team machine's owner can read the relayed texts in its database.
- **What members see.** For another member's task (ADR 0020, built in W-1), announcements, `/status`, `/history` and `/stats` show only who, project, title, state and PR link. Only the requester may approve, correct, follow up, retry or change priority; the requester or an admin may cancel. This replaces today's rule that any group member may cancel, retry or follow up.
- The worker endpoints are a separate server from `dispatch ui` and the Mini App, with their own authentication; they share nothing but the jar.

## Configuration

Team machine, `dispatch.yaml`:

```yaml
workers:                                  # required in team mode
  publicUrl: https://team.example.com     # the owner's tunnel or reverse proxy
  port: 7880                              # Dispatch listens on 127.0.0.1:<port>
```

In team mode each project needs `repo`. `ConfigLoader` rejects a team config without `workers`, a non-https `publicUrl` and a port outside 1–65535. `dispatch check` on the team machine no longer asks for `claude` or `gh`, and warns when `publicUrl` does not answer.

Member's computer, `worker.yaml` beside `dispatch.yaml` in the usual config folder:

```yaml
team: https://team.example.com
name: ann-laptop
maxConcurrentRuns: 1
claudeCommand: claude
projects:
  crm:
    path: /home/ann/work/crm              # an existing clone, or one the worker made
    model: opus                           # optional, overrides the team's default here
```

## Worker setup

`dispatch worker init`, and the same steps in `dispatch ui` when it finds no config:

1. The team URL and the pairing code.
2. The member's projects, from `/api/worker/projects`. For each, pick an existing clone with the folder browser or let the worker clone `repo` into its state folder. `ProjectProbe` checks that the clone's origin matches `repo`.
3. Optional per-project model and effort.
4. Claude Code (`--version`) and the GitHub CLI (`gh auth status`) are checked.
5. A summary, then `worker.yaml` and `worker.env` are written, and it offers to keep the worker running in the background (the existing systemd, launchd and Task Scheduler code, as the service `dispatch-worker`).

Commands: `dispatch worker init`, `dispatch worker pair <url> <code>`, `dispatch worker run`, `dispatch worker service status|start|stop|install|uninstall`. `dispatch check` covers the worker when `worker.yaml` exists.

## Error handling

| Case | What happens |
|---|---|
| The requester's worker is offline when a task is given | The task is queued; the requester is told once "waiting for your computer"; `/status` shows it; it starts when the worker connects |
| A worker dies or loses its network mid-run | After 60 s without progress the run is `INTERRUPTED`; `/retry N` continues. A shorter gap just continues |
| A result arrives after the lease expired | 409; the worktree stays on the worker; `/retry N` delivers it |
| The project is not mapped on that worker | The run fails with "project crm is not set up on your computer: run dispatch worker init" |
| Cancel | The next progress answers `cancel`; the worker stops the agent with today's grace period |
| The worker's key is revoked | 401; the worker stops and says to pair again |
| A member has two workers | Allowed; the first to poll takes a new task; a task's follow-up or retry goes to the worker with its worktree |

## Testing

- Coordinator, with a fake clock and a fake worker: runs go only to the requester's workers; a lease expires to `INTERRUPTED`; a late result gets 409; cancel arrives through progress; a follow-up waits for the worker with its worktree.
- WorkerApi over an in-process `HttpServer`: no key or a revoked key 401; member A's key for member B's run 403; a pairing code works once and expires after 10 minutes.
- Visibility: another member's task shows only its headline in `/status`, `/history`, `/history N` and `/stats`; a non-requester cannot approve, correct, follow up, retry or reprioritize; an admin can cancel. Personal mode unchanged.
- End to end: the team jar and two worker processes (Ann, Bob) against `FakeTelegram`, `fake-claude.sh` and `GitFixture`: each task runs on its requester's worker with that worker's environment (a marker variable stands in for the token), and Bob sees only Ann's headline.
- Regression: the existing tests, including `RunExecutorTest`, pass through the in-process worker.
- Live: a team bot with two members' computers paired through a tunnel, before W-4 is marked done.

## Milestones

Each milestone is built test-first on its own branch with a stacked draft pull request, and stops for review before the next.

| Milestone | Delivers | Done when |
|---|---|---|
| W-1 Privacy | Headline-only view of others' tasks in `/status`, `/history`, `/stats`, with no mode switch (ADR 0020); requester-only actions, cancel also by an admin | The visibility tests pass; personal mode's tests pass unchanged |
| W-2 Split | `RunExecutor` into Coordinator and JobRunner behind `Worker`; the in-process worker | Every existing test passes unchanged |
| W-3 Remote workers | `workers` config, WorkerApi, pairing (`/worker`, `dispatch worker pair`), leases, progress, cancel, attachments, `dispatch worker run`; team mode requires workers | The protocol tests and the two-worker end-to-end test pass |
| W-4 Worker setup (delivered) | `dispatch worker init`, the worker setup in `dispatch ui` (team-side `workers` fields only — deliberately not built: a member still pairs at the terminal, not the browser), the worker service, `check`, README, SECURITY.md, ARCHITECTURE.md, ADR 0021 | A live run with two members' computers |
