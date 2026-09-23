# Dispatch architecture

Dispatch turns development tasks posted in a team's Telegram group into AI coding-agent runs on the team's repositories:
1. The agent posts a read-only plan.
2. A member approves the plan.
3. The agent implements it.
4. Dispatch delivers the change as a draft pull request.

Dispatch is the task, state and communication layer; coding stays with the agent CLI. Vocabulary is defined in [CONTEXT.md](../CONTEXT.md), and the reasons behind each decision in [adr/](adr/).

## Decisions at a glance

| Area | Decision | ADR |
|---|---|---|
| Deployment | One single-process instance per team (own bot, config, SQLite, OS user) on the host holding the team's repos | 0001, 0005 |
| Stack | Plain Java 25, no framework, Maven wrapper, one fat jar | 0002 |
| State | SQLite in WAL mode; every state change is a conditional update | 0003 |
| Build vs adopt | Build; borrow ideas from claude-code-telegram | 0004 |
| Flow | Read-only plan, then member approval, then execution. Replies to a plan are corrections; replies to a result are follow-ups | 0006 |
| Delivery | Dispatch makes one commit per run, pushes `dispatch/<id>` and opens a draft PR | 0007 |
| Restarts | Active runs fail as interrupted; members `/retry` | 0008 |
| Permissions | Plan mode for planning; auto mode plus deny rules for execution, with the agent's actual mode verified; the OS user is the hard boundary | 0009 |
| Telegram | Offset advanced only after commit; outcome messages go through an outbox | 0010 |
| Private details | Plan, corrections and full result go to the requester's private chat, with a one-line outcome in the group and a group fallback; only the requester decides on their plan | 0011 |
| Groups, private tasks, priority | One bot serves several groups, each with members and projects; tasks are given privately with project and priority buttons; priority orders the queue | 0012 |
| Splitting | Only on request (✂️): Haiku without tools proposes the parts of a message, and the member splits it or keeps it whole | 0013 |
| Personal instances | A developer's own bot and instance on their own machine (macOS, Windows, Linux): projects at any `path`, groups without a chat, `effort` per project, set up with `dispatch init` | 0014 |
| Joining a shared bot | People who write to a team's bot ask to join; `telegram.admins` approve them per group in Telegram, and the config is updated without a restart | 0015 |
| Setup | One-line install, an arrow-key `dispatch init` for a personal or a team bot, and a per-user background service on each OS | 0016 |
| Agent sessions | A task has a planning session (the plan and its corrections) and a building session, which execution starts from the approved plan | 0017 |
| Setup and management in a browser | `dispatch ui`, a separate process on 127.0.0.1 with a one-time link | 0018 |
| Task privacy | Another member's task shows only its headline; only the requester acts on it, except cancel, which an admin may also do; a private message Telegram refuses falls back to a content-free group notice | 0020 |

Also decided without an ADR:
- Only members of a configured group act, for their groups' projects, in their own private chat with the bot; groups get announcements and read-only reports.
- At most `maxConcurrentRuns` runs at once, and at most one execution per project.
- Per-run timeout and budget.
- Structured plans (`--json-schema`).
- Mongolian bot texts; the agent writes in the task's language.
- Telegram attachments are passed to the agent.
- No `Channel` interface: the core API is channel-neutral.
- Worktrees are removed by an idle-TTL sweep.
- Every pull request is tested on Linux, macOS and Windows.

## Deployment

```
/opt/dispatch/dispatch.jar            shared binary
/etc/dispatch/<team>.yaml             config
/etc/dispatch/<team>.env              0600: TELEGRAM_BOT_TOKEN, ANTHROPIC_API_KEY, GH_TOKEN
/var/lib/dispatch/<team>/             StateDirectory
  dispatch.db                         SQLite
  repos/<project>/                    Dispatch-owned clones
  worktrees/<task>/                   one worktree per task, branch dispatch/<task>
  attachments/<task>/                 Telegram files, outside the worktree
  runs/<task>/<seq>.jsonl             raw agent output per run (+ .stderr)
```

The systemd template `dispatch@.service` sets:
- `User=dispatch-%i`
- `EnvironmentFile=/etc/dispatch/%i.env`
- `StateDirectory=dispatch/%i`
- `ExecStart=/usr/bin/java -jar /opt/dispatch/dispatch.jar /etc/dispatch/%i.yaml`
- `Restart=on-failure`
- `KillMode=mixed`: SIGTERM reaches only Dispatch, which stops its own agents and records their runs as INTERRUPTED. With the default mode, systemd would kill the agents directly and the runs would be misreported as agent errors.

The instance user has `claude` and `gh` installed, file access only to its team's repositories, and a fine-grained GitHub token scoped to them. Monthly spend is capped by a spend limit on the team's API key in the Anthropic Console.

A personal instance (ADR 0014) runs in the developer's terminal with `dispatch run`, as the developer, using their own `claude` and `gh` logins:

```
                           macOS, Linux                          Windows
config                     ~/.config/dispatch/dispatch.yaml      %APPDATA%\Dispatch\dispatch.yaml
secrets (owner-only)       ~/.config/dispatch/dispatch.env       %APPDATA%\Dispatch\dispatch.env
state                      ~/.local/state/dispatch/              %LOCALAPPDATA%\Dispatch\
projects                   wherever each project's path points; Dispatch adds worktrees under state
```

`bin/dispatch` (sh) and `bin/dispatch.cmd` run `dispatch.jar` from their own folder.

## Components

```
               Telegram Bot API
          getUpdates │   ▲ sendMessage / editMessageText / sendDocument
┌────────────────────▼───┴──────────────────────────────────────┐
│ telegram   Poller -> UpdateHandler            OutboxSender     │
└──────────────────────┬──────────────────────────▲──────────────┘
          core commands │                          │ outbox rows
┌──────────────────────▼──────────────────────────┴──────────────┐
│ core       TaskService   Scheduler -> Coordinator   Recovery   │
│            Splitter                                 Sweeper    │
└───────┬─────────────────────────┬─────────────────────┬────────┘
   store (SQLite)          workspace (git, gh)     agent: Agent
                                                   ClaudeCodeAgent
```

