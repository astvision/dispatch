---
name: Dispatch Mini App
description: Agent tasks as printed tickets on a rail, in Telegram's own colours.
colors:
  ground: "#17212b"
  slip: "#232e3c"
  ink: "#f5f5f5"
  hint: "#708499"
  link: "#6ab3f3"
  button: "#5288c1"
  button-ink: "#ffffff"
  danger: "#dc4446"
  rule: "#333c44"
  done: "#5fbf6f"
  ground-light: "#efeff3"
  slip-light: "#ffffff"
  ink-light: "#000000"
  hint-light: "#999999"
  button-light: "#2481cc"
  link-light: "#2481cc"
  done-light: "#2e9d47"
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
  station-mono:
    fontFamily: "ui-monospace, 'SF Mono', 'Roboto Mono', Menlo, Consolas, monospace"
    fontSize: "11.5px"
    fontWeight: 400
    lineHeight: 1
    fontFeature: "tnum"
rounded:
  stamp: "3px"
  slip-top: "3px"
  slip-foot: "10px"
  served-foot: "6px"
  control: "10px"
  shelf: "8px"
  badge: "14px"
  section: "12px"
spacing:
  gutter: "16px"
  rail-gap: "12px"
  pass-gap: "22px"
  lane: "26px"
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
  ticket-slip:
    backgroundColor: "{colors.slip}"
    textColor: "{colors.ink}"
    padding: "16px 16px 16px 20px"
    width: "100%"
  rail-ticket:
    backgroundColor: "{colors.slip}"
    textColor: "{colors.ink}"
    padding: "14px 14px 14px 18px"
    width: "min(74vw, 236px)"
  served-slip:
    backgroundColor: "{colors.slip}"
    textColor: "{colors.ink}"
    rounded: "{rounded.served-foot}"
    padding: "14px 14px 10px 18px"
  station-now:
    backgroundColor: "{colors.button}"
    textColor: "{colors.button-ink}"
    typography: "{typography.station-mono}"
    rounded: "{rounded.stamp}"
    padding: "4px"
  count-badge:
    backgroundColor: "{colors.button}"
    textColor: "{colors.button-ink}"
    rounded: "{rounded.badge}"
    height: "28px"
    padding: "0 8px"
  shelf-link:
    textColor: "{colors.link}"
    rounded: "{rounded.shelf}"
    padding: "0 10px"
    height: "44px"
---

# Design System: Dispatch Mini App

## Overview

**Creative North Star: "The Pass"**

Agent work is a row of printed kitchen-style tickets. What waits on the owner hangs full width at the pass, a steel bar across the screen; everything in progress hangs smaller from the rail and scrolls sideways; finished work lies in a short stack of served slips. Each ticket is a slip in Telegram's section colour with a perforated top edge, a printed monospace header (number, project, state word, running clock) over a system-sans body, and a coloured band down its left edge that states its condition together with a word.

The world has no colours of its own. Every surface, ink and accent is a Telegram theme parameter mapped to a world role at runtime (`ui/src/mini/world.ts`), so the Mini App reads as part of Telegram in light and dark. The one exception is the done green, which Telegram does not supply. The hex values in the frontmatter are Telegram's default themes as rendered in the finish review (`.impeccable/review/*.png`); they are reference renderings of the roles, not constants to hard-code. Density is calm: one decision per slip, one primary button per slip, nothing decorative that does not describe the ticket's state or route.

**Scope.** This system covers the Telegram Mini App only: the `MiniApp` shell in `ui/src/App.tsx` (`MiniShell`, class `mini-world`) and `ui/src/mini/*` (home, ticket sheet, and the settings-style list pages). The browser `dispatch ui` (`WebUi` in `ui/src/App.tsx`) is not part of this world; it keeps antd's default look and its sidebar layout, and nothing here applies to it.

**Key Characteristics:**
- Telegram theme parameters are the palette; the world maps them to ground, slip, ink, hint, link, button, danger and rule.
- Tickets are slips: perforated top edge, soft lift, squared top corners and rounded foot.
- Printed monospace headers with tabular numerals wherever digits change.
- State is always a band plus a word, never colour alone.
- One steel line (hint colour) that tickets hang from: the pass bar and the rail.
- Motion tells one story: a decided ticket leaves the pass and joins the rail.

