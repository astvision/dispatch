# Agent sandbox (SB)

Status: approved design, 2026-09-30.

## Goal

Every agent run is sandboxed by default, so an unattended agent cannot read the owner's secrets or write outside its
task, and Dispatch keeps working, with a visible warning, on a machine that cannot sandbox. This is the "OS sandbox"
that ADR 0009 named as the next hardening step. It borrows the idea, not the code, of Sandcastle-style AFK tools.

In scope: a `Sandbox` layer that wraps every agent command line; a bubblewrap sandbox on Linux; detecting at startup
whether the machine can sandbox; falling back to no sandbox with a warning; recording per run whether it was sandboxed;
an ADR that amends ADR 0009.

Out of scope:
- **macOS Seatbelt.** Nobody here can run it live; macOS falls back to no sandbox until a follow-up tested on a real Mac.
  The interface below takes it without change.
- **Windows.** Always falls back.
- **Docker/Podman.** Possible later as another `Sandbox`; not planned.
- **Network restrictions.** The network stays open (ADR 0009: an allowlist needs tuning per project).
- **Changing agent permissions.** Permission modes, deny rules and withheld variables stay exactly as today; the sandbox
  is a layer under them, so a sandboxed and an unsandboxed run behave the same apart from blocked file access.
- **Per-project settings.** One setting per instance and per worker.

## Success criteria

1. On a Linux machine with a working `bwrap`, every agent process Dispatch starts (plan, execute, split, assistant) runs
   inside bubblewrap without any configuration.
2. Inside the sandbox the agent can edit its worktree, run `git status`/`git diff`, build and test (downloading new
   dependencies included), and use its own agent login; it cannot see `~/.ssh`, `~/.config/gh`, `~/.gnupg`, Dispatch's
   config and state, or other projects' clones, and cannot write anywhere else in `$HOME`.
3. On a machine without a sandbox, runs work as today, and the owner sees why on `dispatch check`, in the log at startup,
   and as a ⚠️ line under every plan and result of an unsandboxed run.
4. Cancelling a sandboxed run and orphan detection after a crash work as they do today.
5. A real task on the personal bot plans and executes with `sandbox = bubblewrap` recorded.

## Design

### The layer

A new package `dispatch.agent.sandbox`:

```java
public interface Sandbox {
    String name();                                   // "bubblewrap" or "none"
    List<String> wrap(List<String> commandLine, SandboxPolicy policy);
}

public record SandboxPolicy(Path workdir, Path gitCommonDir, List<Path> readOnly,
                            List<Path> writable, List<Path> hidden) {}
```

- `Bubblewrap` returns `bwrap <flags> -- <commandLine>`.
- `NoSandbox(String reason)` returns `commandLine` unchanged; its reason is what the warnings show.

`ProcessRun.start` calls `sandbox.wrap(commandLine, policy)` just before building the `ProcessBuilder`. The sandbox is
given to `ProcessRun` by whoever builds the agents; the three agents only contribute their state directories (below)
and otherwise do not change.

### The policy

`SandboxPolicies.of(request, agentStateDirs, dispatchDirs)` builds the policy for one run.

| Path | Access |
|---|---|
| `/` (system, toolchains) | read-only |
| the run's workdir (`worktrees/<task>`, `splits/…`, the assistant's directory) | read-write |
| the clone's git common dir (from the worktree's `.git` file) | read-write |
| the agent's state: Claude `~/.claude`, `~/.claude.json`; Codex `~/.codex`; Gemini `~/.gemini` | read-write |
| build caches: `~/.m2`, `~/.gradle`, `~/.npm`, `~/.cache` | read-write |
| `/tmp` | a fresh tmpfs per run |
| `request.readOnlyDirs()` (attachments) | read-only |
| the assistant only: `dispatch.db`, `-wal`, `-shm` | read-write |
| the assistant only: `assistant-bin` | read-only |
| `~/.ssh`, `~/.config/gh`, `~/.gnupg`, Dispatch's config dir (as built, the whole `~/.config/dispatch`, which holds every instance's secrets file), Dispatch's state dir, every configured project's clone | hidden (empty tmpfs) |
| the config dirs, state dirs and configured clones of every other Dispatch instance on the computer (`Instances.othersPrivate`), so one instance's agent cannot read another's `dispatch.db` | hidden (empty tmpfs) |
| `$XDG_RUNTIME_DIR` from the agent's environment, else `/run/user/<uid>`: systemd `--user`, ssh-agent, gpg-agent and Secret Service sockets | hidden (empty tmpfs) |
| the run's own log dir (Codex's `--output-schema`), unless it is the workdir or holds it | read-only |
| the worktree's `.git` file; the git common dir's `config`, `hooks`, `info`; for a linked worktree its `config.worktree` and `commondir` | read-only over the read-write git dir and workdir |
| `~/.claude/settings.json`, `~/.claude/settings.local.json`, `~/.codex/config.toml`, `~/.gemini/settings.json`, `~/.gradle/init.d`, `~/.gradle/gradle.properties`, `~/.m2/settings.xml` | read-only over the writable agent and cache dirs |

The worktree's `.git` file is agent-writable between runs, so the git dir it names must be a configured clone's `.git` or
`<stateDir>/repos/<name>/.git` (compared by real path); anything else fails the run with `AgentStartException`.
`~/.claude.json` stays writable because Claude Code writes it, so its `mcpServers` are not protected. Dispatch's own git
passes `-c core.hooksPath=/dev/null -c core.fsmonitor=false` to every command, since it runs with `GH_TOKEN` in clones
agents wrote to.

