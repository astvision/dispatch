# Mini App milestone UI-3b Implementation Plan

Created: 2026-09-23
Agent: Claude Code
Status: PENDING
Approved: Yes
Iterations: 0
Worktree: No
Type: Feature

## Summary

**Goal:** A member opens Dispatch from inside Telegram — the bot's Manage button or `/manage` — and sees their own tasks with Cancel and Retry; an admin sees every task of their groups and the management pages, all served by `dispatch run` behind Telegram's signed launch data.

## Out of Scope

- **Setting `miniApp` up from the browser.** It is two lines a team owner writes in `dispatch.yaml` next to `workers`, and `dispatch check` reports it. The same deliberate boundary as W-4's pairing: the setup UI does not grow a field for it.
- **Approving, correcting and rejecting plans.** They stay in the chat (spec: Task pages); a task awaiting approval links to its chat.
- **Translating the management pages.** Projects, People, Settings, Logs and Overview stay English, as they are in `dispatch ui`; only the task pages members see are Mongolian.
- **Playwright for the Mini App.** `ui/e2e/global-setup.ts` boots `dispatch ui` from a jar; a Mini App run needs `dispatch run` against a fake Telegram, which only exists as a Java test class (`src/test/java/dispatch/testing/FakeTelegram.java`). Auth, roles and routing are proved by Java integration tests over real HTTP, the pages by Vitest, and the whole path by the live run in Task 10.
- **Config hot-reload.** The Mini App server reads the snapshot its process started with, exactly as `WorkerApi` does (`src/main/java/dispatch/worker/WorkerApi.java:50-57`); a save answers "restart needed" and Restart restarts the service.

## Approach

**Chosen:** Re-host the existing `dispatch.ui` server inside `dispatch run` — give `UiServer` its authentication and its caller as parameters (`UiAuth` for `dispatch ui`, a new `TelegramAuth` for the Mini App), and add a `TasksApi` over the JSON payload builders `TaskService` already has.

**Why:** The pages, the routes, the save flow and the error shape are already built and tested (UI-3a); this milestone adds an authentication and a role, not a second web stack. It costs one mechanical change — every route gains a `Caller` first parameter, whether it reads it or not — which is what buys a single route concept instead of two kinds of route living side by side.

## Global Constraints

- `miniApp.publicUrl` must pass `ConfigLoader.isWorkerUrl` (https everywhere, plain http only for 127.0.0.1); `miniApp.port` must be 1–65535. No `miniApp` block means the Mini App is off, in a team and in personal mode alike.
- `auth_date` is accepted when it is at most 1 hour old and at most 1 minute in the future.
- Mini App responses keep `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`, and replace `X-Frame-Options: DENY` with `Content-Security-Policy: frame-ancestors https://web.telegram.org https://*.telegram.org`.
- `/api/setup/*` is never registered on the Mini App server.
- May manage = `groups.isAdmin(ref) || !config.isTeam()`. A personal bot has an empty `admins` list, so `isAdmin` alone would lock its owner out of their own instance.
- Member-facing task pages are written in Mongolian, in the wording of `src/main/resources/messages_mn.properties`; every other page stays English.
- The page loads no script from telegram.org and reads the launch data from the URL fragment, which browsers never send to a server.
- The bot token, the secrets file and `SetupApi` are unreachable from the Mini App.

## Context for Implementer

`TaskService`'s read operations come in two shapes: a `void` one that enqueues an outbox message for Telegram, and a payload one that returns an `ObjectNode` (`statusPayload` at `src/main/java/dispatch/core/TaskService.java:737`, `statsPayload` at `:855`). `/history` and `/history N` only have the `void` shape, with the payload built inline. UI-3b needs the payload shape for both, and the ADR 0020 headline-only rule for another member's task is already inside those builders (`TaskService.java:788`, `:813-817`) — extracting the payload keeps that rule in one place instead of re-deriving it for the pages.

`cancel` and `retry` take an `originRef` and a `chatRef` and enqueue a Telegram reply for every refusal. A Mini App call has no message to reply to: pass `null` as `originRef` and the caller's own `telegram:<id>` as `chatRef`, which the outbox already accepts (`TaskService.java:361`).

## File Structure

