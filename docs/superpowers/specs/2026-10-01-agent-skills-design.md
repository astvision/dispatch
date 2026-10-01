# Agent skills (SK): vetted skills in Claude's runs

Status: approved design, 2026-10-01; built on branch agent-skills (see [ADR 0034](../../adr/0034-claude-runs-load-a-vetted-skills-plugin.md)). Builds on the agent sandbox (ADR 0032) and the verify loop (ADR 0033).

## Goal

Claude Code's runs follow proven engineering workflows instead of improvising one each time: the build writes a failing
test first and verifies before it claims done, a fix finds the root cause before it patches, a plan for a bug names the
cause, and the reviewer works from a checklist. The workflows are skills from the official plugin marketplace
(superpowers 6.4.1, MIT), copied into Dispatch, adapted for runs nobody can answer, and loaded into each run as a
plugin. On by default for Claude Code projects, turned off in the config.

In scope: a `dispatch` plugin of five adapted skills bundled in the jar and written to the state dir at startup; a
`skills` setting (instance and project); `--plugin-dir` and the `Skill` tool on plan, execute, fix and review runs; a
note in each run's prompt naming the skills for that run; the plugin read-only inside the sandbox; an `agent.skill` log
line per skill a run invokes; a "Speed matters" paragraph in every plan and execution prompt; ADR 0034.

Out of scope:
- **Codex and Gemini CLI.** Their projects run as today; `--plugin-dir` and the notes are Claude Code's.
- **Skills an admin picks from a marketplace**, and fetching or updating skills at runtime. The set changes only by a
  commit to Dispatch.
- **The assistant and split.** The assistant keeps its own `taskmanager` skill; a split keeps `--disable-slash-commands`.
- **Showing skills in Telegram, the Mini App or `dispatch ui`.** The log says which skills a run used.
- **Tightening what a sandboxed agent may write under `~/.claude`.** A separate spec, done before this one ships.

## Success criteria

1. On a Claude Code project with skills on, every plan run (and correction), execution (and retry and follow-up), fix
   round and review starts with `--plugin-dir <stateDir>/plugins/dispatch` and `Skill` in `--tools`, and its prompt
   carries the note for its kind.
2. In the live test, a real execution in a scratch repository invokes `dispatch:test-driven-development`, seen in the
   stream and logged as `agent.skill`.
3. `skills: off` (instance or project), a Codex or Gemini project, the assistant and a split start with exactly today's
   command line, and with today's prompt apart from the "Speed matters" paragraph of a plan or execution.
4. No bundled skill tells the agent to ask, wait for or get permission from a person.
5. Inside the sandbox the plugin dir is readable and not writable.
6. A job with skills off carries no new field, so a worker from before this version still reads it.
7. Every plan, correction, execution, retry and follow-up prompt, for every agent and with skills on or off, ends its
   rules with the "Speed matters" paragraph for its kind.

Whether skills help is measured, not gated: after about ten real executions with skills on, compare them with the
earlier ones (see "Measuring").

## Configuration

```yaml
skills: on                   # instance default; off = runs start without the dispatch plugin, as before
projects:
  - name: alm
    skills: off              # optional per-project override of the instance default
```

- `skills`: `on` or `off`; missing means `on`; anything else is refused at config load. A project's `skills` overrides
  the instance's, exactly as `loop` does.
- It applies only where the project's agent is Claude Code; on a Codex or Gemini project it is accepted and has no effect.
- It travels to team workers in `Job.Project`. The Mini App's project form and `dispatch project` are not changed in
  this version; the setting is edited in the YAML.

## The skills

The plugin is named `dispatch`, so Claude Code lists its skills as `dispatch:<name>`. Each is copied from superpowers
6.4.1 and adapted; `NOTICE.md` in the plugin names the upstream plugin, version, path and licence, and what was changed
in each file and why.

