package dispatch.core;

import dispatch.Text;

/** What a task command did (ADR 0031); the channel that ran it words its reply from this. */
public sealed interface CommandResult {

    /**
     * It took effect, and its news is written.
     *
     * @param toldActor the news already reached the actor, in their task's topic: false only for an admin who cancelled
     *                  someone else's task, whom the channel answers where they acted
     */
    record Done(long taskId, boolean toldActor) implements CommandResult {
    }

    /** A new task: one given, or a merged task's follow-up. A task given again from the same origin is this, and writes nothing. */
    record Created(long taskId) implements CommandResult {
    }

    /** Nothing to change: the priority it already had. Nothing written. */
    record Unchanged(long taskId) implements CommandResult {
    }

    /** Nothing written. {@code words} is this refusal's one wording, which every channel shows (ADR 0031). */
    record Refused(Refusal reason, Text words) implements CommandResult {
    }
}
