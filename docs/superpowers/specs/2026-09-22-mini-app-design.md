# Dispatch management pages and the Telegram Mini App

Status: delivered, 2026-09-23. Extends `2026-09-22-web-ui-design.md`, whose milestone UI-3 this splits into UI-3a and UI-3b; both are built.

## Goal

Manage a Dispatch that is already set up from the browser (`dispatch ui`) and, when its owner turns it on, from inside Telegram as a Mini App opened from the bot: on the phone, without a terminal or an SSH tunnel.

In scope: the management pages (Overview with checks and service, Projects, People, Settings, Logs, the save flow) and a second, opt-in way to reach them from Telegram.

Also in scope: members' own task pages in the Mini App (status, history, cancel, retry), and an Advanced section in setup for everything the config supports.

Out of scope: setting Dispatch up from Telegram (the bot needs a token and a running Dispatch first, so setup stays in `dispatch init` and `dispatch ui`); approving, correcting or rejecting plans (they stay in the chat); running tunnel software.

## Decisions

| Question | Decision | Why |
|---|---|---|
| Mini App or web UI | Both: `dispatch ui` for setup and SSH, the Mini App for managing from Telegram | First setup cannot happen inside a bot that is not set up yet |
| How the phone reaches Dispatch | The owner provides a public `https://` URL (Cloudflare Tunnel, Tailscale Funnel, a reverse proxy with a domain) that forwards to a local port | Dispatch stays on 127.0.0.1 and runs no tunnel software; every tunnel works |
| Which process serves the Mini App | `dispatch run` (the bot), on its own port, only when configured | It is always up with the bot; `dispatch ui` runs only while open |
| Who may use it | Every member; admins also manage | Members follow their own tasks; admins are the people who may change the setup today |
| How they log in | Telegram's signed launch data (`initData`), checked on every request | Telegram already knows who opened it; nothing to type or store |
| What it shows | Members: their own tasks. Admins: all tasks of their groups and the management pages | Task lists and timelines read better as pages; approvals stay in the chat |
| Setup options | An Advanced section (closed by default) for per-phase model and effort, aliases, limits, concurrency, state directory, gh command | A quick setup stays quick; a careful one can set everything the config supports |

These are recorded as ADR 0019, "Managing Dispatch from a Telegram Mini App".

## Architecture

### Config

```yaml
miniApp:                                    # leave out to keep it off (the default)
  publicUrl: https://dispatch.example.com   # the owner's tunnel or reverse proxy, https only
  port: 7879                                # Dispatch listens on 127.0.0.1:<port>; the tunnel forwards here
```

Without `miniApp`, nothing listens and the bot shows no Manage button. `ConfigLoader` rejects a `publicUrl` that is not `https://` and a port outside 1–65535.

### Serving

- `dispatch run` starts a `UiServer` on `127.0.0.1:<port>` when `miniApp` is set. It serves the same bundled page as `dispatch ui` and the management routes (`ManageApi`, UI-3a), behind `TelegramAuth` instead of the one-time link.
- The setup routes (`SetupApi`) are never registered on this server.
- `UiServer` takes its authentication as a parameter: `UiAuth` (one-time link and session cookie) for `dispatch ui`, `TelegramAuth` for the Mini App.

### Opening it

- `/manage` answers a member with a Web App button pointing at `publicUrl`, and anyone else as the bot answers non-members today.
- **Not the chat menu button.** This was built as `setChatMenuButton` with a Web App button and withdrawn after the live run: Telegram's menu button is either the command list or a web app, so it silently replaced `/task`, `/status` and the rest. Each start now sets it back to `commands` (ADR 0019).

### Restart

- Saving a change answers "restart needed". Restart asks the service manager to restart Dispatch (systemd `restart`, launchd `kickstart -k`, Task Scheduler end and run). The page shows "Restarting…" and polls the Overview until it answers again (at most 60 s, then an error with the last log lines).
- When Dispatch runs in a terminal (not as a service), Restart is not offered: the page says to stop and start it in the terminal.

## Security

On every Mini App request, before anything else:

1. **Signature.** The request carries `Authorization: tma <initData>`. Dispatch computes the secret key as HMAC-SHA256 of the bot token with the key `"WebAppData"`, then HMAC-SHA256 of the data-check string (every field except `hash`, sorted by key, `key=value` joined by `\n`) with that secret, and compares it with `hash` in constant time. Mismatch: 401.
2. **Freshness.** `auth_date` is at most 1 hour old (and not in the future beyond 1 minute). Older: 401 `{"error":"expired"}`, and the page says to close and reopen the Mini App.
3. **Who.** The `user.id` in the signed data is a member of a group in the config (or an admin). Otherwise 403 "ask an admin to add you". The role is decided here, on the server, from the config: an admin is in `telegram.admins`, or is a personal bot's one member. Management routes and the all-tasks routes answer 403 to anyone who is not an admin.
4. **Host.** `Host` is `publicUrl`'s host or `127.0.0.1:<port>`. Otherwise 403.

