# Dispatch management pages and the Telegram Mini App

Status: approved design, 2026-09-22. Extends `2026-09-22-web-ui-design.md`, whose milestone UI-3 this splits into UI-3a and UI-3b. Next: implementation plan for UI-3a.

## Goal

Manage a Dispatch that is already set up from the browser (`dispatch ui`) and, when its owner turns it on, from inside Telegram as a Mini App opened from the bot: on the phone, without a terminal or an SSH tunnel.

In scope: the management pages (Overview with checks and service, Projects, People, Settings, Logs, the save flow) and a second, opt-in way to reach them from Telegram.

Out of scope: setting Dispatch up from Telegram (the bot needs a token and a running Dispatch first, so setup stays in `dispatch init` and `dispatch ui`); tasks, plans and approvals (they stay in the chat); running tunnel software.

## Decisions

| Question | Decision | Why |
|---|---|---|
| Mini App or web UI | Both: `dispatch ui` for setup and SSH, the Mini App for managing from Telegram | First setup cannot happen inside a bot that is not set up yet |
| How the phone reaches Dispatch | The owner provides a public `https://` URL (Cloudflare Tunnel, Tailscale Funnel, a reverse proxy with a domain) that forwards to a local port | Dispatch stays on 127.0.0.1 and runs no tunnel software; every tunnel works |
| Which process serves the Mini App | `dispatch run` (the bot), on its own port, only when configured | It is always up with the bot; `dispatch ui` runs only while open |
| Who may use it | A personal bot's member; a team bot's `telegram.admins` | They are the people who may change the setup today |
| How they log in | Telegram's signed launch data (`initData`), checked on every request | Telegram already knows who opened it; nothing to type or store |
| What it shows | The management pages only | Tasks work well in the chat; the pages are what the chat lacks |

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

- At start, and whenever the admins change, the bot sets the chat menu button of each allowed person's private chat to a Web App button "Manage" pointing at `publicUrl` (`setChatMenuButton` with `chat_id`). Everyone else keeps the default menu.
- `/manage` answers an allowed person with the same Web App button, and anyone else with "only admins can manage Dispatch".

### Restart

- Saving a change answers "restart needed". Restart asks the service manager to restart Dispatch (systemd `restart`, launchd `kickstart -k`, Task Scheduler end and run). The page shows "Restarting…" and polls the Overview until it answers again (at most 60 s, then an error with the last log lines).
- When Dispatch runs in a terminal (not as a service), Restart is not offered: the page says to stop and start it in the terminal.

## Security

On every Mini App request, before anything else:

1. **Signature.** The request carries `Authorization: tma <initData>`. Dispatch computes the secret key as HMAC-SHA256 of the bot token with the key `"WebAppData"`, then HMAC-SHA256 of the data-check string (every field except `hash`, sorted by key, `key=value` joined by `\n`) with that secret, and compares it with `hash` in constant time. Mismatch: 401.
2. **Freshness.** `auth_date` is at most 1 hour old (and not in the future beyond 1 minute). Older: 401 `{"error":"expired"}`, and the page says to close and reopen the Mini App.
3. **Who.** The `user.id` in the signed data is the personal bot's member or one of `telegram.admins`. Otherwise 403.
4. **Host.** `Host` is `publicUrl`'s host or `127.0.0.1:<port>`. Otherwise 403.

Also:

- No cookies and no sessions on this port, so there is no CSRF; every request proves itself.
- The page reads the launch data from the URL fragment (`tgWebAppData`), which browsers never send to servers. It loads no script from telegram.org.
- The Mini App's responses keep `nosniff` and `no-referrer`. `X-Frame-Options: DENY` is replaced, on this port only, by `Content-Security-Policy: frame-ancestors https://web.telegram.org https://*.telegram.org`, because Telegram's web clients show the page in a frame.
- The bot token, the secrets file, setup and replacing the token are not reachable from the Mini App.
- `dispatch check` reports the Mini App: off, or on with its URL; a warning when `publicUrl` does not answer over HTTPS.
- SECURITY.md and ADR 0019 say plainly that turning it on puts a shell-equivalent API on the internet, protected by Telegram's signature, the one-hour limit and the admin list.

## Pages (UI-3a)

Shared by `dispatch ui` and the Mini App:

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
| UI-3a | `ManageApi` and the pages: Projects, People, Settings, Logs, Restart and the save flow, in `dispatch ui`; Playwright | Playwright passes; saving keeps `.bak` and refuses a config changed on disk; a live run edits a real config |
| UI-3b | `miniApp` config, the server in `dispatch run`, `TelegramAuth`, the Manage menu button and `/manage`, theme and phone layout, `dispatch check`, ADR 0019, docs | The auth tests pass; a live run opens and uses the pages from a phone through a tunnel |