| Package | Responsibility |
|---|---|
| `Main`, `App` | `Main` validates config and installs the shutdown hook. `App` wires everything explicitly: it migrates the DB, checks the bot token (`getMe`, which also says whether the bot has topics in private chats), runs `Recovery`, fails splits a previous process left running, registers the command menus (group, private chats), starts cloning missing repos in the background, and starts the poller, scheduler, outbox, draft-expiry and sweeper threads. A loop that dies unexpectedly is fatal. |
| `config` | YAML + env loading into records. Startup fails naming the invalid field. `ConfigText` and `ConfigEdit` change the file's text in place (add a project or a member; set, remove or append a value), so comments and layout stay; `ConfigFile` replaces the file only with a version that validates. `ConfigFile.edit` holds an exclusive lock on the sibling `.lock` file across read, change, validation and replace. |
| `cli` | The `dispatch` command: `init` (the setup wizard, on a JLine terminal), `project add`, `check`, `service install/start/stop/status/uninstall` (systemd user unit, launchd agent, Task Scheduler task), and `run` (with `--log-file` for services); on a member's own computer, `worker init` (pair this computer, map its projects, install its service), `worker pair`, `worker run`, `worker service`. Per-OS default locations; the secrets file beside the config is merged under the process environment. `install.sh` and `install.ps1` download the release jar and launcher, or build them from source, and install them. |
| `store` | SQLite access and migrations (`PRAGMA user_version`); conditional updates. One connection behind a lock. |
| `core` | `TaskService`: the channel-neutral commands `draft / split / create / approve / reject / correct / followUp / retry / cancel / status / history / timeline / stats`. `Scheduler` picks runs; `Coordinator` drives one run: it reads the store and the config into an immutable `Job`, hands it to a `Worker` — in this process `JobRunner`, which does the worktree, the agent and the delivery and touches no store — and applies the returned `JobResult` through `RunTransitions`; `Splitter` runs splits beside them; `Recovery` handles startup; `Sweeper` removes idle worktrees — in a team those worktrees are on members' computers, where `dispatch.worker.WorkerSweeper` does the same job. |
| `agent` | `Agent` interface and `ClaudeCodeAgent` (CLI subprocess + stream-json parser). Tests run the real adapter against a fake `claude` shell script that replays recorded output. `CodexAgent` comes later. |
| `workspace` | Clone, fetch, worktree add/remove/recreate, `copyFiles`, commit, push, `gh pr create`, delivering a failed delivery again. |
| `telegram` | Bot API client (`java.net.http` + Jackson, including file downloads for attachments), `Poller`, `UpdateHandler` (parses updates, calls `TaskService`), `OutboxSender`, status edits, `messages_mn.properties`. |
| `ui` | `UiServer` (JDK HttpServer, loopback only), `UiAuth` (one-time link, session cookie, Host and Origin checks), `OverviewApi` (version, paths, service status, `Checks`), `SetupApi` (setup in the browser: the steps of `dispatch init` as POST calls, answers kept in memory until Write) and `Folders` (the folder browser), `ManageApi` (the management pages: the config with its SHA-256 version, saves of settings, projects and people through `ConfigEdit` with `dispatch.yaml.bak` and a stale-version check, the service log, Restart); `dispatch.cli.Setup` holds what `dispatch init` and `SetupApi` share: the token check, the setup updates, and rendering and writing the config. The React + Ant Design page in `ui/` is bundled into the jar by the `ui` profile. |

`core` never imports `telegram`. A second messenger would be a new package that calls `TaskService` and renders the same outbox rows.

## Domain model

