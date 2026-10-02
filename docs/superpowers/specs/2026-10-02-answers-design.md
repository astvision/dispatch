# Answers (AN): a task that only asks something is answered by its plan run

Status: approved design, 2026-10-02; to build on branch answers (see ADR 0038). First of five sub-projects that make
Dispatch useful beyond code: answers, then data and production questions through listed MCP servers, recurring tasks,
`/review <PR>`, and voice input. Each later one gets its own spec.

## Goal

Every task today ends as code: plan, approval, execution with Edit and Write, the verify loop, a draft pull request. A
question already half-works: an execution that changes nothing completes with "Кодод өөрчлөлт ороогүй". But the
question pays for an approval, a full execution and a verify loop, and the answer is squeezed into a summary.

After this change, a task that only asks for information (about a repository, data, a document, a production state) is
answered by its plan run, which is already the read-only investigator. Dispatch completes the task in that one run,
without an approval, and sends the answer to the requester privately. A reply continues the same planning session: it
gets another answer, or, when it asks for a change, a normal plan that waits for approval.

In scope: the plan's `result` and `answer` fields; the plan prompt; completing an answered task; the follow-up of an
answered task; the answer in Telegram (a small markdown converter, a document past the message limit), the Mini App and
the desk; ADR 0038.

Out of scope:
- **Files other than markdown** (csv, xlsx, pdf): a later slice, needing a run that may write an outbox.
- **Data and production questions as a feature**: they work through whatever MCP servers the owner lists (ADR 0036),
  as far as plan mode lets those tools run. Probing that, and anything it needs, is sub-project 2.
- **A classifier or an `/ask` command**: the plan run decides.
- **Answers in the group**: the group gets the usual short line, never the answer.

## Success criteria

1. A Claude Code plan run on this repository for "which class parses a plan?" returns `result: "answer"` and an answer
   naming `Plan.java` (live, opt-in). The task goes from PLANNING to COMPLETED with no approval and no execution run,
   and the requester gets the answer in a private message.
2. A task that asks for a change still returns `result: "plan"` and waits for approval, exactly as today.
3. A reply to an answered task starts a PLAN run that resumes the planning session. A further question is answered
   again; "now fix it" returns a plan that waits for approval.
4. A follow-up on a task that executed still starts an EXECUTE run, as today (ADR 0006).
5. An answer longer than one Telegram message arrives as a short caption and `answer-<task>.md`.
6. Fenced code, inline code, bold, headings and list items in an answer show as Telegram formatting; anything else,
   including malformed markup and any HTML, shows as escaped text, and the message's tags are always balanced.
7. A group task's group sees only the usual short completion line.
8. Plans stored before this change, and every plan that returns `result: "plan"`, behave exactly as today.

## The plan

`plan-schema.json` gains two required fields (required, because Codex's strict output mode needs every property listed
in `required`):

- `result`: `"plan"` or `"answer"`.
- `answer`: the answer in markdown when `result` is `"answer"`; `""` otherwise.

`Plan` gains `Result result()` (an enum `PLAN | ANSWER`) and `String answer()`. `Plan.parse`:

- reads an absent `result` as `PLAN`, so every stored plan is unchanged;
- with `ANSWER`, requires a non-blank `answer`, and skips the "steps or questions" rule; steps, questions, decisions and
  plugins are kept as the agent wrote them but nothing acts on them;
- with `PLAN`, keeps today's rules and ignores `answer`;
- refuses any other `result` as an invalid plan.

`toJson` writes both fields.

`Prompts.PLAN_FORMAT`, which both the plan prompt and the correction prompt end with, gains:

> If the task only asks for information (an explanation, a finding, a report, a number) and changes nothing, set result
> to "answer" and write the full answer in markdown in answer: lead with the conclusion, cite file:line for code, and
> plan nothing. If it asks for any change, set result to "plan", plan as usual and leave answer empty.

So a correction to a plan ("never mind, just tell me why") may also come back as an answer.

## A task that is answered

`RunTransitions.planSucceeded` branches on `plan.result()`. With `ANSWER`, in one transaction:

- the run finishes SUCCEEDED with the plan JSON as its output, as today;
- the task stores the plan JSON in `planJson` and goes PLANNING → COMPLETED with `completedAt` set and no pull request
  (`Tasks.answered`);
- an event "answered" is recorded, and the transition logged;
- `ANSWER_READY` is enqueued for the requester with the task id, project, the answer, and the run's details (model,
  cost, duration), as `PLAN_READY` carries them;
- a task with a group gets the group's usual `TASK_COMPLETED_SHORT` line.

No execution run starts, and the worktree the plan run made is left for the idle sweep.

## The follow-up of an answered task

