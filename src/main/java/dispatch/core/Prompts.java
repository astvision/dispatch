package dispatch.core;

import dispatch.domain.Attachment;
import dispatch.domain.CuratedPlugins;
import dispatch.domain.Plan;
import dispatch.domain.Run;
import dispatch.domain.Task;
import java.nio.file.Path;
import java.util.List;

/** What agents are told. Dispatch frames the task; the repository's own CLAUDE.md still applies on top. */
final class Prompts {

    /**
     * The language rule is explicit on purpose: a recorded run given "the same language as the task" answered an English
     * task in Dutch. Questions are kept for what cannot be assumed, because a plan with open questions cannot be approved;
     * an assumption is a decision, which the requester can change with a tap without holding the plan up.
     * Plan mode also tells the agent to save its plan to a file, and recorded runs then wrote each plan twice.
     */
    private static final String PLAN_FORMAT = """
            If the task only asks for information (an explanation, a finding, a report, a number) and changes nothing, \
            set result to "answer" and write the full answer in markdown in answer: lead with the conclusion, cite \
            file:line for code, and plan nothing. If it asks for an HTML report, put the complete HTML document in \
            answer instead of markdown, self-contained with its styles inline. If it asks for any change, set result \
            to "plan", plan as usual and leave answer empty.

            Return the plan only as JSON matching the provided schema; do not write it to a plan file or anywhere else. \
            Its fields:
            - understanding: what the task asks for, in your own words
            - findings: relevant facts from the code; for a bug, its root cause
            - steps: the concrete changes you would make, in order
            - risks: what could break or needs attention
            - questions: only questions whose wrong answer would make the change wrong or harmful; otherwise make a \
            reasonable choice, list it under decisions, and leave questions empty. Each question has its text and \
            options: 2 to 4 short likely answers the requester can pick with one tap, each at most 40 characters, in \
            the same language as the question
            - decisions: every choice you made yourself that the requester might want otherwise, such as what an \
            ambiguous word means or where something is shown. Each has its text (a short question), chosen (what the \
            plan assumes) and alternatives (1 to 3 other answers), each answer at most 40 characters, in the same \
            language as the question. The requester approves with your choices or taps another one

            Be brief and exact: short sentences, one fact each, no filler or restating. Say only what you found in the \
            code; mark a guess as a guess.

            Language: write all text in the natural language used inside <task> (a task written in Mongolian gets a \
            Mongolian plan, a task written in English gets an English plan). Keep code identifiers, file paths and \
            commands unchanged.

            Speed matters: investigate only what the plan needs. Search for the code you need instead of reading whole \
            files or directories, stop once you can name the change, and run a build or test only when it is the \
            quickest way to confirm a bug's cause.
            """;

    /** The summary is the chat's result message and the pull request's body: brief, since the diff carries the detail. */
    private static final String EXECUTE_RULES = """
            Rules:
            - Do not commit, push, create branches or open pull requests; Dispatch delivers your changes.
            - Keep the change focused on the approved plan.
            - Run the relevant tests if they are quick to run.
            - Finish with a brief plain-text summary (no Markdown) of at most 5 short lines: what changed (each file in \
            one clause), the test result in one line, and only the assumptions the team must know. For anything you left \
            out that the task could be read to ask for, one line: skipped: <what>, add when <when>. No background, no \
            restating the plan, no list of every detail: the diff shows those. State only what you verified.

            Language: write the summary in the natural language used inside <task> (a task written in Mongolian gets a \
            Mongolian summary, a task written in English gets an English summary). Keep code identifiers, file paths \
            and commands unchanged.

            Speed matters: read only the code your change touches, search instead of reading whole files, run only the \
            tests that cover your change rather than the whole suite, and stop when the work is done: no refactoring, \
            polish or extras beyond it.
            """;

    /**
     * What each agent call is told to use from the dispatch plugin (spec: agent skills). The machine that runs the agent
     * adds it, never the team machine, so a machine without the plugin is never told to use one.
     */
    /** After a Claude Code plan prompt: the official plugins its plan may pick for the execution (spec: plugin picks). */
    static final String PLUGIN_NOTE = pluginNote();

    private static String pluginNote() {
        StringBuilder note = new StringBuilder("\nPlugins you may pick for the execution, in the plan's plugins field. Pick "
                + "only what this task needs, and none when nothing fits:\n");
        for (CuratedPlugins.Entry entry : CuratedPlugins.ALL) {
            note.append("- ").append(entry.name()).append(": when ").append(entry.when()).append('\n');
        }
        return note.toString();
    }

    enum SkillNote {
        PLAN("Load the dispatch:ponytail skill and plan the smallest change that works. If the task reports a bug, use "
                + "the dispatch:systematic-debugging skill to investigate its root cause before you plan; change nothing. Put "
                + "the root cause in findings."),
        EXECUTE("Before you choose how to make the change, load the dispatch:ponytail skill and follow it. Then use the "
                + "dispatch:test-driven-development skill while you build, and the dispatch:verification-before-completion "
                + "skill before your summary."),
        FIX_TEST("Use the dispatch:systematic-debugging skill to find the root cause before you change code, and load the "
                + "dispatch:ponytail skill to fix it where every caller passes; then use the "
                + "dispatch:verification-before-completion skill."),
        FIX_REVIEW("Use the dispatch:receiving-code-review skill: check each finding against the code before you change "
                + "anything."),
        REVIEW("Use the dispatch:code-reviewer skill, then answer only through the structured output.");

