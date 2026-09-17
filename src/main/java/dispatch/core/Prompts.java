package dispatch.core;

import dispatch.domain.Task;

/** What agents are told. Dispatch frames the task; the repository's own CLAUDE.md still applies on top. */
final class Prompts {

    private Prompts() {
    }

    /**
     * The language instruction is explicit on purpose: a recorded run given "the same language as the task" answered an
     * English task in Dutch.
     */
    static String plan(Task task) {
        return """
                You are planning a development task for this repository. Investigate the code read-only and do not modify \
                anything. Nobody can answer questions while you work, so do not ask for confirmation or permission.

                Task #%d from %s:
                <task>
                %s
                </task>

                Return the plan as JSON matching the provided schema:
                - understanding: what the task asks for, in your own words
                - findings: relevant facts from the code; for a bug, its root cause
                - steps: the concrete changes you would make, in order
                - risks: what could break or needs attention
                - questions: questions that must be answered before implementing; empty if none

                Language: write all text in the natural language used inside <task> (a task written in Mongolian gets a \
                Mongolian plan, a task written in English gets an English plan). Keep code identifiers, file paths and \
                commands unchanged.
                """.formatted(task.id(), task.requester().name(), task.description());
    }
}