An answered task is a COMPLETED task whose `buildSessionId` is null: it never executed. `TaskCommands.followUp` checks
that before today's path:

- answered: the task goes COMPLETED → PLANNING, and a PLAN run is inserted with cause `FOLLOW_UP` and the reply as its
  instruction; the requester gets `FOLLOW_UP_QUEUED` as today;
- executed: unchanged (an EXECUTE run in the building session, ADR 0006); a merged task's follow-up stays a new task.

`Coordinator.planJob` picks the prompt: no plan yet → `Prompts.plan`; the latest plan is an answer →
`Prompts.followUp(task, run)`; otherwise → `Prompts.correction`. `Prompts.followUp` says the requester replied to the
answer, quotes the reply, and asks for an answer or, if the reply asks for a change, a plan; it ends with
`PLAN_FORMAT`. The run resumes the planning session, so the agent remembers what it found.

## The answer, delivered

**Telegram.** `Renderer` draws `ANSWER_READY` as:

```
💬 #42 · alm

<the answer, converted>

<i>model · cost · duration</i>
```

If that is longer than 4096 characters, it sends a caption (`💬 #42 · alm` and one line saying the answer is attached)
and the answer, unconverted, as `answer-42.md`, through the existing `Document`.

**The converter** (`TelegramMarkdown.toHtml`, one class): it escapes the whole text first, then converts only
well-formed constructs:

- a fenced block (```` ``` ```` … ```` ``` ````, with or without a language) → `<pre>…</pre>`;
- `` `code` `` → `<code>…</code>`;
- `**bold**` → `<b>…</b>`;
- a line starting with `#`, `##` or `###` → that line in `<b>…</b>`;
- a line starting with `- ` or `* ` → `• `.

Inside a fenced block nothing else is converted. An unclosed fence, backtick or `**` stays as escaped text. Agent text
can never inject a tag, because conversion runs on escaped text and emits only the tags above.

**Mini App and desk.** `TaskService.currentPlan` adds `result` and `answer`. The ticket sheet and the desk's task view
show the answer, as text with line breaks kept, in place of steps, risks and findings when `result` is `"answer"`.
The timeline shows `💬 Хариулсан` for the "answered" event.

## Errors

- **`result: "answer"` with a blank answer, or an unknown `result`:** an invalid plan, which fails the run as today.
- **A wrong guess:** an answer to a task that wanted a change is corrected by replying "do it" (the follow-up plans);
  a plan for a task that only asked is corrected with "just answer" (the correction may answer).
- **A run past the plan limits:** it fails as a plan would; the owner raises the project's plan budget or timeout.

## Limits

- **Read-only:** the answer comes from a plan-mode run in the sandbox; it can read what the plan run can, including
  listed MCP servers' tools that plan mode allows.
- **Cost and time:** an answer costs at most the project's plan budget and timeout.
- **Privacy:** answers may hold data from the owner's databases or logs, so they go to the requester only.
- **Upgrade together:** a team machine from before this version refuses a plan with `result` and `answer` from a newer
  worker; a newer team machine's plan jobs are unchanged for older workers. A rollback to an older jar cannot read
  plans stored after the upgrade.

## Testing

- **Plan:** an answer; a blank answer; an unknown result; a stored plan without `result`; a plan's `answer` ignored;
  JSON round trip.
- **Prompts:** `PLAN_FORMAT` names both results; `Prompts.followUp` quotes the reply and ends with `PLAN_FORMAT`.
- **Answered branch:** phase COMPLETED, `completedAt`, no execution run, event "answered", `ANSWER_READY` for the
  requester only, the group's short line.
- **Follow-up routing:** an answered task → PLANNING and a PLAN run with cause `FOLLOW_UP`; an executed task → EXECUTE
  as today; `Coordinator.planJob` picks `Prompts.followUp` after an answer and `Prompts.correction` after a plan.
- **Converter:** each construct; nested and adjacent; unclosed fence, backtick and `**`; HTML in the text; balanced tags
  over random input.
- **Rendering:** the message; the document past 4096; the caption.
- **Mini App:** the payload fields; the sheet and desk show the answer (vitest).
- **Live, opt-in** (`DISPATCH_LIVE_CLAUDE=1`): a real sandboxed plan run on this repository answers "which class parses
  a plan?" with `result: "answer"` naming `Plan.java`.

## Documentation

- ADR 0038: a task that only asks something is answered by its plan run, without an approval; the options not taken (a
  classifier run, `/ask`, keeping the approval and skipping the verify loop).
- README (both languages): asking questions. CONTEXT.md: "answer" in the glossary. ARCHITECTURE: the PLANNING →
  COMPLETED transition and the follow-up routing.
