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

    private static Set<OutboxKind> kinds(Predicate<OutboxKind> trait) {
        EnumSet<OutboxKind> kinds = EnumSet.noneOf(OutboxKind.class);
        Arrays.stream(OutboxKind.values()).filter(trait).forEach(kinds::add);
        return kinds;
    }
}
