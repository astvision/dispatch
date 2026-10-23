---
name: data-report
description: Use when building an HTML report from a project's data in a report run.
---

# Data report

A report run is read-only. The requester approved the query explanation; your job is the numbers and one HTML page. Return it as `{"html": "<!doctype html>..."}` in your structured output, or `{"error": "..."}` when a query you need still fails (see Querying). Nothing else is read.

## Workflow

1. Read the approved steps. Query what they say, no more.
2. Run the main query.
3. Run the data checks below.
4. Write the HTML.

## Querying

- Tools: `mcp__dispatch_data__collection-schema`, `count`, `find`, `aggregate`. Look at the schema before the first pipeline; field names and types are not guesses.
- Aggregate on the server (`$match` first, then `$group`). Never pull raw documents to count them yourself.
- Each query stops after 60 s and returns at most 5000 documents. A result of exactly 5000 rows is cut off: narrow the range or group coarser, then say so in the notes.
- A query that errors, times out or cannot reach Mongo: retry it once narrower (a shorter range, or `$match` first). If it still fails, return `{"error": "<the query that failed and why>"}` and no html: never a number you did not get.
- Use `find` only for small lookups, always with a projection.

## Data checks (before writing)

Query at the finest useful grain: daily when the request is per month or per year. Roll up yourself afterwards. Use the daily data only for gaps and partial edges; run the dead-period and spike checks on the requested grain, after rolling up (daily business data dips every weekend). Then look for:

- **Gaps:** missing days or months.
- **Dead periods:** a period at zero or below 10% of the median of the 3 neighbours on each side, while its neighbours are normal. A period with fewer than 3 neighbours on a side, near either end, uses the neighbours that exist.
- **Partial edges:** a first or last period that is only partly covered. Mark it "үргэлжилж буй" when the period is still running.
- **Spikes:** a period above 5x that same neighbour median.

Every finding goes in the warning box with its dates and what it does to the totals, for example "the total for X is lower than the real figure". Report what you see; do not repair or smooth the data.

## Layout, in this order

1. Header: eyebrow with the system name (from the task text or the project; if neither names it, the project name), then the title, then a line with the period, days covered and the source.
2. Warning box, only when there are findings.
3. Hero: the total, with the daily average and the peak below it. Say whether the peak is a day or a month: the finest grain you queried.
4. One card per requested period (year, quarter): its total, a share bar against the grand total, days covered, daily average. A partial period gets a small badge with its date range.
5. Bar chart by month. Partial months are drawn lighter. A tooltip shows the month and value on hover and on tap.
6. Period table with a totals row in `<tfoot>`.
7. Detail table inside `<details>`, closed by default.
8. Notes: what each number is and where it came from, the checks that ran, the limits (5000 rows, partial periods).

Skip the cards or the chart when the request does not need them, never the notes or the period table. Do not invent extra blocks.

## Style

- Define colours as CSS custom properties on `:root`: background, surface, border, text, muted text, bar, lighter partial bar, warning background and border.
- Dark mode: redefine the same properties under `@media (prefers-color-scheme: dark)`.
- System font stack. `font-variant-numeric: tabular-nums` on every number.
- `main` is at most 1040px wide with a 16px side gutter, and the page reads well at 360px: grids collapse to one column, tables and the chart scroll inside their own box, never the page.
- Format numbers with thousands separators (`toLocaleString`).
- Set `<html lang>` to the task's language: `mn` unless the task is written in another.
- Give `<meta charset>`, a viewport meta and a `<title>`.

## Self-contained

No external script, stylesheet, font or image. The chart is inline SVG, drawn by a small inline script from a data array you embed, or written out as static SVG. Prefer the script: it keeps the page short. Escape every string you put in the page.

## Privacy

Aggregates only. Never put names, phone numbers, register numbers, emails or any other raw personal field in the page, in the detail table, or in a tooltip. If the request can only be answered with such fields, return the aggregate and say in the notes what was left out.

## Before you return

- Every number on the page comes from a query you ran. The cards add up to the hero total.
- The warning box lists every finding of the checks, or is absent because there were none.
- The document is complete: `<!doctype html>` through `</html>`.
