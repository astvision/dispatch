package dispatch.core;

import dispatch.domain.Priority;
import java.util.OptionalInt;

/** What a member asks of a task (ADR 0031): a value a channel builds, stores or checks, and {@link TaskCommands} runs. */
public sealed interface TaskCommand {

    record Cancel(long taskId) implements TaskCommand {
    }

    record Retry(long taskId) implements TaskCommand {
    }

    /** A correction of plan {@code planSeq}, or of whichever plan waits now when it names none (a topic message, an addition). */
    record Correct(long taskId, OptionalInt planSeq, String text) implements TaskCommand {
    }

    /** More work on a finished task (ADR 0006); a merged task's becomes a new task, given from {@code origin}. */
    record FollowUp(long taskId, String text, Origin origin) implements TaskCommand {
    }

    /** An answer to question {@code question} (1-based) of plan {@code planSeq} (G-1d). */
    record Answer(long taskId, int planSeq, int question, Choice choice) implements TaskCommand {
    }

    /** What an answer says: the member's words, one of the question's options, or the agent's own choice. */
    sealed interface Choice {

        record Written(String text) implements Choice {
        }

        /** 0-based, as the buttons carry it. */
        record Option(int index) implements Choice {
        }

        record YouDecide() implements Choice {
        }
    }

    /** A task on a project the requester's groups have (ADR 0012), given once per origin. */
    record Give(String project, String text, Priority priority, Origin origin) implements TaskCommand {
    }
}
