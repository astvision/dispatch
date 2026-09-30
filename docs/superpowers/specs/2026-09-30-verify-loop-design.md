# Verify loop (VL): test and review before the draft PR

Status: approved design, 2026-09-30. Builds on the agent sandbox (branch `agent-sandbox`, ADR 0032).

## Goal

An execution run no longer ends when the agent says it is done. Dispatch runs the project's tests itself, hands
failures back to the agent to fix, has a fresh reviewer check the change against the approved plan, and only then
delivers the draft pull request, with what it verified written on it. This is the Sandcastle-style "work until it
passes" loop, built into Dispatch in Java, on by default, and turned off in the config.

In scope: a `loop` setting (instance and project); a per-project `test` command; the loop inside the execution run
(test → fix rounds → review → fix → re-test); a reviewer run kind for Claude Code, Codex and Gemini CLI; a
Verification summary in the PR body and the Telegram result; ADR 0033.

Out of scope:
- **The real Sandcastle tool** (TypeScript, Docker). The toggle switches Dispatch's own loop on and off.
- **Tests and review as their own queued runs or task phases.** The loop runs inside one execution run.
- **Configurable round counts.** Fixed at 3 test-fix rounds and 1 review round until a need shows.
- **The agent discovering the test command.** A project without `test:` skips the test step.
- **Blocking delivery.** The draft PR is always opened; a red result is marked, never withheld.
- **The Mini App and `dispatch ui`** showing the Verification block (Telegram and the PR body only).

## Success criteria

1. With the loop on and `test:` set, an execution whose first attempt breaks a test ends, within the limits, with the
   test passing and the PR saying `✅ tests pass (round N)`.
2. When tests still fail after 3 fix rounds, a draft PR is opened anyway and says `❌ tests failing` with the tail
   of the output, in the PR body and in Telegram.
3. A reviewer's blocking finding is fixed by the building session and re-tested before delivery; findings left over
   are listed on the PR.
4. `loop: off` (instance, or project) gives exactly today's execution: implement, deliver.
5. The whole loop stays inside the project's `limits.execute` timeout and budget: it stops early and delivers with
   the reason rather than being cancelled by the watchdog.
6. The test command runs in the same sandbox as agents (ADR 0032).

## Configuration

```yaml
loop: on                     # instance default; off = implement, then deliver, as before
projects:
  - name: alm
    test: "./mvnw -q test"   # run by Dispatch in the worktree; without it the test step is skipped
    loop: off                # optional per-project override of the instance default
```

- `loop`: `on` or `off`; missing means `on`; anything else is refused at config load. A project's `loop` overrides
  the instance's.
- `test`: a shell command line, run with `sh -c` (Windows: `cmd /c`) in the worktree. Missing means no test step.
- Both travel to team workers in `Job.Project`. The Mini App's project form and `dispatch project` commands are not
  changed in this version; the settings are edited in the YAML.

## The loop

Inside `JobRunner.implement`, after the agent's implementation run succeeded and before delivery, when the loop is
on for the project:

1. **Test** (only when `test` is set). `TestCommand` runs it in the worktree inside this machine's sandbox, with a
   10-minute limit, writing the output to `runs/<task>/<seq>.test-<n>.log`. Exit code 0 is green.
2. **Fix** (red, and fix rounds left; 3 in total). Resume the task's building session with the failing output's tail
   (up to 4 KB) and the instruction to fix the cause without committing. Then test again.
3. **Review** (1 round). A fresh reviewer session of the project's agent, read-only like a planning run, gets the
   approved plan and the diff from the run's start commit to the worktree (computed by Dispatch, capped at 60 KB, with
   the cap noted). It answers with the review schema:
   `{ "verdict": "ok" | "changes", "findings": [ { "severity": "blocking" | "minor", "file", "line", "text" } ] }`.
4. **Fix after review** (blocking findings). Resume the building session with the blocking findings; then test again,
   within the fix rounds left.
5. **Deliver.** Delivery is unchanged apart from the Verification block in the PR body; the draft PR is always opened.

A follow-up's execution goes through the same loop. A retry of a failed execution does too. A `DELIVER` run (delivery
again, no agent) does not.

## Limits

- **Budget.** `limits.execute.budgetUsd` is the budget of the whole execution run. Each agent call (implement, fixes,
  review) gets what is left of it. When less than 5% is left, the loop stops and delivers, noting "stopped: budget".
- **Time.** `limits.execute.timeout` is the whole run's. Before each step the loop checks the time left; when it is
  under the step's need (a test step: its 10-minute limit plus 2 minutes), it stops and delivers, noting
  "stopped: time". The watchdog stays as the last resort.
- The run's reported cost is the sum of its agent calls.

## Components

- `dispatch.core.VerifyLoop`: runs the steps above and returns a `Verification`. It depends on the agent, a
  `TestCommand`, the clock, and the run's control (for cancel).
- `dispatch.core.TestCommand`: runs one command line in a directory through `Confinement.wrap` (no agent state),
  with a timeout that terminates the whole process tree, and returns exit code, duration and the output's tail.
- `RunKind.REVIEW`: each agent starts a reviewer run like a PLAN run (read-only mode, fresh session, no resume) with
  the review schema (`src/main/resources/review-schema.json`) and a review prompt; the reviewer's model and effort are
  the project's `plan` choice.
- `record Verification(TestStatus tests, int testRounds, String testTail, ReviewStatus review,
  List<Finding> findings, String stoppedBy)`, carried in `JobResult` and so from team workers to the team machine.
- `Delivery` writes the Verification block into the PR body; `RunTransitions` adds it to the completed payload;
  `Renderer` shows it under the result in Telegram (Mongolian).

The Verification block:

```
🧪 ✅ tests pass (round 2)        | ❌ tests failing (3 rounds) + tail | ➖ no test command
🔍 ✅ review ok                   | ⚠️ 2 findings left: … | ⚠️ review failed: <reason>
⏹ stopped: budget | time          (only when the loop stopped early)
```

## Errors

- The test command cannot start or times out: that round is red, with the reason as its output.
- The reviewer fails (agent error, output not matching the schema): the review is `⚠️ review failed: <reason>` and
  delivery goes ahead. A reviewer never blocks a PR.
- A fix round's agent fails: the loop stops and delivers what the worktree holds, noting the error.
- Cancel is checked before every step and behaves as today.
- A team machine sending `test`/`loop` to a worker from before this version fails loudly (the worker refuses unknown
  fields); a worker from after it reporting `verification` to an older team machine likewise. Upgrade a team machine and
  its workers together.

## Testing

- `VerifyLoop` with a fake agent and a fake `TestCommand`: green first time; red then green; red after 3 rounds
  (delivered, marked failing); review ok; review blocking → fix → re-test; budget left under 5%; time left too short;
  reviewer failing; loop off takes today's path; no `test` skips to review.
- `TestCommand` with real commands: exit 0, exit 1, a timeout that kills a child, output tail cut at 4 KB.
- Each agent: a REVIEW run's command line (read-only mode, schema, fresh session).
- `Delivery`: the Verification block in the PR body. `Renderer`: the block in Telegram, escaped.
- Config: `loop` and `test` defaults, project override, invalid `loop` refused; `Job` round trip with `test`/`loop`.
- Live: one task on a project with `test: ./mvnw -q test` on the personal bot.

## Documentation

- ADR 0033 "An execution tests and reviews its change before delivery".
- ARCHITECTURE.md: the loop in "Flows" and "Agent boundary" (REVIEW); CONTEXT.md: **Verification**, **Review**.
- README (mn, en): `loop` and `test` in the project settings.
