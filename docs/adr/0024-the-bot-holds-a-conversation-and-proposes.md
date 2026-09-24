# The bot holds a conversation and proposes; the owner decides

Amends ADR 0012.

In a member's private chat, a plain text message no longer becomes a task draft by itself. It goes to the member's own
assistant: a Claude Code session (`claude -p`, resumed per message) in Dispatch's own directory `<stateDir>/assistant`,
with a CLAUDE.md and a `taskmanager` skill. The assistant answers questions about the member's tasks and code, and
proposes changes — a draft, an answer to a plan's question, approve, reject, cancel, retry, a follow-up — in its
structured output. Java checks each proposal against the task as it stands and shows a valid one as a confirm button; a
tap runs the same `TaskService` path the chat's own buttons run, once. Nothing changes until the member taps.

`/task`, a message with files, replies to a plan, a result or a ❓ question, commands and buttons go where they went.
The session is kept per member until `/new` or 12 hours of silence. Turns run on Haiku; the assistant can ask for a turn
to be answered again on Sonnet (`escalate`), and "сайн бодоорой" asks for Sonnet directly. A turn has no budget cap (the
owner's choice); each turn's model and cost are logged (`assistant.turn`) and `/stats` shows what the conversation cost.
A failed or timed-out turn (60 s, three times as long on Sonnet) says so in one line and offers the message as a draft.

The assistant runs with `--permission-mode dontAsk`, `--setting-sources project`, `--strict-mcp-config`, the tools
`Read,Grep,Glob,Bash,Skill`, and one allowed Bash command, `dispatch ask *`: everything else is refused without asking.
It reads the member's project clones (`--add-dir`) and, through `dispatch ask tasks` / `dispatch ask task N`, their
tasks under the ADR 0020 rules. Who is asking is not the model's to choose: each member gets their own `dispatch`
script in the home's `bin/`, first on the run's PATH, which sets the member, their projects and the state file itself
and runs only `ask`; a scope the model writes in front of the command is overwritten.

Personal instances only: a team's machine never runs Claude Code (ADR 0021), so a team bot drafts as before.

We rejected:
- **An API tool loop in Java.** It needs an API key the owner does not have (Claude Code runs on their subscription)
  and rebuilds what Claude Code already does: sessions, skills, reading files.
- **An intent classifier with templates.** It cannot hold a conversation or explain code, which is what was asked for.
- **Letting the assistant act.** Every change stays a tap, so ADR 0006's approval stays the only decision point, and
  the worst a malicious file can do is make the assistant propose something the owner declines.

## Consequences

- `RunKind.ASSISTANT`, never stored as a run; migration 020 adds `assistant_session`, `assistant_turn` and
  `assistant_action`.
- Approval needs a plan without open questions (ADR 0011): "answer and approve" becomes the answer now and the approval
  on the new plan the answers produce, and the reply says so.
- The `dispatch` script is POSIX shell: on Windows the assistant answers without task data.
- The skill's evals live in `src/test/resources/taskmanager-evals/` and run in the assistant's own harness
  (`run_evals.py`), against a stand-in `dispatch ask`.