| Table | Key columns |
|---|---|
| `task` | `id` (#42), `project`, `title` (first line, ≤ 80 chars), `description`, `phase`, `priority` (`URGENT / NORMAL / LOW`), `requester_ref/name`, `origin_ref` (unique, e.g. `telegram:-100123/5567`), `chat_ref`, `session_id` (the planning session's UUID, set when the task is created), `build_session_id` (the building session's, set by the first execution run), `base_branch`, `base_sha`, `worktree`, `plan_json`, `failure_reason/detail`, `created_at`, `started_at`, `completed_at`, `pr_url`, `topic_ref` (the task's topic in the requester's private chat, once created). The branch is always `dispatch/<id>` and is not stored. |
| `run` | `task_id`, `seq` (run 42.2), `kind` (`PLAN / EXECUTE / DELIVER`), `cause` (`TASK / CORRECTION / APPROVAL / FOLLOW_UP / RETRY`: what the agent is told), `status` (`QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED`), `instruction` (task text, correction, the approved plan, a follow-up, or for a DELIVER run the summary to commit with), `requested_by`, `requested_by_name`, `pid`, `pid_start`, `queued_at`, `started_at`, `finished_at`, `exit_code`, `failure_reason` (`SETUP / AGENT / TIMEOUT / BUDGET / INTERRUPTED / DELIVERY / INTERNAL`), `error_detail`, `cost_usd`, `turns`, `output` (a plan, or an execution's summary, kept when its delivery failed), `denials`, `model` (the model that answered; several are joined with ", "). The raw log path is derived: `runs/<task>/<seq>`. |
| `outbox` | `task_id`, `kind`, `chat_ref`, `reply_to_ref`, `edit_ref` (a message this row redraws instead of sending a new one), `fallback_chat_ref/reply_to_ref` (where a refused private message goes instead), `fell_back`, `payload`, `status` (`PENDING / SENT / FAILED`), `attempts`, `next_attempt_at`, `last_error`, `sent_ref` |
| `draft` | `requester_ref/name`, `chat_ref` (private chat), `origin_ref` (unique: the message, plus `#<part>` for a part), `description`, `project` (once chosen), `status` (`OPEN / CREATED / EXPIRED / SPLIT`), `task_id`, `prompt_ref` (the prompt ✂️ was pressed on), `split_state` (`SPLITTING / PROPOSED / ONE_TOPIC / KEPT / FAILED`), `topics` (the proposed parts, JSON), `parent_id` and `part` (for a part) |
| `attachment` | `draft_id`, then `task_id` once the task is given; `file_ref` (the channel's id to download it), `name` (safe, numbered), `size` |
| `task_event` | `task_id`, `run_seq`, `at`, `actor`, `from_phase`, `to_phase`, `reason` (append-only audit) |
| `join_request` | `requester_ref/name`, `username`, `status` (`OPEN / APPROVED / DENIED`), `group_name`, `decided_by(_name)`, `created_at`, `decided_at` |
| `kv` | Telegram `getUpdates` offset |

Finished runs are never modified; a retry or follow-up creates a new run. All timestamps are UTC.

### Task phases

```
/task -> PLANNING --plan posted--> AWAITING_APPROVAL
            ^                         |     |      |
            +------ correction -------+     |      +-- reject --> REJECTED
                                            | approve (no open questions)
                                            v
                         EXECUTING (run queued > running > delivering)
                            | ok                   | failure / interrupted
                            v                      v
                        COMPLETED               FAILED
                            | follow-up            | /retry, or a follow-up after an execution
                            +----> EXECUTING <-----+

planning failure -> FAILED; /retry re-plans
/cancel from PLANNING / AWAITING_APPROVAL / EXECUTING -> CANCELLED
REJECTED and CANCELLED are terminal. Plans never expire.
```

Every transition is one transaction that does all of the following:
- a conditional `UPDATE ... WHERE phase = ?`,
- a `task_event` row,
- an `outbox` row when the team must be told,
- the Telegram offset, when an update caused the transition.

A transition that loses a race updates 0 rows and is logged.

## Flows

**Messages (ADR 0011, 0012).** Tasks are given in the member's private chat with the bot. The project's group gets a one-line announcement (who, project, priority, title). The plan, the execution notice and the full result or failure go to the requester privately, under the message that gave the task. The group gets a one-line outcome: done with the PR link or "no changes", failed with the reason, rejected, or cancelled. If Telegram refuses a private message permanently (the requester blocked the bot), the outbox re-addresses it to the group as a content-free notice — the task number and a hint to open the bot and press Start, not the plan, result or failure itself (ADR 0011, 0020). A group message replies to a task's message only if the task was given in that group, so it never lands on an unrelated message with the same id.

**Give a task (draft).**
1. A member writes the task in their private chat, forwards a message there, or sends `/task [project] [text]` (the first word counts as the project only if it is one of theirs; a replied-to message becomes the text). Anything written privately that is neither a command nor a reply to a plan is a task.
2. One transaction: a `draft` (OPEN) + a prompt in the outbox + offset. The prompt asks with buttons for the project, unless the member can use only one or named one, and for the priority. Only the projects of the member's groups that can take tasks are offered.
3. A project button records the choice; the priority button gives the task. Both redraw the prompt in place (best effort). Only the draft's writer can answer it. A draft nobody answers expires after 24 h (checked every minute), with a note naming it.
4. Giving the task is one transaction: `task` (PLANNING, with priority; `chat_ref` is the project's group) + `run` 1 (PLAN, QUEUED) + event + the group's announcement + the draft marked CREATED. With topics on, a topic for the task is created in the requester's private chat (below).
5. `/task` and `/cancel` in a group are answered with a pointer to the private chat.

**Split a message (ADR 0013).**
1. A whole message's prompt has ✂️. Pressing it is one transaction: the draft becomes SPLITTING and remembers the prompt; after commit the `Splitter` starts on its own virtual thread. The prompt is redrawn with "splitting…"; project and priority stay available.
2. The `Splitter` runs a SPLIT agent run (below): at most two at a time, $0.25 budget, one-minute timeout. Its answer must be one to ten non-blank texts.
3. One transaction records the outcome and enqueues an outbox edit of the remembered prompt:
   - several topics: PROPOSED, and the prompt lists them with [✂️ N даалгавар болгох] [Нэг даалгавар];
   - one topic: ONE_TOPIC, and the prompt says so;
   - anything else (agent failure, budget, timeout, unusable answer): FAILED, and the prompt offers ✂️ again. The detail is logged.
4. Splitting is one transaction: the draft becomes SPLIT, and each part becomes an OPEN draft (`origin_ref` `<message>#<n>`, the whole message's project if still available) with its own prompt under the original message. Keeping it whole (KEPT) redraws the prompt with project and priority, without ✂️.
5. An answer for a draft that was given or expired meanwhile is discarded. On stop, running splits are cancelled and not recorded; on start, drafts still SPLITTING become FAILED.

**Join a shared bot (ADR 0015).**
1. A private message from someone who is not a member, while `telegram.admins` is set, becomes an OPEN `join_request`. The person is told an admin will decide, and each admin gets their name, @handle and id with a button per group and Deny. Without admins, such messages are only logged.
2. While a request is open, or within a day of a Deny, further messages from that person send nothing.
3. Allowing: the member is inserted into that group in the config file, in place, and the edited file must validate before it atomically replaces the old one. The running `Groups` are then replaced, so the person can give tasks at once. If the config cannot be written, the request stays open and the error is logged.
4. The person is told the decision; the admin's message is redrawn with it; stale buttons answer that it was already decided.

**Task topics.** When @BotFather has topics on for the bot's private chats (`getMe` reports `has_topics_enabled`), giving a task enqueues `createForumTopic` in the requester's private chat, named `#7 · project · title` and colored by priority. The task's private messages (plan, corrections, execution notice, result) go into that topic, and anything written there that is not a command corrects the latest plan. When the outcome is sent, the topic is renamed with ✅ ❌ 🚫 🛑. A topic that cannot be created leaves the task in General; one that is gone later (deleted, or `message thread not found`) is forgotten, and the task's messages go to General.

**Plan run.**
1. `git fetch origin <base>`, then `git worktree add -b dispatch/<id> worktrees/<id> origin/<base>`. Record `base_sha`. `copyFiles` are **not** copied: planning needs no local secrets, and whatever the agent can read may be quoted in a plan posted to the group.
2. Run the agent read-only, requiring the plan JSON schema.
3. On success, store `plan_json` and move to AWAITING_APPROVAL. The outbox sends the plan to the requester:
   - `[Approve]` and `[Reject]`, where the button data carries the plan's run seq so a stale button is refused;
   - if `questions` is non-empty, no Approve button: the requester answers by replying, which is a correction;
   - plans over 4096 chars go as a message plus `plan-<id>.md`.

**Approve** (by the requester only): an EXECUTE run is queued whose instruction is the approved plan. Another member's press, a stale button, a task in another phase, or a plan with open questions is refused in the button's answer.

**Correction:** the requester's reply to a plan message, in the private chat or, after a fallback, in the group. The reply is matched to its plan through the message id Telegram gave that plan (`outbox.sent_ref`); a reply that merely starts like a command (`/api/login fails too`) still counts. A PLAN run is queued with the reply as instruction; it resumes the session in the same worktree and returns the complete revised plan. A reply from another member, to a superseded plan, or while the task is not awaiting approval is refused with the reason.

**Reject** (by the requester only): REJECTED.

**Execute run.** The run continues in the task's worktree; one the sweep removed is added back from the `dispatch/<id>` branch (fetched from origin if the clone lost it). `copyFiles` are copied in. Each must be git-ignored, otherwise setup fails, so delivery can never commit it. The agent implements the approved plan in auto mode. On success, delivery:
1. If the agent committed anyway, its commits are folded back (`git reset --soft` to the run's start) so the run still becomes one commit and is not mistaken for "no changes".
2. `git add --all`. If anything is staged: one commit, without hooks or signing so it behaves the same on every run. The subject is `dispatch #<id>: <title>`; the body is the agent's summary, redacted; trailers are `Requested-by` / `Approved-by`; author and committer are `delivery.authorName`/`authorEmail`.
3. `git push origin dispatch/<id>:refs/heads/dispatch/<id>`, without hooks.
4. `gh pr create --draft` when the task has no PR yet; later pushes update the same PR.

The task becomes COMPLETED with PR link, files changed, cost, duration and any denied actions. With no changes it is COMPLETED with "no changes" and nothing is pushed.

**Follow-up** (the requester only; ADR 0020). A reply to a COMPLETED/FAILED task's result, in the private chat or to the group's outcome line, or a message in a finished task's topic, queues an EXECUTE run immediately in the building session and branch. Its delivery adds one commit to the same pull request. A reply from another member is refused as not the requester; replies while the task is active are refused with its phase; a task that never reached execution is refused with a pointer to `/retry`.

**Retry** (`/retry <id>`, privately, by the requester only; ADR 0020; FAILED only) repeats just the failed run, as the same kind:
- a PLAN run: planned again with the same instruction (the task or the correction);
- an EXECUTE run: continued in the building session with the same instruction and a note on how the previous run ended (reason and detail);
- a `DELIVERY` failure, or a failed DELIVER run: a DELIVER run commits what the pushed branch lacks as one commit (folding a commit that was made but not pushed), pushes, and opens the pull request if there is none. No agent runs; the commit message is the failed run's summary.

A session is resumed only if an earlier run of that phase started its agent: a plan whose first run failed in setup starts its planning session instead.

**Attachments.** Photos (the largest size) and documents on the task's message, or on the message `/task` replies to, are stored with its draft (each part of a split gets them all) and then its task. Before each run, files not yet downloaded are fetched into `attachments/<task>/` (under a temporary name first), which the agent reads through `--add-dir`; its prompt lists them. Names are reduced to `[A-Za-z0-9._-]` and numbered. Files over 20 MB (the Bot API's download limit) are skipped, and both the draft prompt and the agent's prompt name them. A download that fails fails the run as `SETUP`.

**Priority** (by the requester only, while the task is active): buttons under a private `/status` change it; the report is redrawn in place and the change is recorded in `task_event`.

**Cancel** (the requester or an admin, privately; an admin may cancel any task, even one outside their own groups; ADR 0020). A queued run is dropped. A running run gets SIGTERM on its process tree, then a 10 s grace period, then SIGKILL. The task becomes CANCELLED and nothing is delivered. The group is told; a cancel sent privately is also answered there.

**Status, history and stats** (a group chat sees its own projects, a member privately those of all their groups; another group's task is answered as not found). For a task that is not the viewer's own, these show only its headline — who, project, title, priority, state and PR link — never its plan, the agent's actions or its cost (ADR 0020). A group chat has no viewer, so it always sees headlines only:
- `/status`: running runs with priority and elapsed time, then queued runs, then plans awaiting approval; the agent's step count and latest action (read live from its stream) appear only for the viewer's own running tasks. Privately, a row of priority buttons per own active task.
- `/history`: the ten most recently finished tasks with outcome, priority, who gave them and when, and PR link or failure reason; total cost appears only on the viewer's own tasks.
- `/history <id>`: for the viewer's own task, the full timeline — each run's time, duration and cost, corrections with their text, and who approved each execution — ending with the outcome and total cost. For another member's task it shows only the header and outcome: no runs, no cost. Times use the server's time zone (`TZ`).
- `/stats`: tasks given in the last 7 days, this month or all time. The numbers are tasks by outcome, pull requests, median time from task to PR, and the share of tasks that reached execution with their first plan; total and average cost are shown only in the viewer's own "me" view, dropped from a group's and the people view's summary. The views are "me" (privately), each visible group, and per person; buttons redraw the message in place. The people view shows cost only on the viewer's own row.

## Telegram boundary

- Bot API over `java.net.http` + Jackson: `getUpdates` (50 s long poll), `sendMessage`, `editMessageText`, `sendDocument`, `answerCallbackQuery`, `createForumTopic`, `editForumTopic`, `getFile`, `leaveChat`.
- Privacy mode stays on. The bot then receives:
  - `/cmd@thisbot` always;
  - a bare `/cmd` only if Dispatch was the last bot to post in the group (verified in Telegram's docs; groups with other bots, e.g. claude-login-bot, lose bare commands);
  - replies to its own messages;
  - button callbacks.
  
  So at startup Dispatch registers a command menu for each group (`setMyCommands`, chat scope: `/status`, `/history`, `/stats`, `/help`); picking a command from the menu reaches it. `/cmd@otherbot` is ignored. Edited messages are ignored. Never make the bot a group admin: admins receive every message.
- Members' private chats with the bot are served: task messages, `/task`, `/start`, `/help`, `/status`, `/history`, `/stats`, `/cancel`, buttons and replies to plans, with their own command menu (`all_private_chats` scope). Private chats of non-members are ignored and logged. Groups not in the config: `leaveChat` + WARN. A group migrated to a supergroup is logged at ERROR with the new chat ID.
- Buttons: draft project / priority / ✂️ and the split proposal's split / keep whole, plan Approve / Reject, status priority, stats view / period. Replies: correction (to a plan), follow-up (to a result or an outcome line). `/retry <id>` is private like `/cancel`; `/projects` works in both.
- Durability (ADR 0010): the offset is stored in the same transaction as an update's effects. Every bot message except live status edits goes through the outbox, whose sender retries with backoff from 5 s to 5 min, honours `retry_after` on 429, falls back from a refused private chat to the group, marks other messages FAILED after 24 h or immediately on a permanent error, and logs every attempt.
- Rendering: `messages_mn.properties` (ResourceBundle), HTML parse mode with escaping; agent text is cut after escaping so a message never passes Telegram's limit. Messages redrawn in place by a button (draft prompt, status, stats) are edited directly, best effort and redacted like outbox messages. A redraw that a background step causes (a split's answer) is an outbox row with `edit_ref`, retried like any other; a retried edit that Telegram reports as "not modified" counts as sent. Live progress is `/status` on demand.
- Attachments: `getFile`, then a GET of `/file/bot<token>/<file_path>`; errors never carry the URL. See the Attachments flow.

## Agent boundary

```java
interface Agent { RunHandle start(RunRequest request); }
record RunRequest(RunKind kind, Path workdir, String prompt, UUID sessionId /* null for SPLIT */, boolean resume,
                  List<Path> readOnlyDirs, BigDecimal budgetUsd, String model, Path logBase) {}
interface RunHandle {
    ProcessHandle process();                // pid + start time, stored for orphan detection
    AgentResult await();                    // outcome, session id, structured output, cost, turns, denials
    void cancel();                          // non-blocking: SIGTERM process tree, grace, SIGKILL
}
```

The timeout is enforced by `JobRunner` (a watchdog calls `cancel()`), not by the agent. `RunHandle.activity()` returns the agent's step count and latest tool call, parsed from the stream as it arrives, for `/status`.

`ClaudeCodeAgent` passes the prompt on stdin to:

| Always | `claude -p --output-format stream-json --verbose --permission-prompts none --setting-sources project,local --strict-mcp-config --max-budget-usd <b>` + (`--session-id <uuid>` on a session's first run, `--resume <uuid>` afterwards: planning runs use the task's planning session, execution runs its building session) + optional `--model` and `--effort` (a project's `plan` or `execute` block, else the project's own), `--add-dir <attachments>` |
|---|---|
| PLAN | `--permission-mode plan --tools Read,Bash --json-schema <plan schema, compacted to one line>` |
| EXECUTE | `--permission-mode auto --tools Read,Edit,Write,Bash --disallowedTools "Bash(git commit *)" "Bash(git push *)" "Bash(gh *)"` |
| SPLIT | `--permission-mode plan --tools "" --json-schema <topics schema> --system-prompt <one line> --no-session-persistence --disable-slash-commands --model haiku`, no session flags, run in `splits/` |

- The agent's environment excludes `TELEGRAM_BOT_TOKEN` and `GH_TOKEN`; only Dispatch's own git/gh calls get the token. This is a guardrail: processes running as the same user can still read each other's environment.
- The init event's `permissionMode` must equal the requested mode (`plan` or `auto`). Otherwise the run is stopped at once and fails as `AGENT`: Claude Code does not refuse a mode the model lacks.
- The plan schema requires the fields `understanding`, `findings`, `steps[]`, `risks[]` and `questions[]`.
- Prompt rules:
  - Write in the language of the task.
  - Plans: ask questions only when a wrong answer would make the change wrong or harmful; otherwise assume, and list the assumption under risks. Return the plan only as the JSON answer, not also as a plan file.
  - Corrections: the task and the member's correction; return the complete revised plan.
  - Execution: the task and the approved plan, in a fresh session; never commit, push or open PRs; stay focused; run quick relevant tests; end with a short plain-text summary.
- The raw stream goes to `runs/<task>/<seq>.jsonl`. The parser maps `assistant` tool-use blocks to events and the final `result` event to `AgentResult`. It also collects the model of the run's own answers (a subagent's are left out) and compares it with `--model`: an alias such as `sonnet` names a family, an id one model, and `opusplan` or `default` are not compared. A mismatch is stored with the run, logged as `agent.model_differs` and shown as a ⚠️ line under the plan or result. Field names are pinned by fixture files recorded from the installed CLI (2.1.274).
- Failure mapping:
  - timeout (Dispatch-side timer, then `cancel()`): `TIMEOUT`
  - budget exhausted: `BUDGET`
  - non-zero exit, a missing `result` event, or output that doesn't match the schema: `AGENT` (with the stderr tail)
- Permission denials are reported with the run's outcome.

Observed in runs recorded from Claude Code 2.1.274 (the test fixtures):
- **Structured output:** with `--json-schema`, the agent calls a `StructuredOutput` tool and the plan arrives in `result.structured_output`. `StructuredOutput` stays available when `--tools` restricts everything else.
- **Tool restriction:** without `--tools`, a plan run spawned a Sonnet subagent and called scheduling tools; that run cost $0.34. With `--tools Read,Bash`, the same task cost $0.17 and produced no noise.
- **Budget overshoot:** the budget is checked between model calls. A $0.005 cap ended at $0.07 with subtype `error_max_budget_usd`, `terminal_reason: budget_exhausted` and exit code 1.
- **Language:** "the same language as the task" produced a Dutch plan for an English task. The prompt now names the language rule explicitly, and a Mongolian task then got a Mongolian plan.
- **Denials are normal:** plan mode denied some compound shell commands, and once `mkdir ~/.claude/plans`. They are recorded in `run.denials`, not treated as failures.
- **Plan files:** plan mode tells the agent to also save its plan under `~/.claude/plans/`. Two of three plans on a smoke instance did, so each of those plans was generated twice. Claude Code accepts a `plansDirectory` only inside the project root, where delivery would commit the file, so the prompt asks for the JSON answer only.
- **The model asked for is not always the one that answers:** plan runs and splits started with `--model haiku` named Haiku in their init event, but every answer came from Sonnet 5, with no warning. Hence the model check above.
- **Resuming across modes reuses no cache:** an execution run that resumed its planning session 5 seconds after the plan read 9.5k tokens from the prompt cache and wrote 26k, since plan and auto mode differ in tools and system prompt. Writing context to the cache was 52–82% of each run's cost (ADR 0017).
- **Questions:** with the first plan prompt ("questions that must be answered before implementing"), a simple task got three questions, and such a plan cannot be approved. With the current rule, the same kind of task got none, and its assumptions were listed under risks.
- **Splitting:** with the default system prompt, a split cost about $0.02 and 5 seconds, and a $0.05 cap was exceeded on a cold run. With `--system-prompt`, no tools, no skills and no saved session, a three-topic Mongolian message cost $0.015 in 2.9 s, and a one-topic message $0.014 in 3.5 s. Shared context ("staging дээр:") was repeated in each topic, as the prompt asks. Haiku once nested its answer (`{"topics": {"topics": [...]}}`), got a schema error back, and answered correctly on the next turn, so the budget leaves room for a retry.
- **Auto mode is model-dependent:** with Haiku, `--permission-mode auto` started in `default` mode, fresh or resumed. The edit was denied ("no approval surface") and the run still ended as `success` with nothing changed. Sonnet and Opus started in `auto`. A resumed Sonnet run then edited, compiled and checked the change for $0.16. It also wrote scratch files under `/tmp`, outside the worktree, which is allowed because the OS user is the boundary.

## Background execution

- Plain threads, all virtual: Telegram poller, outbox sender, scheduler loop, draft expiry (every minute), the hourly sweeper, one thread per missing clone at startup, one thread per active run, and one per split (at most two agents at once, outside the run queue and its limits).
- The scheduler wakes on each commit that queues a run, and every 5 s. It claims the most urgent `QUEUED` run, oldest first among equals, that may start: a run that must wait never holds back the ones behind it. A run may start when:
  - fewer than `maxConcurrentRuns` runs are active (default 2), and
  - the run is a PLAN, or its project has no active EXECUTE/DELIVER run.
- A claim is a conditional update, `QUEUED → RUNNING`.
- The sweeper removes, every hour, worktrees of finished tasks unchanged for longer than `worktrees.idleDays` (7):
  - COMPLETED/FAILED tasks only if the worktree is clean and its commits are on origin; otherwise it keeps them and logs a WARN;
  - CANCELLED/REJECTED tasks even if dirty, logging the discarded paths;
  - never active tasks (planning, awaiting approval, executing).

  The `dispatch/<id>` branch stays in the clone, so a later retry or follow-up recreates the worktree. The sweep re-reads the task's phase just before removing its worktree and skips it if a follow-up or retry made it active.

**The worker boundary.** A claimed run is carried by the `Coordinator`, which is the only part that reads or writes the store. It reads the task, the run, the project, this phase's limits, the attachments and whether the session's agent already ran into one immutable `Job`. Building that job can itself write to the store: a task's first execution run has the Coordinator record the new build session id before the job is ever handed to a worker, so the `JobEvents` writes below are not the only mid-run store write. It applies the returned `JobResult` as exactly one transition.

```java
interface Worker { JobResult run(Job job, JobEvents events, ActiveRuns.ActiveRun control); }
interface JobEvents {
    void worktreeCreated(String worktree, String baseSha);   // recorded at once: the next run continues there
    void agentStarted(long pid, Instant processStart);       // recorded at once: orphan detection, session resume
}
```

`JobRunner` is the worker in this process: worktree, attachments, agent under its timeout, delivery. It is built with no `Database`, `Projects` or `Config`, so the same class runs a job on a team member's own computer with HTTP in between (W-2 for the split, W-3 for the remote workers). Cancelling reaches it through `control`: `ActiveRuns.stop` sets the run's stop reason, the runner stops its agent (SIGTERM, 10 s grace, SIGKILL) and answers `CANCELLED`. A worker that throws is the worker breaking: the run is logged as `run.crashed` and fails as `INTERNAL`.

In a team the same `JobRunner` runs on the member's computer inside `WorkerLoop`, and `RemoteWorkers` is the `Worker` on this side: it parks the job until one of the requester's computers takes it, keeps the 60 s lease, and answers with the `JobResult` that computer reports (ADR 0021).

## Failure and recovery

| Failure | Behaviour |
|---|---|
| Graceful stop (deploy, `systemctl stop`) | Poller stops, active runs are cancelled, runs are marked FAILED `INTERRUPTED`, and outbox rows are written. |
| Crash / host reboot | On startup, each `RUNNING` run whose PID is alive with the same start time has its process tree killed; the run becomes FAILED `INTERRUPTED` and the group is told. Queued runs and waiting plans continue. |
| Telegram unreachable | Poller retries with logged backoff; outbox retries for up to 24 h; runs are unaffected. Host down > 24 h: Telegram drops older updates (ADR 0001). |
| Bot removed from group (403) | Outbox row FAILED + ERROR. |
| `git fetch` / worktree / `copyFiles` fails | Run FAILED `SETUP` with the git error; `/retry`. |
| Agent exit ≠ 0, no result, invalid plan JSON | FAILED `AGENT` + stderr tail. |
| Agent starts in another permission mode (e.g. Haiku without auto mode) | Stopped at once; FAILED `AGENT` naming both modes. |
| Timeout / budget | FAILED `TIMEOUT` / `BUDGET`; reply or `/retry`. |
| Commit / push / PR fails | FAILED `DELIVERY`; `/retry` runs delivery only. |
| Project clone missing | With a `repo` and no `path`: cloned at startup in the background (30 min timeout); unavailable as "cloning" until then. Otherwise, or when the clone fails: unavailable + ERROR; `/task` and `/projects` say why. |
| Attachment download fails | Run FAILED `SETUP` naming the file; `/retry`. |
| Split fails (agent error, budget, timeout, unusable answer) | Draft split FAILED + WARN with the detail; the prompt says so and offers ✂️ again. |
| Stop or crash during a split | The draft stays SPLITTING until the next start, which fails it and redraws its prompt. |
| Task topic deleted or not found | The task forgets its topic + WARN; its messages go to General, never to the group fallback. |
| Invalid config | Startup fails, naming the field. |
| SQLite error | Logged; the process exits non-zero and systemd restarts it (recovery above). |
| A member's computer is offline | Their tasks stay queued; the requester is told once; they start when it connects. A task with a worktree waits for the computer that holds it, and while that computer is busy. |
| A worker stops reporting for 60 s | The run is FAILED `INTERRUPTED`; the worktree stays on that computer and `/retry N` continues it there. |

Nothing retries silently. The only automatic retries are Telegram polling and outbox delivery, and both log every attempt.

## Secrets and sensitive state

The full threat model is in [SECURITY.md](../SECURITY.md).

- **Secrets:** only in the 0600 environment file, or in a personal instance's secrets file beside its config. That file is owner-only: mode 600 on macOS and Linux, an ACL for the current user alone on Windows. `dispatch run` refuses one that others can read. The YAML rejects unknown keys and repository URLs with embedded credentials.
- **Redaction:** a `Redactor` masks the values of the secret variables and common credential formats. `Main` installs it for every log line and stack trace; `OutboxSender` applies it to payloads before rendering, so every Telegram message is covered.
- **State on disk:** the database file (and its `-wal`/`-shm` files) is created `rw-------`, and `repos/`, `worktrees/`, `runs/` and `splits/` are created `rwx------`. Startup warns if the state directory is open to other users.
- **Chat content in logs:** a Telegram update that fails to process is logged with its id and type only, never its content.

## Observability

- logfmt lines to stdout, collected by journald. One line per transition, e.g. `event=task.transition task=42 run=42.2 from=AWAITING_APPROVAL to=EXECUTING actor=telegram:222333444`.
- The `task_event` table is the audit trail; `/history <id>` renders a task's runs (time, duration, cost, failure) and outcome, and `/status` shows what is running now.
- Per-run raw agent output and stderr live under `runs/`, and each split's under `splits/<draft>-<epoch millis>`. `sqlite3 dispatch.db` is the debugging console.

## Configuration

The target shape is below. Dispatch accepts only the keys it uses (`deploy/example.yaml`) and rejects unknown keys, so `language` arrives with the milestone that needs it. `worktrees.idleDays` is optional (7).

```yaml
team: backend
language: mn
telegram:
  groups:
    - name: backend
      chatId: -1001234567890
      members:
        - { id: 123456789, name: Bold }
        - { id: 222333444, name: Ali }
      projects: [autoland-management]
    - name: mobile
      chatId: -1009876543210
      members:
        - { id: 123456789, name: Bold }
      projects: [life]
delivery:
  authorName: Dispatch (backend)
  authorEmail: dispatch-backend@users.noreply.github.com
  ghCommand: gh                        # optional
workers:
  publicUrl: https://team.example.com  # required once a group has a chat: members' own computers reach this machine here
  port: 7880
scheduler:  { maxConcurrentRuns: 2 }
worktrees:  { idleDays: 7 }
limits:
  plan:    { timeout: 15m, budgetUsd: 2 }
  execute: { timeout: 60m, budgetUsd: 10 }
agents:
  claude-code: { command: /home/dispatch-backend/.local/bin/claude }
projects:
  - name: autoland-management
    alias: alm
    repo: https://github.com/acme/autoland-management.git
    # path: /home/bold/work/alm          # an existing clone anywhere instead of repos/<name>; repo is then optional
    baseBranch: main
    agent: claude-code
    model: sonnet                        # needs auto mode for execution
    effort: high                         # optional: low, medium, high, xhigh, max
    plan: { model: opus, effort: xhigh } # optional: planning's own model or effort; execute: { ... } likewise
    copyFiles: [.env]
    limits: { execute: { timeout: 90m, budgetUsd: 15 } }   # optional override
```

A group's `chatId` is optional: a personal bot's group has none, and its tasks stay in the requester's private chat (ADR 0014). `telegram.admins` lists who approves people asking to join (ADR 0015); group names are at most 40 characters, so they fit in a button. Changing members or projects requires a restart, which interrupts active runs. Reloading config without a restart can come later.

`workers` is required once any group has a `chatId`: each member's tasks then run on their own computer (`dispatch worker run`), reached at `publicUrl` behind the owner's tunnel or reverse proxy, on `port` (127.0.0.1 only). A personal bot needs neither setting; its jobs run in this process. **Upgrading an existing team config:** add a `workers` block (see `deploy/example.yaml`) before starting this version — Dispatch refuses to start, and `dispatch check` reports it, once a group has a chat but no `workers` block. A member sets their computer up with `dispatch worker init` and keeps it running as the `dispatch-worker` service (ADR 0021); `dispatch check` covers both sides, and the key model is in [SECURITY.md](../SECURITY.md).

## Milestones

| | Scope |
|---|---|
| **M1** read-only slice (built) | Config/env validation, SQLite + migrations, member/group checks, `/task` (text or reply), durable inbound, outbox, scheduler rules, worktree setup, planning run (plan mode, schema, timeout, budget), plan message in Mongolian with `[Reject]` (plans wait for M2's Approve), `/cancel`, `/tasks`, `/help`, group command menu, restart/orphan handling. End-to-end tests use a fake `claude` script and a fake Telegram server. |
| **M2** execution (built) | `[Approve]` (refused while questions are open), corrections by replying to a plan, execution run (auto mode, deny rules, verified permission mode, execute limits, `copyFiles`), delivery (one commit, push, draft PR), completed/failed outcomes, plan prompt that states assumptions as risks instead of asking |
| **M3a** private details and reports (built) | Task details to the requester's private chat with group fallback and one-line group outcomes, requester-only decisions, private-chat commands and menu, `/status` with live agent activity (replaces `/tasks`), `/history` and `/history <id>` |
| **M3b** groups, private tasks, priority (built) | Several groups per instance with members and projects, scoped reports, tasks given privately as drafts with project and priority buttons, priority-ordered queue with changes from `/status`, `/history` with who and when, `/stats` |
| **M3c** topics and splitting (built) | A Telegram topic per task in the requester's private chat (when @BotFather has topics on), and splitting a message into tasks on request with ✂️ |
| **M3d** personal instances (built) | `path` and `effort` per project, groups without a chat, owner-only state and secrets on every OS, `dispatch init / project add / check / run`, launchers, CI on Linux, macOS and Windows |
| **M3e** setup and team bot (built) | One-line install (`install.sh`, `install.ps1`), arrow-key `dispatch init` for a personal or team bot, joining a shared bot by admin approval in Telegram, `dispatch service` on Linux, macOS and Windows |
| **M3f** leaner agent runs (built) | A planning and a building session per task (ADR 0017), plans asked for as JSON only, the model that actually answered shown on plans and results with a warning when it isn't the configured one, model and effort per phase, a CLAUDE.md hint in `dispatch check` |
| **M3g** interaction and ops (built) | Follow-ups, `/retry`, DELIVER runs, attachments, idle sweep (with worktree recreation), `/projects`, auto-clone of missing repos |
| **M4** team workers (built) | Members see only each other's headlines (ADR 0020); the runner split into `Coordinator` and `JobRunner` behind `Worker`; a member's computer pairs with a one-time code and a worker key, and runs their tasks over `RemoteWorkers`/`WorkerApi` with 60 s leases (`dispatch worker pair`, `dispatch worker run`); `dispatch worker init` pairs a computer, maps or clones its projects and installs the `dispatch-worker` service; `dispatch check` covers both the team machine and a member's computer (ADR 0021) |
| **M5** | `CodexAgent` |

Tests throughout: unit tests for transitions and scheduler rules; end-to-end tests through `TaskService` with `FakeAgent` and a temp SQLite file; Telegram parsing tests from recorded update JSON. No network in tests.

## Derived decisions (not asked explicitly)

1. Agents run as CLI subprocesses with the prompt on stdin. There is no SDK, because there is no Java SDK and a CLI keeps Claude and Codex uniform.
2. Dispatch generates a task's planning session UUID when the task is created, and its building session UUID on the first execution run. Later runs of each phase resume that phase's session (ADR 0017).
3. Runs load only project/local Claude settings and no MCP servers, so behaviour is the same on a laptop and a server. The repo's `CLAUDE.md` still applies. Verified in M1: the recorded runs report no plugins, no MCP servers and no personal skills; only Claude Code's built-in skills remain.
4. The agent never receives `TELEGRAM_BOT_TOKEN` or `GH_TOKEN`.
5. Every bot message except live status edits goes through the outbox, including "queued" acks and "not allowed" replies.
6. Approve/Reject buttons carry the plan's run seq, so buttons on a superseded plan are refused.
7. `copyFiles` entries must be git-ignored, otherwise setup fails.
8. Only the requester approves, corrects, rejects, reprioritizes, follows up on or retries their task (ADR 0011, 0020). The requester or an admin may cancel it.
9. A task's title is the first line of its description, cut at 80 chars; no summarizing.
10. The plan field is `findings`, not `cause`, so it also fits feature tasks.
11. SQLite is accessed through one connection behind a lock.
12. An execution run's instruction is the approved plan's JSON, so the audit trail shows exactly what was approved.
13. Delivery commits skip hooks and signing, and commits the agent made anyway are folded into the one delivery commit.
14. The agent's summary is redacted before it becomes a commit message and PR description.
15. A requester's private chat is addressed by their requester reference (for Telegram, a private chat's id is the user's id), so no chat id is stored per member.
16. Reports are scoped by group membership: a group chat sees its projects, a member privately those of all their groups, and nobody sees another group's tasks.
17. A task's `chat_ref` is its project's group at the time it was given, so later config changes do not re-route its messages.
18. Buttons that change a message's content redraw it in place; the durable path (outbox) is kept for messages that report outcomes and for redraws caused by background work.
19. Splitting always uses the `claude-code` agent, whatever the projects use: a message is split before its project is chosen.
20. A part of a split message keeps the message's reference with `#<part>` appended, so references stay unique while the part's task still replies under the message that gave it.
21. `dispatch project add` inserts lines where SnakeYAML found the existing nodes instead of re-serializing the file, so comments stay in place. The edited file must validate before it atomically replaces the config.
22. `dispatch init` makes the first person who messages the bot its member only after they confirm the name at the terminal, since someone else may have found the bot first. Granting access never defaults to yes.
23. A background service runs `dispatch run --log-file` with the PATH setup ran with, since services start with a minimal PATH that would miss `claude`, `git` and `gh`.
24. The running instance edits its own config only to add an approved member; everything else in the config changes by hand and a restart.
