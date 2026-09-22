# Dispatch

Dispatch takes development tasks that members write to its Telegram bot and has Claude Code plan them in a git worktree. Once the requester approves the plan, the agent implements it and Dispatch delivers the change as a draft pull request. One instance and bot can serve several groups, each with its own members and projects.

- Design: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), decisions in [docs/adr/](docs/adr/), vocabulary in [CONTEXT.md](CONTEXT.md).
- Status: **M3g.** Dispatch installs with one command on macOS, Windows or Linux, and `dispatch init` sets up a bot for just you or for your team, running in the background. Teammates join when an admin approves them in Telegram. Tasks are given in the private chat with project and priority buttons, and a message with several tasks can be split with ✂️. The plan, corrections and result stay in the private chat, in a topic per task when the bot has topics on. A team's group sees its projects' tasks and outcomes in one line. `/status`, `/history` and `/stats` report on your groups. Reply to a result to follow up on it, `/retry` a failed step, and send screenshots or files with a task for the agent to read. `CodexAgent` comes next.

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

With the web UI (needs Node):

```sh
(cd ui && npm ci && npm run build) && ./mvnw -Pui verify
```

## Get started (macOS, Windows, Linux)

Dispatch runs on your own machine, with a bot for just you or one your team shares (ADR 0014–0016). It runs as you: the agent can read what you can, and whoever controls the bot's admin accounts or its token can make it act as you.

**You need** Java 25 or later, git, Claude Code (run `claude` once to log in), and the GitHub CLI logged in with `gh auth login` for pull requests.

**1. Install** with one command. It downloads the latest release and puts `dispatch` on your PATH, and builds Dispatch
from source instead when run from a checkout, when `DISPATCH_FROM_SOURCE=1` is set, when `DISPATCH_REF` names a branch
rather than `main` or a `v*` tag, or when the download fails:

```sh
# macOS, Linux
curl -fsSL https://raw.githubusercontent.com/astvision/dispatch/main/install.sh | sh
# while the repository is private:
gh api -H "Accept: application/vnd.github.raw" repos/astvision/dispatch/contents/install.sh | sh
```

```powershell
# Windows (PowerShell)
irm https://raw.githubusercontent.com/astvision/dispatch/main/install.ps1 | iex
# while the repository is private:
gh api -H "Accept: application/vnd.github.raw" repos/astvision/dispatch/contents/install.ps1 | Out-String | iex
```

**2. Set up** with `dispatch init`. Create a bot with @BotFather (`/newbot`) first. The wizard uses the arrow keys and asks, step by step:

1. **Who will use this bot:** just you, or your team.
2. **The bot token:** typed masked, then checked with Telegram.
3. **People:** you open the bot, press Start and confirm your name. For a team, teammates press Start while it waits, and when you add the bot to your team group it finds that group for its announcements. The bot answers each Start in Telegram. If nothing arrives, the wizard says what to check. For example, Telegram may not deliver your messages while the bot is connected under Settings > Chat Automation.
4. **Claude Code:** found on your PATH, or given.
5. **Projects:** the folders of your git clones, each with the branch tasks start from and, from a list, the model and effort.
6. **Commits:** the author of Dispatch's commits.

