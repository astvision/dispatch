# Agent runs are sandboxed by default

Amends ADR 0009, which left an OS sandbox as the next hardening step.

Every agent process Dispatch starts, for a plan, an execution, a split or an assistant turn, runs inside bubblewrap on
Linux. The whole root is read-only; the run's worktree, its clone's git dir, the agent's own state (`~/.claude`,
`~/.claude.json`, `~/.codex`, `~/.gemini`) and the build caches (`~/.m2`, `~/.gradle`, `~/.npm`, `~/.cache`) are
writable; `/tmp` is fresh per run. `~/.ssh`, `~/.config/gh`, `~/.gnupg`, Dispatch's config directory (which holds every
instance's secrets file), this instance's state directory and every configured clone are hidden behind an empty
directory. So are the state directories and configured clones of every other Dispatch instance on the computer, so one
instance's agent cannot read another's `dispatch.db`; if those instances cannot be discovered, the run goes on and the
log says `sandbox.instances_not_discovered`. The network stays open. Permission modes and deny rules are unchanged: the
sandbox is a layer under them.

A machine that cannot sandbox (no bwrap, user namespaces blocked, macOS, Windows, or `sandbox: off`) runs its agents as
before. It says so in `dispatch check`, at startup, and under every plan and result of such a run, so an unsandboxed run
is never silent. When bwrap itself fails to start a run, the run fails as `AGENT`; it is not retried without the sandbox.

We chose a wrapper Dispatch builds itself over each agent's own sandbox (three behaviours, no single policy), a
user-written wrapper command (nobody would get a sandbox by default) and Docker (an image per project, logins inside the
container, slower builds on macOS and Windows).

## Consequences

- An agent can no longer read the owner's SSH keys, GitHub token or other repositories, nor write outside its task.
- It can still read its own login (it needs it), the worktree's code, and anything else readable under the root, and it
  can still reach the network: the sandbox narrows what a prompt injection can take, it does not remove it.
- A poisoned build cache is possible, since caches are writable; accepted on a developer's own machine.
- A worker reports the sandbox with each result, and a team machine from before this version refuses such a result:
  upgrade the team machine before its workers.
- macOS gets its own sandbox (Seatbelt) only once someone can run it on a Mac.
- On GitHub's Ubuntu 24 runners unprivileged user namespaces are blocked, so the real-bwrap test skips itself there
  until CI is set up for it.
