# A personal bot becomes a team bot (S)

Status: approved design, 2026-09-28. The second of four specs toward a team bot anyone can set up: **M** several
instances on one computer (done, c7620f3) → **S** a personal bot becomes a team bot → **N** no URL at setup →
**W** a worker serves several teams at once. Only S is designed here.

## Goal

The owner of a personal bot turns it into a team bot with one command or one button, keeping the bot, its projects,
its groups and its history, and without changing how their own tasks run. Teammates then join by writing to the bot
(ADR 0015). While only the owner is in it, the owner can switch back.

Today the mode is derived (ADR 0023): `telegram.admins` non-empty or more than one member makes a team. A team runs
nothing on its own machine (ADR 0021): at startup `App` chooses between the in-process `JobRunner` (personal) and
`RemoteWorkers` (team), and several features follow that one choice. Turning a personal bot into a team by hand today
therefore sends the owner's own tasks to a worker they do not run, drops their Assistant, and demands a public URL.

In scope: the `telegram.owner` key; `dispatch team on|off`; the Mini App's switch button; running the owner's tasks
on the team machine; when the `workers` block is required; `dispatch check` for an owner.

Out of scope: the tunnel that makes `workers.publicUrl` automatic (N); a worker serving several teams (W); applying
the switch without a restart; changing who approves joins (ADR 0015 is unchanged).

## The owner

A new optional key `telegram.owner: <telegram user id>`. It names the person whose machine this is: their tasks run
here, as in a personal bot. It is explicit, never derived: a team without it behaves exactly as today (ADR 0021), so
the server layout and existing team configs are unchanged.

The loader requires, when `owner` is set: it is one of `telegram.admins`, and it is a member of at least one group.
A personal bot never carries `owner` (it would be meaningless: everything runs here); `dispatch team off` removes it.

`Config.runsHere(String requesterRef)` answers the question every fork below asks: true for every requester of a
personal bot, true for the owner of a team, false otherwise.

## Switching

`dispatch team on [--instance NAME]`:

1. Refuses when the instance is already a team, or when its config does not load (`dispatch check` says why).
2. Asks the team name (default: today's `team:`), and which already-linked group gets the team's announcements, from
   a list of the groups that have a chat, or none. Nothing else is asked; members join from Telegram later.
3. Writes, under the config's `ConfigFile.edit` lock and after copying the current file to `<config>.bak` (as the
   management pages do, `ManageApi.backup`): `team:` if changed, `telegram.admins: [owner]`, `telegram.owner: owner`.
   The owner is the one member of the personal bot.
4. Restarts the instance's service if it is installed and running (`Service.restart()`), otherwise says to start it.
   A restart is required because `App` makes the choices below at startup.

`dispatch team off [--instance NAME]` refuses unless the team's only member is its owner, naming who is still in
("remove them first: Mini App › People, or the config"). Otherwise it writes `.bak`, removes `telegram.admins` and
`telegram.owner`, and restarts the same way. A team without an owner (made by `dispatch init`, "My team") cannot be
switched off: it has nobody whose machine it is.

The Mini App's settings page gets the same switch as a button (**Багт шилжүүлэх** / **Хувийн болгох**), shown only
to the owner: in a personal bot the one member (`TelegramAuth` already treats them as admin), in a team the caller
whose id is `telegram.owner`. It posts `/api/manage/team` with `{on: true|false}` and the chosen group, goes through
`ManageApi.save` (lock, version check, `.bak`), and then restarts through the existing `/api/manage/service/restart`
path. `dispatch ui` offers the same button on its settings page.

## Where tasks run

With an owner, the team machine is two things at once: the coordinator for everyone (ADR 0021) and the owner's own
computer. Every place that today asks "is there a `workers` block?" to mean "does anything run here?" asks
`runsHere(requester)` instead:

- **Running:** `App` builds both the in-process `JobRunner` and, when the `workers` block exists, `RemoteWorkers`.
  A `Worker` that routes by the job's requester sends the owner's runs to `JobRunner` and everyone else's to
  `RemoteWorkers` (it looks the requester up as `RemoteWorkers.offer` already does). With an owner but no `workers`
  block, only `JobRunner` exists, and a non-owner's run waits (below).
- **Claiming:** `Runs.claimNext`'s team gate (a live, ready worker of the requester with capacity) does not apply to
  the owner's runs; they take a general slot as in a personal bot. `scheduler.maxConcurrentRuns` still caps this
  machine's own runs.
- **Worktrees:** the team machine creates the worktree directories too (`Workspaces.createDirectories`) when it has an
  owner; the owner's worktrees and sweeps work as in a personal bot.
- **Waiting and readiness:** `TaskService`'s "waiting for your computer" and `WORKER_BLOCKED` reports skip the owner's
  tasks: nothing of theirs waits on a worker.
- **Merging from Telegram** (personal-only today, `App.java` `mergeFromTelegram`) stays available for the owner's
  tasks, which were delivered with this machine's `gh`.
- **The Assistant** (ADR 0024, wired only without a `workers` block today) stays on for the owner: it reads the
  owner's clones on this machine. Other members get no Assistant, as in a team today.

## When the `workers` block is required

Today: once a team has a group with a chat (`ConfigLoader.validateWorkers`). With an owner: once the team has a member
other than the owner. So a freshly switched team, even with its announcement group, needs no public URL; a team
without an owner keeps today's rule.

When an admin approves the first member other than the owner and there is no `workers` block, the approval still
succeeds (the member can write to the bot at once), and the admin is told once, privately: their tasks run on their
own computer, which needs this machine reachable: set `workers.publicUrl` and `workers.port` and restart (automatic
after N). The config is then invalid until that block is added, so `dispatch check` fails with that message and the
running bot keeps running with its startup config until restarted. The new member's tasks wait, reported as
"waiting for your computer" (ADR 0022), as they would for any member without a connected computer.

## `dispatch check`

With an owner, the check runs both sets: the personal checks for this machine (the agent, `gh`, each project's clone,
since the owner's tasks run here) and the team checks (the workers block and its URL) when the block exists. It fails
when `owner` is not an admin or not a member (the loader's error), and reports "team with an owner: <name>'s tasks run
here".

## Testing

- ConfigLoader: `owner` must be an admin and a member; the `workers` block is required only with another member.
- `dispatch team on`: personal → team with `admins`, `owner`, `.bak`, the chosen group; already a team is refused.
  `off`: refused while another member exists (named); otherwise `admins`/`owner` removed. The restart is called on a
  fake `Service`.
- Routing: with an owner, the owner's job reaches the local runner and another member's reaches `RemoteWorkers`;
  `claimNext` claims the owner's queued run with no worker connected; the waiting/blocked reports skip it.
- ManageApi `/api/manage/team`: only the owner may call it; it goes through `save` (version check, `.bak`).
- An end-to-end test in the style of `TeamWorkersTest`: a team with an owner and one member, where the owner's task
  runs locally while the member's is served to a paired fake worker.

## Documentation

ADR 0029 "The team machine's owner runs their tasks there" amends ADR 0021 (the exception) and ADR 0023 (mode is still
derived; `owner` is an extra key, not a mode). README: "Turn your bot into a team bot" under "A bot for your team".