- `src/main/java/dispatch/ui/UiServer.java` (modify) — gains `Auth` and `Caller`; routes take the caller; header policy comes from the auth.
- `src/main/java/dispatch/ui/UiAuth.java` (modify) — implements `UiServer.Auth`; unchanged one-time-link behaviour.
- `src/main/java/dispatch/ui/TelegramAuth.java` (create) — signature, freshness, who and host for one Mini App request. No state.
- `src/main/java/dispatch/ui/TasksApi.java` (create) — the Mini App's task routes over `TaskService`.
- `src/main/java/dispatch/ui/UiRoutes.java` (create) — builds the management route map both `dispatch ui` and `dispatch run` register.
- `ui/src/telegram.ts` (create) — reads `tgWebAppData` and `tgWebAppThemeParams` out of the fragment; nothing else touches `location.hash`.
- `ui/src/mini/TasksPage.tsx` (create) — My tasks, and the admin Tasks list, one page with a scope prop.

## Assumptions

- Telegram's `initData` is a URL-encoded query string whose `user` field is JSON — Task 3 verifies against Telegram's own documented example data, so a wrong assumption fails there, before anything depends on it.
- A member's private chat id equals their user id, as `setChatMenuButton` is called with it in Task 8. This already holds everywhere Dispatch sends a private message (`dispatch/telegram/Refs.java`).

## Deviations

- Task 2 (tactical): the plan had every existing route's lambda gain a `Caller` parameter. `ManageApi.routes()` and `SetupApi.routes()` keep their `Function<JsonNode, Object>` shape instead, and `UiRoutes.anyCaller` lifts them at registration — which is also where the admin gate goes. Same single route concept inside `UiServer`, no churn in the two APIs or their tests. Files unchanged from the task's list except that `src/main/java/dispatch/ui/ManageApi.java` and `src/main/java/dispatch/ui/SetupApi.java` were not modified after all.
- Task 2 (tactical): `Auth.mayLoadPages` returns `Optional<String>` and is named `pageRefusal`, so the refusal carries its own message instead of leaving `UiAuth`'s text stranded in `UiServer`. `UiServer.start` also takes an `IntFunction<Auth>` rather than an `Auth`, because an authentication names the port it belongs to and port 0 is only resolved by binding.

## Progress Tracking

- [x] Task 1: the `miniApp` config block
- [x] Task 2: `UiServer` takes its authentication and hands routes the caller
- [ ] Task 3: `TelegramAuth`
- [ ] Task 4: task payloads and `TasksApi`
- [ ] Task 5: the Mini App server in `dispatch run`
- [ ] Task 6: the frontend's Telegram transport, theme and shell
- [ ] Task 7: the task pages
- [ ] Task 8: the Manage button and `/manage`
- [ ] Task 9: `dispatch check`, ADR 0019 and the docs
- [ ] Task 10: the live run from a phone

## Implementation Tasks

### Task 1: The `miniApp` config block

**Objective:** Dispatch understands a `miniApp:` block with `publicUrl` and `port`, and refuses an invalid one with the same wording `workers` uses. Leaving the block out keeps the Mini App off, which is the default for every instance that exists today.

**Files:**

- Modify: `src/main/java/dispatch/config/Config.java`
- Modify: `src/main/java/dispatch/config/ConfigLoader.java`
- Modify: `deploy/example.yaml`
- Modify: `src/test/java/dispatch/AppTest.java`
- Modify: `src/test/java/dispatch/TeamWorkersTest.java`
- Modify: `src/test/java/dispatch/worker/WorkerApiFixture.java`
- Test: `src/test/java/dispatch/config/ConfigLoaderTest.java`

**Key Decisions / Notes:**

- Copy `Config.Workers` exactly (`Config.java:176-183`), `@JsonCreator` and the null-port-becomes-0 trick included, so a missing port is reported with everything else that is wrong rather than as a mapping error.
- `validateMiniApp` mirrors `validateWorkers` (`ConfigLoader.java:238-256`) but has no "required once" arm: the block is optional everywhere. Reuse `isWorkerUrl` (`:259`) rather than writing a second URL rule.
- Add the field to the `ConfigFile` record (`ConfigLoader.java:403-414`) and to `Config`'s components; the six `new Config(...)` call sites listed above pass `null`.
- `deploy/example.yaml`: a commented-out block under `workers:` (`deploy/example.yaml:25-27`) with port 7879, so a reader sees it is separate from the workers port.

