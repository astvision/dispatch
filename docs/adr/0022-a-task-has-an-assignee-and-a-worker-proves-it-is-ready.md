# A task has an assignee, and a worker proves it is ready

Being connected is not the same as being able to do the work. A member's computer can poll the team machine while its Claude Code cannot start, its GitHub CLI is logged out, or a project's clone is missing. Under ADR 0021 alone, such a run was handed to that computer and failed there, and the member learned why only from the failure.

So each worker now reports its **readiness** on the `/api/worker/next` poll it already makes: whether `claude --version` runs, whether `gh auth status` passes, and whether each of its projects has a usable clone. These are the checks `dispatch check` already runs on a worker (`WorkerChecks`). The worker re-runs them at most once a minute and sends the cached result, because spawning `claude` and `gh` on every poll would be wasteful. The team machine keeps the last report on the `worker` row, along with when it arrived.

Not every blocker holds every run:

| Blocker | Holds |
|---|---|
| `claude` cannot run | every run: planning and execution both need it |
| `gh` not authenticated | only runs that deliver (EXECUTE, DELIVER); planning never touches GitHub |
| a project's clone missing | only that project's runs |

The scheduler claims a run only when a computer that may take it (the one holding its worktree, otherwise any of the member's live ones) has no blocker for that run's project and kind. When nothing can be claimed, the scheduler checks what is holding each queued run. For each one held by its member's computer, the member is told privately, once, which thing is wrong and the command that fixes it, and `/status` shows the same reason. The reason is kept on the task, so it is not repeated on every idle poll. It is cleared when the run starts, so a later block is reported again. A worker's poll wakes the scheduler, so the first healthy report starts the held run with no further action.

A worker that reported nothing counts as ready. A team machine upgraded before its members' computers must not stall every member until they upgrade too. The cost is that an old worker gets no readiness message, and its broken run fails as it did before. A report counts only while its worker was seen within `Workers.SEEN_WITHIN`. An older report means the computer is offline, which the existing "waiting for your computer" message already says. It must not be read as "Claude Code is broken".

**What this cannot prove:** `claude --version` shows that the binary runs, not that its session is signed in. A worker whose login has expired reports ready, and its run fails when the agent starts, reported as a failed run as it is today. Proving the session is live would need a real model call, spending tokens on every worker every minute.

The rejected alternatives were a separate readiness endpoint (a second request per worker with nothing to gain over the poll that already runs), checking readiness on the team machine (it has none of the member's tools to check), and a generic "your computer cannot run this" message (it does not tell someone that their Claude Code is logged out).

**Assignee (decided, not yet built — milestone T-2).** A task will get an assignee as well as a requester. The assignee's computer runs it, and the assignee approves its plan, since the code runs as them. The requester sees the headline, the outcome and the pull request, and keeps cancel. A task can then also be given in the group by mentioning the bot and the assignee. If the assignee leaves the team, a task still waiting for them falls back to its requester. Until T-2, the requester is the assignee, and readiness is checked on the requester's computers. See `docs/superpowers/specs/2026-09-23-assignment-and-readiness-design.md`. T-2 will amend ADRs 0011, 0012, 0020 and 0021 and complete this record.

## Consequences

- A member whose computer cannot do a task is told the actual reason once (`claude`, `gh` or the clone, with the fix), not "waiting for your computer". Nothing nags.
- A fix takes effect on the worker's next readiness check, within a minute, or at once when `dispatch worker run` is restarted.
- `gh` being logged out never holds a plan. A member can plan tasks and approve them before fixing GitHub, and the run waits at execution.
- Readiness adds a Claude Code version, a `gh` yes/no, project names and each failed check's one-line detail to the worker's authenticated poll. That detail can name a local path, such as where `claude` was looked for. It never carries tokens or repository contents.
- Handing a job to a computer is not gated per computer yet. When one of a member's computers is ready and another is not, the run is claimed, and whichever computer polls first takes it.
