# Assistant (A-1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In the private chat, plain messages go to a per-member Claude Code assistant session that answers about tasks and code and proposes actions the owner confirms with a tap.

**Architecture:** A new `RunKind.ASSISTANT` in `ClaudeCodeAgent` runs `claude -p --resume` in a bot home (`<stateDir>/assistant/`) with read-only tools and a JSON schema. A new core module `Assistant` owns sessions, the bot home, turns (run off the update transaction on a virtual thread, like the splitter), escalation and fallback. Proposed actions are stored and rendered as confirm buttons; a tap runs the existing `TaskService` paths. A read-only `dispatch ask` CLI gives the assistant the member's tasks.

**Tech Stack:** Java 25 (Maven wrapper), SQLite migrations in `src/main/resources/db/`, Claude Code CLI, the skill-creator skill for the taskmanager skill.

**Spec:** `docs/superpowers/specs/2026-09-25-assistant-design.md`

## Global Constraints

- Private chat only; linked groups, `/commands`, buttons and meaningful replies (plan → correction, result → follow-up, ❓ question / ✍️ prompt → answer) are unchanged. `/task …` still drafts directly.
- Assistant tools: exactly `Read,Grep,Glob,Bash` with only `Bash(dispatch ask *)` allowed; `--setting-sources project`, `--strict-mcp-config`; no Edit/Write/other Bash.
- The member identity for `dispatch ask` comes from `DISPATCH_ASK_MEMBER` set by the bot, never from the model.
- The assistant never acts: every change is a proposed action, validated with the same rules as the buttons, executed only on the member's tap.
- Model: Haiku by default; `escalate: true` or the member's "сайн бодоорой" re-runs the turn on Sonnet in the same session. No cost cap; every turn logs `assistant.turn` with model and cost.
- Session: one per member; `/new` resets; > 12 h since last use resets.
- Failure or 60 s timeout: one line, plus the message offered as a task draft (nothing lost).
- Replies in Mongolian. Telegram callback_data ≤ 64 bytes.

## Review Focus

1. A long-running turn must not hold the update transaction or block other members' updates (Task 3 runs it on a virtual thread after commit).
2. Two messages from the same member while a turn runs: serialize per member (queue or "wait" note), never two concurrent resumes of one session (Task 3).
3. A confirm button tapped after state changed (plan superseded, task finished) answers stale and does nothing (Task 4).
4. The model inventing a task number or project that isn't the member's: validation rejects it with a note, never executes (Task 4).
5. `claude` missing / non-JSON output / schema violation: treated as a failed turn → fallback draft offer (Task 3).

---

### Task 1: `dispatch ask` — read-only task view for the assistant

**Files:** Create `src/main/java/dispatch/cli/AskCommand.java`; modify `src/main/java/dispatch/cli/Cli.java` (route `ask`); test `src/test/java/dispatch/cli/AskCommandTest.java`.

**Interfaces — Produces:** CLI `dispatch ask tasks` and `dispatch ask task <N>`, reading the member from env `DISPATCH_ASK_MEMBER` (a `telegram:<id>` ref; missing → exit 2 with a message) and the config from `DISPATCH_CONFIG` or the default location (as `dispatch check` finds it). Output: compact JSON on stdout.

- [ ] **Step 1: failing tests.** With a temp state DB and config (reuse the fixtures other CLI tests use): `ask tasks` lists the member's active and recent tasks (`id, project, title, phase, priority, openQuestions, updatedAt`); another member's task in a shared project appears headline-only (no plan, no cost), as `/status` does (ADR 0020); `ask task 12` returns plan (understanding, steps, risks), questions with options and answers, runs (kind, status, failureReason), prUrl; an unknown or invisible task → exit 1 with `{"error":"not_found"}`; missing `DISPATCH_ASK_MEMBER` → exit 2.
- [ ] **Step 2: run, see them fail** (`./mvnw -q test -Dtest=AskCommandTest`).
- [ ] **Step 3: implement** by reusing `TaskService.statusPayload` / `historyPayload` / `timelinePayload` and the plan/answers stores (no new SQL where a payload already exists). Open the DB read-only if `Database` supports it; never migrate from this command.
- [ ] **Step 4: tests pass**; **Step 5: commit** "Add dispatch ask: a read-only view of a member's tasks".

### Task 2: The ASSISTANT run kind