**Definition of Done:**

- [ ] A config with `miniApp: {publicUrl: https://x.example, port: 7879}` loads and `config.miniApp()` holds both.
- [ ] `publicUrl: http://example.com` and `port: 0` and `port: 70000` are each refused, naming the field.
- [ ] A config with no `miniApp` block loads with `config.miniApp() == null`, in team and in personal mode.
- [ ] Verify: `./mvnw -q test -Dtest=ConfigLoaderTest`

### Task 2: `UiServer` takes its authentication and hands routes the caller

**Objective:** `UiServer` stops constructing `UiAuth` itself and takes a `UiServer.Auth` instead, which decides the host rule, the login step, the caller of a request and the frame headers. Every route gains a `UiServer.Caller` first parameter so a route can tell who is asking. `dispatch ui` behaves exactly as before.

**Files:**

- Modify: `src/main/java/dispatch/ui/UiServer.java`
- Modify: `src/main/java/dispatch/ui/UiAuth.java`
- Modify: `src/main/java/dispatch/ui/UiCommand.java`
- Modify: `src/main/java/dispatch/ui/ManageApi.java`
- Modify: `src/main/java/dispatch/ui/SetupApi.java`
- Create: `src/main/java/dispatch/ui/UiRoutes.java`
- Test: `src/test/java/dispatch/ui/UiServerTest.java`
- Test: `src/test/java/dispatch/ui/ManageApiTest.java`
- Test: `src/test/java/dispatch/ui/SetupApiTest.java`
- Test: `src/test/java/dispatch/ui/UiCommandTest.java`

**Key Decisions / Notes:**

