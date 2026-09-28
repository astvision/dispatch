package dispatch.core;

/** What a member asks of a task (ADR 0031): a value a channel builds, stores or checks, and {@link TaskCommands} runs. */
public sealed interface TaskCommand {

    record Cancel(long taskId) implements TaskCommand {
    }

    record Retry(long taskId) implements TaskCommand {
    }
}
