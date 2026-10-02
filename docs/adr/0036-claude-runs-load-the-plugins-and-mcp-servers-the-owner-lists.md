# Claude Code runs load the plugins and MCP servers the machine's owner lists

Amends ADR 0034 (the owner's own plugins never load into a run). A split and the assistant (ADR 0024) still load none
of the owner's plugins or MCP servers.

A machine's owner names, in `dispatch.yaml` (`agents.claude-code.plugins`, `agents.claude-code.mcpServers`) or a member's
`worker.yaml` (`claudePlugins`, `claudeMcpServers`), the plugins and user-scope MCP servers that Claude Code plan,
execution and review runs on that machine load; the agent picks what fits the task, such as frontend-design for a page.
The lists are read again from the file before each run, so an edit applies without a restart. Each plugin is found with
`claude plugin list --json`; Dispatch builds one MCP configuration from the listed servers (`~/.claude.json`) and the
listed plugins' own (named `plugin_<plugin>_<server>`, as Claude Code names them) and passes it inline after
`--mcp-config`, keeping `--strict-mcp-config`, so nothing unlisted starts: no claude.ai connector, no repository's
`.mcp.json`. Something listed and missing fails the run before its agent starts, and `dispatch check` says what to do.

We chose this over:
- **Everything the owner has enabled.** Plugins built for a person at the keyboard (a hook that tells the agent to stop
  and ask) would load into runs nobody answers.
- **The repository deciding** (its `.claude/settings.json` and `.mcp.json`). A commit per repository, and MCP
  credentials must not live in a repository.
- **Letting Claude Code start a plugin's servers itself**, with the claude.ai connectors switched off
  (`ENABLE_CLAUDEAI_MCP_SERVERS=false`). Less code, but a repository's approved `.mcp.json` servers could start, and so
  could any MCP source a later Claude Code adds.

Consequences: a listed server's tools and credentials are the agent's, so a prompt-injected agent can use them; listing
is the owner's decision, per machine. A plugin's hooks and servers run inside the sandbox, and one that needs what the
sandbox hides fails (`agent.mcp_failed`). The sandbox has no display: Playwright's server connects, but its browser
cannot open a window there. Only user-scope servers can be listed. A team machine's lists never reach a member's
computer. Each run pays one `claude plugin list --json`.
