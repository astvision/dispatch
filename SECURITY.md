# Security

## Reporting a vulnerability

Do not open an issue. Contact the maintainers privately, or use GitHub's private vulnerability reporting if it is enabled for this repository.

## Threat model

Dispatch lets people in a Telegram group make an AI coding agent run commands on a server. Treat access to it like shell access to that server.

**Who can make the agent act**
- Only members listed in a group of the instance config, for the projects of their groups, in their own private chat with the bot, and only through messages, commands, buttons and replies to plans. Everyone else is refused or ignored.
- Only a task's requester can approve, correct, reject or reprioritize it (ADR 0011, 0012). Any member of the project's group can cancel it.
- Reports (`/status`, `/history`, `/stats`) only cover the viewer's groups, or in a group chat that group's projects. Another group's task is answered as not found.
- Plans and results go to the requester's private chat. If Telegram refuses that chat (the requester never pressed Start, or blocked the bot), the message is posted in the team group instead, where everyone in the group can read it.
- Anyone who controls a member's Telegram account, the bot token or the configured group can act as that member.

**Boundaries**
- The hard boundary is the instance's OS user. Give it access to its groups' repositories only, and a GitHub token scoped to them (ADR 0005). All groups of one instance share that user and token (ADR 0012): run a separate instance for any team that must not reach another team's repositories. The token can push branches and open pull requests; draft status and human review keep agent changes from merging on their own (ADR 0007).
- Claude Code's permission modes and deny rules are guardrails, not a security boundary (ADR 0009).
- Task text and repository content can steer the agent (prompt injection). Planning is read-only, and code changes need a member's approval first (ADR 0006).
- Splitting a message (✂️, ADR 0013) sends it to Haiku with no tools at all, not even read-only ones. The model can only answer with text, and each part is shown to the member before it becomes a draft.
- Execution runs in auto mode: the agent edits files and runs builds and tests as the instance user, within what Claude's classifier allows (ADR 0009).
- A shared bot's admins (ADR 0015) decide in Telegram who becomes a member, so their Telegram accounts are as sensitive as shell access to the bot's machine. A stranger can cause at most one message to each admin per day, and nothing else; every decision is logged.
- A personal instance (ADR 0014) runs as the developer, so its boundary is the developer's own OS account: the agent can read their SSH keys, other repositories and saved logins. Anyone who controls their Telegram account or bot token can make it run commands as them. `dispatch init` makes only the person confirmed at the terminal a member. Nothing about projects, paths or agents can be changed from Telegram.

## Secrets

- **Where secrets live:** only in the environment file (`TELEGRAM_BOT_TOKEN`, `ANTHROPIC_API_KEY`, `GH_TOKEN`), mode 600, owned by the instance user. A personal instance keeps them in the secrets file beside its config, created owner-only (mode 600 on macOS and Linux, an ACL for the current user alone on Windows); `dispatch run` refuses it while others can read it. `dispatch init` reads the bot token without echoing it, and never prints it.
- **Config file:** the YAML config never holds secrets. It rejects unknown keys and repository URLs with embedded credentials.
- **What the agent gets:** the agent process never receives `TELEGRAM_BOT_TOKEN` or `GH_TOKEN`. Git receives the token through its environment, never on a command line. Processes running as the same OS user can still read each other's environment.
- **Redaction:**
  - What is masked: log lines, stack traces, every Telegram message Dispatch sends, and the agent's summary before it becomes a commit message and pull request description.
  - What gets replaced with `[redacted]`: the values of Dispatch's secret variables, plus common credential formats (Telegram bot tokens, Anthropic, OpenAI, GitHub and AWS keys, private keys, credentials in URLs).
  - Limit: redaction is best effort, so a secret in an unknown format can still pass.
- **Local secret files:** planning runs never get `copyFiles` such as `.env`, so local secrets cannot end up quoted in a plan posted to the group. Execution runs do get them, because builds and tests need them. Each must be git-ignored, so delivery never commits the file itself, but the agent could still copy a value into a tracked file or its summary. Review draft pull requests with that in mind.

## Installing

- `install.sh` and `install.ps1` download the release jar and launcher over HTTPS (or through the authenticated GitHub CLI while the repository is private), and verify their SHA-256 checksum before installing them. They build Dispatch from source instead when run from a checkout, when `DISPATCH_FROM_SOURCE=1` is set, when `DISPATCH_REF` names a branch rather than `main` or a `v*` tag, or when the download fails; a source build without Node has no web UI. Read a script before piping it into a shell if you do not trust the source.
- The background service (ADR 0016) runs as the user who installed it, with the PATH setup ran with, and logs to `dispatch.log` in the owner-only state directory.

## The web UI

`dispatch ui` listens only on 127.0.0.1; reach a server's through `ssh -L`. Each start prints a new link with a one-time
token that gives a single browser a session and then stops working, and restarting `dispatch ui` ends every session.
Requests with another Host (DNS rebinding) or, for changes, another Origin are refused. The bot token never reaches the
browser. Whoever has the link or a session acts as you, with what you may do in a shell: don't paste the link where
others see it, and stop `dispatch ui` when you are done. Setup's folder browser lists folder names on the machine that runs Dispatch and says which are git clones; it never shows a file's contents.

## State on disk

The state directory holds the SQLite database, git worktrees and raw agent transcripts: under `runs/`, including everything the agent read, and under `splits/`, including every message a member asked to split.
- Dispatch creates it owner-only (an ACL for the current user alone on Windows), and startup warns if it is open to other users.
- Protect, back up and delete it the same way as the source code it contains.

## If a secret leaks

- **Bot token:** revoke it with @BotFather (`/revoke`), put the new token in the environment file, restart the instance.
- **GitHub token:** revoke it in GitHub settings and create a new fine-grained token scoped to the team's repositories.
- **Anthropic API key:** revoke it in the Anthropic Console and create a new one.

Afterwards, check the logs (`journalctl -u dispatch@<team>`) and the team group for what was exposed.

## Repository hygiene

- `.gitignore` blocks local environment files, dev configs and database files. The only tracked templates are `deploy/example.*`.
- Enable GitHub secret scanning with push protection for this repository. For private repositories this needs GitHub Secret Protection.
