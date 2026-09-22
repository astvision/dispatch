# Dispatch web UI: `dispatch ui`

Status: approved design, 2026-09-22. Next: implementation plan for milestone UI-1.

## Goal

Setting up and managing Dispatch without the terminal, on macOS, Windows and Linux, for a personal bot on the same computer and for a team bot on a server reached remotely. The terminal commands keep working as they are.

In scope: setup (everything `dispatch init` does) and management (projects, people, settings, the background service, `check`, logs).

Out of scope: tasks, plans, approvals, history and stats. They stay in Telegram. A later project may add them on the same API and frontend.

## Decisions

| Question | Decision | Why |
|---|---|---|
| Which process serves the UI | A separate command, `dispatch ui`, not the running service | It works before any config exists, and it can stop and restart the service |
| Where it listens | `127.0.0.1` only; remote use goes through `ssh -L` | Nothing is exposed to a network; the UI is as powerful as a shell |
| How you log in | A one-time token in the printed URL, exchanged for a session cookie | Same model as Jupyter; no password to set or store |
| Frontend | React, TypeScript, Vite, Ant Design | The maintainers' usual stack; the richest UI |
| How installs get the frontend | CI builds a jar with the UI and attaches it to a GitHub Release; the install scripts download it | Installs keep needing only Java; Node is needed only to build the UI |
| Server | JDK `com.sun.net.httpserver.HttpServer` and Jackson | ADR 0002: plain Java without a framework |
| Config edits | The whole YAML is rewritten in `init`'s layout, the previous file kept as `dispatch.yaml.bak` | No YAML library keeps comments; the backup and a warning make the loss visible |

Rejected: serving the UI from `dispatch run` (it cannot do first setup, and restarting the service would kill the UI); a desktop app with Tauri or Electron (per-OS packaging, and it still needs a tunnel for a server).

These decisions are recorded as ADR 0018, "A local web UI served by `dispatch ui`".

## Architecture

```
browser (React + Ant Design single-page app)
   │  HTTP/JSON with a session cookie, only via 127.0.0.1 (ssh -L for a server)
dispatch ui   (its own process, same jar)
   ├─ UiServer    HttpServer on 127.0.0.1:<port>: /api/* and the bundled static files
   ├─ UiAuth      one-time token -> session cookie; Host and Origin checks
   ├─ SetupApi    the setup steps, driven by the browser
   ├─ ManageApi   config, projects, people, settings, service, check, logs
   └─ reuses      Locations, ConfigLoader, ConfigText, ProjectProbe, Service, BotApi, SecretsFile, Redactor, the store (read-only)
dispatch run / the background service   (unchanged, separate process)
```

### Command

`dispatch ui [--config FILE] [--port 7878] [--no-browser]`

- Runs in the foreground until Ctrl+C.
- Prints `http://127.0.0.1:<port>/?t=<token>` once, and opens it in the default browser unless `--no-browser` is given or no browser can be opened (for example over SSH).
- If the port is taken, it fails with a message naming `--port`.
- If the jar has no bundled UI (a source build without Node), it exits with: "this build has no web UI; install the release jar, or build with the ui profile".

### Java units

New package `dispatch.ui`:

- `UiServer`: starts the `HttpServer` on the loopback address, routes `/api/*` to the handlers, and serves `/` and `/assets/*` from the `/ui` resources in the jar. Unknown non-API paths return `index.html`, for client-side routing.
- `UiAuth`: creates the token, exchanges it for a session, and checks each request (see Security).
- `SetupApi`: the setup flow as separate HTTP steps.
- `ManageApi`: management reads and writes.
- `Json` responses through the existing `dispatch.Json`.

Refactor, with no change to the command-line behaviour:

