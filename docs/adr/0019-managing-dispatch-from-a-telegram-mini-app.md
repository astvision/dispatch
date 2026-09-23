# Managing Dispatch from a Telegram Mini App

Dispatch can serve its pages a second way: as a Telegram Mini App, opened from the bot's chat menu button or `/manage`, so a member sees their tasks and an admin manages the instance from a phone, without a terminal and without an SSH tunnel. It is off unless its owner turns it on, and it is the same pages `dispatch ui` serves — the same `UiServer`, the same management routes, with Telegram's signature in place of the one-time link.

The owner publishes one `https://` URL (`miniApp.publicUrl`) through a tunnel, a reverse proxy or a private network, and Dispatch itself listens only on `127.0.0.1:<miniApp.port>`. The bot serves it, not `dispatch ui`: only the running bot holds the queue, and it is up whenever the bot is. Every request carries Telegram's signed launch data as `Authorization: tma <initData>`, checked on each one — the signature against the bot token, an `auth_date` at most an hour old, the user against the config's groups, and the `Host`. There is no cookie and no session, so there is nothing for a cross-site request to ride on. The page itself and its assets load unsigned, because Telegram puts the launch data in the URL fragment, which a browser never sends: the script that reads it has to run first.

Who may do what is decided on the server from the config, never from anything the page sent. A member reaches their own tasks; an admin — or the single member of a personal bot, which has no admins list — also reaches every task of their groups and the management pages. Another member's task shows only its headline (ADR 0020). Approving, correcting and rejecting plans stay in the chat, where they have always been (ADR 0006); setting Dispatch up stays in `dispatch init` and `dispatch ui`, because a bot that is not configured yet cannot serve a page.

We chose this because a team's members carry phones and not terminals, and following a task or stopping one should not need an SSH tunnel to the team machine. The rejected alternatives were a public web UI with its own login (a second set of credentials to keep, and Dispatch already knows who everyone is through Telegram), and doing everything with chat commands (task lists and timelines read better as pages).

## Consequences

- **Turning it on puts a shell-equivalent API on the internet.** The management pages edit the config, restart the service and read the log; whoever reaches them can do what the owner can do in a shell. What stands between them and that is Telegram's signature, the one-hour freshness limit and the admin list. It is off by default, in a team and in personal mode alike, and nobody gets it by upgrading.
- The bot token, the secrets file, the setup routes and replacing the token are not reachable from this port at all.
- `X-Frame-Options: DENY` is replaced, on this port only, by `Content-Security-Policy: frame-ancestors https://web.telegram.org https://*.telegram.org`, because Telegram's web clients show the page in a frame. Every other response of Dispatch keeps DENY.
- A member who joined since the process started can open it at once: membership is read per request, not held from start-up.
- Launch data older than an hour is refused with its own code, and the page asks the member to close and reopen the Mini App rather than failing silently.
- The Mini App reads the config snapshot its process started with, as the worker API does; a save answers "restart needed", and Restart restarts the service.
- `dispatch check` reports the Mini App: off, or on with its port and URL, warning when the public URL does not reach this Dispatch.
- The chat menu button is set for each member at start, and only when `miniApp` is configured; everyone else keeps the default menu, so nobody who may not use it is offered it.
