# A task that only asks is answered by its plan run

Amends ADR 0006 (a follow-up continues a finished task in its building session): a follow-up of an answered task is
planned again instead.

A plan run returns `result: "plan"` or `result: "answer"`. With an answer, the task goes from PLANNING to COMPLETED in that
one run, without an approval, an execution or the verify loop, and the requester alone gets the answer: Telegram HTML
for the markdown that matters on a phone, or the raw markdown as a document past one message. The group hears only
that it was answered. A reply continues the planning session and comes back as another answer or as a plan that waits
for approval. An answered task is one whose stored plan is an answer.

We chose this over:
- **A classifier run before planning.** Every task would pay for an extra run, and there would be a second place to be
  wrong.
- **An `/ask` command.** The requester would have to remember it, and a group mention could not use it.
- **Keeping the approval and skipping the verify loop.** Cheapest in code, but a question would still wait for a tap.

Consequences: an answer costs at most the project's plan budget and timeout, and reads only what a plan run may. A wrong
guess costs one reply: "do it" plans a change, "just answer" turns a plan into an answer. A team machine and its workers
upgrade together, since an older team machine refuses a plan with `result` and `answer`; an older jar cannot read an
answered task.

Amended 2026-10-03: an answer that is a whole HTML document (it begins with the doctype or `<html>`) is sent as
`answer-<task>.html`, however short, so a requested report opens in a browser; the plan's shape is unchanged, and a
`format` field waits for a second file type.