| Skill | From (superpowers 6.4.1) | Used in | Adapted |
|---|---|---|---|
| `test-driven-development` with `writing-good-tests.md` | `skills/test-driven-development` | execute, fix | "Ask your human partner" and "no exceptions without your human partner's permission" become: an exception only where the approved plan allows it (configuration, generated code, a throwaway prototype), named in the summary |
| `systematic-debugging` with `root-cause-tracing.md`, `defense-in-depth.md`, `condition-based-waiting.md` | `skills/systematic-debugging` | plan (a bug: investigation only), fix | "Discuss with your human partner before attempting more fixes" becomes: stop, keep the change as it is, and say in the summary which root cause is unresolved. The skill-authoring files (`test-pressure-*.md`, `test-academic.md`, `CREATION-LOG.md`) and `find-polluter.sh` are left out |
| `verification-before-completion` | `skills/verification-before-completion` | execute, fix | Copied as it is |
| `receiving-code-review` | `skills/receiving-code-review` | fix after blocking review findings | "Push back to your human partner" becomes: check each finding against the code; do not change code for a finding you have shown is wrong, and say why in the summary |
| `code-reviewer` | `skills/requesting-code-review/code-reviewer.md` | review | Rewritten as a skill for a read-only reviewer: no subagents, no git commands that change anything, judge behaviour the plan is silent on by what a reasonable user expects, and answer only through Dispatch's review schema: `blocking` for what makes the change wrong, unsafe, broken or short of the plan (the review prompt's own rule), `minor` for the rest |

A skill costs its one-line description in every run and its full text (about 1,000 to 2,500 tokens) only in a run that
invokes it. Updating the set is a commit that copies a newer upstream version, re-applies the adaptations and updates
`NOTICE.md`.

## How a run gets them

1. **Startup.** `dispatch run` and `dispatch worker run` write the plugin from the jar's `skills/dispatch/` resources
   (listed in its `files.txt`) to `<stateDir>/plugins/dispatch/`, over whatever an older Dispatch left there, as the
   assistant's home is written. The state dir is hidden from agents, so the copy cannot be changed by one.
2. **Job.** The team machine's `Coordinator` sets `Job.Project.skills = true` when the project's skills are on and its
   agent is Claude Code; otherwise the field is null and left out of the JSON.
3. **Run.** `JobRunner`, on the machine that runs the agent, for a job whose `skills` is true:
   - checks that `<stateDir>/plugins/dispatch/` exists, and fails the run as `SETUP` if not (below);
   - puts it in the new `RunRequest.pluginDirs` of the plan or execution run, every fix round and the review;
   - appends the note for the run's kind to the prompt, as it appends the attachments note: the team machine never
     writes it, so a machine without the plugin is never told to use one.
4. **Agent.** `ClaudeCodeAgent` adds `--plugin-dir <dir>` and `--add-dir <dir>` for each entry of `pluginDirs` and, when
   there is one, adds `Skill` to the `--tools` of a plan, execute or review run. `--add-dir` makes the plugin a working
   directory: without it a plan or review run is denied reading a skill's supporting files, and nobody can answer the
   prompt to allow it. `--setting-sources project,local` and
   `--strict-mcp-config` stay: the owner's own plugins and MCP servers still never load into a run. Codex and Gemini
   ignore `pluginDirs`.
5. **Sandbox.** `SandboxPolicies.forRun` binds every entry of `pluginDirs` read-only, as it binds `readOnlyDirs`.

### The notes

| Run | Note |
|---|---|
| Plan, correction | If the task reports a bug, use the dispatch:systematic-debugging skill to investigate its root cause before you plan; change nothing. Put the root cause in findings. |
| Execution, retry, follow-up | Use the dispatch:test-driven-development skill while you build, and the dispatch:verification-before-completion skill before your summary. |
| Fix after a failing test | Use the dispatch:systematic-debugging skill to find the root cause before you change code, then the dispatch:verification-before-completion skill. |
| Fix after blocking findings | Use the dispatch:receiving-code-review skill: check each finding against the code before you change anything. |
| Review | Use the dispatch:code-reviewer skill, then answer only through the structured output. |

