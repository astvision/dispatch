# Plan approval before code changes

Every task starts with a read-only analysis run: the agent investigates and posts its plan (cause and intended changes) to the team group. It may change code only after a member approves. A member can instead reply with corrections, which produces a revised plan in the same agent session. Approval resumes that session to implement, so the analysis context is kept.

Once a task has finished, a member's reply to its result is a follow-up. It runs immediately in the same session and branch, without a new plan, because the reply is itself an explicit instruction. So every code-changing run starts from a member action: approving a plan or sending a follow-up.

We chose this over direct execution because tasks come from a shared group chat. There, a misunderstood or underspecified request should cost one cheap read-only run and one human decision, not a full implementation run on the team's repository.

## Consequences

- ADR 0011 narrows who decides: only a task's requester approves, corrects or rejects its plan, in a private chat with the bot.
- A task can wait indefinitely for a human, so the lifecycle needs a waiting-for-approval state and a policy for stale plans.
- Every task costs at least two agent runs.
- The agent integration must support a read-only mode and resuming a session.
- Replies to a task while one of its runs is active are refused, not queued.