- `CheckCommand` -> `Checks`: returns a list of findings `{area, ok, message, fix}`. `CheckCommand` prints them. `ManageApi` returns them.
- `InitCommand` -> `Setup` (no `Terminal`): checking the token, the setup `Updates` (waiting for people and for the group, the Telegram greeting, acknowledging), `render` and `write`. `InitCommand` becomes the terminal front end to `Setup`. `SetupApi` is the HTTP front end. The config YAML is produced in one place.
- `ProjectAddCommand` and `ServiceCommand`: the same split. Their logic moves into methods that return values or throw `CliException`, and the commands print.

The existing tests `InitCommandTest`, `CheckCommandTest`, `ProjectAddCommandTest` and `ServiceTest` pass without changes. That is the evidence that the terminal commands still behave the same.

### Frontend

- `ui/` at the repository root: Vite, React, TypeScript, Ant Design, React Router.
- `npm run build` writes `ui/dist`. A Maven profile `ui` copies `ui/dist` into the jar under `/ui`. Without the profile the jar has no UI.
- Pages keep UI and data access apart: one API client module (`ui/src/api.ts`) with typed calls; pages use it through small hooks.
- Every page has loading, empty and error states. A banner shows when `dispatch ui` cannot be reached.

## Screens and data flow

### Setup

Shown when the config file does not exist. Ant Design `Steps`, the same six steps as `dispatch init`:

1. **Who uses the bot**: just me, or my team.
2. **The bot**: the token in a password field, checked with `getMe`. Shows `@username` and, when topics are off, the topics tip.
3. **People**:
   - Shows `https://t.me/<bot>` as a link and a QR code.
   - The browser long-polls `GET /api/setup/people/next` (up to 25 seconds per request). Someone who writes to the bot privately appears as a card with **That's me** and **Not me**. Granting access is always a click; nothing is accepted by default.
   - The bot answers them in Telegram, as the terminal wizard does.
   - After a quiet wait the page shows the same hint as the terminal wizard: send any message when there is no Start button; one tick means Telegram has not delivered it (check Settings > Chat Automation); stop a running Dispatch with this bot.
   - Team: teammates are added the same way; then "add the bot to your group", and the group is found from `my_chat_member` or a group message.
4. **Claude Code**: found on the PATH or typed in, checked with `--version`.
5. **Projects**: a folder browser on the machine that runs Dispatch, because the browser may be on another computer. Choosing a git clone fills name, base branch, model and effort from `ProjectProbe`; more projects can be added.
6. **Commits**: author name and email.

Then the summary and **Write**. After writing, **Keep it running in the background** installs the service.

Setup state lives in the `dispatch ui` process until Write; nothing is written before, as with `init`. Leaving the page and coming back resumes the same setup while the process runs.

### Management

Shown when the config exists. A left menu:

- **Overview**: service status (installed, running, detail, notes) with Start, Stop and Restart; the `check` findings with their fixes; bot username, Dispatch version, config and state paths.
- **Projects**: a table of name, path, base branch, model and effort. Add (with the folder browser), edit inline, remove.
- **People**: groups with their members, and the admins. Remove a member, rename, make or unmake admin. New people still join from Telegram by admin approval (ADR 0015); the page says so.
- **Settings**: limits (timeouts and budgets per phase), maximum concurrent runs, commit author, claude command, and replacing the bot token.
- **Logs**: the last lines of the service log, filtered by level and event, refreshed every 2 seconds.

### Saving a change

1. The browser sends the changed section.
2. The server applies it to the loaded config, renders the whole YAML and validates it with `ConfigLoader`.
3. When the file changed on disk since it was loaded (compared by modification time and size), the save is refused: "the config changed on disk; reload".
4. Otherwise it copies the current file to `dispatch.yaml.bak` and writes the new one to a temporary file in the same folder, then renames it over the old one.
5. The answer says whether the running service must restart for the change; the page offers **Restart now**.

Before the first save, when the file contains comments that `init` did not write, the page warns that they will be lost and that `.bak` keeps them.

## Security

