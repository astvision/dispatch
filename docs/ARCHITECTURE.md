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
| Permissions | Plan mode for planning; auto mode plus deny rules for execution; the OS user is the hard boundary | 0009 |
| Telegram | Offset advanced only after commit; outcome messages go through an outbox | 0010 |

Also decided without an ADR:
- Only allowlisted members act, and only in the one configured group.
- Queue is FIFO with no priority field.
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
│                                                     Sweeper    │
└───────┬─────────────────────────┬─────────────────────┬────────┘
   store (SQLite)          workspace (git, gh)     agent: Agent
                                                   ClaudeCodeAgent
```

| Package | Responsibility |
|---|---|
| `Main`, `App` | `Main` validates config and installs the shutdown hook. `App` wires everything explicitly: it migrates the DB, checks the bot token (`getMe`), runs `Recovery`, registers the group's command menu, and starts the poller, scheduler and outbox threads. A loop that dies unexpectedly is fatal. |
| `config` | YAML + env loading into records. Startup fails naming the invalid field. |
| `store` | SQLite access and migrations (`PRAGMA user_version`); conditional updates. One connection behind a lock. |
| `core` | `TaskService`: the channel-neutral commands `create / approve / reject / correct / followUp / cancel / retry / list / show`. `Scheduler` picks runs; `RunExecutor` drives one run; `Recovery` handles startup; `Sweeper` removes idle worktrees. |
| `agent` | `Agent` interface and `ClaudeCodeAgent` (CLI subprocess + stream-json parser). Tests run the real adapter against a fake `claude` shell script that replays recorded output. `CodexAgent` comes later. |
| `workspace` | Clone, fetch, worktree add/remove, `copyFiles`, commit, push, `gh pr create`. |
| `telegram` | Bot API client (`java.net.http` + Jackson), `Poller`, `UpdateHandler` (parses updates, calls `TaskService`), `OutboxSender`, status edits, `messages_mn.properties`. |

`core` never imports `telegram`. A second messenger would be a new package that calls `TaskService` and renders the same outbox rows.

## Domain model

| Table | Key columns |
|---|---|
| `task` | `id` (#42), `project`, `title` (first line, ≤ 80 chars), `description`, `phase`, `requester_ref/name`, `origin_ref` (unique, e.g. `telegram:-100123/5567`), `chat_ref`, `session_id` (UUID), `base_branch`, `base_sha`, `worktree`, `plan_json`, `failure_reason/detail`, `created_at`, `started_at`, `completed_at`; `pr_url` arrives with M2. The branch is always `dispatch/<id>` and is not stored. |
| `run` | `task_id`, `seq` (run 42.2), `kind` (`PLAN / EXECUTE / DELIVER`), `status` (`QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED`), `instruction`, `requested_by`, `pid`, `pid_start`, `queued_at`, `started_at`, `finished_at`, `exit_code`, `failure_reason` (`SETUP / AGENT / TIMEOUT / BUDGET / INTERRUPTED / DELIVERY / INTERNAL`), `error_detail`, `cost_usd`, `turns`, `output`, `denials`. The raw log path is derived: `runs/<task>/<seq>`. |
| `outbox` | `task_id`, `kind`, `reply_to_ref`, `payload`, `status` (`PENDING / SENT / FAILED`), `attempts`, `next_attempt_at`, `last_error` |
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

**Create.**
1. `/task <project|alias> <text>`, or `/task <project>` as a reply to any group message (whose text becomes the description).
2. Checks: the chat is the configured group, the sender is a member, and the project exists and is available.
3. One transaction: `task` (PLANNING) + `run` 1 (PLAN, QUEUED) + event + outbox "queued" ack + offset.

**Plan run.**
1. `git fetch origin <base>`, then `git worktree add -b dispatch/<id> worktrees/<id> origin/<base>`. Record `base_sha` and copy `copyFiles`; each must be git-ignored, otherwise setup fails so delivery can never commit it.
2. Run the agent read-only, requiring the plan JSON schema.
3. On success, store `plan_json` and move to AWAITING_APPROVAL. The outbox posts the plan:
   - `[Approve]` and `[Reject]`, where the button data carries the plan's run seq so a stale button is refused;
   - if `questions` is non-empty, no Approve button: members answer by replying, which is a correction;
   - plans over 4096 chars go as a message plus `plan-<id>.md`.

**Approve** (from any member, including the requester): EXECUTE run queued. **Correction:** PLAN run queued with the reply as instruction, resuming the session. **Reject:** REJECTED.

**Execute run.** The worktree is recreated from `origin/dispatch/<id>` if it was swept. The agent implements the plan (or follow-up) in auto mode. On exit 0, delivery:
1. If `git status` shows changes: `git add -A` and one commit. The subject is `dispatch #<id>: <title>` (or `follow-up: ...`); the body is the agent's summary; trailers are `Requested-by` / `Approved-by`; the author is the instance bot identity.
2. `git push -u origin dispatch/<id>`.
3. `gh pr create --draft` on the first delivery; later pushes update the same PR.

