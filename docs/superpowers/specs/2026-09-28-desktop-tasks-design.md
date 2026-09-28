# Tasks on the desktop, and a live overview (D-2)

Status: approved design, 2026-09-28 (the owner's answers in brainstorming, three sections approved). To be planned and
built as D-2a, then D-2b. Follows D-1 ([the desktop in Mongolian, as a dispatcher's board](2026-09-28-desktop-ui-design.md)).

## Goal

The desktop becomes where the owner follows and acts on tasks, and sees at a glance what Dispatch is doing: a Даалгавар
page, a task's own page, giving a task, the Overview as a live dashboard, and the strip's task lamps. In Mongolian or
English, on the board.

Brief: a Mongolian-only owner at their own computer sees what is running and what waits for them, and approves, answers,
cancels or gives a task without opening Telegram. Members keep working in Telegram; the desktop is the owner's.

In scope: the pages above; a channel from `dispatch ui` to the running bot; the owner's identity on the desktop;
browser notifications; spend per day.

Out of scope: members using the desktop; anything reachable from outside the machine (a team server's desktop stays
behind `ssh -L`, ADR 0018); attachments on a desktop task; editing a task's text after it is given; an English Mini App.

## Success criteria

1. With the bot running, every task of the instance shows in full on the desktop within 5 seconds of changing, and the
   owner can approve, reject, correct and answer their own task's plan, retry their own failed task, cancel any task,
   and give a task, each taking effect at once in the bot and in Telegram.
2. With the bot stopped, the desktop still opens; the task pages and lamps say the bot is not running, and a task's log
   still reads.
3. Nothing new listens beyond 127.0.0.1, and nothing on the machine but the owner's own account can use the new channel.
4. Every new page works at 1280 and 390 pixels, in both languages, with no English word on the Mongolian page beyond
   code values, names and other programs' words (D-1's rule).

## Decisions

| Question | Decision | Why |
|---|---|---|
| Whose tasks | Every task of the instance, in full; the owner acts as their own member | The owner runs this machine and could read its database anyway; decisions keep ADR 0020: a plan and a retry are the requester's, a cancel the requester's or an admin's |
| The channel | The bot's desk port: `dispatch run` listens on 127.0.0.1 with a per-start token in an owner-only file; `dispatch ui` forwards the task calls | One place applies the task rules (`TaskService`, `TaskAccess`); actions take effect at once; the live lamps see what really runs. Rejected: reading the database and posting orders through it (a second intake loop, a second of lag, version skew); the bot serving the desktop (ADR 0018) |
| Scope | The list, a side panel, a task's page, its actions, giving a task, a task's log, the live Overview with spend per day, the strip's lamps, notifications | The owner chose all four additions |
| A task's layout | The list with a side panel; the panel's Дэлгэрэнгүй opens the task's own page (`/tasks/<id>`) | The owner's choice: quick moves between waiting tasks, and a page of its own for reading, links and notifications |
| Bot not running | The task pages and lamps say so and link to the Overview's Start; the rest of the desktop works | `dispatch ui` never touches the queue itself |
| Order | D-2a, then D-2b, each planned, reviewed and merged on its own | Each is working software alone |

## The desk port

- **In the bot.** When `dispatch run` starts, it opens `DeskServer`: D-1's `UiServer` with its own `UiServer.Auth`,
  `DeskAuth`, bound to `127.0.0.1:0` (a free port the OS picks). It then writes `<stateDir>/desk.json` atomically and
  owner-only (`OwnerOnly`): the port, a fresh 256-bit token, the bot's version and the instance's name. A clean stop
  deletes it; a crash leaves a stale one, which the next start replaces.
- **Its routes.** Every request needs `Authorization: desk <token>`, compared in constant time, and a Host of
  `127.0.0.1:<port>`; it answers only these routes, in the request's language (D-1's `Text`):
  - `TasksApi` (the Mini App's: list, detail, timeline, approve, reject, answer, cancel, retry), with an owner viewer;
  - the correction of a plan by text (`TaskService.correct`, which the Mini App lacks);
  - `POST /api/tasks/new`: a task from the desktop (below);
  - `GET /api/live`: what is running and queued, what waits on the owner and on others (ids and headlines), today's and
    this month's spend, the bot's version;
  - `POST /api/tasks/spend` `{"days": 30}`: cost per day and per project, from `run.cost_usd` by the day the run
    started, in the bot's time zone (a body, since `UiServer`'s GET routes read no query string).
- **In `dispatch ui`.** `DeskProxy` forwards `/api/tasks/*` and `/api/live` to the bot: it reads `desk.json` on every
  call (so a restarted bot's new port and token are picked up at once), forwards the method, body, `Accept-Language`
  and the owner's chosen member, and waits at most 5 seconds. A missing file, a refused connection or a timeout answers
  `503 bot_not_running`, in the page's language. The page's own session (`UiAuth`) still guards everything; the desk
  token never reaches the browser.

## Who you are

- **The owner viewer** (`TaskAccess.owner()`) sees every task in full: another member's plan, questions, costs and log,
  which ADR 0020 hides from members, not from the machine's owner.
- **Acting.** Each action runs as the owner's member, through the same `TaskService` calls as Telegram's, so ADR 0020's
  rules apply unchanged: approve, reject, correct, answer and retry only the owner's own tasks; cancel any task as an
  admin. The page shows only the buttons that apply, and the bot refuses the others anyway.
- **Which member.** A personal bot's one member. On a team, `telegram.admins`: with one admin, that one; with several,
  the desktop asks once which one you are and remembers it in the browser. The bot checks that the member it is given is
  an admin (or the personal bot's member) and refuses otherwise.
- **A task given on the desktop** is `TaskService.create` with the origin `desk:<uuid>`: the owner's private chat gets
  "#12 given from the desktop: …" and then the plan as usual, so Telegram stays in step. That origin is no Telegram
  message, so the outbox sends the task's messages without a reply target (today `Refs.messageId` refuses a reference
  that is not Telegram's).

## The pages

- **The rail:** Тойм, Даалгавар, Төслүүд, Хүмүүс, Тохиргоо, Лог.
- **Даалгавар** lists every task of the instance in groups, in this order: waiting on you (your plans to decide), running,
  queued, waiting on someone else, finished (the last 30 days: completed, failed, rejected, cancelled). Filters: project,
  person, and a search for `#14` or any words. A row shows the number, headline, project, person, state (a lamp with its
  word) and age or elapsed time; it opens the task in the side panel.
- **The side panel** (a drawer like D-1's, 520 px wide; the whole width below 640 px): the headline, project, person, priority and
  cost; the plan's steps; its open questions with the offered choices and a box for your own answer; the actions
  (Батлах, Засвар бичих, Татгалзах on your own plan; Цуцлах while it runs; Дахин оролдох after your own task failed; the pull request
  once done); and Дэлгэрэнгүй, which opens the task's page.
- **The task's page** (`/tasks/<id>`, with Back to the list): the same plan, questions and actions on the left; beside
  them the facts (branch, priority, pull request), each run's kind, duration and cost, the timeline, and the
  task's own log rows (D-1's Logs rows for `task=<id>`, read by `dispatch ui` from the log file, so they show even when
  the bot is stopped). The Logs page's task numbers link here.
- **Даалгавар өгөх** opens a side panel: the project (every project), the text and the priority (🔴 🟡 🟢). Given, the
  new task opens in the panel.
- **The strip** gains three lamps, read from `/api/live`: running (green), waiting on you (amber when above zero) and
  today's spend; and the instance's name (the config's `team`, which the overview reads, so it shows while the bot is
  stopped too) replaces the bare "Dispatch". While the bot is not running the three lamps are
  gone and the service lamp already says why. When the bot's version differs from `dispatch ui`'s, the strip says a
  restart runs the new one.
- **Тойм** (the live dashboard, as approved): four counters (running, waiting on you, today, this month); "waiting on
  you" with each item's one action; running and queued, with elapsed time and how many may run at once; spend per day
  for 30 days as bars, one colour per project, with each project's total (Codex and Gemini CLI report no cost: their
  runs are counted, not priced); then the service and the checks, one line each, opening D-1's full panels.
- **Notifications.** The Мэдэгдэл switch on Тойм asks the browser once and is remembered in it. When a task starts
  waiting on you, the browser shows "#14 таныг хүлээж байна: …"; clicking it opens `/tasks/14`.
- **Reading.** `/api/live` every 5 seconds while the page is in view; the page's own data every 5 seconds too. With
  the tab hidden nothing is read, except `/api/live` every 30 seconds while notifications are on.

## Failures

- **The bot is stopped, or crashed and left a stale `desk.json`:** `bot_not_running`; the task pages say "Бот ажиллахгүй
  байна" with a link to the Overview's Start; a task's log still reads.
- **The bot restarted:** the next call reads the new file; nothing to do.
- **An action on a stale view** (the plan was replaced, the task already started, finished or was cancelled): the bot's
  refusal, in the page's language; the panel reads the task again.
- **A save to a task the owner may not decide** (someone else's plan, by an old page): refused by the bot, as in Telegram.
- **The versions differ after an upgrade:** the strip says so; the calls keep working where the two versions agree.

## Security

- The desk port listens only on 127.0.0.1 and answers only with the token, which is 256 random bits, new at every start,
  compared in constant time, and kept in a file only the owner's account can read. Whoever can read it can already read
  the database and run `dispatch ui` as the owner.
- It sends no CORS headers and checks its Host, so a web page cannot reach it; no tunnel points at it.
- The browser never holds the token: `dispatch ui` adds it when forwarding, behind its own session (ADR 0018).
- SECURITY.md and ARCHITECTURE describe the port; an ADR records the channel.

## Testing

- **Java:** `DeskAuth` (no token, a wrong one, the right one, a foreign Host); `desk.json` owner-only, replaced at a
  restart, deleted at a clean stop; the owner viewer sees another member's plan and costs; acting as the owner's member,
  approving someone else's plan is refused and cancelling it is allowed; a non-admin member given by the desktop is
  refused; a desktop task gets its `desk:` origin and its line in the private chat; spend per day and project across a
  day boundary; the live summary; `DeskProxy` against a real desk server in the same test (forwarding, the language,
  `bot_not_running` on a stale file, the new port after a restart).
- **UI (vitest):** the list's groups; which actions each role sees; the panel and the page share their parts; the member
  choice asked only with several admins; notifications with a fake `Notification`; reading stops when hidden and keeps
  its 30-second beat with notifications on.
- **Browser (Playwright, against the `-Pui` jar):** the task pages and lamps while the bot is not running, in both
  languages and at 390 pixels. A running bot needs Telegram, so the full path is the Java end-to-end test above.

## Milestones

| | Scope |
|---|---|
| **D-2a** tasks on the desktop | The desk port, `desk.json`, `DeskAuth`, `DeskProxy`, the owner viewer and member; Даалгавар with the side panel, the task's page, its actions and log; the strip's three lamps and the instance's name; the version notice; ADR and SECURITY.md |
| **D-2b** giving a task, the live overview | Даалгавар өгөх; the live Тойм with spend per day; notifications |

## Glossary (additions)

| English | Монгол |
|---|---|
| Give a task | Даалгавар өгөх |
| Waiting on you, on someone else | Таныг хүлээж, бусдыг хүлээж |
| Running, queued, finished | Явж байна, дараалалд, дууссан |
| Approve, reject, write a correction, answer | Батлах, татгалзах, засвар бичих, хариулах |
| Cancel, retry | Цуцлах, дахин оролдох |
| Details | Дэлгэрэнгүй |
| Today, this month, spend per day | Өнөөдөр, энэ сар, өдөр бүрийн зардал |
| Notifications | Мэдэгдэл |
| The bot is not running | Бот ажиллахгүй байна |

## Risks

- A new listener inside the bot: small by design (loopback, token, task routes only), and its tests are the security
  boundary's tests.
- Polling: two small reads every 5 seconds while a page is in view; the spend query reads 30 days of runs, which stays
  small for one instance.
- Codex and Gemini CLI report no cost, so spend undercounts projects on them; the chart says so.
- The owner sees every member's task in full: true of the machine already, and now shown on a page. Members are not told.
