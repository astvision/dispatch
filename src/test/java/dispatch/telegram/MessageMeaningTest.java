package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.domain.OutboxKind;
import dispatch.domain.Phase;
import dispatch.telegram.MessageMeaning.Facts;
import dispatch.telegram.MessageMeaning.Meaning;
import org.junit.jupiter.api.Test;

/** What a member's message means, decided from facts alone: no store, no Telegram. */
class MessageMeaningTest {

    /** The facts of one message, each changed from a plain one by the method named after it. */
    private static final class Message {
        private boolean privateChat;
        private Phase topicPhase;
        private String command;
        private boolean addressed = true;
        private OutboxKind.Reply replied;
        private boolean draftTakesContext;
        private boolean assistantOn = true;
        private boolean blank;
        private boolean hasFiles;
        private boolean forBot;
        private boolean mentions;

        Facts facts() {
            return new Facts(privateChat, topicPhase, command, addressed, replied, draftTakesContext, assistantOn, blank, hasFiles,
                    forBot, mentions);
        }

        Message inTopic(Phase phase) { topicPhase = phase; return this; }
        Message command(String name) { command = name; return this; }
        Message addressed(boolean toThisBot) { addressed = toThisBot; return this; }
        Message replying(OutboxKind.Reply means) { replied = means; return this; }
        Message draftTakes(boolean context) { draftTakesContext = context; return this; }
        Message assistant(boolean on) { assistantOn = on; return this; }
        Message blank(boolean isBlank) { blank = isBlank; return this; }
        Message files(boolean has) { hasFiles = has; return this; }
        Message forBot(boolean addressedInGroup) { forBot = addressedInGroup; return this; }
        Message mentions(boolean members) { mentions = members; return this; }
    }

    private static Message privately() {
        Message message = new Message();
        message.privateChat = true;
        return message;
    }

    private static Message inGroup() {
        return new Message();
    }

    private static Meaning meaningOf(Message message) {
        return MessageMeaning.of(message.facts());
    }

    private static boolean ignoresReply(Message message) {
        return MessageMeaning.ignoresReply(message.facts());
    }

    @Test
    void insideATasksTopicAnythingThatIsNotACommandIsAboutThatTask() {
        assertEquals(Meaning.TOPIC_FOLLOW_UP, meaningOf(privately().inTopic(Phase.COMPLETED)));
        assertEquals(Meaning.TOPIC_FOLLOW_UP, meaningOf(privately().inTopic(Phase.FAILED)));
        assertEquals(Meaning.TOPIC_CORRECTION, meaningOf(privately().inTopic(Phase.AWAITING_APPROVAL)));
        assertEquals(Meaning.ANSWER_QUESTION, meaningOf(privately().inTopic(Phase.AWAITING_APPROVAL).replying(OutboxKind.Reply.ANSWER)));
        assertEquals(Meaning.COMMAND, meaningOf(privately().inTopic(Phase.COMPLETED).command("status")),
                "a known command in a topic is still a command");
        assertEquals(Meaning.TOPIC_CORRECTION, meaningOf(privately().inTopic(Phase.PLANNING).command("api")),
                "an unknown one, such as /api/login fails, is about the task");
    }

    @Test
    void aReplyToOneOfTheBotsMessagesMeansWhatThatMessageSays() {
        assertEquals(Meaning.ANSWER_QUESTION, meaningOf(privately().replying(OutboxKind.Reply.ANSWER)));
        assertEquals(Meaning.FOLLOW_UP, meaningOf(inGroup().replying(OutboxKind.Reply.FOLLOW_UP)));
        assertEquals(Meaning.CORRECTION, meaningOf(inGroup().replying(OutboxKind.Reply.CORRECTION)));
        assertEquals(Meaning.ADD_TO_DRAFT, meaningOf(privately().replying(OutboxKind.Reply.DRAFT_CONTEXT).draftTakes(true)));
        assertEquals(Meaning.TASK_FROM_PROMPT, meaningOf(privately().replying(OutboxKind.Reply.TASK)));
    }

    @Test
    void aReplyToAClosedDraftOrAnotherBotMessageKeepsTheMessagesOrdinaryMeaningAndIsLogged() {
        Message closedDraft = privately().replying(OutboxKind.Reply.DRAFT_CONTEXT).draftTakes(false);

        assertEquals(Meaning.ASSISTANT_TURN, meaningOf(closedDraft));
        assertTrue(ignoresReply(closedDraft));
        assertEquals(Meaning.ADDITION, meaningOf(inGroup().replying(OutboxKind.Reply.NOTHING)));
        assertTrue(ignoresReply(inGroup().replying(OutboxKind.Reply.NOTHING)));
        assertFalse(ignoresReply(inGroup()), "a reply to a person's message is nobody's business here");
        assertFalse(ignoresReply(privately().replying(OutboxKind.Reply.TASK)));
    }

    @Test
    void aPrivateMessageIsAnAssistantTurnOrATaskAndAGroupMessageATaskAMentionOrAnAddition() {
        assertEquals(Meaning.ASSISTANT_TURN, meaningOf(privately()));
        assertEquals(Meaning.PRIVATE_TASK, meaningOf(privately().assistant(false)));
        assertEquals(Meaning.PRIVATE_TASK, meaningOf(privately().files(true)), "the assistant cannot see files");
        assertEquals(Meaning.PRIVATE_TASK, meaningOf(privately().blank(true)));
        assertEquals(Meaning.GROUP_TASK, meaningOf(inGroup().forBot(true)));
        assertEquals(Meaning.MENTION_TASKS, meaningOf(inGroup().mentions(true)));
        assertEquals(Meaning.ADDITION, meaningOf(inGroup()));
    }

    @Test
    void aDraftReplyAndATaskPromptCountOnlyInThePrivateChat() {
        assertEquals(Meaning.ADDITION, meaningOf(inGroup().replying(OutboxKind.Reply.DRAFT_CONTEXT).draftTakes(true)));
        assertEquals(Meaning.ADDITION, meaningOf(inGroup().replying(OutboxKind.Reply.TASK)));
    }

    @Test
    void commandsAreTheirOwnMeaning() {
        assertEquals(Meaning.COMMAND, meaningOf(privately().command("status")));
        assertEquals(Meaning.UNADDRESSED, meaningOf(inGroup().command("status").addressed(false)));
        assertEquals(Meaning.PRIVATE_ONLY, meaningOf(inGroup().command("cancel")));
        assertEquals(Meaning.COMMAND, meaningOf(privately().command("cancel")));
        assertEquals(Meaning.RESET_ASSISTANT, meaningOf(privately().command("new")));
        assertEquals(Meaning.PRIVATE_TASK, meaningOf(privately().command("new").assistant(false)));
        assertEquals(Meaning.IGNORED_COMMAND, meaningOf(inGroup().command("new")));
    }

    @Test
    void anUnknownCommandIsAReplyToATaskOrATaskOrNothing() {
        assertEquals(Meaning.CORRECTION, meaningOf(inGroup().command("api").replying(OutboxKind.Reply.CORRECTION)));
        assertEquals(Meaning.PRIVATE_TASK, meaningOf(privately().command("api")));
        assertEquals(Meaning.IGNORED_COMMAND, meaningOf(inGroup().command("api")));
        assertTrue(ignoresReply(inGroup().command("api").replying(OutboxKind.Reply.TASK)));
    }
}
