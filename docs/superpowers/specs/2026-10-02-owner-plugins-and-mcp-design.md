# Owner plugins and MCP servers (OP): what a machine's owner lists, Claude Code runs on that machine load

Status: approved design, 2026-10-02; to build on branch owner-plugins (see ADR 0036). Amends ADR 0034 (the owner's own
plugins never load into a run) and the strict MCP configuration of every Claude Code run (ADR 0009, ADR 0024).

## Goal

Dispatch's Claude Code runs load Dispatch's vetted skills plugin, Claude Code's built-in skills and the project's own
`.claude/`, and nothing of the owner's: `--setting-sources project,local` keeps user-level configuration out, and
`--strict-mcp-config` keeps every MCP server out. After this change, the owner of each machine names the plugins and
MCP servers that machine's runs may load, and the agent uses them where they fit: the frontend-design skill on a UI
task, Playwright's browser to check a page, the owner's MongoDB server for a data task.

In scope: two lists per machine, in the instance's `dispatch.yaml` and in a worker's `worker.yaml`; resolving them
before each run with that machine's own Claude Code; loading each listed plugin with `--plugin-dir`; one MCP
configuration per run, built by Dispatch from the listed servers and the listed plugins' own servers, with
`--strict-mcp-config` kept; `dispatch check`; ADR 0036.

Out of scope:
- **Per-project lists.** A machine's lists cover every project it runs; a project that needs a different set waits for
  its own change.
- **Everything the owner has enabled.** Plugins built for a person at the keyboard (a hook that tells the agent to stop
  and ask, a status line) load only when listed.
- **Other MCP sources:** project- and local-scope servers, a repository's `.mcp.json`, claude.ai connectors.
- **Splits, assistant turns, Codex and Gemini CLI runs:** unchanged.
- **Installing plugins.** Dispatch loads what the machine has installed.

## Success criteria

1. With `plugins: [playwright@claude-plugins-official]` under `agents.claude-code`, a sandboxed execution run's init
   event lists the playwright plugin and its server `plugin_playwright_playwright` as connected, and the agent can call
   one of its tools.
2. With `mcpServers: [mongodb]`, the run's MCP configuration holds the definition of the user-scope server `mongodb`
   from `~/.claude.json`, and no server that was not listed starts: no claude.ai connector, no `.mcp.json` of the
   repository.
3. A listed plugin that is not installed, or a listed server that is not defined, fails the run as `AGENT` before its
   agent starts, naming it and what to do; `dispatch check` fails with the same text.
4. A worker loads its own `claudePlugins` and `claudeMcpServers` from its own Claude Code, whatever the team machine
   lists.
5. With both lists empty or absent, every run's command line is exactly what it is today.
6. The run's MCP configuration travels on its command line, as one line of JSON, and is never written to disk.
7. An edit to either list in `dispatch.yaml` or `worker.yaml` applies to the next run, with no restart.

## Configuration

The instance's `dispatch.yaml`, beside the command it already has:

```yaml
agents:
  claude-code:
    command: /home/ann/.local/bin/claude
    plugins: [frontend-design@claude-plugins-official, playwright@claude-plugins-official]
    mcpServers: [mongodb]
```

A member's `worker.yaml`, beside `claudeCommand`:

```yaml
claudeCommand: /home/ann/.local/bin/claude
claudePlugins: [frontend-design@claude-plugins-official]
claudeMcpServers: [mongodb]
```

A plugin is named by its id as `claude plugin list` prints it (`<name>@<marketplace>`). A server is named as the owner
added it at user scope (`claude mcp add --scope user <name> ...`). The lists cover every project that instance or worker
runs, and each machine's own lists apply to the runs on it: a team machine's lists never reach a member's computer.
`plugins` or `mcpServers` under any agent but `claude-code` is a configuration error.

Unlike every other setting, which Dispatch reads once at startup, the two lists are read again from the file on disk
before each run, so adding or removing a plugin or server applies to the next run without a restart. The desktop UI does
not edit them: they are edited in the file.

## Resolving the lists

Before each plan, execution (with its fix rounds) and review run, Dispatch reads the two lists from the instance's
`dispatch.yaml` or the worker's `worker.yaml` as the file is at that moment, and, when either list is not empty:

- **Plugins:** `<claude> plugin list --json` on that machine; each listed id must be among its entries, and the entry's
  `installPath` is the plugin's directory. Installed is enough: whether the owner has it enabled interactively is not
  consulted, since the list is explicit. Resolving per run (one to two seconds) picks up a plugin installed or updated
  since the last run.
- **Servers:** each listed name must be a key of `mcpServers` in `~/.claude.json` of the machine's user.
- **A listed plugin's own servers:** from the file its `.claude-plugin/plugin.json` names in `mcpServers` (a path
  relative to the plugin's directory), else from `.mcp.json` in the plugin's directory; either form holds the servers
  under `mcpServers` or as a bare map. Servers given inline in `plugin.json` are taken as they are. A plugin with none
  brings none.

## A run

For Claude Code PLAN, EXECUTE (fix rounds included) and REVIEW runs, the command line gains:

- per listed plugin: `--plugin-dir <installPath>` and `--add-dir <installPath>`, so that plan and review runs may read the
  plugin's skill files, as for the dispatch plugin (ADR 0034);
- `Skill` in `--tools` when any plugin is listed, as it already is with `skills: on`;
- `--mcp-config <the configuration, as one line of JSON>` when any server is listed or brought by a listed plugin.
  `--strict-mcp-config` stays, so nothing outside it starts.

The MCP configuration:

