---
version: 1
slug: "ui-src-mini-homepage-tsx"
primary_target: "ui/src/mini/HomePage.tsx"
related_targets: ["ui/src/App.tsx"]
---

## Scope

The Dispatch Telegram Mini App (ui/src/App.tsx `MiniApp` shell and ui/src/mini/*): Home, a task sheet (plan, questions, approve/reject), projects, groups, people/settings/logs/overview entry points, Миний тохиргоо. Mode: Operate. The browser `dispatch ui` shell (`WebUi`) is out of scope and keeps its look.

## Audience and job

One owner on a phone inside Telegram, glancing at agent work several times a day; the job is to see at once what waits on them and act in one or two taps (answer a question, approve, reject), then watch the rest move.

## Constraints

Telegram theme params drive every colour (light and dark); Telegram back button, haptics and safe areas; Mongolian copy; React 19 + antd 6 already in the bundle (antd may stay for inputs/popovers, but the surface is built from our own components); no assets from telegram.org; fast on a phone (no heavy libraries, no large images). The owner rejects: generic admin dashboard (tables, card grids, sidebar), gimmicky/playful decoration, slowness, anything not Telegram-native.

## Direction contract

THESIS: Tasks are tickets on a rail. What waits on the owner hangs full-width at the pass; everything else is a smaller ticket moving along the rail; delivered work stacks as served. It refuses the category default: a card grid or KPI dashboard with a sidebar menu.

OWN-WORLD: Telegram's own ground (bg / secondary_bg) with tickets as slightly raised slips in the theme's section colour, a torn/perforated top edge drawn in CSS, printed monospace ticket headers (#number, project, age) over the system sans body; one steel rail line (hint colour) the moving tickets hang from; state as a coloured left band plus a word (needs you = button colour, working = link colour, done = green, failed = destructive red); tabular numerals everywhere digits change. Kitchen words and icons never appear.

STORY: The owner understands in one glance what needs them, trusts that the rest is moving (live step line, ticking age), and acts from the ticket itself.

FIRST VIEWPORT: Top: a compact header strip (bot avatar, "Dispatch", count of tickets at the pass). Then THE PASS: full-width tickets waiting on the owner — each shows #, project, title, the question or "plan ready", a numbered station strip (Ноорог → Төлөвлөгөө → Таны хариу → Хийгдэж байна → PR) with the active station marked, and its primary action as a full-width button (Хариулах / Зөвшөөрөх). If nothing waits, the pass says so in one calm line. Below: THE RAIL — a horizontally scrolling row of smaller tickets in progress, each with its live last step and ticking age. Below that: SERVED — a compact stack of recent delivered/failed tickets. Projects, Группүүд, Миний тохиргоо and admin pages sit behind one row at the bottom.

FORM: The Pass (kitchen ticket rail), position 3 on the grounded list; seed key 832587f9. Raised: fixed tabular timer slots (from seven-segment), numbered station strip (from orizuru sequence), band-plus-word state (from cyclorama).

SIGNATURE INTERACTION: tapping a ticket at the pass slides it up into a full sheet (Telegram-native bottom sheet feel, back button closes it) with the plan, each open question as tap choices + "✍️ Өөрөөр" + "🤷 Та шийд", and Зөвшөөрөх / Татгалзах; when the last question is answered or the plan approved, the ticket visibly leaves the pass and joins the rail (one orchestrated motion, respecting reduced motion) with a haptic tick.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
