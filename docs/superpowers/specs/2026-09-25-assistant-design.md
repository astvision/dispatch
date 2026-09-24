# The bot as an assistant (A-1)

Status: approved design, 2026-09-25. Next: implementation plan (milestone A-1).

## Goal

In the private chat, the bot holds a conversation instead of turning every message into a task draft. It answers
questions about the owner's tasks and code, recognises when a message is a task, and proposes actions on tasks — which
run only when the owner taps to confirm. Its task abilities come from its own Claude Code skill, **taskmanager**, built
with the skill-creator skill.

In scope: private-chat conversation, per-chat assistant sessions, read-only access to tasks and project clones, proposed
actions with confirmation, the taskmanager skill and its evals.

Out of scope: conversation in groups (linked-group behaviour stays as it is), reading group chatter, assignees (T-2),
Mini App changes, a cost cap (the owner chose none).

## Decisions

| Question | Decision | Why |
|---|---|---|
| What it does on day one | Talk about tasks, turn chat into tasks, act on tasks by words, answer about code | All four chosen by the owner |
| Where | Private chat only | Nobody else's messages can steer it |
| How it runs | A Claude Code session per member's private chat (`claude -p --resume`) in a dedicated bot home | Runs on the owner's subscription; skills are real Claude Code skills; no API key |
| Model | Haiku by default; a turn escalates to Sonnet for code questions or on request | Cheap and fast for chat, capable when reading code |
| Cost limit | None; each turn's cost is logged and `/stats` shows chat spend | The owner's choice |
| Who acts | Only the owner, by tapping a confirm button; the assistant only proposes | Nothing changes without the owner (ADR 0006's approval stays the only decision point) |
| Rejected | Anthropic API from Java with a tool loop (needs an API key and rebuilds Claude Code); an intent classifier with templates (not a conversation, cannot explain code) | |

## Message flow

In the member's private chat, a plain message goes to the assistant unless it is a `/command`, a button press, or a
reply to a message that already has a meaning (a plan → correction, a result → follow-up, a ❓ question or its ✍️
prompt → answer). Those paths are unchanged; `/task …` still creates a draft directly.

- **Session:** `assistant_session(member_ref, session_id, model, last_used_at)`. Each message resumes the session; `/new`
  starts a fresh one; a message after 12 hours of silence starts fresh automatically.
- **Bot home:** `<stateDir>/assistant/` with `CLAUDE.md` (Mongolian persona and rules: "you propose, the owner
  decides"), `.claude/skills/taskmanager/`, and the project clones added read-only with `--add-dir`. Launched with
  `--setting-sources project`, `--strict-mcp-config`, `--tools Read,Grep,Glob,Bash`,
  `--allowedTools "Bash(dispatch ask *)"` and no permission prompts, so any other Bash command is refused — the owner's
  personal settings, hooks and MCP servers are not loaded.
- **Model:** Haiku. The structured reply may carry `escalate: true` (or the owner writes "сайн бодоорой"); the turn is
  then re-run on Sonnet in the same session.
- **Presence:** `sendChatAction typing` while the turn runs; one reply message in Mongolian, proposed actions as buttons
  under it.
- **Failure:** a failed or timed-out turn (60 s) answers in one line and offers the message as a task draft instead, so
  nothing is lost.

## What it can see

- **Tasks:** a read-only CLI, `dispatch ask tasks` (the member's tasks and states) and `dispatch ask task N` (plan,
  questions and answers, runs, failure reason, PR), scoped to the calling member with the same privacy rules as
  `/status` and `/history` (ADR 0020). The member is passed by the bot in the environment of the run
  (`DISPATCH_ASK_MEMBER`), not chosen by the model; a command that tries to set it (`VAR=… dispatch ask`) does not match
  the one allowed Bash pattern and is refused.
- **Code:** `Read`, `Grep`, `Glob` over the project clones. No `Edit`, `Write`, or other `Bash`.
- **Tools:** exactly `Read,Grep,Glob,Bash(dispatch ask *)`.
- **Snapshot:** each turn's prompt starts with a short list of what waits on the member and what is running.

## How it acts

Each turn returns structured output (`--json-schema`): `{reply, escalate?, actions[]}`, where an action is one of
`draft {project?, text, priority?}`, `answer {task, question, option | text | decide}`, `approve {task}`,
`reject {task}`, `cancel {task}`, `retry {task}`, `followUp {task, text}`.

Java validates every action against current state — the member's own task, the current plan, a still-open question, a
phase that allows it — and renders a valid one as a confirm button (e.g. "✅ #12: 1-р асуултад «…» гэж хариулах",
"✅ Даалгавар үүсгэх"); an invalid one becomes a short note under the reply. A tap runs the existing `TaskService` path
the chat buttons use. A draft opens the usual draft prompt (project, priority, ✂️). A confirm button that went stale by
the time it is tapped answers stale and does nothing.

## The taskmanager skill

Built with the skill-creator skill into the bot home's `.claude/skills/taskmanager/`. It teaches: telling a task
question, a code question, a new task, an action on a task and small talk apart; which `dispatch ask` call answers what
and how to read its output; how to write a draft (short title in the user's language, project from clone names and
aliases, priority only when stated, the full request as description); proposing actions only as structured `actions`,
one per clear intent, never claiming something happened, asking when the task or project is ambiguous; when to set
`escalate`; short Mongolian replies with `#N`, never other members' costs. skill-creator's evals cover at least:
task-vs-question classification, propose-not-claim, ambiguity → clarifying question, and project selection.

## Safety

- No write tools and one allowed Bash command; the assistant cannot change code, push, or run anything else.
- Every change needs the owner's tap, validated by the same rules as the buttons.
- Private chat only: no other person's text reaches it. Repository content it reads is data, not instructions; the worst
  a malicious file can do is make it propose an action the owner then sees and declines.
- Each turn's model and cost are logged (`assistant.turn`); `/stats` shows chat spend.

## Errors

- `claude` missing or failing: one-line apology, the message offered as a draft.
- `dispatch ask` failing inside a turn: the assistant says it could not read the data; no action proposed from missing
  data.
- Invalid structured output: treated as a failed turn.

## Testing

| Check | Where |
|---|---|
| Plain private message → assistant; `/`-commands, buttons and meaningful replies → unchanged | `UpdateHandlerTest` |
| Each proposed action validated (own task, current plan, open question, phase, stale tap) | `AssistantActionsTest` |
| End to end with a fake `claude` returning fixed structured output: reply shown, buttons rendered, tap runs the action | `AssistantTest` |
| `dispatch ask` output and member scoping (another member's task shows its headline only) | `AskCommandTest` |
| Session reuse, `/new`, 12-hour reset, escalation re-run on Sonnet | `AssistantTest` |
| The skill's behaviour | skill-creator evals |

## Milestone

| | Delivers | Done when |
|---|---|---|
| A-1 Assistant | Routing, sessions, bot home, `dispatch ask`, structured actions with confirm buttons, the taskmanager skill with evals, `/new`, chat spend in `/stats`, ADR, README and the Mongolian guide | On the live bot, "юу хийгдэж байна?" is answered from real tasks, "life-д дасгалын тэмдэглэл нэм" proposes a draft that a tap opens, and "#N-ийн эхний сонголтоор хариулаад зөвшөөр" proposes the two actions that taps carry out |
