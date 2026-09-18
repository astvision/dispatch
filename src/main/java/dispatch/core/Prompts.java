package dispatch.core;

import dispatch.domain.Run;
import dispatch.domain.Task;

/** What agents are told. Dispatch frames the task; the repository's own CLAUDE.md still applies on top. */
final class Prompts {

    /**
     * The language rule is explicit on purpose: a recorded run given "the same language as the task" answered an English
     * task in Dutch. Questions are kept for what cannot be assumed, because a plan with open questions cannot be approved.
     * Plan mode also tells the agent to save its plan to a file, and recorded runs then wrote each plan twice.
     */
    private static final String PLAN_FORMAT = """
            Return the plan only as JSON matching the provided schema; do not write it to a plan file or anywhere else. \
            Its fields:
            - understanding: what the task asks for, in your own words
            - findings: relevant facts from the code; for a bug, its root cause
            - steps: the concrete changes you would make, in order
            - risks: what could break or needs attention, including every assumption you made
            - questions: only questions whose wrong answer would make the change wrong or harmful; otherwise make a \
            reasonable assumption, list it under risks, and leave questions empty

            Language: write all text in the natural language used inside <task> (a task written in Mongolian gets a \
            Mongolian plan, a task written in English gets an English plan). Keep code identifiers, file paths and \
            commands unchanged.
            """;

    private static final String EXECUTE_RULES = """
            Rules:
            - Do not commit, push, create branches or open pull requests; Dispatch delivers your changes.
            - Keep the change focused on the approved plan.
            - Run the relevant tests if they are quick to run.
            - Finish with a short plain-text summary (no Markdown) of what you changed and the test results.

            Language: write the summary in the natural language used inside <task> (a task written in Mongolian gets a \
            Mongolian summary, a task written in English gets an English summary). Keep code identifiers, file paths \
            and commands unchanged.
            """;

    private Prompts() {
    }

    static String plan(Task task) {
        return """
                You are planning a development task for this repository. Investigate the code read-only and do not modify \
                anything. Nobody can answer questions while you work, so do not ask for confirmation or permission.

                Task #%d from %s:
                <task>
                %s
                </task>

                """.formatted(task.id(), task.requester().name(), task.description()) + PLAN_FORMAT;
    }

    /** @param run the planning run whose instruction is the member's reply to the previous plan */
    static String correction(Task task, Run run) {
        return """
                The team replied to your plan for this task with a correction. Revise the plan accordingly: investigate \
                the code read-only again where needed and do not modify anything. Nobody can answer questions while you \
                work, so do not ask for confirmation or permission.

                Task #%d from %s:
                <task>
                %s
                </task>

                Correction from %s:
                <correction>
                %s
                </correction>

                Return the complete revised plan, not only what changed.
                """.formatted(task.id(), task.requester().name(), task.description(), run.requestedByName(), run.instruction())
                + PLAN_FORMAT;
    }

    /** @param planJson the approved plan, as stored on the execution run */
    static String execute(Task task, String planJson) {
        return """
                The team approved the plan below for this task. Implement it now in this repository. Nobody can answer \
                questions while you work: where something is unclear, make the most reasonable choice and say so in your \
                summary.

                Task #%d from %s:
                <task>
                %s
                </task>

                Approved plan:
                <plan>
                %s
                </plan>

                """.formatted(task.id(), task.requester().name(), task.description(), planJson) + EXECUTE_RULES;
    }

    /**
     * A retried execution continues its session, which already has the task and the plan; it needs to know that it was cut
     * short, and why, so it checks what it already changed instead of starting over.
     *
     * @param instruction what the failed run was asked to do: the approved plan, or a follow-up
     */
    static String retry(Task task, String instruction) {
        return """
                Your previous run on task #%d stopped before it finished: %s. A team member asked you to try again. Your \
                changes so far are still in this repository: check them, then finish the work below.

                <instruction>
                %s
                </instruction>

                """.formatted(task.id(), failure(task), instruction) + EXECUTE_RULES;
    }

    /** A follow-up continues the building session, which already has the task, the plan and what was done. */
    static String followUp(Task task, Run run) {
        return """
                %s replied to your result for task #%d with a follow-up. Make these changes too, on top of what you \
                already did; the work so far is in this repository.

                <follow-up>
                %s
                </follow-up>

                """.formatted(run.requestedByName(), task.id(), run.instruction()) + EXECUTE_RULES;
    }

    private static String failure(Task task) {
        if (task.failureReason() == null) {
            return "unknown reason";
        }
        String detail = task.failureDetail() == null || task.failureDetail().isBlank() ? "" : " (" + task.failureDetail().strip() + ")";
        return task.failureReason().name() + detail;
    }

    /**
     * Shared context is repeated in every topic because each part becomes a task on its own: in a recorded split, "staging:
     * fix X, add Y" gave "staging: fix X" and "staging: add Y" (ADR 0013).
     */
    static String split(String message) {
        return """
                Split the message below into independent development tasks, one per topic. If it is one task, return exactly \
                one topic. Do not invent anything: each topic must contain only what the message says about that topic, in \
                the message language, together with any shared context in the message (such as a project, environment or \
                deadline) that applies to it.

                <message>
                %s
                </message>
                """.formatted(message);
    }
}
