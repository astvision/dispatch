# Plugin picks (PP): a task's plan picks the official plugins its execution loads

Status: approved design, 2026-10-02; to build on branch plugin-picks (see ADR 0037). Amends ADR 0036 (listing plugins is
the machine owner's decision): the agent may also pick, per task, from a short list Dispatch ships.

## Goal

Today a Claude Code run loads Dispatch's skills plugin and what the machine's owner lists, the same set for every task.
After this change, the plan run also names which plugins of a curated list the task needs, the requester sees them in
the plan, and the task's execution and review runs load them: frontend-design for a page, Playwright to check it in a
browser, Context7 for a library's current documentation. Nothing is installed: the plugins are read from the official
marketplace that Claude Code already keeps on disk.

In scope: the curated list; a `plugins` field in the plan; the picks shown in the plan message and the Mini App; loading
the picks in execution and review runs, on the team machine and on a worker; ADR 0037.

Out of scope:
- **LSP plugins** (jdtls-lsp, typescript-lsp, …): slice 2. They need the language server installed on the machine, the
  `LSP` tool in the run's tools, their server definitions from the marketplace entry (`strict: false`), and a writable
  configuration area and index per run in the sandbox (LSP spike, 2026-10-02).
- **Plugins outside the curated list**, including the other 50 Anthropic-hosted ones and the 262 third-party ones in
  the official marketplace. The owner's own list (ADR 0036) still loads anything else.
- **Installing plugins**, and any change to the owner's Claude Code settings.
- **Editing the picks** from the plan message. A correction ("don't use Playwright") re-plans, and the new plan's picks
  replace the old ones.
- **Plan runs loading picks** (they choose them), splits, assistant turns, Codex and Gemini CLI runs.

## The curated list

A constant in Dispatch, `CuratedPlugins`: the plugin's name in the `claude-plugins-official` marketplace and one line on
when to pick it, written for the plan prompt.

| Name | Gives the agent | Pick it when |
|---|---|---|
| `frontend-design` | a skill | the task builds or reshapes a user interface |
| `playwright` | an MCP server (`npx @playwright/mcp@latest`) | the change should be checked in a real browser |
| `context7` | an MCP server (remote HTTP, no key) | the task depends on a library's current API or configuration |

A plugin joins the list only if it works in an unattended, sandboxed run: no hook that waits for a person or calls a
model of its own, no credentials, no tool Dispatch's runs lack. Rejected on that ground: `code-simplifier` and
`pr-review-toolkit` (subagents; runs have no `Agent` tool), `security-guidance` (its SessionStart hook installs the
Agent SDK, and its Stop hook runs its own model review), the messaging bridges (`telegram`, `discord`, `imessage`),
`github` and `gitlab` (they would push around Dispatch's `git push` and `gh` ban), and servers that need credentials
(`linear`, `asana`, `firebase`, `terraform`). Changing the list is a Dispatch commit.

## Success criteria

1. A Claude Code plan run for "add a settings page" returns `plugins: ["frontend-design"]` (live, opt-in), and the plan
   message shows one line naming it.
2. That task's execution run's init event lists the `frontend-design` plugin, and the agent can invoke
   `frontend-design:frontend-design` with the Skill tool.
3. A picked `playwright` starts as `plugin_playwright_playwright` in the run's one MCP configuration, with
   `--strict-mcp-config` kept; nothing else new starts.
4. A name outside the curated list in a plan is dropped when the plan is read, and `plan.plugin_dropped` is logged.
5. A pick this machine cannot find is skipped: the run goes on without it, and `agent.plugin_skipped` (WARN) names the
   plugin and the path it looked for.
6. An execution or review run whose plan has no picks, or was stored before this change, has today's command line;
   Codex and Gemini CLI runs load nothing new. (Every plan run's schema gains the `plugins` field.)
7. A worker resolves the picks from its own marketplace copy.
8. Nothing is written under `~/.claude` and no `claude plugin` command runs.

## The plan

`plan-schema.json` gains a required array `plugins` of strings, described as "Plugins from the list in the prompt that
the execution needs; empty if none". Required, because Codex's strict output mode needs every property listed in
`required`. `Plan` gains `List<String> plugins`. `Plan.parse` reads it as absent-means-empty, like `decisions`, keeps
only names in `CuratedPlugins`, logs each dropped one, and removes duplicates. The picks live in the task's `planJson`,
so there is no migration.

Only a Claude Code plan run is offered the list: `JobRunner` appends `Prompts.PluginNote`, the table above as text,
beside the skill note, when the job's agent is `claude-code`. Codex and Gemini CLI plans get no list and return `[]`.

The Telegram plan message gets one line after the risks, `plan.plugins` ("🧩 Плагин: frontend-design, playwright"; Telegram messages are Mongolian only), absent when there are none. The Mini App's plan payload (`TaskService`) gains `plugins`, and the plan
screen shows the same line.

## A run

`Coordinator.executeJob` copies `Plan.parse(task.planJson()).plugins()` into a new `Job` field `plugins`. It is null,
and left out of the job's JSON, for plan and delivery jobs and whenever there are no picks, so an older worker still
reads every job without picks (as with the verify loop's fields, ADR 0033). The worker API carries the field with the
job; `WorkerLoop.withLocalClone` passes it through. `JobRunner.request` puts it on the `RunRequest` as `picks`, for the execution run and for the review run the
verify loop starts.

In `ClaudeCodeAgent`, an execution or review run resolves its picks on this machine:

1. Read `~/.claude/plugins/marketplaces/claude-plugins-official/.claude-plugin/marketplace.json`.
2. For each pick, find its entry and take its `source`, which must be a relative path inside the marketplace directory.
   The plugin directory is that path, normalized, and it must contain `.claude-plugin/plugin.json`.
3. A pick that is not on this machine is skipped with `agent.plugin_skipped`: the marketplace file is missing or
   unreadable, there is no entry, the source is not a relative path, or the directory is missing. The run goes on.

The resolved directories join the owner's (ADR 0036) in one pass. Each gets `--plugin-dir` and `--add-dir`, its MCP
servers are added to the single `--mcp-config` as `plugin_<name>_<server>`, and `Skill` joins `--tools`. A pick the owner
also lists as `<name>@claude-plugins-official` loads once, from the owner's installed copy. Two servers with one name still fail the run, as
in ADR 0036.

## Sandbox

Unchanged rules. The marketplace directory is under `~/.claude`, which the sandbox already makes copy-on-write, and
each picked plugin directory is bound read-only like the owner's. Playwright runs headless without a display (ADR
0036). Context7's server is remote, which the sandbox's open network allows.

## Errors

- **A pick missing on this machine:** skipped, `agent.plugin_skipped` (WARN); the run goes on.
- **A pick's MCP file cannot be read or is not JSON:** the run fails as `AGENT`, naming the plugin and the file, as for
  an owner-listed plugin.
- **A picked server fails to start in the run:** the run goes on without it; `agent.mcp_failed` (WARN).
- **A name outside the list in a plan:** dropped when the plan is read; `plan.plugin_dropped` (INFO).

## Limits

- **Trust.** A picked plugin's code runs in the sandbox with the agent's access. The list holds only Anthropic-hosted
  plugins with no hooks and no credentials, and the agent cannot add to it. `playwright` runs `@playwright/mcp@latest`
  from npm, so its code is whatever npm serves at that moment.
- **Upstream changes.** The marketplace copy updates with Claude Code. A plugin moved or renamed there is skipped until
  the list is updated.
- **Older workers.** A job with picks carries a field an older worker cannot read (`FAIL_ON_UNKNOWN_PROPERTIES`):
  members upgrade their workers before the team machine's plans pick anything.
- **Cost.** One read of `marketplace.json` per execution or review run with picks. Playwright adds its server's startup
  (an `npx` resolve) to each such run.

## Testing

- **Plan:** `plugins` parsed; absent reads as empty; names outside the list dropped and logged; duplicates removed; a
  plan written back keeps them.
- **Prompt:** a Claude Code plan run's prompt holds the list; a Codex plan run's does not.
- **Rendering:** the plan message line, absent with no picks; the Mini App payload field.
- **Job:** `executeJob` copies the picks; the worker API round-trips them; plan and delivery jobs, and execution
  jobs without picks, leave the field out of their JSON.
- **Resolving:** a temporary marketplace directory; a pick resolves to its source directory; each skip case logs and
  goes on; a source outside the marketplace directory (`../x`, an absolute path) is skipped.
- **The command line:** picks give `--plugin-dir`, `--add-dir` and `Skill`; a picked plugin's server in the MCP
  configuration; a pick that is also owner-listed loads once; no picks gives today's command line; plan runs never
  load picks.
- **Live, opt-in** (`DISPATCH_LIVE_CLAUDE=1`): a real plan run for a UI task picks `frontend-design`, and a real
  execution run with that pick invokes the skill.

## Documentation

- ADR 0037, amending ADR 0036: the plan picks from a list Dispatch ships, and the options not taken (the whole
  Anthropic-hosted set, the whole marketplace, an owner allowlist the agent picks from, loading the whole list every
  run).
- SECURITY.md: the picked plugins' code runs in the sandbox; the list is Dispatch's, and the agent cannot extend it.
- README (both languages): the plan's plugins line and what each plugin is for. ARCHITECTURE: the Claude Code command line.
