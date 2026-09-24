# One bot for several groups; tasks are given privately

Amended: a member may also start a task from their project's linked group by mentioning the bot; the draft still opens privately (G-1b).
Amended: a member may also be mentioned in their project's linked group to receive the message as a task, drafted in their own private chat; the bot must be an admin of the group to see such messages (G-1c).

An instance now serves several Telegram groups, and a person may be a member of several of them. The config lists groups, each with its members and projects; every project belongs to exactly one group. A member can give tasks for any project of their groups.

Tasks are given in the private chat with the bot, not in a group. A plain message is enough: Dispatch asks with buttons for the project (skipped when the member has only one) and the priority. A message that waits for those answers is stored, so a restart loses nothing; unanswered, it expires after a day. The plan, corrections and result stay in the private chat, as ADR 0011 set out.

Each group gets one line when a task is given for one of its projects (who, project, priority, title) and one line per outcome. `/status`, `/history` and `/stats` work in a group for that group's projects, and in a private chat for the member's groups. A person never sees tasks of groups they are not in.

Priority (urgent, normal, low) orders the queue: the most urgent queued run starts next, oldest first among equals, and a running agent is never interrupted. The requester can change a waiting task's priority.

We chose this because giving a task in a busy group chat was noisy and awkward, and people work across several teams' projects. Priority replaces the plain first-come order, because one urgent fix should not wait behind routine work.

We rejected two alternatives:
- **Members listed per project, groups as announcement channels only:** membership repeated on every project.
- **One bot per team, as before:** a person working on several teams juggles several bots, histories and statistics.

## Consequences

- This relaxes ADR 0005 for groups that may share a server: their projects run under one OS user and GitHub token. Teams that need a hard boundary between them still run separate instances.
- It replaces ADR 0011's "tasks are created in the group". The group no longer sees the task text arrive in full, only its one-line announcement.
- A member must have pressed Start before giving a task. The group fallback for refused private messages (ADR 0011) now matters mostly for members who later block the bot.
- A busy stream of urgent tasks can keep low-priority tasks waiting indefinitely. Members see this in `/status`.
