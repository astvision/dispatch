package dispatch.telegram;

import dispatch.core.TaskCommand;
import dispatch.domain.Priority;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * A button's callback_data, both ways: {@link Renderer} builds every button's data from one of these records, and
 * {@link UpdateHandler} reads a pressed button's data back into one. The wire strings never change shape: buttons already
 * sent into chats carry them and must keep working after a deploy.
 */
public sealed interface Callback {

    /** Telegram's limit on a button's callback_data, in bytes; a longer one fails the whole message. */
    int LIMIT = 64;

    /** This button's callback_data. Throws IllegalArgumentException past {@link #LIMIT}, rather than failing the message. */
    String data();

    /** Approve plan {@code planSeq} of a task; the seq makes a superseded plan's button stale. */
    record Approve(long taskId, int planSeq) implements Callback {
        @Override
        public String data() {
            return fit("approve:" + taskId + ":" + planSeq);
        }
    }

    record Reject(long taskId, int planSeq) implements Callback {
        @Override
        public String data() {
            return fit("reject:" + taskId + ":" + planSeq);
        }
    }

    /** A priority button under a status report. */
    record StatusPriority(long taskId, Priority priority) implements Callback {
        @Override
        public String data() {
            return fit("prio:" + taskId + ":" + priority.name());
        }
    }

    /** A button on a draft's prompt. */
    sealed interface Draft extends Callback {
        long draftId();
    }

    /**
     * A project for a draft. The name is cut to fit, and choosing resolves a cut name to the one offered project it begins
     * (TaskService.offeredNamed). Project names are ASCII (config validation), so a char is a byte.
     */
    record DraftProject(long draftId, String name) implements Draft {
        @Override
        public String data() {
            String prefix = "draft:" + draftId + ":p:";
            return fit(prefix + name.substring(0, Math.min(name.length(), LIMIT - prefix.length())));
        }
    }

    record DraftPriority(long draftId, Priority priority) implements Draft {
        @Override
        public String data() {
            return fit("draft:" + draftId + ":prio:" + priority.name());
        }
    }

    /** ✂️ (ask for a split), or the answer to a proposed one: split, or keep the message whole. */
    record DraftSplit(long draftId, Split choice) implements Draft {
        @Override
        public String data() {
            return fit("draft:" + draftId + ":split:" + choice.wire());
        }
    }

    enum Split {
        ASK, YES, NO;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Split of(String wire) {
            for (Split split : values()) {
                if (split.wire().equals(wire)) {
                    return split;
                }
            }
            throw new IllegalArgumentException("no split choice " + wire);
        }
    }

    /** 🗑 not a task. The trailing "x" only fills the fourth part; any value there has always meant discard. */
    record DraftDiscard(long draftId) implements Draft {
        @Override
        public String data() {
            return fit("draft:" + draftId + ":discard:x");
        }
    }

    /** An answer to question {@code question} (1-based) of a plan: one of its options (0-based) or "you decide". */
    record Answer(long taskId, int planSeq, int question, TaskCommand.Choice choice) implements Callback {
        public Answer {
            if (choice instanceof TaskCommand.Choice.Written) {
                throw new IllegalArgumentException("a written answer comes as a reply, never as a button");
            }
        }

        @Override
        public String data() {
            String answer = choice instanceof TaskCommand.Choice.Option option ? String.valueOf(option.index()) : "d";
            return fit("q:" + taskId + ":" + planSeq + ":" + question + ":" + answer);
        }
    }

    /** ✍️ under a plan's question: the requester will write their own answer. */
    record WriteAnswer(long taskId, int planSeq, int question) implements Callback {
        @Override
        public String data() {
            return fit("q:" + taskId + ":" + planSeq + ":" + question + ":w");
        }
    }

    /** A confirm button under the assistant's reply. */
    record AssistantAction(long actionId) implements Callback {
        @Override
        public String data() {
            return fit("as:" + actionId);
        }
    }

    /** The button under an addition offered to its requester. */
    record Addition(long additionId) implements Callback {
        @Override
        public String data() {
            return fit("ad:" + additionId);
        }
    }

    record Merge(long taskId) implements Callback {
        @Override
        public String data() {
            return fit("merge:" + taskId);
        }
    }

    /** A button on a group's link prompt; {@code chatId} is the (negative) group chat's. */
    sealed interface GroupLink extends Callback {
        long chatId();
    }

    /** Link the group to the project at {@code index} of the prompt's list: an index, so a long name still fits. */
    record Link(long chatId, long index) implements GroupLink {
        @Override
        public String data() {
            return fit("link:" + chatId + ":" + index);
        }
    }

    /** Leave the group unlinked. */
    record NoLink(long chatId) implements GroupLink {
        @Override
        public String data() {
            return fit("link:" + chatId + ":-");
        }
    }

