# Agent state guard (SG): what a later session loads stays out of a sandboxed agent's reach

Status: approved design, 2026-10-01; built on branch agent-state-guard (see [ADR 0035](../../adr/0035-an-agents-own-state-is-copy-on-write-or-read-only.md)). Amends ADR 0032 (the sandbox).

## Goal

A sandboxed agent may write its own state directory (`~/.claude`, `~/.codex`, `~/.gemini`) because it needs to: session
transcripts, credentials, caches. ADR 0032 protects only the agents' config files there (`settings.json`, `config.toml`).
Much more in those directories is loaded by the owner's next session of that agent, outside any sandbox, as code or as
instructions: plugins and their hooks, skills, subagents, commands, global instruction files, MCP servers, hook files and
trust lists. After this change, nothing a sandboxed run writes to those places reaches a later session.

In scope: Claude Code's `~/.claude` mounted copy-on-write with a short list of paths bound back so they persist, and a
throwaway copy of `~/.claude.json` per run; for Codex and Gemini CLI, read-only binds over the paths a later session
loads, and a sweep after each run that moves aside any such path the run created; detecting overlay support at startup,
with Claude falling back to the read-only paths without it; ADR 0035.

Out of scope:
- **Unsandboxed runs** (macOS, Windows, no `bwrap`, `sandbox: off`). Their agents can write anywhere the owner can; ADR 0032
  already says so wherever such a run is reported.
- **Copy-on-write for Codex and Gemini CLI.** Codex keeps live SQLite databases in the root of `~/.codex`, and Gemini CLI
  replaces its JSON files by renaming temporary files over them; their persistence through binds cannot be verified
  until their pending live check.
- **What the owner's `CLAUDE.md` imports from elsewhere.** Copy-on-write covers it; Claude's read-only fallback cannot know
  it.

## Success criteria

