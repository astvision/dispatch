package dispatch.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.domain.Draft;
import dispatch.domain.DraftStatus;
import dispatch.domain.OutboxKind;
import dispatch.domain.Phase;
import dispatch.domain.Requester;
import dispatch.domain.Task;
import dispatch.store.Additions;
import dispatch.store.Drafts;
import dispatch.store.Outbox;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Additions: more instructions someone writes in a linked group as a reply to the message a task came from. The requester
 * of each task that message gave, or of its draft not given yet, is offered the text privately with one button; nothing
 * changes until they tap it.
 */
public final class GroupAdditions {

    /** The most of an addition an offer shows; with its other lines it stays within one Telegram message (4096). */
    private static final int SHOWN_TEXT = 3000;

    /** What one requester is offered: a task, or a draft (taskId null) that is not a task yet. */
    private record Offer(String originRef, Requester requester, Long taskId, String title) {
    }

    /** How a tap on an addition's button ended; the chat says it in words. */
    public enum Outcome {
        /** Applied: its task's waiting plan is revised with it, or its finished task followed up. */
        DONE,
        /** Its task is being planned or carried out: nothing changed, and the button works again later. */
        BUSY,
        /** Its task refused it and said why under the offer, e.g. nothing was carried out to follow up; the button stays. */
        REFUSED,
        /** It is still a draft, not given as a task yet: nothing changed, and the button works once it is one. */
        NOT_YET,
        /** Its draft was split into parts: each part was offered it again, to tap the right one; this one is used up. */
        SPLIT,
        /** Its task was rejected or cancelled, or its draft discarded or expired, and will never take it: used up. */
        CLOSED,
        /** Applied before, or not this member's to apply. */
        USED
    }

    private final TaskService tasks;
    private final Clock clock;
    private final Runnable wakeOutbox;
    private final String requestedBy;

    /** @param requestedBy what ends an applied addition's text before its author's name, as a mentioned task's text ends */
    public GroupAdditions(TaskService tasks, Clock clock, Runnable wakeOutbox, String requestedBy) {
        this.tasks = tasks;
        this.clock = clock;
        this.wakeOutbox = wakeOutbox;
        this.requestedBy = requestedBy;
    }

    /**
     * Offers {@code text}, which {@code author} wrote in the group {@code chatRef} as {@code replyRef}, a reply to
     * {@code repliedRef}, to the requester of each task still open and each open draft that message gave, except the
     * author's own: they say more to their own plan or result privately. The group sees 👀 on the reply once an offer
     * reached its requester, as it does for a task given there. Text only: files stay in the group. A text longer than
     * {@link #SHOWN_TEXT}, or none at all, comes without a button, so nothing is applied that was not read whole.
     *
     * @param authorRef who wrote it
     * @param author    the writer's first name, as the group calls them
     * @param withFiles the reply carried a photo or a document, which the offer names
     * @return false when there was no one to offer it to
     */
    public boolean offer(Tx tx, String repliedRef, String replyRef, String chatRef, String authorRef, String author, String text,
                         boolean withFiles) {
        List<Offer> offers = offersFrom(tx, repliedRef).stream().filter(offer -> !offer.requester().ref().equals(authorRef)).toList();
        send(tx, offers, author, text, withFiles, chatRef, replyRef);
        return !offers.isEmpty();
    }

    /**
     * Each task still open and each open draft that message {@code messageRef} gave, or that someone gave by replying to it
     * with a mention (G-1b, G-1c).
     */
    private static List<Offer> offersFrom(Tx tx, String messageRef) {
        List<Offer> offers = new ArrayList<>();
        List<Task> given = new ArrayList<>(Tasks.fromMessage(tx, messageRef));
        List<Draft> open = new ArrayList<>(Drafts.openFromMessage(tx, messageRef));
        for (Draft draft : Drafts.fromSource(tx, messageRef)) {
            if (draft.taskId() == null) {
                open.add(draft);
            } else {
                Tasks.find(tx, draft.taskId()).ifPresent(given::add);
            }
        }
        for (Task task : given) {
            if (task.phase() != Phase.REJECTED && task.phase() != Phase.CANCELLED) {
                offers.add(new Offer(task.originRef(), task.requester(), task.id(), task.title()));
            }
        }
        for (Draft draft : open) {
            offers.add(new Offer(draft.originRef(), new Requester(draft.requesterRef(), draft.requesterName()), null,
                    TaskService.title(draft.description())));
        }
        return offers;
    }

