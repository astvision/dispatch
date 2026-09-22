# Task details go to the requester privately

Amended by ADR 0020: members see only each other's headlines; only the requester (or an admin, to cancel) acts.

Tasks are still created in the team group, and Dispatch acknowledges them there in one line. Everything that follows goes to the requester's private chat with the bot:
- the plan with its Approve and Reject buttons;
- answers to corrections (a reply to the plan there is a correction);
- the notice that execution started;
- the full result with summary, denied actions and cost.

The group gets one line per outcome: done with the pull request link, failed with the reason, rejected, or cancelled.

Only the requester can approve, reject or correct their task's plan. Any member can still cancel any task, and `/status` and `/history` show the whole team's work in either chat.

Telegram lets a bot message someone privately only after they have opened the bot and pressed Start. If a private message is refused, it goes to the group instead, with a line asking the requester to press Start.

We chose this because every plan, correction and summary for every task buried the group's own conversation, while the plan is a decision for the person who asked. The one-line outcomes, `/status` and `/history` keep the team aware without the detail.

We rejected three alternatives:
- **Group as before, plus private copies:** the noise stays.
- **Everything private:** the team loses sight of what was delivered.
- **Waiting for Start, or refusing `/task` until then:** tasks stall, or Dispatch must track who pressed Start and still needs a fallback for people who block the bot.

## Consequences

- ADR 0012 moves giving a task into the private chat as well, and extends the group's one-line announcements to newly given tasks.
- This narrows ADR 0006: approving, correcting and rejecting a plan now belong to its requester. If the requester is away, other members can only cancel the task.
- Members should press Start once before creating tasks. Until they do, their task details appear in the group.
- An outbox message can carry a fallback destination; a message that falls back is shown with the Start hint.
- The bot now reads members' private chats (commands, buttons, replies) and still ignores everyone else there. `/task` stays group-only, so the team sees every task arrive.
