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

Also decided without an ADR:
- Only members of a configured group act, for their groups' projects, in their own private chat with the bot; groups get announcements and read-only reports.
- At most `maxConcurrentRuns` runs at once, and at most one execution per project.
- Per-run timeout and budget.
- Structured plans (`--json-schema`).
- Mongolian bot texts; the agent writes in the task's language.
- Telegram attachments are passed to the agent.
- No `Channel` interface: the core API is channel-neutral.
- Worktrees are removed by an idle-TTL sweep.

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

## Components

```
               Telegram Bot API
          getUpdates │   ▲ sendMessage / editMessageText / sendDocument
┌────────────────────▼───┴──────────────────────────────────────┐
│ telegram   Poller -> UpdateHandler            OutboxSender     │
└──────────────────────┬──────────────────────────▲──────────────┘
          core commands │                          │ outbox rows
┌──────────────────────▼──────────────────────────┴──────────────┐
│ core       TaskService   Scheduler -> RunExecutor   Recovery   │
│            Splitter                                 Sweeper    │
└───────┬─────────────────────────┬─────────────────────┬────────┘
   store (SQLite)          workspace (git, gh)     agent: Agent
                                                   ClaudeCodeAgent
```

| Package | Responsibility |
|---|---|
| `Main`, `App` | `Main` validates config and installs the shutdown hook. `App` wires everything explicitly: it migrates the DB, checks the bot token (`getMe`, which also says whether the bot has topics in private chats), runs `Recovery`, fails splits a previous process left running, registers the command menus (group, private chats), and starts the poller, scheduler, outbox and draft-expiry threads. A loop that dies unexpectedly is fatal. |
| `config` | YAML + env loading into records. Startup fails naming the invalid field. |
| `store` | SQLite access and migrations (`PRAGMA user_version`); conditional updates. One connection behind a lock. |
| `core` | `TaskService`: the channel-neutral commands `draft / split / create / approve / reject / correct / cancel / status / history / timeline / stats` (later `followUp / retry`). `Scheduler` picks runs; `RunExecutor` drives one run; `Splitter` runs splits beside them; `Recovery` handles startup; `Sweeper` removes idle worktrees. |
| `agent` | `Agent` interface and `ClaudeCodeAgent` (CLI subprocess + stream-json parser). Tests run the real adapter against a fake `claude` shell script that replays recorded output. `CodexAgent` comes later. |
| `workspace` | Clone, fetch, worktree add/remove, `copyFiles`, commit, push, `gh pr create`. |
| `telegram` | Bot API client (`java.net.http` + Jackson), `Poller`, `UpdateHandler` (parses updates, calls `TaskService`), `OutboxSender`, status edits, `messages_mn.properties`. |

`core` never imports `telegram`. A second messenger would be a new package that calls `TaskService` and renders the same outbox rows.

## Domain model

