# Data reports — design

Beyond Code sub-project #2 (data questions), narrowed to what ZB needs now: a report built from a project's prod data.

## Why

A client asked, through the team group, how many times a client's public website was visited in total and in each of 2024, 2025
and 2026. Nothing needed building. ZB opened an SSH tunnel from the laptop, ran a Mongo aggregation on prod with a
read-only user and hand-built an HTML report (kept outside this repo). That
report is the model for this feature. While building it, ZB spotted a hole in the data: GA4 recorded almost nothing
for about ten weeks in early 2025. The report carries a warning about it.

Dispatch can't do this today:
- A run sees only the repo. The sandbox hides `~/.ssh`, and no project has a way to reach its database.
- A task that asks for numbers would end in an answer containing a query for ZB to run, or in a plan to write code.
  Neither is what the client wants.

ZB wants the bot to recognize "make me a report" and keep it apart from "fix this bug". It should produce a report
like that one when one is needed.

## What ZB decided

- **Who:** only ZB, the owner. Teammates can't request data reports for now.
- **Flow:** before a query touches prod, the bot sends a short explanation of it: which collection, what filter, and
  what it counts or groups. ZB taps approve, then the query runs and the report is built.
- **Schema check before approval:** the plan step may look at prod's shape before ZB approves: list collections and
  indexes, sample a few documents, run small counts. The real aggregation runs only after the tap.
- **The gate is enforced:** "nothing heavy runs on prod before my tap" is enforced by Dispatch, not just asked for in
  the prompt.
- **Output:** always a complete HTML page in the style of that visits report. It's in Mongolian, with a headline
  total, breakdown cards, a chart, full tables, notes saying what each number means, and a warning box for any data
  problems.
- **Data checks:** the run looks for gaps, days at zero, periods cut short at the start or end, and spikes, and says
  so in the report. ZB found the GA gap in the data itself; the bot must do the same.
- **Read-only:** the Mongo user is read-only.
- **Tunnel:** the bot opens the SSH tunnel itself, per project, from settings in the config.
- **Skill:** report runs load a skill made for building reports.

## Goals

1. A request for numbers or a report from a project's data is recognized as a report. A request to change code still
   gets a plan and, after approval, a PR. A question about the code still gets an answer.
2. The plan step sends ZB a short explanation of the query. After approval, a run executes it read-only on prod
   and returns one self-contained `.html` file in Telegram.
3. The report checks its own data and states any problem, with the dates it affects and how the totals are off.
4. Prod can't be written to through any path.

## Non-goals