**Files:** Modify `src/main/java/dispatch/domain/RunKind.java` (add `ASSISTANT`, never stored as a run — check every exhaustive switch and the run table's use), `src/main/java/dispatch/agent/RunRequest.java` (add `Map<String,String> environment`, keeping a constructor without it so existing callers compile), `src/main/java/dispatch/agent/claude/ClaudeCodeAgent.java`; create `src/main/resources/assistant-schema.json`; test `src/test/java/dispatch/agent/claude/ClaudeCodeAgentTest.java`.

**Interfaces — Produces:** `RunKind.ASSISTANT`; `RunRequest(..., Map<String,String> environment)`; command line for ASSISTANT: `--setting-sources project`, `--strict-mcp-config`, `--tools Read,Grep,Glob,Bash`, `--allowedTools Bash(dispatch ask *)`, a permission mode that refuses anything not allowed without prompting (verify the right mode with `claude --help`; `--permission-prompts none` stays), `--json-schema <assistant-schema>`, `--resume`/`--session-id` as for tasks, `--model`, `--add-dir` per project clone. The extra environment is added after the base one (the withheld secrets stay withheld).

`assistant-schema.json`: `{reply: string, escalate?: boolean, actions: [ {type: "draft", project?, text, priority?} | {type: "answer", task, question, option? | text? | decide?} | {type:"approve"|"reject"|"cancel"|"retry", task} | {type:"followUp", task, text} ]}` with `reply` required, at most 3 actions.

- [ ] **Step 1: failing test** in `ClaudeCodeAgentTest` asserting the exact ASSISTANT arguments (via the fake `claude` recording its args, as existing tests do) and that `DISPATCH_ASK_MEMBER` reaches the process environment while `TELEGRAM_BOT_TOKEN` does not.
- [ ] **Step 2: see it fail; Step 3: implement; Step 4: pass** (`ClaudeCodeAgentTest`, full suite for the enum change); **Step 5: commit** "Run Claude Code as the member's assistant".

### Task 3: The Assistant module — sessions, bot home, turns, fallback

**Files:** Create `src/main/java/dispatch/core/Assistant.java`, `src/main/java/dispatch/store/AssistantSessions.java`, migration `src/main/resources/db/020-assistant.sql` (`assistant_session(member_ref PRIMARY KEY, session_id, model, last_used_at)`, `assistant_action(id INTEGER PRIMARY KEY, member_ref, task_id, payload, created_at, used_at)`), resources `src/main/resources/assistant/CLAUDE.md` (Mongolian persona + rules: propose only, answer through the structured output, use `dispatch ask`, read-only code questions); modify `Database` MIGRATIONS, `OutboxKind` (`ASSISTANT_REPLY`), `Renderer` (+ `RendererTest` samplePayload), `messages_mn.properties`, `App.java` (wire it); tests `src/test/java/dispatch/core/AssistantTest.java`, extend `src/test/resources/fake-claude.sh` with an assistant scenario returning fixed structured output.

**Interfaces — Consumes:** Task 2. **Produces:** `Assistant.submit(Requester who, String text, String originRef)` — returns immediately; `Assistant.reset(String memberRef)` for `/new`. Bot home `<stateDir>/assistant/` provisioned at start: `CLAUDE.md` and `.claude/skills/taskmanager/**` copied from resources when missing or changed (content hash).

Behaviour: per member, turns are serialized (a second message while one runs waits its turn in order; tell the member `assistant.busy` once if more than one is queued). A turn: send `sendChatAction typing` (add `BotApi.sendChatAction`, best effort, refreshed every 4 s while running), build the prompt = a short snapshot (what waits on the member, what runs — from the same payloads as Task 1) + the message, run Haiku (`claude-haiku-4-5`) with `--resume` if the stored session is < 12 h old else a fresh `--session-id`; if the output has `escalate: true` or the text contains "сайн бодоорой", re-run the same turn on Sonnet (`claude-sonnet-5`) with `--resume`. Store the session, log `assistant.turn` (member, model, cost, escalated). Store each valid proposed action in `assistant_action` (Task 4 validates) and enqueue `ASSISTANT_REPLY {reply, actions:[{id, label}], notes:[...]}` to the member's private chat. Failure (process error, non-JSON, schema violation, 60 s timeout): enqueue `assistant.failed` plus a normal draft of the message (`TaskService.draft`), so nothing is lost.

- [ ] **Step 1: failing tests** (fake claude): a message produces an ASSISTANT_REPLY with the reply text; the session id is reused on the next message and replaced after `/new` and after 12 h (TestClock); escalation re-runs with the Sonnet model on the same session; a failing claude produces `assistant.failed` + a DRAFT_PROMPT; two messages queued for one member run in order, never concurrently; the update handler's transaction is not held during the turn.
- [ ] **Step 2–4: fail, implement, pass**; **Step 5: commit** "Give each member an assistant session in the bot's own home".

### Task 4: Proposed actions — validation, confirm buttons, execution

**Files:** Create `src/main/java/dispatch/core/AssistantActions.java`; modify `UpdateHandler.onCallback` (prefix `as:<actionId>`), `Renderer` (confirm button labels per action type, notes), `messages_mn.properties`; test `src/test/java/dispatch/core/AssistantActionsTest.java`.

**Interfaces — Consumes:** Task 3's `assistant_action` rows. **Produces:** `AssistantActions.validate(Tx, Requester, JsonNode action) → Validated(label) | Rejected(note)`; `AssistantActions.execute(Tx, Requester, long actionId) → result code` running: draft → `TaskService.draft(tx, who, project, text, origin, List.of())` (project resolved by name or alias among the member's projects; unknown → the prompt asks); answer → the existing plan-answer path (option index / text / decide); approve/reject → `TaskService.approve/reject` with the task's latest plan seq; cancel/retry → `TaskService.cancel/retry`; followUp → `TaskService.followUp`.

