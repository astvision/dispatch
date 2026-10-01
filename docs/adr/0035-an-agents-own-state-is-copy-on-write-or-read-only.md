# An agent's own state is copy-on-write, or read-only where a later session loads it

Amends ADR 0032 (the sandbox).

A sandboxed agent may write its own state directory, because it needs to: transcripts, credentials, caches. Much of what
is there is loaded by the owner's next session of that agent, outside any sandbox, as code or instructions: plugins and
their hooks, skills, subagents, commands, global instruction files, MCP servers, hook files and trust lists. ADR 0032
protected only the config files. Now each agent declares its state, and the sandbox treats it as follows:

- **Claude Code:** `~/.claude` is mounted copy-on-write (`--overlay-src --tmp-overlay`): the agent sees all of it and its
  writes land in a tmpfs that ends with the sandbox. Only `projects/` (transcripts, which resume and teleport need),
  `sessions/` and `.credentials.json` are bound back. Claude Code renames its credentials into place and writes in place
  when that rename fails with `EBUSY`, so a refreshed token still reaches the owner. `~/.claude.json` gets a throwaway
  copy per run, which also closes the MCP-server gap ADR 0032 accepted.
- **Codex and Gemini CLI:** their loader paths (Codex: `config.toml`, `AGENTS.md`, `hooks.json`, `prompts/`, `skills/`,
  `plugins/`, `rules/`, `memories/`; Gemini: `settings.json`, `GEMINI.md`, `extensions/`, `commands/`,
  `trustedFolders.json`) are bound read-only when present. Bubblewrap cannot protect a path that does not exist, so a
  run guard records the absent ones and, after the run, moves any that appeared to `<stateDir>/quarantine` and logs it.
- **Bubblewrap without overlays** (older than 0.10): Claude Code gets the read-only loader paths and the guard instead,
  and startup and `dispatch check` say so.

We chose this over:
- **Copy-on-write for every agent.** Codex keeps live SQLite databases in the root of `~/.codex`, and Gemini CLI renames
  files into place; neither persists safely through binds that cannot be checked until their live check.
- **Read-only loader paths for every agent.** It misses what a later version of an agent starts to load, which
  copy-on-write covers by construction.
- **Leaving it.** A planted plugin hook runs as the owner the next time they open the agent.

Consequences: a planted loader path exists until its run ends, so a session the owner starts in that window could load
it. Claude's fallback protects only the listed paths. Claude Code's persisted paths are 2.1.286's; a version that keeps
something new under `~/.claude` loses it at the end of each run until the list is updated. If the owner's own session
refreshes the token during a run, the run keeps the file it started with; a refresh it then needs can fail, loudly.