## Colors

The palette is Telegram's own; the world assigns roles, and the ground/slip relationship flips between schemes so a slip always reads as raised.

### Primary
- **Telegram Button Blue** (`button`, `button-light`): the owner's colour. The "needs you" band, the primary action on a slip, the stamped current station, the count badge, the focus ring, text caret and the join outline. If it is button-coloured, it is about the owner's next move.

### Secondary
- **Link Blue** (`link`, `link-light`): navigation and secondary actions (shelf links, "Бүгд", choice-aside labels, Хаах/Болих) and the "working" band. In the light scheme link and button are the same blue, so the working band is hatched (5px dash, 3px gap) to stay distinct.

### Tertiary
- **Done Green** (`done`, `done-light`): the only colour the world adds; a green tuned per scheme, used only on the band of a delivered ticket, always beside the word "хүргэсэн".
- **Destructive Red** (`danger`): the failed band, error lines, and the confirmed-reject button. The unconfirmed "Татгалзах" text uses `danger` mixed 70% with `ink` so it reaches legible contrast on the slip.

### Neutral
- **Ground** (`ground`, `ground-light`): the page. Dark: Telegram `bg_color`. Light: `secondary_bg_color`, the grey of Telegram's settings screens.
- **Slip** (`slip`, `slip-light`): Telegram `section_bg_color`; every ticket, the sheet, and list sections.
- **Ink** (`ink`, `ink-light`): Telegram `text_color`; titles, body, the printed `#number` and state word.
- **Hint / Steel** (`hint`, `hint-light`): the pass bar, the rail line and the hangers; the waiting band; station separators. As text it is used only mixed: **Hint Ink** is `hint` 62% into `ink` (`--hint-ink`), the colour of every secondary line, header detail, clock and mono aside.
- **Rule** (`rule`): Telegram `section_separator_color`; dashed rules inside a slip, the shelf's top hairline, choice borders, the zero-count ring, and the "quiet" band of rejected and cancelled tickets.

### Named Rules
**The Host Palette Rule.** Every colour comes from a Telegram theme parameter through `worldStyle()`; antd tokens are only the fallback for a parameter a client omits. The single invented colour is done green. A new colour needs a new role, not a new hex.

**The Band-and-Word Rule.** A ticket's state is shown by its left band and the state word printed in its header, together. Colour alone never carries state.

**The Mixed Hint Rule.** Raw `hint` is for lines and marks; secondary text uses Hint Ink (`hint` 62% into `ink`) so it stays readable on both ground and slip.

## Typography

**Display Font:** none; the system has no display tier.
**Body Font:** the system sans stack inherited from antd's `token.fontFamily` (Telegram's own face on each platform).
**Label/Mono Font:** `ui-monospace` stack (SF Mono, Roboto Mono, Menlo, Consolas).

**Character:** a printed ticket. Monospace is the ticket's printing (number, project, clock, state, route, step line, counts); sans is what a person wrote (titles, questions, plans).

### Hierarchy
- **Headline** (700, 22px, 1.2): the title of a list page (Төслүүд and siblings), balanced wrap.
- **Sheet Title** (700, 20px, 1.3): the ticket's title at the top of its sheet.
- **Title** (600, 17px, 1.3): a pass ticket's title; 15px and clamped to two lines on a rail ticket. The header strip's "Dispatch" is 17px.
- **Lane** (600, 15px): lane headings (Таны шийдвэр, Явж байна, Дууссан) and sheet section heads; a count beside it is 13px mono in Hint Ink.
- **Body** (400, 15px, 1.45–1.5, max 68ch in the sheet): the wait line, quiet lane lines, plan text. Questions are 16px 600.
- **Label Mono** (400, 12.5px, 1.3, tabular): the printed ticket header, rail step line, served "state · age". The `#number` and state word are ink, 700/600; the rest Hint Ink.
- **Station Mono** (400, 11.5px, 1): the route strip; the count badge is 600 15px mono.

