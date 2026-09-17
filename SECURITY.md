# Security

## Reporting a vulnerability

Do not open an issue. Contact the maintainers privately, or use GitHub's private vulnerability reporting if it is enabled for this repository.

## Threat model

Dispatch lets people in a Telegram group make an AI coding agent run commands on a server. Treat access to it like shell access to that server.

**Who can make the agent act**
- Only members listed in the instance config, only in the configured group, and only through commands. Everyone else is refused or ignored.
- Anyone who controls a member's Telegram account, the bot token or the configured group can act as that member.

**Boundaries**
- The hard boundary is the instance's OS user. Give it access to its own team's repositories only, and a GitHub token scoped to them (ADR 0005). The token can push branches and open pull requests; draft status and human review keep agent changes from merging on their own (ADR 0007).
- Claude Code's permission modes and deny rules are guardrails, not a security boundary (ADR 0009).
- Task text and repository content can steer the agent (prompt injection). Planning is read-only, and code changes need a member's approval first (ADR 0006).
- Execution runs in auto mode: the agent edits files and runs builds and tests as the instance user, within what Claude's classifier allows (ADR 0009).

## Secrets

- **Where secrets live:** only in the environment file (`TELEGRAM_BOT_TOKEN`, `ANTHROPIC_API_KEY`, `GH_TOKEN`), mode 600, owned by the instance user.
- **Config file:** the YAML config never holds secrets. It rejects unknown keys and repository URLs with embedded credentials.
- **What the agent gets:** the agent process never receives `TELEGRAM_BOT_TOKEN` or `GH_TOKEN`. Git receives the token through its environment, never on a command line. Processes running as the same OS user can still read each other's environment.
- **Redaction:**
  - What is masked: log lines, stack traces, every Telegram message Dispatch sends, and the agent's summary before it becomes a commit message and pull request description.
  - What gets replaced with `[redacted]`: the values of Dispatch's secret variables, plus common credential formats (Telegram bot tokens, Anthropic, OpenAI, GitHub and AWS keys, private keys, credentials in URLs).
  - Limit: redaction is best effort, so a secret in an unknown format can still pass.
- **Local secret files:** planning runs never get `copyFiles` such as `.env`, so local secrets cannot end up quoted in a plan posted to the group. Execution runs do get them, because builds and tests need them. Each must be git-ignored, so delivery never commits the file itself, but the agent could still copy a value into a tracked file or its summary. Review draft pull requests with that in mind.

## State on disk

The state directory holds the SQLite database, git worktrees and raw agent transcripts under `runs/`, including everything the agent read.
- Dispatch creates it owner-only, and startup warns if it is open to other users.
- Protect, back up and delete it the same way as the source code it contains.

## If a secret leaks

- **Bot token:** revoke it with @BotFather (`/revoke`), put the new token in the environment file, restart the instance.
- **GitHub token:** revoke it in GitHub settings and create a new fine-grained token scoped to the team's repositories.
- **Anthropic API key:** revoke it in the Anthropic Console and create a new one.

Afterwards, check the logs (`journalctl -u dispatch@<team>`) and the team group for what was exposed.

## Repository hygiene

- `.gitignore` blocks local environment files, dev configs and database files. The only tracked templates are `deploy/example.*`.
- Enable GitHub secret scanning with push protection for this repository. For private repositories this needs GitHub Secret Protection.