The plan and execution notes go at the end of the prompt. The review note goes before the reviewer's prompt, which ends
with the diff. The fix notes end `Prompts.testFailure` and `Prompts.reviewFindings`, which `VerifyLoop` builds on the
machine that runs the agent; its `Setup` carries the job's `skills`.

## Speed matters

Runs are slow today: a recorded execution took 56 minutes and its test step ran out of time. Every plan and execution
prompt, for every agent and whether skills are on or not, tells the agent to finish fast. The paragraphs end
`PLAN_FORMAT` (plan, correction) and `EXECUTE_RULES` (execution, retry, follow-up), so the team machine writes them as
it writes the rest of the prompt:

- **Plan:** "Speed matters: investigate only what the plan needs. Search for the code you need instead of reading whole
  files or directories, stop once you can name the change, and run a build or test only when it is the quickest way to
  confirm a bug's cause."
- **Execution:** "Speed matters: read only the code your change touches, search instead of reading whole files, run
  only the tests that cover your change rather than the whole suite, and stop when the work is done: no refactoring,
  polish or extras beyond it."

The skills are adapted to agree: upstream test-driven-development ran the project's whole suite even when the task was
scoped, and verification-before-completion held that a partial check proves nothing. Here test-driven-development runs
the tests that cover the changed code, since Dispatch runs the whole test command after the agent, and
verification-before-completion claims only what its run covered. The reviewer does not run the tests.

## Components

| Unit | Change |
|---|---|
| `src/main/resources/skills/dispatch/` | New: `.claude-plugin/plugin.json`, `skills/<name>/…`, `NOTICE.md`, `files.txt` |
| `SkillsPlugin` (core) | New: `install(stateDir)` writes the resources and returns the dir; `dir(stateDir)`. Shares the resource copy with `AssistantHome` rather than repeating it |
| `App`, `WorkerCommand` | Install the plugin at startup |
| `Config`, `ConfigLoader` | `skills` at instance and project level, validated and resolved like `loop`; `Project.skillsOn()` |
| `Job.Project` | `Boolean skills`, left out when null; `skillsOn()` |
| `Coordinator` | Sets `skills` for Claude Code projects with skills on |
| `RunRequest` | `List<Path> pluginDirs`, empty by default; the existing constructors keep it empty |
| `JobRunner`, `VerifyLoop` | Pass the plugin dir and the notes as above; fail as `SETUP` when the dir is missing |
| `Prompts` | The five notes; the "Speed matters" paragraphs in `PLAN_FORMAT` and `EXECUTE_RULES` |
| `ClaudeCodeAgent` | `--plugin-dir` and `--add-dir` per entry; `Skill` in `--tools` for plan, execute and review when there is one |
| `SandboxPolicies` | `pluginDirs` read-only |
| `StreamParser` (Claude) | Logs `agent.skill skill=<name> run=<log base>` for each top-level `Skill` call, and `agent.plugin_errors` when the init event reports any; the log base (`runs/<task>/<seq>[.fix-N\|.review]`) names the task, the run and the step |

## Errors

- **The plugin cannot be written at startup.** Dispatch does not start and names the path and the reason, as for the
  assistant's home.
- **The plugin dir is missing when a run starts.** The run fails as `SETUP`: `skills plugin missing at <dir>; restart
  Dispatch`. Running on would send a prompt that names skills the agent cannot find.
- **Claude Code too old for `--plugin-dir`.** The run fails as `AGENT` with Claude Code's own message. The README names
  the minimum, 2.1.76, from which `--plugin-dir` takes one directory as Dispatch passes it (verified on 2.1.286).
- **The plugin does not load** (a broken manifest). Claude Code reports it in the init event's `plugin_errors`; the parser
  logs it as `agent.plugin_errors` (WARN) with the run, and the run goes on without the skills.
