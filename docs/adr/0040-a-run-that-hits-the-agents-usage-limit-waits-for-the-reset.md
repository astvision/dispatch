# A run that hits the agent's usage limit waits for the reset

Amends ADR 0011 (a failure is told to the requester; the group hears nothing of a limit) and ADR 0021 (team work is
claimed per computer).

When Claude Code rejects a run for its usage limit, the run ends with reason `USAGE_LIMIT`, not as an agent error. The
machine that ran it is held for Claude Code until the reset Claude named: no planning or execution run of a Claude Code
project starts on it until then, while deliveries, other agents' projects and other machines go on. The task keeps its
phase and the same run is queued again, to start by itself after the reset and continue its session, as `/retry` would.
The requester hears once, with the reset time; the group hears nothing, since the task did not end. A limit inside the
verify loop stops the loop and delivers the draft as stopped, and holds the machine too.

The task fails as any other, with the `/retry` hint, when Claude names no reset, the reset has passed, or the third
hit in a row would queue a fourth run (a runaway guard).

We chose this over:
- **Failing the task and leaving `/retry` to the member.** A weekly limit resets hours later; every queued task of
  that machine failed the same way meanwhile, and each needed its own `/retry`.
- **A warning before the limit (`allowed_warning`).** Claude Code shows it in its own terminal; a bot cannot act on
  it, and the hold makes the hit itself cheap.
- **Holding by hand ("start now" after switching accounts).** Unneeded until someone switches accounts mid-hold.

Codex and Gemini CLI report their limits as free text and are not detected; their runs fail as before.

Consequences: `AgentOutcome.LIMITED`, `AgentResult.limit`, `FailureReason.USAGE_LIMIT`, the `agent_limit` table
(migration 037; 036 widened the reason), `Runs.claimNext`, `RemoteWorkers` and `Workers.blockerOf` respect the hold,
`RunTransitions.limited` re-queues, `OutboxKind.LIMIT_REQUEUED` tells the requester, `/status` says until when. The
worker protocol is 4. Spec: `docs/superpowers/specs/2026-10-01-usage-limit-design.md`.
