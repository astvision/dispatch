---
name: Dispatch Mini App
description: Agent tasks in BotFather-style sections, with a ticket sheet for decisions, in graphite and amber.
colors:
  ground: "#16181c"
  slip: "#22252b"
  ink: "#ecebe7"
  hint: "#8b8f98"
  link: "#5cc8c0"
  button: "#f2a93b"
  button-ink: "#1a1408"
  danger: "#ef5b5b"
  rule: "#30343b"
  done: "#6cc57c"
  ground-light: "#f4f2ee"
  slip-light: "#ffffff"
  ink-light: "#1c1b19"
  hint-light: "#8a8780"
  button-light: "#c77a0a"
  link-light: "#0b7069"
  danger-light: "#c83a3a"
  rule-light: "#e3dfd8"
  done-light: "#2e8b45"
typography:
  headline:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif"
    fontSize: "22px"
    fontWeight: 700
    lineHeight: 1.2
  title-sheet:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif"
    fontSize: "20px"
    fontWeight: 700
    lineHeight: 1.3
  title:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif"
    fontSize: "17px"
    fontWeight: 600
    lineHeight: 1.3
  lane:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif"
    fontSize: "15px"
    fontWeight: 600
    lineHeight: 1.3
  body:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.5
  label-mono:
    fontFamily: "ui-monospace, 'SF Mono', 'Roboto Mono', Menlo, Consolas, monospace"
    fontSize: "12.5px"
    fontWeight: 400
    lineHeight: 1.3
    fontFeature: "tnum"
rounded:
  control: "10px"
  section: "12px"
spacing:
  gutter: "16px"
  slip-inset: "16px"
  band-inset: "20px"
  tap-min: "44px"
  action-min: "46px"
components:
  button-primary:
    backgroundColor: "{colors.button}"
    textColor: "{colors.button-ink}"
    rounded: "{rounded.control}"
    height: "46px"
    width: "100%"
  button-danger-confirm:
    backgroundColor: "{colors.danger}"
    textColor: "{colors.button-ink}"
    rounded: "{rounded.control}"
    height: "46px"
    width: "100%"
  choice:
    backgroundColor: "{colors.ground}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "10px 14px"
    height: "46px"
  choice-aside:
    backgroundColor: "{colors.ground}"
    textColor: "{colors.link}"
    rounded: "{rounded.control}"
    padding: "10px 14px"
  section:
    backgroundColor: "{colors.slip}"
    rounded: "{rounded.section}"
  list-row:
    textColor: "{colors.ink}"
    height: "52px"
    padding: "8px 16px"
---
p = "DESIGN.md"
t = open(p).read().rstrip()
assert t.endswith("---")
t = t[:-3].rstrip() + '''
  section:
    backgroundColor: "{colors.slip}"
    rounded: "{rounded.section}"
  list-row:
    textColor: "{colors.ink}"
    height: "52px"
    padding: "8px 16px"
---
'''
t += open("/dev/stdin").read()
open(p, "w").write(t)

# Design System: Dispatch Mini App

## Overview

**Creative North Star: "BotFather's own screens"**

The Mini App looks like the settings screens Telegram's users already know from @BotFather: the bot's photo and name centred at the top, a search field, then titled sections of rows on a raised surface, each row with an avatar or icon on the left and a chevron on the right. Home lists the owner's tasks in three sections (Таны шийдвэр, Явж байна, Дууссан) and every other page in a Цэс section (and Удирдлага for an admin). A task opens into the ticket sheet, where decisions are taken.

The world has its own palette, graphite and amber, defined once in `ui/src/mini/world.ts` (`DARK`, `LIGHT`) and handed both to world.css as variables and to antd as its tokens, so antd's inputs and rows match the sections. Telegram decides only which scheme is on: `tgWebAppColorScheme`, or the brightness of its `bg_color` when a client (Telegram Desktop) leaves the scheme out. On open, Telegram's own header, background and bottom bar are painted the ground colour.

**Scope.** This system covers the Telegram Mini App only: the `MiniApp` shell in `ui/src/App.tsx` (`MiniShell`, class `mini-world`) and `ui/src/mini/*` (Home, the ticket sheet, the list pages, and the manual, Гарын авлага). The browser `dispatch ui` (`WebUi` in `ui/src/App.tsx`) is not part of this world; it keeps antd's default look and its sidebar layout.

**Key Characteristics:**
- A fixed graphite-and-amber palette per scheme, roles ground, slip, ink, hint, link, button, danger, rule and done.
- Every screen is sections of rows (`Header`, `Section`, `Row` in `ui/src/mini/List.tsx`); nothing else competes with them.
- State is always a dot plus a word, never colour alone.
- Printed monospace for every digit that changes.

## Colors

Warm graphite neutrals with one amber accent; the ground is darker than the slip in dark and greyer than it in light, so a section always reads as raised. Every text pair meets 4.5:1 and every dot, band or fill 3:1 against its slip, in both schemes.

### Primary
- **Amber** (`button`, `button-light`): the owner's colour. The "таныг хүлээж" dot and band, the primary action in the sheet, the focus ring and text caret. If it is amber, it is about the owner's next move. Amber is light, so everything written on it uses the dark `button-ink`, and amber is never a text colour on a light slip (it reaches only 3.4:1 there).

### Secondary
- **Teal** (`link`, `link-light`): navigation: menu row icons, "Төсөл нэмэх", "Буцах", choice-aside labels, Хаах/Болих; and the "working" dot and band.

