# Execution starts a fresh agent session from the approved plan

A task has two agent sessions instead of one.
- **Planning session:** the planning run starts it, and each correction continues it, so a revised plan builds on the investigation.
- **Building session:** the first execution run starts it from the task and the approved plan. Later execution runs of the task, follow-ups and retries, continue it.

Tasks never share a session, whether or not they belong to the same project.

This replaces part of ADR 0006, where approval resumed the planning session "so the analysis context is kept". We measured what keeping it costs on three real tasks (Sonnet 5, Claude Code 2.1.274).
- **Setting up context dominates:** writing context to the prompt cache was 52–82% of every run's cost, and output only 7–28%.
- **The resumed session reused none of the planning run's cache:** an execution that started 5 seconds after its plan read 9.5k tokens from the cache and wrote 26k. Plan mode and auto mode differ in tools and system prompt, which breaks the cached prefix.
- **The investigation rode along:** every execution request carried it, along with any rejected plan versions. Executions started with 36k and 45k tokens of context, where a fresh start has about 25k.

We chose this because the approved plan is what the requester agreed to. Its findings and steps carry what execution needs, and older versions of the plan can only mislead it. On the measured tasks we estimate 20–40% less per execution.

We rejected two alternatives:
- **One session per task, as before:** execution pays again for the whole investigation on every request, with no cache benefit.
- **A fresh session for every run:** a correction would have to investigate the code again, and a follow-up would not know what the execution did.

## Consequences

- Execution may read again some files the planning run already read.
- A follow-up (ADR 0006) continues the building session, and a retry (ADR 0008) continues the session of the phase it retries.
- A task keeps both session ids, so either conversation can be found later.
