# Agent permissions: plan mode, then auto mode

Agent runs are headless, so nobody can answer a permission prompt. Every run passes `--permission-prompts none`, which denies anything that would ask.
- **Planning runs** use `--permission-mode plan` (read-only) with `--tools Read,Bash`. A recorded run without the tool restriction spawned a subagent and called scheduling tools, which doubled its cost.
- **Execution runs** use `--permission-mode auto`: Claude's classifier allows routine work (edits, builds, tests) and denies actions it judges risky. Explicit deny rules for `git commit`, `git push` and `gh` are added on top.

Denied actions are reported with the run's result.

We rejected three alternatives:
- **Per-project tool allowlists:** constant tuning, and every unlisted build step breaks a run.
- **Bypassing permissions:** any instruction, or text injected through repository content, could run anything.
- **An OS sandbox, for now:** each project needs its network allowlist tuned. It remains the next hardening step.

## Consequences

- Classifier decisions are not deterministic; the same task can be blocked differently on two runs.
- Not every model has auto mode. Claude Code does not refuse the flag: with Haiku it starts in default mode, where every edit is denied, and still reports success with nothing changed (observed with 2.1.274). Dispatch therefore compares the permission mode in the agent's init event with the one it requested and stops the run on a mismatch. Execution needs a model with auto mode, e.g. Sonnet or Opus.
- Deny rules and the classifier are guardrails, not a security boundary. The hard boundary is the instance's OS user (ADR 0005), so that user must hold only its team's repositories and a GitHub token scoped to them.
