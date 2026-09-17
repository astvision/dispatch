# Interrupted runs fail and are retried explicitly

An agent run never outlives the Dispatch process.
- On a graceful shutdown, Dispatch terminates running agents.
- After a crash, startup kills any orphaned agent process, matched by PID and process start time, and marks the run as failed because it was interrupted.

Either way the team group is told, and a member decides whether to `/retry`. A retry resumes the same agent session in the same worktree, so partial work is kept. "Recoverable after restart" therefore means no task is lost or left stuck, not that in-flight runs keep going.

We rejected two alternatives:
- Detaching agents so they survive restarts: more process-management edge cases than it is worth now.
- Automatically resuming interrupted runs: a hidden retry that can repeat side effects.

## Consequences

- Restarting or redeploying Dispatch kills active runs, so check what is running before restarting.