- **The agent does not invoke a skill it was told to.** Nothing fails; the run has no `agent.skill` line for it.
- **A skill would make the agent wait for a person.** Prevented by the adaptations and a test over the bundled text; the
  prompts keep saying that nobody can answer questions.
- **Compatibility.** A job with skills off carries no new field. A job with skills on needs the team machine and its
  workers upgraded together, as for the verify loop: an older worker refuses the unknown field loudly.
- **Built-in skills.** With `Skill` on, Claude Code's own skills are listed too. Some need tools no run has (`schedule`,
  `loop`); the others only add instructions and act through the run's own tools, inside its permission mode and the
  sandbox. Accepted, not denied one by one, since a deny list would trail every Claude Code release.
- **A repository's own `.claude/skills/`** become usable in its runs once `Skill` is on, trusted as its `CLAUDE.md` is.

## Testing

- **Install:** writes every file in `files.txt`, replaces an older copy, and `plugin.json` names the plugin `dispatch`.
- **Bundled text:** no file under `skills/dispatch/skills/` contains "human partner", "your partner", "ask the user",
  "ask for help" or "superpowers:" ("wait for" is legitimate in condition-based-waiting); test-driven-development no
  longer sends the agent to the whole suite, keeps an earlier run's work and names its own exceptions; the reviewer does
  not run the tests; every `dispatch:<name>` in `Prompts` names a skill directory in the plugin.
- **`ClaudeCodeAgent`:** with a plugin dir, `--plugin-dir <dir>` and `Skill` in `--tools` for plan, execute and review;
  without one, the command line is exactly today's for every kind.
- **`SandboxPolicies`:** a plugin dir is among the read-only paths and not among the writable ones.
- **Config:** `on`, `off`, missing (on), an invalid value refused with its path, a project overriding the instance.
- **`Coordinator`:** `skills` is true only for a Claude Code project with skills on, and absent from the JSON otherwise.
- **`JobRunner` and `VerifyLoop`:** each kind gets its note and the plugin dir when on, neither when off; a missing dir
  fails the run as `SETUP` without starting the agent.
- **`StreamParser`:** a `Skill` tool call logs `agent.skill` with the skill's name, a subagent's does not, and an init
  event with `plugin_errors` logs `agent.plugin_errors`.
- **`Prompts`:** plan and correction prompts contain the plan's "Speed matters" paragraph; execution, retry and follow-up
  prompts the execution's; the review, fix, split and assistant prompts contain neither.
- **Live, opt-in** (`DISPATCH_LIVE_CLAUDE=1 ./mvnw test -Dtest=LiveSkillsTest`): an execution in a scratch repository with
  a failing test to fix invokes `dispatch:test-driven-development`.

## Measuring

With `:since` the date skills were deployed, compare executions before and after:

```sql
SELECT CASE WHEN s.started_at >= :since THEN 'skills' ELSE 'before' END AS era,
       COUNT(DISTINCT s.task_id || '/' || s.seq)                         AS executions,
       SUM(s.kind = 'TEST' AND s.round = 1 AND s.outcome = 'PASSED')      AS first_test_green,
       SUM(s.kind = 'FIX')                                               AS fix_rounds,
       SUM(s.kind = 'REVIEW' AND s.outcome = 'FINDINGS')                 AS reviews_with_findings
FROM run_step s
WHERE s.kind IN ('IMPLEMENT', 'TEST', 'FIX', 'REVIEW')
GROUP BY era;
```

`grep 'event=agent.skill' dispatch.log` shows which skills the runs actually used.

## Documentation

- ADR 0034: Claude's runs load a vetted skills plugin bundled with Dispatch; it records the rejected options (whole
  marketplace plugins in unattended runs, forcing one skill by slash command, pasting skill text into the prompts,
  admin-picked marketplace skills).
- README (Mongolian and English): what the skills are, `skills: off`, the minimum Claude Code version.
- SECURITY.md: the plugin is read-only to agents; a repository's own skills are trusted like its `CLAUDE.md`.
- ARCHITECTURE: the plugin in the run's path.
