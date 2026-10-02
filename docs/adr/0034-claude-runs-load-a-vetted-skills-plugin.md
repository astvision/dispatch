# Claude's runs load a vetted skills plugin

Builds on ADR 0032 (the sandbox) and ADR 0033 (the verify loop).

Claude Code's plan, execute, fix and review runs load `dispatch`, a plugin of skills that ship with Dispatch:
test-driven-development, systematic-debugging, verification-before-completion, receiving-code-review, code-reviewer and,
since 2026-10-02, ponytail. The first five are adapted from superpowers 6.4.1 (MIT): wherever a skill sent the agent to a person, it now decides, follows the
approved plan, and says what it decided in its summary, because nobody can answer during a run. They also keep to the
run's scope: the agent runs the tests that cover its change, not the whole suite Dispatch runs after it. `NOTICE.md` in
the plugin lists every change.

The jar carries the plugin. A personal instance and a member's worker write it to `<stateDir>/plugins/dispatch` at
startup, replacing any older copy. The team machine marks a Claude Code project's job with `skills`. The machine that
runs the agent passes `--plugin-dir`, and `--add-dir` so that plan and review runs may read the skills' supporting files,
adds `Skill` to `--tools`, and appends a note naming the skills for that call:
root-cause investigation for a plan, TDD and verification for an execution, systematic debugging for a fix after red
tests, receiving-code-review for a fix after blocking findings, and the reviewer checklist for the review. The state dir
is hidden from agents, and the sandbox binds the plugin back read-only. `--setting-sources project,local` stays, so the
owner's own plugins never load into a run. `skills: off`, on the instance or a project, gives the runs without skills.

Every plan and execution prompt also says that speed matters: investigate or read only what the work needs, search
instead of reading whole files, run only the tests that cover the change, and stop when it is done.

**Ponytail by default** (amended 2026-10-02). `ponytail` is adapted from ponytail 4.9.0 (MIT, `LICENSE-ponytail`) and
fixed at its `full` level: the simplest change that works (YAGNI, what the codebase already has, the standard library,
platform features, the shortest diff once the problem is understood), with its levels, commands and questions to a
person removed, and tests left to test-driven-development. The plan, execution and test-fix notes tell the agent to load
it first: without that, runs on small tasks never loaded it. The reviewer's checklist adds ponytail-review's
over-engineering check (delete, stdlib, native, yagni, shrink), whose findings are always `minor`, so taste never holds up
delivery. An execution's summary gives one `skipped: <what>, add when <when>` line for each thing it left out: without
that rule, what was left out hid among the assumptions. Measured with skill-creator on three small tasks (Sonnet, each
with and without): equally lean code either way, runs a few seconds faster, and the skipped lines only once the summary
rule asked for them.

We chose this over:
- **Loading whole marketplace plugins.** Their skills assume a person in the loop (a brainstorming hard gate, "ask your
  human partner"), and their hooks would run in unattended runs.
- **Forcing one skill with a slash command** (`/dispatch:<skill>` at the start of the prompt). It is deterministic, but it
  allows only one skill per run and turns Dispatch's whole prompt into that skill's arguments.
- **Pasting the skill text into Dispatch's prompts.** It would work for every agent, but every run would pay for the full
  text, and the skills' supporting files would be lost.
- **Skills an admin picks from a marketplace.** Each admin would vet what they add, and Dispatch would need download,
  pinning and caching. The set changes only by a commit to Dispatch.

Consequences: a skill is listed in every run and costs its full text only where it is invoked. The log says which skills
a run used (`agent.skill`) and when the plugin did not load (`agent.plugin_errors`). With `Skill` on, Claude Code's
built-in skills are listed too and act only through the run's own tools, and a repository's `.claude/skills/` are
trusted like its `CLAUDE.md`. Codex and Gemini CLI runs are unchanged. Claude Code 2.1.76 or later is needed. A job with
skills on needs the team machine and its workers upgraded together.