```json
{"mcpServers": {
  "mongodb": {"...": "the definition of mongodb in ~/.claude.json, as it is"},
  "plugin_playwright_playwright": {"command": "npx", "args": ["@playwright/mcp@latest"]}
}}
```

- A listed server keeps its own name. A plugin's server is named `plugin_<plugin>_<server>`, `<plugin>` being the id
  before `@`: the name Claude Code gives it in the owner's own sessions, so its tools are called the same
  (`mcp__plugin_playwright_playwright__browser_navigate`), and it cannot clash with the owner's server of the same name.
- `${CLAUDE_PLUGIN_ROOT}` in a plugin's server definition is replaced with the plugin's directory. Other `${VAR}`
  placeholders are left for Claude Code to fill from the agent's environment: the service's environment without
  Dispatch's own secrets.
- Passed inline, compacted to one line as `--json-schema` is (Claude Code's `--mcp-config` takes files or strings):
  nothing is written to disk, mounted into the sandbox, or left behind by a crash. Dispatch never logs a run's command
  line.

## Sandbox

Nothing new is mounted:

- A plugin's directory is under `~/.claude/plugins`: readable through the copy-on-write overlay, or read-only as one of
  Claude Code's loader paths without overlays. What a plugin writes under `~/.claude` ends with the run.
- A plugin's hooks and a listed server run inside the sandbox, as the agent's children: the network is open, the build
  caches are writable (npx's cache, Playwright's browsers under `~/.cache`), and the hidden directories and the owner's
  runtime sockets are absent. A server that needs them fails to start; Claude Code reports it in the run's init event,
  and Dispatch logs `agent.mcp_failed server=<name> run=<logBase>` (WARN).

## Errors

- **A listed plugin is not installed, or a listed server is not defined:** the run fails as `AGENT` before its agent
  starts: `claude-code plugin <id> is not installed on this computer: install it (claude plugin install <id>) or
  remove it from <key> in <file>`, likewise for a server (`claude mcp add --scope user`). `dispatch check`, on the
  instance and on a worker, fails with the same line.
- **The configuration file cannot be read or parsed when a run starts** (say, half saved): the run fails as `AGENT`,
  naming the file and the error; a retry reads it again. When the last read that worked listed nothing, the run goes on
  as it did before the lists and logs `agent.owner_lists_unreadable` (final review I-2: an owner who lists nothing must
  not have runs fail on a save that mattered only at the next start). Startup and `dispatch check` report the same file
  as they do today.
- **`claude plugin list --json` fails** (a Claude Code without it, a broken install): the run fails as `AGENT` with its
  error, only when a plugin is listed.
- **A plugin's MCP file cannot be read, or is not JSON:** the run fails as `AGENT`, naming the plugin and the file.
- **Two servers get one name:** the run fails as `AGENT`, naming both.
- **A server fails to start inside the run:** the run goes on without it; `agent.mcp_failed` (WARN).

## Limits

- **Trust.** A listed server's tools and credentials are the agent's: a prompt-injected agent can, for instance, read
  and write the owner's MongoDB. A listed plugin's hooks run in every run, inside the sandbox. Listing is the owner's
  decision, per machine.
- **User scope only:** a server added at project or local scope, or a claude.ai connector, cannot be listed.
- **A plugin's own data:** what a plugin writes under `~/.claude/plugins` is thrown away with the overlay, or refused
  without overlays.
- **Cost:** one `claude plugin list --json` per run that lists plugins, one to two seconds, and one read of the
  configuration file per run.
- **Only these lists are read per run:** every other setting still applies at the next start.

## Testing

- **Configuration:** both lists read from `dispatch.yaml` and `worker.yaml`; absent lists are empty; lists under
  `codex` or `gemini` are refused; a list edited in the file between two runs reaches the second run without a restart;
  a file that cannot be parsed when a run starts fails that run as `AGENT` when the last read listed something, and
  leaves it as it was when nothing was listed.
- **Resolving:** a fake Claude Code answering `plugin list --json`; a listed id resolves to its `installPath`; a missing
  id or server fails the run as `AGENT` with the text above, before any process starts.
- **The command line:** `--plugin-dir` and `--add-dir` per plugin, `Skill` in `--tools`, `--mcp-config` and
  `--strict-mcp-config` together; unchanged with empty lists; splits and assistant turns unchanged.
- **The MCP configuration:** a listed server copied as it is; a plugin's servers from `.mcp.json` (bare map) and from
  the file `plugin.json` names (under `mcpServers`), named `plugin_<plugin>_<server>`, with `${CLAUDE_PLUGIN_ROOT}`
  replaced; on the command line right after `--mcp-config`, followed by a flag; a server's credentials never in the log.
- **`dispatch check`:** a FAIL line per missing plugin or server, on the instance and on a worker.
- **Live, opt-in** (`DISPATCH_LIVE_CLAUDE=1`): real Claude Code in the real sandbox with Playwright listed: its server
  is connected in the init event, and the agent calls one of its tools.

## Documentation

- ADR 0036, amending ADR 0034 and the strict MCP configuration: Claude Code runs load the plugins and MCP servers the
  machine's owner lists, through an MCP configuration Dispatch builds and passes inline; the options not taken (everything the owner has
  enabled, the repository deciding, letting Claude Code start a plugin's servers itself with the claude.ai connectors
  switched off).
- SECURITY.md: a listed server's tools and credentials are the agent's; plugin hooks run inside the sandbox.
- README: the two keys, in `dispatch.yaml` and `worker.yaml`. ARCHITECTURE: the Claude Code command line.
