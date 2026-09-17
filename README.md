# Dispatch

Dispatch takes development tasks from a team's Telegram group and has Claude Code plan them in a git worktree. Once a member approves the plan, the agent implements it and Dispatch delivers the change as a draft pull request. One instance serves one team, with its own bot.

- Design: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), decisions in [docs/adr/](docs/adr/), vocabulary in [CONTEXT.md](CONTEXT.md).
- Status: **M3a.** Tasks start in the group; plans, corrections and results reach the requester privately, and approved plans end as draft PRs. `/status` shows what the agent is doing, `/history` what was done. Follow-ups and `/retry` come next.

## Security

Access to the bot is effectively shell access to the server. Read [SECURITY.md](SECURITY.md) before deploying.
- Secrets go only in the 0600 environment file, never in the YAML config.
- Logs and Telegram messages are redacted.
- The state directory is created owner-only.

## Build

Requires JDK 25+ and git. The Maven wrapper downloads Maven itself.

```sh
./mvnw verify                       # tests, then target/dispatch-0.1.0.jar
```

## Set up a team instance

The examples use the team `backend`.

**1. Create the bot.** Create it with @BotFather. Leave privacy mode enabled (the default) and do not make the bot a group admin, because admins receive every message. Add the bot to the team group. Every member should also open the bot once and press **Start**, so their task details can reach them privately; until they do, the details are posted in the group.

**2. Find the ids before starting Dispatch.** With a wrong `groupChatId`, Dispatch leaves the group it sees messages from.
1. In the group, each member sends `/help@<bot_username>`.
2. Read the ids:

```sh
curl -s "https://api.telegram.org/bot<TOKEN>/getUpdates" \
  | jq '.result[].message | {group: .chat.id, member: .from.id, name: .from.first_name}'
```

Later, when a non-member runs a command, the log line `event=member.not_allowed requester=telegram:<id>` shows their id.

**3. Install on the server (as root).**

```sh
useradd --system --create-home dispatch-backend
install -d -o dispatch-backend -g dispatch-backend -m 750 /var/lib/dispatch/backend/repos

# Claude Code for the instance user (ends up in ~dispatch-backend/.local/bin/claude), and the GitHub CLI for delivery
sudo -u dispatch-backend -i sh -c 'curl -fsSL https://claude.ai/install.sh | bash'
dnf install gh        # or apt install gh; see https://github.com/cli/cli#installation

# One clone per project, named as in the config. For private repos, pass the token without storing it in .git/config.
sudo -u dispatch-backend GH_TOKEN=github_pat_... git \
  -c credential.helper='!f() { echo username=x-access-token; echo "password=$GH_TOKEN"; }; f' \
  clone https://github.com/acme/autoland-management.git /var/lib/dispatch/backend/repos/autoland-management

install -D -m 644 target/dispatch-0.1.0.jar /opt/dispatch/dispatch.jar
install -D -m 644 deploy/example.yaml /etc/dispatch/backend.yaml     # then edit it
install -D -m 600 deploy/example.env  /etc/dispatch/backend.env      # then fill in the secrets
install -m 644 deploy/dispatch@.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now dispatch@backend
```

Check that `journalctl -u dispatch@backend` shows `event=dispatch.started`, then send `/help@<bot_username>` in the group. Set a monthly spend limit on the team's API key in the Anthropic Console.

In the config, set `delivery.authorName`/`authorEmail` (the git identity of delivery commits) and `limits.execute`. The GitHub token needs Contents and Pull requests read/write on the team's repositories. Execution runs in auto mode, which not every model has: set the project's `model` to Sonnet or Opus, or leave it unset if the account's default model is one of them. With Haiku, every execution run fails with a permission-mode error.

## Use it in the group

Pick commands from the `/` menu. With privacy mode on, only `/command@<bot_username>` reliably reaches the bot; a bare `/command` is lost when another bot posted more recently.

| In the group | Effect |
|---|---|
| `/task@bot alm Fix the login timeout on staging` | Creates task #N. The group gets a one-line acknowledgement; the plan goes to you privately |
| reply to any message with `/task@bot alm` | That message becomes the task (extra text is appended) |
| `/status@bot` | What is running now (with the agent's latest action), queued, and awaiting approval |
| `/history@bot`, `/history@bot N` | The last 10 finished tasks; task N's timeline with durations and costs |
| `/cancel@bot N` | Cancels task N, stopping its agent if one is running |
| `/help@bot` | Commands and projects |

| In your private chat with the bot | Effect |
|---|---|
| **Approve** on a plan | The agent implements the plan; Dispatch commits, pushes `dispatch/N`, and you get the draft PR link and summary |
| reply to a plan | A correction: the agent revises the plan in the same session |
| **Reject** on a plan | Closes the task |
| `/status`, `/history [N]`, `/cancel N`, `/help` | As in the group |

The group sees each outcome in one line: done with the PR link, failed with the reason, rejected, or cancelled. Only members listed in the config can act. Only the requester can approve, correct or reject their plan, and any member can cancel. A plan with open questions has no Approve button: answer the questions by replying to it.

## Run locally

```sh
cp deploy/example.yaml dev.yaml                      # edit group, members, claude path, projects
export TELEGRAM_BOT_TOKEN=... STATE_DIRECTORY=$HOME/.local/state/dispatch-dev
git clone <repo> "$STATE_DIRECTORY/repos/<project>"
java -jar target/dispatch-0.1.0.jar dev.yaml
```

Locally, `claude` uses your own login. Your plugins and MCP servers are not loaded into agent runs, but the repository's `CLAUDE.md` is.

## Operate

- **Logs:** `journalctl -u dispatch@backend -f`. Lines are logfmt; grep for `level=ERROR` or `event=task.transition`.
- **State:** `sqlite3 /var/lib/dispatch/backend/dispatch.db`

  ```sql
  SELECT id, phase, project, title, pr_url, failure_reason FROM task ORDER BY id DESC LIMIT 20;
  SELECT task_id, seq, kind, status, cost_usd, turns, error_detail FROM run ORDER BY task_id DESC LIMIT 20;
  SELECT id, kind, status, attempts, last_error FROM outbox WHERE status <> 'SENT';
  SELECT * FROM task_event WHERE task_id = 42 ORDER BY id;
  ```
- **Raw agent output:** `/var/lib/dispatch/backend/runs/<task>/<run>.jsonl` and `.stderr`.
- **Restarts:** stopping or restarting interrupts active runs. They fail as `INTERRUPTED` and the group is told.
- **Worktrees:** they accumulate under `worktrees/` until the M3 sweep. Remove finished ones with `git -C repos/<project> worktree remove --force worktrees/<id>`; a task whose worktree is gone can no longer be corrected or executed.
- **Delivery:** commits are made without hooks or signing, as `delivery.authorName`. A failed push or PR creation fails the task as `DELIVERY`; the commit stays in the worktree.
- **Bot texts:** `src/main/resources/messages_mn.properties`.