1. In a sandboxed Claude Code run on a machine whose bubblewrap supports overlays, a file the agent writes anywhere under
   `~/.claude` except its own project dir under `projects/` (but that dir's `memory/`) and `.credentials.json` is gone
   when the run ends.
2. A later run resumes the session an earlier run started (the transcript in the run's own `~/.claude/projects/<dir>`
   persists), and a token refreshed during a run is in the owner's `~/.claude/.credentials.json` afterwards.
3. Nothing a run writes to `~/.claude.json` reaches the real file.
4. In a sandboxed Codex or Gemini CLI run, each of that agent's loader paths that exists is read-only, and one the run
   created where none existed is moved to the instance's quarantine after the run and logged.
5. On bubblewrap without overlay support, Claude Code runs get the read-only loader paths and the sweep instead, and
   startup and `dispatch check` say so.
6. Unsandboxed runs, the worktree, the caches, the hidden directories and the skills plugin behave exactly as before.

## Each agent's state

An agent declares what it keeps in the owner's home, relative to it:

| | Claude Code | Codex | Gemini CLI |
|---|---|---|---|
| Directory | `.claude` | `.codex` | `.gemini` |
| Copy-on-write | yes | no | no |
| Project dirs (the run's own persists, its `memory/` a tmpfs; without copy-on-write the rest read-only) | `.claude/projects` | | |
| Persisted (only under copy-on-write) | `.claude/.credentials.json` | | |
| Throwaway copy per run | `.claude.json` | | |
| Loader paths (read-only when present, swept when absent) | `.claude/settings.json`, `.claude/settings.local.json`, `.claude/CLAUDE.md`, `.claude/agents`, `.claude/skills`, `.claude/plugins`, `.claude/commands`, `.claude/output-styles`, `.claude/hooks`, `.claude/rules`, `.claude/local` (used only without copy-on-write) | Codex 0.152.0: `.codex/config.toml`, `.codex/.env`, `.codex/AGENTS.md`, `.codex/AGENTS.override.md`, `.codex/hooks.json`, `.codex/prompts`, `.codex/skills`, `.codex/plugins`, `.codex/rules`, `.codex/memories` | Gemini CLI 0.61.0: `.gemini/settings.json`, `.gemini/.env`, `.gemini/GEMINI.md`, `.gemini/extensions`, `.gemini/commands`, `.gemini/skills`, `.gemini/agents`, `.gemini/policies`, `.gemini/acknowledgments`, `.gemini/trustedFolders.json` |
| Scratch (a tmpfs, only without copy-on-write, where its parent exists) | `.claude/session-env`, `.claude/shell-snapshots`, `.claude/sessions`, `.claude/plugins/store` | | |

The agents' entries leave `SandboxPolicies.CODE_IN_WRITABLE`, which keeps only the build tools' (`~/.gradle/init.d`,
`~/.gradle/gradle.properties`, `~/.m2/settings.xml`).

Why these persist: Claude Code 2.1.286, probed in a plan and an execution run, writes `~/.claude.json`,
`~/.claude/projects/<dir>/`, `~/.claude/sessions` and a built-in plugin's cache file under `~/.claude/plugins/store/`.
`<dir>` is the working dir with every char but a letter or digit made '-'; over 200 chars, its first 200, '-' and the
base-36 Java `String.hashCode` of the path. A resume reads only the transcript there, so `sessions/` (a registry of live
sessions) and the plugin cache may be thrown away. Only the run's own `<dir>` persists: every project dir holds the
auto-memory the owner's later sessions in that project load, and a run's own `memory/` is a tmpfs for that reason. It writes credentials with an atomic rename that falls back to writing in place when the
rename fails with `EBUSY`, which is what a rename over a bind-mounted file gives; so the bind keeps the owner's token
current.

## Mounts

The sandbox mounts, in this order (today's order, with the new steps marked):

1. The read-only root, `/dev`, `/proc`, a fresh `/tmp`; the hidden directories as empty tmpfs.
2. The workdir, the git dir, the writable paths (Codex's and Gemini's directories, the caches).
3. **New:** each copy-on-write directory: `--overlay-src <dir> --tmp-overlay <dir>`. Writes land in a tmpfs that ends with
   the sandbox. Then each throwaway copy: `--bind <copy> <home>/.claude.json`.
4. The read-only paths: the attachments, the skills plugin, Codex's schema file, the build tools' config, the git
   control files, each loader path that exists, and without copy-on-write `~/.claude/projects`.
5. **New:** each persisted path that exists: `--bind-try <path> <path>`, over the overlay or the read-only projects dir.
6. **New:** each tmpfs (the run's own `memory/`, and without copy-on-write the scratch dirs): `--tmpfs <dir>`, last.

## A run's lifecycle

`Confinement` gains a run guard around the process:

- **Before the process starts:** the dirs the mounts need are made owner-only (the run's own project dir and its
  `memory/`, the scratch dirs). When there is anything to undo, the guard records it in
  `<stateDir>/guards/<task>-<seq>[.fix-N|.review].json` (owner-only): the copies and the absent loader paths. Then a
  `~/.claude.json` is copied to `<logBase>.claude.json` (owner-only); the sandbox binds the copy.
- **After the process exits**, whether it finished, failed, was cancelled or timed out (`Process.onExit`): each recorded
  absent loader path that now exists is moved to `<stateDir>/quarantine/<task>-<seq>[.fix-N|.review]/<path relative to
  home>` (`.2`, `.3`, ... when planted again) and logged as `sandbox.quarantined path=<path> run=<logBase>` (WARN).
  Moved rather than deleted: the owner may have created it at the same moment, as Codex creates `memories/` on first
  use. The quarantine is owner-only and never pruned. Then the guard closes: the copy and the record are deleted.
- **After a cancel**, the sandbox's outer process can exit while the processes inside it are still being terminated:
  the guard sweeps at the exit and closes, with a last sweep, only once `ProcessTrees.terminate` has ended the whole tree;
  `await()` returns after that.
- **At startup**, after the orphan kill, the instance and the worker close every record a crash left in `guards/`
  (`sandbox.guard_left`, WARN).

An unsandboxed run has no guard: no copy, no sweep.

## Detecting overlay support

`Sandboxes.detect` runs a second trial after the existing one:
`bwrap --ro-bind / / --unshare-pid --proc /proc --overlay-src /etc --tmp-overlay /etc true`. Bubblewrap 0.10 added
overlays and the kernel must allow them in a user namespace; the trial answers both. `Bubblewrap` carries the answer, the
startup log says `sandbox.selected name=bubblewrap overlay=true|false`, and `dispatch check` adds a WARN line when it is
false: Claude Code falls back to read-only loader paths.

## Errors

- **The `~/.claude.json` copy cannot be made:** the run fails as `AGENT` (`AgentStartException`) with the path and the
  reason, before the agent starts.
- **A quarantine move fails** (a non-empty dir on another filesystem): the path is renamed beside itself to
  `<name>.dispatch-quarantined-<run>`, which no agent loads, and logged as `sandbox.quarantined_in_place` (ERROR); if that
  fails too, `sandbox.quarantine_failed path=<path> error=<reason>` (ERROR). The run's result stands.
- **The record or a dir cannot be made:** the run fails as `AGENT` before the agent starts, leaving nothing to undo.
- **A record cannot be read at startup:** `sandbox.guard_unreadable` (ERROR); it is kept for the owner to look at.
- **The copy cannot be deleted:** `sandbox.copy_left path=<path>` (WARN).
- **A persisted path or `~/.claude.json` does not exist:** nothing is bound for it, as `--bind-try` does today.

## Limits

- **The window:** a loader path the run creates where none existed exists until the run ends; a session the owner starts
  in that window could load it.
- **Claude's fallback** protects only the listed paths: the loader paths and other projects' dirs are read-only, the run's
  own memory and the scratch dirs are thrown away, and the rest of `~/.claude`, such as files the owner's `CLAUDE.md`
  imports from elsewhere under it, stays writable.
- **Credentials and a concurrent session:** the bind holds `.credentials.json` as it was when the run started. If the
  owner's own Claude Code session refreshes its token during the run, it replaces the file by a rename the run does not
  see; a refresh the run then needs can fail as an expired login. The run fails loudly and a retry binds the new file.
- **Agent versions:** the persisted paths and the project-dir names are Claude Code 2.1.286's; a version that persists
  something new elsewhere under `~/.claude` loses it at the end of each run until the list is updated, and one that names
  project dirs differently cannot resume a session, loudly. The loader lists are Codex 0.152.0's and Gemini CLI 0.61.0's.

## Testing

- **`SandboxPolicies`:** Claude with overlay support gets the overlay, the persisted binds and the copy, and no loader
  binds; without it, the loader binds; Codex and Gemini get their present loader paths read-only; absent ones are left to
  the guard.
- **`Bubblewrap`:** the argument order above, overlay after the writable binds, persisted binds after the overlay,
  read-only binds last.
- **Real bubblewrap** (`BubblewrapSandboxTest`, skipped without a working `bwrap` or overlays): with a temporary home, a
  file planted under the overlaid `.claude` is gone after the run, a write under the run's own project dir survives, its
  memory and another project's memory do not change, and a write to the bound `.claude.json` copy leaves the real file
  unchanged; without overlays, another project's dir is read-only, and the run's memory and a `session-env` file are gone.
- **The guard:** a loader path created during the run is moved to the quarantine whole and logged; one that existed
  before is left alone; one that cannot move is renamed in place; the copy is owner-only and deleted; a sweep may run
  again until the guard closes; a loader planted while a cancelled sandbox is dying is swept once it is dead; the record
  exists until the guard closes, and one a crash left is closed at the next start of the instance and of the worker; an
  unsandboxed run, or one with nothing to undo, has no record.
- **Detection:** the overlay trial's exit decides `overlay=true|false`; `dispatch check` reports it.
- **Live, opt-in** (`DISPATCH_LIVE_CLAUDE=1`): two Claude Code runs through the real sandbox, the second resuming the
  first's session, and a file the first plants in `~/.claude/agents` is gone afterwards; with overlays and without.

## Documentation

- ADR 0035, amending ADR 0032: an agent's own state is copy-on-write where the sandbox can, and read-only where a later
  session loads it; the rejected options (copy-on-write for every agent, read-only paths for every agent, leaving it).
- SECURITY.md: the sandbox section's protects and does-not-protect lists, the quarantine, the window and the fallback.
- ARCHITECTURE: the run guard in the agent boundary.