- `public record Caller(String ref, String name, boolean admin)`. `UiAuth` answers one local caller, `new Caller("local", "you", true)`: whoever holds the link already acts as the owner in a shell (SECURITY.md:45-51).
- `Auth` carries what `UiServer.respond` (`UiServer.java:106-140`) asks for today: `boolean hostAllowed(String host)`, `void headers(HttpExchange)` (the frame policy), `boolean login(HttpExchange)` (the `/?t=` redirect; `TelegramAuth` answers false), `boolean mayLoadPages(HttpExchange)` and `Caller caller(HttpExchange)`, which throws `ApiException` to refuse. The existing 401/403 texts move into `UiAuth` unchanged.
- **The page and its assets are not behind `caller()`.** `respond` asks `caller()` only for `/api/*`; a page request asks `mayLoadPages` instead. `UiAuth` answers that from the session cookie, exactly as it gates pages today, so `dispatch ui` is unchanged. `TelegramAuth` always answers true, because the launch data lives in the URL fragment (spec: Security) and a browser cannot send it on the navigation that fetches `index.html` — the script that reads it has not run yet. The bundle is static, carries no data of anyone's, and reaches no route without a signed request.
- The Origin check (`UiServer.java:131`, `UiAuth.originAllowed` at `UiAuth.java:73-75`) moves inside `UiAuth`'s own `caller()`, which sees the method and the headers and refuses a non-GET from another Origin as before. `TelegramAuth` never runs it: with no cookie and no session there is nothing a cross-site request could ride on (spec: Security, "no cookies and no sessions on this port, so there is no CSRF"), and a signed request from Telegram's client carries no Origin this server could predict.
- Route maps become `Map<String, Function<Caller, Object>>` and `Map<String, BiFunction<Caller, JsonNode, Object>>`. `ManageApi.routes()` (`ManageApi.java:103-115`) and `SetupApi.routes()` (`SetupApi.java:117`) ignore the caller; only the arity of their lambdas changes.
- `UiRoutes` holds one static that builds the management map (`OverviewApi` GET plus `ManageApi`'s POSTs) from a config path, `Locations`, `Service`, environment and version, so Task 5 registers the same pages `UiCommand.start` (`UiCommand.java:41-71`) does without copying its wiring.
- Refusing by role is the caller's job, not this class's: Task 5 wraps the management routes it registers.

**Definition of Done:**

- [ ] `dispatch ui` still gives a session for a one-time link, refuses the link twice, refuses another Host and refuses a POST with another Origin — the existing `UiServerTest` assertions, with only the route-lambda arity changed.
- [ ] A route can read the caller: a test route answering `caller.ref()` returns `local` under `UiAuth`.
- [ ] A page still needs a session under `UiAuth`: `GET /` without the cookie is 401, as it is today.
- [ ] `X-Frame-Options: DENY` still comes back from a `dispatch ui` response.
- [ ] Verify: `./mvnw -q test -Dtest='UiServerTest,ManageApiTest,SetupApiTest,UiCommandTest,OverviewApiTest'`

### Task 3: `TelegramAuth`

**Objective:** One Mini App request proves itself: Telegram's signature over the launch data, a fresh `auth_date`, a user who is a member or an admin, and an expected `Host`. Anything else is refused with the code the page needs to explain it.

**Files:**

- Create: `src/main/java/dispatch/ui/TelegramAuth.java`
- Create: `src/test/java/dispatch/ui/TelegramAuthTest.java`

**Key Decisions / Notes:**

- Secret key is `HMAC-SHA256("WebAppData", botToken)`, then the check is `HMAC-SHA256(secret, dataCheckString)` compared with `MessageDigest.isEqual` — the constant-time compare already used in `UiAuth.redeem` (`UiAuth.java:44-45`). Data-check string: every field except `hash`, sorted by key, `key=value` joined with `\n`.
- Refusals, each an `ApiException` (`src/main/java/dispatch/ui/ApiException.java:9`): missing or malformed header and a wrong hash → 401 `unauthorized` (the same code `dispatch check` probes for, `Checks.java:230`); a stale `auth_date` → 401 `expired`; a user who is neither member nor admin → 403 `not_a_member`; a wrong `Host` → 403 `host`.
- The caller is `new Caller("telegram:" + user.id, first_name, isAdmin || !isTeam)` — built inline, because `dispatch.telegram.Refs` is package-private (`Refs.java:8`).
- Allowed hosts are `publicUrl`'s host (with its port when it has one) and `127.0.0.1:<port>`, built the way `WorkerApi.allowedHosts` does it.
- Takes a `Supplier<Groups>` rather than a `Groups`: membership changes in-process when someone joins (`Groups.replace`, `Groups.java:27`), and a member who joined since start must not need a restart to open the Mini App.

**Definition of Done:**

- [ ] Telegram's documented example launch data verifies against its documented bot token.
- [ ] A changed field, a forged `hash`, a missing `hash` and a missing header are each 401 `unauthorized`.
- [ ] An `auth_date` 2 hours old is 401 `expired`; one 5 minutes in the future is 401 `expired`; one 30 minutes old passes.
- [ ] A user id in no group is 403 `not_a_member`; a team admin and a personal bot's sole member both pass, and only the admin and the personal member get `admin == true`.
- [ ] A `Host` that is neither `publicUrl`'s nor `127.0.0.1:<port>` is 403 `host`.
- [ ] Verify: `./mvnw -q test -Dtest=TelegramAuthTest`

### Task 4: Task payloads and `TasksApi`

**Objective:** The Mini App reads tasks through the same payload builders Telegram does. `/history` and `/history N` gain the payload-returning shape `/status` and `/stats` already have, and a new `TasksApi` answers the list, one task's timeline, Cancel and Retry, with each caller seeing exactly what ADR 0020 lets them see.

**Files:**

- Modify: `src/main/java/dispatch/core/TaskService.java`
- Create: `src/main/java/dispatch/ui/TasksApi.java`
- Create: `src/test/java/dispatch/ui/TasksApiTest.java`
- Test: `src/test/java/dispatch/core/StatusAndHistoryTest.java`

**Key Decisions / Notes:**

- Extract `historyPayload(Tx, Set<String> visibleProjects, String viewerRef)` and `timelinePayload(Tx, Set<String>, String viewerRef, long taskId)` from the inline bodies at `TaskService.java:785-793` and `:808-835`; the `void` `history` and `timeline` then call them, so Telegram's rendering is unchanged and the headline-only rule at `:788` and `:813-817` stays in one place.
- Routes: `/api/tasks/list` (the caller's own tasks; an admin may pass `{"scope":"group"}` for every task of their groups), `/api/tasks/timeline`, `/api/tasks/cancel`, `/api/tasks/retry`. All POST, like every other route but `/api/overview`.
- Visible projects come from `groups.projectsOfMember(caller.ref())`; the scope switch refuses a non-admin with 403 rather than quietly narrowing, so a page bug is visible instead of silent.
- **The two scopes differ by a filter, not by a second query.** The payload builders return every task in `visibleProjects`, with the ADR 0020 rule already stripping another member's detail down to a headline. `scope: me` (the default) then keeps only `task.requester().ref().equals(caller.ref())`, so My tasks holds the caller's tasks and nobody else's — not a page of teammates' headlines. `scope: group` (admins only) returns the list as built. The headline rule stays untouched: it is what an admin sees in group scope, and what the timeline answers for a task that is not theirs.
- Cancel and Retry call `TaskService.cancel` / `retry` (`:573`, `:612`) with a `Requester` built from the caller, `null` `originRef` and the caller's own chat ref, and map the result enum to `{"result":"..."}`; `REFUSED` and `NOT_FOUND` become an `ApiException` 403/404 so the page can say why. The authorization rules stay where they are — requester or admin may cancel, requester only may retry.
- No new type for a headline-only task: the payloads are `ObjectNode`, and `UiServer` serializes whatever a route returns (`UiServer.java:182`).

**Definition of Done:**

- [ ] `/status`, `/history` and `/history N` in Telegram render exactly as before the extraction (the existing `StatusAndHistoryTest` assertions pass unchanged).
- [ ] A member's list in `me` scope holds their own tasks with cost and agent activity, and a teammate's task is absent altogether — not present as a headline.
- [ ] The same teammate's task in an admin's `group` scope comes back with `headline` and no runs and no cost.
- [ ] A non-admin asking for `scope: group` is refused 403.
- [ ] Cancel by the requester cancels; cancel by an admin of another group cancels; retry by anyone but the requester is refused; a task id in no group of the caller answers 404 rather than saying it exists.
- [ ] Verify: `./mvnw -q test -Dtest='TasksApiTest,StatusAndHistoryTest,TaskLifecycleTest'`

### Task 5: The Mini App server in `dispatch run`

**Objective:** With a `miniApp` block, `dispatch run` listens on `127.0.0.1:<port>` and serves the bundled pages, the management routes to admins and the task routes to every member, behind `TelegramAuth`. Without one, nothing listens and nothing else changes.

**Files:**

- Modify: `src/main/java/dispatch/App.java`
- Modify: `src/main/java/dispatch/Main.java`
- Modify: `src/main/java/dispatch/ui/UiRoutes.java`
- Test: `src/test/java/dispatch/AppTest.java`
- Test: `src/test/java/dispatch/ui/MiniAppServerTest.java`

**Key Decisions / Notes:**

- `App.start` gains the config file path (`Main.java:109` already has it) and builds `Service.forThisMachine()` and the management routes only when `config.miniApp() != null`. The server is started next to `WorkerApi.start` (`App.java:125`), kept in a field beside `workerApi` (`:57`) and closed in `stop()` next to `workerApi.close()` (`:202-205`), with the same `IOException` → `IllegalStateException("cannot listen on 127.0.0.1:…")` wording as `:126-129`.
- Management routes are registered wrapped: a caller without `admin` gets `ApiException` 403 naming who may manage. Task routes are registered unwrapped. `SetupApi` is not constructed here at all.
- `GET /api/me` answers `{ref, name, admin, personal}` from the caller; the frontend shell needs it because `/api/setup/state` does not exist on this server (`ui/src/App.tsx:35` calls it today).
- A build without the bundled pages (`UiServer.hasUi`, `UiServer.java:75`) logs a warning and skips the server rather than refusing to start the bot: a source build without Node must still run tasks.
- `AppTest` starts with `port: 0` and asks the running server for its port, as `workerPort()` (`App.java:180-183`) already does for workers.

**Definition of Done:**

- [ ] With a `miniApp` block, `dispatch run` answers `/index.html` and `/api/me` for a signed request, and `app.stop()` frees the port.
- [ ] An **unsigned** `GET /index.html` and an unsigned asset under `/assets/` answer 200, while an unsigned `/api/me` answers 401: the page has to load before the script that reads the launch data can run.
- [ ] Without one, nothing listens on any extra port and `AppTest`'s existing assertions pass unchanged.
- [ ] `/api/setup/state` answers 404 on this server, signed request or not.
- [ ] A signed non-admin member gets 403 from `/api/manage/config` and 200 from `/api/tasks/list`; an admin gets 200 from both.
- [ ] A response carries `Content-Security-Policy: frame-ancestors https://web.telegram.org https://*.telegram.org` and no `X-Frame-Options`.
- [ ] Verify: `./mvnw -q test -Dtest='MiniAppServerTest,AppTest'`

### Task 6: The frontend's Telegram transport, theme and shell

**Objective:** Opened from Telegram, the page reads its launch data out of the URL fragment, sends it on every request, follows Telegram's colours and fits a phone; opened through `dispatch ui`, nothing about it changes.

**Files:**

- Create: `ui/src/telegram.ts`
- Create: `ui/src/telegram.test.ts`
- Modify: `ui/src/api.ts`
- Modify: `ui/src/App.tsx`
- Modify: `ui/src/main.tsx`
- Test: `ui/src/App.test.tsx`

**Key Decisions / Notes:**

- `telegram.ts` reads `tgWebAppData` and `tgWebAppThemeParams` from `window.location.hash` once at module load and exports `initData: string | null` plus the parsed theme params. It is the only file that touches `location.hash`; nothing in `ui/src` reads it today.
- `api.ts`: merge the header inside `send` (`ui/src/api.ts:38-52`), not in callers — `headers: { ...init.headers, ...(initData ? { Authorization: `tma ${initData}` } : {}) }`. Add `getMe` and the task calls to the export block at `:257-269`.
- Two new error codes get their own message: `expired` ("close and reopen the Mini App") and `not_a_member` ("ask an admin to add you"). They surface through the existing `ApiError.code` (`api.ts:29-36`), which pages already branch on (`ManagedPage.tsx:28`).
- Theme: `main.tsx:1-12` renders a bare `ConfigProvider`; give it `theme={{ algorithm: params.isDark ? darkAlgorithm : defaultAlgorithm, token: { colorPrimary, colorBgBase, colorTextBase } }}` mapped from `tgWebAppThemeParams`. No params means the antd defaults, exactly as today.
- Shell (`App.tsx:34-55`): in Mini App mode call `getMe()` instead of `getSetupState()`, and build the menu from the role — My tasks always, Tasks and the five management pages only for an admin. Below 768 px the `Layout.Sider` gives way to a `Menu mode="horizontal"` at the top; `Layout.Sider breakpoint="md"` (`App.tsx:44`) already collapses it to zero width, so this is the header that replaces it.
- `ui/src/test-setup.ts:4-16` stubs `matchMedia` as always false, so a phone-layout test overrides it rather than relying on the stub.

**Definition of Done:**

- [ ] With `#tgWebAppData=...` in the fragment, every request carries `Authorization: tma <that data>`; without it, no such header is sent.
- [ ] A 401 `expired` shows "close and reopen"; a 403 `not_a_member` shows who to ask.
- [ ] In Mini App mode the shell calls `getMe`, never `getSetupState`, and a non-admin sees only My tasks in the menu.
- [ ] Dark theme params produce a dark page; no params leave the current look.
- [ ] Verify: `(cd ui && npm run test && npm run typecheck)`

### Task 7: The task pages

**Objective:** My tasks lists the member's running, queued, awaiting-approval and finished tasks with each task's timeline, and Cancel and Retry act on them; an admin also gets Tasks, every task of their groups, where another member's task shows its headline only and Cancel is the only action on it.

**Files:**

- Create: `ui/src/mini/TasksPage.tsx`
- Create: `ui/src/mini/TasksPage.test.tsx`
- Create: `ui/src/mini/fixtures.ts`
- Modify: `ui/src/App.tsx`
- Modify: `ui/src/api.ts`

**Key Decisions / Notes:**

- One component with a `scope: "me" | "group"` prop for both pages: the two differ in which list they ask for and whether a row can be retried, not in layout.
- Follow `LogsPage` (`ui/src/manage/LogsPage.tsx:8-53`), not `useManagedConfig`: a task list polls (its own `useState`, a `setTimeout` chain re-armed after each answer with a `stopped` flag in the cleanup) and never saves config, so it needs no version, no `.bak` and no `RestartNotice`.
- Actions go through `useAction` (`ui/src/useAction.ts:5-24`), as `ProjectsPage` does for probing; Cancel sits behind a `Popconfirm`, Retry does not. Per-row `aria-label` as everywhere else (`ProjectsPage.tsx:51-58`) — the tests select by it.
- A task awaiting approval shows a link to its chat instead of an Approve button: approvals stay in Telegram.
- Copy is Mongolian, in the wording of `src/main/resources/messages_mn.properties` (`worker.*`, `status.*`) — strings inline in the component, no i18n framework for one page.
- The timeline expands in place (antd `Table` `expandable`), so a phone needs no second screen.

**Definition of Done:**

- [ ] My tasks shows the member's tasks with state and title, and expands a row into its timeline.
- [ ] Cancel asks first, then calls the cancel route with that task id and refreshes the list; Retry calls the retry route with no confirmation.
- [ ] In group scope another member's task shows its headline, no cost, and no Retry button; own tasks in the same list keep both actions.
- [ ] A failing action shows its message and leaves the list as it was.
- [ ] Verify: `(cd ui && npm run test && npm run typecheck)`

### Task 8: The Manage button and `/manage`

**Objective:** Each member's private chat gets a "Manage" Web App menu button pointing at the Mini App, set when the bot starts and whenever someone joins; `/manage` answers a member with the same button and everyone else the way the bot answers non-members today.

**Files:**

- Modify: `src/main/java/dispatch/telegram/BotApi.java`
- Modify: `src/main/java/dispatch/telegram/UpdateHandler.java`
- Modify: `src/main/java/dispatch/App.java`
- Modify: `src/main/java/dispatch/domain/OutboxKind.java`
- Modify: `src/main/java/dispatch/telegram/Renderer.java`
- Modify: `src/main/resources/messages_mn.properties`
- Test: `src/test/java/dispatch/telegram/UpdateHandlerTest.java`
- Test: `src/test/java/dispatch/AppTest.java`

**Key Decisions / Notes:**

- `BotApi.setChatMenuButton(long chatId, String text, String url)` as a thin wrapper next to `setMyCommands` (`BotApi.java:146-161`); `FakeTelegram` answers `true` to unknown methods (`FakeTelegram.java:140-150`), so it is assertable with `awaitRequest("setChatMenuButton", …)` without touching the fake.
- Setting the button is best effort per member, like `registerCommandMenus` (`App.java:248-265`): a member who never wrote to the bot has no private chat yet, and that must not stop the others. Without a `miniApp` block, no button is set and nothing is undone.
- `/manage` needs its `case` in the switch (`UpdateHandler.java:211-267`), the private-only guard (`:226-230`), an entry in `COMMANDS` (`:52`) — otherwise it is swallowed as a plan correction inside a task topic — a new `OutboxKind`, a `messages_mn.properties` entry, and `"manage"` in the private-chat menu list (`App.java:261`).
- The answer carries a Web App button, which `BotApi.keyboard` (`:248-256`) cannot build: it only writes `callback_data`. Extend it to accept a URL button rather than assembling the reply markup a second way.
- With no `miniApp` block, `/manage` answers that the Mini App is not set up on this Dispatch, naming `dispatch ui` as the way in.

**Definition of Done:**

- [ ] On start with a `miniApp` block, a `setChatMenuButton` request goes out for each member's chat id and for nobody else.
- [ ] With no `miniApp` block, no `setChatMenuButton` request is made.
- [ ] `/manage` in a member's private chat answers with a Web App button carrying `publicUrl`; in a group it answers "private only"; from a non-member it is answered as other commands from non-members are.
- [ ] `/manage` typed inside a task topic is answered as a command, not taken as a correction.
- [ ] Verify: `./mvnw -q test -Dtest='UpdateHandlerTest,AppTest'`

### Task 9: `dispatch check`, ADR 0019 and the docs

**Objective:** `dispatch check` says whether the Mini App is off or on and whether its URL reaches this Dispatch, and the documentation states plainly what turning it on exposes.

**Files:**

- Modify: `src/main/java/dispatch/cli/Checks.java`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `README.md`
- Modify: `SECURITY.md`
- Modify: `docs/superpowers/specs/2026-09-22-mini-app-design.md`
- Create: `docs/adr/0019-managing-dispatch-from-a-telegram-mini-app.md`
- Test: `src/test/java/dispatch/cli/ChecksTest.java`

**Key Decisions / Notes:**

- `checkMiniApp` follows `checkWorkers` (`Checks.java:190-208`): probe the loopback port first and only then the public URL, so a check run before `dispatch run` blames nothing. Generalize `probe` (`:213-234`) to take the path it should ask for — it hardcodes `WorkerApi.PROJECTS` today — and point it at `/api/me`. `probe` sends a POST while `/api/me` is a GET, which does not matter: `UiServer.respond` (`UiServer.java:106-140`) authenticates before it looks a route up, so an unsigned request is 401 `unauthorized` whatever its method.
- With no `miniApp` block the check says so at `OK` level and stops: off is a valid, and the default, state.
- ADR 0019 in the shape of ADR 0021 (`docs/adr/0021-team-members-tasks-run-on-their-own-computers.md`): what was decided, why, the rejected alternatives, then `## Consequences`. It must say that turning the Mini App on puts a shell-equivalent API on the internet, held by Telegram's signature, the one-hour freshness limit and the admin list.
- `README.md` gets the Mini App under "Manage it in the browser" (`README.md:125`); `SECURITY.md` a section after "The web UI" (`:45`); `docs/ARCHITECTURE.md` a `miniApp` paragraph in Configuration next to the `workers` one (`:412`), ADR 0019 in "Decisions at a glance" and the UI-3b row in Milestones (`:414`). Mark UI-3b delivered in the spec's own milestone table (`2026-09-22-mini-app-design.md:130`) and update its `Status:` line, as the team-workers spec does for W-4.

**Definition of Done:**

- [ ] With no `miniApp` block, `dispatch check` reports the Mini App off, at `OK`.
- [ ] With one and nothing listening, it says the port is free and starts with `dispatch run`; with the server up but the public URL not reaching it, it warns naming the tunnel.
- [ ] `dispatch check` still reports workers exactly as before (the existing `ChecksTest` assertions pass unchanged).
- [ ] ADR 0019 exists and README, SECURITY.md and ARCHITECTURE.md each name the Mini App, its config and what it exposes.
- [ ] Verify: `./mvnw -q test -Dtest=ChecksTest`

### Task 10: The live run from a phone

**Objective:** The milestone's own gate (spec: Milestones, UI-3b): the pages open from a phone, through a tunnel, as an admin and as a member — which no test in this repo can prove, because it needs a real bot, a real tunnel and Telegram's own client.

**Owner:** User

**User Action:** With `miniApp` set to a Cloudflare quick tunnel's URL and its port, start `dispatch run`, open the bot's Manage button on your phone as an admin and as a member, and check that each sees what they should and that Cancel works.

**Files:**

- Modify: `docs/superpowers/plans/2026-09-23-mini-app-ui-3b.md`

**Key Decisions / Notes:**

- A quick tunnel (`cloudflared tunnel --url http://127.0.0.1:7879`) needs no domain and no account, which is why the spec names it.
- What to watch for, in order: the Manage button appears in the chat menu; the admin sees Tasks and the management pages; the member sees only My tasks; a task cancelled in the Mini App is announced in the chat as a cancel from the chat is; reopening after an hour asks to reopen rather than failing silently.

**Definition of Done:**

- [ ] The pages open from a phone as an admin and as a member, and each sees what Task 7 says they should.
- [ ] Cancel from the Mini App cancels the task and the chat says so.
- [ ] Verify: the result of the run recorded in this plan file, under the task.

## Goal Verification

### Truths

1. A member with no terminal and no SSH can see their own tasks and cancel one from inside Telegram, and cannot see another member's task beyond its headline.
2. An instance with no `miniApp` block behaves exactly as it does today: nothing extra listens, no menu button is set, and `dispatch check` reports the Mini App off.
