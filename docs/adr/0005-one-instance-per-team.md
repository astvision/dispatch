# One Dispatch instance per team

Each team gets its own Dispatch instance: its own Telegram bot, config, SQLite file, repositories and OS user. Teams can share a host (e.g. a systemd template unit `dispatch@<team>`) or each have their own. The code therefore stays single-team: no record carries a team id.

We rejected one multi-tenant process serving every team's bot. Any group member can make an agent run arbitrary commands, and only separate OS users give a real boundary between teams. Separate processes also keep one team's crash, upgrade or runaway agent away from the others.

## Consequences

- ADR 0012 lets one instance serve several groups that may share a server; this ADR still applies wherever teams need a hard boundary.
- Serving N teams means N deployments to configure and upgrade.
- Nothing is shared across teams: no cross-team task list, queue or dashboard.