    /**
     * Sends each requester their offer, privately; one the private chat refuses goes to {@code fallbackChatRef} under
     * {@code fallbackReplyToRef} instead, which also gets the 👀 once it arrived. Both null for no fallback and no 👀.
     */
    private void send(Tx tx, List<Offer> offers, String author, String text, boolean withFiles, String fallbackChatRef,
                      String fallbackReplyToRef) {
        Instant now = clock.instant();
        boolean tooLong = text.length() > SHOWN_TEXT;
        for (Offer offer : offers) {
            ObjectNode payload = Json.object().put("title", offer.title()).put("by", author).put("text", tooLong ? cut(text) : text)
                    .put("requester", GroupAcks.firstName(offer.requester().name()));
            if (tooLong) {
                payload.put("tooLong", true);
            } else if (!text.isEmpty()) {
                payload.put("additionId", Additions.insert(tx, offer.originRef(), offer.requester().ref(), author, text, now));
            }
            if (withFiles) {
                payload.put("files", true);
            }
            if (offer.taskId() != null) {
                payload.put("taskId", offer.taskId());
            }
            // The private chat's id is its user's: the requester's own ref names it.
            Outbox.enqueueWithFallback(tx, offer.taskId(), OutboxKind.ADDITION_OFFERED, offer.requester().ref(), null, fallbackChatRef,
                    fallbackReplyToRef, payload, now);
        }
        if (!offers.isEmpty()) {
            tx.afterCommit(wakeOutbox);
        }
    }

    /**
     * {@code who} tapped addition {@code additionId}'s button, on {@code messageRef} in their private chat {@code chatRef}:
     * what the task says about it goes under that message.
     */
    public Outcome apply(Tx tx, Requester who, long additionId, String messageRef, String chatRef) {
        Optional<Additions.Addition> unused = Additions.findUnused(tx, additionId, who.ref());
        if (unused.isEmpty()) {
            return Outcome.USED;
        }
        Additions.Addition addition = unused.get();
        Optional<Task> given = Tasks.findByOrigin(tx, addition.originRef());
        if (given.isEmpty()) {
            // Still its draft, which the button follows once it is given as a task, or split into parts, each offered it
            // again; a draft discarded or expired never will be a task.
            Optional<DraftStatus> draft = Drafts.statusByOrigin(tx, addition.originRef());
            if (draft.equals(Optional.of(DraftStatus.OPEN))) {
                return Outcome.NOT_YET;
            }
            if (draft.equals(Optional.of(DraftStatus.SPLIT))) {
                send(tx, offersFrom(tx, addition.originRef()), addition.author(), addition.text(), false, null, null);
                Additions.markUsed(tx, additionId, clock.instant());
                return Outcome.SPLIT;
            }
            return closed(tx, additionId);
        }
        Task task = given.get();
        String instruction = addition.text() + "\n\n" + requestedBy + " " + addition.author();
        return switch (task.phase()) {
            case AWAITING_APPROVAL -> applied(tx, additionId,
                    tasks.correctLatest(tx, who, task.id(), instruction, messageRef, chatRef) == CorrectResult.CORRECTED);
            case COMPLETED, FAILED -> {
                // A merged task's follow-up becomes a new task: taken all the same.
                FollowUpResult result = tasks.followUp(tx, who, task.id(), instruction, messageRef, chatRef);
                yield applied(tx, additionId, result == FollowUpResult.QUEUED || result == FollowUpResult.NEW_TASK);
            }
            case PLANNING, EXECUTING -> Outcome.BUSY;
            case REJECTED, CANCELLED -> closed(tx, additionId);
        };
    }

    private Outcome closed(Tx tx, long additionId) {
        Additions.markUsed(tx, additionId, clock.instant());
        return Outcome.CLOSED;
    }

    /** DONE, and used up, when the task took it; otherwise the task has already said why under the offer. */
    private Outcome applied(Tx tx, long additionId, boolean taken) {
        if (!taken) {
            return Outcome.REFUSED;
        }
        Additions.markUsed(tx, additionId, clock.instant());
        return Outcome.DONE;
    }

    /** The first {@link #SHOWN_TEXT} characters and an ellipsis, never splitting an emoji's surrogate pair. */
    private static String cut(String text) {
        int end = Character.isHighSurrogate(text.charAt(SHOWN_TEXT - 1)) ? SHOWN_TEXT - 1 : SHOWN_TEXT;
        return text.substring(0, end) + "…";
    }
}