### Named Rules
**The Printed Numerals Rule.** Any digit that changes (clocks, counts, step numbers, question n/N, list markers in the sheet) is set in the mono stack with `tabular-nums`, so a ticking clock never jitters.

**The One Clock Rule.** A live ticket's clock counts in fixed slots (`4:07`, `1:04:07`), then whole days with the same word "өдөр"; finished tickets show age as "12 мин", "3 цаг", "2 өдөр".

## Layout

A single column, max 640px, centered, with a 16px gutter and safe-area padding at the foot. Home stacks three lanes 26px apart: the pass (full-width slips 22px apart), the rail (a horizontal scroll-snap row of slips `min(74vw, 236px)` wide, 12px apart, scrollbar hidden, bleeding to the screen edges), and the served stack. The pass and the rail each bleed to the screen edge and draw a 2px steel line 7px below their top; every slip on them hangs from it by a 2px hanger 28px from its left edge. Below the lanes, a shelf of links to every other page sits after a hairline and wraps to more rows rather than scrolling. No breakpoints: the layout is phone-first and simply stops widening at 640px.

Tap targets are at least 44px (links, reject, shelf) and 46px for primary actions and choices.

## Elevation & Depth

Depth is paper on a counter: slips lift slightly off the ground with a soft two-part shadow, and the ground flips between schemes (grey ground under white slips in light; the darker `bg_color` under `section_bg_color` in dark) so the slip is always the raised plane. The sheet rises over a 45% black scrim with an upward shadow. There are no hard or offset shadows.

### Shadow Vocabulary
- **Slip lift** (`box-shadow: 0 1px 1px rgba(0,0,0,0.06), 0 8px 18px -10px rgba(0,0,0,0.35)`): every ticket at rest.
- **Served stack** (`box-shadow: 0 -1px 0 rgba(0,0,0,0.08), 0 6px 12px -8px rgba(0,0,0,0.4)`): served slips, whose top edge lies over the slip before (−5px overlap, every other slip nudged 3px right).
- **Sheet** (`box-shadow: 0 -12px 32px -12px rgba(0,0,0,0.45)`): the ticket sheet over its scrim.

### Named Rules
**The Paper Rule.** Depth comes from the ground/slip flip and one soft lift. Nothing glows, nothing floats higher than the sheet.

## Shapes

Slips have squared tops and rounded feet (3px top, 10px foot; served slips 2px/6px), because the top is torn from a roll: a perforation drawn in CSS as a row of 3.2px half-circles every 10px in the ground colour (in the sheet, the ground darkened 45% toward black to match the scrim). The state band is a 5px strip down the left edge, following the foot's curve. Controls on a slip (primary button, choices) are 10px rounded; the stamped station is 3px; the count badge is a 28px pill. Inside a slip, divisions are 1px dashed rules; between page regions, a 1px solid hairline.

## Components

### Buttons
Tactile and plain: one full-width primary per slip, and text actions for everything else.
- **Shape:** gently rounded (10px), full width, min 46px.
- **Primary:** button fill with button-ink label, 600 16px. Labels name the action (Хариулах, Төлөвлөгөө үзэх, Зөвшөөрөх, Илгээх).
- **Press:** scales to 0.985 over 140ms with the world ease, only when motion is allowed. Disabled: 45% opacity.
- **Confirmed destructive:** the same shape in danger fill ("Тийм, татгалзах"), reached only after a first tap on the text action and a note that explains the consequence.
- **Text actions:** borderless, min 44px, 15.5px. "Татгалзах" in the mixed destructive tone; Хаах and Болих in link.

### Chips (Choices)
- **Style:** a question's options as stacked 46px rows on the ground colour with a 1.5px rule border, 10px radius, 15.5px ink, left aligned.
- **Aside pair:** "Өөрөөр" and "Та шийд" in a two-column row, centered, in link colour; the same labels as the chat's own buttons for the same actions. "Өөрөөр" opens a textarea (antd) and an Илгээх primary.
- **State:** disabled at 50% while an answer is in flight; later questions are dimmed to 55% until their turn; an answered question shows "Хариулт: **answer**".