### Tertiary
- **Done Green** (`done`, `done-light`): the dot and band of a delivered task, always beside the word "хүргэсэн".
- **Destructive Red** (`danger`, `danger-light`): the failed dot and band, error lines, and the confirmed-reject button. The unconfirmed "Татгалзах" text uses `danger` mixed 70% with `ink` so it reaches legible contrast on the slip.

### Neutral
- **Ground** (`ground`, `ground-light`): the page, and Telegram's header and background around it.
- **Slip** (`slip`, `slip-light`): sections, the search field's fill, the sheet, manual topics.
- **Ink** (`ink`, `ink-light`): titles, body, the state word.
- **Hint** (`hint`, `hint-light`): the waiting dot and band. As text it is used only mixed: **Hint Ink** is `hint` 62% into `ink` (`--hint-ink`), the colour of secondary lines and mono asides.
- **Rule** (`rule`, `rule-light`): hairlines between rows, dashed rules in the sheet, choice borders, the "quiet" dot of rejected and cancelled tasks.

### Named Rules
**The One Palette Rule.** Every colour comes from `DARK`/`LIGHT` in `world.ts`, through `worldStyle()` for the world and `worldTheme()` for antd. A new colour needs a new role there, not a new hex in a component.

**The Dot-and-Word Rule.** A task's state is a tone dot and the state word, together, in its row; in the sheet, the left band and the word in the printed header. Colour alone never carries state.

## Typography

**Body Font:** the system sans stack inherited from antd's `token.fontFamily` (Telegram's own face on each platform).
**Mono Font:** `ui-monospace` stack (SF Mono, Roboto Mono, Menlo, Consolas), for `#numbers`, clocks, step counts and the sheet's printed header.

### Hierarchy
- **Header Title** (antd h3): "Dispatch" under the 80px photo.
- **Headline** (700, 22px, 1.2): the title of a list page (Төслүүд and siblings).
- **Section Title** (600, 16px): above each section.
- **Row Title** (400, 16px, 1.3); **Row Subtitle** (13.5px, Hint Ink); **Row Value** (15px, right, clocks in mono).
- **Sheet Title** (700, 20px, 1.3) and sheet body (15px, 1.5, max 68ch); questions 16px 600.

**The Printed Numerals Rule.** Any digit that changes is set in mono with `tabular-nums`, so a ticking clock never jitters. A live task's clock counts `4:07`, `1:04:07`, then whole days ("өдөр"); a finished task shows its age ("12 мин", "3 цаг", "2 өдөр").

## Layout

A single column, max 640px, centred, 16px gutter, safe-area padding at the foot. Home: the header (80px photo, name, "@bot · N таныг хүлээж байна"), a search that filters the task rows by title, project or `#number`, then sections 20px apart. Rows are at least 52px; a section's rows are split by a hairline inset 16px from the left. Every row that leads somewhere ends in a chevron. Empty sections keep one plain row that says so. No breakpoints.

## Elevation & Depth

Flat: sections are the slip colour on the ground, with no shadow. Only the ticket sheet rises, over a 45% black scrim with `box-shadow: 0 -12px 32px -12px rgba(0,0,0,0.45)`.

## Components

### Rows
- **Task row:** the project's avatar (40px, initials on a colour fixed per name), the title, "#7 · project · ● state" and, when there is one, what it waits on ("2 асуулт: …", "Төлөвлөгөө бэлэн") or its last step ("12 · Edit src/…") on one clipped line; its clock or age on the right.
- **Menu row:** a 20px teal icon in a 40px slot, the page name, a chevron.
- **Header:** `Header` in List.tsx, the bot's profile photo read from Telegram at startup (its initials when it has none).

### Ticket Sheet
Tapping a task slides the sheet up (max 640px, 36px short of the viewport top): perforated top, state band, printed mono header, then the title, Ойлголт, the questions, Алхмууд, Эрсдэл, Олдвор. The decision bar sticks to the bottom over a rule. Back button, Escape or a tap on the scrim closes it; focus goes to its title and returns to the opener.

### Buttons and choices (in the sheet)
- **Primary:** amber fill, dark label, 10px radius, full width, min 46px. Labels name the action (Зөвшөөрөх, Илгээх).
- **Confirmed destructive:** the same shape in danger fill ("Тийм, татгалзах"), only after a first tap on "Татгалзах" and a note that explains the consequence.
- **Choices:** a question's options as stacked 46px rows on the ground colour with a 1.5px rule border; "Өөрөөр" and "Та шийд" side by side in teal.

### Navigation
- **Launch:** the chat's menu button ("Удирдах") or `/manage`.
- **Back:** Telegram's own Back button closes the sheet and leaves a page; a link-style "Буцах" appears only for clients that do not show it.

### Motion
One ease (`cubic-bezier(0.16, 1, 0.3, 1)`): the sheet rises in 380ms over a 240ms scrim fade; buttons and choices press to 0.985. A decision closes the sheet with a haptic tick and the list reloads. Reduced motion removes all of it.

## Performance

Only the shell and Home load up front; every other page is a lazy chunk (`React.lazy` in `App.tsx`), and the server sends the bundle gzipped. Keep new pages lazy.

## Do's and Don'ts

### Do:
- **Do** build every screen from `Header`, `Section` and `Row`.
- **Do** take every colour from `worldStyle()`'s variables or antd's tokens (set from the same palette), and check both schemes.
- **Do** show state as a dot and a word together.
- **Do** set changing digits in mono with tabular numerals.
- **Do** ask once more before anything that ends a task.

### Don't:
- **Don't** apply any of this to the browser `dispatch ui`; it keeps antd's default look.
- **Don't** write a hex in a component; add a role to the palette instead.
- **Don't** build Home from tables, card grids or KPI tiles.
- **Don't** load fonts, images or anything else from telegram.org or a font CDN.