        private final String text;

        SkillNote(String text) {
            this.text = text;
        }

        String text() {
            return text;
        }

        /** The note after a prompt, on its own line. */
        String after() {
            return "\n" + text + "\n";
        }

        /** The note before a prompt, followed by a blank line. */
        String before() {
            return text + "\n\n";
        }
    }

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
     * The verify loop's reviewer: a fresh, read-only session that judges the change against what was approved. The worker
     * appends the diff after the last line.
     *
     * @param instruction a follow-up's or retry's instruction; null for the first execution of the approved plan
     */
    static String review(Task task, String planJson, String instruction) {
        String asked = instruction == null ? "" : """

                Since then the team asked for this, which the change must also do:
                <instruction>
                %s
                </instruction>
                """.formatted(instruction);
        return """
                You review a change another agent made in this repository for task #%d from %s. You change nothing: read \
                the code and the diff, and answer only through the structured output.

                <task>
                %s
                </task>

                The approved plan:
                <plan>
                %s
                </plan>
                %s
                Report a finding as "blocking" only when the change is wrong, unsafe, breaks something, or misses part of \
                the plan; everything else is "minor". Answer "ok" when nothing is blocking. At most 20 findings.

                The change to review:
                """.formatted(task.id(), task.requester().name(), task.description(), planJson, asked);
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

    /** @param run the planning run whose instruction is the requester's reply to the task's answer (spec: answers) */
    static String answerFollowUp(Task task, Run run) {
        return """
                The requester replied to your answer for this task. Investigate read-only again where needed and do not \
                modify anything. Nobody can answer questions while you work, so do not ask for confirmation or permission.

                Task #%d from %s:
                <task>
                %s
                </task>

                Your answer:
                <answer>
                %s
                </answer>

                Reply from %s:
                <reply>
                %s
                </reply>

                If the reply asks something, answer it. If it asks for a change, plan that change.
                """.formatted(task.id(), task.requester().name(), task.description(),
                Plan.parse(task.planJson()).answer(), run.requestedByName(), run.instruction()) + PLAN_FORMAT;
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

    /** Where the agent finds the task's files, and which ones it will not find. */
    static String attachments(Path dir, List<Attachment> files) {
        List<String> available = files.stream().filter(file -> !file.tooLarge()).map(Attachment::name).toList();
        List<String> skipped = files.stream().filter(Attachment::tooLarge).map(Attachment::name).toList();
        StringBuilder note = new StringBuilder("\nFiles sent with the task");
        if (!available.isEmpty()) {
            note.append(" are in ").append(dir).append(" (read them there): ").append(String.join(", ", available)).append('.');
        }
        if (!skipped.isEmpty()) {
            note.append(available.isEmpty() ? ": " : " Not available, over 20 MB: ").append(String.join(", ", skipped))
                    .append(available.isEmpty() ? " (not available, over 20 MB)." : ".");
        }
        return note.append('\n').toString();
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

    /**
     * One message of the member's conversation with the assistant (A-1), after a snapshot of their tasks, so the common
     * question "what is going on?" needs no tool call. Who they are and what the assistant may do are in its home's CLAUDE.md.
     */
    static String assistant(String snapshot, String message) {
        return """
                <dispatch-now>
                %s
                </dispatch-now>

                <owner-message>
                %s
                </owner-message>
                """.formatted(snapshot, message);
    }

    /** The same turn again, now on the stronger model, in the same session. */
    static String assistantEscalated() {
        return "Answer the owner's last message again, from the start: read what you need and think it through properly. "
                + "Your answer replaces the previous one.";
    }

    /** The verify loop hands a red test run back to the building session. */
    static String testFailure(String commandLine, String tail) {
        return """
                Dispatch ran the project's tests after your change and they failed:

                $ %s
                <output>
                %s
                </output>

                Find the cause and fix it in this repository. If a test is wrong rather than the code, fix the test and \
                say why. Do not commit. End with a short summary of what you changed.
                """.formatted(commandLine, tail);
    }

    /** The verify loop hands the reviewer's blocking findings back to the building session. */
    static String reviewFindings(List<Review.Finding> blocking) {
        StringBuilder list = new StringBuilder();
        for (Review.Finding finding : blocking) {
            list.append("- ").append(finding.file()).append(finding.line() > 0 ? ":" + finding.line() : "")
                    .append(": ").append(finding.text()).append('\n');
        }
        return """
                A reviewer checked your change against the approved plan and found problems that must be fixed:

                %s
                Fix them in this repository. Do not commit. End with a short summary of what you changed.
                """.formatted(list);
    }
}
