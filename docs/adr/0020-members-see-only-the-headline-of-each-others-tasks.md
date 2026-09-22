# Members see only the headline of each other's tasks

Members of a team will soon pay for and run their own tasks on their own computers (team workers spec). A task's plan, its corrections, the agent's actions and its cost belong to the person who runs it and pays for it.

Another member's task now shows only its headline — who gave it, project, title, priority, state and pull request link — in `/status`, `/history`, `/history N` and `/stats`, and in the group's lines. `/status` drops the agent's latest action and step count for a task that is not the viewer's own. `/history` drops cost from the list, and `/history N` shows only the header and outcome, with no runs and no cost. `/stats` keeps a group's counts, time to PR and first-time approval rate, but drops cost; the people view shows cost only on the viewer's own row. A group chat has no viewer, so it always sees headlines only. The viewer's own tasks are still shown in full.

Only the requester approves, corrects, rejects, reprioritizes, follows up on or retries their task. The requester or an admin cancels it; an admin may cancel any task, even one outside their own groups.

A private message Telegram refuses (a plan, a result, a failure, or the notice that execution is queued) goes to the group as a content-free notice — "🔒 #N: ... open @bot and press Start" — instead of the message itself, so the fallback no longer leaks what the requester would have read privately.

There is no mode switch: these rules apply everywhere, not only once a team runs workers. A personal bot has one member, who is always the requester of their own tasks, so nothing changes for it.

We chose this because a team's other members are about to see each other's real spend and prompts once tasks run on separate machines and accounts; keeping today's "any member sees everything" rule would let a teammate read another's plans and costs by default.

## Consequences

- Supersedes ADR 0011's "any member can still cancel any task" and its "`/status` and `/history` show the whole team's work": cancellation now needs the requester or an admin, and reports show another member's task as a headline only.
- A team lead no longer sees others' spend in Telegram; the people view of `/stats` shows cost only on their own row.
- A requester who blocked the bot loses that message's text: the group notice names the task but not its content, so they must reopen the bot and ask again (a correction or `/retry`) to see it.
