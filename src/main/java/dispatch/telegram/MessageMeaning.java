package dispatch.telegram;

import dispatch.domain.OutboxKind;
import dispatch.domain.Phase;
import java.util.Set;

/**
 * What a member's chat message means, decided from facts alone: the chat, the topic, the command, what the message replies
 * to and what the chat is set up to do. {@link UpdateHandler} gathers the facts, reads the meaning here and carries it out,
 * so every reading a message can have is listed in one place and tried in one order.
 */
final class MessageMeaning {

    static final Set<String> COMMANDS = Set.of("task", "status", "history", "stats", "cancel", "retry", "teleport", "worker",
            "manage", "new", "projects", "help", "start");
    /** Commands about a member's own tasks and computers, answered only in their private chat. */
    static final Set<String> PRIVATE_COMMANDS = Set.of("cancel", "retry", "teleport", "worker", "manage");

    enum Meaning {
        /** A reply to a plan's question, inside a topic or not. */
        ANSWER_QUESTION,
        /** Inside a finished task's topic: more work on that task. */
        TOPIC_FOLLOW_UP,
        /** Inside an unfinished task's topic: a correction of its plan. */
        TOPIC_CORRECTION,
        /** A reply to the prompt of the writer's own open draft. */
        ADD_TO_DRAFT,
        /** A reply to /help's ✍️ prompt: a task by explicit intent. */
        TASK_FROM_PROMPT,
        /** A reply to a task's result. */
        FOLLOW_UP,
        /** A reply to a plan, or to ✏️'s prompt. */
        CORRECTION,
        /** A private conversation with the assistant. */
        ASSISTANT_TURN,
        /** Anything else written privately: a task to give (ADR 0012). */
        PRIVATE_TASK,
        /** A group message addressed to the bot. */
        GROUP_TASK,
        /** A group message mentioning members. */
        MENTION_TASKS,
        /** Any other group message: an addition to a task given there, when it is one. */
        ADDITION,
        /** A command for another bot. */
        UNADDRESSED,
        /** /new where there is a conversation to restart. */
        RESET_ASSISTANT,
        /** A private-only command written in a group. */
        PRIVATE_ONLY,
        /** One of the bot's commands, addressed to it. */
        COMMAND,
        /** A group message Telegram marks as a command but the bot does not know: nothing to do. */
        IGNORED_COMMAND
    }

    /**
     * @param topicPhase       the phase of the task whose private topic the message is in; null outside one
     * @param command          the leading /word, null for none
     * @param replied          what the bot's message this replies to means by a reply; null when not a reply to one
     * @param draftTakesContext whether the replied draft prompt's draft is still the writer's open one
     * @param forBot           a group message addressed to the bot by mention or reply
     */
    record Facts(boolean privateChat, Phase topicPhase, String command, boolean addressed, OutboxKind.Reply replied, boolean draftTakesContext, boolean assistantOn, boolean blank, boolean hasFiles,
            boolean forBot, boolean mentions) {

        boolean inTopic() {
            return topicPhase != null;
        }

        boolean isCommand() {
            return command != null;
        }

        boolean knownCommand() {
            return command != null && COMMANDS.contains(command);
        }

        boolean repliedMeans(OutboxKind.Reply meaning) {
            return replied == meaning;
        }
    }

    private MessageMeaning() {
    }

    static Meaning of(Facts facts) {
        if (facts.inTopic() && !facts.knownCommand()) {
            // Inside a task's own topic, anything that is not a command is about that task.
            if (facts.repliedMeans(OutboxKind.Reply.ANSWER)) {
                return Meaning.ANSWER_QUESTION;
            }
            return facts.topicPhase() == Phase.COMPLETED || facts.topicPhase() == Phase.FAILED
                    ? Meaning.TOPIC_FOLLOW_UP
                    : Meaning.TOPIC_CORRECTION;
        }
        if (!facts.isCommand()) {
            return plainMessage(facts);
        }
        if (!facts.addressed()) {
            return Meaning.UNADDRESSED;
        }
        if (facts.command().equals("new") && facts.privateChat() && facts.assistantOn()) {
            // Only where there is a conversation to restart; anywhere else /new is any unknown command.
            return Meaning.RESET_ASSISTANT;
        }
        if (!facts.privateChat() && PRIVATE_COMMANDS.contains(facts.command())) {
            return Meaning.PRIVATE_ONLY;
        }
        if (facts.knownCommand() && !facts.command().equals("new")) {
            return Meaning.COMMAND;
        }
        // Telegram marks any leading "/word" as a command, so "/api/login fails too" lands here: a reply to a task keeps
        // its meaning, otherwise it is a task when written privately and nothing in a group.
        Meaning aboutATask = replyToTask(facts);
        if (aboutATask != null) {
            return aboutATask;
        }
        return facts.privateChat() ? Meaning.PRIVATE_TASK : Meaning.IGNORED_COMMAND;
    }

    private static Meaning plainMessage(Facts facts) {
        if (facts.privateChat() && facts.repliedMeans(OutboxKind.Reply.DRAFT_CONTEXT) && facts.draftTakesContext()) {
            return Meaning.ADD_TO_DRAFT;
        }
        if (facts.privateChat() && facts.repliedMeans(OutboxKind.Reply.TASK)) {
            return Meaning.TASK_FROM_PROMPT;
        }
        Meaning aboutATask = replyToTask(facts);
        if (aboutATask != null) {
            return aboutATask;
        }
        if (facts.privateChat()) {
            // The assistant cannot see files, and has nothing to say to an empty message: both make a draft directly.
            return facts.assistantOn() && !facts.blank() && !facts.hasFiles() ? Meaning.ASSISTANT_TURN : Meaning.PRIVATE_TASK;
        }
        if (facts.forBot()) {
            return Meaning.GROUP_TASK;
        }
        return facts.mentions() ? Meaning.MENTION_TASKS : Meaning.ADDITION;
    }

    /** A reply to a plan's question, a task's result or a plan; null for anything else. */
    private static Meaning replyToTask(Facts facts) {
        if (facts.replied() == null) {
            return null;
        }
        return switch (facts.replied()) {
            case ANSWER -> Meaning.ANSWER_QUESTION;
            case FOLLOW_UP -> Meaning.FOLLOW_UP;
            case CORRECTION -> Meaning.CORRECTION;
            case DRAFT_CONTEXT, TASK, NOTHING -> null;
        };
    }

    /**
     * Whether the message replies to one of the bot's messages and that reply means nothing here, so the message keeps
     * its ordinary meaning: worth a log line, since the member probably expected otherwise.
     */
    static boolean ignoresReply(Facts facts) {
        if (facts.replied() == null) {
            return false;
        }
        return switch (of(facts)) {
            case ASSISTANT_TURN, PRIVATE_TASK, GROUP_TASK, MENTION_TASKS, ADDITION, IGNORED_COMMAND -> true;
            default -> false;
        };
    }
}