Rules: only the member's own task (or, for cancel, what `/cancel` allows); answer only for a current, open question; approve only without open questions; any action on a finished/superseded state → validation note at proposal time, and at tap time answer `callback.stale`, mark the row used, do nothing. A row executes once (`used_at`). Button labels: `✅ #12: 1-р асуултад «…» гэж хариулах`, `✅ #12-ыг зөвшөөрөх`, `✅ Даалгавар үүсгэх: «…»`, cut to Telegram's limits; callback `as:<id>`.

- [ ] **Step 1: failing tests** for every action type's happy path and its refusals (other member's task, stale plan, closed question, approve with open questions, unknown task number, double tap), plus a tap after the task finished → stale, nothing run.
- [ ] **Step 2–4; Step 5: commit** "Turn the assistant's proposals into confirm buttons".

### Task 5: Routing, `/new`, typing, chat spend

**Files:** Modify `UpdateHandler` (private plain message → `assistant.submit` instead of `tasks.draft` at the current "Anything else a member writes privately is a task" branch; `/new` command; command menu gets `new`), `TaskService.statsPayload` + `Renderer` stats (a chat-spend line from `assistant.turn` costs — store per-turn cost in a small `assistant_turn(member_ref, at, model, cost_usd)` table in migration 020 if not already), `messages_mn.properties`, `Renderer.groupCommands`/private commands; tests in `UpdateHandlerTest`.

- [ ] **Step 1: failing tests:** a private plain message calls the assistant (no DRAFT_PROMPT); `/task …`, a reply to a plan (correction), a reply to a result (follow-up), a reply to a ❓ question, `/status`, and group messages behave exactly as before; `/new` resets the session and answers `assistant.new`; `/stats` shows chat spend for the member.
- [ ] **Step 2–4; Step 5: commit** "Send private messages to the assistant".

### Task 6: The taskmanager skill (skill-creator) and evals

**Files:** `src/main/resources/assistant/.claude/skills/taskmanager/SKILL.md` (+ references it needs), `src/main/resources/assistant/evals/` (skill-creator's eval set), a test that the bot home provisioning copies the skill (`AssistantTest`).

- [ ] **Step 1:** Invoke the skill-creator skill to create the **taskmanager** skill for the bot home, from the spec's "The taskmanager skill" section: classify task question / code question / new task / action / small talk; which `dispatch ask` call answers what and how to read its JSON; draft writing (short title in the user's language, project from clone names/aliases, priority only when stated, full request as description); actions only as structured `actions`, one per clear intent, never claiming anything happened, ask when task/project is ambiguous; when to set `escalate`; short Mongolian replies with `#N`, never other members' costs.
- [ ] **Step 2:** With skill-creator, write and run evals: task vs question classification (≥ 8 Mongolian/English messages), propose-not-claim, ambiguity → clarifying question, project selection by alias. Iterate until they pass; keep the eval files.
- [ ] **Step 3:** Commit "Teach the assistant its taskmanager skill".

### Task 7: Docs, ADR, guide, live check

**Files:** `docs/adr/0024-the-bot-holds-a-conversation-and-proposes.md`, `docs/ARCHITECTURE.md` (decision + A-1 milestone rows, components), `README.md` ("Use it": plain messages go to the assistant; `/new`; `/task` still drafts directly; confirm buttons), `SECURITY.md` (assistant's tools, identity, read access to clones), `CONTEXT.md` (Assistant, Proposed action) and the Mongolian guide artifact https://claude.ai/artifact/EDeGqVh4EcYdHsPgYdAtSL (controller).

- [ ] **Step 1:** ADR + docs; commit "Record ADR 0024 and document the assistant".
- [ ] **Step 2:** Full `./mvnw -Pui verify`.
- [ ] **Step 3 (controller, live):** install only when no run is RUNNING or QUEUED; then in the private chat: "юу хийгдэж байна?" → answered from real tasks; "life-д дасгалын тэмдэглэл нэм" → a proposed draft whose tap opens the draft prompt; "#N-ийн эхний сонголтоор хариулаад зөвшөөр" → two proposed actions that taps carry out.
