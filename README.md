# Dispatch

Dispatch takes development tasks that members write to its Telegram bot and has Claude Code plan them in a git worktree. Once the requester approves the plan, the agent implements it and Dispatch delivers the change as a draft pull request. One instance and bot can serve several groups, each with its own members and projects.

- Design: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), decisions in [docs/adr/](docs/adr/), vocabulary in [CONTEXT.md](CONTEXT.md).
- Status: **M3d.** Each developer can run their own Dispatch on macOS, Windows or Linux, set up with `dispatch init`, for clones wherever they are. Tasks are given in the private chat with project and priority buttons, and a message with several tasks can be split with ✂️. The plan, corrections and result stay in the private chat, in a topic per task when the bot has topics on. A team's group sees its projects' tasks and outcomes in one line. `/status`, `/history` and `/stats` report on your groups. Follow-ups and `/retry` come next.

## Security

Access to the bot is effectively shell access to the machine it runs on. Read [SECURITY.md](SECURITY.md) before deploying.
- Secrets go only in a file only their owner can read, never in the YAML config.
- Logs and Telegram messages are redacted.
- The state directory is created owner-only.

## Build

Requires JDK 25+ and git. The Maven wrapper downloads Maven itself.

```sh
./mvnw verify                       # tests, then target/dispatch-0.1.0.jar
```

## Your own Dispatch (macOS, Windows, Linux)

Each developer can run Dispatch on their own machine with their own bot, for projects wherever their clones are (ADR 0014). It runs as you: the agent can read what you can, and whoever controls your Telegram account or your bot token can make it act as you.

**You need** Java 25 or later, git, Claude Code (run `claude` once to log in), and the GitHub CLI logged in with `gh auth login` for pull requests.

**1. Install.** Build the jar with `./mvnw package`, put it next to the launcher, and put that folder on your PATH:
- **macOS, Linux:** copy `target/dispatch-0.1.0.jar` to `~/.local/share/dispatch/dispatch.jar` and `bin/dispatch` next to it, then `ln -s ~/.local/share/dispatch/dispatch ~/.local/bin/dispatch`.
- **Windows:** copy `target\dispatch-0.1.0.jar` to `%LOCALAPPDATA%\Programs\Dispatch\dispatch.jar` and `bin\dispatch.cmd` next to it, then add that folder to your PATH.

**2. Set up.** Create a bot with @BotFather (`/newbot`), then run `dispatch init`. It asks for these, one step at a time:
1. the bot token, typed without being shown;
2. you: open your bot, press Start, and confirm the name it shows;
3. the `claude` command, found for you when it is on your PATH;
4. your project folders, each with a name, the branch tasks start from, and optionally the model and effort;
5. the commit author.

It writes the config, and the token into a file only you can read:

| | Config | Secrets | State |
|---|---|---|---|
| macOS, Linux | `~/.config/dispatch/dispatch.yaml` | `~/.config/dispatch/dispatch.env` | `~/.local/state/dispatch` |
| Windows | `%APPDATA%\Dispatch\dispatch.yaml` | `%APPDATA%\Dispatch\dispatch.env` | `%LOCALAPPDATA%\Dispatch` |

**3. Check and run.**

```sh
dispatch check                                   # bot token, claude, projects, gh; says what to fix
dispatch run                                     # keep it running while you work; Ctrl+C stops it
dispatch project add ~/work/crm --effort high    # another clone later; restart dispatch run to use it
```

Your bot has no group chat: tasks, plans and results stay in your private chat with it. The config is plain YAML that you may edit by hand, and `dispatch check` validates it. For each project it holds `path` (your clone), `baseBranch`, and optionally `model` and `effort` (`low`, `medium`, `high`, `xhigh` or `max`). Dispatch works in its own worktrees under the state directory and only adds `dispatch/<task>` branches to your clone.

## Set up a team instance

The examples use the instance `backend`.