| Table | Key columns |
|---|---|
| `task` | `id` (#42), `project`, `title` (first line, ≤ 80 chars), `description`, `phase`, `priority` (`URGENT / NORMAL / LOW`), `requester_ref/name`, `origin_ref` (unique, e.g. `telegram:-100123/5567`), `chat_ref`, `session_id` (UUID), `base_branch`, `base_sha`, `worktree`, `plan_json`, `failure_reason/detail`, `created_at`, `started_at`, `completed_at`, `pr_url`, `topic_ref` (the task's topic in the requester's private chat, once created). The branch is always `dispatch/<id>` and is not stored. |
| `run` | `task_id`, `seq` (run 42.2), `kind` (`PLAN / EXECUTE / DELIVER`), `status` (`QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED`), `instruction` (task text, correction, or the approved plan), `requested_by`, `requested_by_name`, `pid`, `pid_start`, `queued_at`, `started_at`, `finished_at`, `exit_code`, `failure_reason` (`SETUP / AGENT / TIMEOUT / BUDGET / INTERRUPTED / DELIVERY / INTERNAL`), `error_detail`, `cost_usd`, `turns`, `output`, `denials`. The raw log path is derived: `runs/<task>/<seq>`. |
| `outbox` | `task_id`, `kind`, `chat_ref`, `reply_to_ref`, `edit_ref` (a message this row redraws instead of sending a new one), `fallback_chat_ref/reply_to_ref` (where a refused private message goes instead), `fell_back`, `payload`, `status` (`PENDING / SENT / FAILED`), `attempts`, `next_attempt_at`, `last_error`, `sent_ref` |
| `draft` | `requester_ref/name`, `chat_ref` (private chat), `origin_ref` (unique: the message, plus `#<part>` for a part), `description`, `project` (once chosen), `status` (`OPEN / CREATED / EXPIRED / SPLIT`), `task_id`, `prompt_ref` (the prompt ✂️ was pressed on), `split_state` (`SPLITTING / PROPOSED / ONE_TOPIC / KEPT / FAILED`), `topics` (the proposed parts, JSON), `parent_id` and `part` (for a part) |
| `task_event` | `task_id`, `run_seq`, `at`, `actor`, `from_phase`, `to_phase`, `reason` (append-only audit) |
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

**Messages (ADR 0011, 0012).** Tasks are given in the member's private chat with the bot. The project's group gets a one-line announcement (who, project, priority, title). The plan, the execution notice and the full result or failure go to the requester privately, under the message that gave the task. The group gets a one-line outcome: done with the PR link or "no changes", failed with the reason, rejected, or cancelled. If Telegram refuses a private message permanently (the requester blocked the bot), the outbox re-addresses it to the group with a hint to press Start. A group message replies to a task's message only if the task was given in that group, so it never lands on an unrelated message with the same id.

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

**Execute run.** The run continues in the task's worktree; until the M3 sweep recreates swept worktrees from `origin/dispatch/<id>`, a missing worktree fails the run as `SETUP`. `copyFiles` are copied in. Each must be git-ignored, otherwise setup fails, so delivery can never commit it. The agent implements the approved plan in auto mode. On success, delivery:
1. If the agent committed anyway, its commits are folded back (`git reset --soft` to the run's start) so the run still becomes one commit and is not mistaken for "no changes".
2. `git add --all`. If anything is staged: one commit, without hooks or signing so it behaves the same on every run. The subject is `dispatch #<id>: <title>`; the body is the agent's summary, redacted; trailers are `Requested-by` / `Approved-by`; author and committer are `delivery.authorName`/`authorEmail`.
3. `git push origin dispatch/<id>:refs/heads/dispatch/<id>`, without hooks.
4. `gh pr create --draft` when the task has no PR yet; later pushes update the same PR.

The task becomes COMPLETED with PR link, files changed, cost, duration and any denied actions. With no changes it is COMPLETED with "no changes" and nothing is pushed.

**Follow-up.** A reply to a COMPLETED/FAILED task's result queues an EXECUTE run immediately in the same session and branch. Replies while a run is active are refused.

**Retry** (FAILED only) repeats just the failed step:
- `SETUP` or plan failure: new PLAN run.
- Execution failure: new EXECUTE run resuming the session, with a note about how the previous run ended.
- `DELIVERY` failure: DELIVER run (commit/push/PR only, no agent).

**Priority** (by the requester only, while the task is active): buttons under a private `/status` change it; the report is redrawn in place and the change is recorded in `task_event`.

**Cancel** (the requester or any member of the project's group, privately). A queued run is dropped. A running run gets SIGTERM on its process tree, then a 10 s grace period, then SIGKILL. The task becomes CANCELLED and nothing is delivered. The group is told; a cancel sent privately is also answered there.

**Status, history and stats** (a group chat sees its own projects, a member privately those of all their groups; another group's task is answered as not found):
- `/status`: running runs with priority, elapsed time and the agent's step count and latest action (read live from its stream), then queued runs, then plans awaiting approval. Privately, a row of priority buttons per own active task.
- `/history`: the ten most recently finished tasks with outcome, priority, who gave them and when, PR link or failure reason, and total cost.
- `/history <id>`: the task's timeline. Each run shows its time, duration and cost; corrections show their text and executions who approved them. It ends with the outcome and total cost. Times use the server's time zone (`TZ`).
- `/stats`: tasks given in the last 7 days, this month or all time. The numbers are tasks by outcome, pull requests, total and average cost, median time from task to PR, and the share of tasks that reached execution with their first plan. The views are "me" (privately), each visible group, and per person; buttons redraw the message in place.

## Telegram boundary

- Bot API over `java.net.http` + Jackson: `getUpdates` (50 s long poll), `sendMessage`, `editMessageText`, `sendDocument`, `answerCallbackQuery`, `createForumTopic`, `editForumTopic`, `getFile`, `leaveChat`.
- Privacy mode stays on. The bot then receives:
  - `/cmd@thisbot` always;
  - a bare `/cmd` only if Dispatch was the last bot to post in the group (verified in Telegram's docs; groups with other bots, e.g. claude-login-bot, lose bare commands);
  - replies to its own messages;
  - button callbacks.
  
  So at startup Dispatch registers a command menu for each group (`setMyCommands`, chat scope: `/status`, `/history`, `/stats`, `/help`); picking a command from the menu reaches it. `/cmd@otherbot` is ignored. Edited messages are ignored. Never make the bot a group admin: admins receive every message.
- Members' private chats with the bot are served: task messages, `/task`, `/start`, `/help`, `/status`, `/history`, `/stats`, `/cancel`, buttons and replies to plans, with their own command menu (`all_private_chats` scope). Private chats of non-members are ignored and logged. Groups not in the config: `leaveChat` + WARN. A group migrated to a supergroup is logged at ERROR with the new chat ID.
- Buttons: draft project / priority / ✂️ and the split proposal's split / keep whole, plan Approve / Reject, status priority, stats view / period. Replies: correction (to a plan), later follow-up (to a result). Commands later: `/retry <id>`, `/projects`.
- Durability (ADR 0010): the offset is stored in the same transaction as an update's effects. Every bot message except live status edits goes through the outbox, whose sender retries with backoff from 5 s to 5 min, honours `retry_after` on 429, falls back from a refused private chat to the group, marks other messages FAILED after 24 h or immediately on a permanent error, and logs every attempt.
- Rendering: `messages_mn.properties` (ResourceBundle), HTML parse mode with escaping; agent text is cut after escaping so a message never passes Telegram's limit. Messages redrawn in place by a button (draft prompt, status, stats) are edited directly, best effort and redacted like outbox messages. A redraw that a background step causes (a split's answer) is an outbox row with `edit_ref`, retried like any other; a retried edit that Telegram reports as "not modified" counts as sent. Live progress is `/status` on demand.
- Attachments: photos (largest size) and documents from the command and replied message go to `attachments/<task>/` and are listed in the prompt. Files over 20 MB are skipped and the skip is reported.

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

The timeout is enforced by `RunExecutor` (a watchdog calls `cancel()`), not by the agent. `RunHandle.activity()` returns the agent's step count and latest tool call, parsed from the stream as it arrives, for `/status`.

`ClaudeCodeAgent` passes the prompt on stdin to:

| Always | `claude -p --output-format stream-json --verbose --permission-prompts none --setting-sources project,local --strict-mcp-config --max-budget-usd <b>` + (`--session-id <uuid>` on the first run, `--resume <uuid>` afterwards) + optional `--model`, `--add-dir <attachments>` |
|---|---|
| PLAN | `--permission-mode plan --tools Read,Bash --json-schema <plan schema, compacted to one line>` |
| EXECUTE | `--permission-mode auto --tools Read,Edit,Write,Bash --disallowedTools "Bash(git commit *)" "Bash(git push *)" "Bash(gh *)"` |
| SPLIT | `--permission-mode plan --tools "" --json-schema <topics schema> --system-prompt <one line> --no-session-persistence --disable-slash-commands --model haiku`, no session flags, run in `splits/` |

- The agent's environment excludes `TELEGRAM_BOT_TOKEN` and `GH_TOKEN`; only Dispatch's own git/gh calls get the token. This is a guardrail: processes running as the same user can still read each other's environment.
- The init event's `permissionMode` must equal the requested mode (`plan` or `auto`). Otherwise the run is stopped at once and fails as `AGENT`: Claude Code does not refuse a mode the model lacks.
- The plan schema requires the fields `understanding`, `findings`, `steps[]`, `risks[]` and `questions[]`.
- Prompt rules:
  - Write in the language of the task.
  - Plans: ask questions only when a wrong answer would make the change wrong or harmful; otherwise assume, and list the assumption under risks.
  - Corrections: the task and the member's correction; return the complete revised plan.
  - Execution: the task and the approved plan; never commit, push or open PRs; stay focused; run quick relevant tests; end with a short plain-text summary.
- The raw stream goes to `runs/<task>/<seq>.jsonl`. The parser maps `assistant` tool-use blocks to events and the final `result` event to `AgentResult`. Field names are pinned by fixture files recorded from the installed CLI (2.1.274).
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
- **Denials are normal:** plan mode denies writes such as `mkdir ~/.claude/plans` and some compound shell commands. They are recorded in `run.denials`, not treated as failures.
- **Questions:** with the first plan prompt ("questions that must be answered before implementing"), a simple task got three questions, and such a plan cannot be approved. With the current rule, the same kind of task got none, and its assumptions were listed under risks.
- **Splitting:** with the default system prompt, a split cost about $0.02 and 5 seconds, and a $0.05 cap was exceeded on a cold run. With `--system-prompt`, no tools, no skills and no saved session, a three-topic Mongolian message cost $0.015 in 2.9 s, and a one-topic message $0.014 in 3.5 s. Shared context ("staging дээр:") was repeated in each topic, as the prompt asks. Haiku once nested its answer (`{"topics": {"topics": [...]}}`), got a schema error back, and answered correctly on the next turn, so the budget leaves room for a retry.
- **Auto mode is model-dependent:** with Haiku, `--permission-mode auto` started in `default` mode, fresh or resumed. The edit was denied ("no approval surface") and the run still ended as `success` with nothing changed. Sonnet and Opus started in `auto`. A resumed Sonnet run then edited, compiled and checked the change for $0.16. It also wrote scratch files under `/tmp`, outside the worktree, which is allowed because the OS user is the boundary.

## Background execution

- Plain threads, all virtual: Telegram poller, outbox sender, scheduler loop, draft expiry (every minute), later an hourly sweeper, one thread per active run, and one per split (at most two agents at once, outside the run queue and its limits).
- The scheduler wakes on each commit that queues a run, and every 5 s. It claims the most urgent `QUEUED` run, oldest first among equals, that may start: a run that must wait never holds back the ones behind it. A run may start when:
  - fewer than `maxConcurrentRuns` runs are active (default 2), and
  - the run is a PLAN, or its project has no active EXECUTE/DELIVER run.
- A claim is a conditional update, `QUEUED → RUNNING`.
- The sweeper removes worktrees of tasks idle for longer than `worktreeIdleDays` (7):
  - COMPLETED/FAILED tasks only if the worktree is clean and fully pushed; otherwise it keeps them and logs a WARN;
  - CANCELLED/REJECTED tasks even if dirty, logging the discarded paths;
  - never tasks that are awaiting approval or have an active run.

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
| Project clone missing / clone fails | Project unavailable + ERROR; `/task` explains why. |
| Split fails (agent error, budget, timeout, unusable answer) | Draft split FAILED + WARN with the detail; the prompt says so and offers ✂️ again. |
| Stop or crash during a split | The draft stays SPLITTING until the next start, which fails it and redraws its prompt. |
| Task topic deleted or not found | The task forgets its topic + WARN; its messages go to General, never to the group fallback. |
| Invalid config | Startup fails, naming the field. |
| SQLite error | Logged; the process exits non-zero and systemd restarts it (recovery above). |

Nothing retries silently. The only automatic retries are Telegram polling and outbox delivery, and both log every attempt.

## Secrets and sensitive state

The full threat model is in [SECURITY.md](../SECURITY.md).

- **Secrets:** only in the 0600 environment file. The YAML rejects unknown keys and repository URLs with embedded credentials.
- **Redaction:** a `Redactor` masks the values of the secret variables and common credential formats. `Main` installs it for every log line and stack trace; `OutboxSender` applies it to payloads before rendering, so every Telegram message is covered.
- **State on disk:** the database file (and its `-wal`/`-shm` files) is created `rw-------`, and `repos/`, `worktrees/`, `runs/` and `splits/` are created `rwx------`. Startup warns if the state directory is open to other users.
- **Chat content in logs:** a Telegram update that fails to process is logged with its id and type only, never its content.

## Observability

- logfmt lines to stdout, collected by journald. One line per transition, e.g. `event=task.transition task=42 run=42.2 from=AWAITING_APPROVAL to=EXECUTING actor=telegram:222333444`.
- The `task_event` table is the audit trail; `/history <id>` renders a task's runs (time, duration, cost, failure) and outcome, and `/status` shows what is running now.
- Per-run raw agent output and stderr live under `runs/`, and each split's under `splits/<draft>-<epoch millis>`. `sqlite3 dispatch.db` is the debugging console.

## Configuration

The target shape is below. Dispatch accepts only the keys it uses (`deploy/example.yaml`) and rejects unknown keys, so `language` and `worktrees` arrive with the milestones that need them.

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
    baseBranch: main
    agent: claude-code
    model: sonnet                        # needs auto mode for execution
    copyFiles: [.env]
    limits: { execute: { timeout: 90m, budgetUsd: 15 } }   # optional override
```

Changing members or projects requires a restart, which interrupts active runs. Reloading config without a restart can come later.

## Milestones

| | Scope |
|---|---|
| **M1** read-only slice (built) | Config/env validation, SQLite + migrations, member/group checks, `/task` (text or reply), durable inbound, outbox, scheduler rules, worktree setup, planning run (plan mode, schema, timeout, budget), plan message in Mongolian with `[Reject]` (plans wait for M2's Approve), `/cancel`, `/tasks`, `/help`, group command menu, restart/orphan handling. End-to-end tests use a fake `claude` script and a fake Telegram server. |
| **M2** execution (built) | `[Approve]` (refused while questions are open), corrections by replying to a plan, execution run (auto mode, deny rules, verified permission mode, execute limits, `copyFiles`), delivery (one commit, push, draft PR), completed/failed outcomes, plan prompt that states assumptions as risks instead of asking |
| **M3a** private details and reports (built) | Task details to the requester's private chat with group fallback and one-line group outcomes, requester-only decisions, private-chat commands and menu, `/status` with live agent activity (replaces `/tasks`), `/history` and `/history <id>` |
| **M3b** groups, private tasks, priority (built) | Several groups per instance with members and projects, scoped reports, tasks given privately as drafts with project and priority buttons, priority-ordered queue with changes from `/status`, `/history` with who and when, `/stats` |
| **M3c** topics and splitting (built) | A Telegram topic per task in the requester's private chat (when @BotFather has topics on), and splitting a message into tasks on request with ✂️ |
| **M3d** interaction and ops | Follow-ups, `/retry`, DELIVER runs, attachments, idle sweep (with worktree recreation), `/projects`, auto-clone of missing repos |
| **M4** | `CodexAgent` |

Tests throughout: unit tests for transitions and scheduler rules; end-to-end tests through `TaskService` with `FakeAgent` and a temp SQLite file; Telegram parsing tests from recorded update JSON. No network in tests.

## Derived decisions (not asked explicitly)

1. Agents run as CLI subprocesses with the prompt on stdin. There is no SDK, because there is no Java SDK and a CLI keeps Claude and Codex uniform.
2. Dispatch generates each task's session UUID on its first run and resumes it on every later run.
3. Runs load only project/local Claude settings and no MCP servers, so behaviour is the same on a laptop and a server. The repo's `CLAUDE.md` still applies. Verified in M1: the recorded runs report no plugins, no MCP servers and no personal skills; only Claude Code's built-in skills remain.
4. The agent never receives `TELEGRAM_BOT_TOKEN` or `GH_TOKEN`.
5. Every bot message except live status edits goes through the outbox, including "queued" acks and "not allowed" replies.
6. Approve/Reject buttons carry the plan's run seq, so buttons on a superseded plan are refused.
7. `copyFiles` entries must be git-ignored, otherwise setup fails.
8. Only the requester approves, corrects or rejects their plan (ADR 0011). Any member may cancel or retry any task.
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
