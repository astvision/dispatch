# A local web UI served by `dispatch ui`

Setting Dispatch up and changing it later needs a terminal: `dispatch init`, `dispatch project add`, hand-edited YAML and
`dispatch service`. Now `dispatch ui` also serves a web page for it, on macOS, Windows and Linux:
- **Its own process:** `dispatch ui` runs only while it is open, apart from the background service. So it works before
  there is any config, and it can stop and restart the service.
- **Only on this machine:** it listens on 127.0.0.1. A team's bot on a server is managed through `ssh -L`.
- **A one-time link:** each start prints a link with 256 random bits. The link gives one browser a session cookie and then
  stops working. Every request is also checked for its Host (against DNS rebinding) and, when it changes something, its
  Origin.
- **React and Ant Design, built into a release jar:** CI builds the page and publishes a jar with it on each version
  tag, which the install scripts download. A build from source without Node has no web UI.

We chose this because a web page works on every OS without packaging, and the same page reaches a server through a tunnel.

We rejected three alternatives:
- **Serving the page from `dispatch run`:** it cannot help before the first setup, and restarting the service from the
  page would take the page down.
- **A desktop app (Tauri or Electron):** it needs a build and signing per OS, and still a tunnel to reach a server.
- **A password or a login through Telegram on the network:** listening on a network needs TLS and more to get right,
  while `ssh -L` already gives a server's admins an authenticated, encrypted way in.

## Consequences

- Whoever holds the link, or a session made with it, can act as the user who runs `dispatch ui`, as with a shell.
- Releases are built by CI; `install.sh` and `install.ps1` download them and check their checksum, and build from source
  instead when run from a checkout, when asked (`DISPATCH_FROM_SOURCE=1`), when `DISPATCH_REF` names a branch rather
  than `main` or a `v*` tag, or when the download fails.
- Changing the page needs Node; building Dispatch itself still does not.

## Amended 2026-09-30: opening it from the Mini App

On a computer's Telegram, an admin's Mini App home offers "Вэб UI нээх". The bot asks the `dispatch ui` running on its
computer for a new one-time link and hands it back; Telegram opens it in the admin's browser, already signed in. When
none runs, the bot starts one in a transient systemd user unit of its own (`dispatch-ui`, outside the bot's cgroup, so
the bot's restart does not stop it), which stops after 30 minutes without a request. Elsewhere (macOS, Windows, a build
not run from a jar) the answer names the command to run instead.
- **The handshake:** each `dispatch ui` writes its port and a 256-bit key to `ui.json` in the owner-only state
  directory, and mints a link only for a `POST /link` carrying that key (compared in constant time) and its own
  `127.0.0.1` Host. A clean stop deletes the file; a crash leaves it, and the bot, finding nothing there, starts another.
- **A minted link** works once, like the printed one, and only for five minutes; it names `127.0.0.1`, so it opens only
  in a browser on the bot's computer.

This hands a way in through Telegram, which the first decision rejected on the network. It stays local: the web UI
still listens only on 127.0.0.1, and what the Mini App carries is a link that works on that computer alone, to an
admin, who can already manage Dispatch from the Mini App.
