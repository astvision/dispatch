# A project may have several groups, linked by its add link

Amends ADR 0012 and ADR 0023.

A team usually talks about one project in one Telegram group, and sometimes in several (a developers' group and a
testers' group for the same product), but a group is rarely about several projects. So a project may now be listed in
several of `telegram.groups`, each with its own `chatId`; each group still lists its own members and projects, and a
project is listed at most once per group. A task is announced in the group it was given in when that group is one of its
project's; a task given privately is announced in the project's first group with a chat.

Each project has an add link, `https://t.me/<bot>?startgroup=<name or alias>`, which `/projects` (privately, to whoever
may manage Dispatch) and the Mini App's project page offer. Adding the bot to a group through it links that group to
that project at once: Telegram sends `/start@<bot> <key>` into the group right after adding the bot, and Dispatch links
on that message instead of asking privately which project. A plain add still asks, as ADR 0023 describes. The add
event can arrive before the `/start`, so a prompt it already sent is closed as linked.

Linking a project that already has a group chat to another chat adds a group for the new chat, with the members of the
project's first group, and keeps the first. This replaces ruling R6, under which the project's group followed it to the
new chat and the bot left the old one. A chatless group of the project is still filled in place first.

We chose the add link because it is the one step the owner takes anyway, adding the bot, and it carries the project with
it: nothing to type in the group, no private question to answer, and no chat id to find.

We rejected:
- **A `/link@bot <project>` command in the group.** It works, but it is one more step after adding the bot, and anyone
  reading the group sees it.
- **Chat ids listed under each project in the config.** The owner does not have a group's id until the bot is in it,
  which is the problem ADR 0023 solved by linking in Telegram.
- **Keeping one group per project.** The team's own layout has several groups for one project, and forcing one would
  mean choosing which group loses its announcements.

## Consequences

- `ConfigLoader` no longer refuses a project listed in several groups; it refuses a project listed twice in one group.
- `Groups.chatOfTask(project, originRef)` replaces `chatOfProject`: the origin's chat if it is one of the project's.
- Linking never unlinks a chat now, so the bot no longer leaves an old chat after a link.
- A name or alias outside Telegram's start parameter (A-Z, a-z, 0-9, `_`, `-`, at most 64) has no add link; such a
  project is linked from the private prompt as before.