    /** An admin's button on a join request. */
    sealed interface JoinChoice extends Callback {
        long requestId();
    }

    /** Add the person to {@code group}. A group may not be named "-" (config validation): that is {@link JoinDeny}'s. */
    record Join(long requestId, String group) implements JoinChoice {
        public Join {
            if (group.equals("-")) {
                throw new IllegalArgumentException("a group named \"-\" would read as a denial");
            }
        }

        @Override
        public String data() {
            return fit("join:" + requestId + ":" + group);
        }
    }

    record JoinDeny(long requestId) implements JoinChoice {
        @Override
        public String data() {
            return fit("join:" + requestId + ":-");
        }
    }

    /**
     * A period or view button under statistics. Both stay words, checked by TaskService.statsPayload, since a view names a
     * group ("group:backend") and old messages may carry groups that are gone.
     */
    record Stats(String period, String view) implements Callback {
        @Override
        public String data() {
            return fit("stats:" + period + ":" + view);
        }
    }

    /** A /help page; a page it does not know is the home screen, so it stays a word too. */
    record Help(String page) implements Callback {
        @Override
        public String data() {
            return fit("help:" + page);
        }
    }

    /** The button {@code data} names, or empty for anything malformed or unknown: an old or forged button. Never throws. */
    static Optional<Callback> parse(String data) {
        if (data == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(decode(data));
        } catch (IllegalArgumentException malformed) {
            // A number or priority that is not one (NumberFormatException is one of these): answered as an unknown button.
            return Optional.empty();
        }
    }

    private static Callback decode(String data) {
        if (data.startsWith("stats:")) {
            // Split in three only: the view "group:<name>" holds a ':' of its own.
            String[] parts = data.split(":", 3);
            return parts.length == 3 ? new Stats(parts[1], parts[2]) : null;
        }
        // split drops trailing empty parts, so "help:task:" has always been help's "task" page, and ":" has no parts at all.
        String[] parts = data.split(":");
        if (parts.length == 0) {
            return null;
        }
        return switch (parts[0]) {
            case "approve" -> parts.length == 3 ? new Approve(number(parts[1]), (int) number(parts[2])) : null;
            case "reject" -> parts.length == 3 ? new Reject(number(parts[1]), (int) number(parts[2])) : null;
            case "prio" -> parts.length == 3 ? new StatusPriority(number(parts[1]), Priority.valueOf(parts[2])) : null;
            case "draft" -> parts.length == 4 ? draft(number(parts[1]), parts[2], parts[3]) : null;
            case "q" -> parts.length == 5 ? answer(parts) : null;
            case "as" -> parts.length == 2 ? new AssistantAction(number(parts[1])) : null;
            case "ad" -> parts.length == 2 ? new Addition(number(parts[1])) : null;
            case "merge" -> parts.length == 2 ? new Merge(number(parts[1])) : null;
            case "link" -> parts.length == 3 ? link(number(parts[1]), parts[2]) : null;
            case "join" -> parts.length == 3 ? join(number(parts[1]), parts[2]) : null;
            case "help" -> parts.length == 2 ? new Help(parts[1]) : null;
            default -> null;
        };
    }

    private static Draft draft(long draftId, String kind, String value) {
        return switch (kind) {
            case "p" -> new DraftProject(draftId, value);
            case "prio" -> new DraftPriority(draftId, Priority.valueOf(value));
            case "split" -> new DraftSplit(draftId, Split.of(value));
            case "discard" -> new DraftDiscard(draftId);
            default -> null;
        };
    }

    private static Callback answer(String[] parts) {
        long taskId = number(parts[1]);
        int planSeq = (int) number(parts[2]);
        int question = (int) number(parts[3]);
        return switch (parts[4]) {
            case "w" -> new WriteAnswer(taskId, planSeq, question);
            case "d" -> new Answer(taskId, planSeq, question, new TaskCommand.Choice.YouDecide());
            default -> new Answer(taskId, planSeq, question, new TaskCommand.Choice.Option((int) number(parts[4])));
        };
    }

    private static GroupLink link(long chatId, String choice) {
        return choice.equals("-") ? new NoLink(chatId) : new Link(chatId, number(choice));
    }

    private static JoinChoice join(long requestId, String choice) {
        return choice.equals("-") ? new JoinDeny(requestId) : new Join(requestId, choice);
    }

    /** As the parser has always read ids: surrounding blanks ignored, a sign allowed. Throws NumberFormatException. */
    private static long number(String text) {
        return Long.parseLong(text.strip());
    }

    private static String fit(String data) {
        int bytes = data.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > LIMIT) {
            throw new IllegalArgumentException("callback data is " + bytes + " bytes, over Telegram's " + LIMIT + ": " + data);
        }
        return data;
    }
}
