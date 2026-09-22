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
