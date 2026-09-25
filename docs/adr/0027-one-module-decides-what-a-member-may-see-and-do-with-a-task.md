# One module decides what a member may see and do with a task

Status: accepted

What a member may see of a task and do with it was worked out separately by each place that shows or acts on tasks:
`TaskService`'s commands, the assistant's proposals (`AssistantActions`), the Mini App's routes (`TasksApi`) and its
pages (`TasksPage.tsx`, `TicketSheet.tsx`), and the Telegram edge. The copies drifted: the Mini App offers Retry on every
finished task while only a failed one can be retried, and the assistant hides a requester's own task once they leave its
group while the commands still let them act on it. PRODUCT.md (2026-09-24) asks the Mini App to act "with the same rules
as the chat buttons".

So one module decides, for a member and a task:
- **see**: the task in full, its headline only (ADR 0020), or not at all;
- **do**: approve, correct, answer a question, reject, change priority, cancel, retry, follow up — each allowed, or
  refused with the reason.

The commands run it before they act, and everything that shows a task or offers an action asks it instead of working the
rules out again. The rules themselves stay as ADR 0020 and ADR 0024 set them, with one made explicit where the code gave
two answers: **a requester's own task stays theirs** — seen in full and acted on — while they are in any group, even
after they leave its project's group. Leaving a group stops them giving new tasks for its projects, not the work they
already started. Other members' tasks are still seen only within one's own groups (ADR 0012), as headlines (ADR 0020).
The commands already worked this way; the lists, `/history N` and the assistant did not. This amends ADR 0012's "a person
never sees tasks of groups they are not in": their own tasks are the exception. A task a member cannot see is, for every
action, not found, with the one exception ADR 0020 already made: an admin may cancel a task of a group they are not in,
without seeing it.

It is its own module in `core`, built with the groups. Given a transaction, a member and a task, it reads what the rules
need itself — the plan and its answers, the latest plan run, the latest run, whether an execution ever started — so a
caller hands it nothing else and cannot hand it stale or partial facts. Its tests run on a real SQLite file, as the other
`core` tests do.

A verdict says, for each action, that it is allowed or why not, in one vocabulary of refusals: not a member, not found,
not the requester, wrong phase, stale plan, open questions, out of order, already answered, not failed, never executed.
Approving, rejecting and answering speak about the current plan and its first open question, so a tap that holds an older
plan is refused as stale. The commands keep their own result types for now, mapped from the refusal, so the channels
and the replies they send do not change; returning the refusal itself, in place of those types, would change how every
channel handles them and is left for later. The conditional updates stay the guard against races (ADR 0003): the verdict
says whether the member may act, and the update decides who acted first.

Every task the Mini App receives carries the actions its viewer may take now, straight from the verdict, and a plan its
current question; the pages show buttons from that and hold no rules of their own. The status payload carries the same
field, so Telegram's `/status` offers its priority buttons from it too. Refusals are not sent for display: a button that
may not be pressed is not offered, except the sheet's Approve, which stays visible but disabled, with its reason, while
the plan asks questions.

A plan's questions are answered **in order**: a later one is refused while an earlier one is open. Answering sends the
first open question, so answering out of order would send an earlier question to the chat a second time. Only the Mini
App and the assistant checked this; now the command does.

We rejected:
- **Leaving a group ends access to its tasks:** a task waiting for its requester would then wait for an admin to cancel
  it.
- **Deciding actions only:** seeing is the first check of every action, and the assistant's drift was one of seeing;
  about nine callers would keep working out who sees what themselves.
- **A pure rule over facts each caller loads:** easy to test without a database, but the bugs would move to wherever the
  facts are loaded.
- **Methods on `TaskService`:** its interface, already 38 methods, would grow, and access would stay tangled with the
  commands.
- **Only correcting the Mini App's Retry condition:** the next rule change would again have to be made in Java and in
  TypeScript alike.
- **Also deciding who may manage Dispatch:** access to the config (`Groups.mayManage`, the Mini App's admin pages) is a
  different concept from access to a task.

## Consequences

- The rules are tested once, as one table at the module's interface: who is asking, the task's phase, its runs and its
  questions, against what they see and each action's verdict. Commands, channels and pages keep the tests that prove they
  ask and relay the answer; the rule cases the command tests used to repeat are gone.
