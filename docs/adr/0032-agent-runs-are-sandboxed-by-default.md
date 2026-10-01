# Agent runs are sandboxed by default

Amends ADR 0009, which left an OS sandbox as the next hardening step.

Every agent process Dispatch starts, for a plan, an execution, a split or an assistant turn, runs inside bubblewrap on
Linux. The whole root is read-only; the run's worktree, its clone's git dir (plans, executions and reviews only: a
split or an assistant turn never gets a repository, whatever its workdir holds), the agent's own state (`~/.claude`,
`~/.claude.json`, `~/.codex`, `~/.gemini`) and the build caches (`~/.m2`, `~/.gradle`, `~/.npm`, `~/.cache`) are
writable; `/tmp` is fresh per run. `~/.ssh`, `~/.config/gh`, `~/.gnupg`, Dispatch's config directory (which holds every
instance's secrets file), this instance's state directory and every configured clone are hidden behind an empty
directory. So are the config directories, state directories and configured clones of every other Dispatch instance on
the computer, so one instance's agent cannot read another's `dispatch.db`; if those instances cannot be discovered, the
run goes on and the log says `sandbox.instances_not_discovered` (one that does not load: `sandbox.instance_unreadable`).
The owner's runtime directory (`$XDG_RUNTIME_DIR`, else `/run/user/<uid>`) is hidden too, and IPC is unshared: its
sockets (systemd `--user`, ssh-agent, gpg-agent, D-Bus and the Secret Service) would otherwise run things outside the
sandbox. The network stays open. Permission modes and deny rules are unchanged: the sandbox is a layer under them.

Whatever an agent can write that later runs outside the sandbox is mounted back read-only: the worktree's `.git` file
(which names the git dir the next run mounts; it must name a configured clone's `.git` or `<stateDir>/repos/<name>/.git`,
or the run is refused), the clone's `config`, `hooks` and `info`, the admin dirs of the clone's other worktrees
(`<common>/worktrees`, with only the run's own mounted back writable) and its own `config.worktree` and `commondir`,
and the agent and build-tool config that runs code (`~/.claude/settings.json` and `settings.local.json`,
`~/.codex/config.toml`, `~/.gemini/settings.json`, `~/.gradle/init.d`, `~/.gradle/gradle.properties`,
`~/.m2/settings.xml`). Dispatch's own git, which runs with `GH_TOKEN`, also ignores hooks and `core.fsmonitor`, and
refuses to run in a redirected repository: a clone whose git dir holds a `commondir` file, or a worktree whose admin
`commondir` names anything but its own clone, a `.git` that is a symlink, or a `config.worktree` with content (Dispatch
creates each one empty when it adds a worktree, so the sandbox always mounts it read-only), fails the task instead of
reading a config an agent wrote. Each command then runs with `GIT_COMMON_DIR` set to the common dir just verified, so a
`commondir` another task's agent plants after the check is never read.

The clone's refs and objects stay writable, because an agent's own `git status`, `diff` and `add` need them, and all
tasks of a project share one clone: task B's agent can point `refs/heads/dispatch/<A>` at a commit it built, and A's
worktree, whose HEAD follows that branch, silently sits on B's commit. Dispatch therefore keeps, in the task row
(`head_sha`), the commit it last left each task's branch at: the base once the first planning run made the worktree, then
each delivery's commit, including one a failed push left behind. Every EXECUTE and DELIVER job carries it
(`expectedHead`). An execution whose branch is found anywhere else before its agent starts is refused (`SETUP`,
naming the branch, the expected and the found commit, logged as `task.branch_moved`): nothing is built on a commit
Dispatch did not make, and the check anchors the commit the run starts from. A move during the run, by another task's
agent or by the task's own agent committing, cannot reach the run's worktree files or index, which only its own agent
writes, so delivery folds it back (`git reset --soft` to the run's start, as for an agent's own commits) and the
delivery commit sits on that start and holds only the run's change; the move is logged as `task.branch_moved`. Delivery
refuses only a worktree whose HEAD left the branch (detached, or naming another branch), since its commit would land
where the push never looks (`DELIVERY`, nothing pushed). A DELIVER retry resets to what origin has, or to the base, so it
recovers a moved branch the same way. Of the run's
logs only Codex's `<log>.schema.json` is visible, never the log dir, so an assistant turn cannot read other members'
logged conversations.

A machine that cannot sandbox (no bwrap, user namespaces blocked, macOS, Windows, or `sandbox: off`) runs its agents as
before. It says so in `dispatch check`, at startup, and under every plan and result of such a run, so an unsandboxed run
is never silent. When bwrap itself fails to start a run, the run fails as `AGENT`; it is not retried without the sandbox.

A run's sandbox ends with its command (amended 2026-10-01). bwrap returns when the command exits, but its own init waits
for every process still inside, so a Gradle daemon an agent or a test command started lived on with the run's mounts,
outside Dispatch's process tree, where neither a cancel nor the orphan kill reached it; a later run's build reached it
through the shared `~/.gradle` and failed (`could not setcwd()`). The command now runs under `/bin/sh`, which, once the
command exits, kills every other process in the sandbox (`kill -1` in its pid namespace reaches nothing outside it),
waits up to five seconds for bwrap's init to reap them, and exits with the command's status. A build's daemon starts
afresh in each run.

We chose a wrapper Dispatch builds itself over each agent's own sandbox (three behaviours, no single policy), a
user-written wrapper command (nobody would get a sandbox by default) and Docker (an image per project, logins inside the
container, slower builds on macOS and Windows).

## Consequences

- An agent can no longer read the owner's SSH keys, `gh` login, GnuPG keys, Dispatch's secrets or any configured clone,
  nor write outside its task.
- It can still read its own login (it needs it), the worktree's code, and anything else readable under the root, and it
  can still reach the network: the sandbox narrows what a prompt injection can take, it does not remove it. Not
  protected, among others: `~/.git-credentials`, `~/.netrc`, `~/.npmrc`, `~/.docker/config.json`, `~/.aws`, `~/.kube`,
  keyring files, repositories that are not configured clones, `/var/run/docker.sock` (root-equivalent for a user in the
  `docker` group), abstract-namespace sockets (X11, some D-Bus buses) since the network namespace is shared, and the
  `mcpServers` in `~/.claude.json`, which stays writable because Claude Code writes it.
- A poisoned build cache is possible, since caches are writable; accepted on a developer's own machine.
- Agents cannot run `git worktree` (add, remove, prune) inside the sandbox: the clone's `worktrees` dir is read-only
  apart from the run's own admin dir.
- The guard does not stop the move. A branch moved before an execution keeps that task refused until someone points
  the branch back at the commit named in the failure, or retries it (a DELIVER retry rebuilds from origin or the base)
  or gives the work as a new task. A task from before the guard, or one a worker from before it delivered, has no known
  head and is not checked, and an older worker cannot read an EXECUTE or DELIVER job that carries the head: upgrade the
  team machine and its workers together.
- A worker reports the sandbox with each result, and a team machine from before this version refuses such a result:
  upgrade the team machine and its workers together.
- macOS gets its own sandbox (Seatbelt) only once someone can run it on a Mac.
- GitHub's Ubuntu 24 runners block unprivileged user namespaces, so the Linux CI job installs bubblewrap and sets
  `kernel.apparmor_restrict_unprivileged_userns=0` on its throwaway runner; the real-bwrap tests run on every push.
