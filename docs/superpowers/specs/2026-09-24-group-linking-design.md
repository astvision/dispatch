# Linking a Telegram group to a project

Status: approved design, 2026-09-24. Next: implementation plan (milestone G-1).

## Goal

The owner of a Dispatch adds the bot to a Telegram group, taps which project that group is for, and from then on the
group gets that project's one-line announcements and answers `/status@bot` for it. No config editing by hand, no
restart, and no worker or tunnel for someone who runs Dispatch on their own computer.

In scope: what makes a Dispatch personal or a team; linking a group to a project from Telegram; seeing and unlinking
linked groups in the Mini App; following Telegram when it changes a group's chat id.

Out of scope: giving tasks in a group (T-2), converting a personal Dispatch to a team, and any change to how a team's
tasks run (ADR 0021).

## Decisions

| Question | Decision | Why |
|---|---|---|
| What makes a Dispatch personal | No `telegram.admins` and exactly one member, across all groups | Stated in the config's own terms; today "team" is inferred from "a group has a chat", which is exactly what a personal owner now wants |
| Does a personal Dispatch with group chats need workers | No: its tasks run in this process, as before | One person on one computer; a worker talking to a bot on the same machine is ceremony |
| Does a team still need workers once a group has a chat | Yes, unchanged | ADR 0021 |
| Who may link a group | Whoever may manage Dispatch: an admin in a team, the one member in a personal Dispatch | They are who may edit the config today |
| Where linking happens | In Telegram, when the bot is added to a group; the Mini App lists and unlinks | Adding the bot is the step the owner takes anyway; Telegram gives the chat id there and nowhere else |
| Restart needed | No | The running bot replaces its groups as it does when an admin approves a join request (ADR 0015) |

Recorded as an amendment to ADR 0014: a personal bot may link a group per project for announcements.

## Personal and team

`Config.isTeam()` becomes: `telegram.admins` is not empty, or the groups hold more than one distinct member.
`Config.isPersonal()` is its negation. `ConfigLoader` requires `workers` only for a team with a group chat, so a
personal config with chats and no `workers` loads. Everything that already switches on `config.workers() != null`
(the runner, `TaskService.requiresWorker`, `dispatch check`) stays as it is: a personal config never has one.
`TelegramAuth` and the Mini App keep using `isTeam()`, now with the new meaning.

An existing team config is unaffected: it has admins (`dispatch init` writes the owner as one) or several members,
and it already has a `workers` block, which is required anyway.

## Linking in Telegram

When the bot is added to a group whose chat id is in no configured group (`my_chat_member`, joined):

- **Added by someone who may manage Dispatch:** the bot stays, and sends them privately:
  "🔗 **note** группт нэмэгдлээ. Аль төсөлтэй холбох вэ?" with a button per project (config order) and **Холбохгүй**.
  The prompt goes to the adder's private chat; if Telegram refuses it (they never pressed Start), the bot leaves.
- **Added by anyone else:** the bot leaves, as today.

A project button (`link:<chatId>:<project>`) writes the config under `ConfigFile.edit`'s lock and validates it:

1. If the project's current group has no chat and holds only this project, set that group's `chatId`.
2. Otherwise move the project: remove it from its current group's `projects`, and append a new group named after the
   chat's title (`Setup.teamName`, made unique) with this `chatId`, the same members as the project's current group,
   and `projects: [<project>]`. If a group with this `chatId` already exists (a second project for the same chat),
   append the project to it instead.

Then `groups.replace(...)`, the group gets "✅ Энэ группт **life** төслийн мэдэгдэл гарна.", and the prompt is edited to
say what was linked. **Холбохгүй** edits the prompt and leaves the group. A button pressed by someone who may not
manage Dispatch, or for a chat or project that no longer fits (already linked, project removed), answers with a short
reason and changes nothing. A failed config write is logged and reported to the presser, as for a join approval.

A chat that is linked cannot be linked again from the prompt; adding the bot to a chat that is already linked just
logs, as today.

## Mini App: Группүүд

Home › Dispatch gains **Группүүд** (admins, and the owner of a personal Dispatch). It lists each group that has a
chat: its title (the group's name in the config), its projects, and **Салгах**. Unlinking removes that group's
`chatId` (the group and its projects stay, now without announcements) and the bot leaves the chat. Adding is not in the
Mini App: it needs the bot added to the group, which only Telegram can do; the screen says so.

API: `POST /api/manage/groups/unlink {version, name}`, admin-only like the other management routes. The config view
already carries each group's `chatId`.

## Telegram changing a chat id

When a linked group's message carries `migrate_to_chat_id` (a group becoming a supergroup), the bot rewrites that
group's `chatId` to the new id and replaces its groups, instead of logging an error that asks for a hand edit.

## Errors

- Config write refused (lock timeout, validation): the prompt answers "Тохиргоог бичиж чадсангүй" and nothing changes;
  logged as `group.link_failed` with the chat and project.
- The bot was removed from the group before the button was pressed: linking still writes the config; the greeting's
  failure is logged, not fatal.
- A personal Dispatch is linked to several chats for the same project: impossible, a project belongs to one group.

## Security

- Only someone who may already edit the config (admin, or the one member of a personal Dispatch) can link or unlink.
- The group sees what a team's group sees today: one-line headlines and outcomes, and `/status@bot` for its projects.
  For a personal Dispatch that is the owner's own tasks, in a group the owner chose.
- Privacy mode stays on; the bot still receives only commands and mentions in the group.

## Testing

| Check | Where |
|---|---|
| A personal config with group chats and no `workers` loads; a team config with a chat still needs `workers` | `ConfigLoaderTest` |
| The owner adding the bot gets the prompt and the bot stays; anyone else adding it makes it leave | `UpdateHandlerTest` |
| Linking sets `chatId` in place, or moves the project into a new group, or appends to the chat's group; the file stays valid and keeps its comments | `GroupLinkerTest` |
| After linking, the next task's announcement reaches the group, with no restart | end to end, `FakeTelegram` |
| A migrated chat id is rewritten | `UpdateHandlerTest` |
| Groups list and unlink | `ManageApiTest`, Mini App tests |

## Milestone

| | Delivers | Done when |
|---|---|---|
| G-1 Group linking | Personal/team rule, the link prompt and callback, config writes, the Группүүд screen with unlink, chat-id migration, ADR 0014 amendment, README and the Mongolian guide | Adding the bot to "note" and tapping **life** makes the next life task announce in "note", on the live personal Dispatch, with no restart |