- Listens on `127.0.0.1` only. There is no option to listen on another address.
- A new random 256-bit token each time `dispatch ui` starts. It is printed once and never logged. `GET /?t=<token>` sets a session cookie (`HttpOnly`, `SameSite=Strict`, `Path=/`) and redirects to `/`. The token works once; restarting `dispatch ui` ends every session.
- Every `/api` request needs the session cookie and a `Host` of `127.0.0.1:<port>` or `localhost:<port>`, against DNS rebinding. Requests that change something also need a matching `Origin`. Otherwise: 401 without a session, 403 for a wrong host or origin.
- The bot token is never sent to the browser: the UI shows "set, @username" and a Replace action. Secrets are written only through `SecretsFile`, which keeps the file owner-only.
- Log lines go through `Redactor` before they are sent.
- The folder browser lists directories only, starting at the user's home directory, and says for each whether it is a git clone. It never returns file contents.
- `dispatch ui` runs as the user, like every other command; it allows nothing the command line does not. SECURITY.md gains a section on it: whoever can reach the port with a session can act as that user.

## Error handling

- Every API error is JSON: `{"error": "<code>", "message": "<for people>", "fix": "<optional>"}`. `CliException` and `ConfigException` messages are already written for people and are passed through.
- Nothing is written when validation fails; the message names the field.
- During setup, a Telegram conflict (409, Dispatch already polling with this bot) is shown as "Dispatch is running with this bot: stop it first" with a **Stop service** button.
- Service actions answer with the service status after the action, not only success. When a restart leaves the service not running, the answer includes the last 20 lines of its log.
- A save against a config that changed on disk is refused; nothing is overwritten.

## Testing

- Java: unit tests for `Setup`, `Checks` and the split service and project logic against `FakeTelegram`, `GitFixture` and temporary folders. The existing command tests pass unchanged.
- HTTP: one test class that starts `UiServer` in-process and calls it with `HttpClient`:
  - no cookie: 401; a used token: 401; wrong `Host` or `Origin`: 403
  - a whole setup through the API against `FakeTelegram`, ending in a config that `ConfigLoader` accepts
  - a config save writes `.bak`, is refused on a validation error, and is refused when the file changed on disk
- Frontend: Vitest and React Testing Library for the setup steps and forms.
- End to end: one Playwright test per milestone against a real `dispatch ui` process with `FakeTelegram`: setup in the browser (UI-2); adding a project and restarting the service (UI-3).
- Live: a manual run against a real bot before each milestone is marked done.

## Delivery

- `ci.yml`: a Node job (install, lint, test, build) whose `ui/dist` the Maven job bundles with `-Pui`.
- `release.yml` (new): on a `v*` tag, builds the jar with the UI and attaches `dispatch-<version>.jar` and its SHA-256 checksum to a GitHub Release.
- `install.sh` and `install.ps1`: download the latest release jar and verify its checksum. `--from-source` keeps today's build from source, without the UI.
- Documentation: README ("Manage it in the browser"), SECURITY.md (access to the UI), ARCHITECTURE.md, ADR 0018.

## Milestones

Each milestone is built test-first on its own branch with a stacked draft pull request, and stops for review before the next.

| Milestone | Delivers | Done when |
|---|---|---|
| UI-1 | The refactor into `Setup`, `Checks` and the service and project logic; `UiServer`, `UiAuth` and static files; the frontend skeleton; CI, the release workflow and the install scripts; `dispatch ui` showing the Overview with `check` findings | The command tests pass unchanged; the auth tests pass; the release jar opens the Overview on all three OS in CI (a start-up smoke test) |
| UI-2 | Setup in the browser: all six steps, confirming people in the browser, the QR code, the folder browser | A whole setup in the browser against `FakeTelegram` in Playwright, and once live against a real bot, without touching the terminal after `dispatch ui` |
| UI-3 | Management: Projects, People, Settings, service Start, Stop and Restart, Logs, and the save flow | The Playwright management test passes; saving keeps `.bak` and refuses a config changed on disk |
