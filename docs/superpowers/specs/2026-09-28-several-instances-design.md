# Several instances on one computer (M)

Status: approved design, 2026-09-28. The first of four specs toward a team bot anyone can set up: **M** several instances
on one computer → **S** a personal bot becomes a team bot → **N** no URL at setup (Dispatch runs the tunnel itself) →
**W** a worker serves several teams at once. Only M is designed here.

## Goal

One computer runs a personal bot and a team bot at the same time, each set up with `dispatch init`, each kept running
by `dispatch service install`, neither aware of the other except to avoid clashing with it. Today every command defaults to
`~/.config/dispatch/dispatch.yaml` and `service install` always writes `dispatch.service` (launchd `io.dispatch.agent`,
Task Scheduler `Dispatch`), so a second instance only runs by hand with `--config` and a hand-written unit.

In scope: instance names, where each instance's files live, `--instance` on every command, `dispatch list`, init
detecting an existing instance, a branch prefix per instance, and refusing or warning about clashes (bot token, ports).

Out of scope: turning a personal bot into a team bot (S), the tunnel (N), a worker serving several teams (W), the
`deploy/dispatch@.service` server layout (already one instance per team, ADR 0005), and the web setup (`dispatch ui`)
creating a second instance — it manages the instance it was started for.

## The default instance changes nothing

The instance a computer already has is the **default instance**. It keeps every path and name it has today:
`dispatch.yaml` and `dispatch.env`, its state directory, `dispatch.service` / `io.dispatch.agent` / `Dispatch`, and
branches `dispatch/<task>`. Every command without `--instance` addresses it, exactly as now. No migration runs.

## A named instance

A name is 1–32 characters of `a-z`, `0-9` and `-`, starting with a letter. `dispatch` and `worker` are refused: the
first is the default instance's file name, the second is `worker.yaml`, which lives in the same folder.

For the name `team`:

| | Linux, macOS | Windows |
|---|---|---|
| Config | `~/.config/dispatch/team.yaml` | `%APPDATA%\Dispatch\team.yaml` |
| Secrets | `~/.config/dispatch/team.env` | `%APPDATA%\Dispatch\team.env` |
| State, log | `~/.local/state/dispatch-team/` | `%LOCALAPPDATA%\Dispatch-team\` |
| Service | systemd `dispatch-team.service`; launchd `io.dispatch.agent.team` | Task Scheduler `Dispatch-team` |
| Branches | `dispatch/team/<task>` | same |

The state directory is a sibling of the default one, not inside it, so no instance's files can collide with another's
`repos/` or `worktrees/`. `XDG_CONFIG_HOME` and `XDG_STATE_HOME` are honoured as `Locations` does today.

`Locations` gains the instance: `Locations.of(osName, env, home, instance)` returns the config file and state directory
above; `instance == null` returns today's values. The secrets file is already found beside the config
(`SecretsFile.beside`), and the config lock beside it too (`ConfigFile`: `team.yaml.lock`), so neither changes.

## Commands

Every command that reads an instance's config takes `--instance NAME`: `run`, `check`, `service`, `project add`, `ui`,
`init` and `ask`. `--config FILE` still works and wins over `--instance`, for the server layout and for tests. The
`worker` commands do not take it: a computer's worker is one per computer (W changes what it connects to, not how many
there are).

`dispatch list` (new) prints one line per instance found in the config folder — every `*.yaml` except `worker.yaml` whose
content loads as a Dispatch config:

```
default  @dispatch_task_bot   personal  running
team     @astvision_team_bot  team      stopped   (dispatch service start --instance team)
```

A file that does not load is listed with the loader's error, not skipped: something the person made should never vanish
from the list. `list` works offline: it shows each bot by its id (the digits before the token's colon), not its @username, so it never waits on Telegram.

## Services

`Service.Kind` stays the two kinds (Dispatch, worker). The instance is given when the service is made
(`Service.forThisMachine(kind, instance)`), because `stop`, `start`, `status` and `uninstall` never see a spec; each OS
writer derives its names from it: the unit, label or task name above. `Service.Spec` carries the instance too, so the
definition runs `dispatch run --instance team --log-file …`; the default instance's definition keeps `--config …`.

## Init

`dispatch init` without `--instance`, on a computer where the default instance already exists, no longer stops with
"already exists". It asks:

```
You already have @dispatch_task_bot on this computer.
Add another bot here? [y/N]
Name for it (team):
```

The question comes before "Just me / My team", so the suggestion cannot depend on that answer: it is `team`, or
`team-2`, `team-3` … when taken. Saying no ends init with today's advice (edit the file, `dispatch project add`,
`dispatch init --force`). `dispatch init --instance NAME` skips the question. `--force` keeps its meaning: redo the
instance it addresses.

A named instance's config is written with `branchPrefix: dispatch/team` (below) and its `stateDir`, like every config
init writes.

## Branch prefix

A new optional config key `branchPrefix`, default `dispatch`. Branches are `<branchPrefix>/<task>`. It replaces the
literal `"dispatch/"` in `Delivery` (`:44`, `:76`) and `Workspaces` (`:160`, `:176`). Init writes it for a named instance;
the default instance and every existing config keep `dispatch/<task>` because they never set it.

It is explicit config rather than derived from the file name, so the server layout (`/etc/dispatch/backend.yaml`) does
not start naming branches differently after an upgrade. Two instances may therefore share one clone: worktrees already
live under each instance's own state directory, and the prefixes keep their branches apart. `dispatch check` fails when
two instances on the computer have the same `branchPrefix` and share a clone path.

In team mode a member's worker creates and pushes the branch (ADR 0021), so the prefix travels with the run: the team
machine's `Coordinator` puts the whole branch name into each `Job` (`branch`), and a worker uses it. The field is left
out when the team uses the default prefix, so a worker older than this change keeps working with such a team; a team
with its own prefix needs its members' workers updated.

## Clashes

- **Bot token:** Telegram delivers a bot's updates to one reader, so init refuses a token another instance on this
  computer already uses ("@x already runs as instance default"), and `dispatch check` fails on it. Tokens are compared by
  the bot id before the colon, never logged.
- **Ports** (`miniApp.port`, `workers.port`): `dispatch init` suggests the first port from 7880 up that no other
  instance claims; the web setup keeps its 7880 default. `dispatch check` warns when two instances claim the same one.
  `dispatch ui` without `--port` takes 7878, or the next free port when 7878 is in use, and prints the one it took.

## Testing

- `LocationsTest`: default and named instance on Linux, macOS and Windows, with and without `XDG_*`; invalid names.
- Each service writer: the names for a named instance, and that the default instance's names are unchanged.
- `Delivery` and `Workspaces` with a `branchPrefix`, and without one.
- Init: an existing default instance leads to the question; yes writes `team.yaml`, `team.env` and the prefix; no
  writes nothing. The token clash is refused.
- `dispatch list` with a default instance, a named one, and one that fails to load.
- CI runs the suite on the three OSes as it does now.

## Afterwards on ZB's laptop

The team test instance becomes `dispatch init --instance team` and `dispatch service install --instance team`; the
hand-written `~/.config/systemd/user/dispatch-team.service` is removed.
