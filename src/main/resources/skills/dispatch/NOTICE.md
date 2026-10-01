# The dispatch plugin

The skills in this plugin are adapted from superpowers 6.4.1 (https://github.com/obra/superpowers), copyright (c) 2025
Jesse Vincent, under the MIT licence in `LICENSE-superpowers`. Dispatch's runs have nobody to answer a question and a
time limit, so each skill was changed where it sent the agent to a person or past the run's scope; everything else is
the upstream text.

| File | Upstream (superpowers 6.4.1) | Changed |
|---|---|---|
| `skills/test-driven-development/SKILL.md` | same path | The run names its own exceptions (prototypes, generated code, configuration, documentation) in its summary; "other tests" are those covering the changed code, since Dispatch runs the whole suite after the agent; only code written in this run before its test is deleted, an earlier run's is kept; stuck means saying so in the summary |
| `skills/test-driven-development/writing-good-tests.md` | same path | The anecdotes are a reviewer's questions; no reference to the skill-writing skill |
| `skills/systematic-debugging/SKILL.md` | same path | Skill names are `dispatch:`; after three failed fixes the agent stops and reports the unresolved root cause in its summary; the warning signals come from a correction, a review finding or its own reasoning; "ask for help" becomes saying in the summary what it could not work out |
| `skills/systematic-debugging/root-cause-tracing.md` | same path | Bisecting by hand instead of `find-polluter.sh`, which is not shipped |
| `skills/systematic-debugging/defense-in-depth.md` | same path | None |
| `skills/systematic-debugging/condition-based-waiting.md` | same path | No pointer to `condition-based-waiting-example.ts`, which is not shipped |
| `skills/verification-before-completion/SKILL.md` | same path | A partial check proves what it ran: claim no more, rather than run everything |
| `skills/receiving-code-review/SKILL.md` | same path | Rewritten for findings that come back in a run: verify each, fix the real ones, leave the wrong ones and say why in the summary |
| `skills/code-reviewer/SKILL.md` | `skills/requesting-code-review/code-reviewer.md` | Rewritten as a skill for a read-only reviewer that answers with Dispatch's review schema (`blocking` or `minor`) and leaves the tests to Dispatch |

To update: copy the newer upstream files, re-apply these changes, update the version here and in `plugin.json`'s
description, and review the diff.