### Cards / Containers (Ticket slips)
- **Corner Style:** 3px top, 10px foot.
- **Background:** slip.
- **Shadow Strategy:** Slip lift (see Elevation).
- **Border:** none; the perforated top edge and the 5px state band are the frame.
- **Internal Padding:** 16px, 20px on the band side (rail 14/18px).
- **Pass ticket:** header, title, the wait line ("**2 асуулт:** question" or "**Төлөвлөгөө бэлэн.** Уншаад шийднэ үү."), the station strip, the primary action.
- **Rail ticket:** header, two-line title, two-line mono step line ("23 · Edit src/…").
- **Served slip:** "#number title" on one line (number 13px Hint Ink), "state · age" on the next in mono.

### Navigation
- **Header strip:** the bot avatar (36px), "Dispatch" over "@bot" in Hint Ink, and a count badge "N хүлээж байна" at the right; at zero the badge empties to a ring in rule colour.
- **Shelf:** link-coloured 15px text buttons, 44px tall, wrapping; admin pages join the same row.
- **Back:** Telegram's own Back button closes the sheet and leaves a page; a link-style "Буцах" appears only for clients that do not show it.
- **List pages:** Telegram-settings rows (52px, 16px inset, hairline between rows, chevron on rows that lead somewhere) inside slip-coloured sections with 12px corners.

### Station Strip (signature)
The route from draft to pull request printed across the slip in 11.5px mono after a dashed rule: `1 Ноорог › 2 Төлөв › 3 Хариу › 4 Ажил › 5 PR`. Passed stations are struck through; the current one is stamped in button fill with button-ink, 700. It never wraps. Screen readers get one label ("Алхам 3/5: Хариу").

### Ticket Sheet (signature)
Tapping a slip slides it up into a full-height sheet (max 640px, 36px short of the viewport top) that keeps the slip's material: perforated top, state band, printed header, then the title, Ойлголт, the questions, Алхмууд (mono markers), Эрсдэл, Олдвор. The decision bar sticks to the bottom over a rule. Back button, Escape or a tap on the scrim closes it; focus goes to its title and returns to the opener.

### Motion
One ease (`cubic-bezier(0.16, 1, 0.3, 1)`). The sheet rises in 380ms over a 240ms scrim fade. A decided ticket drops off the pass (460ms, down 64px, shrinking to 0.6, fading) while its slot closes (360ms, after 180ms), then it lands on the rail from above (620ms) with a button-coloured outline that fades over 1.6s, and a haptic tick. With reduced motion every one of these is removed and the change is instant.

## Do's and Don'ts

### Do:
- **Do** take every colour from `worldStyle()`'s variables (`--ground`, `--slip`, `--ink`, `--hint`, `--link`, `--button`, `--button-ink`, `--danger`, `--rule`, `--done`) and check the result in both Telegram schemes.
- **Do** mark state with a 5px left band and the state word in the header, together; hatch the working band.
- **Do** set every changing digit in the mono stack with tabular numerals.
- **Do** hang moving and waiting tickets from the single steel line in hint colour.
- **Do** give each slip at most one full-width primary action, and ask once more before anything that ends a task.
- **Do** use Hint Ink (`hint` 62% into `ink`) for secondary text, not raw `hint`.
- **Do** honour reduced motion by removing the motion, not shortening it.

### Don't:
- **Don't** apply any of this to the browser `dispatch ui`; it keeps antd's default look.
- **Don't** introduce colours outside Telegram's theme parameters except the done green.
- **Don't** build home or ticket surfaces from tables, card grids, KPI tiles or a sidebar.
- **Don't** print kitchen words or kitchen icons on the surface; the metaphor lives in form, not copy.
- **Don't** put a small label above a lane or ticket title; the lane heading and the printed header already name it.
- **Don't** use hard or offset shadows; slips lift with the soft shadows above only.
- **Don't** load fonts, images or anything else from telegram.org or a font CDN; the system stacks are the type.
