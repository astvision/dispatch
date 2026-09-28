# The pages speak Mongolian and English

The desktop pages (`dispatch ui`) speak Mongolian or English. The browser's first language decides — Mongolian when it
is `mn`, English otherwise — and the **Монгол / English** switch at the right of the strip changes it at once and is
remembered in that browser. The Mini App speaks Mongolian only, as the bot does, on its own screens and on the pages it
shares with the desktop.

A page's own words come from two dictionaries, `ui/src/i18n/en.ts` and `mn.ts`, through one function, `t(key, params)`.
`mn` is typed `Record<Key, string>`, so a phrase missing from it fails the build. antd's own words (dates, pagination,
empty states) follow through its `mn_MN` locale, and `<html lang>` follows too.

What the server says to a page — a refusal, a validation message, a check's finding, the service's state and notes — is a
`dispatch.Text`: a key and its arguments, not a sentence. Each request is answered in the language its `Accept-Language`
names (the first range: `mn…` is Mongolian, anything else English), rendered from `texts_en.properties` or
`texts_mn.properties` with `MessageFormat`; the page sends its language on every request, and JSON writes a `Text` in
the request's language. A Mongolian phrase missing from its bundle falls back to English and logs `text.missing` once;
an English one missing is a bug, which a test that reads every key the code names catches.

The terminal and the log stay English: `dispatch init`, `dispatch check`, a member's worker and every exception's
message render the English text, so what someone pastes from a terminal or a log reads the same everywhere.

We rejected:
- **An i18n library** (i18next, react-intl). Two languages, plurals only as English's one and other, a few hundred
  phrases: a typed dictionary and one function do it, with nothing to configure or keep up to date.
- **Translating the server's error codes on the client.** Messages carry values (a path, a name, a limit) and are
  raised in many places; keying them where they are raised keeps one source for the terminal and the pages, and the
  page never has to know every refusal the server can make.
- **A light scheme.** The desktop's look, the board, is drawn for dark slate: its lamps and tints would each need
  checking again on a light ground.

## Consequences

- A new page phrase is a key in both dictionaries; a new server message that can reach a page is `Text.of(key, …)`
  with the key in both bundles, its apostrophes doubled for `MessageFormat`.
- The desktop's look is the board (`ui/src/board.ts`, `board.css`): dark slate, amber for what needs the owner, green
  and red lamps that always carry their words, Onest and JetBrains Mono bundled with their Cyrillic subsets. The Mini
  App keeps Telegram's colours (`mini/world.ts`).
- The Mongolian is written by the builder; the owner checks the glossary in the design spec and a screenshot of each
  page, and a wrong word is one line in one dictionary.