**1. Create the bot.** Create it with @BotFather. Leave privacy mode enabled (the default) and do not make the bot a group admin, because admins receive every message. Add the bot to each group it serves. Every member opens the bot once and presses **Start**: tasks are given in that private chat. Optionally, turn on topics (threaded mode) for the bot's private chats in @BotFather: each task then gets its own topic there. Dispatch checks this at startup (`task_topics=true` in the `dispatch.started` log line).

**2. Find the ids before starting Dispatch.** Dispatch leaves any group whose id is not in `telegram.groups`.
1. In each group, each member sends `/help@<bot_username>`.
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

In the config, list each group under `telegram.groups` with its `chatId`, `members` and the `projects` it owns (a person may be in several groups; every project belongs to exactly one). Set `delivery.authorName`/`authorEmail` (the git identity of delivery commits) and `limits.execute`. The GitHub token needs Contents and Pull requests read/write on the team's repositories. Execution runs in auto mode, which not every model has: set the project's `model` to Sonnet or Opus, or leave it unset if the account's default model is one of them. With Haiku, every execution run fails with a permission-mode error.

## Use it

**In your private chat with the bot**

| You | Dispatch |
|---|---|
| write the task as a message (or forward one) | Asks with buttons for the project (skipped if you have only one) and the priority 🔴 🟡 🟢, then queues the task |
| `/task alm Fix the login timeout` | The same, with the project already named |
| **✂️ Салгах** on that prompt | Haiku lists the separate tasks in your message (about $0.015, a few seconds). **✂️ N даалгавар болгох** gives each its own prompt; **Нэг даалгавар** keeps the message as one task |
| **Approve** on the plan | The agent implements it; Dispatch commits, pushes `dispatch/N` and sends you the draft PR link and summary |
| reply to the plan, or write in the task's topic | A correction: the agent revises the plan in the same session |
| **Reject** on the plan | Closes the task |
| `/status` | What is running (with the agent's latest action), queued and awaiting approval in your groups, with buttons to change your tasks' priority |
| `/history`, `/history N` | The last 10 finished tasks with who gave them and when; task N's timeline |
| `/stats` | Your numbers, each group's and per person, for 7 days, this month or all time |
| `/cancel N` | Cancels task N |

**In a group**, Dispatch posts a line when a task is given for one of the group's projects (who, project, priority, title) and a line per outcome: done with the PR link, failed with the reason, rejected, or cancelled. `/status@bot`, `/history@bot` and `/stats@bot` there cover that group's projects. With privacy mode on, only `/command@<bot_username>` reliably reaches the bot in a group.

Only configured members can give tasks, and only for their groups' projects. Only the requester can approve, correct, reject or reprioritize their task; any member of the project's group can cancel it. A plan with open questions has no Approve button: answer the questions by replying to it. The most urgent queued task starts first; nothing running is interrupted.

## Run from a checkout

```sh
./mvnw package
java -jar target/dispatch-0.1.0.jar init --config dev.yaml    # dev.yaml and dev.env are git-ignored
java -jar target/dispatch-0.1.0.jar run --config dev.yaml
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
- **Config check:** `dispatch check --config <file>` names each problem and what to do about it.
- **Raw agent output:** `/var/lib/dispatch/backend/runs/<task>/<run>.jsonl` and `.stderr`; splits under `splits/<draft>-<epoch millis>.jsonl`. Grep the log for `event=split.` to see what each split cost.
- **Restarts:** stopping or restarting interrupts active runs. They fail as `INTERRUPTED` and the group is told.
- **Worktrees:** they accumulate under `worktrees/` until the M3 sweep. Remove finished ones with `git -C repos/<project> worktree remove --force worktrees/<id>`; a task whose worktree is gone can no longer be corrected or executed.
- **Delivery:** commits are made without hooks or signing, as `delivery.authorName`. A failed push or PR creation fails the task as `DELIVERY`; the commit stays in the worktree.
- **Bot texts:** `src/main/resources/messages_mn.properties`.
