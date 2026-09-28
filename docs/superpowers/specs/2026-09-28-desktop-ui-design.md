# The desktop UI in Mongolian, as a dispatcher's board (D-1, D-2)

Status: D-1 approved design, 2026-09-28. D-2 is outlined here and designed after D-1 lands.

## Goal

`dispatch ui` becomes a console its owner reads at a glance and in their own language. D-1 gives every page Mongolian and
English, a new look (the board, below), projects and people edited in side panels, and logs laid out as rows. D-2 adds
the tasks and a live overview.

Brief: the desktop is where the person who owns a Dispatch instance sets it up, keeps it running and follows its work.
They live in a terminal, read Mongolian first, and come here to answer "is it working, what is it doing" and to change
the config without breaking it.

In scope (D-1): the language of every page `dispatch ui` serves, and of the pages the Mini App shares with it; every
message the server writes for a page; the board look on the desktop; the Projects, People, Settings, Logs, Overview and
setup pages in it; the logs endpoint's filters.

Out of scope (D-1): the tasks and live lamps (D-2); a light scheme; an English Mini App (it follows the bot, which speaks
only Mongolian); the bot's Telegram texts; the terminal's texts (`dispatch check`, `dispatch init` and the like stay
English).

## Success criteria

1. With the switch on Монгол, no page `dispatch ui` serves shows an English word except code values (paths, branches,
   ids, log events), names, and what other programs wrote (git's, gh's, Claude Code's own errors), in every page and
   state the tests render.
2. The terminal reads exactly as before: the tests that assert its English messages pass unchanged.
3. Every page works at 1280 and at 390 pixels wide, in the desktop's board and in the Mini App's world.
4. A project is added and edited in the side panel, people are managed by group, and a log line reads as a row that can
   be filtered and followed.

## Where things stand (2026-09-28)

- One React and Ant Design app has two shells: the desktop (`WebUi` in `App.tsx`, antd's default look, English) and the
  Mini App (`MiniApp`, graphite and amber from `mini/world.ts`, Mongolian written into its own pages).
- The Mini App shows the desktop's Overview, People, Settings and Logs pages as they are, so it shows English inside its
  Mongolian screens, and it shows the server's English error messages as they come (`error.message`).
- The server writes about 33 check findings (`Checks`), 51 config validation messages (`ConfigLoader`), 37 refusals
  (`ApiException` in `TasksApi`, `TelegramAuth`, `UiAuth` and others), the setup and management messages
  (`SetupApi`, `ManageApi`, `ConfigEdit`) and the services' notes, all in English. The desktop's own pages write about
  250 phrases.
- antd ships `mn_MN` for its own words. The bot loads `messages_mn.properties` through `ResourceBundle` and
  `MessageFormat` (`Renderer`).
- The desktop has no tasks: `dispatch ui` is its own process (ADR 0018), and only the running bot holds the queue.

## Decisions

| Question | Decision | Why |
|---|---|---|
| Order | D-1 language, look, projects and people, logs; D-2 tasks and the live overview | New pages are then born bilingual in the new look; D-2's open question (below) needs its own design |
| Default language | Mongolian when the browser lists Mongolian among its languages, English otherwise; a switch, remembered in that browser | Works before any config exists; an open-source user who reads no Mongolian starts in English |
| Server text | All of it, from an English and a Mongolian bundle, chosen by the page's `Accept-Language` | A Mongolian-only reader never meets English when something goes wrong; the Mini App's errors are fixed too |
| The terminal | Stays English, from the same keys | The owner chose to leave it; one set of keys serves both |
| The look | The board: a dispatcher's control room, dark slate, signal lamps across the top | The owner's choice of three (the pass, the board, blue sky) |
| Light scheme | None: dark only | The lamps are drawn for slate; a light board would need re-checked shades on every page |
| The Mini App | Keeps its graphite and amber, and stays Mongolian | Telegram has no Mongolian interface, so its language says English for nearly everyone on the team; an English Mini App waits for an English bot |
| i18n library | None: a small `t()` and two typed dictionaries | Two languages and a few hundred phrases; TypeScript checks that Mongolian has every key |
| Fonts | Onest and JetBrains Mono, bundled | Drawn with Cyrillic, Ө and Ү included; bundled because the page runs offline and through `ssh -L` |

## Language on the page (D-1)

- `ui/src/i18n/` holds `en.ts` (`export const en = { … } as const`), `mn.ts` (`export const mn: Record<keyof typeof en,
  string>`, so a missing key fails the build), and `i18n.tsx`: the `t(key, params?)` function, a `useLanguage()` hook
  and a provider. `{name}` in a phrase is replaced from `params`. English plurals have their own keys (`….one`,
  `….other`), chosen by `count`; Mongolian writes the same word for both.
- The language is `localStorage["dispatch.language"]` when it holds `mn` or `en` (read and written inside try/catch:
  storage can be blocked), else `mn` when any of `navigator.languages` has the primary tag `mn`, else `en`. The Mini App
  is always `mn`.
- The switch reads "Монгол / English" at the right of the strip; choosing one re-renders at once and is remembered.
- The provider also sets antd's `ConfigProvider` locale (`mn_MN` or `en_US`) and `<html lang>`.
- Every phrase a desktop page writes goes through `t()`, and so do the pages the Mini App shares with it. The Mini App's
  own pages (home, tasks, the ticket sheet, groups, prefs, guide) keep their Mongolian as it is written: they move into
  the dictionaries when an English Mini App is wanted.
- Words follow the glossary at the end, which is the bot's and the Mini App's vocabulary.

## Server text (D-1)

- A `dispatch.Text` record holds a key and arguments; an argument may itself be a `Text`. `Text.render(Language)`
  formats it from `texts_en.properties` or `texts_mn.properties` (UTF-8, `MessageFormat`, so an apostrophe is written
  twice). A key missing from Mongolian renders in English and logs `text.missing` once.
- Every message a page can show becomes a `Text` where it is made: `Checks`' findings, `ConfigLoader`'s and `ConfigEdit`'s
  validation messages (a `ConfigException` carries a list of `Text`), `ApiException`, `CliException` where the API
  passes it on, `SetupApi`'s and `ManageApi`'s messages, and the services' `detail` and `notes`. What another program
  wrote goes in as an argument, as it came.
- The HTTP layer renders with the request's language: `mn` when the first range of `Accept-Language` has the primary tag
  `mn`, else `en`. `api.ts` sends `Accept-Language` with every request. The terminal, and every log line, render English.
- A check's area is shown by a label from the bundle (`area.bot`, `area.config`, …); the finding keeps the area key.

## The look (D-1)

- **Colours, dark only:** ground `#10191b`, panel `#172427`, strip `#0c1416`, ink `#e4ece8`, secondary `#9fb4ae`, hint
  `#7d948f`, rule `#24363a`; amber `#f0b44c` for what needs the owner and the main action (dark ink on it), green
  `#58c28d` for running and OK, red `#ef6a5a` for failed and danger. Every text colour reaches 4.5:1 on its ground.
- **Built as** `ui/src/board.ts` (the palette, an antd `darkAlgorithm` theme from it) and `ui/src/board.css` (the strip,
  lamps and rail), as `mini/world.ts` and `world.css` do for the Mini App. The desktop shell uses them; the Mini App
  keeps its world.
- **Type:** Onest for everything; JetBrains Mono only for code values. Bundled from `@fontsource-variable/onest` and
  `@fontsource-variable/jetbrains-mono` (OFL), with their Latin and Cyrillic subsets, the extended ones included. If a
  package lacks the extended Cyrillic subset, its woff2 files are copied into `ui/public/fonts` with the licence instead.
- **The strip,** across the top of every desktop page: the instance name; a lamp for the service (running, stopped, not
  installed); a lamp for the checks (all OK, or how many warnings and problems); after a save that needs one, a
  "restart to apply" lamp with its button until the restart; the language switch. A lamp is never colour alone: its
  words say the same. D-2 adds running, waiting on you, and today's spend.
- **The rail** on the left: Тойм, Төслүүд, Хүмүүс, Тохиргоо, Лог; amber marks the current page. Before setup, only the
  setup page.
- **Motion:** only what answers the person (the side panel sliding in), and none when the computer asks for reduced
  motion. Keyboard focus shows as an amber outline.

## The pages (D-1)

- **Тойм (Overview):** the version and paths, the service (state, Install, Restart, Stop) and the checks, restyled. The
  checks run when the desktop opens and on "Дахин шалгах"; the strip's lamps show that same result, and the service lamp
  also changes after Install, Restart or Stop. Nothing polls.
- **Төслүүд (Projects):** the table (project, alias, group, folder, branch, agent). A row or Засах opens the side panel
  (antd `Drawer`, 420 px, full width below 640 px): the fields grouped ("where it starts", "who does it", the phases), one
  line of help under each, Хадгалах and Болих. "Төсөл нэмэх" opens the same panel with the folder browser first. Хасах
  asks in place, saying the clone stays.
- **Хүмүүс (People):** one section per group: its name, its linked Telegram chat with "Чат салгах" (or "no chat: only you"
  for a personal bot's group), its projects, and its members with an admin switch, Нэр солих and Хасах. A personal bot
  has no admin switch.
- **Тохиргоо (Settings):** the same fields, grouped as limits, commits and commands, with a line of help under each.
- **Лог (Logs):** each line parsed as logfmt (a value may be quoted, with `\\`, `\"`, `\n` and `\r` escaped) into a row:
  time, a level dot with its word for screen readers, the event, the task, and the other fields in the order they came.
  Newest first. Filters: level, event (a prefix; `run.*` works), task number, any text. The endpoint takes `task` and
  `text` beside `level` and `event`, so a filter searches the whole tail, not only the rows shown. "Дагах" (follow)
  re-reads every 2 seconds while it is on and the tab is visible. A task number becomes a link in D-2.
- **Setup:** the same steps, restyled and translated.
- **Shared with the Mini App:** Overview, People, Settings and Logs keep working at phone width in the Mini App's world:
  the side panel goes full screen and a log row stacks its fields under its time.

## Testing (D-1)

- **UI (vitest):** tests render in English (the test setup fixes it), so the existing text queries hold. New tests: the
  default from `navigator.languages`, the switch and what it remembers, storage that throws, a Mongolian render of each
  page, the logfmt parser (quotes, escapes, empty values, a line that is not logfmt), the side panels, the log filters.
- **Server (JUnit):** every key in both bundles with the same `{n}` placeholders; `Text` renders nested texts and falls
  back to English; one Mongolian answer per API (an Overview finding, a config validation error from a save, a Mini App
  refusal); the logs endpoint's `task` and `text` filters. The existing tests that assert English messages stay as they
  are.
- **Browser (Playwright, Linux CI):** English stays the default; one new test switches to Mongolian and reads a heading,
  a validation error and a check finding in Mongolian.
- **Screenshots:** every page at 1280 and 390 pixels in both shells, reviewed while building, as the frontend skill asks.

## Rollout (D-1)

- Its own branch and a local merge, after X-1 finishes. No config keys, no migrations.
- The bot serves the Mini App's pages from its jar, so deploying D-1 changes the Mini App's shared pages too: deployed
  with the owner's OK, as always.

## D-2, outlined

The desktop's Даалгавар page (the list, a task's plan, timeline and cost, and its actions), the Overview as a live
dashboard, and the strip's running, waiting-on-you and spend lamps.

Its open question: how `dispatch ui`, its own process, reaches the running bot's queue. Candidates, to be weighed in
D-2's own design:
- the bot answers the desktop's task requests on a local port, with a secret that only the owner's account can read;
- the desktop reads the database directly, and asks the bot to act;
- the bot serves the desktop's pages itself while it runs (ADR 0018 rejected this for setup and restarts, which
  `dispatch ui` would keep).

## Glossary

The bot's and the Mini App's words, kept. The owner checks the last two once.

| English | Монгол |
|---|---|
| Overview | Тойм |
| Tasks, task | Даалгавар |
| Projects, project | Төслүүд, төсөл |
| People | Хүмүүс |
| Settings | Тохиргоо |
| Logs | Лог |
| Group | Бүлэг (the Dispatch group); a Telegram chat is "Telegram чат" |
| Member, admin | Гишүүн, админ |
| Alias | Товч нэр |
| Base branch | Эхлэх салбар |
| Folder | Хавтас |
| Agent | Агент |
| Planning, execution | Төлөвлөх, хэрэгжүүлэх |
| Plan | Төлөвлөгөө |
| Service | Сервис |
| Checks | Шалгалт |
| Restart | Дахин эхлүүлэх |
| Save, cancel, edit, remove, add a project | Хадгалах, болих, засах, хасах, төсөл нэмэх |
| Running, waiting on you, queue | Явж байна, таныг хүлээж, дараалал |
| Cost, spend | Зардал |
| Computer (a worker) | Компьютер |
| Follow (logs), level, event, search | Дагах, түвшин, үйл явдал, хайх |
| Model | Загвар (the Mini App still says "Model"; to check) |
| Effort | Сэтгэх түвшин (the Mini App still says "Effort"; to check) |

## Risks

- The Mongolian is written by the builder: the owner reads the glossary and one screenshot per page, and a wrong word is
  one line in one dictionary.
- `MessageFormat` swallows a single apostrophe; the bundle test fails on one.
- The shared pages now carry the board's layout into the Mini App; the phone-width screenshots and the Mini App's tests
  guard it.
- Dark only: someone who needs a light page cannot have one yet.
