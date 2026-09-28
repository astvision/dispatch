# The desktop reaches the bot through a desk port

The desktop's task pages (`dispatch ui`, D-2) act on the running bot, not on its database. When `dispatch run` starts,
it opens a second small HTTP server inside the bot, the desk port: `DeskServer`, on `127.0.0.1` at a port the OS picks.
It then writes `<stateDir>/desk.json` — the port, a token of 256 random bits, the bot's version and the instance's name —
owner-only, as a draft moved into place, and deletes it on a clean stop. A crash leaves a stale file; its port answers
nothing, its token opens nothing, and the next start replaces it.

The desk port serves no page and sends no CORS header. It answers only the task routes — the Mini App's `TasksApi` (list,
detail, timeline, approve, reject, answer, cancel, retry), the correction of a plan by text, and `/api/live` — and only
to a request with `Authorization: desk <token>`, compared in constant time, and the Host `127.0.0.1:<port>`.
`dispatch ui` forwards `/api/tasks/*` and `/api/live` to it through `DeskProxy`, which reads `desk.json` on every call,
so a restarted bot's new port and token take effect at once. A missing file, a refused connection or a wait over
5 seconds answers `503 bot_not_running`, in the page's language. The browser never holds the token: the page's own
session (`UiAuth`, ADR 0018) guards the page, and `dispatch ui` adds the token when it forwards.

On the desk the owner sees every task of the instance in full — another member's plan, questions, cost and log — since
the owner runs the machine and can read its database anyway. Acting is unchanged: each action runs as the owner's
member, through the same `TaskService` calls as Telegram's buttons, so ADR 0020 decides it. The owner approves, rejects,
corrects, answers and retries only their own tasks, and cancels any task as an admin. That member is a personal bot's
one member, or a team's only admin; with several admins the page asks once which one you are, remembers it in the
browser and sends it with each call, and the bot refuses a member who may not manage the instance.

We rejected:
- **Reading the database and posting orders through it.** The bot would need a second intake loop polling a table,
  actions would land a second late, and the task rules would be applied in a second process, where a version skew
  between the two changes them silently.
- **The bot serving the desktop.** The desktop sets up, installs, starts and stops the bot (ADR 0018), so it has to open
  while the bot is stopped or broken.
- **Handing the browser the token.** Any script in the page could then call the bot directly; behind `dispatch ui`, its
  one session stays the only way in.

## Consequences

- The bot has a second listener. It is small by design — loopback only, task routes only, a per-start token in an
  owner-only file — and `DeskAuthTest`, `DeskServerTest` and `DeskProxyTest` are its boundary's tests.
- Whoever can read `desk.json` can act as the owner's member on tasks until the bot restarts. That is the owner's own
  account, which can already read the database and run `dispatch ui`.
- The task pages and the strip's task lamps need the bot running. While it is stopped they say so, and the rest of the
  desktop works, a task's log included (`dispatch ui` reads the log file itself).
- A desk action takes effect at once in the bot and in Telegram: it is the call a button makes.
- When the bot's version differs from `dispatch ui`'s, the strip says a restart runs the new one; the calls keep working
  where the two versions agree.
