# A personal bot may link a group per project

Amends ADR 0014.

A personal Dispatch's owner adds the bot to a Telegram group, taps which project the group is for, and from then on
that group gets that project's one-line announcements and answers `/status@bot` for it — no config editing by hand,
no restart. `Config.isTeam()` changes from "a group has a chat" to its own terms: `telegram.admins` is not empty, or
the groups hold more than one distinct member; `isPersonal()` is its negation. A personal Dispatch with group chats
still needs no `workers` block, because its tasks still run in this process, as before (ADR 0021 is unchanged for a
team). Whoever may already manage the config — an admin in a team, the one member of a personal bot — may link or
unlink a group; linking happens only in Telegram, because that is where the bot learns the chat's id, and the Mini
App's Группүүд screen lists groups and unlinks one.

We chose Telegram as the only place to link because adding the bot to a group is the step the owner takes anyway, and
Telegram hands the chat id to the bot right there — nowhere else does. Linking is one config write, applied to the
running bot the same way an admin's join approval already is (ADR 0015), so it needs no restart.

We rejected:
- **Requiring a worker for a personal bot with a group chat.** ADR 0021 ties workers to any group with a chat because
  a team's members must not run tasks on the team machine. A personal Dispatch has one owner running it on their own
  computer: a worker that talks to a bot on the same machine is ceremony with nothing to divide.
- **Linking by editing the YAML by hand.** What this replaces. It still works, but the owner does not have the chat
  id to put there until they add the bot and read it out of a log line or an API call.
- **Linking from the Mini App.** The Mini App can only act within chats it already knows; it cannot learn the id of
  a group it has not been added to, so adding must start in Telegram regardless. Making the Mini App the place to
  finish the link, instead of a Telegram prompt, would need the owner to leave Telegram mid-task for no reason.

## Consequences

- `ConfigLoader` requires `workers` only for a team with a group chat: a personal config with chats and no `workers`
  now loads. An existing team config is unaffected — it already has admins or several members, and `workers` besides.
- Everything that already switches on `config.workers() != null` (the runner, `TaskService.requiresWorker`,
  `dispatch check`) is unchanged: a personal config never has one, linked groups or not.
- A chat that is linked cannot be linked again from the prompt, and adding the bot to an already-linked chat just
  logs, as before. Unlinking from the Mini App keeps the group and its projects, only removing the `chatId`.
- A group becoming a supergroup has its `chatId` rewritten automatically instead of logging an error that asks for a
  hand edit.
