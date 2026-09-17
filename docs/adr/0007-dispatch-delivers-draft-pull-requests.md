# Dispatch delivers changes as a draft pull request

After an execution run exits successfully, Dispatch itself does the delivery:
1. Commits the run's changes as exactly one commit: the task title as subject, the agent's summary as body, and trailers naming the requester and approver. The agent is told not to commit.
2. Pushes the task branch `dispatch/<task id>`.
3. Opens a draft pull request with `gh`.

Follow-up runs push new commits to the same pull request. The agent only edits code.

We chose this over prompting the agent to commit, push and open the PR:
- Delivery behaves the same on every run.
- A delivery failure is an ordinary process error that Dispatch can report.
- The agent needs no push or GitHub permissions.

Draft status marks the PR as unreviewed agent work; nothing merges without a human.

## Consequences

- Each instance needs a GitHub identity with push and pull-request rights on its team's repositories.
- The agent is denied `git commit`, `git push` and `gh` so that delivery goes through Dispatch. This is a guardrail, not a security boundary (see ADR 0009).
- Delivery is GitHub-specific; another forge needs its own delivery step.
