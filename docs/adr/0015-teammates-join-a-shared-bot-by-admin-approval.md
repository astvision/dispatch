# Teammates join a shared bot when an admin approves them in Telegram

A team can share one bot, run by one Dispatch on one machine that holds the team's clones (ADR 0005, 0012). People join such a bot over time. The config gains `telegram.admins`: the Telegram users who decide who joins. When someone who is not a member writes to the bot privately, they are told that an admin will decide, and every admin gets their name and id with a button per group and a Deny button. Allowing adds them to that group's members in the config file. The file is edited in place and validated before it replaces the old one, as `dispatch project add` does, and the running instance applies the change without a restart. Both sides are told the decision, and it is logged. A person has at most one open request; after a Deny they can ask again a day later.

We chose this because a shared bot runs on a machine its admins are rarely logged into, and restarting to add a member interrupts every active run.

We rejected two alternatives:
- **`dispatch member add` in the terminal:** it needs access to the bot's machine. While the bot runs, it also holds the bot's only update stream, so the command could not wait for the new member to press Start.
- **Editing the YAML and restarting:** a restart interrupts every run in progress (ADR 0008).

## Consequences

- This narrows ADR 0014's "nothing is configured from Telegram". An admin's Telegram account can now grant a person what membership grants: running agents on the group's projects, on that machine. Projects, paths, agents and the admin list itself are still changed only in the config.
- Removing a member is still done by editing the config and restarting.
- A stranger can cause one message to each admin per day. Nothing else happens until an admin presses a button.