The task becomes COMPLETED with PR link, files changed, cost and duration. With no changes it is COMPLETED with "no changes".

**Follow-up.** A reply to a COMPLETED/FAILED task's result queues an EXECUTE run immediately in the same session and branch. Replies while a run is active are refused.

**Retry** (FAILED only) repeats just the failed step:
- `SETUP` or plan failure: new PLAN run.
- Execution failure: new EXECUTE run resuming the session, with a note about how the previous run ended.
- `DELIVERY` failure: DELIVER run (commit/push/PR only, no agent).

**Cancel.** A queued run is dropped. A running run gets SIGTERM on its process tree, then a 10 s grace period, then SIGKILL. The task becomes CANCELLED and nothing is delivered.

## Telegram boundary

- Bot API over `java.net.http` + Jackson: `getUpdates` (50 s long poll), `sendMessage`, `editMessageText`, `sendDocument`, `answerCallbackQuery`, `getFile`, `leaveChat`.
- Privacy mode stays on. The bot then receives:
  - `/cmd@thisbot` always;
  - a bare `/cmd` only if Dispatch was the last bot to post in the group (verified in Telegram's docs; groups with other bots, e.g. claude-login-bot, lose bare commands);
  - replies to its own messages;
  - button callbacks.
  
  So at startup Dispatch registers its command menu for the team group (`setMyCommands`, chat scope); picking a command from the menu reaches it, and `/help` shows the `/task@<bot>` form. `/cmd@otherbot` is ignored. Edited messages are ignored. Never make the bot a group admin: admins receive every message.
- Private chats are ignored and logged. Other groups: `leaveChat` + WARN. Non-member in the group: a short "not allowed" reply + WARN with their user ID. A group migrated to a supergroup is logged at ERROR with the new chat ID.
- Commands: `/task`, `/tasks`, `/show <id>`, `/cancel <id>`, `/retry <id>`, `/projects`, `/help`. Plan buttons: Approve / Reject. Replies: correction (to a plan) or follow-up (to a result).
- Durability (ADR 0010): the offset is stored in the same transaction as an update's effects. Every bot message except live status edits goes through the outbox, whose sender retries with backoff from 5 s to 5 min, honours `retry_after` on 429, marks a message FAILED after 24 h or immediately on 403, and logs every attempt.
- Rendering: `messages_mn.properties` (ResourceBundle), HTML parse mode with escaping. All bot messages reply to the task's `/task` message. Each run has one status message edited about every 30 s (phase, elapsed time, current tool); outcomes are new messages.
- Attachments: photos (largest size) and documents from the command and replied message go to `attachments/<task>/` and are listed in the prompt. Files over 20 MB are skipped and the skip is reported.

## Agent boundary

```java
interface Agent { RunHandle start(RunRequest request); }
record RunRequest(RunKind kind, Path workdir, String prompt, UUID sessionId, boolean resume,
                  List<Path> readOnlyDirs, BigDecimal budgetUsd, String model, Path logBase) {}
interface RunHandle {
    ProcessHandle process();                // pid + start time, stored for orphan detection
    AgentResult await();                    // outcome, session id, structured output, cost, turns, denials
    void cancel();                          // non-blocking: SIGTERM process tree, grace, SIGKILL
}
```

The timeout is enforced by `RunExecutor` (a watchdog calls `cancel()`), not by the agent. `onEvent(...)` for live status edits arrives with M3.

`ClaudeCodeAgent` passes the prompt on stdin to:

| Always | `claude -p --output-format stream-json --verbose --permission-prompts none --setting-sources project,local --strict-mcp-config --max-budget-usd <b>` + (`--session-id <uuid>` on the first run, `--resume <uuid>` afterwards) + optional `--model`, `--add-dir <attachments>` |
|---|---|
| PLAN | `--permission-mode plan --tools Read,Bash --json-schema <plan schema, compacted to one line>` |
| EXECUTE | `--permission-mode auto --disallowedTools "Bash(git commit *)" "Bash(git push *)" "Bash(gh *)"` |

- The agent's environment excludes `TELEGRAM_BOT_TOKEN` and `GH_TOKEN`; only Dispatch's own git/gh calls get the token. This is a guardrail: processes running as the same user can still read each other's environment.
- The plan schema requires the fields `understanding`, `findings`, `steps[]`, `risks[]` and `questions[]`.
- Prompt rules:
  - Write in the language of the task.
  - Never commit or push.
  - Run relevant tests.
  - End with a short summary.
  - Ask questions only when planning is impossible without answers.
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

## Background execution

- Plain threads, all virtual: Telegram poller, outbox sender, scheduler loop, hourly sweeper, and one thread per active run.
- The scheduler wakes on each commit that queues a run, and every 5 s. It claims the oldest `QUEUED` run (FIFO) when:
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
| Timeout / budget | FAILED `TIMEOUT` / `BUDGET`; reply or `/retry`. |
| Commit / push / PR fails | FAILED `DELIVERY`; `/retry` runs delivery only. |
| Project clone missing / clone fails | Project unavailable + ERROR; `/task` explains why. |
| Invalid config | Startup fails, naming the field. |
| SQLite error | Logged; the process exits non-zero and systemd restarts it (recovery above). |

Nothing retries silently. The only automatic retries are Telegram polling and outbox delivery, and both log every attempt.

## Observability

- logfmt lines to stdout, collected by journald. One line per transition, e.g. `event=task.transition task=42 run=42.2 from=AWAITING_APPROVAL to=EXECUTING actor=telegram:222333444`.
- The `task_event` table is the audit trail; `/show <id>` renders it with the runs (cost, turns, duration, failure).
- Per-run raw agent output and stderr live under `runs/`. `sqlite3 dispatch.db` is the debugging console.

## Configuration

The target shape is below. M1 accepts only the keys it uses (`deploy/example.yaml`) and rejects unknown keys, so `language`, `git`, `worktrees` and `limits.execute` arrive with the milestones that need them.

```yaml
team: backend
language: mn
telegram:
  groupChatId: -1001234567890
  members:
    - { id: 123456789, name: Bold }
    - { id: 222333444, name: Ali }
git:
  authorName: Dispatch (backend)
  authorEmail: dispatch-backend@users.noreply.github.com
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
    copyFiles: [.env]
    limits: { execute: { timeout: 90m, budgetUsd: 15 } }   # optional override
```

Changing members or projects requires a restart, which interrupts active runs. Reloading config without a restart can come later.

## Milestones

| | Scope |
|---|---|
| **M1** read-only slice (built) | Config/env validation, SQLite + migrations, member/group checks, `/task` (text or reply), durable inbound, outbox, scheduler rules, worktree setup, planning run (plan mode, schema, timeout, budget), plan message in Mongolian with `[Reject]` (plans wait for M2's Approve), `/cancel`, `/tasks`, `/help`, group command menu, restart/orphan handling. End-to-end tests use a fake `claude` script and a fake Telegram server. |
| **M2** execution | `[Approve]`, execution run (auto mode, deny rules), delivery (one commit, push, draft PR), completed/failed outcomes |
| **M3** interaction and ops | Corrections, follow-ups, `/retry`, live status edits, attachments, plan questions, idle sweep, `/show`, `/projects`, auto-clone of missing repos |
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
8. Any member may approve, including the requester. Any member may cancel or retry any task.
9. A task's title is the first line of its description, cut at 80 chars; no summarizing.
10. The plan field is `findings`, not `cause`, so it also fits feature tasks.
11. SQLite is accessed through one connection behind a lock.
