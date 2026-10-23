# A report is queried read-only on prod, after its plan is approved

Amends ADR 0038 (a plan run returns `plan` or `answer`): on a project with data, it may also return `report`. Amends ADR
0036: a run given a project's data loads no MCP server of the owner's.

A project on a personal instance may name its production MongoDB in a `data` block: the SSH host to tunnel through, Mongo's
address as that host sees it, the database, and `uri: ${NAME}`, the read-only user's connection string, which lives only in
`dispatch.env`. For each plan or report run on such a project, Dispatch itself (outside the sandbox, which in a sandboxed run hides `~/.ssh`)
opens `ssh -N -L 127.0.0.1:<free port>:<mongo> <host>`, waits up to 20 s for the port, runs the agent, and closes the tunnel
however the run ends. The URI's hosts become `127.0.0.1:<port>`; its `replicaSet` is dropped and `directConnection=true`
added, since one forwarded port reaches one member and discovery would dial hosts the tunnel cannot reach. Reads go to the
host `mongo` names, so point it at a secondary to keep load off the primary.

The run reaches Mongo through `mongodb-mcp-server`, pinned to 2.1.2 and named `dispatch_data`, in `--strict-mcp-config` with
nothing else loaded. Its connection string reaches the server as `${DISPATCH_DATA_URI}`, expanded from the run's environment,
never on a command line. The `dispatch.env` variable that holds the real URI is kept out of every agent's environment, so
no other run carries it in its environment. Every query has a server-side `maxTimeMS` of 60 s. There are two levels:

| Run | Tools |
|-----|-------|
| PLAN on a data project | `list-databases`, `list-collections`, `collection-indexes`, `collection-schema` (field types, no values), `collection-storage-size`, `count`, `db-stats` |
| REPORT, after the requester's tap | those, plus `find`, `aggregate` and `explain`, at most 5000 documents a query |

A plan run told about the data (`Prompts.DATA_NOTE`) returns `result: "report"` when the task asks for numbers from it: its
steps explain the query in a few lines (collection, filter, date range, grouping) for the requester to approve. The plan
message says it is a report and its button is 📊 **Тайлан гаргах**. Approving starts a `REPORT` run, which resumes the planning
session in the task's worktree, follows the `dispatch:data-report` skill (query, check the data for gaps, dead periods,
partial edges and spikes, then one self-contained page) and returns `{"html": …}`, or `{"error": …}` naming a query that
still failed after one narrower retry, which fails the run with that reason. The task then ends as an answered task
does: the requester gets `answer-<task>.html`. A reply plans again. A task that asks about the code or for a change on the
same project is answered or planned as before.

**The gate is enforced, not asked for.** Dispatch reads Claude Code's init event and stops the run when:
- the data server offers any tool outside its level (`data_tools_unexpected`), as a newer server or a changed disabled list
  would;
- the data server is missing or not connected (`data_unreachable`): with `--strict-mcp-config` Dispatch chose the servers,
  so a run without its data can only guess.

A plan run that called any `dispatch_data` tool may not end in `answer`, since an answer goes to the chat unapproved; a
resumed plan session counts as having called one when the task ran a report or its plan proposes one (`Job.dataSeen`),
because what that session read is still in its context. A reply to an answered code question may be answered again.
Such a run fails as an invalid plan.

**Read-only twice.** The Mongo user is the hard boundary: `dispatch check` opens the tunnel, asks Mongo for the user's
privileges (`connectionStatus`, `showPrivileges`) and fails a user that holds any action beyond reading, naming it: only
the built-in `read` role's actions, `listDatabases` and `planCacheRead` pass. The
server's own read-only mode (`MDB_MCP_READ_ONLY=true`) is the second layer: it lists no write tool. Without a sandbox
(macOS, `sandbox: off`, no bwrap), where a data block is still accepted, any agent run can read the owner's SSH keys and
`dispatch.env` from disk, so only the read-only Mongo user bounds it.

**Guardrails beside the gate.** A data run also gets `--disallowedTools` rules for `mongosh`, `mongo`, `mongoexport`,
`mongodump`, `printenv` and `env` in Bash. They are guardrails like ADR 0009's, not a boundary: Claude Code gives the MCP
server and Bash the same environment and sandbox, so the URI is in the agent's environment and a deliberately rogue shell
command could read it and query Mongo directly, with the read-only user's rights. The gate bounds cost and approval on the
normal path; the read-only user bounds what anything can do.

## The probe (2026-10-23, Claude Code 2.1.309, mongodb-mcp-server 2.1.2, mongo:7.0)

The spec asked for a probe before choosing MCP over a Dispatch-owned wrapper around `mongosh`. All three questions held:
1. MCP tools can be called in `--permission-mode plan --permission-prompts none --tools Read,Bash`.
2. `MDB_MCP_DISABLED_TOOLS` removes tools from the init event's list, so a level is a list Dispatch checks. Disabling
   `aggregate` alone is not enough: `export` and `explain` also run a pipeline, and `find` returns raw values.
3. `MDB_MCP_MAX_TIME_MS` reaches `find`, `count` and `aggregate`'s read path as `maxTimeMS`.

Also found: the server's default of 100 documents a query is too few for daily counts over years, so REPORT raises it to
5000; and on a cold npx cache the server's first download outlasts Claude Code's 30 s MCP connect timeout, so data runs set
`MCP_TIMEOUT=120000` and `dispatch check` warms the cache.

The live test (`DISPATCH_LIVE_DATA=1 ./mvnw test -Dtest=LiveDataReportTest`) runs the whole path against Mongo 7 in docker
behind a user-mode `sshd`: a "visits per year" task gets a report plan that calls no `aggregate`, its report warns about a
month planted at zero, a bug fix on the same project gets a code plan, and the read-only user's `insertOne` is refused.

We chose this over:
- **A wrapper around `mongosh`** allowed through one Bash rule. More code to own, and the probe showed the server does what
  it would.
- **Answering numbers straight from the plan run.** Prod would be queried before anyone agreed to the cost, and the numbers
  would reach the chat unchecked.
- **The team bot reaching prod.** The SSH access and the credentials are the owner's, on the owner's machine.

Consequences: data reports are personal-only. A `data` block on a team instance, on a Codex or Gemini CLI project, or on
Windows fails config load; `mongodb+srv://` is refused because SRV cannot go through one forwarded port. `REPORT` never
crosses the worker wire, and `Job.dataSeen` is set only on a data project's plan and left out of the JSON otherwise, so
the worker protocol stays 4. A data run loads none of the owner's MCP servers (their plugins'
skills still load). The server version is pinned: upgrading it means probing its tool list again and updating the levels,
or the run stops on the new tools. A report costs up to the project's execution budget and timeout, with planning's model.
The schema tools show field types, not values, but `count` with a filter can still reveal a fact about a record before the
tap; the read-only user and the 60 s limit bound that.

Rollback: a jar from before migration 038 cannot read `REPORT` runs or report plans, so finish or cancel every report task
before rolling back.