Discovering the other instances failing only logs WARN `sandbox.instances_not_discovered`; the run goes on. A hidden path that is not a directory is skipped, since bwrap cannot mount over it on a read-only root. Network is open. Build caches are writable by choice: an injected prompt could poison a shared cache, which is accepted
on a developer's own machine in exchange for builds that work as they do outside the sandbox.

### bubblewrap command line

```
bwrap --die-with-parent --unshare-pid --unshare-ipc --new-session
      --ro-bind / /  --dev /dev  --proc /proc  --tmpfs /tmp
      --tmpfs <hidden>…                       # hide first
      --bind <workdir> <workdir>              # then mount back what the run needs
      --bind <gitCommonDir> <gitCommonDir>
      --bind-try <writable>…                  # optional: a missing ~/.gemini is not an error
      --ro-bind-try <readOnly>…               # last: read-only over the read-write mounts above
      --chdir <workdir>
      -- <commandLine>
```

The order is the point: the workdir and the git dir live under Dispatch's state dir or a hidden clone, so they are
mounted back after the hiding. Required paths use `--bind` so a wrong one fails loudly; optional ones use `-try`.

bwrap is the process Dispatch starts, records (pid and start time) and terminates. `ProcessTrees.terminate` ends the
whole tree, and `--die-with-parent` ends the sandbox if Dispatch itself dies, so cancelling and orphan detection are
unchanged.

### Detection

`Sandboxes.detect(setting)` runs once at startup, in `dispatch run`, `dispatch worker run` and `dispatch check`:

- `sandbox: off` → `NoSandbox("turned off in config")`.
- `sandbox: auto` (the default):
  - Linux: `bwrap` on the PATH, and a trial `bwrap --ro-bind / / --unshare-pid --proc /proc true` exits 0 → `Bubblewrap`. Otherwise
    `NoSandbox` with the reason (not installed; the trial's stderr, e.g. user namespaces blocked by AppArmor on
    Ubuntu 24).
  - macOS → `NoSandbox("macOS sandbox not built yet")`.
  - Windows → `NoSandbox("not available on Windows")`.

The OS name, PATH lookup and trial are passed in, so every branch is testable. A trial that times out (5 s) is killed and reaped; an IOException's `toString()` is the reason.

### Configuration

`sandbox: auto | off` in the instance config and in `worker.yaml`, default `auto`. Anything else is refused at config
load. A team member's worker decides for its own computer; the team machine's setting covers only its own agent
processes (split, assistant).

### Warnings and recording

- `dispatch check`: `sandbox: bubblewrap ✓` or `sandbox: none ⚠️ <reason>` (a warning, never a failure).
- Startup log: `sandbox.selected name=bubblewrap` or `sandbox.unavailable reason=…` (WARN).
- Each run stores the sandbox it ran in (`run.sandbox`, migration 029; `NULL` for runs before it). A remote worker
  reports it in the result's `AgentResult`.
- A plan or result of a run with sandbox `none` carries a ⚠️ line, "not sandboxed: <reason>", in the same place as the
  model-mismatch line: in Telegram (Mongolian). The Mini App and `dispatch ui` show neither line yet.

### Errors

- bwrap fails to start the run (a required path missing, a mount error): the run fails as `AGENT` with the stderr tail,
  like any agent that fails to start. No retry without the sandbox: that would hide a broken sandbox behind a working
  run.
- The agent writes where it may not: it sees `Read-only file system` or `Permission denied` as a tool error. That is not
  a run failure, as permission denials are not (ADR 0009).

## Testing

Unit tests (every OS in CI):
- `Bubblewrap.wrap`: the exact argument list for a policy, including hiding before mounting back.
- `SandboxPolicies.of`: hidden, writable and read-only lists for each agent kind and for a worktree under the state dir
  and under a member's own clone.
- `Sandboxes.detect`: `off`, no bwrap, trial fails, trial passes, macOS, Windows.
- `NoSandbox.wrap` returns the command unchanged; the ⚠️ line appears for `none` and never for `bubblewrap` or `NULL`.
- Config: `auto`, `off`, missing (→ `auto`), invalid (refused).

Integration test with real bwrap (Linux; skipped with its reason when the trial fails): a fake agent script in the
sandbox writes a file in the worktree, runs `git status` there, fails to read a planted `~/.ssh/id_test`, fails to
write `$HOME/x`, and a cancel ends it and its children. GitHub's Ubuntu 24 runners block unprivileged user namespaces.
As built, no CI step installs bubblewrap or sets `kernel.apparmor_restrict_unprivileged_userns=0` (awaiting the owner's
decision), so `BubblewrapSandboxTest` skips itself with its reason there; it runs on a machine with a working bwrap.

Live check: one real task on the personal bot, plan and execution, with `run.sandbox = bubblewrap`.

## Documentation

- ADR 0032 "Agent runs are sandboxed by default" amends ADR 0009: the OS sandbox is adopted for the filesystem, the
  network stays open, permissions are unchanged, and a machine without a sandbox runs unsandboxed with a warning.
- ARCHITECTURE.md "Agent boundary" gains the sandbox wrapping and the policy table.
- SECURITY.md: what the sandbox protects (other secrets, other repos, the rest of `$HOME`) and what it does not (the
  agent's own login, the network, the worktree's code).
- README (mn, en): `bubblewrap` as a recommended package on Linux and the `sandbox` setting.