Also:

- No cookies and no sessions on this port, so there is no CSRF; every request proves itself.
- The page reads the launch data from the URL fragment (`tgWebAppData`), which browsers never send to servers. It loads no script from telegram.org.
- The Mini App's responses keep `nosniff` and `no-referrer`. `X-Frame-Options: DENY` is replaced, on this port only, by `Content-Security-Policy: frame-ancestors https://web.telegram.org https://*.telegram.org`, because Telegram's web clients show the page in a frame.
- The bot token, the secrets file, setup and replacing the token are not reachable from the Mini App.
- `dispatch check` reports the Mini App: off, or on with its URL; a warning when `publicUrl` does not answer over HTTPS.
- SECURITY.md and ADR 0019 say plainly that turning it on puts a shell-equivalent API on the internet, protected by Telegram's signature, the one-hour limit and the admin list.

## Advanced setup (UI-3a)

`dispatch ui` setup and `dispatch init` gain an Advanced section, closed by default:

- Per project: alias; model and effort for plan and for execute separately (the config's `plan:` and `execute:` blocks), besides the existing one for both.
- Limits: timeout and budget for plan and for execute.
- Maximum concurrent runs.
- State directory and `gh` command.

`Setup.render` writes only values that differ from today's defaults, so a quick setup writes the same config as before. The same fields are on the Settings and Projects pages.

## Task pages (UI-3b, Mini App only)

Only the running bot holds the queue, so task pages are served by `dispatch run`:

- **My tasks** (every member): their own running, queued, awaiting-approval and finished tasks, with each task's timeline. Cancel and Retry use the same code and rules as `/cancel N` and `/retry N`.
- **Tasks** (admins): every task of their groups, like `/status` and `/history`; another member's task shows only its headline (ADR 0020), and Cancel is the only action on it.
- Approving, correcting and rejecting plans stay in the chat; a task awaiting approval links to its chat.

## Pages (UI-3a)

Shared by `dispatch ui` and the Mini App (admins only in the Mini App):

- **Overview:** as in UI-1, plus Restart (see above).
- **Projects:** a table; Add with the folder browser and `ProjectProbe`; edit base branch, model and effort; Remove.
- **People:** each group's members and the admins; remove a member, rename, make or unmake admin. New people still join by admin approval in the chat (ADR 0015).
- **Settings:** limits (timeout and budget per phase), maximum concurrent runs, commit author, claude command.
- **Logs:** the last lines of the service log, filtered by level and event, refreshed every 2 seconds, passed through `Redactor`.
- **Saving:** as in the web UI spec: the whole YAML is rendered and validated first, `dispatch.yaml.bak` is kept, a save is refused when the file changed on disk, and the answer says whether a restart is needed.

Inside Telegram:

- The page takes Telegram's colours from `tgWebAppThemeParams` into Ant Design's theme tokens, so it follows the app's light and dark mode.
- Below 768 px the side menu becomes a menu at the top.
- Without a config, the Mini App says to run `dispatch ui` on the machine; it never shows setup.

## Error handling

As in the web UI spec (JSON errors, messages for people). Mini App additions: 401 `expired` asks to reopen the Mini App; 403 names who may manage Dispatch; a lost connection during Restart is expected and shown as "Restarting…".

## Testing

- `TelegramAuth`: Telegram's documented example data verifies; a changed field, a forged hash, an old `auth_date`, a non-admin and a wrong `Host` are refused; a personal bot's member and a team's admin are accepted.
- The Mini App server does not answer the setup routes.
- Restart goes through the `Service`; in a terminal run it is not offered.
- The menu button: set for allowed people, not for others; `/manage` answers both.
- Frontend: reading `tgWebAppData` and theme params from the fragment; the `tma` header on every call; the expired and forbidden messages.
- End to end: Playwright tests of the pages through `dispatch ui` against `FakeTelegram` (moved here from UI-2).
- Live: from a phone, through a Cloudflare quick tunnel to a real bot, before UI-3b is marked done.

## Milestones

| Milestone | Delivers | Done when |
|---|---|---|
| UI-3a | Advanced setup (both setups); `ManageApi` and the pages: Projects, People, Settings, Logs, Restart and the save flow, in `dispatch ui`; Playwright | `InitCommandTest` passes; Playwright passes; saving keeps `.bak` and refuses a config changed on disk; a live run edits a real config |
| UI-3b (delivered) | `miniApp` config, the server in `dispatch run`, `TelegramAuth` with member and admin roles, My tasks and admin Tasks with Cancel and Retry, the Manage menu button and `/manage`, theme and phone layout, `dispatch check`, ADR 0019, docs | The auth and role tests pass; a live run opens the pages from a phone through a tunnel as an admin and as a member |
