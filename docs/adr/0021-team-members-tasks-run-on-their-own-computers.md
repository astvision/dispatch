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
