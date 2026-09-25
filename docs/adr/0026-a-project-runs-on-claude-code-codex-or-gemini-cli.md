# A project runs on Claude Code, Codex or Gemini CLI

Each project names the CLI its tasks run on: `agent: claude-code`, `codex` or `gemini`, with that CLI's command under
`agents:` once per instance (or `claudeCommand`, `codexCommand`, `geminiCommand` in a member's `worker.yaml`). Planning,
corrections, execution and follow-ups run on the project's agent; everything around them — the worktree, the plan and
its questions, approval, delivery as a draft pull request — stays Dispatch's and is the same for every agent. Splitting a
message with ✂️ (ADR 0013) and the assistant (A-1) stay on Claude Code: they rely on its structured output and tools, and
without Claude Code configured they are simply not offered.

Each adapter wraps one CLI run headless with the prompt on stdin, and maps the run kinds onto that CLI's own controls:

| | Planning (read-only, answers in the plan's JSON shape) | Execution |
|---|---|---|
| Claude Code | `--permission-mode plan`, `--json-schema` | auto mode plus deny rules (ADR 0009) |
| Codex | `exec --json`, `sandbox_mode="read-only"`, `--output-schema` (a copy of the plan schema without length and count limits, which strict schemas refuse; `Plan.parse` enforces them) | `sandbox_mode="danger-full-access"` |
| Gemini CLI | `--approval-mode default`, in which headless Gemini CLI denies every edit and shell command; the schema is appended to the prompt, since the CLI has no schema flag | `--approval-mode yolo` |

Codex and Gemini CLI both run with no one to approve anything (`approval_policy="never"`, headless), without the user's
own Codex config (`--ignore-user-config`, as Claude Code loads project settings only), and Gemini CLI with
`--skip-trust`: in a folder it does not trust it would otherwise turn yolo back into the default mode, where no edit
happens and the run still succeeds.

Sessions continue across a task's runs as they do for Claude Code (ADR 0017). Gemini CLI takes the session id Dispatch
chooses (`--session-id`, then `--resume`). Codex names its own threads, so the thread each Dispatch session started is
kept in `<stateDir>/agent-sessions/codex/<session>`, and a resume that finds none fails with that reason instead of
starting a fresh session that would not know the plan.

We chose the same trust as Claude Code's execution for the other agents — no sandbox, the OS user as the boundary (ADR
0009) — because a real task builds and tests: Maven, Gradle and npm write caches under the home directory, which Codex's
workspace-write sandbox refuses. Delivery stays Dispatch's (ADR 0007): the execution prompt tells every agent not to
commit, push or open pull requests, which only Claude Code also enforces with deny rules.

We rejected:
- **One agent per instance.** A team moves projects between agents one at a time, and tries a new one on one project.
- **Codex's workspace-write sandbox for execution.** Safer on paper, but builds fail on caches outside the worktree.
- **Gemini CLI's plan approval mode.** Headless, it switches to yolo and carries the plan out as soon as it is made,
  before anyone approved it.

## Consequences

- `ConfigLoader` accepts the three types and checks effort per agent: Claude Code's five levels, Codex's `low` to
  `xhigh`, none for Gemini CLI.
- Only Claude Code reports a run's cost and enforces the budget per run; Codex and Gemini CLI report tokens, so their
  cost shows as "—" and their runs are bounded by the run timeout only.
- `dispatch project add --agent`, and the Mini App's **Агент** row, put a project on another agent and add that agent's
  command when the config has none; switching drops the project's model and effort, which name the old agent's.
- `dispatch check` runs each agent's `--version`, names what to install, and warns when Codex is not logged in.
- A member's computer without the project's agent fails the run with what to add. Its readiness (T-1) still reports
  Claude Code only; per-agent readiness is left for when a team runs Codex or Gemini CLI on members' computers.
- The Codex and Gemini CLI adapters were built from their published event formats (Codex 0.155.1, Gemini CLI 0.61) and
  a recorded Codex failure; a successful live run of each is still to be recorded.
