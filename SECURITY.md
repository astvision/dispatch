# Security

## Reporting a vulnerability

Do not open an issue. Contact the maintainers privately, or use GitHub's private vulnerability reporting if it is enabled for this repository.

## Threat model

Dispatch lets people in a Telegram group make an AI coding agent run commands on a server. Treat access to it like shell access to that server.

**Who can make the agent act**
- Only members listed in a group of the instance config, for the projects of their groups, in their own private chat with the bot, and only through messages, commands, buttons and replies to plans. Everyone else is refused or ignored.
- Only a task's requester can approve, correct, reject, reprioritize, follow up on or retry it (ADR 0011, 0012, 0020). The requester or an admin can cancel it; an admin may cancel any task, even one outside their own groups.
- Reports (`/status`, `/history`, `/stats`) only cover the viewer's groups, or in a group chat that group's projects. Another group's task is answered as not found. For a task that is not the viewer's own, they show only its headline — who, project, title, priority, state and PR link — never its plan, the agent's actions or its cost (ADR 0020). A group chat has no viewer, so it always sees headlines only.
- Plans and results go to the requester's private chat. If Telegram refuses that chat (the requester never pressed Start, or blocked the bot), the group gets a content-free notice naming the task instead of the message itself (ADR 0020).
- Anyone who controls a member's Telegram account, the bot token or the configured group can act as that member.

**Boundaries**
- The hard boundary is the instance's OS user. Give it access to its groups' repositories only, and a GitHub token scoped to them (ADR 0005). All groups of one instance share that user and token (ADR 0012): run a separate instance for any team that must not reach another team's repositories. The token can push branches and open pull requests; draft status and human review keep agent changes from merging on their own (ADR 0007).
- Claude Code's permission modes and deny rules are guardrails, not a security boundary (ADR 0009).
- Task text and repository content can steer the agent (prompt injection). Planning is read-only, and code changes need a member's approval first (ADR 0006).
- Splitting a message (✂️, ADR 0013) sends it to Haiku with no tools at all, not even read-only ones. The model can only answer with text, and each part is shown to the member before it becomes a draft.
- Execution runs in auto mode: the agent edits files and runs builds and tests as the instance user, within what Claude's classifier allows (ADR 0009).
- A shared bot's admins (ADR 0015) decide in Telegram who becomes a member, so their Telegram accounts are as sensitive as shell access to the bot's machine. A stranger can cause at most one message to each admin per day, and nothing else; every decision is logged.
- A personal instance (ADR 0014) runs as the developer, so its boundary is the developer's own OS account: the agent can read their SSH keys, other repositories and saved logins. Anyone who controls their Telegram account or bot token can make it run commands as them. `dispatch init` makes only the person confirmed at the terminal a member. Nothing about projects, paths or agents can be changed from Telegram.
- In a team, a member's tasks run on that member's own computer (ADR 0021), so the boundary is each member's own OS account, as for a personal instance. **The team machine holds:** the bot token, the queue, the headlines, the plan and result texts it relays to Telegram — its owner can read those in its database — and each attachment until the worker fetches it, plus the SHA-256 of every worker key and each computer's last readiness report (its Claude Code version, whether `gh` is logged in, its project names, and a one-line detail per failed check, which can name a local path; ADR 0022). **A member's computer holds:** their worker key (in `worker.env`, mode 600), their Claude Code login and transcripts, their clones, worktrees and `gh` credentials. Neither the key nor anything derived from it reaches the other members. **Revoking a key** is `/worker revoke N` in the bot's private chat — an admin may revoke anyone's computer, not only their own — and it refuses whatever that computer reports afterwards with 401. It also reaches a run already in flight: that computer's next progress report, within one `WorkerLoop` progress interval (about 10 s), learns its key is dead and stops the agent (SIGTERM, a grace period, then SIGKILL) instead of letting it run to an outcome nobody can collect. The one thing it does not stop is a delivery already under way: `JobRunner` checks for a stop right after the agent finishes, but never again once it has moved into delivery, so a revoke landing after that check still lets the push and the draft pull request land. Either way, revoking never reaches back into that computer itself: its clones, worktrees, Claude sessions and any output already on disk stay there. Removing a member from the config does not revoke anything by itself: Dispatch has no config hot-reload, so the running instance keeps their key valid, their groups and their projects exactly as before, until it is restarted — a restart is what drops a former member from every group (so they are offered no more work) and revokes every worker of theirs, together. Treat a lost laptop as a lost checkout of everything it was working on.
- A worker key travels on every worker request, so `workers.publicUrl` must be `https://` (plain HTTP only from `127.0.0.1`, for local tests). The worker endpoints are a separate server from `dispatch ui` and share no session with it; a worker only ever receives its own member's jobs, and may only report a run it holds the lease for.

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
others see it, and stop `dispatch ui` when you are done. Setup's folder browser lists folder names on the machine that runs Dispatch and says which are git clones; it never shows a file's contents. The Logs page shows the service log with Dispatch's secrets and common token formats masked. A save keeps the previous config as `dispatch.yaml.bak`, with the config's own permissions.

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
