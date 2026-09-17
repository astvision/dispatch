# Durable Telegram input and an outbox for outcome messages

Dispatch advances the Telegram `getUpdates` offset only after an update's effects are committed to SQLite. Handling is idempotent: a task is unique per chat and message id, and approvals, rejections and cancellations are conditional state changes. So a crash makes Telegram re-deliver updates without anything being lost or duplicated.

Outcome messages that need the team's attention (plan ready, completed, failed, interrupted) are written to an outbox in the same transaction as the state change. One sender loop delivers them, retrying with backoff for up to 24 hours and logging every attempt. Live status edits stay best-effort.

The outbox retries are a deliberate exception to "no hidden retries": a plan or result the team never sees is a lost task in practice, and the retries are logged and bounded.
