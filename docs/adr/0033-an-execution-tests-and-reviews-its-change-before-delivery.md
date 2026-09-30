# An execution tests and reviews its change before delivery

Builds on ADR 0032: the test command runs in the same sandbox as an agent.

An execution run no longer ends when the agent says it is done. After the agent's implementation succeeds and before
delivery, Dispatch runs the verify loop inside the same run:

1. **Test.** When the project sets `test`, Dispatch runs it itself (`sh -c`, Windows `cmd /c`) in the worktree, inside the
   sandbox, with the environment agents get (`TELEGRAM_BOT_TOKEN`, `GH_TOKEN` and `DISPATCH_WORKER_KEY` withheld) and a
   10-minute limit. The output goes to `<logBase>.test-<n>.log`; the last 4096 bytes are kept.
2. **Fix.** A red run resumes the building session with that tail and asks for a fix without committing (log
   `<logBase>.fix-<n>`), then tests again.
3. **Review.** One fresh, read-only session of the project's agent (`RunKind.REVIEW`, log `<logBase>.review`) gets the task,
   the approved plan, any follow-up instruction and the diff from the run's start commit, cut at 60 000 characters with a
   note. It answers with `review-schema.json`: a verdict `ok` or `changes` and findings, each `blocking` or `minor`,
   checked by Dispatch because not every agent enforces a schema.
4. **Fix and re-test.** Blocking findings resume the building session, and the tests run again.
5. **Deliver.** The draft pull request is always opened. What the loop found is appended to the delivery commit's body,
   which is the pull request's body, and is shown in the requester's result as the Verification.

The limits are constants: `VerifyLoop.FIX_ROUNDS = 3`, shared by test fixes and review fixes, and
`VerifyLoop.REVIEW_ROUNDS = 1`. `limits.execute.budgetUsd` and `limits.execute.timeout` are the whole run's: each agent
call gets what is left of the budget, and the loop stops, noting `stopped: budget`, when less than 5% is left. A test
step needs 12 minutes left (its 10 plus 2) and an agent step 5, otherwise the loop stops, noting `stopped: time`. A fix
or review call gets the time left minus a 2-minute delivery reserve; when that runs out only that agent is cancelled, the
call counts as failed ("timed out after ..."), the loop stops and the run still delivers. Only the implementation call
keeps the run-level watchdog, whose timeout fails the run. A failed fix stops the loop (`fix failed: <reason>`), and a
reviewer that fails or answers something unusable is reported as `Review: failed (<reason>)` and never blocks delivery.

Cancel is checked before every step. A member's cancel ends the run CANCELLED, and Dispatch stopping ends it INTERRUPTED;
neither delivers, and both record the whole run's cost, loop calls included.

Configuration: the instance's `loop: on | off` (missing means `on`, anything else is refused at load) is overridden by a
project's `loop`; a project's `test` is a command line, and without one the test step is skipped and the review still
runs. `loop: off`, and a `DELIVER` run, take exactly the path before this ADR.

The reviewer runs the execution's model and effort, since a Job carries one phase's model. The Telegram result shows the
Verification in Mongolian (`verify.*` keys in `messages_mn.properties`); the commit block is English.

We chose to keep the loop inside the execution run over queueing tests and review as their own runs or task phases
(new states, a second approval question, and the worktree and branch would be handed between runs for little gain),
Dispatch running the tests over the agent testing itself (an agent can say "tests pass" without running them, and its
word is not evidence), Dispatch's own loop over the real Sandcastle tool (TypeScript and Docker, a second runtime beside
the sandbox and agents Dispatch already controls), and always delivering, marked, over blocking on red tests (a red draft
PR with the failing tail is still useful to the requester, who decides; a withheld one is lost work).

## Consequences

- An execution takes longer and costs more: up to three fix calls, one review and up to four test runs. It stays bounded
  by the run's own timeout and budget. `loop: off` restores the old cost.
- The test command runs in the sandbox, so a test that needs a hidden path (`~/.ssh`, `~/.config/gh`, another
  clone, Dispatch's state) fails there, and fails the same way on every run. Fix the test or turn the loop off for that
  project.
- The reviewer sees the plan and the diff and can be wrong either way. Its findings are advice
  the building session acts on once; what is left is listed on the pull request.
- The Verification block is in the commit body, so it also stays in git history. A later `DELIVER` retry does not add it
  again.
- A job with the loop off carries no new fields, so older workers still read it. A job with the loop on, and a result with
  a Verification, are refused by a machine from before this version (the protocol rejects unknown fields): upgrade the
  team machine and its workers together.
- Limits kept: a loop call's orphan tracking records the latest agent pid, so the test process tree is not recorded for
  orphan kill; a timed-out loop call's own partial cost is not counted.
- The Mini App and `dispatch ui` do not show the Verification, nor edit `test` and `loop`; they are set in the YAML.