Or run `dispatch ui` and set it up in your browser: the same steps, with a QR code for the bot, buttons to confirm people, and a folder browser for your clones. On a server, open the page through `ssh -L` (see [Manage it in the browser](#manage-it-in-the-browser)).

Both have an Advanced section that stays closed unless you open it (in the terminal: `dispatch init --advanced`): per project an alias, and a model and effort for planning and for execution; for the whole instance the timeout and budget per run for planning and for execution, how many runs at a time, the state directory and the GitHub CLI command. Whatever you leave alone keeps its default.

It shows a summary and writes nothing until you confirm. Then it offers to keep Dispatch running in the background, also after a restart.

| | Config | Secrets (only you can read) | State and log |
|---|---|---|---|
| macOS, Linux | `~/.config/dispatch/dispatch.yaml` | `~/.config/dispatch/dispatch.env` | `~/.local/state/dispatch` |
| Windows | `%APPDATA%\Dispatch\dispatch.yaml` | `%APPDATA%\Dispatch\dispatch.env` | `%LOCALAPPDATA%\Dispatch` |

Every change to the config — from the pages, `dispatch project add`, or the running bot adding someone who joined from Telegram — first takes an exclusive lock on `dispatch.yaml.lock` beside it, so two writers never lose each other's change; leave that file in place.

**3. Use it.** Write a task to the bot in Telegram (see [Use it](#use-it)). From the terminal:

```sh
dispatch check                                   # config, bot token, claude, projects, gh: says what to fix
dispatch service status                          # also: start, stop, install, uninstall
dispatch project add ~/work/crm --effort high    # add another clone, then: dispatch service stop && dispatch service start
dispatch run                                     # run in this terminal instead of the background
dispatch ui                                      # manage it in your browser: projects, people, settings, logs
```

The service is a systemd user service on Linux, a launchd agent on macOS and a Task Scheduler task on Windows. It starts at login and restarts after a failure. On Linux, it keeps running after you log out only once lingering is on; `dispatch service status` says so.

### A bot for your team

One machine runs the team's bot, with clones of the team's projects: a small server or an always-on computer. Run `dispatch init` there and choose **My team**. Everyone writes their tasks to the same bot, in their own private chat, and the team group gets a one-line announcement per task and outcome.

- **Joining later:** someone new opens the bot and writes to it. The bot's admins (you, after `init`) get their name with a button per group and **Deny**. Allowing adds them to the config and they can give tasks at once, no restart needed. After a Deny, a person can ask again a day later.
- **Admins:** `telegram.admins` in the config lists the Telegram user ids of the people who decide.
- **Config:** plain YAML you may edit by hand; `dispatch check` validates it. Per project: `path` (the clone), `baseBranch`, and optionally `model` and `effort` (`low`, `medium`, `high`, `xhigh` or `max`), for both phases or per phase: `plan: { model: opus, effort: high }` or `execute: { model: sonnet }`. Dispatch works in its own worktrees under the state directory and only adds `dispatch/<task>` branches to the clone.

### Help the agent: CLAUDE.md

Every planning and execution run reads the project's `CLAUDE.md` (or `.claude/CLAUDE.md`) as committed on its base branch. Without one, each run first spends several tool calls finding its way around. Keep it short, because every run pays for reading it:
- what the project is and where its main code lives
- the exact commands to build it and run its tests
- conventions a change must follow, and what not to touch

`dispatch check` names the projects that have none.

### Manage it in the browser

`dispatch ui` shows Dispatch's version and files, whether the background service runs, and everything `dispatch check`
finds, with what to do about it. It prints a link and opens it in your browser; the link works once, and Ctrl+C stops the
page. Without a config, the page sets Dispatch up, step by step, as `dispatch init` does.

With a config, the page has a menu:

- **Overview:** the version and files, the background service with **Restart**, and what `dispatch check` finds.
- **Projects:** add a clone with the folder browser; change a project's base branch, alias, model and effort, for both phases or per phase; remove it. A project's model may be any model id (for example `claude-opus-5`), while setup offers Sonnet, Opus and Fable.
- **People:** each group's members and the admins: rename, remove, make or unmake admin. New people still join by writing to the bot and an admin's approval in Telegram.
- **Settings:** the timeout and budget per run, how many runs at a time, the commit author, and the Claude Code and GitHub CLI commands.
- **Logs:** the background service's log, refreshed every 2 seconds, filtered by level and event, with secrets masked.

A save changes only the lines it must, so your comments and layout stay, and keeps the previous file as `dispatch.yaml.bak`. It is refused when the file changed on disk since the page loaded it: reload and try again. The running Dispatch reads its config when it starts, so after a save the page offers **Restart now**.

On a server, from your own computer:

```sh
ssh -L 7878:localhost:7878 you@server    # then, on the server:
dispatch ui --no-browser                 # and open the link it prints on your computer
```

The tunnel's local and remote ports must match (as above): the page only accepts requests for its own port. The page
listens only on the machine it runs on. Anyone with its link can act as you, like a shell: see SECURITY.md.

## Set up a team instance with systemd (Linux server)

For a dedicated server with an OS user per team, run Dispatch as a system service instead. The examples use the instance `backend`.

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
| send a photo or file with the task (as its caption) | The agent gets it to read. Files over 20 MB are skipped, and the prompt says so |
| `/task alm Fix the login timeout` | The same, with the project already named |
| **✂️ Салгах** on that prompt | Haiku lists the separate tasks in your message (about $0.015, a few seconds). **✂️ N даалгавар болгох** gives each its own prompt; **Нэг даалгавар** keeps the message as one task |
| **Approve** on the plan | The agent implements it; Dispatch commits, pushes `dispatch/N` and sends you the draft PR link and summary |
| reply to the plan, or write in the task's topic | A correction: the agent revises the plan in the same session |
| **Reject** on the plan | Closes the task |
| reply to the result, or write in a finished task's topic | A follow-up: the agent continues in the same session, and one more commit goes to the same pull request |
| `/retry N` | Repeats task N's failed step: a failed plan is planned again, a failed execution continues, a failed delivery is only delivered again |
| `/status` | What is running (with the agent's latest action on your own tasks), queued and awaiting approval in your groups, with buttons to change your tasks' priority |
| `/history`, `/history N` | The last 10 finished tasks with who gave them and when; task N's timeline |
| `/stats` | Your numbers, each group's and per person, for 7 days, this month or all time |
| `/cancel N` | Cancels task N |
| `/projects` | Your projects with their base branch, and why any cannot take tasks now |

Each plan and result ends with the model that answered, the cost and the duration. A ⚠️ line appears when the model isn't the one the config asks for.

**In a group**, Dispatch posts a line when a task is given for one of the group's projects (who, project, priority, title) and a line per outcome: done with the PR link, failed with the reason, rejected, or cancelled. `/status@bot`, `/history@bot`, `/stats@bot` and `/projects@bot` there cover that group's projects. Replying to an outcome line there is a follow-up too. With privacy mode on, only `/command@<bot_username>` reliably reaches the bot in a group.

Only configured members can give tasks, and only for their groups' projects. Only the requester can approve, correct, reject, reprioritize, follow up on or retry their task; the requester or an admin can cancel it. Other members see only a task's headline: who, project, title, priority, state and pull request, not its plan, the agent's actions or its cost. A plan with open questions has no Approve button: answer the questions by replying to it. The most urgent queued task starts first; nothing running is interrupted.

## Run from a checkout

```sh
./install.sh                           # builds this checkout and installs it; .\install.ps1 on Windows
dispatch init --config dev.yaml        # dev.yaml and dev.env are git-ignored
dispatch run --config dev.yaml
```

Locally, `claude` uses your own login. Your plugins and MCP servers are not loaded into agent runs, but the repository's `CLAUDE.md` is.

## Operate

- **Logs:** `journalctl -u dispatch@backend -f`. Lines are logfmt; grep for `level=ERROR` or `event=task.transition`.
- **State:** `sqlite3 /var/lib/dispatch/backend/dispatch.db`

  ```sql
  SELECT id, phase, project, title, pr_url, failure_reason FROM task ORDER BY id DESC LIMIT 20;
  SELECT task_id, seq, kind, cause, status, cost_usd, turns, error_detail FROM run ORDER BY task_id DESC LIMIT 20;
  SELECT id, kind, status, attempts, last_error FROM outbox WHERE status <> 'SENT';
  SELECT * FROM task_event WHERE task_id = 42 ORDER BY id;
  ```
- **Config check:** `dispatch check --config <file>` names each problem and what to do about it.
- **Raw agent output:** `/var/lib/dispatch/backend/runs/<task>/<run>.jsonl` and `.stderr`; splits under `splits/<draft>-<epoch millis>.jsonl`. Grep the log for `event=split.` to see what each split cost.
- **Restarts:** stopping or restarting interrupts active runs. They fail as `INTERRUPTED` and the group is told.
- **Worktrees:** every hour, worktrees of tasks idle for `worktrees.idleDays` (default 7) are removed: a completed or failed task's only when it is clean and pushed (otherwise `event=sweeper.kept`), a rejected or cancelled task's anyway. The `dispatch/<id>` branch stays, and a later follow-up or retry recreates the worktree from it.
- **Clones:** a project with a `repo` and no clone is cloned into `repos/<name>` when Dispatch starts; until then `/projects` shows it as cloning, and a failed clone as the git error (`event=project.clone_failed`).
- **Attachments:** downloaded into `attachments/<task>/`, outside the worktree, so they are never delivered.
- **Delivery:** commits are made without hooks or signing, as `delivery.authorName`. A failed push or PR creation fails the task as `DELIVERY`; the work stays in the worktree and `/retry` delivers it without the agent.
- **Bot texts:** `src/main/resources/messages_mn.properties`.
