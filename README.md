# Dispatch

Dispatch takes development tasks from a team's Telegram group, has Claude Code analyze them in a git worktree, and posts the plan back to the group. One instance serves one team, with its own bot.

- Design: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), decisions in [docs/adr/](docs/adr/), vocabulary in [CONTEXT.md](CONTEXT.md).
- Status: **M1, the read-only slice.** Tasks get a plan and can be rejected or cancelled. Approving a plan (execution and draft PRs) arrives with M2.

## Build

Requires JDK 25+ and git. The Maven wrapper downloads Maven itself.

```sh
./mvnw verify                       # tests, then target/dispatch-0.1.0.jar
```

## Set up a team instance

The examples use the team `backend`.

**1. Create the bot.** Create it with @BotFather. Leave privacy mode enabled (the default) and do not make the bot a group admin, because admins receive every message. Add the bot to the team group.

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

# Claude Code for the instance user (ends up in ~dispatch-backend/.local/bin/claude)
sudo -u dispatch-backend -i sh -c 'curl -fsSL https://claude.ai/install.sh | bash'

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

## Use it in the group

Pick commands from the `/` menu. With privacy mode on, only `/command@<bot_username>` reliably reaches the bot; a bare `/command` is lost when another bot posted more recently.

| Command | Effect |
|---|---|
| `/task@bot alm Fix the login timeout on staging` | Creates task #N; the agent's plan is posted as a reply |
| reply to any message with `/task@bot alm` | That message becomes the task (extra text is appended) |
| `/tasks@bot` | Active tasks |
| `/cancel@bot N` | Cancels task N, stopping its agent if one is running |
| `/help@bot` | Commands and projects |

Only members listed in the config can create or cancel tasks, and any member can press **Reject** on a plan.

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
  SELECT id, phase, project, title, failure_reason FROM task ORDER BY id DESC LIMIT 20;
  SELECT task_id, seq, kind, status, cost_usd, turns, error_detail FROM run ORDER BY task_id DESC LIMIT 20;
  SELECT id, kind, status, attempts, last_error FROM outbox WHERE status <> 'SENT';
  SELECT * FROM task_event WHERE task_id = 42 ORDER BY id;
  ```
- **Raw agent output:** `/var/lib/dispatch/backend/runs/<task>/<run>.jsonl` and `.stderr`.
- **Restarts:** stopping or restarting interrupts active runs. They fail as `INTERRUPTED` and the group is told.
- **Worktrees:** they accumulate under `worktrees/` until the M3 sweep. Remove finished ones with `git -C repos/<project> worktree remove --force worktrees/<id>`.
- **Bot texts:** `src/main/resources/messages_mn.properties`.
