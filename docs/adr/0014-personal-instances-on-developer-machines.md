# Personal instances on developers' own machines

A developer can run Dispatch as their own instance on their own machine: their own bot, config, state and projects. Nothing is shared with other developers. It is still one instance per team (ADR 0005), where the team is one person.

Three things make this work without workarounds:
- **Project location:** a project's `path` points at the developer's existing clone, wherever it is. Dispatch adds its worktrees under its state directory and its `dispatch/<task>` branches to that clone, and never changes the files the developer has checked out. Without `path`, the clone stays at `<stateDir>/repos/<name>`.
- **No group chat:** a group's `chatId` is optional. A task of a group without a chat belongs to its requester's private chat. The one-line announcements, which would repeat what the requester already gets, are not sent there, and a refused private message has no group to fall back to. (Amended by ADR 0023: the owner may link a group chat to a project from Telegram, without a worker.)
- **Effort level:** a project's `effort` (`low`, `medium`, `high`, `xhigh`, `max`) is passed to its planning and execution runs.

Setup happens in the terminal, with commands in the same jar: `dispatch init`, `dispatch project add <path>`, `dispatch check` and `dispatch run`. They write and validate the same YAML file that a team server uses, and that file stays the source of truth, readable and editable by hand. Nothing about projects, paths or agents can be configured from Telegram, since anyone who can message the bot would then control what runs on the machine.

We chose this because developers keep their projects in different places, want their own bot, and want to set the model and effort per project. A shared team server does not fit that.

We rejected two alternatives:
- **Symlinks into `repos/` and a one-person Telegram group:** works today, but hides a config format that does not fit.
- **A setup script separate from the jar:** it would duplicate the validation, and could disagree with the running bot about what a valid config is.

## Consequences

- The agent runs as the developer's OS user. It can read everything they can: SSH keys, other repositories, saved logins. Anyone who controls their Telegram account or bot token can make it run commands as them. The hard boundary of ADR 0005, a dedicated OS user, only exists on a team server.
- `dispatch/<task>` branches accumulate in the developer's clone. If a state directory is reset, task numbers restart and a new task whose branch already exists fails at setup, visibly.
- A developer who also wants their team to see announcements can still give a group a chat and add their bot to it.
