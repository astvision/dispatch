# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Primarily one developer running their own Dispatch bot (a personal instance), who gives development tasks from Telegram and follows them from a phone. Teams are supported (several members, admins), but the Mini App is optimised for the single owner.

## Product Purpose

Dispatch turns a development task written in Telegram into a plan by the project's agent — Claude Code, Codex or Gemini CLI — in a git worktree, and, once the requester approves the plan, into a draft pull request. The Telegram Mini App (opened with `/manage`) is where the owner watches that work live and manages the setup without leaving Telegram. Success: the owner sees at a glance what is running, what waits for their answer or approval, and what is done, and can act on it in one or two taps.

## Positioning

The agent runs on the owner's own machine with their own agent login (Claude Code, Codex or Gemini CLI), clones and `gh`; the human decision point is a single plan approval (answering the plan's questions first). The Mini App mirrors that loop inside Telegram.

## Operating Context

- Used inside Telegram's Mini App webview, mostly on a phone, in Telegram's light or dark theme.
- Tasks come from the private chat with the bot, or from a linked group (`/task@bot`, a mention of the bot, or a mention of a developer); group messages get reactions or a short line per the person's choice.
- A task moves: draft (project, priority) → planning → awaiting approval (open questions answered with buttons) → executing → delivered (draft PR) / failed / rejected / cancelled. Follow-ups and retries continue a finished task.
- The owner also manages projects (base branch, alias, model and effort per phase), linked groups, people, settings, logs and the service's overview.

## Capabilities and Constraints

- Frontend: React 19 + Ant Design 6 + Vite (ui/), served by `dispatch run` behind Telegram's signed launch data; the same bundle serves the browser `dispatch ui`, which keeps its own sidebar layout.
- Colours follow Telegram's theme parameters (ui/src/theme.ts); nothing may be loaded from telegram.org.
- Mini App copy is Mongolian; model/effort names stay English.
- Data available to the Mini App: /api/me, /api/projects, /api/tasks/list, timeline, cancel, retry, the /api/manage/* routes (admins), /api/me/prefs (group acknowledgement preference).
- Decided (2026-09-24): the Mini App lets the requester act, not just watch — answer a plan's questions (tap a choice, write, or "you decide"), approve or reject — with the same rules as the chat buttons; the chat keeps working.
- The owner rejects, for this surface: a generic admin dashboard look (tables, card grids, sidebar), playful or gimmicky decoration, anything slow or janky inside Telegram on a phone, and anything that ignores Telegram's theme, back button and haptics.

## Brand Commitments

Name "Dispatch", bot display name "dispatcher" with its robot avatar. No other committed visual identity.

## Product Principles

1. Live status first: what needs me now outranks what is configured.
2. One decision point: surface questions and approvals prominently; everything else is observation.
3. Telegram-native: respect the host's theme, back button and gestures; never feel like a web page inside a frame.
4. Dense but calm: one owner, many tasks — scan in seconds, no chrome for its own sake.
5. Private by default: plans, costs and agent activity are the owner's; groups see headlines only.

## Accessibility & Inclusion

Readable in both Telegram themes; tap targets suitable for a phone; nothing conveyed by colour alone.
