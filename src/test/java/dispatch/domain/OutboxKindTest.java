package dispatch.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Pins each trait's kinds: a kind that gains or loses one changes what the sender and the reply routing do. */
class OutboxKindTest {

    @Test
    void theMessagesThatSayHowATaskEndedRenameItsTopic() {
        assertEquals(EnumSet.of(OutboxKind.TASK_COMPLETED, OutboxKind.TASK_COMPLETED_SHORT, OutboxKind.TASK_FAILED,
                OutboxKind.TASK_FAILED_SHORT, OutboxKind.TASK_REJECTED, OutboxKind.TASK_CANCELLED, OutboxKind.ANSWER_READY),
                kinds(OutboxKind::endsTask));
    }

    @Test
    void aReplyToAResultIsAFollowUpButNotToARejectionOrACancellation() {
        assertEquals(EnumSet.of(OutboxKind.TASK_COMPLETED, OutboxKind.TASK_COMPLETED_SHORT, OutboxKind.TASK_FAILED,
                OutboxKind.TASK_FAILED_SHORT, OutboxKind.ANSWER_READY), kinds(OutboxKind::replyIsFollowUp));
    }

    @Test
    void theProjectPromptAndAnOfferedAdditionAnswerSomethingGivenInAGroup() {
        assertEquals(EnumSet.of(OutboxKind.DRAFT_PROMPT, OutboxKind.ADDITION_OFFERED), kinds(OutboxKind::answersGroupMessage));
    }

    @Test
    void aTopicAndAReactionAreNotChatMessages() {
        assertEquals(EnumSet.of(OutboxKind.TOPIC_CREATE), kinds(kind -> kind.form() == OutboxKind.Form.TOPIC));
        assertEquals(EnumSet.of(OutboxKind.GROUP_REACTION), kinds(kind -> kind.form() == OutboxKind.Form.REACTION));
    }

    @Test
    void aReplyToOneOfTheBotsMessagesMeansWhatItsKindSays() {
        assertEquals(EnumSet.of(OutboxKind.PLAN_QUESTION, OutboxKind.PLAN_ANSWER_PROMPT), meaning(OutboxKind.Reply.ANSWER));
        assertEquals(EnumSet.of(OutboxKind.PLAN_READY, OutboxKind.PLAN_EDIT_PROMPT), meaning(OutboxKind.Reply.CORRECTION));
        assertEquals(EnumSet.of(OutboxKind.DRAFT_PROMPT), meaning(OutboxKind.Reply.DRAFT_CONTEXT));
        assertEquals(EnumSet.of(OutboxKind.TASK_PROMPT), meaning(OutboxKind.Reply.TASK));
        assertEquals(kinds(OutboxKind::replyIsFollowUp), meaning(OutboxKind.Reply.FOLLOW_UP));
    }

    private static Set<OutboxKind> meaning(OutboxKind.Reply reply) {
        return kinds(kind -> kind.replyMeans() == reply);
    }

    private static Set<OutboxKind> kinds(Predicate<OutboxKind> trait) {
        EnumSet<OutboxKind> kinds = EnumSet.noneOf(OutboxKind.class);
        Arrays.stream(OutboxKind.values()).filter(trait).forEach(kinds::add);
        return kinds;
    }
}
