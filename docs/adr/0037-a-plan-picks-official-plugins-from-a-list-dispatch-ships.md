# A plan picks official plugins from a list Dispatch ships

Amends ADR 0036 (listing plugins is the machine owner's decision): a task's plan may also pick plugins, from a short
list in Dispatch.

A Claude Code plan run is shown `CuratedPlugins` (frontend-design, playwright) and names the ones the task
needs in the plan's `plugins` field. The requester sees them in the plan message and the Mini App before approving.
The task's execution, its verify-loop fixes and its reviewer load them; the plan run never does. Each is found in the
copy of Anthropic's `claude-plugins-official` marketplace that Claude Code keeps under `~/.claude`, so nothing is
installed or fetched. Its servers join the run's one inline `--mcp-config`, and `--strict-mcp-config` stays. A name
outside the list is dropped from the plan, and a pick a machine cannot find is skipped with `agent.plugin_skipped`.

A plugin joins the list only if it works in an unattended, sandboxed run: no hook that waits for a person or calls a
model of its own, no credentials or sign-in, and no tool Dispatch's runs lack. Context7 was meant to be on it, but its
plugin's server asks for an OAuth sign-in, which nobody answers in a run.

We chose this over:
- **Every Anthropic-hosted plugin.** That includes messaging bridges (telegram, discord, imessage), github and gitlab,
  which would push around Dispatch's `git push` and `gh` ban, servers that need credentials, and hooks written for a
  person at the keyboard.
- **The whole official marketplace.** 262 of its 315 plugins are third-party repositories fetched at install time, and
  `claude plugin install` also enables a plugin in the owner's own Claude Code.
- **An owner allowlist the agent picks from.** More configuration for each owner to keep, for a list that is the same
  everywhere.
- **Loading the whole list in every run.** Each run would start Playwright's server whether the task needs it or not,
  and the LSP plugins (next) are too heavy for that.

Consequences: a picked plugin's code runs in the sandbox with the agent's access, and `playwright` runs whatever npm
serves as `@playwright/mcp@latest`. The agent cannot add to the list; changing it is a Dispatch commit. A team machine and its
workers upgrade together: every plan now has a `plugins` field, which a team machine from before this version refuses
in a newer worker's plan, and a job with picks carries a field a worker from before it cannot read.