- Teammates requesting reports, and the team bot reaching prod.
- Databases other than MongoDB.
- CSV, XLSX or PDF output.
- Scheduled or recurring reports (sub-project #3).
- Codex and Gemini as report agents. Report runs use Claude Code only.
- Remote workers. The run happens on the machine that has the SSH access, the personal bot's own machine.

## Anti-goals (failure even if it technically works)

- Any write to a prod database.
- Raw personal records in a report, such as names, phone numbers or register numbers. Reports carry aggregates.
- Client data or prod hostnames in this public repo. That includes the visits report: the skill borrows its style,
  never its numbers.
- A bug-fix or feature request routed to a report, or a report request turned into a code change.
- Heavy queries that slow prod down.

## How it works

### 1. Config: a project's data source

A project in `dispatch.yaml` gains an optional `data` block. Without it, nothing changes: a data question still gets
today's answer.

```yaml
projects:
  - name: webapp
    data:
      ssh: webapp-prod            # a Host from the owner's ~/.ssh/config
      mongo: 127.0.0.1:27017      # address as seen from the SSH host
      database: webapp
      uri: ${WEBAPP_MONGO_RO_URI} # read-only user's connection string, user and password only; the host is rewritten
```

- The connection string is a secret. It lives in `dispatch.env` beside the config, where Dispatch's other secrets are
  (`SecretsFile`), and `dispatch.yaml` names it with `${VAR}`, so `ConfigLoader` gains `${VAR}` expansion backed by that
  file. The `Redactor` masks it, it reaches the run only through the environment (as `OwnerPlugins` does with
  `DISPATCH_MCP_n`), and it's never written to the repo or to logs. *(My call.)*
- The tunnel rewrites the URI's host to `127.0.0.1:<port>` and adds `readPreference=secondaryPreferred`, which reads
  from a secondary when prod is a replica set and is ignored on a standalone server. *(My call.)*
- `dispatch check` validates the block: SSH host reachable, tunnel opens, `ping` succeeds, and the user has no write
  role (`connectionStatus` with `showPrivileges`). A user that can write is refused, so the Mongo user is the first
  read-only layer.
- The `data` block is honored only on a personal instance. A team instance that has one fails `dispatch check`.

### 2. The tunnel: opened by Dispatch, outside the sandbox

The sandbox hides `~/.ssh`, and it should keep doing so. For each run that needs data, Dispatch, running as the owner
outside the sandbox, starts `ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:<free port>:<mongo> <ssh>`. It waits until
the port accepts connections, starts the run, and stops ssh when the run ends, including on cancel and crash. The
sandbox shares the host network, so the run reaches `127.0.0.1:<port>`. A tunnel that fails to open fails the run with
a plain message naming the SSH host. The run never starts against nothing.

### 3. The run reaches Mongo through a read-only MCP server

The run loads the MongoDB MCP server (`mongodb-mcp-server`), pointed at the tunnel port, in its `--readOnly` mode. That
is the second read-only layer. It goes through the same per-run inline `--mcp-config` the owner-plugins work built
(Loadout), and the connection string travels in the environment. Only runs for a project with a `data` block, requested
by the owner, get it.

**The approval gate is enforced by Dispatch, not just asked for in the prompt (ZB's decision).** Two levels of access:

| Run | Mongo access |
|-----|--------------|
| PLAN on a project with `data`, any request, bug fixes included | **Schema view only:** list collections, indexes, a schema/sample tool, `count`, `find` with a small limit. No `aggregate`, no unbounded `find`. Enforced by which tools are loaded or allowed (`--allowedTools` for MCP tool names, or a wrapper that refuses), not by the prompt. |
| REPORT, after the tap | Full read-only: everything the read-only MCP server or user allows. |

- A PLAN run that called any data tool can't end in `answer`. Dispatch sees the tool calls in the stream, rejects that
  result and fails the run as an invalid plan, so prod numbers can't reach ZB without a tap. A code question on a
  `data` project, where the run never touches the data tools, is answered as today. *(The rejection is my call:
  without it, small counts during planning could be answered straight back with no approval.)*
- Schema samples are cut down to field names and types before they reach the agent's context, so no prod values land in
  the run log, the session transcript or the plan message. If the MCP server's tools can't do that, the schema-view tool
  is Dispatch's own small wrapper. *(My call.)*

**Hard time limit.** Every query, at both levels, runs with a server-side `maxTimeMS` (default 60 s; a limit the plan
run's small counts reach is a sign it's doing too much). The prompt asking for it isn't enough: the path chosen must
enforce it. *(My call.)*

**Open risk, probe first.** Before building anything else, the builder probes with a local throwaway Mongo:
1. Can MCP tools be called at all in a `--permission-mode plan` run with `--permission-prompts none` and
   `--tools Read,Bash`?
2. Can `--allowedTools` (or plain loading) restrict a run to only the schema-view tools?
3. Does `mongodb-mcp-server` let a server-side `maxTimeMS` be forced on `aggregate`, `find` and `count`?

If any answer is no, the fallback is a small Dispatch-owned wrapper command around `mongosh --quiet --eval` (or the Java
driver). It takes a level (`schema` | `full`), refuses what that level forbids, forces `maxTimeMS`, and is allowed
through Bash by one exact `--allowedTools` rule. The builder picks MCP only if all three hold, and records the probe
results in the ADR.

### 4. Recognizing a report: a third plan result

The plan schema's `result` gains `report`, next to `plan` and `answer` (ADR 0038). PLAN_FORMAT says:

- The task asks for numbers, statistics or a report drawn from the project's data, and this run has data access →
  `report`.
- It only asks about the code → `answer`, as today.
- It asks for any change → `plan`, as today.
- When the request is ambiguous ("why are the visit counts wrong?" could be a bug), choose `plan` or `answer`, never
  `report`. A wrong report costs a prod query; a wrong plan only costs a tap.

A run without data access never sees the `report` option, so a project without `data` behaves exactly as today.

A `report` plan carries:
- **understanding:** what ZB asked for, in a sentence.
- **steps:** the short query explanation ZB approves. It gives the collection, the filter (date range, conditions), the
  grouping and the fields counted, and any data problem the schema check already found ("analytics records stop for ten
  weeks in early 2025").
- **findings:** what the schema check saw, such as collection names and the date field used.

The Telegram plan message for a report says it's a report, shows those steps, and keeps the usual approve / cancel
buttons. Approve doesn't mean "build code" here, so the button label should say what happens ("Тайлан гаргах" or
similar; the builder picks the exact wording to match existing labels).

### 5. The report run

Approving a report plan starts a new run kind, `REPORT`, instead of `EXECUTE`. Execution, the verify loop, the
worktree, commits and PR delivery are all skipped: nothing in the repo changes.

`REPORT` is read-only like `PLAN`. It has Read, Bash, Skill and the data MCP server, and it resumes the plan session so
it keeps what the schema check learned. Its prompt:
- Answer what was approved, nothing broader. ZB approves the prose explanation, not literal pipelines (ZB asked for "a
  brief explanation of the query"), so the REPORT run writes its own queries. That includes the finer ones the data
  checks need, such as daily counts for gaps. Filter early and use indexes. The forced `maxTimeMS` bounds the cost.
- Check the data before trusting it (see the skill).
- Return the whole report as one self-contained HTML document through a structured output, in the same field shape as
  an answer.

The task then ends the way an answered task does today. ANSWER_READY sends `answer-<task>.html`
(`Renderer.isHtmlDocument`), and the group, if any, gets the short completed line. A reply to the report continues the
same task the way an answer follow-up does: a new plan run, a new query explanation, approve, a new report. *(My call,
not asked; it reuses the answer follow-up path.)*

### 6. The report skill

Dispatch's own skills plugin gains `data-report`. Report runs (plan and report) load it whenever the project has a
`data` block, whatever the project's `skills` setting is. It's written from the visits report's structure and style,
with none of its data:

- **Layout:** title with system name and period, data-warning box, hero total, one card per period, monthly bar
  chart, period table, and detail table in a `<details>`.
- **Notes:** what each number is (for example "visits = page views") and where it came from.
- **Language:** Mongolian, `lang="mn"`, numbers with thousands separators.
- **Self-contained:** inline CSS and inline SVG chart, no external scripts or fonts (Telegram opens it offline). Light
  and dark via `prefers-color-scheme`, readable at phone width.
- **Data checks, done before writing:** gaps (missing days or months), days or months at or near zero next to normal
  ones, a first or last period that is only partly covered (marked "үргэлжилж буй"), outlier spikes. Any finding goes
  in the warning box with dates and its effect on totals.
- **Privacy:** no raw personal fields in the output.

The `dataviz` skill ZB knows is a claude.ai built-in and isn't on disk, so this skill borrows its principles rather
than loading it.

## Constraints

- Repo is public: no client names in examples beyond "a visits report", no hostnames, no data. Fake connection
  strings are split so scanners don't flag them (see open-source secret hygiene).
- Old stored plans have no `report` result. Reading them must still work, as with earlier plan fields.
- Worker protocol: report runs never go to a remote worker. A team instance and workers are unaffected. If the plan
  schema change reaches workers, follow ADR 0039's equal-protocol rule.
- One task, one tunnel. Concurrent report tasks each open their own on a free port.
- New ADR, 0043 (0042 is held by the unmerged stepwise branch).

## Left to the builder

- Names: run kind, config keys, button and message texts (Mongolian, matching existing ones).
- Exactly how REPORT's output reaches ANSWER_READY: a structured `answer` field, or a file the run writes into its run
  dir. Pick the one that reuses the most of the answers path.
- How a timed-out query is reported.
- How the plan message labels a report.
- MCP vs. the Dispatch-owned wrapper, decided by the three probe questions in section 3.

## Done when

- A local live test against a throwaway Mongo behind a real `ssh -L` to localhost works end to end. A task "how many
  visits per year" gets a report plan with a query explanation, approve gives an `.html` file, and the file has a
  warning for a gap planted in the test data.
- The same project, asked to fix a bug, gets a normal code plan.
- A write attempt (`insertOne` through the MCP server, and a write with the URI) is refused by both layers in a test.
- `dispatch check` refuses a `data` block whose user can write.
- A PLAN run on a `data` project can't call `aggregate` or an unbounded `find`. A PLAN run that touched data and
  returned `answer` is rejected. Both are covered by tests.
- No prod values (sampled documents) appear in the run log or the plan message.
- CI green on all OSes. The tunnel code is skipped where `ssh` is missing.
