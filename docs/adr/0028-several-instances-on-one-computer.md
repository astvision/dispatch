# Several instances on one computer

One computer can now run more than one Dispatch instance at a time, each set up with `dispatch init` and kept running
with `dispatch service install`, without either being aware of the other except to avoid clashing with it. The
instance a computer already has is unchanged: it keeps `dispatch.yaml`, `dispatch.env`, its state directory, its
service name and `dispatch/<task>` branches, exactly as before. A named instance (`--instance team`) gets its own
sibling config, secrets file, state directory and service, named after it (`team.yaml`, `dispatch-team.service`, and
so on for launchd and Task Scheduler), so its `repos/` and `worktrees/` can never collide with another instance's.

`dispatch init`, run again on a computer that already has an instance, offers to add another beside it instead of
refusing; `dispatch list` shows every instance found in the config folder, each by its bot id (never its @username,
so it works offline) and its service status. Every command that reads an instance's config takes `--instance NAME`.
`--config FILE` still exists for the server layout and for tests; where a command only reads its config once (`run`,
`check`, `project add`, `ask`), `--config` wins when both are given. Where a command derives something durable from
the instance's name (`service`, `ui`, `init`), the combination is refused instead of silently picking one:
"--config and --instance both name the bot to use; give one of them".

Each instance gets an explicit `branchPrefix` (default `dispatch`, so an existing config's branches don't change) as
a new config key rather than one derived from the config's file name. `dispatch init` writes it for a named instance;
the default instance and every existing config leave it out and keep `dispatch/<task>`. A worker still makes and
pushes the branch on its own computer (ADR 0021); the team machine's `Coordinator` now puts the whole branch name
into the `Job` it sends, and the field is left out when the team uses the default prefix. `dispatch check` fails
when two instances on the computer share both a `branchPrefix` and a clone path.

We chose this because a single person increasingly wants both a personal bot and their team's bot running on the
same machine, and a config-derived branch prefix (e.g. the server layout's `/etc/dispatch/<team>.yaml`) would rename
the branches of every team already running, on an upgrade that only added several-instance support elsewhere. An
explicit key defaulting to today's value changes nothing for anyone who doesn't ask for it.

## Consequences

- A team on the default prefix keeps working with workers on the previous version, because `Job.branch` is left out
  for it; a team that sets its own `branchPrefix` needs its members to update their workers first, since an older
  worker does not know to prefix branches Dispatch itself expects.
- Setting `branchPrefix` on an instance that already has tasks points their retries and follow-ups at branches that
  no longer exist (the old ones were `dispatch/<task>`, not `<prefix>/<task>`); it should only be set once, when the
  instance is created — which is what `init` does for a named instance, and why the default instance never gets one.
- One worker per computer for now: `dispatch worker` commands do not take `--instance`, because a worker's job is to
  serve the whole computer, not one instance on it. A worker serving several teams at once is a later milestone (W).
