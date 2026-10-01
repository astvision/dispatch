# An agent's own state is copy-on-write, or read-only where a later session loads it

Amends ADR 0032 (the sandbox).

A sandboxed agent may write its own state directory, because it needs to: transcripts, credentials, caches. Much of what
is there is loaded by the owner's next session of that agent, outside any sandbox, as code or instructions: plugins and
their hooks, skills, subagents, commands, global instruction files, MCP servers, hook files and trust lists. ADR 0032
protected only the config files. Now each agent declares its state, and the sandbox treats it as follows:

- **Claude Code:** `~/.claude` is mounted copy-on-write (`--overlay-src --tmp-overlay`): the agent sees all of it and its
  writes land in a tmpfs that ends with the sandbox. Only `.credentials.json` and the run's own project dir under
  `projects/` are bound back: the dir Claude Code 2.1.286 names after the working dir, which holds the transcripts resume
  and teleport need. Its `memory/` is a tmpfs, since the owner's later sessions in a project load its auto-memory; no
  other project's dir is bound. Claude Code renames its credentials into place and writes in place when that rename fails
  with `EBUSY`, so a refreshed token still reaches the owner. `~/.claude.json` gets an owner-only throwaway copy per run,
  which also closes the MCP-server gap ADR 0032 accepted.
- **Codex and Gemini CLI:** their loader paths (Codex 0.152.0: `config.toml`, `.env`, `AGENTS.md`, `AGENTS.override.md`,
  `hooks.json`, `prompts/`, `skills/`, `plugins/`, `rules/`, `memories/`; Gemini CLI 0.61.0: `settings.json`, `.env`,
  `GEMINI.md`, `extensions/`, `commands/`, `skills/`, `agents/`, `policies/`, `acknowledgments/`, `trustedFolders.json`)
  are bound read-only when present. Bubblewrap cannot protect a path that does not exist, so a run guard records the
  absent ones and, after the run, moves any that appeared to `<stateDir>/quarantine` and logs it. One that cannot be moved
  there, such as a non-empty dir on another filesystem, is renamed in place to a name no agent loads.
- **Bubblewrap without overlays** (older than 0.10): Claude Code gets the read-only loader paths (also `rules/` and
  `local/`) and the guard instead; `projects/` is read-only with the run's own dir bound back over it and its `memory/` a
  tmpfs, as are the dirs a later session sources (`session-env/`, `shell-snapshots/`, `sessions/`, and `plugins/store/`
  when `plugins/` exists). Startup and `dispatch check` say so.
- **The guard's lifecycle:** it records what it must undo in `<stateDir>/guards/` before the run starts. After a cancel,
  the sandbox's outer process exits while the processes inside it are still being ended, so the guard sweeps then and
  again once the whole tree is gone. A record a crash left behind is closed at the next start, after the orphan kill.

We chose this over:
- **Copy-on-write for every agent.** Codex keeps live SQLite databases in the root of `~/.codex`, and Gemini CLI renames
  files into place; neither persists safely through binds that cannot be checked until their live check.
- **Read-only loader paths for every agent.** It misses what a later version of an agent starts to load, which
  copy-on-write covers by construction.
- **Leaving it.** A planted plugin hook runs as the owner the next time they open the agent.

Consequences: a planted loader path exists until its run ends, so a session the owner starts in that window could load
it. Claude's fallback protects only the listed paths. Claude Code's persisted paths and project-dir names are 2.1.286's;
a version that keeps something new under `~/.claude` loses it at the end of each run until the list is updated, and one
that names project dirs differently cannot resume a session, loudly. If the owner's own session refreshes the token
during a run, the run keeps the file it started with; a refresh it then needs can fail, loudly.
