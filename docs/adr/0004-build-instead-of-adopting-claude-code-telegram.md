# Build Dispatch instead of adopting claude-code-telegram

We evaluated overwirehq/claude-code-telegram (Python, ~20k LOC, v1.7.0) and chose to build Dispatch, borrowing ideas but not code. It is a conversational bridge, not a task manager:
- Claude runs inside the Telegram update handler behind one global lock.
- The exchange is persisted only after Claude returns, and in-flight work is lost on restart.
- It edits projects in place.
- Its v2 roadmap explicitly keeps it Claude-only and Telegram-only.

Those gaps are exactly Dispatch's core requirements (persistent task lifecycle, restart recovery, worktree isolation, agent abstraction), so forking would mean rebuilding its core in a stack we don't use.

## Borrowed ideas

- Allowlist of Telegram user IDs; anyone else gets no reply.
- One live progress message showing what the agent is doing.
- Allow/Deny inline-keyboard approval for risky tool calls (later).
- A YAML project registry.
- Cost and turn counts taken from Claude's final result message.
